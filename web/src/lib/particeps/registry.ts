/** Query and validation helpers over the generated Protocol v1 registry projection. */

import {
  EVENT_SOURCE_REGISTRY,
  type ProfileFieldContract,
  type RegistryEventContract,
  type RegistryFieldContract,
  type RegistrySourceContract
} from './generated/event-source-registry.ts';
import type { CollectorId, CollectorProfileConfiguration, EventIdentity } from './types';
import { isProtocolDecimalFloat, javaDoubleString } from './wire-float';
import { parseStrictEmbeddedJson } from './wire-json';

export const COLLECTOR_SOURCES = EVENT_SOURCE_REGISTRY.sources
  .filter((source) => source.source_kind === 'COLLECTOR' && source.selectable)
  .slice()
  .sort((left, right) => left.source_id.localeCompare(right.source_id)) as readonly RegistrySourceContract[];

export const RESEARCHER_EVENTS = EVENT_SOURCE_REGISTRY.sources.flatMap((source) =>
  source.events
    .filter((event) => event.trigger.scope === 'RESEARCHER')
    .map((event) => ({ source, event }))
);

export function collectorContract(id: CollectorId): RegistrySourceContract {
  const source = COLLECTOR_SOURCES.find((candidate) => candidate.source_id === id);
  if (!source) throw new Error(`Unknown collector source ${id}`);
  return source;
}

export function eventContract(identity: EventIdentity): {
  source: RegistrySourceContract;
  event: RegistryEventContract;
} | null {
  const source = EVENT_SOURCE_REGISTRY.sources.find(
    (candidate) => candidate.source_id === identity.source_id && candidate.schema_version === identity.schema_version
  );
  const event = source?.events.find((candidate) => candidate.event_type === identity.event_type);
  return source && event ? { source, event } : null;
}

export function defaultProfileConfiguration(id: CollectorId): CollectorProfileConfiguration {
  const fields = collectorContract(id).configuration?.fields ?? {};
  return Object.fromEntries(
    Object.entries(fields).map(([name, contract]) => [name, structuredClone(contract.authoring_default)])
  ) as CollectorProfileConfiguration;
}

export interface ProfileProblem {
  field: string;
  code: 'required' | 'unknown' | 'type' | 'range' | 'selection' | 'field_order';
}

export function validateProfileConfiguration(
  sourceId: CollectorId,
  value: CollectorProfileConfiguration
): ProfileProblem[] {
  const fields = collectorContract(sourceId).configuration?.fields ?? {};
  const input = value as Record<string, unknown>;
  const problems: ProfileProblem[] = [];
  for (const name of Object.keys(input)) {
    if (!Object.hasOwn(fields, name)) problems.push({ field: name, code: 'unknown' });
  }
  for (const [name, contract] of Object.entries(fields)) {
    if (!Object.hasOwn(input, name)) {
      if (contract.required) problems.push({ field: name, code: 'required' });
      continue;
    }
    validateProfileField(input[name], contract, name, problems);
  }
  for (const [name, contract] of Object.entries(fields)) {
    if (!contract.less_than_or_equal_field) continue;
    const lower = input[name];
    const upper = input[contract.less_than_or_equal_field];
    if (typeof lower === 'number' && typeof upper === 'number' && lower > upper) {
      problems.push({ field: name, code: 'field_order' });
    }
  }
  return problems;
}

function validateProfileField(
  value: unknown,
  contract: ProfileFieldContract,
  path: string,
  problems: ProfileProblem[]
): void {
  switch (contract.type) {
    case 'boolean':
      if (typeof value !== 'boolean') problems.push({ field: path, code: 'type' });
      return;
    case 'integer':
      if (typeof value !== 'number' || !Number.isSafeInteger(value)) problems.push({ field: path, code: 'type' });
      else if ((contract.minimum !== undefined && value < contract.minimum) ||
        (contract.maximum !== undefined && value > contract.maximum)) problems.push({ field: path, code: 'range' });
      return;
    case 'string': {
      if (typeof value !== 'string') {
        problems.push({ field: path, code: 'type' });
        return;
      }
      const length = contract.length_unit === 'UTF8_BYTES'
        ? new TextEncoder().encode(value).length
        : value.length;
      if ((contract.minimum_length !== undefined && length < contract.minimum_length) ||
        (contract.maximum_length !== undefined && length > contract.maximum_length)) {
        problems.push({ field: path, code: 'range' });
      }
      return;
    }
    case 'enum':
      if (typeof value !== 'string' || !contract.enum_values?.includes(value)) {
        problems.push({ field: path, code: 'selection' });
      }
      return;
    case 'enum_array': {
      if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
        problems.push({ field: path, code: 'type' });
        return;
      }
      const strings = value as string[];
      if (strings.length < (contract.minimum_items ?? 0) || strings.length > (contract.maximum_items ?? Infinity) ||
        strings.some((item) => !contract.enum_values?.includes(item)) ||
        strings.join('\0') !== [...new Set(strings)].sort().join('\0')) {
        problems.push({ field: path, code: 'selection' });
      }
      return;
    }
    case 'object': {
      if (!value || typeof value !== 'object' || Array.isArray(value)) {
        problems.push({ field: path, code: 'type' });
        return;
      }
      const nested = value as Record<string, unknown>;
      const fields = contract.fields ?? {};
      for (const name of Object.keys(nested)) {
        if (!Object.hasOwn(fields, name)) problems.push({ field: `${path}.${name}`, code: 'unknown' });
      }
      for (const [name, child] of Object.entries(fields)) {
        if (!Object.hasOwn(nested, name)) {
          if (child.required) problems.push({ field: `${path}.${name}`, code: 'required' });
        } else {
          validateProfileField(nested[name], child, `${path}.${name}`, problems);
        }
      }
    }
  }
}

