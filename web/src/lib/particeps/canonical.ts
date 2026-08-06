/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) for Protocol v1 values.
 *
 * JCS uses ECMAScript's JSON primitive serialization and recursively sorts object member names by
 * UTF-16 code units. Protocol values are also I-JSON: lone surrogates and values JSON cannot encode
 * are rejected instead of repaired. Schema validation is responsible for the narrower Particeps rule
 * that configuration numbers are bounded integers.
 */

import type { StudyConfiguration } from './types';

const ENCODER = new TextEncoder();
const FATAL_DECODER = new TextDecoder('utf-8', { fatal: true });

export function canonicalize(value: unknown): string {
  return encode(value, new Set<object>());
}

export function canonicalBytes(value: unknown): Uint8Array {
  return ENCODER.encode(canonicalize(value));
}

/** Parse only canonical UTF-8 JSON. This rejects whitespace, duplicate members, and alternate
 * number/string spellings because re-encoding must reproduce every input byte. */
export function parseCanonicalJson(bytes: Uint8Array): unknown {
  let text: string;
  let value: unknown;
  try {
    text = FATAL_DECODER.decode(bytes);
    value = JSON.parse(text);
  } catch {
    throw new Error('canonical_json_invalid');
  }
  if (canonicalize(value) !== text) throw new Error('canonical_json_required');
  return value;
}

/** The Protocol v1 configuration value before JCS. The in-memory model uses `null` for disabled
 * upload, while the one wire shape uses an exact empty object. Kotlin also normalizes instants and
 * the set-like transport list before its canonical-byte equality check. */
export function configurationValue(configuration: StudyConfiguration): unknown {
  const normalizeInstant = (value: string) => {
    const parsed = parseInstant(value);
    return parsed ? formatInstant(parsed) : value;
  };
  return {
    ...configuration,
    issued_at: normalizeInstant(configuration.issued_at),
    expires_at: normalizeInstant(configuration.expires_at),
    collectors: configuration.collectors.map((collector) =>
      collector.id === 'network_usage.v1'
        ? {
            ...collector,
            config: {
              ...collector.config,
              transports: [...new Set(collector.config.transports)].sort()
            }
          }
        : collector
    ),
    upload: configuration.upload ?? {}
  };
}

export function canonicalizeConfiguration(configuration: StudyConfiguration): string {
  return canonicalize(configurationValue(configuration));
}

export function canonicalConfigurationBytes(configuration: StudyConfiguration): Uint8Array {
  return ENCODER.encode(canonicalizeConfiguration(configuration));
}

function encode(value: unknown, ancestors: Set<object>): string {
  if (value === null) return 'null';
  switch (typeof value) {
    case 'string':
      assertUnicodeScalarString(value);
      return JSON.stringify(value);
    case 'number': {
      if (!Number.isFinite(value)) throw new Error('jcs_number');
      return JSON.stringify(value);
    }
    case 'boolean':
      return value ? 'true' : 'false';
    case 'object':
      break;
    default:
      throw new Error('jcs_type');
  }

  const object = value as object;
  if (ancestors.has(object)) throw new Error('jcs_cycle');
  ancestors.add(object);
  try {
    if (Array.isArray(object)) {
      const entries: string[] = [];
      for (let index = 0; index < object.length; index += 1) {
        if (!Object.hasOwn(object, index)) throw new Error('jcs_sparse_array');
        entries.push(encode(object[index], ancestors));
      }
      return `[${entries.join(',')}]`;
    }

    const prototype = Object.getPrototypeOf(object);
    if (prototype !== Object.prototype && prototype !== null) throw new Error('jcs_object');
    const record = object as Record<string, unknown>;
    const members = Object.keys(record).sort().map((key) => {
      assertUnicodeScalarString(key);
      return `${JSON.stringify(key)}:${encode(record[key], ancestors)}`;
    });
    return `{${members.join(',')}}`;
  } finally {
    ancestors.delete(object);
  }
}

function assertUnicodeScalarString(value: string): void {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit < 0xd800 || unit > 0xdfff) continue;
    if (unit > 0xdbff || index + 1 >= value.length) throw new Error('jcs_unicode');
    const next = value.charCodeAt(++index);
    if (next < 0xdc00 || next > 0xdfff) throw new Error('jcs_unicode');
  }
}

export const CANONICAL_DECIMAL = /^(0|[1-9][0-9]*)$/;

