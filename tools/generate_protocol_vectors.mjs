#!/usr/bin/env node
/** Generate the deterministic, language-neutral Protocol v1 conformance corpus. */

import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { ed25519, x25519 } from '../web/node_modules/@noble/curves/ed25519.js';
import { expand, extract } from '../web/node_modules/@noble/hashes/hkdf.js';
import { sha256 } from '../web/node_modules/@noble/hashes/sha2.js';

const UTF8 = new TextEncoder();
const EMPTY = new Uint8Array();
const output = new URL('../protocol/v1/conformance-vectors.json', import.meta.url);
const registryDigest = (
  await readFile(new URL('../protocol/v1/generated/event-source-registry.sha256', import.meta.url), 'utf8')
).trim();
const concat = (...values) => {
  const result = new Uint8Array(values.reduce((sum, value) => sum + value.length, 0));
  let offset = 0;
  for (const value of values) {
    result.set(value, offset);
    offset += value.length;
  }
  return result;
};
const hex = (value) => Buffer.from(value).toString('hex');
const base64url = (value) => Buffer.from(value).toString('base64url');
const digest = (value) => createHash('sha256').update(value).digest();
const u16 = (value) => Uint8Array.of(value >>> 8, value & 255);
const u32 = (value) => Uint8Array.of(value >>> 24, value >>> 16, value >>> 8, value).map((x) => x & 255);
const canonical = (value) => {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
};
const bytes = (value) => UTF8.encode(canonical(value));
const clone = (value) => structuredClone(value);
const xorLast = (value) => {
  const result = value.slice();
  result[result.length - 1] ^= 1;
  return result;
};

class BinaryWriter {
  chunks = [];

  int(value) {
    const output = Buffer.alloc(4);
    output.writeInt32BE(Number(value));
    this.chunks.push(output);
  }

  long(value) {
    const output = Buffer.alloc(8);
    output.writeBigInt64BE(BigInt(value));
    this.chunks.push(output);
  }

  boolean(value) {
    this.chunks.push(Uint8Array.of(value ? 1 : 0));
  }

  byte(value) {
    this.chunks.push(Uint8Array.of(value));
  }

  string(value) {
    const encoded = UTF8.encode(value);
    this.int(encoded.length);
    this.chunks.push(encoded);
  }

  nullable(value, encode) {
    this.boolean(value !== null);
    if (value !== null) encode(value);
  }

  list(values, encode) {
    this.int(values.length);
    for (const value of values) encode(value);
  }

  bytes() {
    return concat(...this.chunks);
  }
}

const deterministicDigest = (domain, ...parts) =>
  hex(digest(UTF8.encode([domain, ...parts].join('\0'))));
const escapedComponent = (value) => value
  .replaceAll('%', '%25')
  .replaceAll('\0', '%00')
  .replaceAll(':', '%3a')
  .replaceAll('=', '%3d');
const researchTime = (wall, elapsed, boot = 'boot-vector') => ({
  boot_session_id: boot,
  elapsed_realtime_nanos: String(elapsed),
  wall_time_utc_millis: String(wall)
});
const embeddedTime = (wall, elapsed, boot = 'boot-vector') => canonical({
  boot_session_id: boot,
  monotonic_time_nanos: String(elapsed),
  wall_time_utc_millis: String(wall)
});

const i2osp2 = (value) => u16(value);
const suiteKem = concat(UTF8.encode('KEM'), i2osp2(0x20));
const suiteHpke = concat(UTF8.encode('HPKE'), i2osp2(0x20), i2osp2(1), i2osp2(2));
const version = UTF8.encode('HPKE-v1');
const labeledExtract = (suite, salt, label, ikm) =>
  extract(sha256, concat(version, suite, UTF8.encode(label), ikm), salt);
const labeledExpand = (suite, prk, label, info, length) =>
  expand(sha256, prk, concat(i2osp2(length), version, suite, UTF8.encode(label), info), length);

async function aesGcm(keyBytes, nonce, plaintext, aad) {
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['encrypt']);
  return new Uint8Array(await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 },
    key,
    plaintext
  ));
}

async function hpkeSeal(recipientPublic, ephemeralPrivate, plaintext, info) {
  const enc = x25519.getPublicKey(ephemeralPrivate);
  const dh = x25519.getSharedSecret(ephemeralPrivate, recipientPublic);
  const eaePrk = labeledExtract(suiteKem, EMPTY, 'eae_prk', dh);
  const shared = labeledExpand(suiteKem, eaePrk, 'shared_secret', concat(enc, recipientPublic), 32);
  const schedule = concat(
    Uint8Array.of(0),
    labeledExtract(suiteHpke, EMPTY, 'psk_id_hash', EMPTY),
    labeledExtract(suiteHpke, EMPTY, 'info_hash', info)
  );
  const secret = labeledExtract(suiteHpke, shared, 'secret', EMPTY);
  const key = labeledExpand(suiteHpke, secret, 'key', schedule, 32);
  const nonce = labeledExpand(suiteHpke, secret, 'base_nonce', schedule, 12);
  return concat(enc, await aesGcm(key, nonce, plaintext, EMPTY));
}

