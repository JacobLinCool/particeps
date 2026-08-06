/** Cross-language Protocol v1 conformance checks plus focused primitive vectors. */

import { describe, expect, it } from 'vitest';
import {
  canonicalBytes,
  canonicalConfigurationBytes,
  canonicalize,
  canonicalizeConfiguration
} from '../src/lib/particeps/canonical';
import { decodeBase64Url, encodeBase64Url, sign, verify } from '../src/lib/particeps/crypto';
import { decodeEnvelope, encodeEnvelope } from '../src/lib/particeps/envelope';
import { openBundle } from '../src/lib/particeps/bundle';
import { parseConfiguration } from '../src/routes/researcher/parse';
import { HPKE, SIGNING, validConfiguration } from './fixture';
import { sealBundle } from './seal';

const fromHex = (value: string) =>
  Uint8Array.from(value.match(/../g) ?? [], (byte) => parseInt(byte, 16));

describe('Protocol v1 deterministic compatibility boundary', () => {
  it('matches RFC 8032 Ed25519 test vector 1 using raw keys', () => {
    const privateKey = encodeBase64Url(
      fromHex('9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60')
    );
    const publicKey = encodeBase64Url(
      fromHex('d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a')
    );
    const expected = fromHex(
      'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155' +
        '5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b'
    );
    expect(sign(new Uint8Array(), privateKey)).toEqual(expected);
    expect(verify(new Uint8Array(), expected, publicKey)).toBe(true);
  });

  it('round-trips JCS configuration bytes through the fixed signed envelope', () => {
    const configuration = validConfiguration();
    const payload = canonicalConfigurationBytes(configuration);
    const signature = sign(payload, SIGNING.privateKey);
    const envelope = encodeEnvelope(configuration.signer.key_id, payload, signature);
    const decoded = decodeEnvelope(envelope);
    expect(decoded.configurationBytes).toEqual(payload);
    expect(decoded.signerKeyId).toBe(configuration.signer.key_id);
    expect(verify(decoded.configurationBytes, decoded.signature, SIGNING.publicKey)).toBe(true);
    expect(canonicalizeConfiguration(parseConfiguration(envelope))).toBe(
      canonicalizeConfiguration(configuration)
    );
  });

  it('round-trips deterministic RFC 9180/AES-GCM bundle bytes', async () => {
    const configuration = validConfiguration();
    const first = await sealBundle(configuration, SIGNING.privateKey);
    const second = await sealBundle(configuration, SIGNING.privateKey);
    expect(second).toEqual(first);
    expect(await openBundle(first, configuration, HPKE.privateKey)).toMatchObject({ ok: true });
  });

  it('rejects the former Protocol v1 JSON, the retired ADCCFG01 magic, and a variable-length envelope', () => {
    const current = JSON.parse(canonicalizeConfiguration(validConfiguration())) as Record<string, unknown>;
    delete current.platform;
    delete current.minimum_client_version;
    current.minimum_app_version = 1;
    current.export = {
      researcher_key_id: 'protocol-export',
      tink_hpke_public_keyset: { primaryKeyId: 1, key: [] }
    };
    expect(() => parseConfiguration(canonicalBytes(current))).toThrow();

    const configuration = validConfiguration();
    const payload = canonicalConfigurationBytes(configuration);
    const retired = encodeEnvelope(
      configuration.signer.key_id,
      payload,
      sign(payload, SIGNING.privateKey)
    );
    expect(() => decodeEnvelope(retired)).not.toThrow();
    // Retired-identity rejection fixture. `ADCCFG01` is the old configuration magic and must stay
    // spelled out here: a rename sweep that "fixes" it would leave this test proving nothing.
    retired.set(new TextEncoder().encode('ADCCFG01'));
    expect(() => decodeEnvelope(retired)).toThrow('envelope_magic');

    const old = new Uint8Array(16 + payload.length + 64);
    old.set(new TextEncoder().encode('PTCCFG01'));
    const view = new DataView(old.buffer);
    view.setUint16(8, 3);
    view.setUint32(10, payload.length);
    view.setUint16(14, 64);
    expect(() => decodeEnvelope(old)).toThrow();
  });

  it('uses generic JCS rather than a schema-order encoder', () => {
    expect(canonicalize({ z: 0, a: { y: 2, x: 1 } })).toBe('{"a":{"x":1,"y":2},"z":0}');
  });
});
