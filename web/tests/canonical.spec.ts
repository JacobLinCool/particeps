import { describe, expect, it } from 'vitest';
import {
  canonicalConfigurationBytes,
  canonicalize,
  canonicalizeConfiguration,
  formatInstant,
  parseCanonicalJson,
  parseInstant
} from '../src/lib/particeps/canonical';
import { validConfiguration } from './fixture';

describe('RFC 8785 JCS', () => {
  it('sorts recursively by UTF-16 member names and uses ECMAScript primitives', () => {
    expect(
      canonicalize({ z: 1, a: { '\u20ac': 'Euro', '\r': 'CR', '1': true }, n: -0 })
    ).toBe('{"a":{"\\r":"CR","1":true,"€":"Euro"},"n":0,"z":1}');
    expect(canonicalize({ numbers: [333333333.33333329, 1e30, 4.5, 2e-3, 1e-27] })).toBe(
      '{"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27]}'
    );
  });

  it('rejects values outside I-JSON instead of repairing them', () => {
    expect(() => canonicalize({ value: Number.NaN })).toThrow('jcs_number');
    expect(() => canonicalize({ value: BigInt(1) })).toThrow('jcs_type');
    expect(() => canonicalize({ value: '\ud800' })).toThrow('jcs_unicode');
    const cycle: Record<string, unknown> = {};
    cycle.self = cycle;
    expect(() => canonicalize(cycle)).toThrow('jcs_cycle');
  });

  it('accepts only byte-for-byte canonical UTF-8 JSON', () => {
    expect(parseCanonicalJson(new TextEncoder().encode('{"a":1,"b":2}'))).toEqual({ a: 1, b: 2 });
    for (const hostile of [' {"a":1}', '{"b":2,"a":1}', '{"a":1,"a":1}', '{"a":1.0}']) {
      expect(() => parseCanonicalJson(new TextEncoder().encode(hostile))).toThrow();
    }
    expect(() => parseCanonicalJson(Uint8Array.of(0x7b, 0x22, 0xff, 0x22, 0x3a, 0x31, 0x7d))).toThrow();
  });
});

describe('Protocol v1 configuration value', () => {
  it('uses JCS, an empty upload object, raw keys, decimal client version, and integer millimetres', () => {
    const configuration = validConfiguration();
    const text = canonicalizeConfiguration(configuration);
    expect(text).toBe(new TextDecoder().decode(canonicalConfigurationBytes(configuration)));
    expect(text).toContain('"minimum_client_version":"1"');
    expect(text).toContain('"minimum_displacement_millimeters":25000');
    expect(text).toContain('"platform":"android"');
    expect(text).toContain('"upload":{}');
    expect(text).toContain(`"hpke_public_key":"${configuration.export.hpke_public_key}"`);
    expect(text).not.toMatch(/tink|minimum_app_version|minimum_displacement_meters/);
  });

  it('normalizes instants but never silently repairs signed resource arrays', () => {
    const configuration = validConfiguration({ issued_at: '2026-01-01T08:00:00+08:00' });
    const text = canonicalizeConfiguration(configuration);
    expect(text).toContain('"issued_at":"2026-01-01T00:00:00Z"');
    const network = configuration.collectors[0];
    expect(canonicalizeConfiguration({ ...configuration, collectors: [network] })).toContain('"profiles"');
  });
});

describe('instant authoring', () => {
  it('parses offsets and emits canonical UTC', () => {
    expect(formatInstant(parseInstant('2026-01-01T08:00:00+08:00')!)).toBe('2026-01-01T00:00:00Z');
    expect(parseInstant('2026-02-30T00:00:00Z')).toBeNull();
    expect(parseInstant('2026-01-01T00:00Z')).toBeNull();
  });
});
