/** Authenticated Protocol v1 `.partexp` reader.
 *
 * The browser keeps this as a convenience reader for bounded bundles. The offline Python pipeline
 * remains the analysis path for large studies. No plaintext value is returned until both AEAD
 * layers, canonical JSON, embedded configuration provenance, identities, and actual event range
 * have all verified.
 */

import {
  canonicalBytes,
  canonicalConfigurationBytes,
  canonicalize,
  canonicalizeConfiguration,
  isCanonicalDecimal,
  parseCanonicalJson
} from './canonical';
import { decodeBase64Url, encodeBase64Url, verify } from './crypto';
import { ID_PATTERN, type StudyConfiguration } from './types';
import { x25519 } from '@noble/curves/ed25519.js';
import { expand, extract } from '@noble/hashes/hkdf.js';
import { sha256 } from '@noble/hashes/sha2.js';
import collectorCatalog from '../../../../protocol/v1/collector-catalog.json';

const MAGIC = 'PTCEXP01';
const FIXED_HEADER_BYTES = 70;
const BUNDLE_ID_BYTES = 16;
const DIGEST_BYTES = 32;
const NONCE_BYTES = 12;
const WRAPPED_KEY_BYTES = 80;
const TAG_BYTES = 16;
const CONTENT_KEY_BYTES = 32;
const MINIMUM_KEY_ID_BYTES = 3;
const MAXIMUM_KEY_ID_BYTES = 64;
const MAXIMUM_INT64 = 9_223_372_036_854_775_807n;
const PARTICIPANT_INSTANCE_ID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const CANONICAL_SIGNED_INTEGER = /^(?:0|-?[1-9][0-9]*)$/;
const DECIMAL_FLOAT = /^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)$/;
const UTF8 = new TextEncoder();
const FATAL_UTF8 = new TextDecoder('utf-8', { fatal: true });

type CatalogField = {
  type: 'boolean' | 'decimal_string' | 'enum' | 'float32' | 'float64' | 'int32' | 'json_string' | 'string';
  required: boolean;
  enum?: string[];
  minimum?: number;
  maximum?: number;
  maximum_length?: number;
};
type CatalogPayload = { fields: Record<string, CatalogField>; types: string[] };
type CatalogCollector = {
  id: string;
  maximum_encoded_event_bytes: number;
  payload_schema_version: number;
  payloads: CatalogPayload[];
};
const EVENT_CONTRACTS = new Map(
  (collectorCatalog.collectors as CatalogCollector[]).map((collector) => [collector.id, collector])
);
const TRANSITION_DESTINATIONS: Record<string, string> = {
  ACCESS_PREFLIGHT_PASSED: 'READY',
  CONFIGURATION_SIGNATURE_VERIFIED: 'CONFIG_VERIFIED',
  CONSENT_ACCEPTED: 'ACCESS_SETUP',
  CONSENT_REVIEW_OPENED: 'CONSENT_PENDING',
  PARTICIPANT_PAUSED: 'PAUSED',
  PARTICIPANT_RESUMED: 'RUNNING',
  PARTICIPANT_STARTED: 'RUNNING',
  PARTICIPANT_WITHDREW: 'WITHDRAWN',
  COLLECTION_HOST_FAILURE: 'PAUSED',
  COLLECTION_TEARDOWN_FAILURE: 'PAUSED',
  REQUIRED_ACCESS_MISSING: 'PAUSED',
  STORAGE_FAILURE: 'PAUSED',
  WORK_SCHEDULING_FAILURE: 'PAUSED',
  STUDY_DURATION_ELAPSED: 'COMPLETED'
};
const STATE_TRANSITIONS: Record<string, string[]> = {
  ACCESS_SETUP: ['READY', 'WITHDRAWN'],
  COMPLETED: ['WITHDRAWN'],
  CONFIG_VERIFIED: ['CONSENT_PENDING', 'WITHDRAWN'],
  CONSENT_PENDING: ['ACCESS_SETUP', 'WITHDRAWN'],
  IMPORTED: ['CONFIG_VERIFIED', 'WITHDRAWN'],
  PAUSED: ['RUNNING', 'COMPLETED', 'WITHDRAWN'],
  READY: ['RUNNING', 'WITHDRAWN'],
  RUNNING: ['PAUSED', 'COMPLETED', 'WITHDRAWN'],
  WITHDRAWN: []
};

