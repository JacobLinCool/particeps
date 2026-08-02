/**
 * The `.adccfg` container: `ADCCFG01`, three big-endian lengths, then the three payloads.
 *
 * The bounds mirror `SignedConfigurationCodec.encode`'s `require`s rather than trusting the caller.
 * A file that leaves this page and is refused by every phone it reaches is a worse failure than a
 * download that does not start, because nothing about it says which of the two ends was wrong.
 */

import { MAXIMUM_CONFIGURATION_BYTES } from './types';

const MAGIC = 'ADCCFG01';
const HEADER_BYTES = MAGIC.length + 2 + 4 + 2;

export function encodeEnvelope(
  signerKeyId: string,
  configurationBytes: Uint8Array,
  signature: Uint8Array
): Uint8Array {
  const keyId = new TextEncoder().encode(signerKeyId);
  if (keyId.length < 3 || keyId.length > 64) throw new Error('envelope_key_id');
  if (configurationBytes.length < 2 || configurationBytes.length > MAXIMUM_CONFIGURATION_BYTES) {
    throw new Error('envelope_configuration');
  }
  if (signature.length < 32 || signature.length > 128) throw new Error('envelope_signature');

  const envelope = new Uint8Array(
    HEADER_BYTES + keyId.length + configurationBytes.length + signature.length
  );
  const header = new DataView(envelope.buffer);
  for (let index = 0; index < MAGIC.length; index += 1) envelope[index] = MAGIC.charCodeAt(index);
  header.setUint16(8, keyId.length);
  header.setInt32(10, configurationBytes.length);
  header.setUint16(14, signature.length);
  envelope.set(keyId, HEADER_BYTES);
  envelope.set(configurationBytes, HEADER_BYTES + keyId.length);
  envelope.set(signature, HEADER_BYTES + keyId.length + configurationBytes.length);
  return envelope;
}
