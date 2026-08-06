import { describe, expect, it } from 'vitest';
import {
  canonicalBytes,
  canonicalConfigurationBytes,
  canonicalizeConfiguration
} from '../src/lib/particeps/canonical';
import { sign } from '../src/lib/particeps/crypto';
import { encodeEnvelope } from '../src/lib/particeps/envelope';
import { validate } from '../src/lib/particeps/schema';
import type { StudyConfiguration } from '../src/lib/particeps/types';
import { parseConfiguration } from '../src/routes/researcher/parse';
import { SIGNING, validConfiguration } from './fixture';

describe('closed-world configuration parser', () => {
  const wire = () => JSON.parse(canonicalizeConfiguration(validConfiguration())) as Record<string, unknown>;

  it.each([
    ['unknown root member', (value: Record<string, unknown>) => (value.future = true)],
    ['wrong platform', (value: Record<string, unknown>) => (value.platform = 'ios')],
    ['numeric client version', (value: Record<string, unknown>) => (value.minimum_client_version = 1)],
    ['padded client version', (value: Record<string, unknown>) => (value.minimum_client_version = '01')],
    [
      'old minimum app version',
      (value: Record<string, unknown>) => {
        delete value.minimum_client_version;
        value.minimum_app_version = 1;
      }
    ],
    [
      'old Tink export keyset',
      (value: Record<string, unknown>) => {
        value.export = {
          researcher_key_id: 'protocol-export',
          tink_hpke_public_keyset: { primaryKeyId: 1, key: [] }
        };
      }
    ]
  ])('rejects %s', (_name, mutate) => {
    const value = wire();
    mutate(value);
    expect(() => parseConfiguration(canonicalBytes(value))).toThrow();
  });

  it('rejects noncanonical member order, duplicate members, malformed UTF-8, and floats', () => {
    expect(() => parseConfiguration(new TextEncoder().encode('{"b":2,"a":1}'))).toThrow();
    expect(() => parseConfiguration(new TextEncoder().encode('{"a":1,"a":1}'))).toThrow();
    expect(() => parseConfiguration(Uint8Array.of(0x7b, 0xff, 0x7d))).toThrow();
    const value = wire();
    value.duration_hours = 1.5;
    expect(() => parseConfiguration(canonicalBytes(value))).toThrow();
  });

  it('verifies signer ID and Ed25519 signature when the input is PTCCFG01', () => {
    const configuration = validConfiguration();
    const payload = canonicalConfigurationBytes(configuration);
    const signature = sign(payload, SIGNING.privateKey);
    expect(parseConfiguration(encodeEnvelope(configuration.signer.key_id, payload, signature))).toEqual(
      configuration
    );
    expect(() => parseConfiguration(encodeEnvelope('other-signer', payload, signature))).toThrow(
      'envelope_signer'
    );
    signature[0] ^= 1;
    expect(() =>
      parseConfiguration(encodeEnvelope(configuration.signer.key_id, payload, signature))
    ).toThrow('envelope_signature');
  });
});

describe('configuration validation', () => {
  it('accepts the complete Protocol v1 fixture', () => {
    expect(validate(validConfiguration())).toEqual([]);
  });

  it.each(['', '0', '01', '+1', '-1', '2147483648'])('rejects client build %j', (value) => {
    const issues = validate(validConfiguration({ minimum_client_version: value }));
    expect(issues.some((issue) => issue.path === 'minimum_client_version')).toBe(true);
  });

  it.each(['AA==', 'A'.repeat(42), 'not+a-key'])('rejects noncanonical raw key %j', (value) => {
    const configuration = validConfiguration({
      signer: { key_id: 'protocol-signer', public_key: value }
    });
    expect(validate(configuration)).toContainEqual({ path: 'signer.public_key', code: 'key_invalid' });
  });

  it('enforces integer millimetres and their physical bound', () => {
    const configuration = validConfiguration();
    const location = configuration.collectors[1] as Extract<
      StudyConfiguration['collectors'][number],
      { id: 'location.v1' }
    >;
    location.config.minimum_displacement_millimeters = -1;
    expect(validate(configuration).some((issue) =>
      issue.path.endsWith('minimum_displacement_millimeters')
    )).toBe(true);
  });
});