// Browser-only memory policy, not a Protocol wire limit. Automatic uploads happen to share this
// bound; larger manual exports remain valid and belong in the streaming particeps-analysis CLI.
const MAXIMUM_BROWSER_PREVIEW_BYTES = 33_554_432;
export const BUNDLE_FORMAT = 'particeps-research-bundle-v1';

export interface ResearchTime {
  wall_time_utc_millis: string;
  monotonic_time_nanos: string;
  boot_session_id: string;
}

export interface ResearchEvent {
  sequence_number: string;
  collector_id: string;
  payload_schema_version: number;
  observed_time: ResearchTime;
  payload_type: string;
  fields: Record<string, string>;
}

export interface ResearchTransition {
  from: string;
  to: string;
  reason: string;
  time: ResearchTime;
}

export interface ResearchExperiment {
  experiment_id: string;
  configuration_id: string;
  participant_instance_id: string;
  assigned_participant_id: string | null;
  state: string;
  retained_from_sequence: string;
  uploaded_through_sequence: string;
  durable_through_sequence: string;
  next_sequence_number: string;
  first_sequence_number: string;
  last_sequence_number: string;
  event_count: string;
  transitions: ResearchTransition[];
  events: ResearchEvent[];
}

export interface ResearchDocument {
  format: typeof BUNDLE_FORMAT;
  bundle_id: string;
  bundle_kind: 'manual_export' | 'automatic_upload';
  configuration_sha256: string;
  producer: { platform: 'android'; client_version: string };
  exported_at_utc_millis: string;
  configuration: StudyConfiguration;
  configuration_signature: { signer_key_id: string; signature: string };
  experiment: ResearchExperiment;
}

export interface ResearchBundle {
  keyId: string;
  document: ResearchDocument;
  text: string;
  bytes: number;
}

export type BundleFailure =
  | 'not_a_bundle'
  | 'too_large'
  | 'wrong_study'
  | 'wrong_key'
  | 'unwrap_failed'
  | 'tag_failed'
  | 'unreadable';

export type BundleResult =
  | { ok: true; bundle: ResearchBundle }
  | { ok: false; failure: BundleFailure };

const failed = (failure: BundleFailure): BundleResult => ({ ok: false, failure });
const source = (bytes: Uint8Array): BufferSource => bytes as unknown as BufferSource;

export function configurationDigest(configuration: StudyConfiguration): Uint8Array {
  return sha256(canonicalConfigurationBytes(configuration));
}

export function bundleContext(
  bundleId: string,
  configurationSha256: string,
  researcherKeyId: string
): Uint8Array {
  return canonicalBytes({
    bundle_format: BUNDLE_FORMAT,
    bundle_id: bundleId,
    configuration_sha256: configurationSha256,
    researcher_key_id: researcherKeyId
  });
}