export type DecodedEventFieldValue = string | boolean | bigint | number;

/** Validate and decode one value received in an event envelope. */
export function decodeEventWireFieldValue(
  field: RegistryFieldContract,
  value: string,
  maximumEncodedBytes: number
): DecodedEventFieldValue {
  if (new TextEncoder().encode(value).length > maximumEncodedBytes) throw new Error('event_field_size');
  const length = field.length_unit === 'UTF8_BYTES' ? new TextEncoder().encode(value).length : value.length;
  if ((field.minimum_length !== null && length < field.minimum_length) ||
    (field.maximum_length !== null && length > field.maximum_length)) throw new Error('event_field_length');
  switch (field.wire_type) {
    case 'boolean':
      if (value !== 'true' && value !== 'false') throw new Error('event_boolean');
      return value === 'true';
    case 'int32':
    case 'int64_decimal':
    case 'uint64_decimal': {
      if (!/^(0|-?[1-9][0-9]*)$/.test(value)) throw new Error('event_integer');
      const integer = BigInt(value);
      if (field.wire_type === 'int32' && (integer < -2_147_483_648n || integer > 2_147_483_647n)) {
        throw new Error('event_int32_range');
      }
      if (field.wire_type === 'int64_decimal' && (integer < -(1n << 63n) || integer >= (1n << 63n))) {
        throw new Error('event_int64_range');
      }
      if (field.wire_type === 'uint64_decimal' && (integer < 0n || integer >= (1n << 64n))) {
        throw new Error('event_uint64_range');
      }
      if ((field.minimum !== null && integer < BigInt(field.minimum)) ||
        (field.maximum !== null && integer > BigInt(field.maximum))) throw new Error('event_integer_bounds');
      return integer;
    }
    case 'float32':
    case 'float64': {
      if (!isProtocolDecimalFloat(value)) throw new Error('event_float_grammar');
      const number = Number(value);
      if (!Number.isFinite(number) || (field.wire_type === 'float32' && !Number.isFinite(Math.fround(number)))) {
        throw new Error('event_float_finite');
      }
      if ((field.minimum !== null && number < field.minimum) ||
        (field.maximum !== null && number > field.maximum)) throw new Error('event_float_bounds');
      return number;
    }
    case 'enum':
      if (!field.enum_values.includes(value)) throw new Error('event_enum');
      return value;
    case 'uuid':
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) {
        throw new Error('event_uuid');
      }
      return value;
    case 'sha256_hex':
      if (!/^[0-9a-f]{64}$/.test(value)) throw new Error('event_digest');
      return value;
    case 'json_string':
      try {
        parseStrictEmbeddedJson(value);
      } catch {
        throw new Error('event_json');
      }
      return value;
    case 'string':
      return value;
  }
}

/** Decode a signature-covered matcher literal; float values have one canonical spelling. */
export function decodePredicateFieldValue(
  field: RegistryFieldContract,
  value: string,
  maximumEncodedBytes: number
): DecodedEventFieldValue {
  const decoded = decodeEventWireFieldValue(field, value, maximumEncodedBytes);
  if ((field.wire_type === 'float32' || field.wire_type === 'float64') &&
    (typeof decoded !== 'number' || javaDoubleString(decoded) !== value)) {
    throw new Error('predicate_float_canonical');
  }
  return decoded;
}

export function canonicalPredicateFieldValue(
  event: RegistryEventContract,
  fieldName: string,
  value: string
): boolean {
  const field = event.fields[fieldName];
  if (!field) return false;
  try {
    decodePredicateFieldValue(field, value, event.maximum_encoded_event_bytes);
    return true;
  } catch {
    return false;
  }
}
