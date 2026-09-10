import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { summarizeStudyTimeline } from '../src/lib/particeps/study-timeline';
import type { OccurrenceAutomation, ResourceBindingAutomation, StateCondition, StudyConfiguration } from '../src/lib/particeps/types';

function fixture(): StudyConfiguration {
  return { ...JSON.parse(readFileSync(new URL('../../researcher-tools/examples/five-day-speed-study.json', import.meta.url), 'utf8')), upload: null };
}
function occurrence(overrides: Partial<OccurrenceAutomation> = {}): OccurrenceAutomation {
  return { type: 'occurrence', id: 'test-survey', intervention_id: 'period-survey',
    trigger: { type: 'schedule', schedule: { type: 'daily_local', local_time: '17:00' } },
    guard: null, availability_seconds: 21_600, cooldown: null, maximum_activations: 512, ...overrides };
}
const localWindow = (start = '12:00', end = '17:00'): StateCondition => ({ type: 'study_local_window', first_day: 1, last_day: 5, start_local_time: start, end_local_time: end });
const unknown: StateCondition = { type: 'event_latch', set_when: [], reset_when: [] };
function traffic(configuration: StudyConfiguration): ResourceBindingAutomation {
  return configuration.automations.find((item): item is ResourceBindingAutomation => item.type === 'resource_binding' && item.resource.kind === 'actuator')!;
}

