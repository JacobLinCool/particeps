/** Exact Protocol v1 validation and authoring defaults. */

import { canonicalConfigurationBytes, formatInstant, isCanonicalDecimal, parseInstant } from './canonical';
import { decodeBase64Url } from './crypto';
import type { ConditionKind, RegistryEventContract } from './generated/event-source-registry';
import { canonicalPredicateFieldValue, collectorContract, defaultProfileConfiguration, eventContract, validateProfileConfiguration } from './registry';
import {
  ANDROID_APPLICATION_ID_PATTERN,
  ASSIGNED_PARTICIPANT_ID_PATTERN,
  BOUNDS,
  COLLECTOR_ORDER,
  DEFAULT_MINIMUM_CLIENT_VERSION,
  ID_PATTERN,
  MAXIMUM_CONFIGURATION_BYTES,
  MAXIMUM_LOCAL_BYTES,
  MINIMUM_LOCAL_BYTES,
  PARTICEPS_APPLICATION_ID,
  PLATFORM,
  SCHEMA_VERSION,
  UPLOAD_MAXIMUM_INTERVAL_MINUTES,
  UPLOAD_MINIMUM_INTERVAL_MINUTES,
  trafficShapingEnabled,
  type AutomationDefinition,
  type AutomationSchedule,
  type AutomationTrigger,
  type CollectorConfig,
  type CollectorId,
  type EventMatcher,
  type FieldPredicate,
  type LocalizedText,
  type ResourceBindingAutomation,
  type StateCondition,
  type StudyConfiguration,
  type SurveyQuestion
} from './types';

export type IssueCode =
  | 'required' | 'id_format' | 'length_range' | 'number_range' | 'integer' | 'instant'
  | 'window_order' | 'duplicate_id' | 'sorted_unique' | 'profile_invalid' | 'endpoint_scheme'
  | 'endpoint_host' | 'document_too_large' | 'signer_missing' | 'export_key_missing'
  | 'key_invalid' | 'language_tag' | 'unknown_reference' | 'selection_bounds'
  | 'automation_invalid' | 'unknown_event' | 'unknown_field' | 'unsupported_operator'
  | 'canonical_value' | 'resource_owner' | 'trigger_source_liveness' | 'dependency_cycle'
  | 'unbounded_state';

export interface Issue {
  path: string;
  code: IssueCode;
  bounds?: { min: number; max: number };
}

type Bounds = readonly [number, number];
const DEFAULT_VALIDITY_DAYS = 90;
export const DEFAULT_LOCAL_BYTES = 1_024 * 1_024 * 1_024;

export function validate(configuration: StudyConfiguration): Issue[] {
  const issues: Issue[] = [];
  if (configuration.schema_version !== SCHEMA_VERSION) issues.push(range('schema_version', [1, 1]));
  if (configuration.platform !== PLATFORM) issues.push({ path: 'platform', code: 'required' });
  identifier(issues, 'experiment_id', configuration.experiment_id);
  identifier(issues, 'configuration_id', configuration.configuration_id);
  if (configuration.assigned_participant_id !== null &&
    (!ASSIGNED_PARTICIPANT_ID_PATTERN.test(configuration.assigned_participant_id) ||
      new TextEncoder().encode(configuration.assigned_participant_id).length > 64)) {
    issues.push({ path: 'assigned_participant_id', code: 'id_format' });
  }
  const issued = instant(issues, 'issued_at', configuration.issued_at);
  const expires = instant(issues, 'expires_at', configuration.expires_at);
  if (issued && expires && compareInstant(issued, expires) >= 0) issues.push({ path: 'expires_at', code: 'window_order' });
  decimal(issues, 'minimum_client_version', configuration.minimum_client_version, BOUNDS.minimumClientVersion);
  text(issues, 'title', configuration.title, BOUNDS.title);
  text(issues, 'researcher.name', configuration.researcher.name, BOUNDS.researcherName);
  text(issues, 'researcher.contact', configuration.researcher.contact, BOUNDS.researcherContact);
  text(issues, 'purpose', configuration.purpose, BOUNDS.purpose);
  integer(issues, 'duration_hours', configuration.duration_hours, BOUNDS.durationHours);
  text(issues, 'consent.document_version', configuration.consent.document_version, BOUNDS.consentDocumentVersion);
  text(issues, 'consent.summary', configuration.consent.summary, BOUNDS.consentSummary);

  validateCollectors(issues, configuration);
  const surveyIds = validateSurveys(issues, configuration);
  const interventionIds = validateInterventions(issues, configuration, surveyIds);
  validateTrafficShaping(issues, configuration);
  validateAutomations(issues, configuration, interventionIds);

  integer(issues, 'storage.maximum_local_bytes', configuration.storage.maximum_local_bytes, [MINIMUM_LOCAL_BYTES, MAXIMUM_LOCAL_BYTES]);
  identifier(issues, 'signer.key_id', configuration.signer.key_id);
  rawPublicKey(issues, 'signer.public_key', configuration.signer.public_key, 'signer_missing');
  identifier(issues, 'export.researcher_key_id', configuration.export.researcher_key_id);
  rawPublicKey(issues, 'export.hpke_public_key', configuration.export.hpke_public_key, 'export_key_missing');
  validateUpload(issues, configuration);
  if (canonicalConfigurationBytes(configuration).length > MAXIMUM_CONFIGURATION_BYTES) {
    issues.push({ path: '', code: 'document_too_large', bounds: { min: 2, max: MAXIMUM_CONFIGURATION_BYTES } });
  }
  return stableIssues(issues);
}

