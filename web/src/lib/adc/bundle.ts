/**
 * Opening a `.adcexp` — the file a phone hands back at the end of a study.
 *
 * This is the only reader on the site: everything else in `lib/adc` writes files for the app and
 * the CLI to read. `researcher-tools decrypt` is the other implementation of this function, and it
 * takes the same three inputs for the same reason — the bundle, the study configuration, and the
 * export private key. The configuration is not a convenience. Its `experiment_id`,
 * `configuration_id` and `export.researcher_key_id` are the AAD the body was sealed under and the
 * `info` the content key was wrapped under, and none of the three is anywhere in the file's
 * cleartext except the last. A personalised study issues one configuration per participant, so the
 * tag only verifies against *that* participant's file.
 *
 * The CLI stages its output and writes nothing until the tag verifies. The browser gets the same
 * guarantee for free and takes it: the body is one AES-GCM stream with one tag at the end —
 * `DECRYPT_CHUNK_BYTES` on the Kotlin side is a read buffer, not a frame size — so a single
 * `subtle.decrypt` covers the whole document, and WebCrypto verifies the tag before it resolves.
 * Nothing here returns a document that has not been authenticated.
 *
 * Every way this can fail returns a name rather than throwing one. A researcher holding last
 * month's key and this month's bundle has made a mistake with a precise fix, and "decryption
 * failed" is the one answer that does not tell them which of the three files to change.
 */

import { hpkePublicKey, readHpkePrivateKeyset } from './tink';
import type { StudyConfiguration, TinkKeyset } from './types';
import { x25519 } from '@noble/curves/ed25519.js';
import { expand, extract } from '@noble/hashes/hkdf.js';
import { sha256 } from '@noble/hashes/sha2.js';

/* ---- the container ------------------------------------------------------------------------- */

const MAGIC = 'ADCEXP01';

/** magic 8 | uint16 keyIdLen | int32 wrappedKeyLen | nonce 12. Everything after it is variable. */
const HEADER_BYTES = 26;

const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const CONTENT_KEY_BYTES = 32;

/** Tink's `TINK` output prefix: `0x01` then the key id, big-endian. */
const PREFIX_BYTES = 5;
const ENCAPSULATED_BYTES = 32;

/** `ResearchExport.decrypt`'s own bounds, so a length that lies is refused rather than allocated. */
const MINIMUM_KEY_ID_BYTES = 3;
const MAXIMUM_KEY_ID_BYTES = 64;
const MINIMUM_WRAPPED_BYTES = 32;
const MAXIMUM_WRAPPED_BYTES = 16_384;

/**
 * WebCrypto has no streaming AEAD, so the ciphertext and the plaintext are both resident at once
 * and a bundle is bounded by what a tab can hold rather than by what a phone can write —
 * `storage.maximum_local_bytes` reaches 8 GiB. Refusing at a stated size is a sentence a researcher
 * can act on; a `RangeError` out of `subtle.decrypt`, or a tab that stops responding, is not.
 */
export const MAXIMUM_BUNDLE_BYTES = 268_435_456;

/* ---- what comes out ------------------------------------------------------------------------ */

/**
 * The phone's clock, its uptime, and which boot the uptime is measured from. `elapsed_realtime_nanos`
 * passes `Number.MAX_SAFE_INTEGER` after about 104 days of uptime, so it is carried and shown and
 * never used in arithmetic.
 */
export interface ResearchTime {
  wall_time_utc_millis: number;
  elapsed_realtime_nanos: number;
  boot_session_id: string;
}

/** `fields` is string→string in every event, including the ones whose values look like numbers. */
export interface ResearchEvent {
  sequence_number: number;
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
  /** The key is absent in an anonymous study, so absent and null are the same answer here. */
  assigned_participant_id: string | null;
  state: string;
  next_sequence_number: number;
  transitions: ResearchTransition[];
  events: ResearchEvent[];
  /**
   * The window this file carries, which is not always the whole study: a scheduled upload sends a
   * slice, and `next_sequence_number - 1` is what the device has recorded in its lifetime.
   */
  first_sequence_number: number;
  last_sequence_number: number;
}

