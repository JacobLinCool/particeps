/** Closed-world reader for the current Protocol v1 configuration only. */

import { canonicalConfigurationBytes, parseCanonicalJson } from '$lib/particeps/canonical';
import { verify } from '$lib/particeps/crypto';
import { decodeEnvelope, isEnvelope } from '$lib/particeps/envelope';
import { collectorContract } from '$lib/particeps/registry';
import { validate } from '$lib/particeps/schema';
import {
  PLATFORM,
  isCollectorId,
  trafficShapingEnabled,
  type Aggregate,
  type AutomationDefinition,
  type AutomationSchedule,
  type AutomationTrigger,
  type CollectorConfig,
  type CollectorProfileConfiguration,
  type EventMatcher,
  type FieldPredicate,
  type InterventionConfig,
  type LocalizedText,
  type StateCondition,
  type StudyConfiguration,
  type SurveyDefinition,
  type SurveyQuestion,
  type TrafficShapingConfiguration
} from '$lib/particeps/types';

export { decodeEnvelope, isEnvelope } from '$lib/particeps/envelope';

const ROOT_KEYS = [
  'schema_version', 'platform', 'experiment_id', 'configuration_id', 'assigned_participant_id',
  'issued_at', 'expires_at', 'minimum_client_version', 'title', 'researcher', 'purpose',
  'duration_hours', 'consent', 'collectors', 'surveys', 'interventions', 'automations',
  'traffic_shaping', 'storage', 'signer', 'export', 'upload'
] as const;

export function parseConfiguration(bytes: Uint8Array): StudyConfiguration {
  const envelope = isEnvelope(bytes) ? decodeEnvelope(bytes) : null;
  const source = envelope?.configurationBytes ?? bytes;
  const raw = object(parseCanonicalJson(source));
  requireExactKeys(raw, ROOT_KEYS);
  const researcher = exactObject(raw.researcher, ['name', 'contact']);
  const consent = exactObject(raw.consent, ['document_version', 'summary']);
  const storage = exactObject(raw.storage, ['maximum_local_bytes']);
  const signer = exactObject(raw.signer, ['key_id', 'public_key']);
  const exported = exactObject(raw.export, ['researcher_key_id', 'hpke_public_key']);
  const upload = object(raw.upload);
  if (Object.keys(upload).length > 0) requireExactKeys(upload, ['endpoint', 'interval_minutes', 'allow_metered']);
  if (raw.platform !== PLATFORM) fail('parse_platform');

  const configuration: StudyConfiguration = {
    schema_version: integer(raw.schema_version), platform: PLATFORM,
    experiment_id: string(raw.experiment_id), configuration_id: string(raw.configuration_id),
    assigned_participant_id: nullableString(raw.assigned_participant_id),
    issued_at: string(raw.issued_at), expires_at: string(raw.expires_at),
    minimum_client_version: string(raw.minimum_client_version), title: string(raw.title),
    researcher: { name: string(researcher.name), contact: string(researcher.contact) },
    purpose: string(raw.purpose), duration_hours: integer(raw.duration_hours),
    consent: { document_version: string(consent.document_version), summary: string(consent.summary) },
    collectors: array(raw.collectors).map(parseCollector),
    surveys: array(raw.surveys).map(parseSurvey),
    interventions: array(raw.interventions).map(parseIntervention),
    automations: array(raw.automations).map(parseAutomation),
    traffic_shaping: parseTrafficShaping(raw.traffic_shaping),
    storage: { maximum_local_bytes: integer(storage.maximum_local_bytes) },
    signer: { key_id: string(signer.key_id), public_key: string(signer.public_key) },
    export: { researcher_key_id: string(exported.researcher_key_id), hpke_public_key: string(exported.hpke_public_key) },
    upload: Object.keys(upload).length === 0 ? null : {
      endpoint: string(upload.endpoint), interval_minutes: integer(upload.interval_minutes),
      allow_metered: boolean(upload.allow_metered)
    }
  };
  if (!sameBytes(canonicalConfigurationBytes(configuration), source)) fail('parse_canonical');
  if (validate(configuration).length > 0) fail('parse_invalid');
  if (envelope) {
    if (envelope.signerKeyId !== configuration.signer.key_id) fail('envelope_signer');
    if (!verify(source, envelope.signature, configuration.signer.public_key)) fail('envelope_signature');
  }
  return configuration;
}