export function emptyConfiguration(): StudyConfiguration {
  const now = Math.floor(Date.now() / 1_000);
  return {
    schema_version: SCHEMA_VERSION,
    platform: PLATFORM,
    experiment_id: '', configuration_id: '', assigned_participant_id: null,
    issued_at: formatInstant({ second: now, nano: 0 }),
    expires_at: formatInstant({ second: now + DEFAULT_VALIDITY_DAYS * 86_400, nano: 0 }),
    minimum_client_version: DEFAULT_MINIMUM_CLIENT_VERSION,
    title: '', researcher: { name: '', contact: '' }, purpose: '', duration_hours: 24,
    consent: { document_version: '', summary: '' },
    collectors: [], surveys: [], interventions: [], automations: [], traffic_shaping: {},
    storage: { maximum_local_bytes: DEFAULT_LOCAL_BYTES },
    signer: { key_id: '', public_key: '' },
    export: { researcher_key_id: '', hpke_public_key: '' },
    upload: null
  };
}

/** Registry-backed continuous collector macro used by the authoring UI. */
export function defaultCollector(id: CollectorId): CollectorConfig {
  return { id, required: false, profiles: [{ id: 'continuous', config: defaultProfileConfiguration(id) }] } as CollectorConfig;
}

export function continuousBinding(collector: CollectorConfig): ResourceBindingAutomation {
  return {
    type: 'resource_binding', id: `bind-${collector.id.replace(/\.v1$/, '').replaceAll('_', '-')}`,
    resource: { kind: 'collector', id: collector.id },
    cases: [{ condition: { type: 'study_session_active' }, profile_id: collector.profiles[0]?.id ?? null }],
    default_profile_id: null
  };
}

/**
 * The authoring macro's exact shape. A required collector uses the same profile as its default so
 * the closed-world compiler can prove that no automation state makes the resource inactive;
 * lifecycle pause and terminal transitions still revoke every resource outside the active study.
 */
export function continuousBindingProfile(
  binding: ResourceBindingAutomation
): string | null {
  if (binding.resource.kind !== 'collector' || binding.cases.length !== 1) return null;
  const entry = binding.cases[0];
  if (entry?.condition.type !== 'study_session_active' || entry.profile_id === null) return null;
  if (binding.default_profile_id !== null && binding.default_profile_id !== entry.profile_id) {
    return null;
  }
  return entry.profile_id;
}

function validateCollectors(issues: Issue[], configuration: StudyConfiguration): void {
  if (configuration.collectors.length > 64) issues.push(range('collectors', [0, 64]));
  if (!sortedUnique(configuration.collectors.map((collector) => collector.id))) issues.push({ path: 'collectors', code: 'sorted_unique' });
  configuration.collectors.forEach((collector, index) => {
    const path = `collectors.${index}`;
    if (!(COLLECTOR_ORDER as readonly string[]).includes(collector.id)) {
      issues.push({ path: `${path}.id`, code: 'unknown_reference' });
      return;
    }
    if (collector.profiles.length < 1 || collector.profiles.length > 64) issues.push(range(`${path}.profiles`, [1, 64]));
    if (!sortedUnique(collector.profiles.map((profile) => profile.id))) issues.push({ path: `${path}.profiles`, code: 'sorted_unique' });
    collector.profiles.forEach((profile, profileIndex) => {
      const profilePath = `${path}.profiles.${profileIndex}`;
      identifier(issues, `${profilePath}.id`, profile.id);
      for (const problem of validateProfileConfiguration(collector.id, profile.config)) {
        issues.push({ path: `${profilePath}.config.${problem.field}`, code: 'profile_invalid' });
      }
    });
  });
}

function validateSurveys(issues: Issue[], configuration: StudyConfiguration): Set<string> {
  const ids = new Set<string>();
  if (configuration.surveys.length > 128) issues.push(range('surveys', [0, 128]));
  configuration.surveys.forEach((survey, index) => {
    const path = `surveys.${index}`;
    identifier(issues, `${path}.id`, survey.id);
    duplicate(issues, ids, `${path}.id`, survey.id);
    localized(issues, `${path}.title`, survey.title);
    localized(issues, `${path}.description`, survey.description);
    if (survey.questions.length < 1 || survey.questions.length > 100) issues.push(range(`${path}.questions`, [1, 100]));
    const questionIds = new Set<string>();
    survey.questions.forEach((question, questionIndex) => {
      const questionPath = `${path}.questions.${questionIndex}`;
      identifier(issues, `${questionPath}.id`, question.id);
      duplicate(issues, questionIds, `${questionPath}.id`, question.id);
      validateQuestion(issues, questionPath, question);
    });
  });
  return ids;
}

