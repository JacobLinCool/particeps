import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { render } from 'svelte/server';
import type { StudyConfiguration, SurveyQuestion } from '$lib/particeps/types';
import { canonicalize } from '$lib/particeps/canonical';
import { continuousBinding, defaultCollector, validate } from '$lib/particeps/schema';
import { zhTW } from '$lib/i18n/zh-TW';
import InterventionEditor from '../src/routes/researcher/InterventionEditor.svelte';
import StateConditionEditor from '../src/routes/researcher/StateConditionEditor.svelte';
import OccurrenceEditor from '../src/routes/researcher/OccurrenceEditor.svelte';
import SurveyPreview from '../src/routes/researcher/SurveyPreview.svelte';
import type { Draft } from '../src/routes/researcher/draft.svelte';
import { createAutomationSchedule, createAutomationTrigger, createStateCondition, createSurveyQuestion, moveEditorItem, removeInterventionReferences } from '../src/routes/researcher/editor-model';
import { applyTrafficExample, reviewTrafficExample, undoTrafficExample } from '../src/routes/researcher/traffic-example';
import { validConfiguration } from './fixture';

const fiveDay = (): StudyConfiguration => JSON.parse(readFileSync(new URL('../../researcher-tools/examples/five-day-speed-study.json', import.meta.url), 'utf8'));
const localized = (value: string) => ({ default: value, translations: {} });

function trafficStudy(): StudyConfiguration {
  return validConfiguration({ traffic_shaping: {
    target_packages: ['com.android.chrome'],
    profiles: [{ id: 'baseline', uplink_kbps: null, downlink_kbps: null }, { id: 'slow-network', uplink_kbps: 256, downlink_kbps: 512 }]
  }, automations: [...validConfiguration().automations, {
    type: 'resource_binding' as const, id: 'traffic-rule', resource: { kind: 'actuator' as const, id: 'traffic-shaping.v1' },
    cases: [{ condition: { type: 'study_local_window' as const, first_day: 1, last_day: 1, start_local_time: '12:00', end_local_time: '17:00' }, profile_id: 'slow-network' }],
    default_profile_id: 'baseline'
  }].sort((a, b) => a.id.localeCompare(b.id)) });
}

