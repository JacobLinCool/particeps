/** Deterministic preview adapter over the authoritative pure automation reducer. */

import { compileAutomationProgram } from './automation/compiler';
import { reduceAutomationBatch } from './automation/reducer';
import { produceStandardScheduleTimer } from './automation/timers';
import {
  emptyAutomationCheckpoint,
  type DurableTimer,
  type ReducerClock,
  type ReducerInput,
  type ReductionResult,
  type ResearchTime
} from './automation/types';
import type { StudyConfiguration } from './types';

export interface SyntheticEvent {
  source_id: string;
  schema_version: number;
  event_type: string;
  at_active_seconds: number;
  at_calendar_seconds: number;
  fields: Record<string, string>;
}

export interface SyntheticTrace {
  active_seconds: number;
  calendar_seconds: number;
  events: SyntheticEvent[];
}

export interface SimulationResult {
  resources: { kind: string; id: string; profile_id: string | null }[];
  interventions: { automation_id: string; intervention_id: string; matched_at_seconds: number }[];
}

interface Point {
  activeNanos: bigint;
  calendarNanos: bigint;
}

const NANOSECONDS_PER_SECOND = 1_000_000_000n;
const NANOSECONDS_PER_MILLISECOND = 1_000_000n;
const STUDY_START_UTC_MILLIS = 1_800_000_000_000;
const BOOT_SESSION_ID = 'synthetic-preview';
const ZONE_ID = 'UTC';

/**
 * Replays a synthetic trace through the same compiler and reducer used for authoritative Web
 * conformance. Standard timers are produced deterministically. Random-window selections are not
 * sampled in the researcher preview because participant entropy is a durable runtime input.
 */
export function simulate(configuration: StudyConfiguration, trace: SyntheticTrace): SimulationResult {
  validateTrace(trace);
  const program = compileAutomationProgram(configuration);
  let checkpoint = emptyAutomationCheckpoint();
  let sequence = 0;
  let current: Point = { activeNanos: 0n, calendarNanos: 0n };
  const interventions: SimulationResult['interventions'] = [];

  const settle = (initial: ReductionResult, point: Point): void => {
    let result = initial;
    while (true) {
      checkpoint = result.checkpoint;
      captureActions(result, point, interventions);
      const candidate = [...result.timer_production_requests]
        .sort((left, right) => left.automation.id.localeCompare(right.automation.id))
        .map((request) => produceStandardScheduleTimer(request))
        .find((timer): timer is DurableTimer => timer !== null);
      if (!candidate) return;
      result = reduceAutomationBatch(program, checkpoint, [{
        type: 'TIMER_MATERIALIZED',
        sequence_number: ++sequence,
        clock: clock(point),
        timer: candidate
      }]);
    }
  };

  const reduce = (makeInput: (sequenceNumber: number) => ReducerInput, point: Point): void => {
    const input = makeInput(++sequence);
    settle(reduceAutomationBatch(program, checkpoint, [input]), point);
  };

  const fireTimersThrough = (limit: Point, includeLimit: boolean): void => {
    while (true) {
      const candidates = [...checkpoint.timers.values()].flatMap((timer) => {
        const due = timerDuePoint(timer, current, limit);
        if (due === null || (!includeLimit && samePoint(due, limit))) return [];
        return [{ timer, due }];
      }).sort((left, right) => comparePoint(left.due, right.due) || left.timer.id.localeCompare(right.timer.id));
      const candidate = candidates[0];
      if (!candidate) return;
      current = candidate.due;
      const timer = candidate.timer;
      reduce((sequenceNumber): ReducerInput => ({
        type: 'TIMER_DUE',
        sequence_number: sequenceNumber,
        clock: clock(current),
        timer_id: timer.id,
        automation_id: timer.automation_id,
        generation: timer.generation,
        causal_sequence: timer.causal_sequence,
        target: timer.target,
        logical_due: timerAuditCoordinate(timer)
      }), current);
    }
  };

  reduce((sequenceNumber): ReducerInput => ({
    type: 'LIFECYCLE', sequence_number: sequenceNumber, clock: clock(current), state: 'ACTIVATING'
  }), current);
  reduce((sequenceNumber): ReducerInput => ({
    type: 'LIFECYCLE', sequence_number: sequenceNumber, clock: clock(current), state: 'RUNNING'
  }), current);

  for (const event of trace.events) {
    const point = tracePoint(event.at_active_seconds, event.at_calendar_seconds);
    fireTimersThrough(point, false);
    current = point;
    reduce((sequenceNumber): ReducerInput => {
      const observed = researchTime(point);
      return {
        type: 'EVENT',
        sequence_number: sequenceNumber,
        clock: clock(point),
        event: {
          sequence_number: sequenceNumber,
          source_id: event.source_id,
          schema_version: event.schema_version,
          event_type: event.event_type,
          observed_time: observed,
          primary_source_time: observed,
          fields: { ...event.fields }
        }
      };
    }, point);
    fireTimersThrough(point, true);
  }

  const end = tracePoint(trace.active_seconds, trace.calendar_seconds);
  fireTimersThrough(end, true);

  return {
    resources: [...checkpoint.desired_resources.values()]
      .sort((left, right) => resourceIdentity(left.key).localeCompare(resourceIdentity(right.key)))
      .map(({ key, desired }) => ({
        kind: key.kind.toLowerCase(), id: key.id, profile_id: desired.profile_id
      })),
    interventions
  };
}

