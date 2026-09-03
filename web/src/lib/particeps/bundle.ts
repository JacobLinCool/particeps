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
import {
  EVENT_SOURCE_CONTRACTS,
  EVENT_SOURCE_REGISTRY_SHA256,
  type RegistrySourceContract,
  type RegistryFieldContract
} from './generated/event-source-registry.ts';
import { parseStrictEmbeddedJson } from './wire-json';

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
const MAXIMUM_UINT64 = (1n << 64n) - 1n;
const CANONICAL_SIGNED_INTEGER = /^(?:0|-?[1-9][0-9]*)$/;
const DECIMAL_FLOAT = /^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)$/;
const SHA256_HEX = /^[0-9a-f]{64}$/;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const UUID_RFC4122 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SOURCE_ID = /^[a-z][a-z0-9_.-]{2,63}$/;
const EVENT_TYPE = /^[A-Z][A-Z0-9_]{1,63}$/;
const FIELD_KEY = /^[a-z][a-z0-9_]{0,63}$/;
const COMPONENT_ID = /^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,191}$/;
const RESOURCE_ID = /^[a-z][a-z0-9_.-]{2,63}$/;
const AUTOMATION_ID = /^[a-z0-9][a-z0-9-]{2,63}$/;
const TIMER_ID = SHA256_HEX;
const GENESIS_DIGEST = '0'.repeat(64);
const MAXIMUM_OBSERVATION_EVENTS = 4_096;
const MAXIMUM_EVENTS_PER_COMMIT = 262_144;
const MAXIMUM_MUTATIONS_PER_COMMIT = 4_096;
const MAXIMUM_SOURCE_CHECKPOINTS = 128;
const MAXIMUM_COMPONENT_BYTES = 512 * 1_024;
const CHECKPOINT_PREFIX = 'automation-checkpoint-v1:';
const CHECKPOINT_COMPONENT_ID = /^main(?:\/[0-9]{4})?$/;
const UTF8 = new TextEncoder();
const FATAL_UTF8 = new TextDecoder('utf-8', { fatal: true });
const EVENT_CONTRACTS = new Map(
  EVENT_SOURCE_CONTRACTS.map((source) => [source.source_id, source])
);
const EXPERIMENT_STATES = new Set([
  'IMPORTED', 'CONFIG_VERIFIED', 'CONSENT_PENDING', 'ACCESS_SETUP', 'READY', 'ACTIVATING',
  'RUNNING', 'PAUSING', 'PAUSED', 'COMPLETED', 'WITHDRAWN'
]);
const INPUT_KINDS = new Set([
  'SOURCE_OBSERVATION', 'LIFECYCLE_COMMAND', 'TIMER_WAKE', 'RANDOM_SELECTION', 'ACTION_RESULT',
  'UPLOAD_ACKNOWLEDGEMENT', 'RESOURCE_RESULT', 'SAFETY_FAILURE', 'RECOVERY'
]);
const COMPONENT_KINDS = [
  'AUTOMATION_CHECKPOINT', 'TIMER', 'STUDY_DEADLINE_TIMER', 'RESOURCE_AUDIT_TIMER', 'ACTION_INVOCATION',
  'UPLOAD_ACKNOWLEDGEMENT', 'RESOURCE', 'RESOURCE_CLEANUP'
] as const;
const COMPONENT_KIND_ORDER = new Map(COMPONENT_KINDS.map((kind, index) => [kind, index]));

export function isRuntimeComponentKind(value: string): value is typeof COMPONENT_KINDS[number] {
  return COMPONENT_KIND_ORDER.has(value as typeof COMPONENT_KINDS[number]);
}

// Browser-only memory policy, not a Protocol wire limit. Automatic uploads happen to share this
// bound; larger manual exports remain valid and belong in the streaming particeps-analysis CLI.
const MAXIMUM_BROWSER_PREVIEW_BYTES = 33_554_432;
export const BUNDLE_FORMAT = 'particeps-research-bundle-v1';

export interface ResearchTime {
  wall_time_utc_millis: string;
  elapsed_realtime_nanos: string;
  boot_session_id: string;
}

export interface ResearchEvent {
  sequence_number: string;
  source_id: string;
  schema_version: number;
  observed_time: ResearchTime;
  event_type: string;
  condition_epoch_id: string | null;
  fields: Record<string, string>;
}

export interface SourceCoverage {
  clock_basis: 'OBSERVED_RESEARCH_TIME' | 'SOURCE_WALL_TIME' | 'SOURCE_MONOTONIC_TIME';
  start_inclusive: string;
  end_exclusive: string;
}

export interface SourceObservation {
  observation_sequence: string;
  source_id: string;
  schema_version: number;
  resource_generation: string;
  admission_kind: 'NORMAL' | 'BARRIER_FLUSH';
  producer_ordinal: string;
  condition_epoch_id: string;
  event_count: number;
  first_event_sequence: string | null;
  last_event_sequence: string | null;
  coverage: SourceCoverage | null;
  encoded_sha256: string;
}

export interface RuntimeMutation {
  component_kind: typeof COMPONENT_KINDS[number];
  component_id: string;
  operation: 'UPSERT' | 'REMOVE';
  canonical_value: string | null;
}

export interface SourceCheckpoint {
  source_id: string;
  resource_generation: string;
  next_producer_ordinal: string;
  coverage: SourceCoverage | null;
  cursor: string | null;
}

export interface StudyClockCheckpoint {
  calendar_elapsed_nanos: string;
  active_running_elapsed_nanos: string;
  anchor: ResearchTime;
  deadline_utc_millis: string;
  deadline_utc_trusted: boolean;
  zone_id: string;
}

export interface ConditionEpoch {
  id: string;
  configuration_sha256: string;
  applied_resource_vector_sha256: string;
  activated_at: ResearchTime;
}

export interface RuntimeProjection {
  state: string;
  revision: string;
  next_commit_sequence: string;
  next_observation_sequence: string;
  next_event_sequence: string;
  source_checkpoints: Record<string, SourceCheckpoint>;
  clock_checkpoint: StudyClockCheckpoint | null;
  active_condition_epoch: ConditionEpoch | null;
  lifetime_data_event_count: string;
  uploaded_through_commit: string;
  evaluated_through_commit: string;
  retained_from_commit: string;
}

export interface EngineCommit {
  commit_sequence: string;
  previous_commit_sha256: string;
  input_kind: string;
  consumed_pending_input_sha256: string | null;
  source_observations: SourceObservation[];
  events: ResearchEvent[];
  mutations: RuntimeMutation[];
  committed_at: ResearchTime;
  successor_projection: RuntimeProjection;
  resulting_checkpoint_sha256: string;
  commit_sha256: string;
}

export interface ResearchExperiment {
  experiment_id: string;
  configuration_id: string;
  participant_instance_id: string;
  assigned_participant_id: string | null;
  state: string;
  retained_from_commit: string;
  uploaded_through_commit: string;
  evaluated_through_commit: string;
  durable_through_commit: string;
  next_commit_sequence: string;
  first_commit_sequence: string;
  last_commit_sequence: string;
  commit_count: string;
  event_count: string;
  lifetime_data_event_count: string;
  commits: EngineCommit[];
}

