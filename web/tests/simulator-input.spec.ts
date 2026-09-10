import { describe, expect, it } from 'vitest';
import { defaultSyntheticFieldValue, parseSyntheticTrace, simulate, SimulationTraceError, SIMULATION_REFERENCE } from '../src/lib/particeps/simulator';
import { RESEARCHER_EVENTS } from '../src/lib/particeps/registry';
import { validConfiguration } from './fixture';

const trace = () => ({ active_seconds: 300, calendar_seconds: 300, events: [] });
const event = () => ({ source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_RESUMED',
  at_active_seconds: 60, at_calendar_seconds: 60, fields: { activity_component_token: '0'.repeat(64) } });

function issue(input: unknown) {
  try { parseSyntheticTrace(JSON.stringify(input)); }
  catch (error) {
    expect(error).toBeInstanceOf(SimulationTraceError);
    return { path: (error as SimulationTraceError).path, code: (error as SimulationTraceError).code };
  }
  throw new Error('Expected a trace issue');
}

describe('guided synthetic scenarios', () => {
  it('validates every registry-derived required-field sample used by the builder', () => {
    for (const { source, event } of RESEARCHER_EVENTS) {
      const fields = Object.fromEntries(Object.entries(event.fields).filter(([, field]) => field.required)
        .map(([name, field]) => [name, defaultSyntheticFieldValue(field)]));
      const input = { ...trace(), events: [{ source_id: source.source_id, schema_version: source.schema_version,
        event_type: event.event_type, at_active_seconds: 0, at_calendar_seconds: 0, fields }] };
      expect(() => parseSyntheticTrace(JSON.stringify(input)), `${source.source_id}:${event.event_type}`).not.toThrow();
    }
  });

  it('rejects malformed/duplicate JSON and unknown top-level fields with specific codes', () => {
    expect(() => parseSyntheticTrace('{')).toThrow('trace: invalid_json');
    expect(() => parseSyntheticTrace('{"active_seconds":0,"active_seconds":1,"calendar_seconds":1,"events":[]}')).toThrow('trace: invalid_json');
    expect(issue({ ...trace(), extra: true })).toEqual({ path: 'trace', code: 'object_shape' });
    expect(issue(null)).toEqual({ path: 'trace', code: 'object_shape' });
    expect(issue({ ...trace(), events: {} })).toEqual({ path: 'events', code: 'event_count' });
  });

  it('rejects negative clocks, reversed ordering and physically impossible active-time advances', () => {
    expect(issue({ ...trace(), active_seconds: -1 })).toEqual({ path: 'active_seconds', code: 'clock_range' });
    expect(issue({ ...trace(), active_seconds: 301 })).toEqual({ path: 'calendar_seconds', code: 'clock_order' });
    expect(issue({ ...trace(), events: [{ ...event(), at_active_seconds: -1 }] })).toEqual({ path: 'events.0.at_active_seconds', code: 'clock_range' });
    expect(issue({ ...trace(), events: [event(), { ...event(), at_active_seconds: 59 }] })).toEqual({ path: 'events.1', code: 'clock_order' });
    expect(issue({ ...trace(), events: [{ ...event(), at_calendar_seconds: 59 }] })).toEqual({ path: 'events.0', code: 'clock_order' });
    expect(issue({ ...trace(), events: [{ ...event(), at_calendar_seconds: 100 }] })).toEqual({ path: 'calendar_seconds', code: 'clock_order' });
  });

  it('preserves structurally valid unfinished clock edits for guided form correction', () => {
    const input = JSON.stringify({ ...trace(), active_seconds: 360 });
    expect(parseSyntheticTrace(input, { validateSemantics: false }).active_seconds).toBe(360);
    expect(() => parseSyntheticTrace(input)).toThrow('calendar_seconds: clock_order');
    expect(() => parseSyntheticTrace(JSON.stringify({ ...trace(), events: 'bad' }), { validateSemantics: false })).toThrow('events: event_count');
  });

  it('rejects unknown events, unknown fields, prototype property names and malformed wire values', () => {
    expect(issue({ ...trace(), events: [{ ...event(), event_type: 'NO_SUCH_EVENT' }] })).toEqual({ path: 'events.0', code: 'unknown_event' });
    expect(issue({ ...trace(), events: [{ ...event(), fields: { extra: 'x' } }] })).toEqual({ path: 'events.0.fields.extra', code: 'unknown_field' });
    expect(issue({ ...trace(), events: [{ ...event(), fields: JSON.parse('{"__proto__":"x"}') }] })).toEqual({ path: 'events.0.fields.__proto__', code: 'unknown_field' });
    expect(issue({ ...trace(), events: [{ ...event(), fields: { activity_component_token: 'invalid' } }] })).toEqual({ path: 'events.0.fields.activity_component_token', code: 'field_value' });
  });

  it('enforces event and payload bounds before invoking the reducer', () => {
    expect(issue({ ...trace(), events: Array.from({ length: 2_001 }, event) })).toEqual({ path: 'events', code: 'event_count' });
    expect(() => parseSyntheticTrace(' '.repeat(1_048_577))).toThrow('trace: too_large');
    expect(issue({ ...trace(), calendar_seconds: 31_536_001 })).toEqual({ path: 'calendar_seconds', code: 'clock_range' });
  });

  it('requires enabled event sources and keeps the scenario inside study duration', () => {
    expect(() => simulate(validConfiguration(), { ...trace(), events: [event()] })).toThrow('events.0.source_id: source_not_enabled');
    expect(() => simulate(validConfiguration({ duration_hours: 1 }), { ...trace(), active_seconds: 3_601, calendar_seconds: 3_601 })).toThrow('active_seconds: study_duration');
    expect(simulate(validConfiguration(), trace()).resources).toHaveLength(2);
    expect(SIMULATION_REFERENCE).toEqual({ startUtc: '2027-01-15T08:00:00.000Z', timeZone: 'UTC' });
  });
});