const signerPrivate = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
const signerPublic = ed25519.getPublicKey(signerPrivate);
const researcherPrivate = Uint8Array.from({ length: 32 }, (_, index) => 0x41 + index);
const researcherPublic = x25519.getPublicKey(researcherPrivate);
const configuration = {
  assigned_participant_id: null,
  automations: [
    {
      cases: [{ condition: { type: 'study_session_active' }, profile_id: 'continuous' }],
      default_profile_id: 'continuous',
      id: 'bind-battery',
      resource: { id: 'battery_state.v1', kind: 'collector' },
      type: 'resource_binding'
    }
  ],
  collectors: [
    {
      id: 'battery_state.v1',
      profiles: [{ config: {}, id: 'continuous' }],
      required: true
    }
  ],
  configuration_id: 'vector-config',
  consent: { document_version: 'v1', summary: 'Protocol vector consent.' },
  duration_hours: 24,
  expires_at: '2030-01-01T00:00:00Z',
  experiment_id: 'vector-study',
  export: { hpke_public_key: base64url(researcherPublic), researcher_key_id: 'vector-hpke' },
  interventions: [],
  issued_at: '2026-01-01T00:00:00Z',
  minimum_client_version: '7',
  platform: 'android',
  purpose: 'Exercise the destructive Protocol v1 contract.',
  researcher: { contact: 'vector@example.invalid', name: 'Protocol Vector' },
  schema_version: 1,
  signer: { key_id: 'vector-signer', public_key: base64url(signerPublic) },
  storage: { maximum_local_bytes: 16777216 },
  surveys: [],
  title: 'Protocol vector',
  traffic_shaping: {},
  upload: {}
};
const configurationBytes = bytes(configuration);
const configurationSha = digest(configurationBytes);
const signature = ed25519.sign(configurationBytes, signerPrivate);
const signerKeyId = UTF8.encode(configuration.signer.key_id);
const envelope = concat(
  UTF8.encode('PTCCFG01'),
  u16(signerKeyId.length),
  u32(configurationBytes.length),
  signerKeyId,
  configurationBytes,
  signature
);

const bundleId = '00000000-0000-4000-8000-000000000099';
const bundleIdBytes = Uint8Array.from(Buffer.from(bundleId.replaceAll('-', ''), 'hex'));
const contextValue = {
  bundle_format: 'particeps-research-bundle-v1',
  bundle_id: bundleId,
  configuration_sha256: hex(configurationSha),
  researcher_key_id: 'vector-hpke'
};
const context = bytes(contextValue);

const profileSha256 = hex(digest(bytes({})));
const conditionEpochId = '00000000-0000-4000-8000-000000000023';
const resourceVector = canonical({
  resources: [
    {
      applied_profile_sha256: profileSha256,
      desired_generation: '1',
      failure_reason: null,
      id: 'battery_state.v1',
      kind: 'collector',
      profile_id: 'continuous',
      status: 'APPLIED'
    }
  ]
});
const resourceVectorSha256 = hex(digest(UTF8.encode(resourceVector)));
const activeEpoch = {
  activated_at: researchTime(1000, 1000),
  applied_resource_vector_sha256: resourceVectorSha256,
  configuration_sha256: hex(configurationSha),
  id: conditionEpochId
};

const encodeAutomationCheckpoint = ({
  evaluated,
  lifecycle,
  studyStartUtcMillis,
  activeElapsed,
  calendarElapsed
}) => {
  const writer = new BinaryWriter();
  writer.int(1);
  writer.long(evaluated);
  writer.string(lifecycle);
  writer.nullable(studyStartUtcMillis, (value) => writer.long(value));
  writer.long(activeElapsed);
  writer.long(calendarElapsed);
  for (let index = 0; index < 8; index += 1) writer.int(0);
  writer.int(1);
  writer.string('COLLECTOR');
  writer.string('battery_state.v1');
  writer.string('1');
  writer.nullable('continuous', (value) => writer.string(value));
  writer.int(0);
  writer.int(0);
  writer.int(0);
  return `automation-checkpoint-v1:${base64url(writer.bytes())}`;
};
const automationCheckpointSha256 = ({
  evaluated,
  lifecycle,
  studyStartUtcMillis,
  activeElapsed,
  calendarElapsed
}) =>
  deterministicDigest(
    'particeps-automation-checkpoint-v1',
    `evaluated=${evaluated}`,
    `lifecycle=${lifecycle}`,
    `start=${studyStartUtcMillis ?? '-'}`,
    `active=${activeElapsed}`,
    `calendar=${calendarElapsed}`,
    `resource:COLLECTOR:${escapedComponent('battery_state.v1')}:1:${escapedComponent('continuous')}`
  );
const appliedResourceComponent = (() => {
  const writer = new BinaryWriter();
  writer.int(1);
  writer.string('COLLECTOR');
  writer.string('battery_state.v1');
  writer.string('1');
  writer.nullable('continuous', (value) => writer.string(value));
  writer.nullable(profileSha256, (value) => writer.string(value));
  writer.string('APPLIED');
  writer.nullable(null, (value) => writer.string(value));
  return `applied-resource-v1:${base64url(writer.bytes())}`;
})();