export function isCanonicalDecimal(value: unknown, maximum?: bigint): value is string {
  if (typeof value !== 'string' || !CANONICAL_DECIMAL.test(value)) return false;
  return maximum === undefined || BigInt(value) <= maximum;
}

/** A `java.time.Instant`, decomposed. */
export interface Instant {
  second: number;
  nano: number;
}

const INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2}(?::\d{2})?)$/;
const MINIMUM_INSTANT_SECOND = -62_167_219_200;
const MAXIMUM_INSTANT_SECOND = 253_402_300_799;

/** Parse the ISO-8601 spellings accepted for configuration validity windows. */
export function parseInstant(text: string): Instant | null {
  const match = INSTANT.exec(text);
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  if (month < 1 || month > 12 || day < 1 || day > monthLength(year, month)) return null;
  if (hour > 23 || minute > 59 || second > 59) return null;
  const offset = offsetSeconds(match[8]);
  if (offset === null) return null;
  const epochSecond =
    epochDay(year, month, day) * 86_400 + hour * 3_600 + minute * 60 + second - offset;
  if (epochSecond < MINIMUM_INSTANT_SECOND || epochSecond > MAXIMUM_INSTANT_SECOND) return null;
  return { second: epochSecond, nano: match[7] ? Number(match[7].padEnd(9, '0')) : 0 };
}

/** Canonical UTC spelling used by the authoring defaults. */
export function formatInstant(instant: Instant): string {
  const day = Math.floor(instant.second / 86_400);
  const time = instant.second - day * 86_400;
  const [year, month, dayOfMonth] = civilFromEpochDay(day);
  const date = `${pad(year, 4)}-${pad(month, 2)}-${pad(dayOfMonth, 2)}`;
  const clock = `${pad(Math.floor(time / 3_600), 2)}:${pad(Math.floor(time / 60) % 60, 2)}:${pad(time % 60, 2)}`;
  return `${date}T${clock}${fraction(instant.nano)}Z`;
}

function fraction(nano: number): string {
  if (nano === 0) return '';
  if (nano % 1_000_000 === 0) return `.${pad(nano / 1_000_000, 3)}`;
  if (nano % 1_000 === 0) return `.${pad(nano / 1_000, 6)}`;
  return `.${pad(nano, 9)}`;
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, '0');
}

function monthLength(year: number, month: number): number {
  if (month === 2) return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0 ? 29 : 28;
  return month === 4 || month === 6 || month === 9 || month === 11 ? 30 : 31;
}

function offsetSeconds(zone: string): number | null {
  if (zone === 'Z') return 0;
  const [hour, minute, second = 0] = zone.slice(1).split(':').map(Number);
  if (minute > 59 || second > 59) return null;
  const magnitude = hour * 3_600 + minute * 60 + second;
  if (magnitude > 18 * 3_600) return null;
  return zone[0] === '-' ? -magnitude : magnitude;
}

function epochDay(year: number, month: number, day: number): number {
  const shifted = year - (month <= 2 ? 1 : 0);
  const era = Math.floor(shifted / 400);
  const yearOfEra = shifted - era * 400;
  const dayOfYear = Math.trunc((153 * (month > 2 ? month - 3 : month + 9) + 2) / 5) + day - 1;
  const dayOfEra =
    yearOfEra * 365 + Math.trunc(yearOfEra / 4) - Math.trunc(yearOfEra / 100) + dayOfYear;
  return era * 146_097 + dayOfEra - 719_468;
}

function civilFromEpochDay(days: number): [number, number, number] {
  const shifted = days + 719_468;
  const era = Math.floor(shifted / 146_097);
  const dayOfEra = shifted - era * 146_097;
  const yearOfEra = Math.trunc(
    (dayOfEra - Math.trunc(dayOfEra / 1_460) + Math.trunc(dayOfEra / 36_524) -
      Math.trunc(dayOfEra / 146_096)) / 365
  );
  const dayOfYear =
    dayOfEra - (365 * yearOfEra + Math.trunc(yearOfEra / 4) - Math.trunc(yearOfEra / 100));
  const monthIndex = Math.trunc((5 * dayOfYear + 2) / 153);
  const day = dayOfYear - Math.trunc((153 * monthIndex + 2) / 5) + 1;
  const month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9;
  return [yearOfEra + era * 400 + (month <= 2 ? 1 : 0), month, day];
}
