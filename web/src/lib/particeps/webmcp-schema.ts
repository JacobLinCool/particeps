/** Closed JSON schemas shared by WebMCP discovery and runtime input validation. */
import { COLLECTOR_SOURCES } from './registry';
import type { ProfileFieldContract } from './generated/event-source-registry';
import { COLLECTOR_ORDER, MAXIMUM_CONFIGURATION_BYTES } from './types';

export interface ToolSchema {
  type?: 'object' | 'array' | 'string' | 'integer' | 'boolean' | 'null';
  description?: string;
  properties?: Record<string, ToolSchema>;
  required?: string[];
  additionalProperties?: false | ToolSchema;
  minProperties?: number;
  maxProperties?: number;
  items?: ToolSchema;
  minItems?: number;
  maxItems?: number;
  minLength?: number;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
  pattern?: string;
  enum?: readonly string[];
  const?: string;
  oneOf?: ToolSchema[];
  $ref?: string;
  $defs?: Record<string, ToolSchema>;
}

const string = (maxLength = 2_000, description?: string): ToolSchema => ({ type: 'string', maxLength, description });
const integer = (minimum: number, maximum: number, description?: string): ToolSchema => ({ type: 'integer', minimum, maximum, description });
const boolean: ToolSchema = { type: 'boolean' };
const literal = (value: string): ToolSchema => ({ type: 'string', const: value });
const enumeration = (values: readonly string[], description?: string): ToolSchema => ({ type: 'string', enum: values, description });
const object = (properties: Record<string, ToolSchema>, required = Object.keys(properties)): ToolSchema => ({
  type: 'object', properties, required, additionalProperties: false
});
const array = (items: ToolSchema, minItems: number, maxItems: number): ToolSchema => ({ type: 'array', items, minItems, maxItems });
const reference = (name: string): ToolSchema => ({ $ref: `#/$defs/${name}` });
const nullable = (schema: ToolSchema): ToolSchema => ({ oneOf: [schema, { type: 'null' }] });
const variant = (type: string, properties: Record<string, ToolSchema> = {}): ToolSchema => object({ type: literal(type), ...properties });
const id: ToolSchema = { ...string(64), pattern: '^[a-z0-9][a-z0-9-]{2,63}$', description: 'Stable unique identifier; 3–64 lowercase letters, digits and hyphens.' };
const clock = enumeration(['ACTIVE_RUNNING_TIME', 'CALENDAR_TIME'], 'Active time pauses with the study; calendar time continues through pauses.');
const evaluationClock = enumeration(['OBSERVED_RESEARCH_TIME', 'PRIMARY_SOURCE_TIME'], 'Select only a clock allowed by the event registry contract.');
const localTime: ToolSchema = { ...string(5), pattern: '^(?:[01][0-9]|2[0-3]):[0-5][0-9]$', description: 'HH:mm in the participant study time zone; end times are exclusive.' };
const localized = object({ default: { ...string(), minLength: 1 }, translations: {
  type: 'object', additionalProperties: { ...string(), minLength: 1 }, maxProperties: 32,
  description: 'Optional translations keyed by language tag, for example zh-TW.'
} });
const matcher = object({ event: object({ source_id: string(128), schema_version: integer(1, 1), event_type: string(128) }), predicates: array({ oneOf: [
  object({ field: string(128), operator: enumeration(['eq', 'ne', 'lt', 'lte', 'gt', 'gte']), value: string(8_000, 'Canonical wire-format string; see the selected registry field.') }),
  object({ field: string(128), operator: literal('in'), values: array(string(8_000), 1, 64) })
] }, 0, 16) });
const windowThreshold = {
  selector: matcher, window_seconds: integer(1, 604_800), evaluation_clock: evaluationClock,
  aggregate: { oneOf: [variant('count'), variant('sum', { field: string(128) })] },
  comparison: object({ operator: enumeration(['eq', 'ne', 'lt', 'lte', 'gt', 'gte']), value: string(128) })
};
const options = array(object({ id, label: localized }), 2, 50);
const questionBase = { id, prompt: localized, required: boolean };

