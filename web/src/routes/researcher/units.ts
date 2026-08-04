/**
 * `CollectorSummary.kt`'s humanisers, ported.
 *
 * A researcher setting `poll_interval_minutes` to 1440 should read the words the participant will
 * read on the consent screen, not the number they typed — the phone renders "1 day", so this does
 * too. Every rule here is the Kotlin one: coarsest unit that stays exact, never a rounded one.
 *
 * Days have no key in the catalogue, and inventing English inside a module is exactly what the i18n
 * rule forbids, so they come from `Intl.NumberFormat`'s unit style — the platform's own catalogue,
 * in the locale the reader chose. Everything else uses `unit.*`.
 *
 * Seconds used to come from there too and now come from `unit.seconds`, because the word beside a
 * number box and the word in the readout beside it have to be the same word for the same unit, or
 * the box's suffix and the humanised echo disagree about what they are naming. Both catalogue
 * entries are byte-identical to what `Intl` emitted, so no rendered string moved.
 */

import { binaryBytes } from '$lib/ui/format';
import type { Locale, Messages } from '$lib/i18n/types';

export interface Units {
  minutes(value: number): string;
  millis(value: number): string;
  /** The humaniser for a control whose space is seconds: `60` reads `1 min`. */
  seconds(value: number): string;
  hours(value: number): string;
  hertz(value: number): string;
  metres(value: number): string;
  millimetres(value: number): string;
  lux(value: number): string;
  bytes(value: number): string;
  /** One significant figure above ten, because the constants behind it are that good and no better. */
  count(value: number): string;
  /** Every estimate says so with a leading `≈`, which is a symbol rather than a word. */
  about(value: string): string;
}

function intlDay(locale: Locale, value: number): string {
  try {
    return new Intl.NumberFormat(locale, {
      style: 'unit',
      unit: 'day',
      unitDisplay: 'short'
    }).format(value);
  } catch {
    return String(value);
  }
}

/** One significant figure above ten: an estimate that reads as exact would be read as exact. */
function coarse(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 0;
  if (value < 10) return Math.round(value * 10) / 10;
  const scale = 10 ** (Math.floor(Math.log10(value)) - 1);
  return Math.round(value / scale) * scale;
}

export function units(m: Messages, locale: Locale): Units {
  const number = new Intl.NumberFormat(locale);
  const day = (value: number) => intlDay(locale, value);

  // Zero is its own answer everywhere it is legal — an unbatched sensor, an immediate delivery —
  // and "0 days" would be a unit attached to the absence of a quantity.
  const minutes = (value: number): string => {
    if (value === 0) return '0';
    if (value % 1_440 === 0) return day(value / 1_440);
    if (value % 60 === 0) return `${value / 60} ${m.unit.hours}`;
    return `${value} ${m.unit.minutes}`;
  };

  const millis = (value: number): string => {
    if (value === 0) return '0';
    return value % 60_000 === 0
      ? minutes(value / 60_000)
      : `${value / 1_000} ${m.unit.seconds}`;
  };

  const hours = (value: number): string => {
    if (value < 24) return `${value} ${m.unit.hours}`;
    if (value % 24 === 0) return day(value / 24);
    return `${day(Math.trunc(value / 24))} ${value % 24} ${m.unit.hours}`;
  };

  return {
    minutes,
    millis,
    // Fractional seconds are legal here — sub-second batching is a real setting — so the round is
    // to the millisecond the humaniser below already speaks, not to a whole second.
    seconds: (value) => (value === 0 ? '0' : millis(Math.round(value * 1_000))),
    hours,
    // The app shows a whole number of hertz because Android delivers at least that rate, never
    // less; a fractional rate would suggest a precision the sensor does not offer.
    hertz: (value) => `${value} ${m.unit.hertz}`,
    metres: (value) => `${number.format(value)} ${m.unit.metres}`,
    millimetres: (value) => `${number.format(value)} ${m.unit.millimetres}`,
    lux: (value) => `${number.format(value)} ${m.unit.lux}`,
    bytes: (value) => binaryBytes(value),
    count: (value) => number.format(coarse(value)),
    about: (value) => `≈ ${value}`
  };
}