describe('researcher reference study timeline', () => {
  it.each([3, 4, 5])('shows exact day %i treatment and all six survey questions at 17:00', (studyDay) => {
    const summary = summarizeStudyTimeline(fixture(), { studyDay, timeZone: 'Asia/Taipei' });
    const lane = summary.lanes.find((item) => item.resourceId === 'traffic-shaping.v1')!;
    expect(lane.detail).toBe('All apps');
    expect(lane.segments.map((item) => [item.startTime, item.endTime, item.profileIds])).toEqual([
      ['00:00', '12:00', ['baseline']], ['12:00', '17:00', ['limited-500']], ['17:00', '24:00', ['baseline']]
    ]);
    expect(lane.segments[1].detail).toContain('upload 500 kbps, download 500 kbps');
    expect(summary.events).toMatchObject([{ automationId: `activities-day-${studyDay}`, localTime: '17:00', questionCount: 6, certainty: 'scheduled' }]);
    expect(summary.events).toHaveLength(1);
    expect(summary.rules).toHaveLength(fixture().automations.length);
  });
  it('shows baseline only and no survey on the first two days', () => {
    for (const studyDay of [1, 2]) {
      const summary = summarizeStudyTimeline(fixture(), { studyDay });
      expect(summary.lanes.find((item) => item.kind === 'actuator')!.segments.map((item) => item.profileIds)).toEqual([['baseline']]);
      expect(summary.events).toEqual([]);
    }
  });
  it('clips a cross-midnight study to partial first and last local dates', () => {
    const configuration = fixture(); configuration.duration_hours = 32;
    const first = summarizeStudyTimeline(configuration, { startTime: '18:00' });
    const last = summarizeStudyTimeline(configuration, { startTime: '18:00', studyDay: 3 });
    expect(first.day.totalDays).toBe(3);
    expect(first.lanes[0].segments.map((segment) => [segment.startTime, segment.endTime, segment.state])).toEqual([
      ['00:00', '18:00', 'outside-study'], ['18:00', '24:00', 'active']
    ]);
    expect(last.lanes[0].segments.map((segment) => [segment.startTime, segment.endTime, segment.state])).toEqual([
      ['00:00', '02:00', 'active'], ['02:00', '24:00', 'outside-study']
    ]);
  });
  it('excludes schedule instants at or after the calendar study deadline', () => {
    const configuration = fixture(); configuration.duration_hours = 24;
    configuration.automations = [occurrence({ trigger: { type: 'schedule', schedule: { type: 'interval', start_offset_minutes: 0, interval_minutes: 60, clock: 'CALENDAR_TIME' } } })];
    const result = summarizeStudyTimeline(configuration);
    expect(result.events).toHaveLength(24);
    expect(result.events.at(-1)?.localTime).toBe('23:00');
  });
  it('shows every occurrence targeting the same survey', () => {
    const configuration = fixture();
    configuration.automations = ['08:00', '12:00', '17:00'].map((local_time, index) => occurrence({ id: `survey-${index}`, trigger: { type: 'schedule', schedule: { type: 'daily_local', local_time } } }));
    expect(summarizeStudyTimeline(configuration).events.map((event) => event.localTime)).toEqual(['08:00', '12:00', '17:00']);
  });
  it('honors guards, cooldown and activation caps across preceding days', () => {
    const configuration = fixture();
    configuration.automations = [occurrence({ maximum_activations: 2, guard: { type: 'elapsed_at_least', duration_seconds: 24 * 3600, clock: 'CALENDAR_TIME' }, cooldown: { duration_seconds: 48 * 3600, clock: 'CALENDAR_TIME' } })];
    expect([1, 2, 3, 4, 5].map((studyDay) => summarizeStudyTimeline(configuration, { studyDay }).events.length)).toEqual([0, 1, 0, 1, 0]);
  });
  it('shows random guarded windows without selecting random instants', () => {
    const configuration = fixture();
    configuration.automations = [occurrence({ trigger: { type: 'schedule', schedule: { type: 'random_window',
      local_windows: [{ start_local_time: '08:00', end_local_time: '12:00' }, { start_local_time: '14:00', end_local_time: '19:00' }],
      occurrences_per_window: 2, maximum_occurrences_per_day: 3, maximum_occurrences_total: 9, minimum_separation_minutes: 30 } }, guard: localWindow('10:00', '17:00') })];
    const result = summarizeStudyTimeline(configuration);
    expect(result.events).toEqual([]);
    expect(result.randomWindows.map((window) => [window.startTime, window.endTime])).toEqual([['10:00', '12:00'], ['14:00', '17:00']]);
    expect(result.randomWindows[0].detail).toContain('≤ 3/day, ≤ 9 total, ≥ 30 min apart');
    expect(result.randomWindows.every((window) => !Object.hasOwn(window, 'instant'))).toBe(true);
  });
  it('retains ordered alternatives when higher-priority cases depend on observations', () => {
    const configuration = fixture(); const binding = traffic(configuration);
    binding.cases = [
      { condition: { type: 'all', conditions: [localWindow(), unknown] }, profile_id: null },
      { condition: localWindow(), profile_id: 'limited-500' }
    ];
    const result = summarizeStudyTimeline(configuration);
    expect(result.lanes.find((lane) => lane.kind === 'actuator')!.segments.map((segment) => [segment.startTime, segment.endTime, segment.state, segment.profileIds])).toEqual([
      ['00:00', '12:00', 'active', ['baseline']], ['12:00', '17:00', 'conditional', [null, 'limited-500']], ['17:00', '24:00', 'active', ['baseline']]
    ]);
    expect(result.rules.find((rule) => rule.id === binding.id)?.conditional).toBe(true);
  });
  it('respects first matching case and the final default with NOT and ANY conditions', () => {
    const configuration = fixture(); const binding = traffic(configuration);
    binding.cases = [
      { condition: { type: 'not', condition: { type: 'any', conditions: [localWindow('09:00', '11:00'), localWindow('15:00', '17:00')] } }, profile_id: null },
      { condition: { type: 'study_session_active' }, profile_id: 'limited-500' }, { condition: unknown, profile_id: 'baseline' }
    ];
    expect(summarizeStudyTimeline(configuration).lanes.find((lane) => lane.kind === 'actuator')!.segments.map((segment) => [segment.startTime, segment.endTime, segment.profileIds])).toEqual([
      ['00:00', '09:00', [null]], ['09:00', '11:00', ['limited-500']], ['11:00', '15:00', [null]], ['15:00', '17:00', ['limited-500']], ['17:00', '24:00', [null]]
    ]);
  });
  it('labels guarded schedules conditional and keeps history triggers off fixed timestamps', () => {
    const configuration = fixture();
    configuration.automations = [occurrence({ guard: unknown }), occurrence({ id: 'history-trigger', trigger: { type: 'condition_rising_edge', condition: { type: 'all', conditions: [localWindow(), unknown] } } })];
    const result = summarizeStudyTimeline(configuration);
    expect(result.events).toMatchObject([{ automationId: 'test-survey', localTime: '17:00', certainty: 'conditional' }]);
    expect(result.events).toHaveLength(1);
    expect(result.rules.every((rule) => rule.conditional)).toBe(true);
  });
  it('uses runtime gap policy for spring daylight saving', () => {
    const configuration = fixture(); configuration.duration_hours = 24;
    configuration.automations = [occurrence({ trigger: { type: 'schedule', schedule: { type: 'daily_local', local_time: '02:30' } } })];
    const result = summarizeStudyTimeline(configuration, { startDate: '2026-03-08', timeZone: 'America/New_York' });
    expect(result.day.durationMinutes).toBe(23 * 60);
    expect(result.events).toEqual([]);
    expect(result.warnings.join(' ')).toContain('23 elapsed hours');
  });
  it('uses the first repeated local time and retains a 25-hour axis', () => {
    const configuration = fixture(); configuration.duration_hours = 30;
    configuration.automations = [occurrence({ trigger: { type: 'schedule', schedule: { type: 'daily_local', local_time: '01:30' } } })];
    const result = summarizeStudyTimeline(configuration, { startDate: '2026-11-01', timeZone: 'America/New_York' });
    expect(result.day.durationMinutes).toBe(25 * 60);
    expect(result.events).toMatchObject([{ localTime: '01:30', instant: '2026-11-01T05:30:00.000Z', atMinute: 90 }]);
  });
  it('flags invalid overnight local windows instead of inventing wraparound', () => {
    const configuration = fixture(); traffic(configuration).cases[0].condition = localWindow('22:00', '02:00');
    const result = summarizeStudyTimeline(configuration);
    expect(result.warnings.join(' ')).toContain('split an overnight period');
    expect(result.lanes.find((lane) => lane.kind === 'actuator')!.segments[0].state).toBe('conditional');
  });
  it('keeps active-running schedules relative to start and explains pauses', () => {
    const configuration = fixture();
    configuration.automations = [occurrence({ trigger: { type: 'schedule', schedule: { type: 'one_time', offset_minutes: 90, clock: 'ACTIVE_RUNNING_TIME' } } })];
    const result = summarizeStudyTimeline(configuration, { startTime: '10:15' });
    expect(result.events[0].localTime).toBe('11:45');
    expect(result.events[0].detail).toContain('active running time');
    expect(result.assumptions.join(' ')).toContain('Active running time stops during pauses');
  });
  it('rejects invalid dates, zones, nonexistent start times and out-of-range days', () => {
    expect(() => summarizeStudyTimeline(fixture(), { startDate: '2026-02-30' })).toThrow('valid reference start date');
    expect(() => summarizeStudyTimeline(fixture(), { timeZone: 'Mars/Base' })).toThrow('valid IANA time zone');
    expect(() => summarizeStudyTimeline(fixture(), { startTime: '24:00' })).toThrow('00:00 to 23:59');
    expect(() => summarizeStudyTimeline(fixture(), { studyDay: 6 })).toThrow('1 to 5');
    expect(() => summarizeStudyTimeline(fixture(), { startDate: '2026-03-08', startTime: '02:30', timeZone: 'America/New_York' })).toThrow('does not exist');
  });
  it('returns serializable localized content without mutating configuration', () => {
    const configuration = fixture(); const before = JSON.stringify(configuration);
    const result = summarizeStudyTimeline(configuration, { studyDay: 3, locale: 'zh-TW' });
    expect(JSON.parse(JSON.stringify(result)).events[0].questionCount).toBe(6);
    expect(result.lanes.find((lane) => lane.kind === 'actuator')!.detail).toBe('所有 App');
    expect(result.rules[0].summary).toContain('6 題');
    expect(JSON.stringify(configuration)).toBe(before);
  });
});