function validateInterventions(issues: Issue[], configuration: StudyConfiguration, surveyIds: Set<string>): Set<string> {
  const ids = new Set<string>();
  if (configuration.interventions.length > 128) issues.push(range('interventions', [0, 128]));
  if (!sortedUnique(configuration.interventions.map((item) => item.id))) issues.push({ path: 'interventions', code: 'sorted_unique' });
  configuration.interventions.forEach((intervention, index) => {
    const path = `interventions.${index}`;
    identifier(issues, `${path}.id`, intervention.id);
    duplicate(issues, ids, `${path}.id`, intervention.id);
    text(issues, `${path}.action.notification_title`, intervention.action.notification_title, BOUNDS.notificationTitle);
    text(issues, `${path}.action.notification_message`, intervention.action.notification_message, BOUNDS.notificationMessage);
    if (intervention.action.type === 'survey' && !surveyIds.has(intervention.action.survey_id)) {
      issues.push({ path: `${path}.action.survey_id`, code: 'unknown_reference' });
    }
  });
  return ids;
}

function validateTrafficShaping(issues: Issue[], configuration: StudyConfiguration): void {
  if (!trafficShapingEnabled(configuration.traffic_shaping)) return;
  const shaping = configuration.traffic_shaping;
  if (shaping.target_packages.length < 1 || shaping.target_packages.length > 64) issues.push(range('traffic_shaping.target_packages', [1, 64]));
  if (!sortedUnique(shaping.target_packages)) issues.push({ path: 'traffic_shaping.target_packages', code: 'sorted_unique' });
  shaping.target_packages.forEach((name, index) => {
    if (!ANDROID_APPLICATION_ID_PATTERN.test(name) || name === PARTICEPS_APPLICATION_ID) {
      issues.push({ path: `traffic_shaping.target_packages.${index}`, code: 'id_format' });
    }
  });
  if (shaping.profiles.length < 1 || shaping.profiles.length > 64) issues.push(range('traffic_shaping.profiles', [1, 64]));
  if (!sortedUnique(shaping.profiles.map((profile) => profile.id))) issues.push({ path: 'traffic_shaping.profiles', code: 'sorted_unique' });
  shaping.profiles.forEach((profile, index) => {
    const path = `traffic_shaping.profiles.${index}`;
    identifier(issues, `${path}.id`, profile.id);
    nullableInteger(issues, `${path}.uplink_kbps`, profile.uplink_kbps, BOUNDS.trafficKbps);
    nullableInteger(issues, `${path}.downlink_kbps`, profile.downlink_kbps, BOUNDS.trafficKbps);
  });
}