function parseCollector(raw: unknown): CollectorConfig {
  const source = exactObject(raw, ['id', 'required', 'profiles']);
  if (!isCollectorId(source.id)) fail('parse_collector');
  const id = source.id;
  return {
    id, required: boolean(source.required),
    profiles: array(source.profiles).map((rawProfile) => {
      const profile = exactObject(rawProfile, ['id', 'config']);
      return { id: string(profile.id), config: parseProfile(id, profile.config) };
    })
  } as CollectorConfig;
}

function parseProfile(id: CollectorConfig['id'], raw: unknown): CollectorProfileConfiguration {
  const input = object(raw);
  const fields = collectorContract(id).configuration?.fields ?? {};
  requireExactKeys(input, Object.keys(fields));
  return Object.fromEntries(Object.entries(fields).map(([name, contract]) => {
    const value = input[name];
    switch (contract.type) {
      case 'boolean': return [name, boolean(value)];
      case 'integer': return [name, integer(value)];
      case 'string': case 'enum': return [name, string(value)];
      case 'enum_array': return [name, array(value).map(string)];
      case 'object': return [name, parseProfileObject(value, contract.fields ?? {})];
    }
  })) as CollectorProfileConfiguration;
}

function parseProfileObject(raw: unknown, contracts: Readonly<Record<string, { type: string; fields?: unknown }>>): Record<string, unknown> {
  const input = object(raw); requireExactKeys(input, Object.keys(contracts));
  return Object.fromEntries(Object.entries(contracts).map(([name, contract]) => {
    const value = input[name];
    if (contract.type === 'boolean') return [name, boolean(value)];
    if (contract.type === 'integer') return [name, integer(value)];
    if (contract.type === 'enum_array') return [name, array(value).map(string)];
    if (contract.type === 'object') return [name, parseProfileObject(value, contract.fields as never)];
    return [name, string(value)];
  }));
}

function parseTrafficShaping(raw: unknown): TrafficShapingConfiguration {
  const source = object(raw);
  if (Object.keys(source).length === 0) return {};
  requireExactKeys(source, ['target_packages', 'profiles']);
  const result: TrafficShapingConfiguration = {
    target_packages: array(source.target_packages).map(string),
    profiles: array(source.profiles).map((value) => {
      const profile = exactObject(value, ['id', 'uplink_kbps', 'downlink_kbps']);
      return {
        id: string(profile.id), uplink_kbps: nullableInteger(profile.uplink_kbps),
        downlink_kbps: nullableInteger(profile.downlink_kbps)
      };
    })
  };
  if (!trafficShapingEnabled(result)) fail('parse_traffic_shaping');
  return result;
}

function parseIntervention(raw: unknown): InterventionConfig {
  const source = exactObject(raw, ['id', 'required', 'action']);
  const action = object(source.action);
  if (action.type === 'notification') {
    requireExactKeys(action, ['type', 'notification_title', 'notification_message']);
    return { id: string(source.id), required: boolean(source.required), action: {
      type: 'notification', notification_title: string(action.notification_title),
      notification_message: string(action.notification_message)
    } };
  }
  if (action.type === 'survey') {
    requireExactKeys(action, ['type', 'notification_title', 'notification_message', 'survey_id']);
    return { id: string(source.id), required: boolean(source.required), action: {
      type: 'survey', notification_title: string(action.notification_title),
      notification_message: string(action.notification_message), survey_id: string(action.survey_id)
    } };
  }
  return fail('parse_action');
}

