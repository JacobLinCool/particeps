/**
 * The two things a unit adapter has to be, checked over every point of every lattice.
 *
 * 1. Round-trip. A value must not change by passing through the control. If 10 Hz stores 100 000 µs
 *    and reads back as 10 Hz, good; reading back as 9 Hz is a defect, not a rounding detail — and
 *    it is the defect `Math.round` on the sampling period would have introduced on 94 of 200 rates.
 *
 * 2. Encodable. Whatever the control can produce has to be something the canonical encoder can
 *    write. Every stored value is an integer inside the schema's bound, because `integer()` emits
 *    `-?(0|[1-9][0-9]*)` and anything else is a file the app refuses. The one exception is
 *    `minimum_displacement_meters`, a Kotlin `Float` written by `formatFloat`, where the stored
 *    value is a float32 and `formatFloat` must round-trip back to the number the box showed.
 *
 * The lattice is `min, min+step, …, max` for a control with a box, and the ladder for one without.
 */

import { describe, expect, it } from 'vitest';
import { formatFloat } from '../src/lib/adc/canonical';
import { en } from '../src/lib/i18n/en';
import { zhTW } from '../src/lib/i18n/zh-TW';
import { SCALE_BOUNDS, scales, type Scale, type ScaleKey } from '../src/routes/researcher/scales';
import { units } from '../src/routes/researcher/units';

const CATALOGUES = [
  ['en', en, 'en'],
  ['zh-TW', zhTW, 'zh-TW']
] as const;

/** Every value the control can be moved to, in control space. */
function lattice(scale: Scale): number[] {
  if (scale.ladder) return [...scale.ladder];
  const digits = String(scale.step).split('.')[1]?.length ?? 0;
  const points: number[] = [];
  for (let h = scale.min; h <= scale.max + scale.step / 2; h += scale.step) {
    points.push(digits > 0 ? Number(h.toFixed(digits)) : h);
  }
  return points;
}

for (const [name, catalogue, locale] of CATALOGUES) {
  const S = scales(catalogue, units(catalogue, locale));
  const keys = Object.keys(S) as ScaleKey[];

  describe(`scales (${name})`, () => {
    it('offers a box only where one human unit names both ends', () => {
      // The rule is not a judgement per control: it follows from the bounds. These four are hertz
      // at both ends, seconds at both ends, metres at both ends, hertz at both ends.
      const boxed = keys.filter((key) => S[key].box);
      expect(boxed.sort()).toEqual(
        [
          'maximum_report_latency_us',
          'minimum_displacement_meters',
          'sampling_period_us',
          'trajectory_sampling_hz'
        ].sort()
      );
      // A control with no box has only its slider, so every position it can stop on must be a
      // value a person would say. Asserting a ladder would assert the mechanism; the property is
      // that the reachable set is small enough to walk and no two stops read the same. A linear
      // integer step satisfies it as well as a ladder does — the quota steps by 256 MiB — and a
      // formatter that rounds fails it, because rounding is what collapses two stops into one
      // string.
      for (const key of keys) {
        if (S[key].box) continue;
        const stops = lattice(S[key]);
        expect(stops.length, `${key} has too many stops to arrow through`).toBeLessThanOrEqual(64);
        const shown = stops.map((human) => S[key].format(human));
        expect(new Set(shown).size, `${key} renders two stops the same`).toBe(stops.length);
      }
    });

    it('names the unit beside every box', () => {
      for (const key of keys) {
        if (S[key].box) expect(S[key].affix, key).not.toBe('');
      }
    });

    for (const key of keys) {
      const scale = S[key];

      /**
       * The lattice is walked in plain JavaScript and asserted once at the end, rather than through
       * one `expect` per value. Displacement alone has 100 001 stops, and four matchers apiece is
       * 400 000 matcher contexts — enough to pass on a developer's machine in a second and time out
       * on a CI runner at five. The coverage is identical; only the accounting is cheap.
       */
      it(`${key} round-trips every value the control can produce`, () => {
        const broken: string[] = [];
        for (const human of lattice(scale)) {
          const back = scale.toHuman(scale.toStored(human));
          if (back !== human) broken.push(`${human} came back as ${back}`);
        }
        expect(broken.slice(0, 8), `${key}: ${broken.length} of its stops do not round-trip`).toEqual(
          []
        );
      });

      it(`${key} stores something the encoder can write`, () => {
        const [low, high] = SCALE_BOUNDS[key];
        const broken: string[] = [];
        for (const human of lattice(scale)) {
          const stored = scale.toStored(human);
          if (stored < low || stored > high) {
            broken.push(`${human} stores ${stored}, outside ${low}..${high}`);
          } else if (key === 'minimum_displacement_meters') {
            // A Float, and the shortest decimal that round-trips to it is what the file carries.
            if (Math.fround(stored) !== stored) broken.push(`${human} stores a non-float32`);
            else if (Number(formatFloat(stored)) !== human) {
              broken.push(`${human} writes as ${formatFloat(stored)}`);
            }
          } else if (!Number.isInteger(stored)) {
            broken.push(`${human} stores ${stored}, which is not an integer`);
          }
        }
        expect(broken.slice(0, 8), `${key}: ${broken.length} of its stops are unencodable`).toEqual(
          []
        );
      });

      it(`${key} can reach every value on its chip row`, () => {
        const reachable = new Set(lattice(scale));
        for (const preset of scale.presets) expect(reachable.has(preset), `${key} ${preset}`).toBe(true);
      });
    }

    it('reaches the round values the old sliders could not', () => {
      // Each of these was measured against the log slider it replaces: 720 min landed on 717,
      // 30 000 ms on 30 103, 600 000 ms on 599 489, 512 MiB on 535 218 657 B, 720 min on 715, and
      // the upload interval's 12 hours on 722 min.
      const wanted: Partial<Record<ScaleKey, number[]>> = {
        poll_interval_minutes: [120, 720],
        interval_millis: [30_000, 120_000],
        maximum_batch_delay_millis: [600_000],
        duration_hours: [504, 2_160, 4_320],
        maximum_local_bytes: [512 * 1_024 * 1_024],
        upload_interval_minutes: [720]
      };
      // In stored units, because that is what the file carries and what was measured.
      for (const [key, values] of Object.entries(wanted) as [ScaleKey, number[]][]) {
        const reachable = new Set(lattice(S[key]).map((human) => S[key].toStored(human)));
        for (const value of values) expect(reachable.has(value), `${key} ${value}`).toBe(true);
      }
    });

    it('reads the sampling period the way the phone will', () => {
      // `CollectorSummary.kt:47` is `(1_000_000.0 / samplingPeriodUs).toInt().coerceAtLeast(1)`.
      const scale = S.sampling_period_us;
      for (let hertz = 1; hertz <= 200; hertz++) {
        const stored = scale.toStored(hertz);
        expect(Math.max(1, Math.trunc(1_000_000 / stored))).toBe(hertz);
      }
      expect(scale.toStored(200)).toBe(5_000);
      expect(scale.toStored(1)).toBe(1_000_000);
    });

    it('says a minute where a minute is what 60 seconds is', () => {
      // The one place the humanised echo survives beside a box: it names a different unit, which is
      // the whole reason the affix cannot be left to it.
      expect(S.maximum_report_latency_us.format(60)).toBe(units(catalogue, locale).minutes(1));
      expect(S.maximum_report_latency_us.toStored(0.1)).toBe(100_000);
    });
  });
}