const writeTime = (writer, value) => {
  writer.long(value.wall_time_utc_millis);
  writer.long(value.elapsed_realtime_nanos);
  writer.string(value.boot_session_id);
};
const writeCoverage = (writer, value) => {
  writer.string(value.clock_basis);
  writer.string(value.start_inclusive);
  writer.string(value.end_exclusive);
};
const writeEpoch = (writer, value) => {
  writer.string(value.id);
  writer.string(value.configuration_sha256);
  writer.string(value.applied_resource_vector_sha256);
  writeTime(writer, value.activated_at);
};
const writeClock = (writer, value) => {
  writer.long(value.calendar_elapsed_nanos);
  writer.long(value.active_running_elapsed_nanos);
  writeTime(writer, value.anchor);
  writer.long(value.deadline_utc_millis);
  writer.boolean(value.deadline_utc_trusted);
  writer.string(value.zone_id);
};
const writeEvent = (writer, value) => {
  writer.long(value.sequence_number);
  writer.string(value.source_id);
  writer.int(value.schema_version);
  writer.string(value.event_type);
  writeTime(writer, value.observed_time);
  writer.nullable(value.condition_epoch_id, (item) => writer.string(item));
  const fields = Object.entries(value.fields).sort(([left], [right]) => left.localeCompare(right));
  writer.int(fields.length);
  for (const [key, fieldValue] of fields) {
    writer.string(key);
    writer.string(fieldValue);
  }
};
const writeObservation = (writer, value) => {
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
};
const writeMutation = (writer, value) => {
  writer.string(value.component_kind);
  writer.string(value.component_id);
  writer.string(value.operation);
  writer.nullable(value.canonical_value, (item) => writer.string(item));
};
const writeProjection = (writer, value) => {
  writer.string(value.state);
  writer.long(value.revision);
  writer.long(value.next_commit_sequence);
  writer.long(value.next_observation_sequence);
  writer.long(value.next_event_sequence);
  const checkpoints = Object.entries(value.source_checkpoints)
    .sort(([left], [right]) => left.localeCompare(right));
  writer.int(checkpoints.length);
  for (const [sourceId, checkpoint] of checkpoints) {
    writer.string(sourceId);
    writer.string(checkpoint.source_id);
    writer.long(checkpoint.resource_generation);
    writer.long(checkpoint.next_producer_ordinal);
    writer.nullable(checkpoint.coverage, (item) => writeCoverage(writer, item));
    writer.nullable(checkpoint.cursor, (item) => writer.string(item));
  }
  writer.nullable(value.clock_checkpoint, (item) => writeClock(writer, item));
  writer.nullable(value.active_condition_epoch, (item) => writeEpoch(writer, item));
  writer.long(value.lifetime_data_event_count);
  writer.long(value.uploaded_through_commit);
  writer.long(value.evaluated_through_commit);
  writer.long(value.retained_from_commit);
};
const observationSha256 = (observation, events) => {
  const writer = new BinaryWriter();
  writer.string('particeps-source-observation-v1');
  writer.string(observation.source_id);
  writer.int(observation.schema_version);
  writer.long(observation.resource_generation);
  writer.long(observation.producer_ordinal);
  writer.string(observation.condition_epoch_id);
  writer.boolean(observation.coverage !== null);
  if (observation.coverage !== null) writeCoverage(writer, observation.coverage);
  writer.int(events.length);
  for (const event of events) {
    writer.string(event.event_type);
    writer.long(event.observed_time.wall_time_utc_millis);
    writer.long(event.observed_time.elapsed_realtime_nanos);
    writer.string(event.observed_time.boot_session_id);
    const fields = Object.entries(event.fields).sort(([left], [right]) => left.localeCompare(right));
    writer.int(fields.length);
    for (const [key, value] of fields) {
      writer.string(key);
      writer.string(value);
    }
  }
  return hex(digest(writer.bytes()));
};
const commitSha256 = (commit) => {
  const writer = new BinaryWriter();
  writer.string('particeps-engine-commit-v1');
  writer.long(commit.commit_sequence);
  writer.string(commit.previous_commit_sha256);
  writer.string(commit.input_kind);
  writer.nullable(commit.consumed_pending_input_sha256, (value) => writer.string(value));
  writer.list(commit.source_observations, (value) => writeObservation(writer, value));
  writer.list(commit.events, (value) => writeEvent(writer, value));
  writer.list(commit.mutations, (value) => writeMutation(writer, value));
  writeTime(writer, commit.committed_at);
  writeProjection(writer, commit.successor_projection);
  writer.string(commit.resulting_checkpoint_sha256);
  return hex(digest(writer.bytes()));
};
const sealCommit = (value) => {
  const commit = { ...value, commit_sha256: '0'.repeat(64) };
  commit.commit_sha256 = commitSha256(commit);
  return commit;
};

const studyStartWall = 500;
const studyStartElapsed = 500;
const runningWall = 1000;
const runningElapsed = 1000;
const batteryWall = 2000;
const batteryElapsed = 2000;
const studyDeadlineUtcMillis = studyStartWall + configuration.duration_hours * 60 * 60 * 1000;
const studyDeadlineElapsed = studyStartElapsed + configuration.duration_hours * 60 * 60 * 1_000_000_000;
const studyDeadlineTimerId = deterministicDigest(
  'particeps-study-deadline-timer-v1',
  hex(configurationSha),
  'study-duration',
  'study-deadline'
);
const studyDeadlineTimerComponent = (() => {
  const writer = new BinaryWriter();
  writer.int(1);
  writer.string(studyDeadlineTimerId);
  writer.string('study-duration');
  writer.string('1');
  writer.long(1);
  writer.string('study-deadline');
  writer.byte(2);
  writer.string('boot-vector');
  writer.long(studyDeadlineElapsed);
  writer.nullable(studyDeadlineUtcMillis, (value) => writer.long(value));
  writer.nullable(null, (value) => writer.long(value));
  return `durable-timer-v1:${base64url(writer.bytes())}`;
})();
const studyClock = (wall, elapsed, active, calendar) => ({
  active_running_elapsed_nanos: String(active),
  anchor: researchTime(wall, elapsed),
  calendar_elapsed_nanos: String(calendar),
  deadline_utc_millis: String(studyDeadlineUtcMillis),
  deadline_utc_trusted: true,
  zone_id: 'UTC'
});
const startCommandId = deterministicDigest(
  'particeps-runtime-command-v1',
  hex(configurationSha),
  'start',
  '1'
);
const startedEvent = {
  condition_epoch_id: null,
  event_type: 'STUDY_STARTED',
  fields: {
    command_id: startCommandId,
    current_state: 'ACTIVATING',
    transition_reason: 'STUDY_START'
  },
  observed_time: researchTime(studyStartWall, studyStartElapsed),
  schema_version: 1,
  sequence_number: '1',
  source_id: 'study_runtime.v1'
};
const studyDeadlineScheduledEvent = {
  condition_epoch_id: null,
  event_type: 'TIMER_SCHEDULED',
  fields: {
    automation_id: 'study-duration',
    causal_sequence: '1',
    clock: 'SAME_BOOT_MONOTONIC',
    generation: '1',
    logical_due_research_time: embeddedTime(studyDeadlineUtcMillis, studyDeadlineElapsed),
    producer_key: 'study-deadline',
    timer_id: studyDeadlineTimerId
  },
  observed_time: researchTime(studyStartWall, studyStartElapsed),
  schema_version: 1,
  sequence_number: '2',
  source_id: 'timer.v1'
};
const activationEvent = {
  condition_epoch_id: conditionEpochId,
  event_type: 'CONDITION_EPOCH_ACTIVATED',
  fields: {
    activation_reason: 'INITIAL_START',
    applied_resource_vector_sha256: resourceVectorSha256,
    boundary_research_time: embeddedTime(runningWall, runningElapsed),
    condition_epoch_id: conditionEpochId,
    resource_vector_json: resourceVector,
    signed_configuration_sha256: hex(configurationSha)
  },
  observed_time: researchTime(runningWall, runningElapsed),
  schema_version: 1,
  sequence_number: '3',
  source_id: 'study_condition.v1'
};
const runningEvent = {
  condition_epoch_id: conditionEpochId,
  event_type: 'STUDY_RUNNING',
  fields: {
    command_id: startCommandId,
    current_state: 'RUNNING',
    previous_state: 'ACTIVATING',
    transition_reason: 'ACTIVATION_CONFIRMED'
  },
  observed_time: researchTime(runningWall, runningElapsed),
  schema_version: 1,
  sequence_number: '4',
  source_id: 'study_runtime.v1'
};
const batteryEvent = {
  condition_epoch_id: conditionEpochId,
  event_type: 'BATTERY_STATE',
  fields: {
    charging_source: 'NONE',
    charging_state: 'DISCHARGING',
    percentage: '50',
    power_save_enabled: 'false'
  },
  observed_time: researchTime(batteryWall, batteryElapsed),
  schema_version: 1,
  sequence_number: '5',
  source_id: 'battery_state.v1'
};
const batteryObservation = {
  admission_kind: 'NORMAL',
  condition_epoch_id: conditionEpochId,
  coverage: null,
  encoded_sha256: '',
  event_count: 1,
  first_event_sequence: '5',
  last_event_sequence: '5',
  observation_sequence: '1',
  producer_ordinal: '0',
  resource_generation: '1',
  schema_version: 1,
  source_id: 'battery_state.v1'
};
batteryObservation.encoded_sha256 = observationSha256(batteryObservation, [batteryEvent]);

