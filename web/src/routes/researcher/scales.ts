/**
 * The one place the stored unit exists.
 *
 * A control's *control space* is the unit a researcher states the value in. The unit the schema
 * stores is a boundary detail: converted here, on the way in and on the way out, and never
 * rendered and never typed. A box showing `100000` beside `10 Hz` asked a researcher to read a
 * number that means nothing to them and, worse, to type in it; a box showing a bare `24` next to
 * `1 day` did not say the 24 was hours. Both are the same defect and this module is the fix.
 *
 * Which of two shapes a control takes is not a judgement per control. It follows from one fact
 * about the control's own bounds:
 *
 *   A — box.    One human unit names *both ends* of the legal range. The box edits in that unit,
 *               the unit word sits beside it, and an exact off-ladder value is typable. 1–200 Hz
 *               is hertz at both ends; 0–60 s is seconds at both ends.
 *   B — no box.  The legal range crosses a unit boundary, so no single word names both ends. There
 *               is nothing honest to put in a box, so there is no box: slider, chips, and a
 *               humanised readout. 8 MiB–8 GiB is the case that was already fixed this way;
 *               1 hour–1 year is the same case and was missed.
 *
 * Shape B's slider indexes a `ladder` rather than running over a continuum, because a log slider
 * cannot be dragged to a round number — measured, the old one reached 716, 722 and 727 minutes but
 * not 720. The ladder is what lets "can the slider land on 12 hours" be answered with yes.
 *
 * Two invariants hold for every adapter here, and `tests/scales.spec.ts` checks both over every
 * point of every lattice:
 *
 *   1. Round-trip.  `toHuman(toStored(h)) === h`. A value does not change by passing through a box.
 *   2. Encodable.   `toStored(h)` is an integer inside `BOUNDS`, so the canonical encoder's
 *                   `-?(0|[1-9][0-9]*)` still matches. The one exception is
 *                   Location displacement is presented in metres and stored as integer mm.
 */

import {
  BOUNDS,
  MAXIMUM_LOCAL_BYTES,
  MINIMUM_LOCAL_BYTES,
  UPLOAD_MAXIMUM_INTERVAL_MINUTES,
  UPLOAD_MINIMUM_INTERVAL_MINUTES
} from '$lib/particeps/types';
import { LADDERS, PRESETS } from './presets';
import type { Messages } from '$lib/i18n/types';
import type { Scale } from '$lib/ui/types';
import type { Units } from './units';

const MIB = 1024 * 1024;

/**
 * The quota's own byte humaniser, exact where the shared one rounds.
 *
 * `binaryBytes` renders to one decimal, which is right for the fill estimate beside it — that
 * number is an estimate and says so with a leading `≈`. It is wrong here: the track steps by
 * 256 MiB, so 7 936 MiB is exactly 7.75 GiB and `7.8 GiB` would be a rounded reading of a value
 * the researcher chose exactly. Every stop above a gibibyte is a quarter of one, so two decimals
 * are always enough and trailing zeros never survive.
 */
function exactBinary(mib: number): string {
  if (mib < 1024) return `${mib} MiB`;
  const gib = mib / 1024;
  return `${Number.isInteger(gib) ? gib : Number(gib.toFixed(2))} GiB`;
}

export type { Scale };

export type ScaleKey =
  | 'sampling_period_us'
  | 'ambient_sampling_period_us'
  | 'maximum_report_latency_us'
  | 'change_threshold_millilux'
  | 'minimum_event_interval_ms'
  | 'change_threshold_millimeters'
  | 'poll_interval_minutes'
  | 'interval_millis'
  | 'maximum_batch_delay_millis'
  | 'minimum_displacement_millimeters'
  | 'trajectory_sampling_hz'
  | 'duration_hours'
  | 'maximum_local_bytes'
  | 'upload_interval_minutes';

/** Shape B, where control space is stored space and the ladder is the reachable set. */
function laddered(
  ladder: readonly number[],
  presets: readonly number[],
  format: (value: number) => string
): Scale {
  return {
    box: false,
    affix: '',
    toHuman: (stored) => stored,
    toStored: (human) => human,
    min: ladder[0],
    max: ladder[ladder.length - 1],
    step: 1,
    presets,
    ladder,
    scale: 'log',
    format
  };
}