function parseAutomation(raw: unknown): AutomationDefinition {
  const source = object(raw);
  if (source.type === 'occurrence') {
    requireExactKeys(source, ['type', 'id', 'trigger', 'guard', 'intervention_id', 'availability_seconds', 'cooldown', 'maximum_activations']);
    const cooldown = source.cooldown === null ? null : exactObject(source.cooldown, ['duration_seconds', 'clock']);
    return {
      type: 'occurrence', id: string(source.id), trigger: parseTrigger(source.trigger),
      guard: source.guard === null ? null : parseCondition(source.guard),
      intervention_id: string(source.intervention_id), availability_seconds: integer(source.availability_seconds),
      cooldown: cooldown ? { duration_seconds: integer(cooldown.duration_seconds), clock: durationClock(cooldown.clock) } : null,
      maximum_activations: integer(source.maximum_activations)
    };
  }
  if (source.type === 'resource_binding') {
    requireExactKeys(source, ['type', 'id', 'resource', 'cases', 'default_profile_id']);
    const resource = exactObject(source.resource, ['kind', 'id']);
    const kind = resource.kind;
    if (kind !== 'collector' && kind !== 'actuator') fail('parse_resource');
    return {
      type: 'resource_binding', id: string(source.id), resource: { kind, id: string(resource.id) },
      cases: array(source.cases).map((value) => {
        const entry = exactObject(value, ['condition', 'profile_id']);
        return { condition: parseCondition(entry.condition), profile_id: nullableString(entry.profile_id) };
      }),
      default_profile_id: nullableString(source.default_profile_id)
    };
  }
  return fail('parse_automation');
}

function parseTrigger(raw: unknown): AutomationTrigger {
  const source = object(raw);
  switch (source.type) {
    case 'event_match':
      requireExactKeys(source, ['type', 'selector', 'evaluation_clock']);
      return { type: 'event_match', selector: parseMatcher(source.selector), evaluation_clock: evaluationClock(source.evaluation_clock) };
    case 'sequence':
      requireExactKeys(source, ['type', 'steps', 'within_seconds', 'evaluation_clock']);
      return { type: 'sequence', steps: array(source.steps).map(parseMatcher), within_seconds: integer(source.within_seconds), evaluation_clock: evaluationClock(source.evaluation_clock) };
    case 'window_threshold':
      requireExactKeys(source, ['type', 'selector', 'window_seconds', 'evaluation_clock', 'aggregate', 'comparison']);
      return { type: 'window_threshold', selector: parseMatcher(source.selector), window_seconds: integer(source.window_seconds),
        evaluation_clock: evaluationClock(source.evaluation_clock), aggregate: parseAggregate(source.aggregate), comparison: parseComparison(source.comparison) };
    case 'condition_rising_edge':
      requireExactKeys(source, ['type', 'condition']); return { type: 'condition_rising_edge', condition: parseCondition(source.condition) };
    case 'schedule':
      requireExactKeys(source, ['type', 'schedule']); return { type: 'schedule', schedule: parseSchedule(source.schedule) };
    default: return fail('parse_trigger');
  }
}

function parseCondition(raw: unknown): StateCondition {
  const source = object(raw);
  switch (source.type) {
    case 'study_session_active': requireExactKeys(source, ['type']); return { type: 'study_session_active' };
    case 'event_latch': requireExactKeys(source, ['type', 'set_when', 'reset_when']); return {
      type: 'event_latch', set_when: array(source.set_when).map(parseMatcher), reset_when: array(source.reset_when).map(parseMatcher)
    };
    case 'keyed_presence': requireExactKeys(source, ['type', 'enter_when', 'exit_when', 'key_field']); return {
      type: 'keyed_presence', enter_when: array(source.enter_when).map(parseMatcher),
      exit_when: array(source.exit_when).map(parseMatcher), key_field: string(source.key_field)
    };
    case 'held_for': requireExactKeys(source, ['type', 'condition', 'duration_seconds', 'clock']); return {
      type: 'held_for', condition: parseCondition(source.condition), duration_seconds: integer(source.duration_seconds), clock: durationClock(source.clock)
    };
    case 'elapsed_at_least': requireExactKeys(source, ['type', 'duration_seconds', 'clock']); return {
      type: 'elapsed_at_least', duration_seconds: integer(source.duration_seconds), clock: durationClock(source.clock)
    };
    case 'window_threshold': requireExactKeys(source, ['type', 'selector', 'window_seconds', 'evaluation_clock', 'aggregate', 'comparison']); return {
      type: 'window_threshold', selector: parseMatcher(source.selector), window_seconds: integer(source.window_seconds),
      evaluation_clock: evaluationClock(source.evaluation_clock), aggregate: parseAggregate(source.aggregate), comparison: parseComparison(source.comparison)
    };
    case 'all': case 'any': requireExactKeys(source, ['type', 'conditions']); return {
      type: source.type, conditions: array(source.conditions).map(parseCondition)
    };
    case 'not': requireExactKeys(source, ['type', 'condition']); return { type: 'not', condition: parseCondition(source.condition) };
    default: return fail('parse_condition');
  }
}