export async function openBundle(
  bytes: Uint8Array,
  configuration: StudyConfiguration,
  privateKey: string
): Promise<BundleResult> {
  if (bytes.length > MAXIMUM_BROWSER_PREVIEW_BYTES) return failed('too_large');
  if (bytes.length < FIXED_HEADER_BYTES + MINIMUM_KEY_ID_BYTES + WRAPPED_KEY_BYTES + TAG_BYTES) {
    return failed('not_a_bundle');
  }
  for (let index = 0; index < MAGIC.length; index += 1) {
    if (bytes[index] !== MAGIC.charCodeAt(index)) return failed('not_a_bundle');
  }

  const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const keyIdLength = header.getUint16(56);
  if (keyIdLength < MINIMUM_KEY_ID_BYTES || keyIdLength > MAXIMUM_KEY_ID_BYTES) {
    return failed('not_a_bundle');
  }
  const keyIdEnd = FIXED_HEADER_BYTES + keyIdLength;
  const wrappedEnd = keyIdEnd + WRAPPED_KEY_BYTES;
  if (bytes.length <= wrappedEnd + TAG_BYTES) return failed('not_a_bundle');

  let keyId: string;
  try {
    keyId = FATAL_UTF8.decode(bytes.subarray(FIXED_HEADER_BYTES, keyIdEnd));
  } catch {
    return failed('not_a_bundle');
  }
  const bundleId = uuid(bytes.subarray(8, 8 + BUNDLE_ID_BYTES));
  if (!bundleId || !ID_PATTERN.test(keyId)) return failed('not_a_bundle');
  const digest = bytes.subarray(24, 24 + DIGEST_BYTES);
  const digestHex = hex(digest);
  const nonce = bytes.subarray(58, 58 + NONCE_BYTES);
  const wrapped = bytes.subarray(keyIdEnd, wrappedEnd);
  const body = bytes.subarray(wrappedEnd);

  if (keyId !== configuration.export.researcher_key_id) return failed('wrong_study');
  if (!same(digest, configurationDigest(configuration))) return failed('wrong_study');

  let recipientPrivate: Uint8Array;
  let recipientPublic: Uint8Array;
  try {
    recipientPrivate = decodeBase64Url(privateKey.trim(), 32);
    recipientPublic = x25519.getPublicKey(recipientPrivate);
    if (!same(recipientPublic, decodeBase64Url(configuration.export.hpke_public_key, 32))) {
      return failed('wrong_key');
    }
  } catch {
    return failed('wrong_key');
  }

  const context = bundleContext(bundleId, digestHex, keyId);
  const contentKey = await unwrap(wrapped, recipientPublic, recipientPrivate, context);
  if (!contentKey) return failed('unwrap_failed');

  let plaintext: Uint8Array;
  try {
    const key = await crypto.subtle.importKey('raw', source(contentKey), 'AES-GCM', false, [
      'decrypt'
    ]);
    plaintext = new Uint8Array(
      await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: source(nonce), tagLength: 128, additionalData: source(context) },
        key,
        source(body)
      )
    );
  } catch {
    return failed('tag_failed');
  }

  let text: string;
  let parsed: unknown;
  try {
    text = FATAL_UTF8.decode(plaintext);
    parsed = parseCanonicalJson(plaintext);
  } catch {
    return failed('unreadable');
  }
  const document = readDocument(parsed, configuration, bundleId, digestHex);
  return document
    ? { ok: true, bundle: { keyId, document, text, bytes: plaintext.length } }
    : failed('unreadable');
}

/* RFC 9180 base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM. */
const KEM_ID = 0x0020;
const KDF_ID = 0x0001;
const AEAD_ID = 0x0002;
const EMPTY = new Uint8Array(0);
const i2osp2 = (value: number) => Uint8Array.of((value >> 8) & 0xff, value & 0xff);
const SUITE_KEM = concat(UTF8.encode('KEM'), i2osp2(KEM_ID));
const SUITE_HPKE = concat(
  UTF8.encode('HPKE'),
  i2osp2(KEM_ID),
  i2osp2(KDF_ID),
  i2osp2(AEAD_ID)
);
const VERSION = UTF8.encode('HPKE-v1');

const labeledExtract = (suite: Uint8Array, salt: Uint8Array, label: string, ikm: Uint8Array) =>
  extract(sha256, concat(VERSION, suite, UTF8.encode(label), ikm), salt);
const labeledExpand = (
  suite: Uint8Array,
  prk: Uint8Array,
  label: string,
  info: Uint8Array,
  length: number
) => expand(sha256, prk, concat(i2osp2(length), VERSION, suite, UTF8.encode(label), info), length);

async function unwrap(
  wrapped: Uint8Array,
  recipientPublic: Uint8Array,
  recipientPrivate: Uint8Array,
  info: Uint8Array
): Promise<Uint8Array | null> {
  if (wrapped.length !== WRAPPED_KEY_BYTES) return null;
  const enc = wrapped.subarray(0, 32);
  const sealed = wrapped.subarray(32);
  try {
    const dh = x25519.getSharedSecret(recipientPrivate, enc);
    const eaePrk = labeledExtract(SUITE_KEM, EMPTY, 'eae_prk', dh);
    const shared = labeledExpand(
      SUITE_KEM,
      eaePrk,
      'shared_secret',
      concat(enc, recipientPublic),
      32
    );
    const schedule = concat(
      Uint8Array.of(0),
      labeledExtract(SUITE_HPKE, EMPTY, 'psk_id_hash', EMPTY),
      labeledExtract(SUITE_HPKE, EMPTY, 'info_hash', info)
    );
    const secret = labeledExtract(SUITE_HPKE, shared, 'secret', EMPTY);
    const key = await crypto.subtle.importKey(
      'raw',
      source(labeledExpand(SUITE_HPKE, secret, 'key', schedule, 32)),
      'AES-GCM',
      false,
      ['decrypt']
    );
    const baseNonce = labeledExpand(SUITE_HPKE, secret, 'base_nonce', schedule, NONCE_BYTES);
    const opened = new Uint8Array(
      await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: source(baseNonce), tagLength: 128, additionalData: source(EMPTY) },
        key,
        source(sealed)
      )
    );
    return opened.length === CONTENT_KEY_BYTES ? opened : null;
  } catch {
    return null;
  }
}