export function scales(m: Messages, u: Units): Record<ScaleKey, Scale> {
  return {
    /**
     * Microseconds in the file, hertz in the control. Nobody designs an accelerometer study in
     * microseconds — µs is `SensorManager`'s unit, not a researcher's.
     *
     * `floor`, not `round`, and not a preference: `CollectorSummary.kt:47` reads the period back as
     * `(1_000_000.0 / samplingPeriodUs).toInt().coerceAtLeast(1)`, so the box has to show what the
     * participant's own phone will show. `round` breaks the round-trip on 94 of the 200 rates —
     * 60 Hz would store 16 667 µs and read back as 59 — while `floor` needs only f(f+1) < 1e6 and
     * holds for all 200. 200 Hz is exactly the schema's floor, 1 Hz exactly its ceiling.
     */
    sampling_period_us: {
      box: true,
      affix: m.unit.hertz,
      toHuman: (stored) => Math.max(1, Math.trunc(1_000_000 / Math.max(1, stored))),
      toStored: (human) => Math.floor(1_000_000 / human),
      min: 1,
      max: 200,
      step: 1,
      presets: PRESETS.sampling_period_us,
      scale: 'log',
      format: u.hertz
    },

    ambient_sampling_period_us: {
      box: true,
      affix: m.unit.seconds,
      toHuman: (stored) => stored / 1_000_000,
      toStored: (human) => Math.round(human * 1_000_000),
      min: BOUNDS.ambientLightSamplingPeriodUs[0] / 1_000_000,
      max: BOUNDS.ambientLightSamplingPeriodUs[1] / 1_000_000,
      step: 0.1,
      presets: PRESETS.ambient_sampling_period_us,
      scale: 'log',
      format: u.seconds
    },

    /**
     * A box rather than a ladder, where its neighbour the batch delay is a ladder: the two are the
     * same kind of decision three orders of magnitude apart, and this one tops out at a minute, so
     * "seconds" names both ends and 7 s and 12 s are typable. A tenth of a second is the step
     * because sub-second batching is real and a whole-second box would have deleted it.
     */
    maximum_report_latency_us: {
      box: true,
      affix: m.unit.seconds,
      toHuman: (stored) => Number((stored / 1_000_000).toFixed(1)),
      toStored: (human) => Math.round(human * 1_000_000),
      min: BOUNDS.maximumReportLatencyUs[0] / 1_000_000,
      max: BOUNDS.maximumReportLatencyUs[1] / 1_000_000,
      step: 0.1,
      presets: PRESETS.maximum_report_latency_us,
      scale: 'log',
      format: u.seconds
    },

    change_threshold_millilux: {
      box: true,
      affix: m.unit.lux,
      toHuman: (stored) => stored / 1_000,
      toStored: (human) => Math.round(human * 1_000),
      min: BOUNDS.changeThresholdMillilux[0] / 1_000,
      max: BOUNDS.changeThresholdMillilux[1] / 1_000,
      step: 0.001,
      presets: PRESETS.change_threshold_millilux,
      scale: 'log',
      format: u.lux
    },

    minimum_event_interval_ms: {
      box: true,
      affix: m.unit.seconds,
      toHuman: (stored) => stored / 1_000,
      toStored: (human) => Math.round(human * 1_000),
      min: BOUNDS.minimumEventIntervalMs[0] / 1_000,
      max: BOUNDS.minimumEventIntervalMs[1] / 1_000,
      step: 0.1,
      presets: PRESETS.minimum_event_interval_ms,
      scale: 'log',
      format: u.seconds
    },

    change_threshold_millimeters: {
      box: true,
      affix: m.unit.millimetres,
      toHuman: (stored) => stored,
      toStored: (human) => human,
      min: BOUNDS.changeThresholdMillimeters[0],
      max: BOUNDS.changeThresholdMillimeters[1],
      step: 1,
      presets: PRESETS.change_threshold_millimeters,
      scale: 'log',
      format: u.millimetres
    },

    // 1 min → 1 day. 2 hours is an ordinary poll interval and is on no chip.
    poll_interval_minutes: laddered(
      LADDERS.poll_interval_minutes,
      PRESETS.poll_interval_minutes,
      u.minutes
    ),

    // 0.5 s → 1 h, and the ladder both thumbs of `DualRangeField` index. 30 seconds used to land on
    // 30 103 ms, which the readout then had to render as `30.103 sec`.
    interval_millis: laddered(LADDERS.interval_millis, PRESETS.interval_millis, u.millis),

    // 0 → 1 day. 10 minutes used to land on 599 489 ms.
    maximum_batch_delay_millis: laddered(
      LADDERS.maximum_batch_delay_millis,
      PRESETS.maximum_batch_delay_millis,
      u.millis
    ),

    /** Metres in the control, exact integer millimetres in Protocol v1. */
    minimum_displacement_millimeters: {
      box: true,
      affix: m.unit.metres,
      toHuman: (stored) => stored / 1_000,
      toStored: (human) => Math.round(human * 1_000),
      min: BOUNDS.minimumDisplacementMillimeters[0] / 1_000,
      max: BOUNDS.minimumDisplacementMillimeters[1] / 1_000,
      step: 0.001,
      presets: PRESETS.minimum_displacement_millimeters,
      scale: 'log',
      format: u.metres
    },

    /**
     * Stored unit and human unit are the same one. It is here because the rule is that the unit is
     * visible, and it was not: the box showed a bare `60`. 90 Hz is a real refresh rate, is on no
     * chip, and 90 arrow presses is not a keyboard path — so a box, not a ladder.
     */
    trajectory_sampling_hz: {
      box: true,
      affix: m.unit.hertz,
      toHuman: (stored) => stored,
      toStored: (human) => human,
      min: BOUNDS.trajectorySamplingHz[0],
      max: BOUNDS.trajectorySamplingHz[1],
      step: 1,
      presets: PRESETS.trajectory_sampling_hz,
      scale: 'linear',
      format: u.hertz
    },

    /**
     * 1 hour → 1 year, which crosses hours into days, so no box — and this is the control the owner
     * was reading when they said the number did not say it was hours. Getting 90 days out of a box
     * means typing 2160, which is the arithmetic they objected to in the quota.
     */
    duration_hours: laddered(LADDERS.duration_hours, PRESETS.duration_hours, u.hours),

    // The precedent, unchanged in shape. The ladder is what it was missing: 512 MiB used to land on
    // 535 218 657 B, which renders `510 MiB`.
    /**
     * Control space is mebibytes, on a linear track from 256 MiB to 8 GiB in steps of 256 MiB —
     * 32 stops, every one of them a whole number of MiB or GiB.
     *
     * Linear rather than log because the range spans a factor of 32, not orders of magnitude: a
     * log track puts most of its length under a gigabyte, which is not where the decision is. And
     * the floor is 256 MiB rather than the schema's 8 MiB because a quota that small is not a
     * study anyone runs — it fills in minutes at any usable sampling rate. The schema still
     * accepts 8 MiB, so a configuration imported with less is shown as it is and left alone.
     */
    maximum_local_bytes: {
      box: false,
      affix: '',
      toHuman: (stored) => Math.round(stored / MIB),
      toStored: (human) => human * MIB,
      min: 256,
      max: MAXIMUM_LOCAL_BYTES / MIB,
      step: 256,
      presets: PRESETS.maximum_local_bytes,
      scale: 'linear',
      format: exactBinary
    },

    // 1 min → 7 days. 12 hours used to land on 722 min.
    upload_interval_minutes: laddered(
      LADDERS.upload_interval_minutes,
      PRESETS.upload_interval_minutes,
      u.minutes
    )
  };
}

