/**
 * A `.adcexp`, built here, because nothing else in this repository can build one on demand.
 *
 * `tests/compat.spec.ts` gets its fixtures the honest way: it shells out to `researcher-tools` and
 * compares. That trick does not work for bundles — the CLI has a `decrypt` and no `encrypt`, because
 * the only thing that ever writes one of these is a participant's phone. Committing a real bundle is
 * not an option either: opening one needs the export *private* key, and `.gitignore` refuses
 * `*-private.json` and `*.adcexp` for exactly that reason.
 *
 * So the direction is inverted. This seals a bundle with the writer's half of the same recipe the
 * reader implements, and two different things then check it. `tests/bundle.spec.ts` opens it with
 * the site's reader, which proves the pair agree; `tests/compat.spec.ts` hands the same three files
 * to `researcher-tools decrypt`, which proves the pair agree *with the JVM* — and that second one is
 * the claim that matters, because a writer and a reader that are wrong the same way would pass the
 * first on their own.
 *
 * A key exists here only for the length of a test, and only in memory or a `mkdtemp` directory.
 */

import { canonicalize } from '../src/lib/adc/canonical';
import { bundleContext } from '../src/lib/adc/bundle';
import { hpkePublicKey } from '../src/lib/adc/tink';
import type { StudyConfiguration, TinkKeyset } from '../src/lib/adc/types';
import { x25519 } from '@noble/curves/ed25519.js';
import { expand, extract } from '@noble/hashes/hkdf.js';
import { sha256 } from '@noble/hashes/sha2.js';

const encoder = new TextEncoder();
const utf8 = (text: string) => encoder.encode(text);
const EMPTY = new Uint8Array(0);
const buffer = (bytes: Uint8Array): BufferSource => bytes as unknown as BufferSource;

const i2osp2 = (value: number) => Uint8Array.of((value >> 8) & 0xff, value & 0xff);
const SUITE_KEM = concat(utf8('KEM'), i2osp2(0x0020));
const SUITE_HPKE = concat(utf8('HPKE'), i2osp2(0x0020), i2osp2(0x0001), i2osp2(0x0002));
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

export interface SealOptions {
  /** The name the header carries, when a test needs it to disagree with the configuration. */
  keyId?: string;
  /** The keyset to seal to, when a test needs a bundle the study's own key cannot open. */
  keyset?: TinkKeyset;
}

/**
 * `ResearchExport.encrypt`, in reverse of the reader: a random content key sealed to the study's
 * HPKE public key with the context as `info`, then the body under that key with the same context as
 * AAD, behind the 26-byte header.
 */
export async function seal(
  configuration: StudyConfiguration,
  plaintext: string,
  options: SealOptions = {}
): Promise<Uint8Array> {
  const keyset = options.keyset ?? configuration.export.tink_hpke_public_keyset;
  const recipient = hpkePublicKey(keyset);
  if (!recipient) throw new Error('seal_keyset');
  const context = bundleContext(configuration);

  const contentKey = crypto.getRandomValues(new Uint8Array(32));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const wrapped = await wrap(recipient, keyset.primaryKeyId, contentKey, context);

  const key = await crypto.subtle.importKey('raw', buffer(contentKey), 'AES-GCM', false, [
    'encrypt'
  ]);
  const body = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: buffer(nonce), tagLength: 128, additionalData: buffer(context) },
      key,
      buffer(utf8(plaintext))
    )
  );

  const keyId = utf8(options.keyId ?? configuration.export.researcher_key_id);
  const header = new Uint8Array(26);
  header.set(utf8('ADCEXP01'));
  const view = new DataView(header.buffer);
  view.setUint16(8, keyId.length);
  view.setInt32(10, wrapped.length);
  header.set(nonce, 14);
  return concat(header, keyId, wrapped, body);
}