function readDocument(
  parsed: unknown,
  expectedConfiguration: StudyConfiguration,
  bundleId: string,
  configurationSha256: string
): ResearchDocument | null {
  const root = exact(parsed, [
    'bundle_id',
    'bundle_kind',
    'configuration',
    'configuration_sha256',
    'configuration_signature',
    'experiment',
    'exported_at_utc_millis',
    'format',
    'producer'
  ]);
  if (!root || root.format !== BUNDLE_FORMAT || root.bundle_id !== bundleId) return null;
  if (root.bundle_kind !== 'manual_export' && root.bundle_kind !== 'automatic_upload') return null;
  if (root.configuration_sha256 !== configurationSha256) return null;
  if (!decimal(root.exported_at_utc_millis)) return null;

  const producer = exact(root.producer, ['client_version', 'platform']);
  if (!producer || producer.platform !== 'android' || !positiveDecimal(producer.client_version)) {
    return null;
  }
  if (BigInt(producer.client_version as string) < BigInt(expectedConfiguration.minimum_client_version)) {
    return null;
  }

  const configuration = record(root.configuration);
  if (!configuration || canonicalize(configuration) !== canonicalizeConfiguration(expectedConfiguration)) {
    return null;
  }
  if (hex(sha256(canonicalBytes(configuration))) !== configurationSha256) return null;

  const provenance = exact(root.configuration_signature, ['signature', 'signer_key_id']);
  if (!provenance || provenance.signer_key_id !== expectedConfiguration.signer.key_id) return null;
  let signature: Uint8Array;
  try {
    signature = decodeBase64Url(string(provenance.signature), 64);
  } catch {
    return null;
  }
  if (!verify(canonicalBytes(configuration), signature, expectedConfiguration.signer.public_key)) {
    return null;
  }

  const experiment = readExperiment(root.experiment, expectedConfiguration);
  if (!experiment) return null;
  if (
    root.bundle_kind === 'automatic_upload' &&
    (experiment.event_count === '0' ||
      BigInt(experiment.first_sequence_number) !== BigInt(experiment.uploaded_through_sequence) + 1n)
  ) return null;
  return {
    format: BUNDLE_FORMAT,
    bundle_id: bundleId,
    bundle_kind: root.bundle_kind,
    configuration_sha256: configurationSha256,
    producer: { platform: 'android', client_version: producer.client_version as string },
    exported_at_utc_millis: root.exported_at_utc_millis as string,
    configuration: expectedConfiguration,
    configuration_signature: {
      signer_key_id: provenance.signer_key_id as string,
      signature: encodeBase64Url(signature)
    },
    experiment
  };
}