const activatingCheckpoint = {
  activeElapsed: 0,
  calendarElapsed: 0,
  evaluated: 1,
  lifecycle: 'ACTIVATING',
  studyStartUtcMillis: studyStartWall
};
const runningCheckpoint = {
  activeElapsed: 0,
  calendarElapsed: runningElapsed - studyStartElapsed,
  evaluated: 2,
  lifecycle: 'RUNNING',
  studyStartUtcMillis: studyStartWall
};
const observationCheckpoint = {
  activeElapsed: batteryElapsed - runningElapsed,
  calendarElapsed: batteryElapsed - studyStartElapsed,
  evaluated: 3,
  lifecycle: 'RUNNING',
  studyStartUtcMillis: studyStartWall
};
const checkpointOne = encodeAutomationCheckpoint(activatingCheckpoint);
const checkpointTwo = encodeAutomationCheckpoint(runningCheckpoint);
const checkpointThree = encodeAutomationCheckpoint(observationCheckpoint);
const commitOne = sealCommit({
  commit_sequence: '1',
  committed_at: researchTime(studyStartWall, studyStartElapsed),
  consumed_pending_input_sha256: null,
  events: [startedEvent, studyDeadlineScheduledEvent],
  input_kind: 'LIFECYCLE_COMMAND',
  mutations: [
    {
      canonical_value: checkpointOne,
      component_id: 'main',
      component_kind: 'AUTOMATION_CHECKPOINT',
      operation: 'UPSERT'
    },
    {
      canonical_value: studyDeadlineTimerComponent,
      component_id: 'study-duration',
      component_kind: 'STUDY_DEADLINE_TIMER',
      operation: 'UPSERT'
    }
  ],
  previous_commit_sha256: '0'.repeat(64),
  resulting_checkpoint_sha256: automationCheckpointSha256(activatingCheckpoint),
  source_observations: [],
  successor_projection: {
    active_condition_epoch: null,
    clock_checkpoint: studyClock(studyStartWall, studyStartElapsed, 0, 0),
    evaluated_through_commit: '1',
    lifetime_data_event_count: '0',
    next_commit_sequence: '2',
    next_event_sequence: '3',
    next_observation_sequence: '1',
    retained_from_commit: '1',
    revision: '1',
    source_checkpoints: {},
    state: 'ACTIVATING',
    uploaded_through_commit: '0'
  }
});
const commitTwo = sealCommit({
  commit_sequence: '2',
  committed_at: researchTime(runningWall, runningElapsed),
  consumed_pending_input_sha256: null,
  events: [activationEvent, runningEvent],
  input_kind: 'RESOURCE_RESULT',
  mutations: [
    {
      canonical_value: checkpointTwo,
      component_id: 'main',
      component_kind: 'AUTOMATION_CHECKPOINT',
      operation: 'UPSERT'
    },
    {
      canonical_value: appliedResourceComponent,
      component_id: 'collector:battery_state.v1',
      component_kind: 'RESOURCE',
      operation: 'UPSERT'
    }
  ],
  previous_commit_sha256: commitOne.commit_sha256,
  resulting_checkpoint_sha256: automationCheckpointSha256(runningCheckpoint),
  source_observations: [],
  successor_projection: {
    active_condition_epoch: activeEpoch,
    clock_checkpoint: studyClock(
      runningWall,
      runningElapsed,
      runningCheckpoint.activeElapsed,
      runningCheckpoint.calendarElapsed
    ),
    evaluated_through_commit: '2',
    lifetime_data_event_count: '0',
    next_commit_sequence: '3',
    next_event_sequence: '5',
    next_observation_sequence: '1',
    retained_from_commit: '1',
    revision: '2',
    source_checkpoints: {},
    state: 'RUNNING',
    uploaded_through_commit: '0'
  }
});
const commitThree = sealCommit({
  commit_sequence: '3',
  committed_at: researchTime(batteryWall, batteryElapsed),
  consumed_pending_input_sha256: null,
  events: [batteryEvent],
  input_kind: 'SOURCE_OBSERVATION',
  mutations: [
    {
      canonical_value: checkpointThree,
      component_id: 'main',
      component_kind: 'AUTOMATION_CHECKPOINT',
      operation: 'UPSERT'
    }
  ],
  previous_commit_sha256: commitTwo.commit_sha256,
  resulting_checkpoint_sha256: automationCheckpointSha256(observationCheckpoint),
  source_observations: [batteryObservation],
  successor_projection: {
    active_condition_epoch: activeEpoch,
    clock_checkpoint: studyClock(
      batteryWall,
      batteryElapsed,
      observationCheckpoint.activeElapsed,
      observationCheckpoint.calendarElapsed
    ),
    evaluated_through_commit: '3',
    lifetime_data_event_count: '1',
    next_commit_sequence: '4',
    next_event_sequence: '6',
    next_observation_sequence: '2',
    retained_from_commit: '1',
    revision: '3',
    source_checkpoints: {
      'battery_state.v1': {
        coverage: null,
        cursor: null,
        next_producer_ordinal: '1',
        resource_generation: '1',
        source_id: 'battery_state.v1'
      }
    },
    state: 'RUNNING',
    uploaded_through_commit: '0'
  }
});
const documentValue = {
  bundle_id: bundleId,
  bundle_kind: 'automatic_upload',
  configuration,
  configuration_sha256: hex(configurationSha),
  configuration_signature: { signature: base64url(signature), signer_key_id: 'vector-signer' },
  event_source_registry_sha256: registryDigest,
  experiment: {
    assigned_participant_id: null,
    commit_count: '3',
    commits: [commitOne, commitTwo, commitThree],
    configuration_id: 'vector-config',
    durable_through_commit: '3',
    evaluated_through_commit: '3',
    event_count: '5',
    experiment_id: 'vector-study',
    first_commit_sequence: '1',
    last_commit_sequence: '3',
    lifetime_data_event_count: '1',
    next_commit_sequence: '4',
    participant_instance_id: '00000000-0000-4000-8000-000000000017',
    retained_from_commit: '1',
    state: 'RUNNING',
    uploaded_through_commit: '0'
  },
  exported_at_utc_millis: '10000',
  format: 'particeps-research-bundle-v1',
  producer: { client_version: '7', platform: 'android' }
};
const documentBytes = bytes(documentValue);
const contentKey = Uint8Array.from({ length: 32 }, (_, index) => 0xa0 + index);
const contentNonce = Uint8Array.from({ length: 12 }, (_, index) => 0x10 + index);
const ephemeralPrivate = Uint8Array.from({ length: 32 }, (_, index) => 0x21 + index);
const wrappedKey = await hpkeSeal(researcherPublic, ephemeralPrivate, contentKey, context);
const documentCiphertext = await aesGcm(contentKey, contentNonce, documentBytes, context);
const bundlePrefixForNonce = (nonce) => concat(
  UTF8.encode('PTCEXP01'),
  bundleIdBytes,
  configurationSha,
  u16(UTF8.encode('vector-hpke').length),
  nonce,
  UTF8.encode('vector-hpke'),
  wrappedKey
);
const bundlePrefix = bundlePrefixForNonce(contentNonce);
const bundle = concat(bundlePrefix, documentCiphertext);
let semanticNonceCounter = 0;
const authenticatedBundle = async (document) => {
  // These are public deterministic fixtures, but each authenticated hostile still models the
  // production invariant that one AES-GCM key never repeats a nonce.
  const nonce = contentNonce.slice();
  nonce[nonce.length - 1] += ++semanticNonceCounter;
  return concat(
    bundlePrefixForNonce(nonce),
    await aesGcm(contentKey, nonce, bytes(document), context)
  );
};
const bundleSha = digest(bundle);
const receiptValue = {
  bundle_id: bundleId,
  byte_count: String(bundle.length),
  commit_count: '3',
  configuration_sha256: hex(configurationSha),
  event_count: '5',
  first_commit_sequence: '1',
  last_commit_sequence: '3',
  sha256: hex(bundleSha)
};
const receiptBytes = bytes(receiptValue);
const oldConfig = concat(envelope.slice(0, 14), u16(64), envelope.slice(14));
const wrongDigestBundle = bundle.slice();
wrongDigestBundle[24] ^= 1;
// The retired Android Data Collector identity is not a second dialect of Protocol v1; it is input
// that must fail closed. These fixtures are the only place the old names survive, and they exist
// so every implementation proves it rejects them rather than sniffing a fallback.
const retiredMagicConfiguration = concat(UTF8.encode('ADCCFG01'), envelope.slice(8));
const retiredMagicBundle = concat(UTF8.encode('ADCEXP01'), bundle.slice(8));