function profileField(contract: ProfileFieldContract): ToolSchema {
  const description = `${contract.meaning} Unit: ${contract.unit}. Default: ${JSON.stringify(contract.authoring_default)}.${contract.less_than_or_equal_field ? ` Must be ≤ ${contract.less_than_or_equal_field}.` : ''}`;
  let schema: ToolSchema;
  switch (contract.type) {
    case 'integer': schema = integer(contract.minimum ?? Number.MIN_SAFE_INTEGER, contract.maximum ?? Number.MAX_SAFE_INTEGER); break;
    case 'boolean': schema = boolean; break;
    case 'string': schema = { ...string(contract.maximum_length ?? 8_000), minLength: contract.minimum_length ?? 0 }; break;
    case 'enum': schema = enumeration(contract.enum_values ?? []); break;
    case 'enum_array': schema = array(enumeration(contract.enum_values ?? []), contract.minimum_items ?? 0, contract.maximum_items ?? 64); break;
    case 'object': schema = profileObject(contract.fields ?? {}); break;
  }
  return { ...schema, description };
}

function profileObject(fields: Readonly<Record<string, ProfileFieldContract>>): ToolSchema {
  return object(Object.fromEntries(Object.entries(fields).map(([name, field]) => [name, profileField(field)])),
    Object.entries(fields).filter(([, field]) => field.required).map(([name]) => name));
}

export const AUTHORING_DEFINITIONS: Record<string, ToolSchema> = {
  collector: { oneOf: COLLECTOR_SOURCES.map((source) => object({
    id: literal(source.source_id), required: boolean,
    profiles: array(object({ id, config: profileObject(source.configuration?.fields ?? {}) }), 1, 64)
  })) },
  condition: { oneOf: [
    variant('study_session_active'),
    variant('event_latch', { set_when: array(matcher, 1, 8), reset_when: array(matcher, 1, 8) }),
    variant('keyed_presence', { enter_when: array(matcher, 1, 8), exit_when: array(matcher, 1, 8), key_field: string(128) }),
    variant('held_for', { condition: reference('condition'), duration_seconds: integer(1, 31_536_000), clock }),
    variant('study_local_window', { first_day: integer(1, 366), last_day: integer(1, 366), start_local_time: localTime, end_local_time: localTime }),
    variant('elapsed_at_least', { duration_seconds: integer(1, 31_536_000), clock }),
    variant('window_threshold', windowThreshold),
    variant('all', { conditions: array(reference('condition'), 2, 8) }),
    variant('any', { conditions: array(reference('condition'), 2, 8) }),
    variant('not', { condition: reference('condition') })
  ] },
  schedule: { oneOf: [
    variant('one_time', { offset_minutes: integer(0, 525_599), clock }),
    variant('interval', { start_offset_minutes: integer(0, 525_599), interval_minutes: integer(1, 525_600), clock }),
    variant('daily_local', { local_time: localTime }),
    variant('random_window', {
      local_windows: array(object({ start_local_time: localTime, end_local_time: localTime }), 1, 8),
      occurrences_per_window: integer(1, 8), maximum_occurrences_per_day: integer(1, 64),
      maximum_occurrences_total: integer(1, 512), minimum_separation_minutes: integer(1, 1_440)
    })
  ] },
  trigger: { oneOf: [
    variant('event_match', { selector: matcher, evaluation_clock: evaluationClock }),
    variant('sequence', { steps: array(matcher, 2, 16), within_seconds: integer(1, 604_800), evaluation_clock: evaluationClock }),
    variant('window_threshold', windowThreshold),
    variant('condition_rising_edge', { condition: reference('condition') }),
    variant('schedule', { schedule: reference('schedule') })
  ] },
  binding: variant('resource_binding', {
    id, resource: object({ kind: enumeration(['collector', 'actuator']), id: string(128) }),
    cases: { ...array(object({ condition: reference('condition'), profile_id: nullable(id) }), 1, 16), description: 'First matching case wins; null profile stops the resource.' },
    default_profile_id: nullable(id)
  }),
  occurrence: variant('occurrence', {
    id, trigger: reference('trigger'), guard: nullable(reference('condition')), intervention_id: id,
    availability_seconds: integer(1, 31_536_000), cooldown: nullable(object({ duration_seconds: integer(1, 31_536_000), clock })),
    maximum_activations: integer(1, 512)
  }),
  survey: object({ id, title: localized, description: localized, questions: array({ oneOf: [
    variant('short_text', { ...questionBase, maximum_length: integer(1, 4_000) }),
    variant('scale', { ...questionBase, minimum: integer(-1_000, 1_000), maximum: integer(-1_000, 1_000), minimum_label: localized, maximum_label: localized }),
    variant('single_choice', { ...questionBase, options }),
    variant('multiple_choice', { ...questionBase, options, minimum_selections: integer(0, 50), maximum_selections: integer(1, 50) })
  ] }, 1, 100) }),
  intervention: object({ id, required: boolean, action: { oneOf: [
    variant('notification', { notification_title: { ...string(120), minLength: 1 }, notification_message: { ...string(500), minLength: 1 } }),
    variant('survey', { notification_title: { ...string(120), minLength: 1 }, notification_message: { ...string(500), minLength: 1 }, survey_id: id })
  ] } }),
  traffic_shaping: { oneOf: [object({}), object({
    target_packages: { oneOf: [literal('all'), array(string(255), 1, 64)] },
    profiles: array(object({ id, uplink_kbps: nullable(integer(1, 1_000_000)), downlink_kbps: nullable(integer(1, 1_000_000)) }), 1, 64)
  })] },
  upload: nullable(object({ endpoint: { ...string(2_048), minLength: 8 }, interval_minutes: integer(1, 10_080), allow_metered: boolean })),
  storage: object({ maximum_local_bytes: integer(8_388_608, 8_589_934_592) }),
  study: { ...object({
    title: string(120), purpose: string(2_000), researcher: object({ name: string(120), contact: string(240) }),
    consent: object({ document_version: string(64), summary: string(8_000) }), duration_hours: integer(1, 8_760),
    issued_at: string(40, 'Canonical UTC instant, for example 2026-09-11T00:00:00Z.'),
    expires_at: string(40, 'Configuration import deadline; distinct from study duration.'),
    minimum_client_version: { ...string(10), pattern: '^[1-9][0-9]*$' }
  }, []), minProperties: 1 }
};

