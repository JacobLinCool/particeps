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
import { decodeEventWireFieldValue, eventContract } from './registry';
import { parseStrictEmbeddedJson } from './wire-json';
import type { RegistryFieldContract } from './generated/event-source-registry';

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
export const SIMULATION_REFERENCE = { startUtc: new Date(STUDY_START_UTC_MILLIS).toISOString(), timeZone: ZONE_ID } as const;
export const MAXIMUM_TRACE_SECONDS = 31_536_000;
export const MAXIMUM_TRACE_EVENTS = 2_000;
const MAXIMUM_TRACE_BYTES = 1_048_576;

export type TraceIssueCode = 'invalid_json' | 'too_large' | 'object_shape' | 'clock_range' | 'clock_order'
  | 'event_count' | 'unknown_event' | 'unknown_field' | 'field_value' | 'source_not_enabled' | 'study_duration';

export class SimulationTraceError extends Error {
  constructor(readonly path: string, readonly code: TraceIssueCode) {
    super(`${path}: ${code}`);
    this.name = 'SimulationTraceError';
  }
}

/** Parse untrusted editor JSON without coercion or duplicate-object-key ambiguity. */
export function parseSyntheticTrace(input: string, options: { validateSemantics?: boolean } = {}): SyntheticTrace {
  if (new TextEncoder().encode(input).length > MAXIMUM_TRACE_BYTES) throw new SimulationTraceError('trace', 'too_large');
  let value: unknown;
  try { value = parseStrictEmbeddedJson(input); }
  catch { throw new SimulationTraceError('trace', 'invalid_json'); }
  validateTraceShape(value);
  if (options.validateSemantics !== false) validateTrace(value);
  return value;
}

/** Neutral, explicitly synthetic test values; callers can edit every value before replay. */
export function defaultSyntheticFieldValue(field: RegistryFieldContract): string {
  switch (field.wire_type) {
    case 'enum': {
      const value = field.enum_values[0];
      if (value === undefined) throw new Error('registry_enum_without_values');
      return value;
    }
    case 'boolean': return 'false';
    case 'uuid': return '00000000-0000-4000-8000-000000000001';
    case 'sha256_hex': return '0'.repeat(64);
    case 'json_string': return '{}';
    case 'int32': case 'int64_decimal': case 'uint64_decimal': case 'float32': case 'float64':
      return String(Math.min(field.maximum ?? Infinity, Math.max(field.minimum ?? -Infinity, 0)));
    case 'string': return 'synthetic'.padEnd(field.minimum_length ?? 0, 'x').slice(0, field.maximum_length ?? 8_000);
  }
}

/**
 * Replays a synthetic trace through the same compiler and reducer used for authoritative Web
 * conformance. Standard timers are produced deterministically. Random-window selections are not
 * sampled in the researcher preview because participant entropy is a durable runtime input.
 */