function validateAutomations(issues: Issue[], configuration: StudyConfiguration, interventionIds: Set<string>): void {
  const resources = new Map<string, { required: boolean; profiles: Set<string> }>();
  for (const collector of configuration.collectors) resources.set(`collector:${collector.id}`, {
    required: collector.required, profiles: new Set(collector.profiles.map((profile) => profile.id))
  });
  if (trafficShapingEnabled(configuration.traffic_shaping)) resources.set('actuator:traffic-shaping.v1', {
    required: true, profiles: new Set(configuration.traffic_shaping.profiles.map((profile) => profile.id))
  });
  if (resources.size > 64) issues.push(range('collectors', [0, 64]));
  if (configuration.automations.length > 128) issues.push(range('automations', [0, 128]));
  if (!sortedUnique(configuration.automations.map((automation) => automation.id))) issues.push({ path: 'automations', code: 'sorted_unique' });
  if (configuration.automations.length === 0 && (resources.size > 0 || interventionIds.size > 0)) {
    issues.push({ path: 'automations', code: 'required' });
  }
  let lifetime = 0;
  const usedInterventions = new Set<string>();
  const owners = new Map<string, ResourceBindingAutomation[]>();
  const referencedCollectorIds = new Set<string>();
  const dependencies = new Map<string, Set<string>>();
  let maximumConcurrentTimers = 0;
  configuration.automations.forEach((automation, index) => {
    const path = `automations.${index}`;
    identifier(issues, `${path}.id`, automation.id);
    if (automation.type === 'occurrence') {
      lifetime += Math.max(0, automation.maximum_activations);
      if (!interventionIds.has(automation.intervention_id)) issues.push({ path: `${path}.intervention_id`, code: 'unknown_reference' });
      else usedInterventions.add(automation.intervention_id);
      integer(issues, `${path}.availability_seconds`, automation.availability_seconds, BOUNDS.availabilitySeconds);
      integer(issues, `${path}.maximum_activations`, automation.maximum_activations, [1, 512]);
      if (automation.cooldown) integer(issues, `${path}.cooldown.duration_seconds`, automation.cooldown.duration_seconds, [1, 31_536_000]);
      validateTrigger(issues, `${path}.trigger`, automation.trigger, configuration, referencedCollectorIds);
      if (automation.guard) validateCondition(issues, `${path}.guard`, automation.guard, configuration, referencedCollectorIds, { nodes: 0 }, 1);
      if (triggerConditionNodeCount(automation.trigger) + (automation.guard ? conditionNodeCount(automation.guard) : 0) > 64) {
        issues.push({ path, code: 'automation_invalid' });
      }
    } else {
      const key = `${automation.resource.kind}:${automation.resource.id}`;
      const owned = owners.get(key) ?? [];
      owned.push(automation); owners.set(key, owned);
      const resource = resources.get(key);
      if (!resource) issues.push({ path: `${path}.resource`, code: 'unknown_reference' });
      if (automation.cases.length < 1 || automation.cases.length > 16) issues.push(range(`${path}.cases`, [1, 16]));
      const counter = { nodes: 0 };
      const resourceDependencies = new Set<string>();
      automation.cases.forEach((entry, caseIndex) => {
        validateCondition(issues, `${path}.cases.${caseIndex}.condition`, entry.condition, configuration, referencedCollectorIds, counter, 1);
        for (const matcher of conditionMatchers(entry.condition)) {
          const contract = eventContract(matcher.event);
          if (contract?.source.source_kind === 'COLLECTOR') resourceDependencies.add(`collector:${matcher.event.source_id}`);
        }
        if (entry.profile_id !== null && !resource?.profiles.has(entry.profile_id)) issues.push({ path: `${path}.cases.${caseIndex}.profile_id`, code: 'unknown_reference' });
      });
      dependencies.set(key, resourceDependencies);
      if (automation.default_profile_id !== null && !resource?.profiles.has(automation.default_profile_id)) issues.push({ path: `${path}.default_profile_id`, code: 'unknown_reference' });
    }
    maximumConcurrentTimers += maximumConcurrentTimerCount(automation);
  });
  if (lifetime > 512) issues.push({ path: 'automations', code: 'automation_invalid' });
  if (maximumConcurrentTimers > 512) issues.push({ path: 'automations', code: 'automation_invalid' });
  for (const id of interventionIds) if (!usedInterventions.has(id)) issues.push({ path: 'interventions', code: 'unknown_reference' });
  for (const [key, resource] of resources) {
    const resourceOwners = owners.get(key) ?? [];
    if (resourceOwners.length !== 1) issues.push({ path: 'automations', code: 'resource_owner' });
    const owner = resourceOwners[0];
    if (resource.required && owner && !bindingAlwaysActive(owner)) {
      issues.push({ path: 'automations', code: 'trigger_source_liveness' });
    }
  }
  for (const sourceId of referencedCollectorIds) {
    const collector = configuration.collectors.find((item) => item.id === sourceId);
    const owner = owners.get(`collector:${sourceId}`)?.[0];
    if (!collector?.required || !owner || !bindingAlwaysActive(owner)) {
      issues.push({ path: 'automations', code: 'trigger_source_liveness' });
    }
    if (sourceId === 'usage_events.v1' && collector?.profiles.some((profile) =>
      (profile.config as { poll_interval_seconds?: number }).poll_interval_seconds !== 15)) {
      issues.push({ path: `collectors.${configuration.collectors.indexOf(collector)}.profiles`, code: 'profile_invalid' });
    }
  }
  if (hasCycle(dependencies)) issues.push({ path: 'automations', code: 'dependency_cycle' });
}

function bindingAlwaysActive(binding: ResourceBindingAutomation): boolean {
  for (const entry of binding.cases) {
    if (entry.profile_id === null) return false;
    if (entry.condition.type === 'study_session_active') return true;
  }
  return binding.default_profile_id !== null;
}

function validateTrigger(
  issues: Issue[], path: string, trigger: AutomationTrigger, configuration: StudyConfiguration,
  referenced: Set<string>
): void {
  switch (trigger.type) {
    case 'event_match':
      validateMatcher(issues, `${path}.selector`, trigger.selector, trigger.evaluation_clock, 'EVENT_MATCH', referenced); return;
    case 'sequence':
      if (trigger.steps.length < 2 || trigger.steps.length > 16) issues.push(range(`${path}.steps`, [2, 16]));
      integer(issues, `${path}.within_seconds`, trigger.within_seconds, [1, 604_800]);
      trigger.steps.forEach((matcher, index) => validateMatcher(
        issues, `${path}.steps.${index}`, matcher, trigger.evaluation_clock, 'SEQUENCE_STEP', referenced
      ));
      retainedBound(issues, `${path}.steps`, trigger.steps, trigger.within_seconds);
      return;
    case 'window_threshold':
      validateWindow(issues, path, trigger, referenced); return;
    case 'condition_rising_edge':
      validateCondition(issues, `${path}.condition`, trigger.condition, configuration, referenced, { nodes: 0 }, 1); return;
    case 'schedule': validateSchedule(issues, `${path}.schedule`, trigger.schedule, configuration.duration_hours * 60); return;
  }
}

