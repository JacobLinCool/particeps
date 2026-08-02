/**
 * Ed25519 signing keys, in the exact encodings Java reads.
 *
 * The CLI writes `KeyPairGenerator.getInstance("Ed25519")`'s output straight to disk and the app
 * reads `signer.public_key` through `X509EncodedKeySpec`, so both halves are DER — not raw 32-byte
 * keys. A researcher has to be able to move between this page and the CLI in either direction:
 * a key made here must sign in `researcher-tools`, and a key made there must verify here. That is
 * why the two prefixes below are byte constants rather than something assembled at runtime; for
 * Ed25519 the DER is fixed-length and every field is known, so there is nothing to compute.
 */

import * as ed from '@noble/ed25519';
import { sha256, sha512 } from '@noble/hashes/sha2.js';

// @noble/ed25519 ships its synchronous API unwired so the hash can be tree-shaken away. Signing
// happens inside a click handler on a key the page already holds, so the sync path is the one we
// want.
ed.hashes.sha512 = sha512;

export interface SigningKeyPair {
  privatePkcs8Base64: string;
  publicX509Base64: string;
}

/** PKCS#8: SEQUENCE { INTEGER 0, SEQUENCE { OID 1.3.101.112 }, OCTET STRING { OCTET STRING seed } } */
const PKCS8_PREFIX = Uint8Array.of(
  0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
);

/** X.509 SubjectPublicKeyInfo: SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING { key } } */
const X509_PREFIX = Uint8Array.of(
  0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
);

const KEY_BYTES = 32;

export function generateSigningKeyPair(): SigningKeyPair {
  const { secretKey, publicKey } = ed.keygen();
  return {
    privatePkcs8Base64: encodePkcs8(secretKey),
    publicX509Base64: encodeX509(publicKey)
  };
}

export function sign(configurationBytes: Uint8Array, privatePkcs8Base64: string): Uint8Array {
  return ed.sign(configurationBytes, decodePkcs8(privatePkcs8Base64));
}

/**
 * Never throws: this is the self-check that stops a configuration going out with a `public_key`
 * that does not match the key it was signed with, and a malformed key is one of the answers it
 * exists to give. RFC 8032 semantics rather than the ZIP-215 default, because the app verifies
 * through the JDK, which rejects the non-canonical encodings ZIP-215 accepts.
 */
export function verify(
  configurationBytes: Uint8Array,
  signature: Uint8Array,
  publicX509Base64: string
): boolean {
  try {
    return ed.verify(signature, configurationBytes, decodeX509(publicX509Base64), {
      zip215: false
    });
  } catch {
    return false;
  }
}

/**
 * SHA-256 over the DER, not over the 32-byte key — `SignerIdentity.fingerprint` hashes whatever
 * the Base64 decodes to, and this is the string a participant compares against the recruitment
 * sheet. Hashing the wrong span produces a fingerprint nobody can match and nobody can debug.
 */
export function fingerprint(publicX509Base64: string): string {
  const digest = sha256(decodeBase64(publicX509Base64)).subarray(0, 16);
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, '0').toUpperCase())
    .join('')
    .replace(/(.{4})(?=.)/g, '$1 ');
}

export function encodePkcs8(seed: Uint8Array): string {
  return encodeBase64(wrap(PKCS8_PREFIX, seed, 'pkcs8_length'));
}

/** The 32-byte seed, which is what @noble/ed25519 and the RFC call the secret key. */
export function decodePkcs8(privatePkcs8Base64: string): Uint8Array {
  return unwrap(PKCS8_PREFIX, decodeBase64(privatePkcs8Base64), 'pkcs8_invalid');
}

export function encodeX509(publicKey: Uint8Array): string {
  return encodeBase64(wrap(X509_PREFIX, publicKey, 'x509_length'));
}

export function decodeX509(publicX509Base64: string): Uint8Array {
  return unwrap(X509_PREFIX, decodeBase64(publicX509Base64), 'x509_invalid');
}

export function encodeBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function decodeBase64(text: string): Uint8Array {
  let binary: string;
  try {
    binary = atob(text.trim());
  } catch {
    throw new Error('base64_invalid');
  }
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function wrap(prefix: Uint8Array, key: Uint8Array, code: string): Uint8Array {
  if (key.length !== KEY_BYTES) throw new Error(code);
  const der = new Uint8Array(prefix.length + KEY_BYTES);
  der.set(prefix);
  der.set(key, prefix.length);
  return der;
}

function unwrap(prefix: Uint8Array, der: Uint8Array, code: string): Uint8Array {
  if (der.length !== prefix.length + KEY_BYTES) throw new Error(code);
  if (prefix.some((byte, index) => der[index] !== byte)) throw new Error(code);
  return der.slice(prefix.length);
}