/** RFC 9180 base-mode seal, with Tink's 5-byte `TINK` prefix in front of it. */
async function wrap(
  recipientPublic: Uint8Array,
  keyId: number,
  contentKey: Uint8Array,
  info: Uint8Array
): Promise<Uint8Array> {
  const ephemeral = x25519.utils.randomSecretKey();
  const enc = x25519.getPublicKey(ephemeral);
  const dh = x25519.getSharedSecret(ephemeral, recipientPublic);
  const eaePrk = labeledExtract(SUITE_KEM, EMPTY, 'eae_prk', dh);
  const shared = labeledExpand(SUITE_KEM, eaePrk, 'shared_secret', concat(enc, recipientPublic), 32);
  const schedule = concat(
    Uint8Array.of(0),
    labeledExtract(SUITE_HPKE, EMPTY, 'psk_id_hash', EMPTY),
    labeledExtract(SUITE_HPKE, EMPTY, 'info_hash', info)
  );
  const secret = labeledExtract(SUITE_HPKE, shared, 'secret', EMPTY);
  const key = await crypto.subtle.importKey(
    'raw',
    buffer(labeledExpand(SUITE_HPKE, secret, 'key', schedule, 32)),
    'AES-GCM',
    false,
    ['encrypt']
  );
  const baseNonce = labeledExpand(SUITE_HPKE, secret, 'base_nonce', schedule, 12);
  const sealed = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: buffer(baseNonce), tagLength: 128, additionalData: buffer(EMPTY) },
      key,
      buffer(contentKey)
    )
  );
  const prefix = new Uint8Array(5);
  prefix[0] = 1;
  new DataView(prefix.buffer).setUint32(1, keyId);
  return concat(prefix, enc, sealed);
}

/**
 * A plaintext in the shape `JsonWriter` emits it, key order included. `configuration` is the study's
 * own canonical JSON inlined verbatim, which is what makes the bundle self-describing.
 */
export function bundleJson(
  configuration: StudyConfiguration,
  experiment: {
    participantInstanceId?: string;
    assignedParticipantId?: string | null;
    state?: string;
    events?: number;
    firstSequenceNumber?: number;
    lifetime?: number;
    collectors?: readonly string[];
  } = {}
): string {
  const first = experiment.firstSequenceNumber ?? 1;
  const count = experiment.events ?? 0;
  const collectors = experiment.collectors ?? ['app_lifecycle.v1', 'accelerometer.v1'];
  const events = Array.from({ length: count }, (_unused, index) => ({
    sequence_number: first + index,
    collector_id: collectors[index % collectors.length],
    payload_schema_version: 1,
    observed_time: {
      wall_time_utc_millis: 1_762_000_000_000 + index * 1_000,
      elapsed_realtime_nanos: 2_000_000_000 + index * 1_000_000,
      boot_session_id: 'boot-a1b2c3'
    },
    payload_type: 'SENSOR_SAMPLE',
    // Strings, every one of them, as the wire format has it — a number here would be a bundle no
    // phone ever wrote.
    fields: { x: '0.2', y: '-1.2', z: '9.82' }
  }));
  const last = count === 0 ? 0 : first + count - 1;
  return JSON.stringify({
    format: 'research-bundle-v1',
    exported_at_utc_millis: 1_762_000_100_000,
    configuration: JSON.parse(canonicalize(configuration)),
    experiment: {
      experiment_id: configuration.experiment_id,
      configuration_id: configuration.configuration_id,
      participant_instance_id:
        experiment.participantInstanceId ?? '00000000-0000-4000-8000-000000000017',
      // Written only when the study assigns one, so an anonymous bundle has no such key at all.
      ...(experiment.assignedParticipantId
        ? { assigned_participant_id: experiment.assignedParticipantId }
        : {}),
      state: experiment.state ?? 'RUNNING',
      next_sequence_number: (experiment.lifetime ?? last) + 1,
      transitions: [
        {
          from: 'IMPORTED',
          to: 'CONFIG_VERIFIED',
          reason: 'CONFIGURATION_SIGNATURE_VERIFIED',
          time: {
            wall_time_utc_millis: 1_762_000_000_000,
            elapsed_realtime_nanos: 2_000_000_000,
            boot_session_id: 'boot-a1b2c3'
          }
        }
      ],
      events,
      first_sequence_number: first,
      last_sequence_number: last
    }
  });
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