function validateCondition(
  issues: Issue[], path: string, condition: StateCondition, configuration: StudyConfiguration,
  referenced: Set<string>, counter: { nodes: number }, depth: number
): void {
  counter.nodes += 1;
  if (depth > 8 || counter.nodes > 64) issues.push({ path, code: 'automation_invalid' });
  switch (condition.type) {
    case 'study_session_active': return;
    case 'event_latch':
      matcherList(issues, `${path}.set_when`, condition.set_when, 'EVENT_MATCH', referenced);
      matcherList(issues, `${path}.reset_when`, condition.reset_when, 'EVENT_MATCH', referenced); return;
    case 'keyed_presence': {
      matcherList(issues, `${path}.enter_when`, condition.enter_when, 'KEYED_PRESENCE_ENTER', referenced);
      matcherList(issues, `${path}.exit_when`, condition.exit_when, 'KEYED_PRESENCE_EXIT', referenced);
      const matchers = [...condition.enter_when, ...condition.exit_when];
      const eventContracts = matchers.map((matcher) => eventContract(matcher.event)?.event);
      const fields = eventContracts.map((event) => event?.fields[condition.key_field]);
      if (fields.some((field) => !field?.required || !field.keyed_presence_key) ||
        new Set(fields.map((field) => field?.wire_type)).size !== 1) issues.push({ path: `${path}.key_field`, code: 'automation_invalid' });
      if (!presenceContractsMatch(condition.key_field, eventContracts, condition.enter_when.length)) {
        issues.push({ path, code: 'automation_invalid' });
      }
      return;
    }
    case 'held_for':
      integer(issues, `${path}.duration_seconds`, condition.duration_seconds, [1, configuration.duration_hours * 3_600]);
      validateCondition(issues, `${path}.condition`, condition.condition, configuration, referenced, counter, depth + 1); return;
    case 'elapsed_at_least':
      integer(issues, `${path}.duration_seconds`, condition.duration_seconds, [1, configuration.duration_hours * 3_600]); return;
    case 'window_threshold': validateWindow(issues, path, condition, referenced); return;
    case 'all': case 'any':
      if (condition.conditions.length < 2 || condition.conditions.length > 8) issues.push(range(`${path}.conditions`, [2, 8]));
      condition.conditions.forEach((child, index) => validateCondition(issues, `${path}.conditions.${index}`, child, configuration, referenced, counter, depth + 1)); return;
    case 'not': validateCondition(issues, `${path}.condition`, condition.condition, configuration, referenced, counter, depth + 1);
  }
}

function validateWindow(
  issues: Issue[], path: string,
  value: Extract<AutomationTrigger | StateCondition, { type: 'window_threshold' }>,
  referenced: Set<string>
): void {
  const conditionKind: ConditionKind = value.aggregate.type === 'sum' ? 'WINDOW_SUM' : 'WINDOW_COUNT';
  validateMatcher(issues, `${path}.selector`, value.selector, value.evaluation_clock, conditionKind, referenced);
  integer(issues, `${path}.window_seconds`, value.window_seconds, [1, 604_800]);
  if (value.aggregate.type === 'sum') {
    const field = eventContract(value.selector.event)?.event.fields[value.aggregate.field];
    if (!field?.required || !field.window_sum || !['int32', 'int64_decimal', 'uint64_decimal'].includes(field.wire_type)) {
      issues.push({ path: `${path}.aggregate.field`, code: 'automation_invalid' });
    }
  }
  if (!/^(0|-?[1-9][0-9]*)$/.test(value.comparison.value)) issues.push({ path: `${path}.comparison.value`, code: 'canonical_value' });
  retainedBound(issues, `${path}.selector`, [value.selector], value.window_seconds);
}

function validateMatcher(
  issues: Issue[], path: string, matcher: EventMatcher, clock: string | null,
  conditionKind: ConditionKind, referenced: Set<string>
): void {
  const contract = eventContract(matcher.event);
  if (!contract || contract.event.trigger.scope !== 'RESEARCHER') {
    issues.push({ path: `${path}.event`, code: 'unknown_event' });
    return;
  }
  if (contract.source.source_kind === 'COLLECTOR') referenced.add(contract.source.source_id);
  if (!contract.event.trigger.condition_kinds.includes(conditionKind)) {
    issues.push({ path, code: 'automation_invalid' });
  }
  if (clock && !contract.event.clock.automation_time_inputs.includes(clock as never)) issues.push({ path, code: 'automation_invalid' });
  if (matcher.predicates.length > 16) issues.push(range(`${path}.predicates`, [0, 16]));
  if (!unique(matcher.predicates.map((predicate) => predicate.field))) issues.push({ path: `${path}.predicates`, code: 'duplicate_id' });
  matcher.predicates.forEach((predicate, index) => validatePredicate(issues, `${path}.predicates.${index}`, predicate, contract.event));
}

