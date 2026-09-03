/**
 * The small set of values each decision is actually made from, and the reachable set behind it.
 *
 * A preset row is what turns an unbounded number into a short list of real choices, and it is the
 * fastest keyboard path to any of them. They are values rather than labels — `RangeField` renders
 * each one through the same humaniser as the readout, so a button reads `15 min`, not "medium".
 *
 * Every number in this file is in **control space** — the unit the researcher states the value in,
 * which is the unit the control shows. `sampling_period_us` is listed in hertz because that is what
 * the box edits; `scales.ts` converts to microseconds at the boundary and nowhere else.
 *
 * `LADDERS` is the other half. A control whose legal range crosses a unit boundary has no number
 * box (see `scales.ts`), so the slider is the only way to reach a value that is not a chip — and a
 * log slider over three decades cannot land on a round one: measured, the poll slider reaches 716,
 * 722 and 727 minutes but not 720, and the quota slider reaches 535 218 657 B, which renders as
 * `510 MiB`. So those sliders index a ladder instead of running over a continuum. One arrow press
 * is one rung, every rung is a number somebody meant, and the chips are the shortlist inside it.
 *
 * Keyed by the schema path so a control, its presets and its ladder cannot drift apart.
 */

import { DEFAULT_LOCAL_BYTES } from '$lib/particeps/schema';

const KIB = 1_024;
const MIB = 1_024 * KIB;

export const PRESETS = {
  // Hertz, ascending, because that is the control's space. 1, 10, 50, 100, 200 Hz. Five, not six:
  // a preset row that needs a second line costs 50px on the card, and the sixth value is the one
  // the box reaches in two keystrokes. 5 Hz is typed.
  sampling_period_us: [1, 10, 50, 100, 200],
  ambient_sampling_period_us: [0.2, 0.5, 1, 5, 10],
  // Seconds. Zero is unbatched delivery and is a real choice, not an empty box.
  maximum_report_latency_us: [0, 1, 5, 30, 60],
  change_threshold_millilux: [0, 1, 10, 100, 1_000],
  minimum_event_interval_ms: [0.1, 0.5, 1, 10, 60],
  change_threshold_millimeters: [0, 1, 10, 100, 1_000],
  // 6 hours comes off the shortlist and stays a rung on the ladder below, one arrow press from 4.
  poll_interval_seconds: [15, 60, 300, 900, 3_600],
  interval_millis: [1_000, 10_000, 60_000, 300_000, 3_600_000],
  maximum_batch_delay_millis: [0, 30_000, 300_000, 3_600_000, 86_400_000],
  // 1 km is a kilometre of walking between fixes; it is typed, not clicked.
  minimum_displacement_millimeters: [0, 5, 25, 100],
  trajectory_sampling_hz: [30, 60, 120],
  // `DEFAULT_LOCAL_BYTES` rather than a fourth literal: the study opens on that value, so the chip
  // showing it has to be the same number and cannot be left behind if the default moves.
  // Mebibytes, the unit the quota's track counts in. Nothing below its 256 MiB floor.
  maximum_local_bytes: [256, 512, 1_024, 2_048, 4_096, 8_192],
  // 1, 3, 7, 14 and 28 days, in the unit the schema counts: how long one participant runs.
  duration_hours: [24, 72, 168, 336, 672],
  upload_interval_minutes: [15, 60, 360, 1_440, 10_080]
} as const satisfies Record<string, readonly number[]>;

/**
 * The reachable set for every control that has no number box, ascending, in control space.
 *
 * Each rung renders exactly through its humaniser — no `599.489 sec`, no `510 MiB`, no `717 min`.
 * The costs are stated rather than hidden: `7 min` stops being a poll interval and `37 hours` stops
 * being a study length. Neither was ever chosen; both were what a slider landed on.
 *
 * Every `PRESETS` entry for a laddered control is one of its rungs, so a chip and the slider agree.
 */
export const LADDERS = {
  // 1 min → 1 day. Minute granularity where a poll is minutes, then the hours a person names.
  poll_interval_seconds: [
    15, 30, 60, 120, 300, 600, 900, 1_800, 3_600, 7_200, 14_400, 21_600, 43_200, 86_400
  ],
  // 0.5 s → 1 h. Both thumbs of the location pair index this.
  interval_millis: [
    500, 1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 20_000, 30_000, 45_000, 60_000, 120_000,
    180_000, 300_000, 600_000, 900_000, 1_200_000, 1_800_000, 2_700_000, 3_600_000
  ],
  // 0 → 1 day. Zero is a rung: an unbatched location fix is a decision.
  maximum_batch_delay_millis: [
    0, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000,
    3_600_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000, 86_400_000
  ],
  // 1 h → 1 year. Hour granularity for a session, then the day counts a study is designed in:
  // 21 days, 90 days and 180 days are rungs, and none of them was reachable from a box without
  // multiplying by 24 first.
  duration_hours: [
    1, 2, 3, 4, 6, 8, 12, 18, 24, 36, 48, 72, 96, 120, 168, 240, 336, 504, 672, 720, 1_080, 1_440,
    2_160, 4_320, 8_760
  ],
  // 8 MiB → 8 GiB. Every rung is a size somebody would say out loud, 512 MiB included.
  // 1 min → 7 days.
  upload_interval_minutes: [
    1, 5, 15, 30, 60, 120, 180, 360, 720, 1_440, 2_880, 4_320, 7_200, 10_080
  ]
} as const satisfies Record<string, readonly number[]>;