/**
 * The schema bound each adapter's lattice has to land inside, in *stored* units. Read by the tests
 * rather than by the components, which never see a stored bound at all.
 */
export const SCALE_BOUNDS: Record<ScaleKey, readonly [number, number]> = {
  sampling_period_us: BOUNDS.samplingPeriodUs,
  ambient_sampling_period_us: BOUNDS.ambientLightSamplingPeriodUs,
  maximum_report_latency_us: BOUNDS.maximumReportLatencyUs,
  change_threshold_millilux: BOUNDS.changeThresholdMillilux,
  minimum_event_interval_ms: BOUNDS.minimumEventIntervalMs,
  change_threshold_millimeters: BOUNDS.changeThresholdMillimeters,
  poll_interval_minutes: BOUNDS.pollIntervalMinutes,
  interval_millis: [BOUNDS.minimumIntervalMillis[0], BOUNDS.intervalMillis[1]],
  maximum_batch_delay_millis: BOUNDS.maximumBatchDelayMillis,
  minimum_displacement_millimeters: BOUNDS.minimumDisplacementMillimeters,
  trajectory_sampling_hz: BOUNDS.trajectorySamplingHz,
  duration_hours: BOUNDS.durationHours,
  maximum_local_bytes: [MINIMUM_LOCAL_BYTES, MAXIMUM_LOCAL_BYTES],
  upload_interval_minutes: [UPLOAD_MINIMUM_INTERVAL_MINUTES, UPLOAD_MAXIMUM_INTERVAL_MINUTES]
};
