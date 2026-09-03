import type { AutomationSchedule } from '$lib/particeps/types';

type RandomWindowAutomationSchedule = Extract<AutomationSchedule, { type: 'random_window' }>;
type RandomLocalWindow = RandomWindowAutomationSchedule['local_windows'][number];

export function nextRandomWindow(schedule: RandomWindowAutomationSchedule): RandomLocalWindow | null {
  const minute = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3));
  const clock = (value: number) =>
    `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
  const last = schedule.local_windows.at(-1);
  if (!last) return { start_local_time: '08:00', end_local_time: '12:00' };

  const first = schedule.local_windows[0];
  const width = 1 + (schedule.occurrences_per_window - 1) * schedule.minimum_separation_minutes;
  const start = minute(last.end_local_time) + schedule.minimum_separation_minutes - 1;
  const end = start + width;
  if (
    end >= 1_440 ||
    minute(first.start_local_time) + 1_440 - (end - 1) < schedule.minimum_separation_minutes
  ) {
    return null;
  }
  return { start_local_time: clock(start), end_local_time: clock(end) };
}