export function simulate(configuration: StudyConfiguration, trace: SyntheticTrace): SimulationResult {
  validateTrace(trace);
  if (trace.active_seconds > configuration.duration_hours * 3_600) throw new SimulationTraceError('active_seconds', 'study_duration');
  trace.events.forEach((event, index) => {
    const source = eventContract(event)?.source;
    if (source?.source_kind === 'COLLECTOR' && !configuration.collectors.some((collector) => collector.id === source.source_id)) {
      throw new SimulationTraceError(`events.${index}.source_id`, 'source_not_enabled');
    }
  });
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

function validateTrace(value: unknown): asserts value is SyntheticTrace {
  validateTraceShape(value);
  const trace = traceObject(value, ['active_seconds', 'calendar_seconds', 'events'], 'trace');
  const finalActive = traceSeconds(trace.active_seconds, 'active_seconds');
  const finalCalendar = traceSeconds(trace.calendar_seconds, 'calendar_seconds');
  if (finalCalendar < finalActive) throw new SimulationTraceError('calendar_seconds', 'clock_order');
  if (!Array.isArray(trace.events) || trace.events.length > MAXIMUM_TRACE_EVENTS) throw new SimulationTraceError('events', 'event_count');
  let active = 0;
  let calendar = 0;
  trace.events.forEach((value, index) => {
    const path = `events.${index}`;
    const event = traceObject(value, ['source_id', 'schema_version', 'event_type', 'at_active_seconds', 'at_calendar_seconds', 'fields'], path);
    const nextActive = traceSeconds(event.at_active_seconds, `${path}.at_active_seconds`);
    const nextCalendar = traceSeconds(event.at_calendar_seconds, `${path}.at_calendar_seconds`);
    if (nextActive < active || nextCalendar < calendar || nextActive > finalActive || nextCalendar > finalCalendar ||
      nextCalendar - calendar < nextActive - active) throw new SimulationTraceError(path, 'clock_order');
    active = nextActive;
    calendar = nextCalendar;
    if (typeof event.source_id !== 'string' || event.schema_version !== 1 || typeof event.event_type !== 'string') {
      throw new SimulationTraceError(path, 'unknown_event');
    }
    const contract = eventContract({ source_id: event.source_id, schema_version: event.schema_version, event_type: event.event_type });
    if (!contract || contract.event.trigger.scope !== 'RESEARCHER') throw new SimulationTraceError(path, 'unknown_event');
    const fields = traceObject(event.fields, undefined, `${path}.fields`);
    for (const [name, value] of Object.entries(fields)) {
      const field = Object.hasOwn(contract.event.fields, name) ? contract.event.fields[name] : undefined;
      if (!field) throw new SimulationTraceError(`${path}.fields.${name}`, 'unknown_field');
      if (typeof value !== 'string') throw new SimulationTraceError(`${path}.fields.${name}`, 'field_value');
      try { decodeEventWireFieldValue(field, value, contract.event.maximum_encoded_event_bytes); }
      catch { throw new SimulationTraceError(`${path}.fields.${name}`, 'field_value'); }
    }
    if (new TextEncoder().encode(JSON.stringify(fields)).length > contract.event.maximum_encoded_event_bytes) {
      throw new SimulationTraceError(`${path}.fields`, 'too_large');
    }
  });
  if (finalCalendar - calendar < finalActive - active) throw new SimulationTraceError('calendar_seconds', 'clock_order');
}

/** Editable clock values may temporarily violate ordering; retain a safe shape for the form. */
function validateTraceShape(value: unknown): asserts value is SyntheticTrace {
  const trace = traceObject(value, ['active_seconds', 'calendar_seconds', 'events'], 'trace');
  if (typeof trace.active_seconds !== 'number' || typeof trace.calendar_seconds !== 'number') throw new SimulationTraceError('trace', 'clock_range');
  if (!Array.isArray(trace.events) || trace.events.length > MAXIMUM_TRACE_EVENTS) throw new SimulationTraceError('events', 'event_count');
  trace.events.forEach((value, index) => {
    const path = `events.${index}`;
    const event = traceObject(value, ['source_id', 'schema_version', 'event_type', 'at_active_seconds', 'at_calendar_seconds', 'fields'], path);
    if (typeof event.source_id !== 'string' || typeof event.schema_version !== 'number' || typeof event.event_type !== 'string') throw new SimulationTraceError(path, 'unknown_event');
    if (typeof event.at_active_seconds !== 'number' || typeof event.at_calendar_seconds !== 'number') throw new SimulationTraceError(path, 'clock_range');
    const fields = traceObject(event.fields, undefined, `${path}.fields`);
    for (const [name, field] of Object.entries(fields)) if (typeof field !== 'string') throw new SimulationTraceError(`${path}.fields.${name}`, 'field_value');
  });
}

function traceObject(value: unknown, keys: readonly string[] | undefined, path: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value) ||
    (keys && (Object.keys(value).length !== keys.length || keys.some((key) => !Object.hasOwn(value, key))))) {
    throw new SimulationTraceError(path, 'object_shape');
  }
  return value as Record<string, unknown>;
}

function traceSeconds(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > MAXIMUM_TRACE_SECONDS) {
    throw new SimulationTraceError(path, 'clock_range');
  }
  return value;
}