export const CHANGE_SCHEMA: ToolSchema = {
  ...object({
    expected_revision: { ...string(64), pattern: '^[a-f0-9]{64}$', description: 'Revision from read_draft; all UI or agent changes invalidate older revisions.' },
    dry_run: { ...boolean, description: 'Validate and preview all changes without modifying the shared draft.' },
    changes: { ...array({ oneOf: [
      variant('set_study', { study: reference('study') }),
      variant('upsert_collector', { collector: reference('collector') }),
      variant('remove_collector', { id: enumeration(COLLECTOR_ORDER) }),
      variant('upsert_survey', { survey: reference('survey') }),
      variant('remove_survey', { id }),
      variant('upsert_intervention', { intervention: reference('intervention') }),
      variant('remove_intervention', { id }),
      variant('upsert_automation', { automation: { oneOf: [reference('binding'), reference('occurrence')] } }),
      variant('remove_automation', { id }),
      variant('set_traffic_shaping', { traffic_shaping: reference('traffic_shaping') }),
      variant('set_upload', { upload: reference('upload') }),
      variant('set_storage', { storage: reference('storage') })
    ] }, 1, 64), description: 'Ordered atomic changes. Supply dependencies together: every collector needs one resource binding, and every intervention needs an occurrence. Removals never silently cascade.' }
  }, ['expected_revision', 'changes']),
  $defs: AUTHORING_DEFINITIONS
};

export const EMPTY_INPUT_SCHEMA = object({});
export const CATALOG_INPUT_SCHEMA = object({ section: enumeration(['all', 'collectors', 'events', 'schemas']) }, []);
export const TIMELINE_INPUT_SCHEMA = object({
  studyDay: integer(1, 366, 'Study day to display, with day 1 containing the start.'),
  startDate: { ...string(10), pattern: '^\\d{4}-\\d{2}-\\d{2}$' }, startTime: localTime,
  timeZone: string(100, 'IANA time zone for the reference scenario.'), locale: enumeration(['en', 'zh-TW'])
}, []);

export interface InputIssue { path: string; code: string }

/** Only the small JSON Schema vocabulary used above is accepted here. */
export function validateToolInput(input: unknown, schema: ToolSchema): InputIssue[] {
  const budgetIssue = jsonBudget(input);
  if (budgetIssue) return [budgetIssue];
  return check(input, schema, schema.$defs ?? AUTHORING_DEFINITIONS, '$').slice(0, 32);
}

