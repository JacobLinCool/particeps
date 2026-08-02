/**
 * The HPKE keyset pair, written the way Tink writes it.
 *
 * Tink's JSON keyset is a thin wrapper around a serialised `HpkePrivateKey` / `HpkePublicKey`
 * protobuf, and the app hands the public half straight to `TinkJsonProtoKeysetFormat`. There is no
 * protobuf runtime here: the two messages have four fields between them, all of them known, so
 * they are emitted directly. `researcher-tools/examples/INSECURE-demo-hpke-private.json` and the
 * keyset inside `demo-study.json` are a real pair from the shipped CLI, and `tests/crypto.spec.ts`
 * rebuilds both from the demo private scalar and compares the JSON text.
 *
 * Key order in the objects below is load-bearing. `tink_hpke_public_keyset` is re-emitted by the
 * canonicaliser in the order it was built, so a keyset assembled with its fields in another order
 * canonicalises to different bytes than the same keyset from the CLI.
 */

import { decodeBase64, encodeBase64 } from './crypto';
import type { TinkKeyset } from './types';
import { x25519 } from '@noble/curves/ed25519.js';

export interface HpkeKeyset {
  publicKeyset: TinkKeyset;
  privateKeyset: TinkKeyset;
}

const PRIVATE_TYPE_URL = 'type.googleapis.com/google.crypto.tink.HpkePrivateKey';
const PUBLIC_TYPE_URL = 'type.googleapis.com/google.crypto.tink.HpkePublicKey';

/** `HpkeParams { kem: DHKEM_X25519_HKDF_SHA256, kdf: HKDF_SHA256, aead: AES_256_GCM }`. */
const PARAMS = Uint8Array.of(0x08, 0x01, 0x10, 0x01, 0x18, 0x02);

const PRIVATE_KEY_BYTES = 32;
const MAXIMUM_KEY_ID = 0xffff_ffff;

export function generateHpkeKeyset(): HpkeKeyset {
  return hpkeKeysetFromPrivateKey(generatePrivateKey(), randomKeyId());
}

/**
 * The deterministic half of {@link generateHpkeKeyset}, so a known scalar can be checked against a
 * known keyset. The public key is the X25519 base-point multiplication of the scalar; Tink stores
 * the scalar unclamped and clamps on use, so the bytes here are the scalar exactly as given.
 */
export function hpkeKeysetFromPrivateKey(privateKey: Uint8Array, keyId: number): HpkeKeyset {
  if (privateKey.length !== PRIVATE_KEY_BYTES) throw new Error('hpke_private_key_length');
  if (!Number.isInteger(keyId) || keyId < 1 || keyId > MAXIMUM_KEY_ID) {
    throw new Error('hpke_key_id');
  }
  const publicKey = concat(
    lengthDelimited(2, PARAMS),
    lengthDelimited(3, x25519.getPublicKey(privateKey))
  );
  return {
    publicKeyset: keyset(PUBLIC_TYPE_URL, publicKey, 'ASYMMETRIC_PUBLIC', keyId),
    privateKeyset: keyset(
      PRIVATE_TYPE_URL,
      concat(lengthDelimited(2, publicKey), lengthDelimited(3, privateKey)),
      'ASYMMETRIC_PRIVATE',
      keyId
    )
  };
}

function keyset(
  typeUrl: string,
  value: Uint8Array,
  keyMaterialType: string,
  keyId: number
): TinkKeyset {
  return {
    primaryKeyId: keyId,
    key: [
      {
        keyData: { typeUrl, value: encodeBase64(value), keyMaterialType },
        status: 'ENABLED',
        keyId,
        outputPrefixType: 'TINK'
      }
    ]
  };
}

/**
 * Tink's `X25519.generatePrivateKey`: 32 random bytes with the three bits RFC 7748 clamping
 * overwrites set to the opposite of what clamping would leave, so a scalar used without clamping
 * fails loudly instead of interoperating by luck one time in eight.
 */
function generatePrivateKey(): Uint8Array {
  const key = crypto.getRandomValues(new Uint8Array(PRIVATE_KEY_BYTES));
  key[0] |= 7;
  key[31] &= 63;
  key[31] |= 128;
  return key;
}

/** Tink's `Util.randKeyId`: any 32-bit pattern but zero, written unsigned in the JSON keyset. */
function randomKeyId(): number {
  const buffer = new Uint32Array(1);
  do {
    crypto.getRandomValues(buffer);
  } while (buffer[0] === 0);
  return buffer[0];
}

/** Field 1 of both messages is `version`, which is 0, and proto3 omits scalar defaults. */
function lengthDelimited(fieldNumber: number, value: Uint8Array): number[] {
  return [(fieldNumber << 3) | 2, ...varint(value.length), ...value];
}

function varint(value: number): number[] {
  const bytes: number[] = [];
  let rest = value;
  do {
    bytes.push(rest > 0x7f ? (rest & 0x7f) | 0x80 : rest);
    rest >>>= 7;
  } while (rest > 0);
  return bytes;
}