function validatePredicate(
  issues: Issue[], path: string, predicate: FieldPredicate,
  event: NonNullable<ReturnType<typeof eventContract>>['event']
): void {
  const field = event.fields[predicate.field];
  if (!field) { issues.push({ path: `${path}.field`, code: 'unknown_field' }); return; }
  if (!field.operators.includes(predicate.operator)) issues.push({ path: `${path}.operator`, code: 'unsupported_operator' });
  if (predicate.operator === 'in') {
    if (predicate.values.length < 1 || predicate.values.length > 64) issues.push(range(`${path}.values`, [1, 64]));
    if (!sortedUnique(predicate.values)) issues.push({ path: `${path}.values`, code: 'sorted_unique' });
    predicate.values.forEach((value, index) => {
      if (!canonicalPredicateFieldValue(event, predicate.field, value)) issues.push({ path: `${path}.values.${index}`, code: 'canonical_value' });
    });
  } else if (!canonicalPredicateFieldValue(event, predicate.field, predicate.value)) {
    issues.push({ path: `${path}.value`, code: 'canonical_value' });
  }
}

function retainedBound(issues: Issue[], path: string, matchers: EventMatcher[], seconds: number): void {
  let retained = 0;
  for (const matcher of matchers) {
    const bound = eventContract(matcher.event)?.event.rate_bound;
    const events = bound?.maximum_events_per_period;
    const period = bound?.period_seconds;
    if (!events || !period || !['HARD', 'CONFIGURATION_DERIVED'].includes(bound.kind)) {
      issues.push({ path, code: 'unbounded_state' });
      return;
    }
    retained += events * Math.ceil(seconds / period);
  }
  if (retained > 4_096) issues.push({ path, code: 'unbounded_state' });
}

function validateSchedule(issues: Issue[], path: string, schedule: AutomationSchedule, studyMinutes: number): void {
  switch (schedule.type) {
    case 'one_time':
      if (!Number.isInteger(schedule.offset_minutes) || schedule.offset_minutes < 0 || schedule.offset_minutes >= studyMinutes) issues.push({ path: `${path}.offset_minutes`, code: 'automation_invalid' }); return;
    case 'interval':
      if (!Number.isInteger(schedule.start_offset_minutes) || schedule.start_offset_minutes < 0 || schedule.start_offset_minutes >= studyMinutes) issues.push({ path: `${path}.start_offset_minutes`, code: 'automation_invalid' });
      integer(issues, `${path}.interval_minutes`, schedule.interval_minutes, [1, 525_600]); return;
    case 'daily_local': if (!localTime(schedule.local_time)) issues.push({ path: `${path}.local_time`, code: 'automation_invalid' }); return;
    case 'random_window': {
      if (schedule.local_windows.length < 1 || schedule.local_windows.length > 8) issues.push(range(`${path}.local_windows`, [1, 8]));
      let previousEnd = -1;
      const parsed = schedule.local_windows.map((window, index) => {
        const start = localMinute(window.start_local_time); const end = localMinute(window.end_local_time);
        if (start === null || end === null || start >= end || start < previousEnd) issues.push({ path: `${path}.local_windows.${index}`, code: 'automation_invalid' });
        previousEnd = end ?? previousEnd;
        if (start !== null && end !== null && end - start < 1 + (schedule.occurrences_per_window - 1) * schedule.minimum_separation_minutes) {
          issues.push({ path: `${path}.local_windows.${index}`, code: 'automation_invalid' });
        }
        return start === null || end === null ? null : { start, end };
      });
      integer(issues, `${path}.occurrences_per_window`, schedule.occurrences_per_window, [1, 8]);
      integer(issues, `${path}.maximum_occurrences_per_day`, schedule.maximum_occurrences_per_day, [1, 64]);
      integer(issues, `${path}.maximum_occurrences_total`, schedule.maximum_occurrences_total, [1, 512]);
      integer(issues, `${path}.minimum_separation_minutes`, schedule.minimum_separation_minutes, [1, 1_440]);
      if (schedule.maximum_occurrences_per_day > schedule.local_windows.length * schedule.occurrences_per_window) {
        issues.push({ path: `${path}.maximum_occurrences_per_day`, code: 'automation_invalid' });
      }
      if (parsed.length > 0 && parsed.every((window): window is { start: number; end: number } => window !== null)) {
        parsed.forEach((window, index) => {
          const next = parsed[(index + 1) % parsed.length];
          const nextStart = next.start + (index === parsed.length - 1 ? 1_440 : 0);
          if (nextStart - (window.end - 1) < schedule.minimum_separation_minutes) {
            issues.push({ path: `${path}.local_windows`, code: 'automation_invalid' });
          }
        });
      }
    }
  }
}

