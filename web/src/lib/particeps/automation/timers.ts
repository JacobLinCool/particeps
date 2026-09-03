import type { AutomationSchedule } from '../types';
import { timerId } from './checkpoint';
import type { DurableTimer, TimerProductionRequest } from './types';

const MINUTE_MS = 60_000;
const SECOND_MS = 1_000;
const NANO = 1_000_000_000n;

/**
 * Validates committed timer materialization without sampling entropy. Random
 * selections must be the next signed producer slot and a member of its exact
 * eligible UTC-minute set.
 */
export function requireValidScheduleTimer(request: TimerProductionRequest, timer: DurableTimer): void {
  if (request.pending_timer !== null) throw new Error('automation_schedule_timer_pending');
  let expected: DurableTimer | null;
  if (request.schedule.type === 'random_window') {
    const candidates = nextRandomCandidates(request, request.schedule);
    if (!candidates) throw new Error('automation_random_schedule_exhausted');
    if (timer.producer_key !== candidates.producerKey) throw new Error('automation_random_producer_not_next');
    if (timer.target.type !== 'CALENDAR_UTC' || !candidates.utcMillis.includes(timer.target.utc_millis)) {
      throw new Error('automation_random_selection_ineligible');
    }
    expected = calendarTimer(request, candidates.producerKey, timer.target.utc_millis);
  } else {
    expected = produceStandard(request);
    if (!expected) throw new Error('automation_standard_schedule_ineligible');
  }
  if (!timerEquals(timer, expected)) throw new Error('automation_timer_signed_schedule_mismatch');
}

/** Deterministic preview/runtime adapter for every non-random signed schedule. */
export function produceStandardScheduleTimer(request: TimerProductionRequest): DurableTimer | null {
  if (request.schedule.type === 'random_window' || request.pending_timer !== null) return null;
  return produceStandard(request);
}

function produceStandard(request: TimerProductionRequest): DurableTimer | null {
  if (request.pending_timer) return request.pending_timer;
  if (request.session_state !== 'RUNNING') return null;
  const schedule = request.schedule;
  if (schedule.type === 'one_time') {
    const producer = 'one-time';
    if (hasKey(request, producer)) return null;
    if (schedule.clock === 'CALENDAR_TIME') {
      const deadline = request.study_start_utc_millis + schedule.offset_minutes * MINUTE_MS;
      return calendarEligible(request, deadline) ? calendarTimer(request, producer, deadline) : null;
    }
    return activeTimer(request, producer, BigInt(schedule.offset_minutes) * 60n * NANO);
  }
  if (schedule.type === 'interval') {
    const keys = new Set(request.materialized.map((item) => item.producer_key));
    for (let ordinal = 0; ordinal < request.automation.maximum_activations; ordinal += 1) {
      const producer = `interval:${ordinal}`;
      if (keys.has(producer)) continue;
      const offset = schedule.start_offset_minutes + ordinal * schedule.interval_minutes;
      if (schedule.clock === 'CALENDAR_TIME') {
        const deadline = request.study_start_utc_millis + offset * MINUTE_MS;
        if (deadline >= request.study_deadline_utc_millis) return null;
        if (minimumExpiry(request, deadline) <= request.clock.now.wall_time_utc_millis) continue;
        return calendarTimer(request, producer, deadline);
      }
      return activeTimer(request, producer, BigInt(offset) * 60n * NANO);
    }
    return null;
  }
  if (schedule.type === 'daily_local') {
    let date = maxDate(localDate(request.study_start_utc_millis, request.clock.zone_id),
      localDate(request.clock.now.wall_time_utc_millis, request.clock.zone_id));
    const lastDate = localDate(request.study_deadline_utc_millis - 1, request.clock.zone_id);
    const keys = new Set(request.materialized.map((item) => item.producer_key));
    while (date <= lastDate) {
      const producer = `daily:${date}`;
      if (!keys.has(producer)) {
        const instant = firstInstant(date, schedule.local_time, request.clock.zone_id);
        if (instant !== null && calendarEligible(request, instant)) return calendarTimer(request, producer, instant);
      }
      date = nextDate(date);
    }
    return null;
  }
  throw new Error('automation_standard_producer_random_schedule');
}