function parseMatcher(raw: unknown): EventMatcher {
  const source = exactObject(raw, ['event', 'predicates']);
  const event = exactObject(source.event, ['source_id', 'schema_version', 'event_type']);
  return { event: { source_id: string(event.source_id), schema_version: integer(event.schema_version), event_type: string(event.event_type) },
    predicates: array(source.predicates).map(parsePredicate) };
}

function parsePredicate(raw: unknown): FieldPredicate {
  const source = object(raw); const operator = string(source.operator);
  if (operator === 'in') {
    requireExactKeys(source, ['field', 'operator', 'values']);
    return { field: string(source.field), operator, values: array(source.values).map(string) };
  }
  if (!['eq', 'ne', 'lt', 'lte', 'gt', 'gte'].includes(operator)) fail('parse_operator');
  requireExactKeys(source, ['field', 'operator', 'value']);
  return { field: string(source.field), operator: operator as Exclude<FieldPredicate['operator'], 'in'>, value: string(source.value) };
}

function parseAggregate(raw: unknown): Aggregate {
  const source = object(raw);
  if (source.type === 'count') { requireExactKeys(source, ['type']); return { type: 'count' }; }
  if (source.type === 'sum') { requireExactKeys(source, ['type', 'field']); return { type: 'sum', field: string(source.field) }; }
  return fail('parse_aggregate');
}

function parseComparison(raw: unknown) {
  const source = exactObject(raw, ['operator', 'value']); const operator = string(source.operator);
  if (!['eq', 'ne', 'lt', 'lte', 'gt', 'gte'].includes(operator)) fail('parse_operator');
  return { operator: operator as 'eq' | 'ne' | 'lt' | 'lte' | 'gt' | 'gte', value: string(source.value) };
}

function parseSchedule(raw: unknown): AutomationSchedule {
  const source = object(raw);
  switch (source.type) {
    case 'one_time': requireExactKeys(source, ['type', 'offset_minutes', 'clock']); return {
      type: 'one_time', offset_minutes: integer(source.offset_minutes), clock: durationClock(source.clock)
    };
    case 'interval': requireExactKeys(source, ['type', 'start_offset_minutes', 'interval_minutes', 'clock']); return {
      type: 'interval', start_offset_minutes: integer(source.start_offset_minutes), interval_minutes: integer(source.interval_minutes), clock: durationClock(source.clock)
    };
    case 'daily_local': requireExactKeys(source, ['type', 'local_time']); return { type: 'daily_local', local_time: string(source.local_time) };
    case 'random_window': requireExactKeys(source, ['type', 'local_windows', 'occurrences_per_window', 'maximum_occurrences_per_day', 'maximum_occurrences_total', 'minimum_separation_minutes']); return {
      type: 'random_window', local_windows: array(source.local_windows).map((value) => {
        const window = exactObject(value, ['start_local_time', 'end_local_time']);
        return { start_local_time: string(window.start_local_time), end_local_time: string(window.end_local_time) };
      }), occurrences_per_window: integer(source.occurrences_per_window),
      maximum_occurrences_per_day: integer(source.maximum_occurrences_per_day),
      maximum_occurrences_total: integer(source.maximum_occurrences_total),
      minimum_separation_minutes: integer(source.minimum_separation_minutes)
    };
    default: return fail('parse_schedule');
  }
}

