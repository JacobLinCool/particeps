/**
 * Deriving a key pair from a private half that already exists.
 *
 * `lib/adc` generates both key pairs and has no reason to read one back; this page does. Without
 * it a second configuration under the same signer is impossible, and the cross-language workflow —
 * one signed configuration per language, same signer, same `experiment_id`, new
 * `configuration_id` — needs exactly that.
 *
 * Both derivations are pure and local: the public half is computed from the private one, never
 * taken from the file beside it.
 */

import { decodeBase64, decodePkcs8, encodeX509, type SigningKeyPair } from '$lib/adc/crypto';
import { delimited, hpkeKeysetFromPrivateKey, readMessage, type HpkeKeyset } from '$lib/adc/tink';
import { ed25519 } from '@noble/curves/ed25519.js';

/** The CLI writes the key with a trailing newline and trims on read; do the same. */
export function signingKeyPairFromPrivate(privatePkcs8Base64: string): SigningKeyPair {
  const trimmed = privatePkcs8Base64.trim();
  const seed = decodePkcs8(trimmed);
  return { privatePkcs8Base64: trimmed, publicX509Base64: encodeX509(ed25519.getPublicKey(seed)) };
}

/**
 * The private scalar out of a Tink JSON keyset. `HpkePrivateKey` is
 * `{2: HpkePublicKey, 3: privateKey}` with field 1 (version 0) omitted, so the scalar is the one
 * length-delimited field 3 at the top level. The key ID comes from the file rather than being
 * minted again: a keyset that decrypts existing bundles has to keep announcing the same ID.
 */
export function hpkeKeysetFromPrivate(privateKeysetJson: string): HpkeKeyset {
  const parsed: unknown = JSON.parse(privateKeysetJson);
  const key = (parsed as { key?: unknown[] })?.key?.[0] as
    | { keyData?: { value?: string }; keyId?: number }
    | undefined;
  const value = key?.keyData?.value;
  if (typeof value !== 'string' || typeof key?.keyId !== 'number') {
    throw new Error('hpke_keyset_shape');
  }
  return hpkeKeysetFromPrivateKey(scalarOf(value), key.keyId);
}

/** Every way a file can fail to be a keyset reads the same to the researcher: it was not one. */
function scalarOf(base64: string): Uint8Array {
  let bytes: Uint8Array | null;
  try {
    bytes = delimited(readMessage(decodeBase64(base64)), 3);
  } catch {
    throw new Error('hpke_keyset_shape');
  }
  if (!bytes) throw new Error('hpke_keyset_shape');
  return bytes;
}

export type { HpkeKeyset, SigningKeyPair };