function validateQuestion(issues: Issue[], path: string, question: SurveyQuestion): void {
  localized(issues, `${path}.prompt`, question.prompt);
  if (question.type === 'short_text') integer(issues, `${path}.maximum_length`, question.maximum_length, BOUNDS.shortTextMaximumLength);
  else if (question.type === 'scale') {
    integer(issues, `${path}.minimum`, question.minimum, [-1_000, 1_000]);
    integer(issues, `${path}.maximum`, question.maximum, [-1_000, 1_000]);
    if (question.minimum >= question.maximum) issues.push({ path: `${path}.maximum`, code: 'window_order' });
    localized(issues, `${path}.minimum_label`, question.minimum_label); localized(issues, `${path}.maximum_label`, question.maximum_label);
  } else {
    if (question.options.length < 2 || question.options.length > 50) issues.push(range(`${path}.options`, [2, 50]));
    const ids = new Set<string>();
    question.options.forEach((option, index) => {
      identifier(issues, `${path}.options.${index}.id`, option.id); duplicate(issues, ids, `${path}.options.${index}.id`, option.id);
      localized(issues, `${path}.options.${index}.label`, option.label);
    });
    if (question.type === 'multiple_choice' &&
      (!Number.isInteger(question.minimum_selections) || !Number.isInteger(question.maximum_selections) ||
        question.minimum_selections < 0 || question.maximum_selections < Math.max(1, question.minimum_selections) ||
        question.maximum_selections > question.options.length || (question.required && question.minimum_selections === 0))) {
      issues.push({ path, code: 'selection_bounds' });
    }
  }
}

function conditionMatchers(condition: StateCondition): EventMatcher[] {
  switch (condition.type) {
    case 'study_session_active': case 'elapsed_at_least': return [];
    case 'event_latch': return [...condition.set_when, ...condition.reset_when];
    case 'keyed_presence': return [...condition.enter_when, ...condition.exit_when];
    case 'held_for': case 'not': return conditionMatchers(condition.condition);
    case 'window_threshold': return [condition.selector];
    case 'all': case 'any': return condition.conditions.flatMap(conditionMatchers);
  }
}

function triggerConditionNodeCount(trigger: AutomationTrigger): number {
  return trigger.type === 'condition_rising_edge' ? conditionNodeCount(trigger.condition) : 0;
}

function conditionNodeCount(condition: StateCondition): number {
  switch (condition.type) {
    case 'held_for': case 'not': return 1 + conditionNodeCount(condition.condition);
    case 'all': case 'any': return 1 + condition.conditions.reduce((sum, child) => sum + conditionNodeCount(child), 0);
    default: return 1;
  }
}

function maximumConcurrentTimerCount(automation: AutomationDefinition): number {
  if (automation.type === 'occurrence') {
    return triggerTimerCount(automation.trigger) + (automation.guard ? conditionTimerCount(automation.guard) : 0);
  }
  return automation.cases.reduce((sum, entry) => sum + conditionTimerCount(entry.condition), 0);
}

function triggerTimerCount(trigger: AutomationTrigger): number {
  switch (trigger.type) {
    case 'schedule': case 'window_threshold': return 1;
    case 'condition_rising_edge': return conditionTimerCount(trigger.condition);
    case 'event_match': case 'sequence': return 0;
  }
}

function conditionTimerCount(condition: StateCondition): number {
  switch (condition.type) {
    case 'study_session_active': case 'event_latch': case 'keyed_presence': return 0;
    case 'elapsed_at_least': case 'window_threshold': return 1;
    case 'held_for': return 1 + conditionTimerCount(condition.condition);
    case 'all': case 'any': return condition.conditions.reduce((sum, child) => sum + conditionTimerCount(child), 0);
    case 'not': return conditionTimerCount(condition.condition);
  }
}

function matcherList(
  issues: Issue[], path: string, matchers: EventMatcher[], conditionKind: ConditionKind,
  referenced: Set<string>
): void {
  if (matchers.length < 1 || matchers.length > 8) issues.push(range(path, [1, 8]));
  matchers.forEach((matcher, index) => validateMatcher(
    issues, `${path}.${index}`, matcher, null, conditionKind, referenced
  ));
}

function presenceContractsMatch(
  keyField: string,
  events: readonly (RegistryEventContract | undefined)[],
  enterCount: number
): boolean {
  if (events.length === 0 || enterCount < 1 || enterCount >= events.length || events.some((event) => !event)) {
    return false;
  }
  const contracts = events.map((event) => event?.trigger.presence);
  if (contracts.some((presence) => !presence)) return false;
  const present = contracts.filter((presence) => presence !== null && presence !== undefined);
  return present.every((presence, index) => presence.role === (index < enterCount ? 'ENTER' : 'EXIT')) &&
    new Set(present.map((presence) => presence.group_id)).size === 1 &&
    present.every((presence) => presence.key_fields.length === 1 && presence.key_fields[0] === keyField);
}