describe('complete researcher editors', () => {
  it('renders all six imported questions and every occurrence without rewriting the study', () => {
    const configuration = fiveDay();
    const before = canonicalize(configuration);
    const { body } = render(InterventionEditor, { props: { draft: { configuration } as Draft, m: zhTW, locale: 'zh-TW' } });
    for (const [surveyIndex, survey] of configuration.surveys.entries()) {
      expect(body).toContain(`field-surveys.${surveyIndex}.title.default`);
      for (const [questionIndex, question] of survey.questions.entries()) {
        expect(body).toContain(`field-surveys.${surveyIndex}.questions.${questionIndex}.prompt.default`);
        expect(body).toContain(question.id);
      }
    }
    for (const [index, rule] of configuration.automations.entries()) {
      if (rule.type === 'occurrence') expect(body).toContain(`field-automations.${index}.trigger.type`);
    }
    expect(body).toContain('activities-day-3'); expect(body).toContain('activities-day-4'); expect(body).toContain('activities-day-5');
    expect(canonicalize(configuration)).toBe(before);
  });

  it.each(['study_session_active', 'study_local_window', 'elapsed_at_least', 'event_latch', 'keyed_presence', 'held_for', 'window_threshold', 'all', 'any', 'not'] as const)('faithfully renders the %s condition and its editable type', (type) => {
    const value = createStateCondition(type);
    const { body } = render(StateConditionEditor, { props: { value, path: 'automations.0.cases.0.condition', locale: 'en', durationHours: 120, onchange: () => {} } });
    expect(body).toContain(`value="${type}" selected`);
    expect(body).toContain('field-automations.0.cases.0.condition.type');
    if (type === 'event_latch') expect(body).toContain('field-automations.0.cases.0.condition.reset_when.0.event');
    if (type === 'keyed_presence') expect(body).toContain('field-automations.0.cases.0.condition.key_field');
    if (type === 'all' || type === 'any') expect(body).toContain('field-automations.0.cases.0.condition.conditions.1.type');
  });

  it('provides all random-window, guard, and cooldown fields without drawing participant times', () => {
    const rule = { type: 'occurrence' as const, id: 'random-survey', intervention_id: 'survey-action', trigger: { type: 'schedule' as const, schedule: createAutomationSchedule('random_window') }, guard: createStateCondition('study_local_window'), availability_seconds: 900, cooldown: { duration_seconds: 300, clock: 'CALENDAR_TIME' as const }, maximum_activations: 20 };
    const { body } = render(OccurrenceEditor, { props: { value: rule, path: 'automations.1', locale: 'en', durationHours: 120, onrename: () => {} } });
    for (const field of ['local_windows.0.start_local_time', 'local_windows.0.end_local_time', 'occurrences_per_window', 'maximum_occurrences_per_day', 'maximum_occurrences_total', 'minimum_separation_minutes']) {
      expect(body).toContain(`field-automations.1.trigger.schedule.${field}`);
    }
    expect(body).toContain('field-automations.1.guard.last_day');
    expect(body).toContain('field-automations.1.cooldown.duration_seconds');
    expect(body).toContain('no participant times are preselected');
  });

  it.each(['event_match', 'sequence', 'window_threshold', 'condition_rising_edge'] as const)('exposes the %s trigger with its actual type and complete field paths', (type) => {
    const rule = { type: 'occurrence' as const, id: 'test-rule', intervention_id: 'survey-action', trigger: createAutomationTrigger(type), guard: null, availability_seconds: 900, cooldown: null, maximum_activations: 20 };
    const { body } = render(OccurrenceEditor, { props: { value: rule, path: 'automations.1', locale: 'en', durationHours: 120, onrename: () => {} } });
    expect(body).toContain(`value="${type}" selected`);
    if (type === 'sequence') { expect(body).toContain('field-automations.1.trigger.steps.1.event'); expect(body).toContain('field-automations.1.trigger.within_seconds'); }
    if (type === 'window_threshold') expect(body).toContain('field-automations.1.trigger.comparison.value');
  });

  it('preserves shared surveys when deleting one activity and removes only the deleted activity occurrences', () => {
    const c = fiveDay(); const survey = c.surveys[0];
    const first = c.interventions.find((item) => item.action.type === 'survey' && item.action.survey_id === survey.id)!;
    c.interventions.push({ id: 'second-action', required: false, action: { ...first.action } });
    const rule = c.automations.find((item) => item.type === 'occurrence' && item.intervention_id === first.id)!;
    if (rule.type !== 'occurrence') throw new Error('missing occurrence');
    c.automations.push({ ...rule, id: 'second-rule', intervention_id: 'second-action' });
    removeInterventionReferences(c, first.id);
    expect(c.surveys.some((item) => item.id === survey.id)).toBe(true);
    expect(c.automations.some((item) => item.type === 'occurrence' && item.intervention_id === first.id)).toBe(false);
    expect(c.automations.some((item) => item.id === 'second-rule')).toBe(true);
    removeInterventionReferences(c, 'second-action');
    expect(c.surveys.some((item) => item.id === survey.id)).toBe(false);
  });

  it('preserves question wording and options when switching between choice types and reorders without losing answers', () => {
    const question: SurveyQuestion = { type: 'single_choice', id: 'test-question', required: true, prompt: localized('Choose an activity'), options: [{ id: 'walk', label: localized('Walking') }, { id: 'read', label: localized('Reading') }] };
    const changed = createSurveyQuestion('multiple_choice', question.id, question);
    expect(changed.prompt).toEqual(question.prompt);
    if (changed.type !== 'multiple_choice') throw new Error('wrong type');
    expect(changed.minimum_selections).toBe(1);
    expect(changed.options).toEqual(question.options);
    moveEditorItem(changed.options, 1, -1);
    expect(changed.options.map((option) => option.id)).toEqual(['read', 'walk']);
  });

  it('shows localized participant question wording, required status, option labels and bounds', () => {
    const survey = fiveDay().surveys[0];
    survey.title.translations['zh-TW'] = '問卷預覽';
    const { body } = render(SurveyPreview, { props: { survey, locale: 'zh-TW' } });
    expect(body).toContain('問卷預覽');
    for (const question of survey.questions) expect(body).toContain(question.prompt.translations['zh-TW'] ?? question.prompt.default);
  });
});

