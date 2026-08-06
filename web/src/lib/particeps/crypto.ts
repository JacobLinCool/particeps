/** Raw Protocol v1 key handling.
 *
 * Every key artifact and wire field is an unpadded base64url encoding of exactly 32 raw bytes.
 * There is deliberately no DER, protobuf, Tink prefix, or alternate decoder in this module.
 */

import { x25519 } from '@noble/curves/ed25519.js';
import * as ed from '@noble/ed25519';
import { sha256, sha512 } from '@noble/hashes/sha2.js';

ed.hashes.sha512 = sha512;

const KEY_BYTES = 32;
const SIGNATURE_BYTES = 64;
const BASE64URL = /^[A-Za-z0-9_-]+$/;

export interface SigningKeyPair {
  privateKey: string;
  publicKey: string;
}

export interface HpkeKeyPair {
  privateKey: string;
  publicKey: string;
}

export function generateSigningKeyPair(): SigningKeyPair {
  const { secretKey, publicKey } = ed.keygen();
  return { privateKey: encodeBase64Url(secretKey), publicKey: encodeBase64Url(publicKey) };
}

export function signingKeyPairFromPrivate(privateKey: string): SigningKeyPair {
  const raw = decodeBase64Url(privateKey.trim(), KEY_BYTES);
  return {
    privateKey: encodeBase64Url(raw),
    publicKey: encodeBase64Url(ed.getPublicKey(raw))
  };
}

export function generateHpkeKeyPair(): HpkeKeyPair {
  const privateKey = crypto.getRandomValues(new Uint8Array(KEY_BYTES));
  return hpkeKeyPairFromPrivate(encodeBase64Url(privateKey));
}

export function hpkeKeyPairFromPrivate(privateKey: string): HpkeKeyPair {
  const raw = decodeBase64Url(privateKey.trim(), KEY_BYTES);
  return {
    privateKey: encodeBase64Url(raw),
    publicKey: encodeBase64Url(x25519.getPublicKey(raw))
  };
}

export function sign(configurationBytes: Uint8Array, privateKey: string): Uint8Array {
  return ed.sign(configurationBytes, decodeBase64Url(privateKey, KEY_BYTES));
}

/** Strict RFC 8032 verification; malformed encodings are a false result, never an exception. */
export function verify(
  configurationBytes: Uint8Array,
  signature: Uint8Array,
  publicKey: string
): boolean {
  try {
    if (signature.length !== SIGNATURE_BYTES) return false;
    return ed.verify(signature, configurationBytes, decodeBase64Url(publicKey, KEY_BYTES), {
      zip215: false
    });
  } catch {
    return false;
  }
}

/** Participant-facing 128-bit fingerprint over the raw Ed25519 public key. */
export function fingerprint(publicKey: string): string {
  const digest = sha256(decodeBase64Url(publicKey, KEY_BYTES)).subarray(0, 16);
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, '0').toUpperCase())
    .join('')
    .replace(/(.{4})(?=.)/g, '$1 ');
}

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

/** Decode one canonical, unpadded base64url value. */
export function decodeBase64Url(text: string, expectedBytes?: number): Uint8Array {
  if (text.length === 0 || !BASE64URL.test(text) || text.includes('=')) {
    throw new Error('base64url_invalid');
  }
  const remainder = text.length % 4;
  if (remainder === 1) throw new Error('base64url_invalid');
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - remainder) % 4);
  let binary: string;
  try {
    binary = atob(padded);
  } catch {
    throw new Error('base64url_invalid');
  }
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  if (expectedBytes !== undefined && bytes.length !== expectedBytes) {
    throw new Error('base64url_length');
  }
  if (encodeBase64Url(bytes) !== text) throw new Error('base64url_noncanonical');
  return bytes;
}