export interface ResearchDocument {
  format: typeof BUNDLE_FORMAT;
  bundle_id: string;
  bundle_kind: 'manual_export' | 'automatic_upload';
  configuration_sha256: string;
  event_source_registry_sha256: string;
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
    'event_source_registry_sha256',
    'experiment',
    'exported_at_utc_millis',
    'format',
    'producer'
  ]);
  if (!root || root.format !== BUNDLE_FORMAT || root.bundle_id !== bundleId) return null;
  if (root.bundle_kind !== 'manual_export' && root.bundle_kind !== 'automatic_upload') return null;
  if (root.configuration_sha256 !== configurationSha256) return null;
  if (root.event_source_registry_sha256 !== EVENT_SOURCE_REGISTRY_SHA256) return null;
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

  const experiment = readExperiment(root.experiment, expectedConfiguration, configurationSha256);
  if (!experiment) return null;
  if (
    root.bundle_kind === 'automatic_upload' &&
    (experiment.commit_count === '0' ||
      BigInt(experiment.first_commit_sequence) !== BigInt(experiment.uploaded_through_commit) + 1n)
  ) return null;
  return {
    format: BUNDLE_FORMAT,
    bundle_id: bundleId,
    bundle_kind: root.bundle_kind,
    configuration_sha256: configurationSha256,
    event_source_registry_sha256: EVENT_SOURCE_REGISTRY_SHA256,
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

function readExperiment(
  raw: unknown,
  configuration: StudyConfiguration,
  configurationSha256: string
): ResearchExperiment | null {
  const source = exact(raw, [
    'assigned_participant_id',
    'commit_count',
    'commits',
    'configuration_id',
    'durable_through_commit',
    'evaluated_through_commit',
    'event_count',
    'experiment_id',
    'first_commit_sequence',
    'last_commit_sequence',
    'lifetime_data_event_count',
    'next_commit_sequence',
    'participant_instance_id',
    'retained_from_commit',
    'state',
    'uploaded_through_commit'
  ]);
  if (!source) return null;
  if (
    source.experiment_id !== configuration.experiment_id ||
    source.configuration_id !== configuration.configuration_id ||
    source.assigned_participant_id !== configuration.assigned_participant_id ||
    typeof source.participant_instance_id !== 'string' ||
    !UUID_V4.test(source.participant_instance_id) ||
    typeof source.state !== 'string' ||
    !EXPERIMENT_STATES.has(source.state)
  ) return null;

  const decimalKeys = [
    'commit_count',
    'durable_through_commit',
    'evaluated_through_commit',
    'event_count',
    'first_commit_sequence',
    'last_commit_sequence',
    'lifetime_data_event_count',
    'next_commit_sequence',
    'retained_from_commit',
    'uploaded_through_commit'
  ] as const;
  if (decimalKeys.some((key) => !decimal(source[key]))) return null;
  if (!Array.isArray(source.commits)) return null;

  const commitCount = BigInt(source.commit_count as string);
  const durable = BigInt(source.durable_through_commit as string);
  const evaluated = BigInt(source.evaluated_through_commit as string);
  const next = BigInt(source.next_commit_sequence as string);
  const retained = BigInt(source.retained_from_commit as string);
  const uploaded = BigInt(source.uploaded_through_commit as string);
  const first = BigInt(source.first_commit_sequence as string);
  const last = BigInt(source.last_commit_sequence as string);
  if (
    commitCount < 0n || commitCount !== BigInt(source.commits.length) ||
    first < 1n || last !== (commitCount === 0n ? first - 1n : first + commitCount - 1n) ||
    next !== durable + 1n || retained < 1n || retained > next ||
    uploaded > durable || evaluated > durable ||
    retained > (uploaded < evaluated ? uploaded : evaluated) + 1n ||
    first < retained || first > next || last > durable
  ) return null;

  const commits: EngineCommit[] = [];
  let previous: EngineCommit | null = null;
  let eventCount = 0n;
  let collectorEventCount = 0n;
  const epoch: EpochReplayState = {
    activeDigest: null,
    activeId: null,
    known: first === 1n,
    seen: new Set()
  };
  for (let index = 0; index < source.commits.length; index += 1) {
    const commit = readCommit(source.commits[index], configuration);
    if (!commit || BigInt(commit.commit_sequence) !== first + BigInt(index)) return null;
    if (index === 0) {
      if (first === 1n && commit.previous_commit_sha256 !== GENESIS_DIGEST) return null;
    } else if (commit.previous_commit_sha256 !== previous?.commit_sha256) return null;
    if (previous && !verifyProjectionContinuity(previous.successor_projection, commit)) return null;
    const collectorEvents = verifyCommitSemantics(
      commit,
      previous?.successor_projection ?? null,
      configurationSha256,
      epoch
    );
    if (collectorEvents === null) {
      return null;
    }
    eventCount += BigInt(commit.events.length);
    collectorEventCount += BigInt(collectorEvents);
    commits.push(commit);
    previous = commit;
  }
  if (eventCount !== BigInt(source.event_count as string)) return null;
  if (BigInt(source.lifetime_data_event_count as string) < collectorEventCount) return null;
  const lastProjection = commits.at(-1)?.successor_projection;
  if (lastProjection && last === durable && (
    lastProjection.state !== source.state ||
    lastProjection.next_commit_sequence !== source.next_commit_sequence ||
    lastProjection.retained_from_commit !== source.retained_from_commit ||
    lastProjection.uploaded_through_commit !== source.uploaded_through_commit ||
    lastProjection.evaluated_through_commit !== source.evaluated_through_commit ||
    lastProjection.lifetime_data_event_count !== source.lifetime_data_event_count
  )) return null;

  return {
    experiment_id: configuration.experiment_id,
    configuration_id: configuration.configuration_id,
    participant_instance_id: source.participant_instance_id as string,
    assigned_participant_id: configuration.assigned_participant_id,
    state: source.state as string,
    retained_from_commit: source.retained_from_commit as string,
    uploaded_through_commit: source.uploaded_through_commit as string,
    evaluated_through_commit: source.evaluated_through_commit as string,
    durable_through_commit: source.durable_through_commit as string,
    next_commit_sequence: source.next_commit_sequence as string,
    first_commit_sequence: source.first_commit_sequence as string,
    last_commit_sequence: source.last_commit_sequence as string,
    commit_count: source.commit_count as string,
    event_count: source.event_count as string,
    lifetime_data_event_count: source.lifetime_data_event_count as string,
    commits
  };
}

interface EpochReplayState {
  activeDigest: string | null;
  activeId: string | null;
  known: boolean;
  seen: Set<string>;
}

function readCommit(
  raw: unknown,
  configuration: StudyConfiguration,
  configurationSha256 = hex(configurationDigest(configuration))
): EngineCommit | null {
  const source = exact(raw, [
    'commit_sequence',
    'commit_sha256',
    'committed_at',
    'consumed_pending_input_sha256',
    'events',
    'input_kind',
    'mutations',
    'previous_commit_sha256',
    'resulting_checkpoint_sha256',
    'source_observations',
    'successor_projection'
  ]);
  if (
    !source || !positiveDecimal(source.commit_sequence) || !digest(source.commit_sha256) ||
    !digest(source.previous_commit_sha256) || !digest(source.resulting_checkpoint_sha256) ||
    (source.consumed_pending_input_sha256 !== null &&
      !digest(source.consumed_pending_input_sha256)) ||
    typeof source.input_kind !== 'string' || !INPUT_KINDS.has(source.input_kind) ||
    !Array.isArray(source.events) || source.events.length > MAXIMUM_EVENTS_PER_COMMIT ||
    !Array.isArray(source.source_observations) ||
    source.source_observations.length > MAXIMUM_OBSERVATION_EVENTS ||
    !Array.isArray(source.mutations) || source.mutations.length > MAXIMUM_MUTATIONS_PER_COMMIT
  ) return null;

  const committedAt = readTime(source.committed_at);
  const events = source.events.map((event) => readEvent(event, configuration));
  const observations = source.source_observations.map((item) => readObservation(item, configuration));
  const mutations = source.mutations.map(readMutation);
  const projection = readProjection(source.successor_projection, configuration, configurationSha256);
  if (
    !committedAt || events.some((event) => event === null) ||
    observations.some((item) => item === null) || mutations.some((item) => item === null) ||
    !projection
  ) return null;
  const commit: EngineCommit = {
    commit_sequence: source.commit_sequence,
    commit_sha256: source.commit_sha256,
    committed_at: committedAt,
    consumed_pending_input_sha256: source.consumed_pending_input_sha256,
    events: events as ResearchEvent[],
    input_kind: source.input_kind,
    mutations: mutations as RuntimeMutation[],
    previous_commit_sha256: source.previous_commit_sha256,
    resulting_checkpoint_sha256: source.resulting_checkpoint_sha256,
    source_observations: observations as SourceObservation[],
    successor_projection: projection
  };
  const sequence = BigInt(commit.commit_sequence);
  if (
    BigInt(projection.revision) !== sequence ||
    BigInt(projection.next_commit_sequence) !== sequence + 1n ||
    projection.evaluated_through_commit !== commit.commit_sequence ||
    !isContiguous(commit.events.map((event) => BigInt(event.sequence_number))) ||
    !isContiguous(commit.source_observations.map((item) => BigInt(item.observation_sequence))) ||
    !isStrictlyMutationOrdered(commit.mutations) ||
    calculateCommitDigest(commit) !== commit.commit_sha256 ||
    !verifyAutomationCheckpoint(commit)
  ) return null;
  return commit;
}

function readObservation(
  raw: unknown,
  configuration: StudyConfiguration
): SourceObservation | null {
  const source = exact(raw, [
    'admission_kind',
    'condition_epoch_id',
    'coverage',
    'encoded_sha256',
    'event_count',
    'first_event_sequence',
    'last_event_sequence',
    'observation_sequence',
    'producer_ordinal',
    'resource_generation',
    'schema_version',
    'source_id'
  ]);
  if (
    !source || (source.admission_kind !== 'NORMAL' && source.admission_kind !== 'BARRIER_FLUSH') ||
    typeof source.condition_epoch_id !== 'string' || !UUID_V4.test(source.condition_epoch_id) ||
    !digest(source.encoded_sha256) || !Number.isSafeInteger(source.event_count) ||
    (source.event_count as number) < 0 || (source.event_count as number) > MAXIMUM_OBSERVATION_EVENTS ||
    !positiveDecimal(source.observation_sequence) || !decimal(source.producer_ordinal) ||
    !positiveDecimal(source.resource_generation) || !Number.isSafeInteger(source.schema_version) ||
    (source.schema_version as number) < 1 || typeof source.source_id !== 'string' ||
    !SOURCE_ID.test(source.source_id)
  ) return null;
  const coverage = source.coverage === null ? null : readCoverage(source.coverage);
  if (source.coverage !== null && !coverage) return null;
  const first = nullableDecimal(source.first_event_sequence);
  const last = nullableDecimal(source.last_event_sequence);
  if (first === undefined || last === undefined) return null;
  const eventCount = source.event_count as number;
  if (
    (eventCount === 0 && (first !== null || last !== null || coverage === null)) ||
    (eventCount > 0 && (
      first === null || last === null || first < 1n || last < first ||
      last - first + 1n !== BigInt(eventCount)
    ))
  ) return null;

  const contract = EVENT_CONTRACTS.get(source.source_id);
  if (
    !contract || contract.source_kind !== 'COLLECTOR' ||
    contract.schema_version !== source.schema_version ||
    !configuration.collectors.some((collector) => collector.id === source.source_id)
  ) return null;
  const retrospective = contract.events.every((event) => event.delivery.kind === 'POLL');
  if (retrospective !== (coverage !== null)) return null;
  return {
    admission_kind: source.admission_kind,
    condition_epoch_id: source.condition_epoch_id,
    coverage,
    encoded_sha256: source.encoded_sha256,
    event_count: eventCount,
    first_event_sequence: first === null ? null : first.toString(),
    last_event_sequence: last === null ? null : last.toString(),
    observation_sequence: source.observation_sequence,
    producer_ordinal: source.producer_ordinal,
    resource_generation: source.resource_generation,
    schema_version: source.schema_version,
    source_id: source.source_id
  };
}

function readMutation(raw: unknown): RuntimeMutation | null {
  const source = exact(raw, ['canonical_value', 'component_id', 'component_kind', 'operation']);
  if (
    !source || typeof source.component_kind !== 'string' ||
    !isRuntimeComponentKind(source.component_kind) ||
    typeof source.component_id !== 'string' || !COMPONENT_ID.test(source.component_id) ||
    (source.operation !== 'UPSERT' && source.operation !== 'REMOVE') ||
    (source.canonical_value !== null && typeof source.canonical_value !== 'string') ||
    (typeof source.canonical_value === 'string' &&
      UTF8.encode(source.canonical_value).length > MAXIMUM_COMPONENT_BYTES) ||
    (source.operation === 'UPSERT' &&
      (typeof source.canonical_value !== 'string' || source.canonical_value.length === 0)) ||
    (source.operation === 'REMOVE' && source.canonical_value !== null)
  ) return null;
  return {
    canonical_value: source.canonical_value,
    component_id: source.component_id,
    component_kind: source.component_kind,
    operation: source.operation
  };
}

function readProjection(
  raw: unknown,
  configuration: StudyConfiguration,
  configurationSha256: string
): RuntimeProjection | null {
  const source = exact(raw, [
    'active_condition_epoch',
    'clock_checkpoint',
    'evaluated_through_commit',
    'lifetime_data_event_count',
    'next_commit_sequence',
    'next_event_sequence',
    'next_observation_sequence',
    'retained_from_commit',
    'revision',
    'source_checkpoints',
    'state',
    'uploaded_through_commit'
  ]);
  const decimalKeys = [
    'evaluated_through_commit', 'lifetime_data_event_count', 'next_commit_sequence',
    'next_event_sequence', 'next_observation_sequence', 'retained_from_commit', 'revision',
    'uploaded_through_commit'
  ] as const;
  if (
    !source || decimalKeys.some((key) => !decimal(source[key])) ||
    typeof source.state !== 'string' || !EXPERIMENT_STATES.has(source.state)
  ) return null;
  const revision = BigInt(source.revision as string);
  const nextCommit = BigInt(source.next_commit_sequence as string);
  const nextObservation = BigInt(source.next_observation_sequence as string);
  const nextEvent = BigInt(source.next_event_sequence as string);
  const uploaded = BigInt(source.uploaded_through_commit as string);
  const evaluated = BigInt(source.evaluated_through_commit as string);
  const retained = BigInt(source.retained_from_commit as string);
  if (
    nextCommit !== revision + 1n || nextObservation < 1n || nextEvent < 1n ||
    uploaded > revision || evaluated > revision || retained < 1n || retained > nextCommit ||
    retained > (uploaded < evaluated ? uploaded : evaluated) + 1n
  ) return null;

  const rawCheckpoints = record(source.source_checkpoints);
  if (!rawCheckpoints || Object.keys(rawCheckpoints).length > MAXIMUM_SOURCE_CHECKPOINTS) return null;
  const sourceCheckpoints: Record<string, SourceCheckpoint> = {};
  for (const [sourceId, value] of Object.entries(rawCheckpoints)) {
    const checkpoint = readSourceCheckpoint(value, sourceId, configuration);
    if (!checkpoint) return null;
    sourceCheckpoints[sourceId] = checkpoint;
  }
  const clock = source.clock_checkpoint === null ? null : readClock(source.clock_checkpoint);
  if (source.clock_checkpoint !== null && !clock) return null;
  const epoch = source.active_condition_epoch === null
    ? null
    : readEpoch(source.active_condition_epoch, configurationSha256);
  if (source.active_condition_epoch !== null && !epoch) return null;
  return {
    active_condition_epoch: epoch,
    clock_checkpoint: clock,
    evaluated_through_commit: source.evaluated_through_commit as string,
    lifetime_data_event_count: source.lifetime_data_event_count as string,
    next_commit_sequence: source.next_commit_sequence as string,
    next_event_sequence: source.next_event_sequence as string,
    next_observation_sequence: source.next_observation_sequence as string,
    retained_from_commit: source.retained_from_commit as string,
    revision: source.revision as string,
    source_checkpoints: sourceCheckpoints,
    state: source.state as string,
    uploaded_through_commit: source.uploaded_through_commit as string
  };
}

function readSourceCheckpoint(
  raw: unknown,
  key: string,
  configuration: StudyConfiguration
): SourceCheckpoint | null {
  const source = exact(raw, [
    'coverage', 'cursor', 'next_producer_ordinal', 'resource_generation', 'source_id'
  ]);
  if (
    !source || source.source_id !== key || !SOURCE_ID.test(key) ||
    !decimal(source.resource_generation) || !decimal(source.next_producer_ordinal) ||
    (source.cursor !== null &&
      (typeof source.cursor !== 'string' || source.cursor.length > 4_096))
  ) return null;
  const contract = EVENT_CONTRACTS.get(key);
  if (
    !contract || contract.source_kind !== 'COLLECTOR' ||
    !configuration.collectors.some((collector) => collector.id === key)
  ) return null;
  const coverage = source.coverage === null ? null : readCoverage(source.coverage);
  if (source.coverage !== null && !coverage) return null;
  return {
    source_id: key,
    resource_generation: source.resource_generation,
    next_producer_ordinal: source.next_producer_ordinal,
    coverage,
    cursor: source.cursor
  };
}

function readCoverage(raw: unknown): SourceCoverage | null {
  const source = exact(raw, ['clock_basis', 'end_exclusive', 'start_inclusive']);
  if (
    !source ||
    source.clock_basis !== 'OBSERVED_RESEARCH_TIME' &&
    source.clock_basis !== 'SOURCE_WALL_TIME' &&
    source.clock_basis !== 'SOURCE_MONOTONIC_TIME'
  ) return null;
  if (
    typeof source.start_inclusive !== 'string' || source.start_inclusive.trim().length === 0 ||
    source.start_inclusive.length > 160 || typeof source.end_exclusive !== 'string' ||
    source.end_exclusive.trim().length === 0 || source.end_exclusive.length > 160
  ) return null;
  return {
    clock_basis: source.clock_basis,
    start_inclusive: source.start_inclusive,
    end_exclusive: source.end_exclusive
  };
}

function readClock(raw: unknown): StudyClockCheckpoint | null {
  const source = exact(raw, [
    'active_running_elapsed_nanos', 'anchor', 'calendar_elapsed_nanos',
    'deadline_utc_millis', 'deadline_utc_trusted', 'zone_id'
  ]);
  if (
    !source || !decimal(source.active_running_elapsed_nanos) ||
    !decimal(source.calendar_elapsed_nanos) || !decimal(source.deadline_utc_millis) ||
    typeof source.deadline_utc_trusted !== 'boolean' ||
    typeof source.zone_id !== 'string' || !canonicalIanaZone(source.zone_id)
  ) return null;
  const anchor = readTime(source.anchor);
  if (
    !anchor || BigInt(source.active_running_elapsed_nanos) >
      BigInt(source.calendar_elapsed_nanos)
  ) return null;
  return {
    active_running_elapsed_nanos: source.active_running_elapsed_nanos,
    anchor,
    calendar_elapsed_nanos: source.calendar_elapsed_nanos,
    deadline_utc_millis: source.deadline_utc_millis,
    deadline_utc_trusted: source.deadline_utc_trusted,
    zone_id: source.zone_id
  };
}

function readEpoch(raw: unknown, configurationSha256: string): ConditionEpoch | null {
  const source = exact(raw, [
    'activated_at', 'applied_resource_vector_sha256', 'configuration_sha256', 'id'
  ]);
  const activatedAt = source && readTime(source.activated_at);
  if (
    !source || !activatedAt || typeof source.id !== 'string' || !UUID_V4.test(source.id) ||
    source.configuration_sha256 !== configurationSha256 ||
    !digest(source.applied_resource_vector_sha256)
  ) return null;
  return {
    activated_at: activatedAt,
    applied_resource_vector_sha256: source.applied_resource_vector_sha256,
    configuration_sha256: configurationSha256,
    id: source.id
  };
}

function readEvent(raw: unknown, configuration: StudyConfiguration): ResearchEvent | null {
  const source = exact(raw, [
    'condition_epoch_id',
    'event_type',
    'fields',
    'observed_time',
    'schema_version',
    'sequence_number',
    'source_id',
  ]);
  const fields = source && record(source.fields);
  const time = source && readTime(source.observed_time);
  if (
    !source || !fields || !time || !positiveDecimal(source.sequence_number) ||
    !nonempty(source.source_id) || !SOURCE_ID.test(source.source_id) ||
    !nonempty(source.event_type) || !EVENT_TYPE.test(source.event_type) ||
    !Number.isSafeInteger(source.schema_version) ||
    (source.schema_version as number) < 1 ||
    Object.keys(fields).length > 32 || Object.keys(fields).some((key) => !FIELD_KEY.test(key)) ||
    Object.values(fields).some((value) =>
      typeof value !== 'string' || value.length > 60 * 1_024
    ) ||
    (source.condition_epoch_id !== null &&
      (typeof source.condition_epoch_id !== 'string' || !UUID_V4.test(source.condition_epoch_id)))
  ) return null;
  const event = {
    sequence_number: source.sequence_number as string,
    source_id: source.source_id as string,
    schema_version: source.schema_version as number,
    observed_time: time,
    event_type: source.event_type as string,
    condition_epoch_id: source.condition_epoch_id as string | null,
    fields: fields as Record<string, string>
  };
  return acceptsEvent(event, source, configuration) ? event : null;
}

function readTime(raw: unknown): ResearchTime | null {
  const source = exact(raw, ['boot_session_id', 'elapsed_realtime_nanos', 'wall_time_utc_millis']);
  if (
    !source || !nonempty(source.boot_session_id) ||
    !/^[A-Za-z0-9._:-]{1,128}$/.test(source.boot_session_id) ||
    UTF8.encode(source.boot_session_id as string).length > 128 ||
    !decimal(source.elapsed_realtime_nanos) ||
    !decimal(source.wall_time_utc_millis)
  ) return null;
  return {
    boot_session_id: source.boot_session_id as string,
    elapsed_realtime_nanos: source.elapsed_realtime_nanos as string,
    wall_time_utc_millis: source.wall_time_utc_millis as string
  };
}

function acceptsEvent(
  event: ResearchEvent,
  raw: Record<string, unknown>,
  configuration: StudyConfiguration
): boolean {
  const source = EVENT_CONTRACTS.get(event.source_id);
  if (!source || event.schema_version !== source.schema_version) {
    return false;
  }
  const configured = source.source_kind === 'SYSTEM' ||
    configuration.collectors.some((collector) => collector.id === event.source_id);
  if (!configured) return false;
  const contract = source.events.find((candidate) => candidate.event_type === event.event_type);
  if (!contract || !contract.privacy.exported) return false;
  const names = Object.keys(event.fields);
  if (
    names.some((name) => !Object.hasOwn(contract.fields, name)) ||
    Object.entries(contract.fields).some(([name, field]) =>
      field.required ? !Object.hasOwn(event.fields, name) : false
    ) ||
    Object.entries(event.fields).some(([name, value]) => !acceptsField(value, contract.fields[name]))
  ) return false;
  return canonicalBytes(raw).length <= contract.maximum_encoded_event_bytes;
}

function acceptsField(value: string | null, field: RegistryFieldContract): boolean {
  if (value === null) return field.nullable;
  const length = field.length_unit === 'UTF8_BYTES' ? UTF8.encode(value).length : value.length;
  if (
    (field.minimum_length != null && length < field.minimum_length) ||
    (field.maximum_length != null && length > field.maximum_length)
  ) return false;
  let numeric: number;
  let integer: bigint;
  switch (field.wire_type) {
    case 'boolean':
      return value === 'true' || value === 'false';
    case 'enum':
      return field.enum_values.includes(value);
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
    case 'int64_decimal':
      if (!CANONICAL_SIGNED_INTEGER.test(value)) return false;
      integer = BigInt(value);
      return integer >= -(1n << 63n) && integer <= (1n << 63n) - 1n && integerInRange(integer, field);
    case 'uint64_decimal':
      if (!CANONICAL_SIGNED_INTEGER.test(value)) return false;
      integer = BigInt(value);
      return integer >= 0n && integer <= (1n << 64n) - 1n && integerInRange(integer, field);
    case 'json_string':
      try {
        parseStrictEmbeddedJson(value);
        return true;
      } catch {
        return false;
      }
    case 'sha256_hex':
      return SHA256_HEX.test(value);
    case 'string':
      return true;
    case 'uuid':
      return UUID_RFC4122.test(value);
  }
}

const inRange = (value: number, field: RegistryFieldContract) =>
  (field.minimum == null || value >= field.minimum) &&
  (field.maximum == null || value <= field.maximum);

const integerInRange = (value: bigint, field: RegistryFieldContract) =>
  (field.minimum == null || value >= BigInt(field.minimum)) &&
  (field.maximum == null || value <= BigInt(field.maximum));

function verifyProjectionContinuity(previous: RuntimeProjection, commit: EngineCommit): boolean {
  const firstEvent = commit.events[0];
  const firstObservation = commit.source_observations[0];
  return previous.next_commit_sequence === commit.commit_sequence &&
    (!firstEvent || firstEvent.sequence_number === previous.next_event_sequence) &&
    (!firstObservation || firstObservation.observation_sequence === previous.next_observation_sequence) &&
    BigInt(commit.successor_projection.lifetime_data_event_count) >=
      BigInt(previous.lifetime_data_event_count) &&
    BigInt(commit.successor_projection.uploaded_through_commit) >=
      BigInt(previous.uploaded_through_commit) &&
    BigInt(commit.successor_projection.retained_from_commit) >=
      BigInt(previous.retained_from_commit);
}

type SourceObservationOrderEntry = Pick<
  SourceObservation,
  'admission_kind' | 'event_count' | 'first_event_sequence' | 'last_event_sequence'
>;

/** Protocol conformance hook for the sole allowed manifest/event-order divergence. */
export function verifySourceObservationEventOrder(
  observations: readonly SourceObservationOrderEntry[],
  consumedPendingInputSha256: string | null
): boolean {
  const eventful = observations.filter((observation) => observation.event_count > 0);
  if (eventful.length <= 1) return true;
  const semanticOrder = [...eventful].sort((left, right) => {
    const leftSequence = BigInt(left.first_event_sequence!);
    const rightSequence = BigInt(right.first_event_sequence!);
    return leftSequence < rightSequence ? -1 : leftSequence > rightSequence ? 1 : 0;
  });
  for (let index = 1; index < semanticOrder.length; index += 1) {
    if (
      BigInt(semanticOrder[index - 1].last_event_sequence!) + 1n !==
      BigInt(semanticOrder[index].first_event_sequence!)
    ) return false;
  }
  if (semanticOrder.every((observation, index) => observation === eventful[index])) return true;
  if (consumedPendingInputSha256 === null) return false;
  const causal = observations[0];
  if (!causal || causal.admission_kind !== 'NORMAL' || causal.event_count === 0) return false;
  let flushStarted = false;
  for (const observation of observations.slice(1)) {
    if (observation.admission_kind === 'NORMAL') {
      if (flushStarted) return false;
    } else {
      flushStarted = true;
    }
  }
  const expectedSemanticOrder = observations
    .slice(1)
    .filter((observation) => observation.event_count > 0)
    .concat(causal);
  return semanticOrder.every(
    (observation, index) => observation === expectedSemanticOrder[index]
  );
}

function verifyCommitSemantics(
  commit: EngineCommit,
  previous: RuntimeProjection | null,
  configurationSha256: string,
  epoch: EpochReplayState
): number | null {
  const events = commit.events;
  const observations = commit.source_observations;
  if (events.length > 0) {
    if (
      BigInt(commit.successor_projection.next_event_sequence) !==
        BigInt(events.at(-1)!.sequence_number) + 1n ||
      (commit.commit_sequence === '1' && events[0].sequence_number !== '1')
    ) return null;
  } else if (
    previous && commit.successor_projection.next_event_sequence !== previous.next_event_sequence
  ) return null;
  if (observations.length > 0) {
    if (
      BigInt(commit.successor_projection.next_observation_sequence) !==
        BigInt(observations.at(-1)!.observation_sequence) + 1n ||
      (commit.commit_sequence === '1' && observations[0].observation_sequence !== '1')
    ) return null;
  } else if (
    previous &&
    commit.successor_projection.next_observation_sequence !== previous.next_observation_sequence
  ) return null;

  const bySequence = new Map(events.map((event) => [event.sequence_number, event]));
  if (bySequence.size !== events.length) return null;
  const covered = new Set<string>();
  for (const observation of observations) {
    const observationEvents: ResearchEvent[] = [];
    if (observation.event_count > 0) {
      let sequence = BigInt(observation.first_event_sequence!);
      const last = BigInt(observation.last_event_sequence!);
      while (sequence <= last) {
        const key = sequence.toString();
        const event = bySequence.get(key);
        if (
          !event || event.source_id !== observation.source_id ||
          event.schema_version !== observation.schema_version ||
          event.condition_epoch_id !== observation.condition_epoch_id || covered.has(key)
        ) return null;
        covered.add(key);
        observationEvents.push(event);
        sequence += 1n;
      }
    }
    if (calculateObservationDigest(observation, observationEvents) !== observation.encoded_sha256) {
      return null;
    }
    if (epoch.known && observation.condition_epoch_id !== epoch.activeId) return null;
    if (!epoch.known) {
      epoch.activeId = observation.condition_epoch_id;
      epoch.known = true;
    }
  }
  const collectorEvents = events.filter(
    (event) => EVENT_CONTRACTS.get(event.source_id)?.source_kind === 'COLLECTOR'
  );
  if (
    collectorEvents.some((event) => !covered.has(event.sequence_number)) ||
    covered.size !== collectorEvents.length
  ) return null;
  if (!verifySourceObservationEventOrder(observations, commit.consumed_pending_input_sha256)) {
    return null;
  }

  for (const event of events) {
    const signedDigest = event.fields.signed_configuration_sha256;
    if (signedDigest !== undefined && signedDigest !== configurationSha256) return null;
    if (EVENT_CONTRACTS.get(event.source_id)?.source_kind === 'COLLECTOR') {
      if (!event.condition_epoch_id) return null;
      if (epoch.known && epoch.activeId !== event.condition_epoch_id) return null;
      if (!epoch.known) {
        epoch.activeId = event.condition_epoch_id;
        epoch.known = true;
      }
    }
    if (event.source_id !== 'study_condition.v1') continue;
    const id = event.fields.condition_epoch_id;
    const appliedDigest = event.fields.applied_resource_vector_sha256;
    const vector = event.fields.resource_vector_json;
    if (
      !id || !UUID_V4.test(id) || !appliedDigest || !SHA256_HEX.test(appliedDigest) ||
      !vector || !isCanonicalEmbeddedJson(vector) || hex(sha256(UTF8.encode(vector))) !== appliedDigest
    ) return null;
    if (event.event_type === 'CONDITION_EPOCH_ACTIVATED') {
      if (
        event.condition_epoch_id !== id || (epoch.known && epoch.activeId !== null) ||
        epoch.seen.has(id)
      ) return null;
      epoch.activeId = id;
      epoch.activeDigest = appliedDigest;
      epoch.known = true;
      epoch.seen.add(id);
    } else if (event.event_type === 'CONDITION_EPOCH_DEACTIVATED') {
      if (
        event.condition_epoch_id !== id ||
        (epoch.known && (epoch.activeId !== id || epoch.activeDigest !== appliedDigest))
      ) return null;
      epoch.activeId = null;
      epoch.activeDigest = null;
      epoch.known = true;
      epoch.seen.add(id);
    }
  }

  const successor = commit.successor_projection.active_condition_epoch;
  if (
    epoch.known &&
    (successor?.id ?? null) !== epoch.activeId
  ) return null;
  if (
    successor && epoch.activeDigest !== null &&
    successor.applied_resource_vector_sha256 !== epoch.activeDigest
  ) return null;
  if (successor) {
    epoch.seen.add(successor.id);
    epoch.activeId = successor.id;
    epoch.activeDigest = successor.applied_resource_vector_sha256;
  } else {
    epoch.activeId = null;
    epoch.activeDigest = null;
  }
  epoch.known = true;

  if (
    previous && BigInt(commit.successor_projection.lifetime_data_event_count) !==
      BigInt(previous.lifetime_data_event_count) + BigInt(collectorEvents.length)
  ) return null;
  return verifySourceCheckpoints(commit, previous) ? collectorEvents.length : null;
}

function verifySourceCheckpoints(
  commit: EngineCommit,
  previous: RuntimeProjection | null
): boolean {
  const expected = new Map<string, SourceCheckpoint>(
    Object.entries(previous?.source_checkpoints ?? {}).map(([key, value]) => [key, { ...value }])
  );
  const historyKnown = previous !== null || commit.commit_sequence === '1';
  const unanchored = new Set<string>();
  for (const observation of commit.source_observations) {
    const prior = expected.get(observation.source_id);
    const unknownPredecessor = !historyKnown && !prior && !unanchored.has(observation.source_id);
    unanchored.add(observation.source_id);
    if (!unknownPredecessor) {
      const expectedOrdinal = !prior || prior.resource_generation !== observation.resource_generation
        ? 0n
        : BigInt(prior.next_producer_ordinal);
      if (BigInt(observation.producer_ordinal) !== expectedOrdinal) return false;
    }
    if (
      prior && prior.resource_generation === observation.resource_generation &&
      prior.coverage && observation.coverage &&
      (prior.coverage.clock_basis !== observation.coverage.clock_basis ||
        prior.coverage.end_exclusive !== observation.coverage.start_inclusive)
    ) return false;
    expected.set(observation.source_id, {
      source_id: observation.source_id,
      resource_generation: observation.resource_generation,
      next_producer_ordinal: (BigInt(observation.producer_ordinal) + 1n).toString(),
      coverage: observation.coverage ?? prior?.coverage ?? null,
      cursor: prior?.cursor ?? null
    });
  }
  const actual = commit.successor_projection.source_checkpoints;
  if (historyKnown) return sameCheckpointMap(actual, expected);
  for (const [sourceId, observations] of groupObservations(commit.source_observations)) {
    const last = observations.at(-1)!;
    const checkpoint = actual[sourceId];
    if (
      !checkpoint || checkpoint.resource_generation !== last.resource_generation ||
      BigInt(checkpoint.next_producer_ordinal) !== BigInt(last.producer_ordinal) + 1n
    ) return false;
  }
  return true;
}

function calculateObservationDigest(
  observation: SourceObservation,
  events: ResearchEvent[]
): string {
  if (events.length !== observation.event_count) return '';
  const writer = new CanonicalBinaryWriter();
  writer.string('particeps-source-observation-v1');
  writer.string(observation.source_id);
  writer.int(observation.schema_version);
  writer.long(observation.resource_generation);
  writer.long(observation.producer_ordinal);
  writer.string(observation.condition_epoch_id);
  writer.boolean(observation.coverage !== null);
  if (observation.coverage) writeCoverage(writer, observation.coverage);
  writer.int(events.length);
  for (const event of events) {
    writer.string(event.event_type);
    writer.long(event.observed_time.wall_time_utc_millis);
    writer.long(event.observed_time.elapsed_realtime_nanos);
    writer.string(event.observed_time.boot_session_id);
    writeFields(writer, event.fields);
  }
  return hex(sha256(writer.bytes()));
}

function calculateCommitDigest(commit: EngineCommit): string {
  const writer = new CanonicalBinaryWriter();
  writer.string('particeps-engine-commit-v1');
  writer.long(commit.commit_sequence);
  writer.string(commit.previous_commit_sha256);
  writer.string(commit.input_kind);
  writer.nullable(commit.consumed_pending_input_sha256, (value) => writer.string(value));
  writer.list(commit.source_observations, (item) => writeObservation(writer, item));
  writer.list(commit.events, (event) => writeEvent(writer, event));
  writer.list(commit.mutations, (mutation) => writeMutation(writer, mutation));
  writeTime(writer, commit.committed_at);
  writeProjection(writer, commit.successor_projection);
  writer.string(commit.resulting_checkpoint_sha256);
  return hex(sha256(writer.bytes()));
}

function writeObservation(writer: CanonicalBinaryWriter, value: SourceObservation): void {
  writer.long(value.observation_sequence);
  writer.string(value.source_id);
  writer.int(value.schema_version);
  writer.long(value.resource_generation);
  writer.string(value.admission_kind);
  writer.long(value.producer_ordinal);
  writer.string(value.condition_epoch_id);
  writer.int(value.event_count);
  writer.nullable(value.first_event_sequence, (item) => writer.long(item));
  writer.nullable(value.last_event_sequence, (item) => writer.long(item));
  writer.nullable(value.coverage, (item) => writeCoverage(writer, item));
  writer.string(value.encoded_sha256);
}

function writeEvent(writer: CanonicalBinaryWriter, value: ResearchEvent): void {
  writer.long(value.sequence_number);
  writer.string(value.source_id);
  writer.int(value.schema_version);
  writer.string(value.event_type);
  writeTime(writer, value.observed_time);
  writer.nullable(value.condition_epoch_id, (item) => writer.string(item));
  writeFields(writer, value.fields);
}

function writeMutation(writer: CanonicalBinaryWriter, value: RuntimeMutation): void {
  writer.string(value.component_kind);
  writer.string(value.component_id);
  writer.string(value.operation);
  writer.nullable(value.canonical_value, (item) => writer.string(item));
}

function writeProjection(writer: CanonicalBinaryWriter, value: RuntimeProjection): void {
  writer.string(value.state);
  writer.long(value.revision);
  writer.long(value.next_commit_sequence);
  writer.long(value.next_observation_sequence);
  writer.long(value.next_event_sequence);
  const checkpoints = Object.entries(value.source_checkpoints).sort(([left], [right]) =>
    left < right ? -1 : left > right ? 1 : 0
  );
  writer.int(checkpoints.length);
  for (const [key, checkpoint] of checkpoints) {
    writer.string(key);
    writer.string(checkpoint.source_id);
    writer.long(checkpoint.resource_generation);
    writer.long(checkpoint.next_producer_ordinal);
    writer.nullable(checkpoint.coverage, (item) => writeCoverage(writer, item));
    writer.nullable(checkpoint.cursor, (item) => writer.string(item));
  }
  writer.nullable(value.clock_checkpoint, (clock) => {
    writer.long(clock.calendar_elapsed_nanos);
    writer.long(clock.active_running_elapsed_nanos);
    writeTime(writer, clock.anchor);
    writer.long(clock.deadline_utc_millis);
    writer.boolean(clock.deadline_utc_trusted);
    writer.string(clock.zone_id);
  });
  writer.nullable(value.active_condition_epoch, (epoch) => {
    writer.string(epoch.id);
    writer.string(epoch.configuration_sha256);
    writer.string(epoch.applied_resource_vector_sha256);
    writeTime(writer, epoch.activated_at);
  });
  writer.long(value.lifetime_data_event_count);
  writer.long(value.uploaded_through_commit);
  writer.long(value.evaluated_through_commit);
  writer.long(value.retained_from_commit);
}

function writeCoverage(writer: CanonicalBinaryWriter, value: SourceCoverage): void {
  writer.string(value.clock_basis);
  writer.string(value.start_inclusive);
  writer.string(value.end_exclusive);
}

function writeTime(writer: CanonicalBinaryWriter, value: ResearchTime): void {
  writer.long(value.wall_time_utc_millis);
  writer.long(value.elapsed_realtime_nanos);
  writer.string(value.boot_session_id);
}

function writeFields(writer: CanonicalBinaryWriter, fields: Record<string, string>): void {
  const entries = Object.entries(fields).sort(([left], [right]) =>
    left < right ? -1 : left > right ? 1 : 0
  );
  writer.int(entries.length);
  for (const [key, value] of entries) {
    writer.string(key);
    writer.string(value);
  }
}

class CanonicalBinaryWriter {
  private readonly chunks: Uint8Array[] = [];

  int(value: number): void {
    if (!Number.isInteger(value) || value < -2_147_483_648 || value > 2_147_483_647) {
      throw new Error('binary_int32');
    }
    const bytes = new Uint8Array(4);
    new DataView(bytes.buffer).setInt32(0, value);
    this.chunks.push(bytes);
  }

  long(value: string | bigint): void {
    const integer = typeof value === 'bigint' ? value : BigInt(value);
    if (integer < -(1n << 63n) || integer > MAXIMUM_INT64) throw new Error('binary_int64');
    const bytes = new Uint8Array(8);
    new DataView(bytes.buffer).setBigInt64(0, integer);
    this.chunks.push(bytes);
  }

  boolean(value: boolean): void {
    this.chunks.push(Uint8Array.of(value ? 1 : 0));
  }

  string(value: string): void {
    const bytes = UTF8.encode(value);
    if (bytes.length > 8 * 1024 * 1024) throw new Error('binary_string');
    this.int(bytes.length);
    this.chunks.push(bytes);
  }

  nullable<T>(value: T | null, encode: (item: T) => void): void {
    this.boolean(value !== null);
    if (value !== null) encode(value);
  }

  list<T>(values: readonly T[], encode: (item: T) => void): void {
    this.int(values.length);
    for (const value of values) encode(value);
  }

  bytes(): Uint8Array {
    return concat(...this.chunks);
  }
}

function sameCheckpointMap(
  actual: Record<string, SourceCheckpoint>,
  expected: Map<string, SourceCheckpoint>
): boolean {
  const entries = Object.entries(actual);
  if (entries.length !== expected.size) return false;
  return entries.every(([key, value]) => {
    const wanted = expected.get(key);
    return wanted !== undefined && sameSourceCheckpoint(value, wanted);
  });
}

function sameSourceCheckpoint(left: SourceCheckpoint, right: SourceCheckpoint): boolean {
  return left.source_id === right.source_id &&
    left.resource_generation === right.resource_generation &&
    left.next_producer_ordinal === right.next_producer_ordinal && left.cursor === right.cursor &&
    sameCoverage(left.coverage, right.coverage);
}

function sameCoverage(left: SourceCoverage | null, right: SourceCoverage | null): boolean {
  return left === null ? right === null : right !== null &&
    left.clock_basis === right.clock_basis && left.start_inclusive === right.start_inclusive &&
    left.end_exclusive === right.end_exclusive;
}

function groupObservations(values: SourceObservation[]): Map<string, SourceObservation[]> {
  const groups = new Map<string, SourceObservation[]>();
  for (const value of values) {
    const group = groups.get(value.source_id) ?? [];
    group.push(value);
    groups.set(value.source_id, group);
  }
  return groups;
}

function isContiguous(values: bigint[]): boolean {
  return values.every((value, index) => index === 0 || value === values[index - 1] + 1n);
}

function isStrictlyMutationOrdered(values: RuntimeMutation[]): boolean {
  let previous: RuntimeMutation | null = null;
  const seen = new Set<string>();
  for (const value of values) {
    const identity = `${value.component_kind}\u0000${value.component_id}`;
    if (seen.has(identity)) return false;
    seen.add(identity);
    if (previous && compareMutation(previous, value) >= 0) return false;
    previous = value;
  }
  return true;
}

function compareMutation(left: RuntimeMutation, right: RuntimeMutation): number {
  const leftKind = COMPONENT_KIND_ORDER.get(left.component_kind)!;
  const rightKind = COMPONENT_KIND_ORDER.get(right.component_kind)!;
  if (leftKind !== rightKind) return leftKind - rightKind;
  return left.component_id < right.component_id ? -1 : left.component_id > right.component_id ? 1 : 0;
}

function isCanonicalEmbeddedJson(value: string): boolean {
  try {
    return canonicalize(JSON.parse(value)) === value;
  } catch {
    return false;
  }
}

interface DecodedTimer {
  id: string;
  automationId: string;
  generation: bigint;
  causalSequence: bigint;
  producerKey: string;
  target: { kind: 'calendar'; value: bigint } | { kind: 'active'; value: bigint } |
    { kind: 'monotonic'; bootSessionId: string; value: bigint };
  logicalDeadline: bigint | null;
  expiresAt: bigint | null;
}

function verifyAutomationCheckpoint(commit: EngineCommit): boolean {
  try {
    const mutations = commit.mutations.filter(
      (mutation) => mutation.component_kind === 'AUTOMATION_CHECKPOINT'
    );
    if (
      mutations.length === 0 ||
      mutations.some((mutation) => !CHECKPOINT_COMPONENT_ID.test(mutation.component_id))
    ) return false;
    const parts = mutations.filter((mutation) => mutation.operation === 'UPSERT');
    if (parts.length === 0) return false;
    for (let index = 0; index < parts.length; index += 1) {
      const expected = index === 0 ? 'main' : `main/${index.toString().padStart(4, '0')}`;
      if (parts[index].component_id !== expected) return false;
    }
    const encoded = parts.map((part) => part.canonical_value!).join('');
    const checkpoint = decodeAutomationCheckpoint(encoded);
    return checkpoint.evaluated === BigInt(commit.commit_sequence) &&
      deterministicDigest('particeps-automation-checkpoint-v1', checkpoint.components) ===
        commit.resulting_checkpoint_sha256;
  } catch {
    return false;
  }
}

function decodeAutomationCheckpoint(encoded: string): {
  evaluated: bigint;
  components: string[];
} {
  if (!encoded.startsWith(CHECKPOINT_PREFIX)) throw new Error('checkpoint_prefix');
  const payload = decodeBase64Url(encoded.slice(CHECKPOINT_PREFIX.length));
  if (payload.length > MAXIMUM_COMPONENT_BYTES) throw new Error('checkpoint_size');
  const reader = new CheckpointBinaryReader(payload);
  if (reader.int() !== 1) throw new Error('checkpoint_version');
  const evaluated = reader.long();
  const lifecycle = reader.string();
  const start = reader.nullableLong();
  const active = reader.long();
  const calendar = reader.long();
  const lifecycles = new Set(['READY', 'ACTIVATING', 'RUNNING', 'PAUSING', 'PAUSED', 'COMPLETED', 'WITHDRAWN']);
  if (
    evaluated < 0n || !lifecycles.has(lifecycle) ||
    (start !== null && start < 0n) || (lifecycle !== 'READY' && start === null) ||
    active < 0n || calendar < active
  ) throw new Error('checkpoint_header');
  const components = [
    `evaluated=${evaluated}`,
    `lifecycle=${lifecycle}`,
    `start=${start ?? ''}`,
    `active=${active}`,
    `calendar=${calendar}`
  ];

  readSortedMap(reader, () => reader.string(), (key) => {
    stateKey(key);
    components.push(`latch:${escapeComponent(key)}=${reader.boolean()}`);
  });
  readSortedMap(reader, () => reader.string(), (key) => {
    stateKey(key);
    const values = reader.list(() => reader.string());
    if (values.length > 256 || !strictlySorted(values)) throw new Error('checkpoint_presence');
    for (const value of values) components.push(
      `presence:${escapeComponent(key)}:${escapeComponent(value)}`
    );
  });
  readSortedMap(reader, () => reader.string(), (key) => {
    stateKey(key);
    const value = reader.long();
    if (value < 0n) throw new Error('checkpoint_held');
    components.push(`held:${escapeComponent(key)}=${value}`);
  });
  readSortedMap(reader, () => reader.string(), (key) => {
    stateKey(key);
    components.push(`prior:${escapeComponent(key)}=${reader.boolean()}`);
  });
  readSortedMap(reader, () => reader.string(), (key) => {
    stateKey(key);
    const entries = reader.list(() => ({
      sequence: reader.long(),
      time: reader.long(),
      boot: reader.string(),
      numeric: reader.string()
    }));
    let prior = 0n;
    for (const entry of entries) {
      if (
        entry.sequence <= prior || entry.time < 0n || entry.boot.trim().length === 0 ||
        !CANONICAL_SIGNED_INTEGER.test(entry.numeric)
      ) throw new Error('checkpoint_window');
      prior = entry.sequence;
      components.push(
        `window:${escapeComponent(key)}:${entry.sequence}:${entry.time}:` +
        `${escapeComponent(entry.boot)}:${BigInt(entry.numeric)}`
      );
    }
  });
  readSortedMap(reader, () => reader.string(), (key) => {
    stateKey(key);
    const partials = reader.list(() => ({
      nextStep: reader.int(),
      first: reader.long(),
      last: reader.long(),
      time: reader.long(),
      boot: reader.string()
    }));
    for (const partial of partials) {
      if (
        partial.nextStep <= 0 || partial.first <= 0n || partial.last < partial.first ||
        partial.time < 0n || partial.boot.trim().length === 0
      ) throw new Error('checkpoint_sequence');
      components.push(
        `sequence:${escapeComponent(key)}:${partial.nextStep}:${partial.first}:${partial.last}:` +
        `${partial.time}:${escapeComponent(partial.boot)}`
      );
    }
  });
  let activationTotal = 0;
  readSortedMap(reader, () => reader.string(), (key) => {
    if (!AUTOMATION_ID.test(key)) throw new Error('checkpoint_automation');
    const value = reader.int();
    if (value < 0 || value > 512) throw new Error('checkpoint_activation');
    activationTotal += value;
    components.push(`activation:${escapeComponent(key)}=${value}`);
  });
  if (activationTotal > 512) throw new Error('checkpoint_activation_total');
  readSortedMap(reader, () => reader.string(), (key) => {
    if (!AUTOMATION_ID.test(key)) throw new Error('checkpoint_automation');
    const activeMark = reader.long();
    const calendarMark = reader.long();
    if (activeMark < 0n || calendarMark < activeMark) throw new Error('checkpoint_cooldown');
    components.push(
      `cooldown:${escapeComponent(key)}:${activeMark}:${calendarMark}`
    );
  });
  readSortedMap(
    reader,
    () => {
      const kind = reader.string();
      const id = reader.string();
      if ((kind !== 'ACTUATOR' && kind !== 'COLLECTOR') || !RESOURCE_ID.test(id)) {
        throw new Error('checkpoint_resource');
      }
      return { kind, id, sort: `${kind.toLowerCase()}\u0000${id}` };
    },
    (key) => {
      const generation = reader.ulong();
      const profile = reader.nullableString();
      if (generation === 0n) throw new Error('checkpoint_resource_generation');
      components.push(
        `resource:${key.kind}:${escapeComponent(key.id)}:${generation}:` +
        escapeComponent(profile ?? '')
      );
    },
    (key) => key.sort
  );
  readSortedMap(reader, () => reader.string(), (key) => {
    const timer = readCheckpointTimer(reader);
    if (key !== timer.id) throw new Error('checkpoint_timer_key');
    components.push(timerComponent(timer));
  });
  readSortedMap(reader, () => reader.string(), (key) => {
    stateKey(key);
    const generation = reader.ulong();
    if (generation === 0n) throw new Error('checkpoint_timer_generation');
    components.push(`timer-generation:${escapeComponent(key)}:${generation}`);
  });
  let materializedTotal = 0;
  readSortedMap(reader, () => reader.string(), (key) => {
    if (!AUTOMATION_ID.test(key)) throw new Error('checkpoint_automation');
    const summaries = reader.list(() => ({
      producerKey: reader.string(),
      selected: reader.long(),
      terminal: reader.boolean()
    }));
    materializedTotal += summaries.length;
    const producers = new Set<string>();
    for (const summary of summaries) {
      if (
        summary.producerKey.length < 1 || summary.producerKey.length > 160 ||
        summary.selected < 0n || producers.has(summary.producerKey)
      ) throw new Error('checkpoint_materialized');
      producers.add(summary.producerKey);
      components.push(
        `materialized:${escapeComponent(key)}:${escapeComponent(summary.producerKey)}:` +
        `${summary.selected}:${summary.terminal}`
      );
    }
  });
  if (materializedTotal > 512 || !reader.done()) throw new Error('checkpoint_trailing');
  const encodedBytes = UTF8.encode('particeps-automation-checkpoint-v1').length +
    components.reduce((sum, component) => sum + UTF8.encode(component).length + 1, 0);
  if (encodedBytes > MAXIMUM_COMPONENT_BYTES) throw new Error('checkpoint_semantic_size');
  return { evaluated, components };
}

function readCheckpointTimer(reader: CheckpointBinaryReader): DecodedTimer {
  const id = reader.string();
  const automationId = reader.string();
  const generation = reader.ulong();
  const causalSequence = reader.long();
  const producerKey = reader.string();
  const targetTag = reader.byte();
  const target = targetTag === 0
    ? { kind: 'calendar' as const, value: reader.long() }
    : targetTag === 1
      ? { kind: 'active' as const, value: reader.long() }
      : targetTag === 2
        ? {
            kind: 'monotonic' as const,
            bootSessionId: reader.string(),
            value: reader.long()
          }
        : null;
  const logicalDeadline = reader.nullableLong();
  const expiresAt = reader.nullableLong();
  if (
    !target || !TIMER_ID.test(id) || !AUTOMATION_ID.test(automationId) || generation === 0n ||
    causalSequence <= 0n || producerKey.length < 1 || producerKey.length > 160 ||
    producerKey.includes('\u0000') || target.value < 0n ||
    (target.kind === 'monotonic' && target.bootSessionId.trim().length === 0) ||
    (logicalDeadline !== null && logicalDeadline < 0n) ||
    (expiresAt !== null && expiresAt < 0n) ||
    (logicalDeadline !== null && expiresAt !== null && expiresAt < logicalDeadline)
  ) throw new Error('checkpoint_timer');
  return {
    id, automationId, generation, causalSequence, producerKey, target,
    logicalDeadline, expiresAt
  };
}

function timerComponent(timer: DecodedTimer): string {
  let target: string;
  if (timer.target.kind === 'calendar') target = `calendar:${timer.target.value}`;
  else if (timer.target.kind === 'active') target = `active:${timer.target.value}`;
  else target = `monotonic:${escapeComponent(timer.target.bootSessionId)}:${timer.target.value}`;
  return `timer:${timer.id}:${escapeComponent(timer.automationId)}:${timer.generation}:` +
    `${timer.causalSequence}:${escapeComponent(timer.producerKey)}:${target}:` +
    `${timer.logicalDeadline ?? ''}:${timer.expiresAt ?? ''}`;
}

function deterministicDigest(domain: string, components: string[]): string {
  if (!domain || domain.includes('\u0000') || components.some((value) => value.includes('\u0000'))) {
    throw new Error('checkpoint_digest_component');
  }
  return hex(sha256(UTF8.encode([domain, ...components].join('\u0000'))));
}

function escapeComponent(value: string): string {
  return value.replaceAll('%', '%25').replaceAll('\u0000', '%00')
    .replaceAll(':', '%3a').replaceAll('=', '%3d');
}

function stateKey(value: string): void {
  if (value.length < 1 || value.length > 512 || value.includes('\u0000')) {
    throw new Error('checkpoint_state_key');
  }
}

function readSortedMap<K>(
  reader: CheckpointBinaryReader,
  readKey: () => K,
  readValue: (key: K) => void,
  sortKey: (key: K) => string = (key) => String(key)
): void {
  const count = reader.collectionSize();
  let previous: string | null = null;
  for (let index = 0; index < count; index += 1) {
    const key = readKey();
    const sort = sortKey(key);
    if (previous !== null && previous >= sort) throw new Error('checkpoint_map_order');
    previous = sort;
    readValue(key);
  }
}

function strictlySorted(values: string[]): boolean {
  return values.every((value, index) => index === 0 || values[index - 1] < value);
}

class CheckpointBinaryReader {
  private offset = 0;

  constructor(private readonly payload: Uint8Array) {}

  int(): number {
    this.require(4);
    const value = new DataView(
      this.payload.buffer,
      this.payload.byteOffset + this.offset,
      4
    ).getInt32(0);
    this.offset += 4;
    return value;
  }

  collectionSize(): number {
    const value = this.int();
    if (value < 0 || value > 4_096) throw new Error('checkpoint_collection');
    return value;
  }

  long(): bigint {
    this.require(8);
    const value = new DataView(
      this.payload.buffer,
      this.payload.byteOffset + this.offset,
      8
    ).getBigInt64(0);
    this.offset += 8;
    return value;
  }

  ulong(): bigint {
    const value = this.string();
    if (!/^(?:0|[1-9][0-9]*)$/.test(value)) throw new Error('checkpoint_ulong');
    const integer = BigInt(value);
    if (integer > MAXIMUM_UINT64) throw new Error('checkpoint_ulong');
    return integer;
  }

  byte(): number {
    this.require(1);
    return this.payload[this.offset++];
  }

  boolean(): boolean {
    const value = this.byte();
    if (value !== 0 && value !== 1) throw new Error('checkpoint_boolean');
    return value === 1;
  }

  string(): string {
    const length = this.int();
    if (length < 0 || length > MAXIMUM_COMPONENT_BYTES) throw new Error('checkpoint_string');
    this.require(length);
    const bytes = this.payload.subarray(this.offset, this.offset + length);
    this.offset += length;
    return FATAL_UTF8.decode(bytes);
  }

  nullableLong(): bigint | null {
    return this.boolean() ? this.long() : null;
  }

  nullableString(): string | null {
    return this.boolean() ? this.string() : null;
  }

  list<T>(read: () => T): T[] {
    const size = this.collectionSize();
    return Array.from({ length: size }, read);
  }

  done(): boolean {
    return this.offset === this.payload.length;
  }

  private require(length: number): void {
    if (length < 0 || this.offset + length > this.payload.length) {
      throw new Error('checkpoint_truncated');
    }
  }
}

function nullableDecimal(value: unknown): bigint | null | undefined {
  return value === null ? null : decimal(value) ? BigInt(value) : undefined;
}

const digest = (value: unknown): value is string => typeof value === 'string' && SHA256_HEX.test(value);

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

function canonicalIanaZone(value: string): boolean {
  if (value !== 'UTC' && !value.includes('/')) return false;
  try {
    return new Intl.DateTimeFormat('en', { timeZone: value }).resolvedOptions().timeZone === value;
  } catch {
    return false;
  }
}

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
