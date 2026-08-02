/**
 * Interop tests against the fixtures in `researcher-tools/examples`, which were produced by the
 * shipped CLI on a real JDK and a real Tink.
 *
 * The point of every case here is that the site and the CLI are interchangeable. A researcher who
 * generates a key on this page must be able to sign with the CLI later, and a study signed here
 * must verify on a phone, so nothing below asserts "our encoder agrees with itself" — each case
 * pins bytes that came from the other implementation.
 */

import { describe, expect, it } from 'vitest';
import demoSigningPrivateKey from '../../researcher-tools/examples/INSECURE-demo-signing-private.key?raw';
import demoHpkePrivateKeyset from '../../researcher-tools/examples/INSECURE-demo-hpke-private.json?raw';
import demoStudyJson from '../../researcher-tools/examples/demo-study.json?raw';
import {
  decodeBase64,
  decodePkcs8,
  decodeX509,
  encodeBase64,
  encodePkcs8,
  encodeX509,
  fingerprint,
  generateSigningKeyPair,
  sign,
  verify
} from '../src/lib/adc/crypto';
import { hpkeKeysetFromPrivateKey } from '../src/lib/adc/tink';
import { encodeEnvelope } from '../src/lib/adc/envelope';

const demoPrivateKey = demoSigningPrivateKey.trim();
const demoPrivateKeyset = demoHpkePrivateKeyset.trim();
const demoStudy = JSON.parse(demoStudyJson);
const demoPublicKey: string = demoStudy.signer.public_key;
const demoPublicKeyset = demoStudy.export.tink_hpke_public_keyset;

describe('Ed25519 keys', () => {
  it('round-trips a generated pair through sign and verify', () => {
    const pair = generateSigningKeyPair();
    const message = new TextEncoder().encode('{"schema_version":1}');
    const signature = sign(message, pair.privatePkcs8Base64);

    expect(signature).toHaveLength(64);
    expect(verify(message, signature, pair.publicX509Base64)).toBe(true);

    const tampered = Uint8Array.from(message);
    tampered[0] ^= 1;
    expect(verify(tampered, signature, pair.publicX509Base64)).toBe(false);
    expect(verify(message, signature, generateSigningKeyPair().publicX509Base64)).toBe(false);
  });

  it('re-encodes the demo PKCS#8 and X.509 keys byte for byte', () => {
    expect(encodePkcs8(decodePkcs8(demoPrivateKey))).toBe(demoPrivateKey);
    expect(encodeX509(decodeX509(demoPublicKey))).toBe(demoPublicKey);

    // Both fixtures are 48 and 44 bytes of DER, not raw keys, and they are one key pair: a
    // signature made with the file verifies under the key the demo study publishes.
    expect(decodeBase64(demoPrivateKey)).toHaveLength(48);
    expect(decodeBase64(demoPublicKey)).toHaveLength(44);
    const message = new TextEncoder().encode('demo');
    expect(verify(message, sign(message, demoPrivateKey), demoPublicKey)).toBe(true);
  });

  it('fingerprints the demo public key the way the app displays it', () => {
    expect(fingerprint(demoPublicKey)).toBe('9D0D AE5A 0D20 B29F D642 942A 0E17 4AAE');
  });
});

describe('Tink HPKE keysets', () => {
  it('rebuilds both demo keysets from the demo private key material', () => {
    // Taken apart by hand rather than through the encoder, so a wrong idea about the layout cannot
    // hide by being wrong in both places. Field 2 is the 42-byte HpkePublicKey, field 3 the scalar.
    const fixture = JSON.parse(demoPrivateKeyset);
    const value = decodeBase64(fixture.key[0].keyData.value);
    expect(Array.from(value.subarray(0, 2))).toEqual([0x12, 0x2a]);
    expect(Array.from(value.subarray(44, 46))).toEqual([0x1a, 0x20]);

    const keyset = hpkeKeysetFromPrivateKey(value.subarray(46), fixture.primaryKeyId);
    expect(JSON.stringify(keyset.privateKeyset)).toBe(demoPrivateKeyset);
    expect(JSON.stringify(keyset.publicKeyset)).toBe(JSON.stringify(demoPublicKeyset));
  });

  it('refuses key material Tink would reject', () => {
    const privateKey = new Uint8Array(32);
    expect(() => hpkeKeysetFromPrivateKey(privateKey.subarray(0, 31), 1)).toThrow();
    expect(() => hpkeKeysetFromPrivateKey(privateKey, 0)).toThrow();
    expect(() => hpkeKeysetFromPrivateKey(privateKey, 0x1_0000_0000)).toThrow();
  });
});

describe('envelope', () => {
  it('lays the header out big-endian ahead of the three payloads', () => {
    const configuration = new TextEncoder().encode('{}');
    const signature = new Uint8Array(64).fill(7);
    const envelope = encodeEnvelope('demo-signer-2026', configuration, signature);

    expect(new TextDecoder().decode(envelope.subarray(0, 8))).toBe('ADCCFG01');
    const header = new DataView(envelope.buffer, 8, 8);
    expect(header.getUint16(0)).toBe(16);
    expect(header.getInt32(2)).toBe(2);
    expect(header.getUint16(6)).toBe(64);
    expect(new TextDecoder().decode(envelope.subarray(16, 32))).toBe('demo-signer-2026');
    expect(envelope.subarray(32, 34)).toEqual(configuration);
    expect(envelope.subarray(34)).toEqual(signature);
    expect(envelope).toHaveLength(16 + 16 + 2 + 64);
  });

  it('refuses payloads the app would refuse to open', () => {
    const signature = new Uint8Array(64);
    expect(() => encodeEnvelope('ab', new Uint8Array(2), signature)).toThrow();
    expect(() => encodeEnvelope('a'.repeat(65), new Uint8Array(2), signature)).toThrow();
    expect(() => encodeEnvelope('demo', new Uint8Array(1), signature)).toThrow();
    expect(() => encodeEnvelope('demo', new Uint8Array(2), new Uint8Array(31))).toThrow();
  });
});

describe('base64', () => {
  it('round-trips every byte value', () => {
    const bytes = Uint8Array.from({ length: 256 }, (_, index) => index);
    expect(decodeBase64(encodeBase64(bytes))).toEqual(bytes);
  });
});