export const BUNDLE_FORMAT = 'research-bundle-v1';

export interface ResearchDocument {
  format: string;
  exported_at_utc_millis: number;
  /**
   * The study's own canonical JSON, verbatim. Nothing here reads it: the tag verified under a
   * context derived from the configuration the caller supplied, which is already the proof that the
   * two are the same study.
   */
  configuration: unknown;
  experiment: ResearchExperiment;
}

export interface ResearchBundle {
  /** `export.researcher_key_id`, as the file names it. */
  keyId: string;
  document: ResearchDocument;
  /** The decrypted JSON exactly as the phone wrote it, which is what `--output` would contain. */
  text: string;
  bytes: number;
}

/**
 * Why nothing opened, in the order the checks run. Each one names a different file to change.
 *
 * The last two are a real distinction rather than two words for one failure, and which one comes
 * back says where the mismatch is. The context binds the wrap *and* the body, and the wrap is
 * opened first — so a configuration whose `experiment_id` or `configuration_id` is not the one this
 * file was sealed under fails as `unwrap_failed`, which is the personalised-study case of holding
 * another participant's configuration. `tag_failed` can only happen once the context and the key
 * have both already proved correct, so it means the bytes changed after the phone wrote them.
 */
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

/**
 * A `Uint8Array` is not a `BufferSource` to TypeScript, because a view could sit over a
 * `SharedArrayBuffer` and WebCrypto refuses those. None of these ever does — every array here comes
 * from a `File`, a `TextEncoder`, or `@noble` — so the narrowing is stated once instead of at each
 * of the four calls that would otherwise carry the same cast.
 */
const source = (bytes: Uint8Array): BufferSource => bytes as unknown as BufferSource;

/* ---- the read ------------------------------------------------------------------------------ */

/**
 * The context both layers are bound to: the wrap's RFC 9180 `info`, and the body's AES-GCM AAD.
 * The same bytes in two different roles, which is the one thing about this format that is easy to
 * get backwards — a reader that passes it as the wrap's *associated data* instead fails with an
 * `OperationError` indistinguishable from the wrong key.
 */
export function bundleContext(configuration: StudyConfiguration): Uint8Array {
  return utf8(
    `${BUNDLE_FORMAT}:${configuration.experiment_id}:${configuration.configuration_id}:` +
      configuration.export.researcher_key_id
  );
}

