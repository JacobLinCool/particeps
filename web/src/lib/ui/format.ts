/**
 * Presentation-only formatting. Unit humanisation for study parameters lives in
 * `lib/researcher/units.ts`, ported from `CollectorSummary.kt`; this file holds only the things
 * the visual layer needs to draw a number.
 */

/**
 * Thin spaces between thousands, not commas. The byte pane puts a count directly above a
 * denominator and both are read as digit columns — a comma at 12,480 and none at 480 makes the
 * columns disagree, and a comma is a decimal point to half the world.
 */
export function groupDigits(value: number): string {
  return Math.trunc(value)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

/** `12 480 / 1 048 576 B` — the denominator is `MAXIMUM_CONFIGURATION_BYTES`, literally. */
export function byteRatio(bytes: number, max: number): string {
  return `${groupDigits(bytes)} / ${groupDigits(max)} B`;
}

/** Binary units, because every bound in the schema is a power of two. */
export function binaryBytes(bytes: number): string {
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const shown = value >= 100 || Number.isInteger(value) ? Math.round(value) : Number(value.toFixed(1));
  return `${shown} ${units[unit]}`;
}

/** `near` at 80%, `over` past 100%. Anything else has no level and stays accent-coloured. */
export function fillLevel(value: number, max: number, near = 0.8): 'under' | 'near' | 'over' {
  if (max <= 0) return 'under';
  const ratio = value / max;
  if (ratio > 1) return 'over';
  return ratio >= near ? 'near' : 'under';
}

/** 0–1, clamped, for a transform-scale gauge. */
export function fraction(value: number, max: number): number {
  if (!(max > 0)) return 0;
  return Math.min(1, Math.max(0, value / max));
}

/** Log positioning, offset so a range that includes 0 still works. */
export function logPosition(value: number, min: number, max: number): number {
  const span = Math.log(max - min + 1);
  if (!(span > 0)) return 0;
  return Math.min(1, Math.max(0, Math.log(Math.max(0, value - min) + 1) / span));
}

export function logValue(position: number, min: number, max: number): number {
  const span = Math.log(max - min + 1);
  return min + Math.exp(Math.min(1, Math.max(0, position)) * span) - 1;
}

/** Eight groups of four, as `fingerprint()` emits them and as the app draws them. */
export function fingerprintGroups(value: string): string[] {
  return value.trim().split(/\s+/).filter(Boolean);
}

/** Class list helper: falsy entries drop out. */
export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ');
}