function nextRandomCandidates(
  request: TimerProductionRequest,
  schedule: Extract<AutomationSchedule, { type: 'random_window' }>
): { producerKey: string; utcMillis: number[] } | null {
  if (request.session_state !== 'RUNNING' || request.materialized.length >= schedule.maximum_occurrences_total) return null;
  let date = localDate(request.study_start_utc_millis, request.clock.zone_id);
  const lastDate = localDate(request.study_deadline_utc_millis - 1, request.clock.zone_id);
  const keys = new Set(request.materialized.map((item) => item.producer_key));
  const chronologicalFloor = request.materialized.length
    ? Math.max(...request.materialized.map((item) => item.selected_utc_millis)) : null;
  const separation = schedule.minimum_separation_minutes * MINUTE_MS;
  while (date <= lastDate) {
    const prefix = `random:${date}:`;
    const remainingDaily = schedule.maximum_occurrences_per_day -
      request.materialized.filter((item) => item.producer_key.startsWith(prefix)).length;
    if (remainingDaily > 0) {
      for (const [windowIndex, window] of schedule.local_windows.entries()) {
        const start = parseMinute(window.start_local_time);
        const end = parseMinute(window.end_local_time);
        for (let ordinal = 0; ordinal < schedule.occurrences_per_window; ordinal += 1) {
          const totalRemaining = schedule.maximum_occurrences_total - request.materialized.length;
          if (totalRemaining <= 0) return null;
          const producerKey = `random:${date}:${windowIndex}:${ordinal}`;
          if (keys.has(producerKey)) continue;
          let later = 0;
          for (let candidate = ordinal + 1; candidate < schedule.occurrences_per_window; candidate += 1) {
            if (!keys.has(`random:${date}:${windowIndex}:${candidate}`)) later += 1;
          }
          const reserved = Math.min(later, remainingDaily - 1, totalRemaining - 1);
          const latest = end - 1 - reserved * schedule.minimum_separation_minutes;
          if (latest < start) continue;
          const preceding = ordinal === 0 ? null : request.materialized.find((item) =>
            item.producer_key === `random:${date}:${windowIndex}:${ordinal - 1}`)?.selected_utc_millis ?? null;
          const eligible: number[] = [];
          for (let minute = start; minute <= latest; minute += 1) {
            const candidate = firstInstant(date, minuteText(minute), request.clock.zone_id);
            if (candidate === null || candidate < request.study_start_utc_millis ||
              candidate < request.clock.now.wall_time_utc_millis || candidate >= request.study_deadline_utc_millis ||
              (chronologicalFloor !== null && candidate <= chronologicalFloor) ||
              (preceding !== null && candidate - preceding < separation) ||
              request.materialized.some((item) => Math.abs(candidate - item.selected_utc_millis) < separation)) continue;
            eligible.push(candidate);
          }
          if (eligible.length) return { producerKey, utcMillis: eligible };
        }
      }
    }
    date = nextDate(date);
  }
  return null;
}

function activeTimer(request: TimerProductionRequest, producer: string, target: bigint): DurableTimer | null {
  const duration = BigInt(request.study_deadline_utc_millis - request.study_start_utc_millis) * 1_000_000n;
  if (target < 0n || target >= duration) return null;
  return {
    id: timerId(request.configuration_sha256, request.automation.id, producer), automation_id: request.automation.id,
    generation: request.current_generation + 1n, causal_sequence: request.causal_sequence, producer_key: producer,
    target: { type: 'ACTIVE_ELAPSED', elapsed_nanos: target }, logical_deadline_utc_millis: null,
    expires_at_utc_millis: null
  };
}