function readExperiment(raw: unknown, configuration: StudyConfiguration): ResearchExperiment | null {
  const source = exact(raw, [
    'assigned_participant_id',
    'configuration_id',
    'durable_through_sequence',
    'event_count',
    'events',
    'experiment_id',
    'first_sequence_number',
    'last_sequence_number',
    'next_sequence_number',
    'participant_instance_id',
    'retained_from_sequence',
    'state',
    'transitions',
    'uploaded_through_sequence'
  ]);
  if (!source) return null;
  if (
    source.experiment_id !== configuration.experiment_id ||
    source.configuration_id !== configuration.configuration_id ||
    source.assigned_participant_id !== configuration.assigned_participant_id ||
    typeof source.participant_instance_id !== 'string' ||
    !PARTICIPANT_INSTANCE_ID.test(source.participant_instance_id) ||
    !nonempty(source.state)
  ) return null;

  const decimalKeys = [
    'durable_through_sequence',
    'event_count',
    'first_sequence_number',
    'last_sequence_number',
    'next_sequence_number',
    'retained_from_sequence',
    'uploaded_through_sequence'
  ] as const;
  if (decimalKeys.some((key) => !decimal(source[key]))) return null;
  if (!Array.isArray(source.events) || !Array.isArray(source.transitions)) return null;

  const durable = BigInt(source.durable_through_sequence as string);
  const next = BigInt(source.next_sequence_number as string);
  const retained = BigInt(source.retained_from_sequence as string);
  const uploaded = BigInt(source.uploaded_through_sequence as string);
  const first = BigInt(source.first_sequence_number as string);
  const last = BigInt(source.last_sequence_number as string);
  if (
    next !== durable + 1n || retained < 1n || retained > next || uploaded >= next ||
    retained > uploaded + 1n || first < retained || last > durable
  ) return null;

  const events: ResearchEvent[] = [];
  for (const rawEvent of source.events) {
    const event = readEvent(rawEvent, configuration);
    if (!event) return null;
    events.push(event);
  }
  if (BigInt(source.event_count as string) !== BigInt(events.length)) return null;
  if (events.length > 0) {
    for (let index = 1; index < events.length; index += 1) {
      if (BigInt(events[index].sequence_number) !== BigInt(events[index - 1].sequence_number) + 1n) {
        return null;
      }
    }
    if (
      events[0].sequence_number !== source.first_sequence_number ||
      events[events.length - 1].sequence_number !== source.last_sequence_number
    ) return null;
  } else if (
    BigInt(source.last_sequence_number as string) + 1n !==
      BigInt(source.first_sequence_number as string)
  ) {
    return null;
  }

  const transitions: ResearchTransition[] = [];
  let transitionState = 'IMPORTED';
  for (const rawTransition of source.transitions) {
    const transition = readTransition(rawTransition);
    if (
      !transition || transition.from !== transitionState ||
      TRANSITION_DESTINATIONS[transition.reason] !== transition.to ||
      !STATE_TRANSITIONS[transition.from]?.includes(transition.to)
    ) return null;
    transitionState = transition.to;
    transitions.push(transition);
  }
  if (
    (transitions.length === 0 && source.state !== 'IMPORTED') ||
    (transitions.length > 0 && source.state !== transitionState)
  ) return null;

  return {
    experiment_id: configuration.experiment_id,
    configuration_id: configuration.configuration_id,
    participant_instance_id: source.participant_instance_id as string,
    assigned_participant_id: configuration.assigned_participant_id,
    state: source.state as string,
    retained_from_sequence: source.retained_from_sequence as string,
    uploaded_through_sequence: source.uploaded_through_sequence as string,
    durable_through_sequence: source.durable_through_sequence as string,
    next_sequence_number: source.next_sequence_number as string,
    first_sequence_number: source.first_sequence_number as string,
    last_sequence_number: source.last_sequence_number as string,
    event_count: source.event_count as string,
    transitions,
    events
  };
}

function readEvent(raw: unknown, configuration: StudyConfiguration): ResearchEvent | null {
  const source = exact(raw, [
    'collector_id',
    'fields',
    'observed_time',
    'payload_schema_version',
    'payload_type',
    'sequence_number'
  ]);
  const fields = source && record(source.fields);
  const time = source && readTime(source.observed_time);
  if (
    !source || !fields || !time || !decimal(source.sequence_number) ||
    !nonempty(source.collector_id) || !nonempty(source.payload_type) ||
    !Number.isSafeInteger(source.payload_schema_version) ||
    (source.payload_schema_version as number) < 1
  ) return null;
  if (Object.values(fields).some((value) => typeof value !== 'string')) return null;
  const event = {
    sequence_number: source.sequence_number as string,
    collector_id: source.collector_id as string,
    payload_schema_version: source.payload_schema_version as number,
    observed_time: time,
    payload_type: source.payload_type as string,
    fields: fields as Record<string, string>
  };
  return acceptsEvent(event, source, configuration) ? event : null;
}

function readTransition(raw: unknown): ResearchTransition | null {
  const source = exact(raw, ['from', 'reason', 'time', 'to']);
  const time = source && readTime(source.time);
  if (!source || !time || !nonempty(source.from) || !nonempty(source.to) || !nonempty(source.reason)) {
    return null;
  }
  return { from: source.from, to: source.to, reason: source.reason, time } as ResearchTransition;
}