function concat(...parts: number[][]): Uint8Array {
  return Uint8Array.from(parts.flat());
}

/* ---- reading one back --------------------------------------------------------------------- */

/**
 * Whether a keyset read out of a study file is one the app can actually seal to.
 *
 * `ExportConfiguration` bounds this document's *length* and checks nothing else, and the codec
 * re-emits it verbatim, so a keyset that names the wrong algorithm, is disabled, is not the primary,
 * or has a truncated key signs cleanly here and is refused by Tink on a participant's phone at
 * export time. Nothing between the two would have said so, and a study can be weeks old before
 * anyone finds out. This is Tink's own validation, run before the signature instead of after it.
 *
 * Both producers of this keyset — this page and `researcher-tools hpke-keygen` — write exactly one
 * enabled `TINK` key. A keyset shaped any other way did not come from either, so it is refused
 * rather than guessed at.
 */
export function isUsableHpkePublicKeyset(keyset: TinkKeyset): boolean {
  if (!keyset || typeof keyset !== 'object') return false;
  if (!Array.isArray(keyset.key) || keyset.key.length !== 1) return false;
  const [entry] = keyset.key;
  if (!entry || typeof entry !== 'object') return false;
  if (entry.status !== 'ENABLED' || entry.outputPrefixType !== 'TINK') return false;
  if (!Number.isInteger(entry.keyId) || entry.keyId < 1 || entry.keyId > MAXIMUM_KEY_ID) {
    return false;
  }
  if (entry.keyId !== keyset.primaryKeyId) return false;
  const data = entry.keyData;
  if (!data || typeof data !== 'object') return false;
  if (data.typeUrl !== PUBLIC_TYPE_URL || data.keyMaterialType !== 'ASYMMETRIC_PUBLIC') return false;
  if (typeof data.value !== 'string') return false;
  try {
    return isHpkePublicKey(decodeBase64(data.value));
  } catch {
    return false;
  }
}

/** `HpkePublicKey { params: HpkeParams, public_key: bytes }` with this module's one suite. */
function isHpkePublicKey(message: Uint8Array): boolean {
  const fields = readMessage(message);
  const params = delimited(fields, 2);
  const publicKey = delimited(fields, 3);
  if (!params || !publicKey || publicKey.length !== PRIVATE_KEY_BYTES) return false;
  const suite = readMessage(params);
  // DHKEM_X25519_HKDF_SHA256 / HKDF_SHA256 / AES_256_GCM. Read field by field rather than compared
  // as bytes, so a producer that orders the three differently is still recognised.
  return scalar(suite, 1) === 1 && scalar(suite, 2) === 1 && scalar(suite, 3) === 2;
}

interface ProtoField {
  field: number;
  wire: number;
  bytes: Uint8Array;
  value: number;
}

/**
 * A protobuf message, flat. Fields this module does not know are carried rather than refused —
 * `version` is a varint proto3 omits at 0, and a reader that cannot step over one breaks on the
 * first producer that writes it.
 */
export function readMessage(message: Uint8Array): ProtoField[] {
  const fields: ProtoField[] = [];
  let cursor = 0;
  while (cursor < message.length) {
    const [tag, afterTag] = readVarint(message, cursor);
    const field = tag >>> 3;
    const wire = tag & 7;
    if (wire === 2) {
      const [length, afterLength] = readVarint(message, afterTag);
      const end = afterLength + length;
      if (end > message.length) throw new Error('proto_truncated');
      fields.push({ field, wire, bytes: message.subarray(afterLength, end), value: 0 });
      cursor = end;
    } else if (wire === 0) {
      const [value, after] = readVarint(message, afterTag);
      fields.push({ field, wire, bytes: EMPTY, value });
      cursor = after;
    } else {
      const width = wire === 5 ? 4 : wire === 1 ? 8 : -1;
      if (width < 0 || afterTag + width > message.length) throw new Error('proto_wire_type');
      fields.push({ field, wire, bytes: EMPTY, value: 0 });
      cursor = afterTag + width;
    }
  }
  return fields;
}

export function delimited(fields: readonly ProtoField[], field: number): Uint8Array | null {
  return fields.find((candidate) => candidate.field === field && candidate.wire === 2)?.bytes ?? null;
}

function scalar(fields: readonly ProtoField[], field: number): number | null {
  return fields.find((candidate) => candidate.field === field && candidate.wire === 0)?.value ?? null;
}

const EMPTY = new Uint8Array(0);

function readVarint(bytes: Uint8Array, start: number): [number, number] {
  let value = 0;
  let shift = 0;
  let cursor = start;
  for (;;) {
    if (cursor >= bytes.length || shift > 28) throw new Error('proto_varint');
    const byte = bytes[cursor++];
    value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return [value >>> 0, cursor];
    shift += 7;
  }
}