function calendarTimer(request: TimerProductionRequest, producer: string, deadline: number): DurableTimer {
  return {
    id: timerId(request.configuration_sha256, request.automation.id, producer), automation_id: request.automation.id,
    generation: request.current_generation + 1n, causal_sequence: request.causal_sequence, producer_key: producer,
    target: { type: 'CALENDAR_UTC', utc_millis: deadline }, logical_deadline_utc_millis: deadline,
    expires_at_utc_millis: minimumExpiry(request, deadline)
  };
}

function minimumExpiry(request: TimerProductionRequest, deadline: number): number {
  return Math.min(deadline + request.automation.availability_seconds * SECOND_MS, request.study_deadline_utc_millis);
}

function calendarEligible(request: TimerProductionRequest, deadline: number): boolean {
  return deadline >= request.study_start_utc_millis && deadline < request.study_deadline_utc_millis &&
    minimumExpiry(request, deadline) > request.clock.now.wall_time_utc_millis;
}

function hasKey(request: TimerProductionRequest, producer: string): boolean {
  return request.materialized.some((item) => item.producer_key === producer);
}

function firstInstant(date: string, localTime: string, zone: string): number | null {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = localTime.split(':').map(Number);
  const localAsUtc = Date.UTC(year, month - 1, day, hour, minute, 0, 0);
  const offsets = new Set([-172_800_000, -86_400_000, 0, 86_400_000, 172_800_000]
    .map((delta) => zoneOffsetMillis(localAsUtc + delta, zone)));
  const candidates = [...offsets].map((offset) => localAsUtc - offset).filter((candidate) => {
    const parts = zonedParts(candidate, zone);
    return parts.year === year && parts.month === month && parts.day === day && parts.hour === hour && parts.minute === minute;
  });
  return candidates.length ? Math.min(...candidates) : null;
}

function zoneOffsetMillis(instant: number, zone: string): number {
  const second = Math.trunc(instant / 1_000) * 1_000;
  const parts = zonedParts(second, zone);
  return Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second) - second;
}

function zonedParts(instant: number, zone: string): Record<'year' | 'month' | 'day' | 'hour' | 'minute' | 'second', number> {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone, calendar: 'iso8601', numberingSystem: 'latn', hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'
  });
  const result = {} as Record<'year' | 'month' | 'day' | 'hour' | 'minute' | 'second', number>;
  for (const part of formatter.formatToParts(new Date(instant))) {
    if (part.type in { year: 1, month: 1, day: 1, hour: 1, minute: 1, second: 1 }) {
      result[part.type as keyof typeof result] = Number(part.value);
    }
  }
  if (Object.keys(result).length !== 6) throw new Error('automation_zone_format_failure');
  return result;
}

function localDate(instant: number, zone: string): string {
  const parts = zonedParts(instant, zone);
  return `${parts.year.toString().padStart(4, '0')}-${parts.month.toString().padStart(2, '0')}-${parts.day.toString().padStart(2, '0')}`;
}

function nextDate(value: string): string {
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day + 1));
  return `${date.getUTCFullYear().toString().padStart(4, '0')}-${(date.getUTCMonth() + 1).toString().padStart(2, '0')}-${date.getUTCDate().toString().padStart(2, '0')}`;
}

const maxDate = (left: string, right: string): string => left > right ? left : right;
const parseMinute = (value: string): number => Number(value.slice(0, 2)) * 60 + Number(value.slice(3));
const minuteText = (value: number): string => `${Math.trunc(value / 60).toString().padStart(2, '0')}:${(value % 60).toString().padStart(2, '0')}`;

function timerEquals(left: DurableTimer, right: DurableTimer): boolean {
  return left.id === right.id && left.automation_id === right.automation_id && left.generation === right.generation &&
    left.causal_sequence === right.causal_sequence && left.producer_key === right.producer_key &&
    JSON.stringify(left.target, (_, value) => typeof value === 'bigint' ? value.toString() : value) ===
      JSON.stringify(right.target, (_, value) => typeof value === 'bigint' ? value.toString() : value) &&
    left.logical_deadline_utc_millis === right.logical_deadline_utc_millis &&
    left.expires_at_utc_millis === right.expires_at_utc_millis;
}