function hasCycle(graph: Map<string, Set<string>>): boolean {
  const visiting = new Set<string>(); const visited = new Set<string>();
  const visit = (node: string): boolean => {
    if (visiting.has(node)) return true;
    if (visited.has(node)) return false;
    visiting.add(node);
    for (const child of graph.get(node) ?? []) if (graph.has(child) && visit(child)) return true;
    visiting.delete(node); visited.add(node); return false;
  };
  return [...graph.keys()].some(visit);
}

function validateUpload(issues: Issue[], configuration: StudyConfiguration): void {
  if (!configuration.upload) return;
  const { endpoint, interval_minutes, allow_metered } = configuration.upload;
  text(issues, 'upload.endpoint', endpoint, BOUNDS.uploadEndpoint);
  if (!endpoint.startsWith('https://')) issues.push({ path: 'upload.endpoint', code: 'endpoint_scheme' });
  else { try { if (!new URL(endpoint).hostname) throw new Error(); } catch { issues.push({ path: 'upload.endpoint', code: 'endpoint_host' }); } }
  integer(issues, 'upload.interval_minutes', interval_minutes, [UPLOAD_MINIMUM_INTERVAL_MINUTES, UPLOAD_MAXIMUM_INTERVAL_MINUTES]);
  if (typeof allow_metered !== 'boolean') issues.push({ path: 'upload.allow_metered', code: 'required' });
}

function localized(issues: Issue[], path: string, value: LocalizedText): void {
  text(issues, `${path}.default`, value.default, BOUNDS.surveyText);
  if (Object.keys(value.translations).length > 32) issues.push(range(`${path}.translations`, [0, 32]));
  const lowered = Object.keys(value.translations).map((key) => key.toLowerCase());
  if (!unique(lowered)) issues.push({ path: `${path}.translations`, code: 'duplicate_id' });
  for (const [language, translated] of Object.entries(value.translations)) {
    if (!/^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(language)) issues.push({ path: `${path}.translations.${language}`, code: 'language_tag' });
    text(issues, `${path}.translations.${language}`, translated, BOUNDS.surveyText);
  }
}

function rawPublicKey(issues: Issue[], path: string, value: string, missing: 'signer_missing' | 'export_key_missing'): void {
  if (!value) { issues.push({ path, code: missing }); return; }
  try { if (decodeBase64Url(value).length !== 32 || value.length !== 43) throw new Error(); }
  catch { issues.push({ path, code: 'key_invalid' }); }
}

function text(issues: Issue[], path: string, value: unknown, bounds: Bounds): void {
  if (typeof value !== 'string' || value.length < bounds[0] || value.length > bounds[1]) issues.push(range(path, bounds));
}
function integer(issues: Issue[], path: string, value: unknown, bounds: Bounds): void {
  if (!Number.isInteger(value)) issues.push({ path, code: 'integer' });
  else if ((value as number) < bounds[0] || (value as number) > bounds[1]) issues.push(range(path, bounds));
}
function nullableInteger(issues: Issue[], path: string, value: unknown, bounds: Bounds): void {
  if (value !== null) integer(issues, path, value, bounds);
}
function decimal(issues: Issue[], path: string, value: unknown, bounds: Bounds): void {
  if (!isCanonicalDecimal(value) || BigInt(value) < BigInt(bounds[0]) || BigInt(value) > BigInt(bounds[1])) issues.push(range(path, bounds));
}
function identifier(issues: Issue[], path: string, value: string): void {
  if (!ID_PATTERN.test(value)) issues.push({ path, code: 'id_format' });
}
function duplicate(issues: Issue[], seen: Set<string>, path: string, value: string): void {
  if (seen.has(value)) issues.push({ path, code: 'duplicate_id' });
  seen.add(value);
}
function instant(issues: Issue[], path: string, value: string) {
  const parsed = parseInstant(value); if (!parsed) issues.push({ path, code: 'instant' }); return parsed;
}
function compareInstant(left: { second: number; nano: number }, right: { second: number; nano: number }): number {
  return left.second - right.second || left.nano - right.nano;
}
function range(path: string, bounds: Bounds): Issue { return { path, code: 'number_range', bounds: { min: bounds[0], max: bounds[1] } }; }
function unique(values: string[]): boolean { return new Set(values).size === values.length; }
function sortedUnique(values: string[]): boolean { return unique(values) && values.join('\0') === [...values].sort().join('\0'); }
function localTime(value: string): boolean { return /^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(value); }
function localMinute(value: string): number | null { return localTime(value) ? Number(value.slice(0, 2)) * 60 + Number(value.slice(3)) : null; }
function stableIssues(issues: Issue[]): Issue[] {
  const seen = new Set<string>();
  return issues.filter((issue) => { const key = `${issue.path}\0${issue.code}`; if (seen.has(key)) return false; seen.add(key); return true; })
    .sort((left, right) => left.path.localeCompare(right.path) || left.code.localeCompare(right.code));
}

export function maximumReachableLocalDates(studyMinutes: number): number {
  return Math.ceil((studyMinutes + 36 * 60) / 1_440) + 1;
}