export async function openBundle(
  bytes: Uint8Array,
  configuration: StudyConfiguration,
  privateKeyset: TinkKeyset
): Promise<BundleResult> {
  if (bytes.length > MAXIMUM_BUNDLE_BYTES) return failed('too_large');
  if (bytes.length < HEADER_BYTES) return failed('not_a_bundle');
  for (let index = 0; index < MAGIC.length; index += 1) {
    if (bytes[index] !== MAGIC.charCodeAt(index)) return failed('not_a_bundle');
  }

  const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const keyIdLength = header.getUint16(8);
  // Signed, as the Java writer wrote it, and range-checked before it is used as a length: read
  // unsigned, a hostile `0xffffffff` becomes four gigabytes of subarray.
  const wrappedLength = header.getInt32(10);
  if (keyIdLength < MINIMUM_KEY_ID_BYTES || keyIdLength > MAXIMUM_KEY_ID_BYTES) {
    return failed('not_a_bundle');
  }
  if (wrappedLength < MINIMUM_WRAPPED_BYTES || wrappedLength > MAXIMUM_WRAPPED_BYTES) {
    return failed('not_a_bundle');
  }
  const bodyAt = HEADER_BYTES + keyIdLength + wrappedLength;
  if (bytes.length <= bodyAt + TAG_BYTES) return failed('not_a_bundle');

  const nonce = bytes.subarray(14, 14 + NONCE_BYTES);
  const keyId = new TextDecoder().decode(bytes.subarray(HEADER_BYTES, HEADER_BYTES + keyIdLength));
  const wrapped = bytes.subarray(HEADER_BYTES + keyIdLength, bodyAt);
  const body = bytes.subarray(bodyAt);

  // Before any crypto, exactly as the CLI does it. Bundle and configuration from two different
  // studies is the commonest mistake of the three, and this is the only check that can name it.
  if (keyId !== configuration.export.researcher_key_id) return failed('wrong_study');

  const recipient = readHpkePrivateKeyset(privateKeyset);
  if (!recipient) return failed('wrong_key');
  // The 5-byte prefix names the key that sealed this. A mismatch here is last month's key file, and
  // saying so costs one comparison; letting it through costs an HPKE failure with no name.
  const prefix = new DataView(wrapped.buffer, wrapped.byteOffset, wrapped.byteLength);
  if (wrapped[0] !== 1 || prefix.getUint32(1) !== recipient.keyId) return failed('wrong_key');
  // Deliberately stricter than `ResearchExport.decrypt`, which never looks at the configuration's
  // keyset on the read path. `researcher_key_id` is derived from the public key, so a configuration
  // naming a different key was already refused above; what is left is a configuration whose own two
  // halves disagree — a key id from one keyset beside the bytes of another, which only a hand-edited
  // or corrupted file has. Nothing this site or the CLI writes can reach here, and a study whose
  // declared key cannot be the one that sealed the bundle is worth saying so rather than letting
  // HPKE fail with no name.
  const study = hpkePublicKey(configuration.export.tink_hpke_public_keyset);
  if (!study || !same(study, x25519.getPublicKey(recipient.scalar))) return failed('wrong_key');

  const context = bundleContext(configuration);
  const contentKey = await unwrap(wrapped, recipient.publicKey, recipient.scalar, context);
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

  const text = new TextDecoder().decode(plaintext);
  const document = readDocument(text);
  return document
    ? { ok: true, bundle: { keyId, document, text, bytes: plaintext.length } }
    : failed('unreadable');
}

/* ---- RFC 9180, base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM -------------
 *
 * The one suite Tink's `HpkeCrypto.validateParameters` accepts and the one this site writes, so
 * there is nothing to negotiate and no algorithm agility to implement. `mode` is 0 and the sequence
 * number is 0, which makes the AEAD nonce `base_nonce` unchanged.
 * ------------------------------------------------------------------------------------------- */

const KEM_ID = 0x0020;
const KDF_ID = 0x0001;
const AEAD_ID = 0x0002;

const encoder = new TextEncoder();
const utf8 = (text: string) => encoder.encode(text);
const EMPTY = new Uint8Array(0);

const i2osp2 = (value: number) => Uint8Array.of((value >> 8) & 0xff, value & 0xff);

const SUITE_KEM = concat(utf8('KEM'), i2osp2(KEM_ID));
const SUITE_HPKE = concat(utf8('HPKE'), i2osp2(KEM_ID), i2osp2(KDF_ID), i2osp2(AEAD_ID));
const VERSION = utf8('HPKE-v1');

const labeledExtract = (suite: Uint8Array, salt: Uint8Array, label: string, ikm: Uint8Array) =>
  extract(sha256, concat(VERSION, suite, utf8(label), ikm), salt);

const labeledExpand = (
  suite: Uint8Array,
  prk: Uint8Array,
  label: string,
  info: Uint8Array,
  length: number
) => expand(sha256, prk, concat(i2osp2(length), VERSION, suite, utf8(label), info), length);

/**
 * The content key out of `HybridEncrypt`'s output. `info` is the raw context — Tink passes
 * `contextInfo` straight into the key schedule, and the output prefix is no part of it — while the
 * sealed key's own associated data is empty.
 */
