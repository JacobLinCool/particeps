import { describe, expect, it } from 'vitest';
import {
  decodeBase64Url,
  encodeBase64Url,
  generateHpkeKeyPair,
  generateSigningKeyPair,
  hpkeKeyPairFromPrivate,
  sign,
  signingKeyPairFromPrivate,
  verify
} from '../src/lib/adc/crypto';
import { decodeEnvelope, encodeEnvelope } from '../src/lib/adc/envelope';

describe('raw Protocol v1 keys', () => {
  it('generates and reopens raw 32-byte Ed25519 and X25519 artifacts', () => {
    const signing = generateSigningKeyPair();
    const hpke = generateHpkeKeyPair();
    for (const value of [signing.privateKey, signing.publicKey, hpke.privateKey, hpke.publicKey]) {
      expect(value).toMatch(/^[A-Za-z0-9_-]{43}$/);
      expect(decodeBase64Url(value, 32)).toHaveLength(32);
    }
    expect(signingKeyPairFromPrivate(signing.privateKey)).toEqual(signing);
    expect(hpkeKeyPairFromPrivate(hpke.privateKey)).toEqual(hpke);
  });

  it('rejects padded, standard-base64, whitespace, and wrong-length alternatives', () => {
    const canonical = encodeBase64Url(new Uint8Array(32).fill(7));
    for (const value of [`${canonical}=`, ` ${canonical}`, `+${canonical.slice(1)}`, 'AA']) {
      expect(() => decodeBase64Url(value, 32)).toThrow();
    }
  });

  it('signs and verifies with strict RFC 8032 semantics', () => {
    const pair = generateSigningKeyPair();
    const message = new TextEncoder().encode('{"schema_version":1}');
    const signature = sign(message, pair.privateKey);
    expect(signature).toHaveLength(64);
    expect(verify(message, signature, pair.publicKey)).toBe(true);
    signature[0] ^= 1;
    expect(verify(message, signature, pair.publicKey)).toBe(false);
  });
});

describe('fixed ADCCFG01 envelope', () => {
  it('has no signature-length field and round-trips exactly 64 signature bytes', () => {
    const configuration = new TextEncoder().encode('{"a":1}');
    const signature = new Uint8Array(64).fill(9);
    const bytes = encodeEnvelope('protocol-signer', configuration, signature);
    expect(new TextDecoder().decode(bytes.subarray(0, 8))).toBe('ADCCFG01');
    expect(new DataView(bytes.buffer).getUint16(8)).toBe(15);
    expect(new DataView(bytes.buffer).getUint32(10)).toBe(configuration.length);
    expect(bytes).toHaveLength(14 + 15 + configuration.length + 64);
    expect(decodeEnvelope(bytes)).toEqual({
      signerKeyId: 'protocol-signer',
      configurationBytes: configuration,
      signature
    });
  });

  it('rejects variable signatures and trailing bytes', () => {
    expect(() => encodeEnvelope('protocol-signer', new Uint8Array(2), new Uint8Array(63))).toThrow(
      'envelope_signature'
    );
    const valid = encodeEnvelope('protocol-signer', new Uint8Array(2), new Uint8Array(64));
    const trailing = new Uint8Array(valid.length + 1);
    trailing.set(valid);
    expect(() => decodeEnvelope(trailing)).toThrow('envelope_length');
  });
});
