import { describe, expect, it } from 'vitest';
import { canonicalBytes, canonicalConfigurationBytes, canonicalizeConfiguration } from '../src/lib/particeps/canonical';
import { maximumReachableLocalDates, validate } from '../src/lib/particeps/schema';
import type { CollectorConfig, InterventionConfig, StudyConfiguration } from '../src/lib/particeps/types';
import { parseConfiguration } from '../src/routes/researcher/parse';
import { nextRandomWindow } from '../src/routes/researcher/random-window';
import { validConfiguration } from './fixture';

function p2Collectors(): CollectorConfig[] {
  return [
    { id: 'battery_state.v1', required: false, config: {} },
    { id: 'temporal_context.v1', required: false, config: {} },
    {
      id: 'gyroscope.v1',
      required: false,
      config: { sampling_period_us: 100_000, maximum_report_latency_us: 1_000_000 }
    },
    {
      id: 'ambient_light.v1',
      required: false,
      config: { sampling_period_us: 1_000_000, change_threshold_millilux: 1_000 }
    },
    {
      id: 'proximity.v1',
      required: false,
      config: { minimum_event_interval_ms: 1_000, change_threshold_millimeters: 0 }
    }
  ];
}

function randomIntervention(): InterventionConfig {
  return {
    id: 'random-ema',
    action: {
      type: 'notification',
      notification_title: 'Check in',
      notification_message: 'Please complete the check-in.'
    },
    triggers: [
      {
        id: 'random-ema-window',
        availability_minutes: 60,
        schedule: {
          type: 'random_window',
          local_windows: [
            { start_local_time: '08:00', end_local_time: '12:00' },
            { start_local_time: '14:00', end_local_time: '18:00' }
          ],
          occurrences_per_window: 2,
          maximum_occurrences_per_day: 4,
          maximum_occurrences_total: 14,
          minimum_separation_minutes: 60
        }
      }
    ]
  };
}

function p2Configuration(overrides: Partial<StudyConfiguration> = {}): StudyConfiguration {
  const base = validConfiguration();
  return {
    ...base,
    collectors: [base.collectors[0], ...p2Collectors()],
    interventions: [randomIntervention()],
    ...overrides
  };
}

describe('P2 Protocol v1 authoring', () => {
  it('round-trips all five collectors and random windows through the closed-world parser', () => {
    const configuration = p2Configuration();
    expect(validate(configuration)).toEqual([]);
    expect(parseConfiguration(canonicalConfigurationBytes(configuration))).toEqual(configuration);
  });

  it('refuses unknown collector config fields instead of silently dropping them', () => {
    const wire = JSON.parse(canonicalizeConfiguration(p2Configuration())) as {
      collectors: Array<{ id: string; config: Record<string, unknown> }>;
    };
    const proximity = wire.collectors.find((collector) => collector.id === 'proximity.v1');
    expect(proximity).toBeDefined();
    proximity!.config.infer_presence = true;
    expect(() => parseConfiguration(canonicalBytes(wire))).toThrow('parse_keys');
  });

  it('enforces the collector-specific physical-unit bounds', () => {
    const configuration = p2Configuration();
    const ambient = configuration.collectors.find(
      (collector): collector is Extract<CollectorConfig, { id: 'ambient_light.v1' }> =>
        collector.id === 'ambient_light.v1'
    )!;
    const proximity = configuration.collectors.find(
      (collector): collector is Extract<CollectorConfig, { id: 'proximity.v1' }> =>
        collector.id === 'proximity.v1'
    )!;
    ambient.config.sampling_period_us = 199_999;
    proximity.config.change_threshold_millimeters = 10_001;
    const paths = validate(configuration).map((issue) => issue.path);
    expect(paths).toContain('collectors.4.config.sampling_period_us');
    expect(paths).toContain('collectors.5.config.change_threshold_millimeters');
  });

  it('rejects overlapping, undersized, and over-capacity random windows', () => {
    const configuration = p2Configuration();
    const schedule = configuration.interventions[0].triggers[0].schedule;
    if (schedule.type !== 'random_window') throw new Error('fixture');
    schedule.local_windows[0].end_local_time = '08:30';
    schedule.local_windows[1].start_local_time = '08:15';
    schedule.maximum_occurrences_per_day = 17;
    const issues = validate(configuration);
    expect(issues.some((issue) => issue.code === 'window_order')).toBe(true);
    expect(issues.some((issue) => issue.code === 'schedule_bounds')).toBe(true);
  });

  it('uses each signed random total for the global bound under arbitrary clock edits', () => {
    expect(maximumReachableLocalDates(60)).toBe(3);
    const configuration = p2Configuration({ duration_hours: 1 });
    const intervention = randomIntervention();
    const template = intervention.triggers[0];
    configuration.interventions = [{
      ...intervention,
      triggers: Array.from({ length: 2 }, (_, index) => ({
        ...template,
        id: `random-trigger-${index + 1}`,
        schedule: {
          type: 'random_window' as const,
          local_windows: [{ start_local_time: '08:00', end_local_time: '09:00' }],
          occurrences_per_window: 8,
          maximum_occurrences_per_day: 8,
          maximum_occurrences_total: 512,
          minimum_separation_minutes: 1
        }
      }))
    }];

    expect(validate({ ...configuration, interventions: [{
      ...configuration.interventions[0],
      triggers: configuration.interventions[0].triggers.slice(0, 1)
    }] })).toEqual([]);
    expect(validate(configuration)).toContainEqual({ path: 'interventions', code: 'schedule_bounds' });
  });

  it('never suggests a random window whose end would be Protocol-invalid 24:00', () => {
    const schedule = randomIntervention().triggers[0].schedule;
    if (schedule.type !== 'random_window') throw new Error('fixture');
    schedule.local_windows = [{ start_local_time: '08:00', end_local_time: '23:00' }];
    schedule.occurrences_per_window = 1;

    expect(nextRandomWindow(schedule)).toBeNull();

    schedule.local_windows[0].end_local_time = '22:59';
    expect(nextRandomWindow(schedule)).toEqual({
      start_local_time: '23:58',
      end_local_time: '23:59'
    });
  });
});
