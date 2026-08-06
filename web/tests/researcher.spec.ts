import { describe, expect, it } from 'vitest';
import { canonicalConfigurationBytes, canonicalizeConfiguration } from '$lib/particeps/canonical';
import { generateHpkeKeyPair, generateSigningKeyPair, sign } from '$lib/particeps/crypto';
import { encodeEnvelope } from '$lib/particeps/envelope';
import { DEFAULT_LOCAL_BYTES, emptyConfiguration } from '$lib/particeps/schema';
import { artifactBytes } from '../src/routes/researcher/artifacts';
import { hpkeKeyPairFromPrivate, signingKeyPairFromPrivate } from '../src/routes/researcher/keys';
import { parseConfiguration } from '../src/routes/researcher/parse';
import { validConfiguration } from './fixture';

describe('researcher Protocol v1 workflow', () => {
  it('generates portable raw private artifacts with no wrapper or newline', () => {
    const signing = generateSigningKeyPair();
    const hpke = generateHpkeKeyPair();
    expect(signing.privateKey).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(hpke.privateKey).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(signingKeyPairFromPrivate(signing.privateKey)).toEqual(signing);
    expect(hpkeKeyPairFromPrivate(hpke.privateKey)).toEqual(hpke);
  });

  it('signs, packages, verifies, and reopens one canonical configuration', () => {
    const configuration = validConfiguration();
    const pair = generateSigningKeyPair();
    configuration.signer.public_key = pair.publicKey;
    const finalPayload = canonicalConfigurationBytes(configuration);
    const envelope = encodeEnvelope(
      configuration.signer.key_id,
      finalPayload,
      sign(finalPayload, pair.privateKey)
    );
    expect(canonicalizeConfiguration(parseConfiguration(envelope))).toBe(
      canonicalizeConfiguration(configuration)
    );
  });

  it('authors pinned platform/build defaults and integer physical units', () => {
    const configuration = emptyConfiguration();
    expect(configuration.platform).toBe('android');
    expect(configuration.minimum_client_version).toBe('1');
    expect(configuration.storage.maximum_local_bytes).toBe(DEFAULT_LOCAL_BYTES);
    expect(configuration.export).toEqual({ researcher_key_id: '', hpke_public_key: '' });
  });

  it('downloads private keys as exact raw text and the config only after signing', () => {
    const signing = generateSigningKeyPair();
    const hpke = generateHpkeKeyPair();
    const canonical = canonicalConfigurationBytes(validConfiguration());
    const source = {
      signingPrivate: signing.privateKey,
      hpkePrivate: hpke.privateKey,
      canonical,
      envelope: null
    };
    expect(new TextDecoder().decode(artifactBytes('signing-private', source)!)).toBe(signing.privateKey);
    expect(new TextDecoder().decode(artifactBytes('hpke-private', source)!)).toBe(hpke.privateKey);
    expect(artifactBytes('canonical', source)).toBeNull();
  });
});
