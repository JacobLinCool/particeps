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
  collectors: [
    {
      config: { maximum_report_latency_us: 1000000, sampling_period_us: 100000 },
      id: 'accelerometer.v1',
      required: false
    },
    { config: {}, id: 'app_lifecycle.v1', required: true }
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
const documentValue = {
  bundle_id: bundleId,
  bundle_kind: 'automatic_upload',
  configuration,
  configuration_sha256: hex(configurationSha),
  configuration_signature: { signature: base64url(signature), signer_key_id: 'vector-signer' },
  experiment: {
    assigned_participant_id: null,
    configuration_id: 'vector-config',
    durable_through_sequence: '1',
    event_count: '1',
    events: [{
      collector_id: 'app_lifecycle.v1',
      fields: { activity_class: 'vector.Activity' },
      observed_time: {
        boot_session_id: 'boot-vector',
        monotonic_time_nanos: '2000',
        wall_time_utc_millis: '1000'
      },
      payload_schema_version: 1,
      payload_type: 'ACTIVITY_CREATED',
      sequence_number: '1'
    }],
    experiment_id: 'vector-study',
    first_sequence_number: '1',
    last_sequence_number: '1',
    next_sequence_number: '2',
    participant_instance_id: '00000000-0000-4000-8000-000000000017',
    retained_from_sequence: '1',
    state: 'RUNNING',
    transitions: [
      {
        from: 'IMPORTED',
        reason: 'CONFIGURATION_SIGNATURE_VERIFIED',
        time: { boot_session_id: 'boot-vector', monotonic_time_nanos: '100', wall_time_utc_millis: '100' },
        to: 'CONFIG_VERIFIED'
      },
      {
        from: 'CONFIG_VERIFIED',
        reason: 'CONSENT_REVIEW_OPENED',
        time: { boot_session_id: 'boot-vector', monotonic_time_nanos: '200', wall_time_utc_millis: '200' },
        to: 'CONSENT_PENDING'
      },
      {
        from: 'CONSENT_PENDING',
        reason: 'CONSENT_ACCEPTED',
        time: { boot_session_id: 'boot-vector', monotonic_time_nanos: '300', wall_time_utc_millis: '300' },
        to: 'ACCESS_SETUP'
      },
      {
        from: 'ACCESS_SETUP',
        reason: 'ACCESS_PREFLIGHT_PASSED',
        time: { boot_session_id: 'boot-vector', monotonic_time_nanos: '400', wall_time_utc_millis: '400' },
        to: 'READY'
      },
      {
        from: 'READY',
        reason: 'PARTICIPANT_STARTED',
        time: { boot_session_id: 'boot-vector', monotonic_time_nanos: '500', wall_time_utc_millis: '500' },
        to: 'RUNNING'
      }
    ],
    uploaded_through_sequence: '0'
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
  configuration_sha256: hex(configurationSha),
  event_count: '1',
  first_sequence_number: '1',
  last_sequence_number: '1',
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
const withRangeCountMismatch = await semanticBundle((value) => {
  value.experiment.event_count = '2';
  value.experiment.last_sequence_number = '2';
});
const withSequenceGap = await semanticBundle((value) => {
  const second = clone(value.experiment.events[0]);
  second.sequence_number = '3';
  value.experiment.events.push(second);
  value.experiment.durable_through_sequence = '3';
  value.experiment.event_count = '2';
  value.experiment.last_sequence_number = '3';
  value.experiment.next_sequence_number = '4';
});
const withUnknownCollector = await semanticBundle((value) => {
  value.experiment.events[0].collector_id = 'unknown.v1';
});
const withUnknownPayload = await semanticBundle((value) => {
  value.experiment.events[0].payload_type = 'UNKNOWN_PAYLOAD';
});
const withUnknownPayloadSchema = await semanticBundle((value) => {
  value.experiment.events[0].payload_schema_version = 2;
});
const withUnknownEventField = await semanticBundle((value) => {
  value.experiment.events[0].fields.unexpected = 'value';
});
const withOversizedEventField = await semanticBundle((value) => {
  value.experiment.events[0].fields.activity_class = 'x'.repeat(513);
});
const sensorFloatBundle = (floatValue) => semanticBundle((value) => {
  value.experiment.events[0] = {
    collector_id: 'accelerometer.v1',
    fields: {
      accuracy: '3',
      source_elapsed_realtime_nanos: '2000',
      x_meters_per_second_squared: floatValue,
      y_meters_per_second_squared: '0',
      z_meters_per_second_squared: '9.81'
    },
    observed_time: {
      boot_session_id: 'boot-vector',
      monotonic_time_nanos: '2000',
      wall_time_utc_millis: '1000'
    },
    payload_schema_version: 1,
    payload_type: 'ACCELEROMETER_SAMPLE',
    sequence_number: '1'
  };
});
const withNonfiniteSensor = await sensorFloatBundle('NaN');
const withEmptySensorFloat = await sensorFloatBundle('');
const withWhitespaceSensorFloat = await sensorFloatBundle(' ');
const withHexSensorFloat = await sensorFloatBundle('0x10');
const withBinarySensorFloat = await sensorFloatBundle('0b10');
const batteryBundle = (percentage) => semanticBundle((value) => {
  value.experiment.events[0] = {
    collector_id: 'battery_state.v1',
    fields: {
      charging_source: 'NONE',
      charging_state: 'DISCHARGING',
      percentage,
      power_save_enabled: 'false'
    },
    observed_time: {
      boot_session_id: 'boot-vector',
      monotonic_time_nanos: '2000',
      wall_time_utc_millis: '1000'
    },
    payload_schema_version: 1,
    payload_type: 'BATTERY_STATE',
    sequence_number: '1'
  };
});
const withNoncanonicalInt32 = await batteryBundle('-0');
const withOutOfRangeInt32 = await batteryBundle('101');
const withInvalidTransitions = await semanticBundle((value) => {
  value.experiment.transitions[4].reason = 'PARTICIPANT_PAUSED';
});
const withLongBootSession = await semanticBundle((value) => {
  value.experiment.events[0].observed_time.boot_session_id = 'b'.repeat(129);
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
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'nonintegral_number', id: 'config-nonintegral-duration', input_hex: hex(configurationVariant((value) => { value.duration_hours = 1.5; })) },
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'int64_overflow', id: 'config-client-version-overflow', input_hex: hex(configurationVariant((value) => { value.minimum_client_version = '9223372036854775808'; })) },
    { category: 'integral_bounds', entrypoint: 'configuration_jcs', expected_failure: 'physical_bound', id: 'config-zero-duration', input_hex: hex(configurationVariant((value) => { value.duration_hours = 0; })) },
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
    { category: 'range_count', entrypoint: 'bundle', expected_failure: 'event_count', id: 'bundle-range-count-mismatch', input_hex: hex(withRangeCountMismatch) },
    { category: 'range_count', entrypoint: 'bundle', expected_failure: 'noncontiguous_sequence', id: 'bundle-sequence-gap', input_hex: hex(withSequenceGap) },
    { category: 'catalog_contract', entrypoint: 'bundle', expected_failure: 'unknown_collector', id: 'bundle-unknown-collector', input_hex: hex(withUnknownCollector) },
    { category: 'unknown_payload', entrypoint: 'bundle', expected_failure: 'unknown_payload', id: 'bundle-unknown-payload', input_hex: hex(withUnknownPayload) },
    { category: 'unknown_payload', entrypoint: 'bundle', expected_failure: 'unknown_payload_schema', id: 'bundle-unknown-payload-schema', input_hex: hex(withUnknownPayloadSchema) },
    { category: 'unknown_field', entrypoint: 'bundle', expected_failure: 'unknown_event_field', id: 'bundle-unknown-event-field', input_hex: hex(withUnknownEventField) },
    { category: 'catalog_contract', entrypoint: 'bundle', expected_failure: 'field_bound', id: 'bundle-oversized-event-field', input_hex: hex(withOversizedEventField) },
    { category: 'nonfinite_sensor', entrypoint: 'bundle', expected_failure: 'nonfinite_float', id: 'bundle-nonfinite-sensor', input_hex: hex(withNonfiniteSensor) },
    { category: 'float_grammar', entrypoint: 'bundle', expected_failure: 'empty_float', id: 'bundle-empty-sensor-float', input_hex: hex(withEmptySensorFloat) },
    { category: 'float_grammar', entrypoint: 'bundle', expected_failure: 'whitespace_float', id: 'bundle-whitespace-sensor-float', input_hex: hex(withWhitespaceSensorFloat) },
    { category: 'float_grammar', entrypoint: 'bundle', expected_failure: 'hex_float', id: 'bundle-hex-sensor-float', input_hex: hex(withHexSensorFloat) },
    { category: 'float_grammar', entrypoint: 'bundle', expected_failure: 'binary_float', id: 'bundle-binary-sensor-float', input_hex: hex(withBinarySensorFloat) },
    { category: 'catalog_contract', entrypoint: 'bundle', expected_failure: 'noncanonical_int32', id: 'bundle-noncanonical-int32', input_hex: hex(withNoncanonicalInt32) },
    { category: 'catalog_contract', entrypoint: 'bundle', expected_failure: 'int32_field_bound', id: 'bundle-int32-field-bound', input_hex: hex(withOutOfRangeInt32) },
    { category: 'catalog_contract', entrypoint: 'bundle', expected_failure: 'transition_chain', id: 'bundle-invalid-transition', input_hex: hex(withInvalidTransitions) },
    { category: 'catalog_contract', entrypoint: 'bundle', expected_failure: 'boot_session_bound', id: 'bundle-long-boot-session', input_hex: hex(withLongBootSession) },
    { category: 'hpke_context', entrypoint: 'bundle_unwrap_context', expected_failure: 'hpke_authentication', id: 'bundle-wrong-context', input_hex: hex(bytes({ ...contextValue, bundle_id: '00000000-0000-4000-8000-000000000098' })) },
    { category: 'trailing_bytes', entrypoint: 'receipt', expected_failure: 'noncanonical_json', id: 'receipt-leading-whitespace', input_hex: hex(concat(UTF8.encode(' '), receiptBytes)) },
    { category: 'integral_bounds', entrypoint: 'receipt', expected_failure: 'noncanonical_decimal', id: 'receipt-leading-zero', input_hex: hex(UTF8.encode(canonical(receiptValue).replace('"first_sequence_number":"1"', '"first_sequence_number":"01"'))) },
    { category: 'integral_bounds', entrypoint: 'receipt', expected_failure: 'wrong_type', id: 'receipt-numeric-count', input_hex: hex(UTF8.encode(canonical(receiptValue).replace('"event_count":"1"', '"event_count":1'))) },
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
        'X-Particeps-Configuration-SHA256',
        'X-Particeps-Event-Count',
        'X-Particeps-Researcher-Key-Id',
        'X-Particeps-Sequence-From',
        'X-Particeps-Sequence-To'
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