function readTime(raw: unknown): ResearchTime | null {
  const source = exact(raw, ['boot_session_id', 'monotonic_time_nanos', 'wall_time_utc_millis']);
  if (
    !source || !nonempty(source.boot_session_id) ||
    UTF8.encode(source.boot_session_id as string).length > 128 ||
    !decimal(source.monotonic_time_nanos) ||
    !decimal(source.wall_time_utc_millis)
  ) return null;
  return {
    boot_session_id: source.boot_session_id as string,
    monotonic_time_nanos: source.monotonic_time_nanos as string,
    wall_time_utc_millis: source.wall_time_utc_millis as string
  };
}

function acceptsEvent(
  event: ResearchEvent,
  raw: Record<string, unknown>,
  configuration: StudyConfiguration
): boolean {
  const configured = configuration.collectors.some((collector) => collector.id === event.collector_id) ||
    (event.collector_id === 'interventions.v1' && configuration.interventions.length > 0);
  const contract = EVENT_CONTRACTS.get(event.collector_id);
  if (!configured || !contract || event.payload_schema_version !== contract.payload_schema_version) {
    return false;
  }
  const payload = contract.payloads.find((candidate) => candidate.types.includes(event.payload_type));
  if (!payload) return false;
  const names = Object.keys(event.fields);
  if (
    names.some((name) => !Object.hasOwn(payload.fields, name)) ||
    Object.entries(payload.fields).some(([name, field]) =>
      field.required ? !Object.hasOwn(event.fields, name) : false
    ) ||
    Object.entries(event.fields).some(([name, value]) => !acceptsField(value, payload.fields[name]))
  ) return false;
  return canonicalBytes(raw).length <= contract.maximum_encoded_event_bytes;
}

function acceptsField(value: string, field: CatalogField): boolean {
  if (field.maximum_length !== undefined && value.length > field.maximum_length) return false;
  let numeric: number;
  switch (field.type) {
    case 'boolean':
      return value === 'true' || value === 'false';
    case 'decimal_string':
      return decimal(value);
    case 'enum':
      return field.enum?.includes(value) === true;
    case 'float32':
      if (!DECIMAL_FLOAT.test(value)) return false;
      numeric = Number(value);
      return Number.isFinite(numeric) && Number.isFinite(Math.fround(numeric)) && inRange(numeric, field);
    case 'float64':
      if (!DECIMAL_FLOAT.test(value)) return false;
      numeric = Number(value);
      return Number.isFinite(numeric) && inRange(numeric, field);
    case 'int32':
      if (!CANONICAL_SIGNED_INTEGER.test(value)) return false;
      numeric = Number(value);
      return Number.isInteger(numeric) && numeric >= -2_147_483_648 && numeric <= 2_147_483_647 &&
        inRange(numeric, field);
    case 'json_string':
      try {
        JSON.parse(value);
        return true;
      } catch {
        return false;
      }
    case 'string':
      return true;
  }
}

const inRange = (value: number, field: CatalogField) =>
  (field.minimum === undefined || value >= field.minimum) &&
  (field.maximum === undefined || value <= field.maximum);

function exact(value: unknown, keys: readonly string[]): Record<string, unknown> | null {
  const candidate = record(value);
  if (!candidate) return null;
  const actual = Object.keys(candidate);
  return actual.length === keys.length && keys.every((key) => Object.hasOwn(candidate, key))
    ? candidate
    : null;
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

const string = (value: unknown): string => typeof value === 'string' ? value : '';
const nonempty = (value: unknown): value is string => typeof value === 'string' && value.length > 0;
const decimal = (value: unknown): value is string => isCanonicalDecimal(value, MAXIMUM_INT64);
const positiveDecimal = (value: unknown): value is string => decimal(value) && value !== '0';

function uuid(bytes: Uint8Array): string | null {
  if (
    bytes.length !== BUNDLE_ID_BYTES ||
    (bytes[6] & 0xf0) !== 0x40 ||
    (bytes[8] & 0xc0) !== 0x80
  ) return null;
  const value = hex(bytes);
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

function same(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((byte, index) => byte === right[index]);
}
