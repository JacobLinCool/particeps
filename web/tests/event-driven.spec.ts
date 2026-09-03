import { describe, expect, it } from 'vitest';
import { defaultCollector, validate } from '$lib/particeps/schema';
import {
  canonicalPredicateFieldValue,
  decodeEventWireFieldValue,
  eventContract
} from '$lib/particeps/registry';
import { simulate } from '$lib/particeps/simulator';
import { javaDoubleString } from '$lib/particeps/wire-float';
import { nextRandomWindow } from '../src/routes/researcher/random-window';
import { validConfiguration } from './fixture';
import type { CollectorConfig, StateCondition } from '$lib/particeps/types';

describe('event-driven authoring contract', () => {
  it('bridges lifecycle outputs as audit-only instead of researcher triggers', () => {
    for (const eventType of ['STUDY_STARTED', 'STUDY_RESUMED', 'STUDY_RUNNING']) {
      const contract = eventContract({
        source_id: 'study_runtime.v1', schema_version: 1, event_type: eventType
      });
      expect(contract?.event.trigger, eventType).toEqual({
        scope: 'AUDIT_ONLY', condition_kinds: [], presence: null
      });
      expect(contract?.event.privacy.trigger_exposure, eventType).toBe('NONE');
    }
  });

  it('uses Android Java double spelling for float predicate literals at notation boundaries', () => {
    const contract = eventContract({
      source_id: 'accelerometer.v1', schema_version: 1, event_type: 'ACCELEROMETER_SAMPLE'
    })?.event;
    if (!contract) throw new Error('missing accelerometer contract');

    expect([
      javaDoubleString(1),
      javaDoubleString(-0),
      javaDoubleString(0.001),
      javaDoubleString(0.0001),
      javaDoubleString(9_999_999),
      javaDoubleString(10_000_000)
    ]).toEqual(['1.0', '-0.0', '0.001', '1.0E-4', '9999999.0', '1.0E7']);
    for (const value of ['1.0', '-0.0', '0.001', '1.0E-4', '9999999.0', '1.0E7']) {
      expect(canonicalPredicateFieldValue(contract, 'x_meters_per_second_squared', value), value).toBe(true);
    }
    for (const hostile of [
      '+1', '01', '.5', '1.', '1e-3', '1E+3',
      '1', '-0', '0', '0.0001', '1e-4', '1.0e-4', '10000000.0', '1.0E+7'
    ]) {
      expect(canonicalPredicateFieldValue(contract, 'x_meters_per_second_squared', hostile), hostile).toBe(false);
    }
  });

  it('accepts Protocol decimal event floats and rejects malformed, non-finite, and out-of-range values', () => {
    const accelerometer = eventContract({
      source_id: 'accelerometer.v1', schema_version: 1, event_type: 'ACCELEROMETER_SAMPLE'
    })?.event;
    const location = eventContract({
      source_id: 'location.v1', schema_version: 1, event_type: 'LOCATION_FIX'
    })?.event;
    if (!accelerometer || !location) throw new Error('missing float contracts');
    const acceleration = accelerometer.fields.x_meters_per_second_squared;
    const latitude = location.fields.latitude_degrees;
    for (const [wire, expected] of [
      ['+1', 1], ['01', 1], ['.5', 0.5], ['1.', 1], ['1e-3', 0.001], ['1E+3', 1_000]
    ] as const) {
      expect(decodeEventWireFieldValue(acceleration, wire, accelerometer.maximum_encoded_event_bytes), wire)
        .toBe(expected);
    }
    for (const hostile of ['NaN', 'Infinity', '-Infinity', '1e309', '0x1.0p0', '1_0', ' 1', '1 ', '.', '+']) {
      expect(() => decodeEventWireFieldValue(acceleration, hostile, accelerometer.maximum_encoded_event_bytes), hostile)
        .toThrow();
    }
    expect(() => decodeEventWireFieldValue(latitude, '90.1', location.maximum_encoded_event_bytes)).toThrow();
  });

  it('accepts non-JCS embedded JSON but rejects duplicate members at every depth', () => {
    const submitted = eventContract({
      source_id: 'interventions.v1', schema_version: 1, event_type: 'SURVEY_SUBMITTED'
    })?.event;
    if (!submitted) throw new Error('missing survey-submitted contract');
    const answers = submitted.fields.answers_json;

    for (const value of [
      ' { "answer": 1.0, "nested": {"accepted": true} } ',
      '[1, 2, null, false]'
    ]) {
      expect(() => decodeEventWireFieldValue(answers, value, submitted.maximum_encoded_event_bytes), value)
        .not.toThrow();
    }
    for (const value of [
      '{"answer":1,"answer":2}',
      '{"answer":1,"\\u0061nswer":2}',
      '{"nested":{"key":1,"key":2}}',
      '\uFEFF{"answer":1}'
    ]) {
      expect(() => decodeEventWireFieldValue(answers, value, submitted.maximum_encoded_event_bytes), value)
        .toThrow();
    }
  });

  it('uses UTF-16 code units for signed text bounds', () => {
    expect(validate(validConfiguration({ title: '😀'.repeat(60) }))).toEqual([]);
    expect(validate(validConfiguration({ title: '😀'.repeat(61) })))
      .toContainEqual({ path: 'title', code: 'number_range', bounds: { min: 1, max: 120 } });
  });

  it('accepts 128 surveys and rejects 129', () => {
    const survey = (index: number) => ({
      id: `survey-${index.toString().padStart(3, '0')}`,
      title: { default: 'Survey', translations: {} },
      description: { default: 'Description', translations: {} },
      questions: [{
        type: 'short_text' as const, id: 'question-one',
        prompt: { default: 'Prompt', translations: {} }, required: false, maximum_length: 100
      }]
    });
    expect(validate(validConfiguration({ surveys: Array.from({ length: 128 }, (_, index) => survey(index)) }))).toEqual([]);
    expect(validate(validConfiguration({ surveys: Array.from({ length: 129 }, (_, index) => survey(index)) })))
      .toContainEqual({ path: 'surveys', code: 'number_range', bounds: { min: 0, max: 128 } });
  });

  it('derives profile defaults from the generated registry', () => {
    expect(defaultCollector('usage_events.v1')).toEqual({
      id: 'usage_events.v1', required: false,
      profiles: [{ id: 'continuous', config: { poll_interval_seconds: 900 } }]
    });
    expect(defaultCollector('network_usage.v1').profiles[0].config).toEqual({
      poll_interval_seconds: 900, transports: ['mobile', 'wifi']
    });
  });

  it('rejects legacy-shaped resources and missing binding owners', () => {
    const configuration = validConfiguration();
    configuration.automations = configuration.automations.slice(1);
    expect(validate(configuration).some((issue) => issue.code === 'resource_owner')).toBe(true);
  });

  it('treats the first live study-session case as an active-session total binding', () => {
    const configuration = validConfiguration();
    const binding = configuration.automations.find((automation) =>
      automation.type === 'resource_binding' && automation.resource.id === 'app_lifecycle.v1'
    );
    if (!binding || binding.type !== 'resource_binding') throw new Error('missing lifecycle binding');
    binding.default_profile_id = null;
    expect(validate(configuration)).toEqual([]);

    binding.cases.unshift({
      condition: { type: 'elapsed_at_least', duration_seconds: 1, clock: 'ACTIVE_RUNNING_TIME' },
      profile_id: null
    });
    expect(validate(configuration).some((issue) => issue.code === 'trigger_source_liveness')).toBe(true);
  });

  it('requires a 15-second usage profile when it drives a condition', () => {
    const configuration = validConfiguration();
    const usage = defaultCollector('usage_events.v1') as Extract<CollectorConfig, { id: 'usage_events.v1' }>;
    usage.required = true;
    configuration.collectors.push(usage);
    configuration.collectors.sort((left, right) => left.id.localeCompare(right.id));
    configuration.interventions = [{
      id: 'usage-prompt', required: false,
      action: { type: 'notification', notification_title: 'Check in', notification_message: 'How is it going?' }
    }];
    configuration.automations.push({
      type: 'resource_binding', id: 'bind-usage-events',
      resource: { kind: 'collector', id: 'usage_events.v1' },
      cases: [{ condition: { type: 'study_session_active' }, profile_id: 'continuous' }],
      default_profile_id: 'continuous'
    });
    configuration.automations.push({
      type: 'occurrence', id: 'usage-prompt-event',
      trigger: {
        type: 'event_match', evaluation_clock: 'OBSERVED_RESEARCH_TIME',
        selector: { event: { source_id: 'usage_events.v1', schema_version: 1, event_type: 'SCREEN_INTERACTIVE' }, predicates: [] }
      },
      guard: null, intervention_id: 'usage-prompt', availability_seconds: 60,
      cooldown: null, maximum_activations: 1
    });
    configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
    expect(validate(configuration).some((issue) => issue.path.includes('profiles'))).toBe(true);
    usage.profiles[0].config.poll_interval_seconds = 15;
    expect(validate(configuration)).toEqual([]);
  });

  it('enforces the condition-node bound across an occurrence trigger and guard', () => {
    const denseCondition = (): StateCondition => ({
      type: 'all',
      conditions: Array.from({ length: 8 }, () => ({
        type: 'any',
        conditions: Array.from({ length: 4 }, (): StateCondition => ({ type: 'study_session_active' }))
      }))
    });
    const configuration = validConfiguration();
    configuration.interventions = [{
      id: 'condition-heavy-prompt', required: false,
      action: { type: 'notification', notification_title: 'Check in', notification_message: 'How is it going?' }
    }];
    configuration.automations.push({
      type: 'occurrence', id: 'condition-heavy',
      trigger: { type: 'condition_rising_edge', condition: denseCondition() },
      guard: denseCondition(), intervention_id: 'condition-heavy-prompt', availability_seconds: 60,
      cooldown: null, maximum_activations: 1
    });
    configuration.automations.sort((left, right) => left.id.localeCompare(right.id));

    expect(validate(configuration)).toContainEqual({ path: 'automations.2', code: 'automation_invalid' });
  });

  it('enforces the global 512 concurrent automation timer bound', () => {
    const eightHeldTimers = (): StateCondition => ({
      type: 'all',
      conditions: Array.from({ length: 8 }, (): StateCondition => ({
        type: 'held_for', duration_seconds: 1, clock: 'ACTIVE_RUNNING_TIME',
        condition: { type: 'study_session_active' }
      }))
    });
    const configuration = validConfiguration();
    configuration.interventions = Array.from({ length: 57 }, (_, index) => ({
      id: `timer-prompt-${index.toString().padStart(2, '0')}`,
      required: false,
      action: { type: 'notification' as const, notification_title: 'Check in', notification_message: 'How is it going?' }
    }));
    configuration.automations.push(...configuration.interventions.map((intervention, index) => ({
      type: 'occurrence' as const,
      id: `timer-rule-${index.toString().padStart(2, '0')}`,
      trigger: {
        type: 'schedule' as const,
        schedule: { type: 'one_time' as const, offset_minutes: 0, clock: 'ACTIVE_RUNNING_TIME' as const }
      },
      guard: eightHeldTimers(),
      intervention_id: intervention.id,
      availability_seconds: 60,
      cooldown: null,
      maximum_activations: 1
    })));
    configuration.automations.sort((left, right) => left.id.localeCompare(right.id));

    expect(validate(configuration)).toContainEqual({ path: 'automations', code: 'automation_invalid' });
  });

  it('simulates held keyed presence without retroactively applying an ended interval', () => {
    const configuration = validConfiguration();
    const usage = defaultCollector('usage_events.v1') as Extract<CollectorConfig, { id: 'usage_events.v1' }>;
    usage.required = true;
    usage.profiles[0].config.poll_interval_seconds = 15;
    configuration.collectors.push(usage);
    configuration.collectors.sort((left, right) => left.id.localeCompare(right.id));
    configuration.traffic_shaping = {
      target_packages: ['com.example.social'],
      profiles: [
        { id: 'baseline', uplink_kbps: null, downlink_kbps: null },
        { id: 'slow-network', uplink_kbps: 256, downlink_kbps: 1024 }
      ]
    };
    configuration.automations.push({
      type: 'resource_binding', id: 'bind-usage-events',
      resource: { kind: 'collector', id: 'usage_events.v1' },
      cases: [{ condition: { type: 'study_session_active' }, profile_id: 'continuous' }],
      default_profile_id: 'continuous'
    });
    configuration.automations.push({
      type: 'resource_binding', id: 'bind-traffic-shaping', resource: { kind: 'actuator', id: 'traffic-shaping.v1' },
      cases: [{
        condition: {
          type: 'held_for', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME',
          condition: {
            type: 'keyed_presence', key_field: 'activity_component_token',
            enter_when: [{ event: { source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_RESUMED' }, predicates: [] }],
            exit_when: [{ event: { source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_PAUSED' }, predicates: [] }]
          }
        }, profile_id: 'slow-network'
      }], default_profile_id: 'baseline'
    });
    configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
    const token = 'a'.repeat(64);
    const active = simulate(configuration, {
      active_seconds: 200, calendar_seconds: 200,
      events: [{ source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_RESUMED', at_active_seconds: 10, at_calendar_seconds: 10, fields: { activity_component_token: token } }]
    });
    expect(active.resources.find((resource) => resource.id === 'traffic-shaping.v1')?.profile_id).toBe('slow-network');
    const ended = simulate(configuration, {
      active_seconds: 250, calendar_seconds: 250,
      events: [
        { source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_RESUMED', at_active_seconds: 10, at_calendar_seconds: 10, fields: { activity_component_token: token } },
        { source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_PAUSED', at_active_seconds: 220, at_calendar_seconds: 220, fields: { activity_component_token: token } }
      ]
    });
    expect(ended.resources.find((resource) => resource.id === 'traffic-shaping.v1')?.profile_id).toBe('baseline');
  });

  it('keeps random-window suggestions within the same local day', () => {
    expect(nextRandomWindow({
      type: 'random_window', local_windows: [{ start_local_time: '22:00', end_local_time: '23:00' }],
      occurrences_per_window: 1, maximum_occurrences_per_day: 1,
      maximum_occurrences_total: 10, minimum_separation_minutes: 60
    })).toBeNull();
  });

  it('rejects random-window daily capacity and adjacent separation violations', () => {
    const configured = (windows: { start_local_time: string; end_local_time: string }[], daily: number, separation: number) => {
      const configuration = validConfiguration();
      configuration.interventions = [{
        id: 'random-prompt', required: false,
        action: { type: 'notification', notification_title: 'Check in', notification_message: 'How is it going?' }
      }];
      configuration.automations.push({
        type: 'occurrence', id: 'random-prompt-rule',
        trigger: {
          type: 'schedule',
          schedule: {
            type: 'random_window', local_windows: windows, occurrences_per_window: 1,
            maximum_occurrences_per_day: daily, maximum_occurrences_total: 10,
            minimum_separation_minutes: separation
          }
        },
        guard: null, intervention_id: 'random-prompt', availability_seconds: 60,
        cooldown: null, maximum_activations: 10
      });
      configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
      return configuration;
    };

    expect(validate(configured([{ start_local_time: '08:00', end_local_time: '09:00' }], 2, 1)))
      .toContainEqual({ path: 'automations.2.trigger.schedule.maximum_occurrences_per_day', code: 'automation_invalid' });
    expect(validate(configured([
      { start_local_time: '08:00', end_local_time: '09:00' },
      { start_local_time: '09:30', end_local_time: '10:30' }
    ], 2, 32))).toContainEqual({ path: 'automations.2.trigger.schedule.local_windows', code: 'automation_invalid' });
  });
});