const configurationVariant = (change) => {
  const value = clone(configuration);
  change(value);
  return bytes(value);
};
const signedVariant = (payload, signatureBytes = signature) => concat(
  UTF8.encode('PTCCFG01'),
  u16(signerKeyId.length),
  u32(payload.length),
  signerKeyId,
  payload,
  signatureBytes
);
const semanticBundle = async (change) => {
  const value = clone(documentValue);
  change(value);
  return authenticatedBundle(value);
};
const resealObservationCommit = (commit) => {
  const observation = commit.source_observations[0];
  observation.encoded_sha256 = observationSha256(observation, commit.events);
  commit.commit_sha256 = commitSha256(commit);
};
const withInnerBundleMismatch = await semanticBundle((value) => {
  value.bundle_id = '00000000-0000-4000-8000-000000000098';
});
const withEmbeddedConfigurationMember = await semanticBundle((value) => {
  value.configuration.unexpected = true;
});
const withInnerDigestMismatch = await semanticBundle((value) => {
  value.configuration_sha256 = '00'.repeat(32);
});
const withInvalidSignature = await semanticBundle((value) => {
  value.configuration_signature.signature = base64url(xorLast(signature));
});
const withOldProducer = await semanticBundle((value) => {
  value.producer.client_version = '6';
});
const withUnknownRootMember = await semanticBundle((value) => {
  value.unexpected = true;
});
// Authenticated under the current context, so only the inner format check can reject it.
const withRetiredBundleFormat = await semanticBundle((value) => {
  value.format = 'research-bundle-v1';
});
const withRegistryDigestMismatch = await semanticBundle((value) => {
  value.event_source_registry_sha256 = '00'.repeat(32);
});
const withCommitRangeMismatch = await semanticBundle((value) => {
  value.experiment.commit_count = '4';
});
const withCommitDigestMismatch = await semanticBundle((value) => {
  value.experiment.commits[2].commit_sha256 = '00'.repeat(32);
});
const withCommitPredecessorMismatch = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.previous_commit_sha256 = '11'.repeat(32);
  commit.commit_sha256 = commitSha256(commit);
});
const withCheckpointDigestMismatch = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.resulting_checkpoint_sha256 = '22'.repeat(32);
  commit.commit_sha256 = commitSha256(commit);
});
const withUnknownEventField = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.events[0].fields.unexpected = 'value';
  resealObservationCommit(commit);
});
const withUnknownSource = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.events[0].source_id = 'unknown.v1';
  commit.commit_sha256 = commitSha256(commit);
});
const withUnknownEventType = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.events[0].event_type = 'UNKNOWN_EVENT';
  resealObservationCommit(commit);
});
const withInvalidTypedField = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.events[0].fields.percentage = '101';
  resealObservationCommit(commit);
});
const withObservationDigestMismatch = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.source_observations[0].encoded_sha256 = '33'.repeat(32);
  commit.commit_sha256 = commitSha256(commit);
});
const withPartialObservation = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.source_observations[0].first_event_sequence = '3';
  commit.source_observations[0].last_event_sequence = '3';
  commit.commit_sha256 = commitSha256(commit);
});
const withProducerOrdinalMismatch = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.source_observations[0].producer_ordinal = '1';
  resealObservationCommit(commit);
});
const withMissingEpoch = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  commit.events[0].condition_epoch_id = null;
  commit.commit_sha256 = commitSha256(commit);
});
const withOrphanEpoch = await semanticBundle((value) => {
  const commit = value.experiment.commits[2];
  const orphan = '00000000-0000-4000-8000-000000000024';
  commit.events[0].condition_epoch_id = orphan;
  commit.source_observations[0].condition_epoch_id = orphan;
  resealObservationCommit(commit);
});
const withFlatEventDocument = await semanticBundle((value) => {
  value.experiment.events = clone(value.experiment.commits[2].events);
  delete value.experiment.commits;
});
const withTrailingBundleByte = concat(bundle, Uint8Array.of(0));
const signaturePayload = configurationVariant((value) => { value.title = 'Wrong signature input'; });
const validCanonicalJson = bytes({
  '\r': 'Carriage Return',
  '1': 'One',
  '\u0080': 'Control',
  'ö': 'Latin Small Letter O With Diaeresis',
  '€': 'Euro Sign',
  '😀': 'Emoji: Grinning Face',
  'דּ': 'Hebrew Letter Dalet With Dagesh'
});

