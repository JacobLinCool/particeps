/** Deterministic Protocol v1 bundle sealer used only by reader tests. */

import {
  BUNDLE_FORMAT,
  bundleContext,
  configurationDigest,
  type ResearchDocument
} from '../src/lib/particeps/bundle';
import {
  canonicalBytes,
  canonicalizeConfiguration
} from '../src/lib/particeps/canonical';
import { decodeBase64Url, encodeBase64Url, sign } from '../src/lib/particeps/crypto';
import type { StudyConfiguration } from '../src/lib/particeps/types';
import { x25519 } from '@noble/curves/ed25519.js';
import { expand, extract } from '@noble/hashes/hkdf.js';
import { sha256 } from '@noble/hashes/sha2.js';

const UTF8 = new TextEncoder();
const EMPTY = new Uint8Array(0);
const NONCE = new Uint8Array(12).fill(0x44);
const EPHEMERAL_PRIVATE = new Uint8Array(32).fill(0x33);
const CONTENT_KEY = new Uint8Array(32).fill(0x55);
const DEFAULT_BUNDLE_ID = '00112233-4455-4677-8899-aabbccddeeff';

export interface SealOptions {
  bundleId?: string;
  keyId?: string;
  document?: (value: ResearchDocument) => unknown;
}

export async function sealBundle(
  configuration: StudyConfiguration,
  signingPrivateKey: string,
  options: SealOptions = {}
): Promise<Uint8Array> {
  const bundleId = options.bundleId ?? DEFAULT_BUNDLE_ID;
  const keyId = options.keyId ?? configuration.export.researcher_key_id;
  const digest = configurationDigest(configuration);
  const digestHex = hex(digest);
  const configurationValue = JSON.parse(canonicalizeConfiguration(configuration));
  const signature = sign(canonicalBytes(configurationValue), signingPrivateKey);
  const time = {
    boot_session_id: 'boot-0001',
    monotonic_time_nanos: '9007199254740993',
    wall_time_utc_millis: '1767225600000'
  };
  const document: ResearchDocument = {
    format: BUNDLE_FORMAT,
    bundle_id: bundleId,
    bundle_kind: 'manual_export',
    configuration_sha256: digestHex,
    producer: { platform: 'android', client_version: '1' },
    exported_at_utc_millis: '1767225600000',
    configuration: configurationValue,
    configuration_signature: {
      signer_key_id: configuration.signer.key_id,
      signature: encodeBase64Url(signature)
    },
    experiment: {
      experiment_id: configuration.experiment_id,
      configuration_id: configuration.configuration_id,
      participant_instance_id: '123e4567-e89b-42d3-a456-426614174000',
      assigned_participant_id: configuration.assigned_participant_id,
      state: 'RUNNING',
      retained_from_sequence: '1',
      uploaded_through_sequence: '0',
      durable_through_sequence: '2',
      next_sequence_number: '3',
      first_sequence_number: '1',
      last_sequence_number: '2',
      event_count: '2',
      transitions: [
        {
          from: 'IMPORTED',
          to: 'CONFIG_VERIFIED',
          reason: 'CONFIGURATION_SIGNATURE_VERIFIED',
          time
        },
        {
          from: 'CONFIG_VERIFIED',
          to: 'CONSENT_PENDING',
          reason: 'CONSENT_REVIEW_OPENED',
          time
        },
        {
          from: 'CONSENT_PENDING',
          to: 'ACCESS_SETUP',
          reason: 'CONSENT_ACCEPTED',
          time
        },
        {
          from: 'ACCESS_SETUP',
          to: 'READY',
          reason: 'ACCESS_PREFLIGHT_PASSED',
          time
        },
        { from: 'READY', to: 'RUNNING', reason: 'PARTICIPANT_STARTED', time }
      ],
      events: [
        {
          sequence_number: '1',
          collector_id: 'app_lifecycle.v1',
          payload_schema_version: 1,
          observed_time: time,
          payload_type: 'ACTIVITY_CREATED',
          fields: { activity_class: 'tests.ProtocolFixtureActivity' }
        },
        {
          sequence_number: '2',
          collector_id: 'app_lifecycle.v1',
          payload_schema_version: 1,
          observed_time: time,
          payload_type: 'ACTIVITY_RESUMED',
          fields: { activity_class: 'tests.ProtocolFixtureActivity' }
        }
      ]
    }
  };

  const context = bundleContext(bundleId, digestHex, keyId);
  const wrapped = await wrap(
    CONTENT_KEY,
    decodeBase64Url(configuration.export.hpke_public_key, 32),
    context
  );
  const plaintext = canonicalBytes(options.document ? options.document(document) : document);
  const body = await aesGcm(CONTENT_KEY, NONCE, context, plaintext);
  const keyIdBytes = UTF8.encode(keyId);
  const out = new Uint8Array(70 + keyIdBytes.length + wrapped.length + body.length);
  out.set(UTF8.encode('PTCEXP01'));
  out.set(uuidBytes(bundleId), 8);
  out.set(digest, 24);
  new DataView(out.buffer).setUint16(56, keyIdBytes.length);
  out.set(NONCE, 58);
  out.set(keyIdBytes, 70);
  out.set(wrapped, 70 + keyIdBytes.length);
  out.set(body, 70 + keyIdBytes.length + wrapped.length);
  return out;
}