function parseSurvey(raw: unknown): SurveyDefinition {
  const source = exactObject(raw, ['id', 'title', 'description', 'questions']);
  return { id: string(source.id), title: localized(source.title), description: localized(source.description), questions: array(source.questions).map(parseQuestion) };
}

function parseQuestion(raw: unknown): SurveyQuestion {
  const source = object(raw); const common = { id: string(source.id), prompt: localized(source.prompt), required: boolean(source.required) };
  switch (source.type) {
    case 'short_text': requireExactKeys(source, ['type', 'id', 'prompt', 'required', 'maximum_length']); return { type: 'short_text', ...common, maximum_length: integer(source.maximum_length) };
    case 'scale': requireExactKeys(source, ['type', 'id', 'prompt', 'required', 'minimum', 'maximum', 'minimum_label', 'maximum_label']); return {
      type: 'scale', ...common, minimum: integer(source.minimum), maximum: integer(source.maximum),
      minimum_label: localized(source.minimum_label), maximum_label: localized(source.maximum_label)
    };
    case 'single_choice': requireExactKeys(source, ['type', 'id', 'prompt', 'required', 'options']); return { type: 'single_choice', ...common, options: choices(source.options) };
    case 'multiple_choice': requireExactKeys(source, ['type', 'id', 'prompt', 'required', 'options', 'minimum_selections', 'maximum_selections']); return {
      type: 'multiple_choice', ...common, options: choices(source.options),
      minimum_selections: integer(source.minimum_selections), maximum_selections: integer(source.maximum_selections)
    };
    default: return fail('parse_question');
  }
}

function localized(raw: unknown): LocalizedText {
  const source = exactObject(raw, ['default', 'translations']); const translations = object(source.translations);
  return { default: string(source.default), translations: Object.fromEntries(Object.entries(translations).map(([key, value]) => [key, string(value)])) };
}
function choices(raw: unknown) { return array(raw).map((value) => { const choice = exactObject(value, ['id', 'label']); return { id: string(choice.id), label: localized(choice.label) }; }); }

function evaluationClock(value: unknown) {
  if (value !== 'OBSERVED_RESEARCH_TIME' && value !== 'PRIMARY_SOURCE_TIME') fail('parse_clock'); return value;
}
function durationClock(value: unknown) {
  if (value !== 'ACTIVE_RUNNING_TIME' && value !== 'CALENDAR_TIME') fail('parse_clock'); return value;
}
function requireExactKeys(value: Record<string, unknown>, expected: readonly string[]): void {
  const actual = Object.keys(value); if (actual.length !== expected.length || expected.some((key) => !Object.hasOwn(value, key))) fail('parse_keys');
}
function exactObject(raw: unknown, keys: readonly string[]) { const value = object(raw); requireExactKeys(value, keys); return value; }
function object(value: unknown): Record<string, unknown> { return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : fail('parse_object'); }
function string(value: unknown): string { return typeof value === 'string' ? value : fail('parse_string'); }
function nullableString(value: unknown): string | null { return value === null || typeof value === 'string' ? value : fail('parse_string'); }
function boolean(value: unknown): boolean { return typeof value === 'boolean' ? value : fail('parse_boolean'); }
function array(value: unknown): unknown[] { return Array.isArray(value) ? value : fail('parse_array'); }
function integer(value: unknown): number { return typeof value === 'number' && Number.isSafeInteger(value) ? value : fail('parse_number'); }
function nullableInteger(value: unknown): number | null { return value === null ? null : integer(value); }
function fail(message: string): never { throw new Error(message); }
function sameBytes(left: Uint8Array, right: Uint8Array): boolean { return left.length === right.length && left.every((byte, index) => byte === right[index]); }