const corpus = {
  corpus_format: 'particeps-protocol-conformance-v1',
  hostile: [
    { category: 'unicode_jcs', entrypoint: 'canonical_json', expected_failure: 'utf16_key_order', id: 'jcs-wrong-utf16-key-order', input_hex: hex(UTF8.encode('{"":0,"𐀀":1}')) },
    { category: 'unicode_jcs', entrypoint: 'canonical_json', expected_failure: 'malformed_utf8', id: 'jcs-malformed-utf8', input_hex: '7b2278223a22c328227d' },
    { category: 'unicode_jcs', entrypoint: 'canonical_json', expected_failure: 'unpaired_surrogate', id: 'jcs-unpaired-surrogate', input_hex: hex(UTF8.encode('{"x":"\\ud800"}')) },
    { category: 'unicode_jcs', entrypoint: 'canonical_json', expected_failure: 'noncanonical_escape', id: 'jcs-noncanonical-unicode-escape', input_hex: hex(UTF8.encode('{"x":"\\u0061"}')) },
    { category: 'integral_bounds', entrypoint: 'canonical_json', expected_failure: 'negative_zero', id: 'jcs-negative-zero', input_hex: hex(UTF8.encode('{"n":-0}')) },
    { category: 'trailing_bytes', entrypoint: 'canonical_json', expected_failure: 'trailing_whitespace', id: 'jcs-trailing-whitespace', input_hex: hex(concat(validCanonicalJson, UTF8.encode('\n'))) },
    { category: 'unknown_field', entrypoint: 'configuration_jcs', expected_failure: 'duplicate_member', id: 'config-duplicate-member', input_hex: hex(UTF8.encode(canonical(configuration).replace('{', '{"assigned_participant_id":null,'))) },
    { category: 'old_v1', entrypoint: 'configuration_jcs', expected_failure: 'legacy_field', id: 'config-old-v1-field', input_hex: hex(UTF8.encode(canonical(configuration).replace('"minimum_client_version":"7"', '"minimum_app_version":7'))) },
    { category: 'old_v1', entrypoint: 'configuration_jcs', expected_failure: 'legacy_collector_shape', id: 'config-flat-collector-profile', input_hex: hex(configurationVariant((value) => { value.collectors[0].config = {}; delete value.collectors[0].profiles; })) },
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'nonintegral_number', id: 'config-nonintegral-duration', input_hex: hex(configurationVariant((value) => { value.duration_hours = 1.5; })) },
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'int64_overflow', id: 'config-client-version-overflow', input_hex: hex(configurationVariant((value) => { value.minimum_client_version = '9223372036854775808'; })) },
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'physical_bound', id: 'config-zero-duration', input_hex: hex(configurationVariant((value) => { value.duration_hours = 0; })) },
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'utf16_text_length', id: 'config-title-exceeds-utf16-code-unit-bound', input_hex: hex(configurationVariant((value) => { value.title = '😀'.repeat(61); })) },
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'array_length', id: 'config-too-many-surveys', input_hex: hex(configurationVariant((value) => {
      value.surveys = Array.from({ length: 129 }, (_, index) => ({
        description: { default: 'Description', translations: {} },
        id: `survey-${String(index).padStart(3, '0')}`,
        questions: [{
          id: 'question-one', maximum_length: 100,
          prompt: { default: 'Prompt', translations: {} }, required: false, type: 'short_text'
        }],
        title: { default: 'Survey', translations: {} }
      }));
    })) },
    { category: 'raw_key_encoding', entrypoint: 'configuration_jcs', expected_failure: 'padded_base64url', id: 'config-padded-signing-key', input_hex: hex(configurationVariant((value) => { value.signer.public_key += '='; })) },
    { category: 'raw_key_encoding', entrypoint: 'configuration_jcs', expected_failure: 'wrong_key_length', id: 'config-short-hpke-key', input_hex: hex(configurationVariant((value) => { value.export.hpke_public_key = base64url(researcherPublic.slice(0, -1)); })) },
    { category: 'raw_key_encoding', entrypoint: 'configuration_jcs', expected_failure: 'legacy_tink_keyset', id: 'config-tink-hpke-keyset', input_hex: hex(configurationVariant((value) => { value.export.hpke_public_key = '{"primaryKeyId":1,"key":[]}'; })) },
    { category: 'trailing_bytes', entrypoint: 'configuration_jcs', expected_failure: 'noncanonical_json', id: 'config-leading-whitespace', input_hex: hex(concat(UTF8.encode(' '), configurationBytes)) },
    { category: 'old_v1', entrypoint: 'signed_configuration', expected_failure: 'old_v1_framing', id: 'partcfg-old-signature-length', input_hex: hex(oldConfig) },
    { category: 'old_v1', entrypoint: 'signed_configuration', expected_failure: 'retired_product_magic', id: 'partcfg-retired-product-magic', input_hex: hex(retiredMagicConfiguration) },
    { category: 'malformed_length', entrypoint: 'signed_configuration', expected_failure: 'zero_key_length', id: 'partcfg-zero-key-length', input_hex: hex(concat(envelope.slice(0, 8), u16(0), envelope.slice(10))) },
    { category: 'malformed_length', entrypoint: 'signed_configuration', expected_failure: 'truncated', id: 'partcfg-truncated', input_hex: hex(envelope.slice(0, -1)) },
    { category: 'trailing_bytes', entrypoint: 'signed_configuration', expected_failure: 'trailing_byte', id: 'partcfg-trailing-byte', input_hex: hex(concat(envelope, Uint8Array.of(0))) },
    { category: 'signature_input', entrypoint: 'signed_configuration', expected_failure: 'signature_payload_mismatch', id: 'partcfg-wrong-signature-input', input_hex: hex(signedVariant(signaturePayload)) },
    { category: 'signature_input', entrypoint: 'signed_configuration', expected_failure: 'tampered_signature', id: 'partcfg-tampered-signature', input_hex: hex(signedVariant(configurationBytes, xorLast(signature))) },
    { category: 'old_v1', entrypoint: 'bundle', expected_failure: 'old_v1_framing', id: 'bundle-old-zero-header', input_hex: hex(concat(UTF8.encode('PTCEXP01'), new Uint8Array(128))) },
    { category: 'old_v1', entrypoint: 'bundle', expected_failure: 'retired_product_magic', id: 'bundle-retired-product-magic', input_hex: hex(retiredMagicBundle) },
    { category: 'old_v1', entrypoint: 'bundle', expected_failure: 'retired_bundle_format', id: 'bundle-retired-bundle-format', input_hex: hex(withRetiredBundleFormat) },
    { category: 'outer_inner_identity', entrypoint: 'bundle', expected_failure: 'configuration_digest_mismatch', id: 'bundle-wrong-configuration-digest', input_hex: hex(wrongDigestBundle) },
    { category: 'body_tampering', entrypoint: 'bundle', expected_failure: 'aead_authentication', id: 'bundle-tampered-tag', input_hex: hex(xorLast(bundle)) },
    { category: 'malformed_length', entrypoint: 'bundle', expected_failure: 'truncated', id: 'bundle-truncated', input_hex: hex(bundle.slice(0, -1)) },
    { category: 'malformed_length', entrypoint: 'bundle', expected_failure: 'zero_key_length', id: 'bundle-zero-key-length', input_hex: hex(bundle.map((byte, index) => index === 56 || index === 57 ? 0 : byte)) },
    { category: 'trailing_bytes', entrypoint: 'bundle', expected_failure: 'aead_authentication', id: 'bundle-trailing-byte', input_hex: hex(withTrailingBundleByte) },
    { category: 'outer_inner_identity', entrypoint: 'bundle', expected_failure: 'inner_bundle_id', id: 'bundle-inner-id-mismatch', input_hex: hex(withInnerBundleMismatch) },
    { category: 'outer_inner_identity', entrypoint: 'bundle', expected_failure: 'embedded_configuration', id: 'bundle-embedded-configuration-member', input_hex: hex(withEmbeddedConfigurationMember) },
    { category: 'outer_inner_identity', entrypoint: 'bundle', expected_failure: 'inner_configuration_digest', id: 'bundle-inner-configuration-digest', input_hex: hex(withInnerDigestMismatch) },
    { category: 'signature_input', entrypoint: 'bundle', expected_failure: 'embedded_signature', id: 'bundle-invalid-embedded-signature', input_hex: hex(withInvalidSignature) },
    { category: 'integral_bounds', entrypoint: 'bundle', expected_failure: 'minimum_client_version', id: 'bundle-old-producer', input_hex: hex(withOldProducer) },
    { category: 'unknown_field', entrypoint: 'bundle', expected_failure: 'unknown_root_member', id: 'bundle-unknown-root-member', input_hex: hex(withUnknownRootMember) },
    { category: 'registry_binding', entrypoint: 'bundle', expected_failure: 'registry_digest', id: 'bundle-registry-digest-mismatch', input_hex: hex(withRegistryDigestMismatch) },
    { category: 'commit_chain', entrypoint: 'bundle', expected_failure: 'commit_range_count', id: 'bundle-commit-range-count-mismatch', input_hex: hex(withCommitRangeMismatch) },
    { category: 'commit_chain', entrypoint: 'bundle', expected_failure: 'commit_digest', id: 'bundle-commit-digest-mismatch', input_hex: hex(withCommitDigestMismatch) },
    { category: 'commit_chain', entrypoint: 'bundle', expected_failure: 'predecessor_digest', id: 'bundle-commit-predecessor-mismatch', input_hex: hex(withCommitPredecessorMismatch) },
    { category: 'commit_chain', entrypoint: 'bundle', expected_failure: 'checkpoint_digest', id: 'bundle-checkpoint-digest-mismatch', input_hex: hex(withCheckpointDigestMismatch) },
    { category: 'event_contract', entrypoint: 'bundle', expected_failure: 'unknown_source', id: 'bundle-unknown-event-source', input_hex: hex(withUnknownSource) },
    { category: 'event_contract', entrypoint: 'bundle', expected_failure: 'unknown_event_type', id: 'bundle-unknown-event-type', input_hex: hex(withUnknownEventType) },
    { category: 'event_contract', entrypoint: 'bundle', expected_failure: 'unknown_event_field', id: 'bundle-unknown-event-field', input_hex: hex(withUnknownEventField) },
    { category: 'event_contract', entrypoint: 'bundle', expected_failure: 'typed_field_bound', id: 'bundle-invalid-typed-event-field', input_hex: hex(withInvalidTypedField) },
    { category: 'observation_manifest', entrypoint: 'bundle', expected_failure: 'observation_digest', id: 'bundle-observation-digest-mismatch', input_hex: hex(withObservationDigestMismatch) },
    { category: 'observation_manifest', entrypoint: 'bundle', expected_failure: 'partial_event_range', id: 'bundle-partial-observation-range', input_hex: hex(withPartialObservation) },
    { category: 'observation_manifest', entrypoint: 'bundle', expected_failure: 'producer_ordinal', id: 'bundle-producer-ordinal-mismatch', input_hex: hex(withProducerOrdinalMismatch) },
    { category: 'condition_epoch', entrypoint: 'bundle', expected_failure: 'missing_epoch', id: 'bundle-missing-condition-epoch', input_hex: hex(withMissingEpoch) },
    { category: 'condition_epoch', entrypoint: 'bundle', expected_failure: 'orphan_epoch', id: 'bundle-orphan-condition-epoch', input_hex: hex(withOrphanEpoch) },
    { category: 'old_v1', entrypoint: 'bundle', expected_failure: 'flat_event_document', id: 'bundle-flat-event-document', input_hex: hex(withFlatEventDocument) },
    { category: 'hpke_context', entrypoint: 'bundle_unwrap_context', expected_failure: 'hpke_authentication', id: 'bundle-wrong-context', input_hex: hex(bytes({ ...contextValue, bundle_id: '00000000-0000-4000-8000-000000000098' })) },
    { category: 'trailing_bytes', entrypoint: 'receipt', expected_failure: 'noncanonical_json', id: 'receipt-leading-whitespace', input_hex: hex(concat(UTF8.encode(' '), receiptBytes)) },
    { category: 'integral_bounds', entrypoint: 'receipt', expected_failure: 'noncanonical_decimal', id: 'receipt-leading-zero', input_hex: hex(UTF8.encode(canonical(receiptValue).replace('"first_commit_sequence":"1"', '"first_commit_sequence":"01"'))) },
    { category: 'integral_bounds', entrypoint: 'receipt', expected_failure: 'wrong_type', id: 'receipt-numeric-count', input_hex: hex(UTF8.encode(canonical(receiptValue).replace('"commit_count":"3"', '"commit_count":3'))) },
    { category: 'integral_bounds', entrypoint: 'receipt', expected_failure: 'int64_overflow', id: 'receipt-byte-count-overflow', input_hex: hex(UTF8.encode(canonical(receiptValue).replace(`"byte_count":"${bundle.length}"`, '"byte_count":"9223372036854775808"'))) },
    { category: 'trailing_bytes', entrypoint: 'receipt', expected_failure: 'trailing_byte', id: 'receipt-trailing-byte', input_hex: hex(concat(receiptBytes, Uint8Array.of(0))) }
  ],
  schema_version: 1,
  valid: {
    canonical_json: {
      canonical_jcs_utf8_hex: hex(validCanonicalJson)
    },
    bundle: {
      bundle_id: bundleId,
      container_hex: hex(bundle),
      content_key_hex: hex(contentKey),
      content_nonce_hex: hex(contentNonce),
      context_jcs_utf8_hex: hex(context),
      document_jcs_utf8_hex: hex(documentBytes),
      hpke_ephemeral_private_key_base64url: base64url(ephemeralPrivate),
      hpke_wrapped_content_key_hex: hex(wrappedKey),
      researcher_private_key_base64url: base64url(researcherPrivate),
      researcher_public_key_base64url: base64url(researcherPublic),
      sha256: hex(bundleSha)
    },
    signed_configuration: {
      canonical_jcs_sha256: hex(configurationSha),
      canonical_jcs_utf8_hex: hex(configurationBytes),
      envelope_hex: hex(envelope),
      signature_base64url: base64url(signature),
      signer_key_id: 'vector-signer',
      signer_private_key_base64url: base64url(signerPrivate),
      signer_public_key_base64url: base64url(signerPublic)
    },
    // The producer of these headers is Kotlin and the only reader is the TypeScript Worker, so
    // nothing but a shared fixture stops one side from being renamed without the other. Both sides
    // assert against this list rather than against their own constants.
    upload_request: {
      bundle_format: 'particeps-research-bundle-v1',
      media_type: 'application/vnd.particeps.research-bundle',
      routing_headers: [
        'X-Particeps-Bundle-Format',
        'X-Particeps-Bundle-Id',
        'X-Particeps-Commit-Count',
        'X-Particeps-Commit-From',
        'X-Particeps-Commit-To',
        'X-Particeps-Configuration-SHA256',
        'X-Particeps-Event-Count',
        'X-Particeps-Researcher-Key-Id'
      ]
    },
    upload_receipt: {
      canonical_jcs_utf8_hex: hex(receiptBytes),
      value: receiptValue
    }
  }
};

const encoded = `${JSON.stringify(corpus, null, 2)}\n`;
if (process.argv.includes('--check')) {
  const checkedIn = await readFile(output, 'utf8');
  if (checkedIn !== encoded) throw new Error('Protocol conformance corpus is stale; regenerate it');
  console.log('Protocol v1 conformance corpus is reproducible');
} else {
  await writeFile(output, encoded);
  console.log(`wrote ${output.pathname}`);
}