const KEM_ID = 0x0020;
const KDF_ID = 0x0001;
const AEAD_ID = 0x0002;
const i2osp2 = (value: number) => Uint8Array.of((value >> 8) & 0xff, value & 0xff);
const VERSION = UTF8.encode('HPKE-v1');
const SUITE_KEM = concat(UTF8.encode('KEM'), i2osp2(KEM_ID));
const SUITE_HPKE = concat(
  UTF8.encode('HPKE'), i2osp2(KEM_ID), i2osp2(KDF_ID), i2osp2(AEAD_ID)
);
const labeledExtract = (suite: Uint8Array, salt: Uint8Array, label: string, ikm: Uint8Array) =>
  extract(sha256, concat(VERSION, suite, UTF8.encode(label), ikm), salt);
const labeledExpand = (
  suite: Uint8Array,
  prk: Uint8Array,
  label: string,
  info: Uint8Array,
  length: number
) => expand(sha256, prk, concat(i2osp2(length), VERSION, suite, UTF8.encode(label), info), length);

async function wrap(
  plaintext: Uint8Array,
  recipientPublic: Uint8Array,
  info: Uint8Array
): Promise<Uint8Array> {
  const enc = x25519.getPublicKey(EPHEMERAL_PRIVATE);
  const dh = x25519.getSharedSecret(EPHEMERAL_PRIVATE, recipientPublic);
  const eaePrk = labeledExtract(SUITE_KEM, EMPTY, 'eae_prk', dh);
  const shared = labeledExpand(SUITE_KEM, eaePrk, 'shared_secret', concat(enc, recipientPublic), 32);
  const schedule = concat(
    Uint8Array.of(0),
    labeledExtract(SUITE_HPKE, EMPTY, 'psk_id_hash', EMPTY),
    labeledExtract(SUITE_HPKE, EMPTY, 'info_hash', info)
  );
  const secret = labeledExtract(SUITE_HPKE, shared, 'secret', EMPTY);
  const key = labeledExpand(SUITE_HPKE, secret, 'key', schedule, 32);
  const nonce = labeledExpand(SUITE_HPKE, secret, 'base_nonce', schedule, 12);
  return concat(enc, await aesGcm(key, nonce, EMPTY, plaintext));
}

async function aesGcm(
  rawKey: Uint8Array,
  nonce: Uint8Array,
  aad: Uint8Array,
  plaintext: Uint8Array
): Promise<Uint8Array> {
  const source = (value: Uint8Array): BufferSource => value as unknown as BufferSource;
  const key = await crypto.subtle.importKey('raw', source(rawKey), 'AES-GCM', false, ['encrypt']);
  return new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: source(nonce), additionalData: source(aad), tagLength: 128 },
      key,
      source(plaintext)
    )
  );
}

function uuidBytes(value: string): Uint8Array {
  return Uint8Array.from(value.replace(/-/g, '').match(/../g) ?? [], (byte) => parseInt(byte, 16));
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}
