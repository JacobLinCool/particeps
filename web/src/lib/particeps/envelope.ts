/** Fixed Protocol v1 `.partcfg` framing.
 *
 * `PTCCFG01 | signer-key-id length u16 BE | configuration length u32 BE | key id UTF-8 |
 * canonical configuration | Ed25519 signature[64]`.
 */

import { ID_PATTERN, MAXIMUM_CONFIGURATION_BYTES } from './types';

const MAGIC = 'PTCCFG01';
const HEADER_BYTES = 14;
const SIGNATURE_BYTES = 64;
const ENCODER = new TextEncoder();
const DECODER = new TextDecoder('utf-8', { fatal: true });

export interface ConfigurationEnvelope {
  signerKeyId: string;
  configurationBytes: Uint8Array;
  signature: Uint8Array;
}

export function encodeEnvelope(
  signerKeyId: string,
  configurationBytes: Uint8Array,
  signature: Uint8Array
): Uint8Array {
  const keyId = ENCODER.encode(signerKeyId);
  if (!ID_PATTERN.test(signerKeyId) || keyId.length > 64) throw new Error('envelope_key_id');
  if (configurationBytes.length < 2 || configurationBytes.length > MAXIMUM_CONFIGURATION_BYTES) {
    throw new Error('envelope_configuration');
  }
  if (signature.length !== SIGNATURE_BYTES) throw new Error('envelope_signature');

  const envelope = new Uint8Array(
    HEADER_BYTES + keyId.length + configurationBytes.length + SIGNATURE_BYTES
  );
  for (let index = 0; index < MAGIC.length; index += 1) envelope[index] = MAGIC.charCodeAt(index);
  const header = new DataView(envelope.buffer);
  header.setUint16(8, keyId.length);
  header.setUint32(10, configurationBytes.length);
  envelope.set(keyId, HEADER_BYTES);
  envelope.set(configurationBytes, HEADER_BYTES + keyId.length);
  envelope.set(signature, HEADER_BYTES + keyId.length + configurationBytes.length);
  return envelope;
}

export function decodeEnvelope(bytes: Uint8Array): ConfigurationEnvelope {
  if (bytes.length < HEADER_BYTES + SIGNATURE_BYTES) throw new Error('envelope_short');
  if (!isEnvelope(bytes)) throw new Error('envelope_magic');
  const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const keyIdLength = header.getUint16(8);
  const configurationLength = header.getUint32(10);
  if (keyIdLength < 3 || keyIdLength > 64) throw new Error('envelope_key_id');
  if (configurationLength < 2 || configurationLength > MAXIMUM_CONFIGURATION_BYTES) {
    throw new Error('envelope_configuration');
  }
  const expected = HEADER_BYTES + keyIdLength + configurationLength + SIGNATURE_BYTES;
  if (bytes.length !== expected) throw new Error('envelope_length');
  const keyIdEnd = HEADER_BYTES + keyIdLength;
  const configurationEnd = keyIdEnd + configurationLength;
  let signerKeyId: string;
  try {
    signerKeyId = DECODER.decode(bytes.subarray(HEADER_BYTES, keyIdEnd));
  } catch {
    throw new Error('envelope_key_id');
  }
  if (!ID_PATTERN.test(signerKeyId)) throw new Error('envelope_key_id');
  return {
    signerKeyId,
    configurationBytes: bytes.slice(keyIdEnd, configurationEnd),
    signature: bytes.slice(configurationEnd)
  };
}

export function isEnvelope(bytes: Uint8Array): boolean {
  if (bytes.length < MAGIC.length) return false;
  for (let index = 0; index < MAGIC.length; index += 1) {
    if (bytes[index] !== MAGIC.charCodeAt(index)) return false;
  }
  return true;
}