function captureActions(
  result: ReductionResult,
  point: Point,
  output: SimulationResult['interventions']
): void {
  const matchedAt = Number(point.activeNanos / NANOSECONDS_PER_SECOND);
  result.action_requests.forEach((action) => output.push({
    automation_id: action.automation_id,
    intervention_id: action.intervention_id,
    matched_at_seconds: matchedAt
  }));
}

function timerDuePoint(timer: DurableTimer, current: Point, limit: Point): Point | null {
  const target = timer.target;
  if (target.type === 'ACTIVE_ELAPSED') {
    return interpolateOnActive(target.elapsed_nanos, current, limit);
  }
  if (target.type === 'CALENDAR_UTC') {
    const calendar = BigInt(target.utc_millis - STUDY_START_UTC_MILLIS) *
      NANOSECONDS_PER_MILLISECOND;
    return interpolateOnCalendar(calendar, current, limit);
  }
  if (target.boot_session_id !== BOOT_SESSION_ID) return null;
  return interpolateOnCalendar(target.elapsed_realtime_nanos, current, limit);
}

function interpolateOnActive(target: bigint, current: Point, limit: Point): Point | null {
  if (target <= current.activeNanos) return current;
  if (target > limit.activeNanos) return null;
  const span = limit.activeNanos - current.activeNanos;
  const calendar = span === 0n ? current.calendarNanos : current.calendarNanos +
    (limit.calendarNanos - current.calendarNanos) * (target - current.activeNanos) / span;
  return { activeNanos: target, calendarNanos: calendar };
}

function interpolateOnCalendar(target: bigint, current: Point, limit: Point): Point | null {
  if (target <= current.calendarNanos) return current;
  if (target > limit.calendarNanos) return null;
  const span = limit.calendarNanos - current.calendarNanos;
  const active = span === 0n ? current.activeNanos : current.activeNanos +
    (limit.activeNanos - current.activeNanos) * (target - current.calendarNanos) / span;
  return { activeNanos: active, calendarNanos: target };
}

function timerAuditCoordinate(timer: DurableTimer): ResearchTime {
  const target = timer.target;
  if (target.type === 'CALENDAR_UTC') return {
    wall_time_utc_millis: target.utc_millis,
    elapsed_realtime_nanos: 0n,
    boot_session_id: 'calendar-time'
  };
  if (target.type === 'ACTIVE_ELAPSED') return {
    wall_time_utc_millis: 0,
    elapsed_realtime_nanos: target.elapsed_nanos,
    boot_session_id: 'active-running-time'
  };
  return {
    wall_time_utc_millis: timer.logical_deadline_utc_millis ?? 0,
    elapsed_realtime_nanos: target.elapsed_realtime_nanos,
    boot_session_id: target.boot_session_id
  };
}

function clock(point: Point): ReducerClock {
  return {
    now: researchTime(point),
    active_elapsed_nanos: point.activeNanos,
    calendar_elapsed_nanos: point.calendarNanos,
    zone_id: ZONE_ID
  };
}

function researchTime(point: Point): ResearchTime {
  return {
    wall_time_utc_millis: STUDY_START_UTC_MILLIS +
      Number(point.calendarNanos / NANOSECONDS_PER_MILLISECOND),
    elapsed_realtime_nanos: point.calendarNanos,
    boot_session_id: BOOT_SESSION_ID
  };
}

function tracePoint(activeSeconds: number, calendarSeconds: number): Point {
  return {
    activeNanos: BigInt(activeSeconds) * NANOSECONDS_PER_SECOND,
    calendarNanos: BigInt(calendarSeconds) * NANOSECONDS_PER_SECOND
  };
}

function resourceIdentity(key: { kind: string; id: string }): string {
  return `${key.kind.toLowerCase()}\0${key.id}`;
}

function samePoint(left: Point, right: Point): boolean {
  return left.activeNanos === right.activeNanos && left.calendarNanos === right.calendarNanos;
}

function comparePoint(left: Point, right: Point): number {
  return left.calendarNanos < right.calendarNanos ? -1 : left.calendarNanos > right.calendarNanos ? 1 :
    left.activeNanos < right.activeNanos ? -1 : left.activeNanos > right.activeNanos ? 1 : 0;
}

function validateTrace(trace: SyntheticTrace): void {
  if (!Number.isSafeInteger(trace.active_seconds) || trace.active_seconds < 0 ||
    !Number.isSafeInteger(trace.calendar_seconds) || trace.calendar_seconds < trace.active_seconds) {
    throw new Error('Invalid synthetic trace clocks');
  }
  let active = -1;
  let calendar = -1;
  for (const event of trace.events) {
    if (!Number.isSafeInteger(event.at_active_seconds) ||
      !Number.isSafeInteger(event.at_calendar_seconds) ||
      event.at_active_seconds < active || event.at_calendar_seconds < calendar ||
      event.at_active_seconds > trace.active_seconds ||
      event.at_calendar_seconds > trace.calendar_seconds) {
      throw new Error('Synthetic trace events must be ordered within the trace clocks');
    }
    active = event.at_active_seconds;
    calendar = event.at_calendar_seconds;
  }
}