async function unwrap(
  wrapped: Uint8Array,
  recipientPublic: Uint8Array,
  scalar: Uint8Array,
  info: Uint8Array
): Promise<Uint8Array | null> {
  const enc = wrapped.subarray(PREFIX_BYTES, PREFIX_BYTES + ENCAPSULATED_BYTES);
  const sealed = wrapped.subarray(PREFIX_BYTES + ENCAPSULATED_BYTES);
  if (enc.length !== ENCAPSULATED_BYTES || sealed.length <= TAG_BYTES) return null;
  try {
    // Refuses a shared secret that is all zeroes, which is the low-order-point case RFC 9180
    // requires an implementation to reject.
    const dh = x25519.getSharedSecret(scalar, enc);
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

/* ---- the document -------------------------------------------------------------------------- */

/**
 * Structural, and only as deep as anything reads. A summary that maps over `events` cannot be shown
 * a `null` there and recover, and a document that fails this is one no version of this page wrote —
 * so it is refused with a name rather than half-rendered. Unknown fields are carried: this reads a
 * format the phone owns, and refusing a field somebody added would break every future bundle.
 */
function readDocument(text: string): ResearchDocument | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  const root = record(parsed);
  if (!root || root.format !== BUNDLE_FORMAT) return null;
  if (!isNumber(root.exported_at_utc_millis)) return null;
  if (!record(root.configuration)) return null;

  const source = record(root.experiment);
  if (!source) return null;
  if (
    !isString(source.experiment_id) ||
    !isString(source.configuration_id) ||
    !isString(source.participant_instance_id) ||
    !isString(source.state) ||
    !isNumber(source.next_sequence_number) ||
    !isNumber(source.first_sequence_number) ||
    !isNumber(source.last_sequence_number)
  ) {
    return null;
  }
  const assigned = source.assigned_participant_id;
  if (assigned !== undefined && assigned !== null && !isString(assigned)) return null;
  if (!Array.isArray(source.events) || !Array.isArray(source.transitions)) return null;

  const events: ResearchEvent[] = [];
  for (const raw of source.events) {
    const event = readEvent(raw);
    if (!event) return null;
    events.push(event);
  }
  const transitions: ResearchTransition[] = [];
  for (const raw of source.transitions) {
    const transition = readTransition(raw);
    if (!transition) return null;
    transitions.push(transition);
  }

  return {
    format: root.format,
    exported_at_utc_millis: root.exported_at_utc_millis,
    configuration: root.configuration,
    experiment: {
      experiment_id: source.experiment_id,
      configuration_id: source.configuration_id,
      participant_instance_id: source.participant_instance_id,
      assigned_participant_id: isString(assigned) ? assigned : null,
      state: source.state,
      next_sequence_number: source.next_sequence_number,
      transitions,
      events,
      first_sequence_number: source.first_sequence_number,
      last_sequence_number: source.last_sequence_number
    }
  };
}

function readEvent(raw: unknown): ResearchEvent | null {
  const source = record(raw);
  if (!source) return null;
  const time = readTime(source.observed_time);
  const fields = record(source.fields);
  if (!time || !fields) return null;
  if (
    !isNumber(source.sequence_number) ||
    !isString(source.collector_id) ||
    !isNumber(source.payload_schema_version) ||
    !isString(source.payload_type)
  ) {
    return null;
  }
  // Always strings on the wire, including a survey submission, which arrives as JSON *text*.
  for (const value of Object.values(fields)) if (!isString(value)) return null;
  return {
    sequence_number: source.sequence_number,
    collector_id: source.collector_id,
    payload_schema_version: source.payload_schema_version,
    observed_time: time,
    payload_type: source.payload_type,
    fields: fields as Record<string, string>
  };
}

function readTransition(raw: unknown): ResearchTransition | null {
  const source = record(raw);
  if (!source) return null;
  const time = readTime(source.time);
  if (!time || !isString(source.from) || !isString(source.to) || !isString(source.reason)) {
    return null;
  }
  return { from: source.from, to: source.to, reason: source.reason, time };
}

function readTime(raw: unknown): ResearchTime | null {
  const source = record(raw);
  if (!source) return null;
  if (
    !isNumber(source.wall_time_utc_millis) ||
    !isNumber(source.elapsed_realtime_nanos) ||
    !isString(source.boot_session_id)
  ) {
    return null;
  }
  return {
    wall_time_utc_millis: source.wall_time_utc_millis,
    elapsed_realtime_nanos: source.elapsed_realtime_nanos,
    boot_session_id: source.boot_session_id
  };
}

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

const isString = (value: unknown): value is string => typeof value === 'string';
const isNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);