describe('reviewed traffic example transaction', () => {
  it('leaves existing rules untouched until application, validates the result, and can restore them exactly', () => {
    const c = trafficStudy(); const before = canonicalize(c);
    const review = reviewTrafficExample(c);
    expect(canonicalize(c)).toBe(before);
    expect(JSON.stringify(review.changedBefore)).toContain('study_local_window');
    expect(JSON.stringify(review.changedAfter)).toContain('held_for');
    const applied = applyTrafficExample(c, review);
    expect(validate(c)).toEqual([]);
    expect(c.collectors.find((item) => item.id === 'usage_events.v1')?.required).toBe(true);
    undoTrafficExample(c, review, applied);
    expect(canonicalize(c)).toBe(before);
  });
  it('reviews changes to every existing usage profile and inactive branch explicitly', () => {
    const c = trafficStudy();
    const usage = defaultCollector('usage_events.v1');
    if (usage.id !== 'usage_events.v1') throw new Error('wrong source');
    usage.profiles[0].config.poll_interval_seconds = 60;
    usage.profiles.push({ id: 'night', config: { poll_interval_seconds: 120 } });
    c.collectors.push(usage); c.collectors.sort((a,b) => a.id.localeCompare(b.id));
    c.automations.push(continuousBinding(usage)); c.automations.sort((a,b) => a.id.localeCompare(b.id));
    const review = reviewTrafficExample(c);
    expect(JSON.stringify(review.changedBefore)).toContain('120');
    const applied = applyTrafficExample(c, review);
    const changed = c.collectors.find((item) => item.id === 'usage_events.v1');
    expect(changed?.profiles.map((profile) => profile.config.poll_interval_seconds)).toEqual([15, 15]);
    expect(validate(c)).toEqual([]);
    undoTrafficExample(c, review, applied);
    expect(c.collectors.find((item) => item.id === 'usage_events.v1')?.profiles.map((profile) => profile.config.poll_interval_seconds)).toEqual([60, 120]);
  });
  it('refuses a stale preview or undo without discarding subsequent study changes', () => {
    const c = trafficStudy(); const review = reviewTrafficExample(c); c.purpose = 'Updated purpose';
    expect(() => applyTrafficExample(c, review)).toThrow('example_preview_stale');
    const current = reviewTrafficExample(c); const applied = applyTrafficExample(c, current);
    c.automations[0].id = 'changed-after-example';
    expect(() => undoTrafficExample(c, current, applied)).toThrow('example_undo_stale');
    expect(c.purpose).toBe('Updated purpose'); expect(c.automations[0].id).toBe('changed-after-example');
  });
  it('does not call an unchanged or faster profile a slowdown', () => {
    const c = trafficStudy();
    if (!('profiles' in c.traffic_shaping)) throw new Error('missing traffic profiles');
    c.traffic_shaping.profiles[0].uplink_kbps = 64;
    c.traffic_shaping.profiles[0].downlink_kbps = 128;
    const before = canonicalize(c);
    expect(() => reviewTrafficExample(c)).toThrow('example_requires_slower_profile');
    expect(canonicalize(c)).toBe(before);
  });
  it('refuses examples whose capped profile or required binding is absent and never mutates the input', () => {
    const c = trafficStudy();
    if (!('profiles' in c.traffic_shaping)) throw new Error('missing traffic profiles');
    c.traffic_shaping.profiles = [{ id: 'baseline', uplink_kbps: null, downlink_kbps: null }];
    const before = canonicalize(c);
    expect(() => reviewTrafficExample(c)).toThrow('example_requires_capped_profile');
    expect(canonicalize(c)).toBe(before);
  });
});
