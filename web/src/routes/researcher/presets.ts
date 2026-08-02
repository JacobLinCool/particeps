/**
 * The small set of values each decision is actually made from.
 *
 * A preset row is what turns an unbounded number into a short list of real choices, and it is the
 * fastest keyboard path to any of them. They are values rather than labels — `RangeField` renders
 * each one through the same humaniser as the readout, so a button reads `15 min`, not "medium".
 *
 * Keyed by the schema path so a control and its presets cannot drift apart.
 */

import { DEFAULT_LOCAL_BYTES } from '$lib/adc/schema';

const KIB = 1_024;
const MIB = 1_024 * KIB;

export const PRESETS = {
  // 1, 5, 10, 50, 100, 200 Hz, descending in microseconds because the track runs as a rate.
  sampling_period_us: [1_000_000, 200_000, 100_000, 20_000, 10_000, 5_000],
  maximum_report_latency_us: [0, 1_000_000, 5_000_000, 30_000_000, 60_000_000],
  poll_interval_minutes: [1, 5, 15, 60, 360, 1_440],
  interval_millis: [1_000, 10_000, 60_000, 300_000, 3_600_000],
  maximum_batch_delay_millis: [0, 30_000, 300_000, 3_600_000, 86_400_000],
  minimum_displacement_meters: [0, 5, 25, 100, 1_000],
  trajectory_sampling_hz: [30, 60, 120],
  // `DEFAULT_LOCAL_BYTES` rather than a fourth literal: the study opens on that value, so the chip
  // showing it has to be the same number and cannot be left behind if the default moves.
  maximum_local_bytes: [8 * MIB, 64 * MIB, 256 * MIB, DEFAULT_LOCAL_BYTES, 4_096 * MIB, 8_192 * MIB],
  // 1, 3, 7, 14 and 28 days, in the unit the schema counts: how long one participant runs.
  duration_hours: [24, 72, 168, 336, 672],
  delay_minutes: [60, 360, 1_440, 4_320, 10_080],
  upload_interval_minutes: [15, 60, 360, 1_440, 10_080]
} as const satisfies Record<string, readonly number[]>;