function check(input: unknown, schema: ToolSchema, definitions: Record<string, ToolSchema>, path: string): InputIssue[] {
  if (schema.$ref) return check(input, definitions[schema.$ref.slice('#/$defs/'.length)], definitions, path);
  if (schema.oneOf) {
    const alternatives = schema.oneOf.map((candidate) => check(input, candidate, definitions, path));
    if (alternatives.filter((issues) => issues.length === 0).length === 1) return [];
    return alternatives.sort((left, right) => left.length - right.length)[0].length
      ? alternatives[0] : [{ path, code: 'ambiguous_variant' }];
  }
  const fail = (code: string) => [{ path, code }];
  if (schema.type === 'null') return input === null ? [] : fail('expected_null');
  if (schema.type === 'object') {
    if (input === null || typeof input !== 'object' || Array.isArray(input)) return fail('expected_object');
    const value = input as Record<string, unknown>;
    for (const [name, child] of Object.entries(schema.properties ?? {})) {
      if (child.const !== undefined && value[name] !== child.const) return [{ path: `${path}.${name}`, code: 'unknown_value' }];
    }
    const issues: InputIssue[] = [];
    if (Object.keys(value).length < (schema.minProperties ?? 0) || Object.keys(value).length > (schema.maxProperties ?? Infinity)) issues.push({ path, code: 'object_size' });
    for (const name of schema.required ?? []) if (!Object.hasOwn(value, name)) issues.push({ path: `${path}.${name}`, code: 'required' });
    for (const [name, entry] of Object.entries(value)) {
      const child = Object.hasOwn(schema.properties ?? {}, name) ? schema.properties![name] : schema.additionalProperties;
      if (!child) issues.push({ path: `${path}.${name}`, code: 'unknown_field' });
      else issues.push(...check(entry, child, definitions, `${path}.${name}`));
    }
    return issues;
  }
  if (schema.type === 'array') {
    if (!Array.isArray(input)) return fail('expected_array');
    if (input.length < (schema.minItems ?? 0) || input.length > (schema.maxItems ?? Infinity)) return fail('array_size');
    return input.flatMap((entry, index) => check(entry, schema.items!, definitions, `${path}.${index}`));
  }
  if (schema.type === 'string') {
    if (typeof input !== 'string') return fail('expected_string');
    if (input.length < (schema.minLength ?? 0) || input.length > (schema.maxLength ?? Infinity)) return fail('string_length');
    if (schema.pattern && !new RegExp(schema.pattern).test(input)) return fail('string_format');
    if ((schema.const !== undefined && input !== schema.const) || (schema.enum && !schema.enum.includes(input))) return fail('unknown_value');
  }
  if (schema.type === 'integer') {
    if (typeof input !== 'number' || !Number.isSafeInteger(input)) return fail('expected_integer');
    if (input < (schema.minimum ?? -Infinity) || input > (schema.maximum ?? Infinity)) return fail('number_range');
  }
  if (schema.type === 'boolean' && typeof input !== 'boolean') return fail('expected_boolean');
  return [];
}

function jsonBudget(input: unknown): InputIssue | null {
  let nodes = 0;
  const seen = new Set<object>();
  const visit = (value: unknown, depth: number): boolean => {
    if (++nodes > 100_000 || depth > 32) return false;
    if (value === null || typeof value === 'boolean') return true;
    if (typeof value === 'string') return value.length <= MAXIMUM_CONFIGURATION_BYTES;
    if (typeof value === 'number') return Number.isFinite(value);
    if (typeof value !== 'object' || seen.has(value)) return false;
    if (!Array.isArray(value) && Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null) return false;
    seen.add(value);
    const valid = Object.entries(value).every(([key, child]) => !['__proto__', 'constructor', 'prototype'].includes(key) && visit(child, depth + 1));
    seen.delete(value);
    return valid;
  };
  if (!visit(input, 0)) return { path: '$', code: 'invalid_or_excessive_json' };
  if (new TextEncoder().encode(JSON.stringify(input)).length > MAXIMUM_CONFIGURATION_BYTES) return { path: '$', code: 'input_too_large' };
  return null;
}
