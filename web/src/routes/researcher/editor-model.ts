import { javaDoubleString } from '$lib/particeps/wire-float';
import { RESEARCHER_EVENTS, eventContract } from '$lib/particeps/registry';
import type { ConditionKind, RegistryFieldContract } from '$lib/particeps/generated/event-source-registry';
import type { AutomationSchedule, AutomationTrigger, EventMatcher, FieldPredicate, LocalizedText, StateCondition, StudyConfiguration, SurveyQuestion } from '$lib/particeps/types';

export function freshEditorId(stem: string, values: Iterable<string>): string {
  const used = new Set(values);
  let index = 1;
  while (used.has(`${stem}-${index}`)) index += 1;
  return `${stem}-${index}`;
}
export function moveEditorItem<T>(items: T[], index: number, direction: -1 | 1): void {
  const next = index + direction;
  if (index < 0 || index >= items.length || next < 0 || next >= items.length) return;
  const [item] = items.splice(index, 1);
  items.splice(next, 0, item);
}
export function blankLocalizedText(): LocalizedText { return { default: '', translations: {} }; }
export function createSurveyQuestion(type: SurveyQuestion['type'], id: string, previous?: SurveyQuestion): SurveyQuestion {
  const base = { id, prompt: previous?.prompt ?? blankLocalizedText(), required: previous?.required ?? false };
  if (type === 'short_text') return { ...base, type, maximum_length: 200 };
  if (type === 'scale') return { ...base, type, minimum: 1, maximum: 5, minimum_label: blankLocalizedText(), maximum_label: blankLocalizedText() };
  const options = previous && (previous.type === 'single_choice' || previous.type === 'multiple_choice')
    ? previous.options : [1, 2].map((index) => ({ id: `option-${index}`, label: blankLocalizedText() }));
  return type === 'single_choice' ? { ...base, type, options } : {
    ...base, type, options, minimum_selections: base.required ? 1 : 0, maximum_selections: options.length
  };
}
export function eventIdentityKey(matcher: EventMatcher): string {
  return `${matcher.event.source_id}:${matcher.event.schema_version}:${matcher.event.event_type}`;
}
export function createEventMatcher(kind: ConditionKind = 'EVENT_MATCH'): EventMatcher {
  const contract = RESEARCHER_EVENTS.find(({ event }) => (event.trigger.condition_kinds as readonly ConditionKind[]).includes(kind));
  if (!contract) throw new Error(`No researcher event supports ${kind}`);
  return { event: { source_id: contract.source.source_id, schema_version: contract.source.schema_version, event_type: contract.event.event_type }, predicates: [] };
}
export function defaultPredicateValue(field: RegistryFieldContract): string {
  if (field.wire_type === 'enum') return field.enum_values[0] ?? '';
  if (field.wire_type === 'boolean') return 'true';
  if (field.wire_type === 'float32' || field.wire_type === 'float64') return javaDoubleString(field.minimum ?? 0);
  if (['int32', 'int64_decimal', 'uint64_decimal'].includes(field.wire_type)) return String(field.minimum ?? 0);
  return '';
}
export function createFieldPredicate(fieldName: string, matcher: EventMatcher): FieldPredicate {
  const field = eventContract(matcher.event)?.event.fields[fieldName];
  if (!field || !field.operators.length) throw new Error(`Field ${fieldName} cannot be used in predicates`);
  const operator = field.operators.includes('eq') ? 'eq' : field.operators[0];
  return operator === 'in' ? { field: fieldName, operator, values: [defaultPredicateValue(field)] }
    : { field: fieldName, operator, value: defaultPredicateValue(field) };
}
export function createAutomationSchedule(type: AutomationSchedule['type']): AutomationSchedule {
  switch (type) {
    case 'one_time': return { type, offset_minutes: 0, clock: 'ACTIVE_RUNNING_TIME' };
    case 'interval': return { type, start_offset_minutes: 0, interval_minutes: 1_440, clock: 'ACTIVE_RUNNING_TIME' };
    case 'daily_local': return { type, local_time: '09:00' };
    case 'random_window': return { type, local_windows: [{ start_local_time: '09:00', end_local_time: '12:00' }], occurrences_per_window: 1, maximum_occurrences_per_day: 1, maximum_occurrences_total: 14, minimum_separation_minutes: 60 };
  }
}
export function createStateCondition(type: StateCondition['type']): StateCondition {
  switch (type) {
    case 'study_session_active': return { type };
    case 'elapsed_at_least': return { type, duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' };
    case 'study_local_window': return { type, first_day: 1, last_day: 1, start_local_time: '09:00', end_local_time: '17:00' };
    case 'held_for': return { type, condition: { type: 'study_session_active' }, duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' };
    case 'not': return { type, condition: { type: 'study_session_active' } };
    case 'all': case 'any': return { type, conditions: [{ type: 'study_session_active' }, { type: 'elapsed_at_least', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' }] };
    case 'event_latch': return { type, set_when: [createEventMatcher()], reset_when: [createEventMatcher()] };
    case 'keyed_presence': return { type, key_field: 'activity_component_token', enter_when: [{ event: { source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_RESUMED' }, predicates: [] }], exit_when: ['ACTIVITY_PAUSED', 'ACTIVITY_STOPPED'].map((event_type) => ({ event: { source_id: 'usage_events.v1', schema_version: 1, event_type }, predicates: [] })) };
    case 'window_threshold': return { type, selector: createEventMatcher('WINDOW_COUNT'), window_seconds: 60, evaluation_clock: 'OBSERVED_RESEARCH_TIME', aggregate: { type: 'count' }, comparison: { operator: 'gte', value: '1' } };
  }
}
export function createAutomationTrigger(type: AutomationTrigger['type']): AutomationTrigger {
  switch (type) {
    case 'schedule': return { type, schedule: createAutomationSchedule('one_time') };
    case 'event_match': return { type, selector: createEventMatcher(), evaluation_clock: 'OBSERVED_RESEARCH_TIME' };
    case 'condition_rising_edge': return { type, condition: { type: 'study_session_active' } };
    case 'sequence': return { type, steps: [createEventMatcher('SEQUENCE_STEP'), createEventMatcher('SEQUENCE_STEP')], within_seconds: 60, evaluation_clock: 'OBSERVED_RESEARCH_TIME' };
    case 'window_threshold': return createStateCondition('window_threshold') as Extract<AutomationTrigger, { type: 'window_threshold' }>;
  }
}
/** Removing an activity removes only its own occurrences and an unshared attached survey. */
export function removeInterventionReferences(configuration: StudyConfiguration, interventionId: string): void {
  const item = configuration.interventions.find((intervention) => intervention.id === interventionId);
  if (!item) return;
  const surveyId = item.action.type === 'survey' ? item.action.survey_id : null;
  configuration.interventions = configuration.interventions.filter((intervention) => intervention !== item);
  configuration.automations = configuration.automations.filter((automation) => automation.type !== 'occurrence' || automation.intervention_id !== interventionId);
  if (surveyId !== null && !configuration.interventions.some((intervention) => intervention.action.type === 'survey' && intervention.action.survey_id === surveyId)) {
    configuration.surveys = configuration.surveys.filter((survey) => survey.id !== surveyId);
  }
}
