/**
 * Every string asserted here came out of the other implementation, not out of this one.
 *
 * The float renderings are `Float.toString` on a real JDK, checked in bulk over two million values
 * before the interesting ones were written down; the whole-document snapshots are what
 * `researcher-tools canonicalize` writes for the same input. Nothing here proves the encoder agrees
 * with itself — a test that did would pass just as happily on bytes no device accepts.
 */

import { describe, expect, it } from 'vitest';
import {
  canonicalBytes,
  canonicalize,
  escapeJsonString,
  formatFloat,
  formatInstant,
  parseInstant
} from '../src/lib/adc/canonical';
import type { CollectorConfig, StudyConfiguration, TinkKeyset } from '../src/lib/adc/types';

/**
 * The demonstration keyset from `researcher-tools/examples`. It is a real, working keyset, which
 * makes it the right fixture for the one field this encoder re-emits rather than composes.
 */
const DEMO_KEYSET: TinkKeyset = {
  primaryKeyId: 218992727,
  key: [
    {
      keyData: {
        typeUrl: 'type.googleapis.com/google.crypto.tink.HpkePublicKey',
        value: 'EgYIARABGAIaIBpyQ3w4fFx9XgEUx5kyzZaIPXLq7aYU6RJ+y9+rGNEA',
        keyMaterialType: 'ASYMMETRIC_PUBLIC'
      },
      status: 'ENABLED',
      keyId: 218992727,
      outputPrefixType: 'TINK'
    }
  ]
};

const DEMO_SUMMARY =
  "This demonstration can collect precise location, motion, network state, aggregate Wi-Fi/mobile usage, app and screen usage events, this app's lifecycle, and touch dynamics made inside the optional research keyboard. It never records keyboard text. Data stays encrypted on this device until you choose Export. You can pause, withdraw, export repeatedly, or delete local data.";

const DEMO_PUBLIC_KEY = 'MCowBQYDK2VwAyEAsRSaTpZmTSBL7eN6nS/HBsNmLM8n1hdRmIt1vtLZsC0=';

/** `researcher-tools/examples/demo-study.json`, as the site would hold it. */
const demoStudy: StudyConfiguration = {
  schema_version: 1,
  experiment_id: 'modular-sensing-demo',
  configuration_id: 'demo-config-2026',
  issued_at: '2026-01-01T00:00:00Z',
  expires_at: '2035-01-01T00:00:00Z',
  minimum_app_version: 1,
  title: 'Modular sensing demonstration',
  researcher: {
    name: 'Android Data Collector maintainers',
    contact: 'research@example.invalid'
  },
  purpose:
    'Verify the complete on-device collection, pause, export, and researcher-decryption loop.',
  duration_hours: 24,
  consent: { document_version: 'demo-1', summary: DEMO_SUMMARY },
  collectors: [
    { id: 'app_lifecycle.v1', required: true, config: {} },
    {
      id: 'accelerometer.v1',
      required: true,
      config: { sampling_period_us: 100000, maximum_report_latency_us: 1000000 }
    },
    { id: 'network_state.v1', required: true, config: { include_bandwidth_estimates: true } },
    {
      id: 'network_usage.v1',
      required: false,
      config: { transports: ['mobile', 'wifi'], poll_interval_minutes: 5 }
    },
    { id: 'usage_events.v1', required: false, config: { poll_interval_minutes: 15 } },
    {
      id: 'location.v1',
      required: false,
      config: {
        interval_millis: 10000,
        minimum_interval_millis: 5000,
        maximum_batch_delay_millis: 30000,
        minimum_displacement_meters: 5,
        priority: 'BALANCED'
      }
    },
    { id: 'keyboard_touch.v1', required: false, config: { trajectory_sampling_hz: 60 } }
  ],
  prompts: [
    {
      id: 'demo-check-in',
      delay_minutes: 60,
      message: 'Please check that the study is still running as expected.'
    }
  ],
  storage: { maximum_local_bytes: 16777216 },
  signer: { key_id: 'demo-signer-2026', public_key: DEMO_PUBLIC_KEY },
  export: { researcher_key_id: 'demo-hpke-2026', tink_hpke_public_keyset: DEMO_KEYSET },
  upload: null
};

function withCollectors(collectors: CollectorConfig[]): StudyConfiguration {
  return { ...demoStudy, collectors };
}

describe('formatFloat', () => {
  it('reproduces the renderings measured against the CLI', () => {
    expect(formatFloat(0)).toBe('0.0');
    expect(formatFloat(5)).toBe('5.0');
    expect(formatFloat(100)).toBe('100.0');
    expect(formatFloat(0.25)).toBe('0.25');
    expect(formatFloat(0.1)).toBe('0.1');
    expect(formatFloat(0.3)).toBe('0.3');
    expect(formatFloat(9999.999)).toBe('9999.999');
    expect(formatFloat(1234.5678)).toBe('1234.5677');
  });

  it('always writes a fractional digit, and keeps the sign of zero', () => {
    expect(formatFloat(10000)).toBe('10000.0');
    expect(formatFloat(1)).toBe('1.0');
    expect(formatFloat(-0)).toBe('-0.0');
    expect(formatFloat(-2.5)).toBe('-2.5');
    expect(formatFloat(-0.1)).toBe('-0.1');
  });

  it('breaks a tie toward the even digit, as the JDK does', () => {
    expect(formatFloat(4618.53125)).toBe('4618.5312');
    expect(formatFloat(6806.40625)).toBe('6806.4062');
    expect(formatFloat(0.03125)).toBe('0.03125');
  });

  it('prefers two digits over one where a two-digit decimal is closer', () => {
    // Float.MIN_VALUE. `1.0E-45` also round-trips, and is not what Java writes.
    expect(formatFloat(1.401298464324817e-45)).toBe('1.4E-45');
  });

  it('switches to exponent form exactly where Java does', () => {
    expect(formatFloat(0.001)).toBe('0.001');
    expect(formatFloat(0.0001)).toBe('1.0E-4');
    expect(formatFloat(9999999)).toBe('9999999.0');
    expect(formatFloat(1e7)).toBe('1.0E7');
    expect(formatFloat(3.4028235e38)).toBe('3.4028235E38');
  });

  it('round-trips every rendering back to the same float32', () => {
    for (let step = 0; step <= 400; step++) {
      const value = (step / 400) * 10_000;
      expect(Math.fround(Number(formatFloat(value)))).toBe(Math.fround(value));
      expect(formatFloat(value)).toMatch(/^-?(\d+\.\d+|\d\.\d+E-?\d+)$/);
    }
  });

  it('is shortest for a float32, not for the double holding it', () => {
    // What `Number.prototype.toString` would have written for the same value.
    expect(String(Math.fround(0.1))).toBe('0.10000000149011612');
    expect(formatFloat(0.1)).toBe('0.1');
    expect(String(Math.fround(1234.5678))).toBe('1234.5677490234375');
    expect(formatFloat(1234.5678)).toBe('1234.5677');
  });
});

describe('escapeJsonString', () => {
  it('uses Gson’s default table, not the HTML-safe one', () => {
    expect(escapeJsonString('a"b')).toBe('a\\"b');
    expect(escapeJsonString('a\\b')).toBe('a\\\\b');
    expect(escapeJsonString('\b\f\n\r\t')).toBe('\\b\\f\\n\\r\\t');
    expect(escapeJsonString('\u0000')).toBe('\\u0000');
    expect(escapeJsonString('\u000b')).toBe('\\u000b');
    expect(escapeJsonString('\u001f')).toBe('\\u001f');
    expect(escapeJsonString('\u2028\u2029')).toBe('\\u2028\\u2029');
  });

  it('leaves alone everything an HTML-safe writer would have escaped', () => {
    expect(escapeJsonString('</a> & b = \'c\'')).toBe('</a> & b = \'c\'');
    expect(escapeJsonString('\u007f')).toBe('\u007f');
    expect(escapeJsonString('é')).toBe('é');
    expect(escapeJsonString('研究 \u{1f512}')).toBe('研究 \u{1f512}');
  });

  it('returns the input untouched when there is nothing to escape', () => {
    expect(escapeJsonString('')).toBe('');
    expect(escapeJsonString('plain')).toBe('plain');
  });
});

describe('parseInstant', () => {
  it('re-spells any accepted instant the way Instant.toString would', () => {
    const respell = (text: string) => formatInstant(parseInstant(text)!);
    expect(respell('2026-01-01T00:00:00Z')).toBe('2026-01-01T00:00:00Z');
    expect(respell('2026-01-01T00:00:00.000Z')).toBe('2026-01-01T00:00:00Z');
    expect(respell('2026-01-01T00:00:00.120Z')).toBe('2026-01-01T00:00:00.120Z');
    expect(respell('2026-01-01T00:00:00.000001Z')).toBe('2026-01-01T00:00:00.000001Z');
    expect(respell('2026-01-01T00:00:00.000000001Z')).toBe('2026-01-01T00:00:00.000000001Z');
    expect(respell('2026-01-01T08:30:00+08:00')).toBe('2026-01-01T00:30:00Z');
    expect(respell('2024-02-29T23:59:59Z')).toBe('2024-02-29T23:59:59Z');
  });

  it('refuses what Instant.parse refuses', () => {
    expect(parseInstant('')).toBeNull();
    expect(parseInstant('2026-01-01')).toBeNull();
    expect(parseInstant('2026-01-01T00:00Z')).toBeNull();
    expect(parseInstant('2026-01-01T00:00:00')).toBeNull();
    expect(parseInstant('2026-02-30T00:00:00Z')).toBeNull();
    expect(parseInstant('2023-02-29T00:00:00Z')).toBeNull();
    expect(parseInstant('2026-13-01T00:00:00Z')).toBeNull();
    expect(parseInstant('2026-01-01T24:00:00Z')).toBeNull();
  });
});

describe('canonicalize', () => {
  it('matches the demonstration study byte for byte', () => {
    expect(canonicalize(demoStudy)).toBe(
      '{"schema_version":1' +
        ',"experiment_id":"modular-sensing-demo"' +
        ',"configuration_id":"demo-config-2026"' +
        ',"issued_at":"2026-01-01T00:00:00Z"' +
        ',"expires_at":"2035-01-01T00:00:00Z"' +
        ',"minimum_app_version":1' +
        ',"title":"Modular sensing demonstration"' +
        ',"researcher":{"name":"Android Data Collector maintainers"' +
        ',"contact":"research@example.invalid"}' +
        ',"purpose":"Verify the complete on-device collection, pause, export, and researcher-decryption loop."' +
        ',"duration_hours":24' +
        ',"consent":{"document_version":"demo-1","summary":"' +
        DEMO_SUMMARY +
        '"}' +
        ',"collectors":[' +
        '{"id":"app_lifecycle.v1","required":true,"config":{}}' +
        ',{"id":"accelerometer.v1","required":true,"config":{"sampling_period_us":100000,"maximum_report_latency_us":1000000}}' +
        ',{"id":"network_state.v1","required":true,"config":{"include_bandwidth_estimates":true}}' +
        ',{"id":"network_usage.v1","required":false,"config":{"transports":["mobile","wifi"],"poll_interval_minutes":5}}' +
        ',{"id":"usage_events.v1","required":false,"config":{"poll_interval_minutes":15}}' +
        ',{"id":"location.v1","required":false,"config":{"interval_millis":10000,"minimum_interval_millis":5000,"maximum_batch_delay_millis":30000,"minimum_displacement_meters":5.0,"priority":"BALANCED"}}' +
        ',{"id":"keyboard_touch.v1","required":false,"config":{"trajectory_sampling_hz":60}}' +
        ']' +
        ',"prompts":[{"id":"demo-check-in","delay_minutes":60,"message":"Please check that the study is still running as expected."}]' +
        ',"storage":{"maximum_local_bytes":16777216}' +
        ',"signer":{"key_id":"demo-signer-2026","public_key":"' +
        DEMO_PUBLIC_KEY +
        '"}' +
        ',"export":{"researcher_key_id":"demo-hpke-2026","tink_hpke_public_keyset":' +
        '{"primaryKeyId":218992727,"key":[{"keyData":{"typeUrl":"type.googleapis.com/google.crypto.tink.HpkePublicKey"' +
        ',"value":"EgYIARABGAIaIBpyQ3w4fFx9XgEUx5kyzZaIPXLq7aYU6RJ+y9+rGNEA"' +
        ',"keyMaterialType":"ASYMMETRIC_PUBLIC"},"status":"ENABLED","keyId":218992727,"outputPrefixType":"TINK"}]}}' +
        ',"upload":{}}'
    );
  });

  it('writes a populated upload block, and text exactly as it was written', () => {
    const configuration: StudyConfiguration = {
      ...demoStudy,
      title: '心理韌性研究 "2026"',
      purpose: 'Line one\nline two\ttabbed',
      researcher: { name: 'Lin\\Chen', contact: 'lab@example.invalid' },
      prompts: [],
      collectors: [{ id: 'app_lifecycle.v1', required: true, config: {} }],
      upload: {
        endpoint: 'https://intake.example.invalid/v1/bundles?study=1',
        interval_minutes: 720,
        allow_metered: false
      }
    };
    const canonical = canonicalize(configuration);
    expect(canonical).toContain('"title":"心理韌性研究 \\"2026\\""');
    expect(canonical).toContain('"purpose":"Line one\\nline two\\ttabbed"');
    expect(canonical).toContain('"name":"Lin\\\\Chen"');
    expect(canonical).toContain('"prompts":[]');
    expect(canonical).toContain(
      '"upload":{"endpoint":"https://intake.example.invalid/v1/bundles?study=1","interval_minutes":720,"allow_metered":false}'
    );
    expect(canonical.endsWith('}')).toBe(true);
    expect(JSON.parse(canonical).title).toBe(configuration.title);
  });

  it('sorts transports by their Kotlin enum name and drops duplicates', () => {
    const encoded = (transports: ('mobile' | 'wifi')[]) =>
      canonicalize(
        withCollectors([
          {
            id: 'network_usage.v1',
            required: false,
            config: { transports, poll_interval_minutes: 30 }
          }
        ])
      );
    expect(encoded(['wifi', 'mobile'])).toContain('"transports":["mobile","wifi"]');
    expect(encoded(['mobile'])).toContain('"transports":["mobile"]');
    expect(encoded(['wifi', 'wifi'])).toContain('"transports":["wifi"]');
    expect(encoded([])).toContain('"transports":[]');
  });

  it('re-spells the validity window, so a browser-shaped instant still signs correctly', () => {
    const canonical = canonicalize({
      ...demoStudy,
      issued_at: new Date(Date.UTC(2026, 0, 1)).toISOString(),
      expires_at: '2026-04-01T08:00:00+08:00'
    });
    expect(canonical).toContain('"issued_at":"2026-01-01T00:00:00Z"');
    expect(canonical).toContain('"expires_at":"2026-04-01T00:00:00Z"');
  });

  it('carries a location displacement through Float.toString', () => {
    const encoded = (minimum_displacement_meters: number) =>
      canonicalize(
        withCollectors([
          {
            id: 'location.v1',
            required: false,
            config: {
              interval_millis: 60000,
              minimum_interval_millis: 30000,
              maximum_batch_delay_millis: 0,
              minimum_displacement_meters,
              priority: 'HIGH_ACCURACY'
            }
          }
        ])
      );
    expect(encoded(0)).toContain('"minimum_displacement_meters":0.0,"priority":"HIGH_ACCURACY"');
    expect(encoded(0.1)).toContain('"minimum_displacement_meters":0.1');
    expect(encoded(1234.5678)).toContain('"minimum_displacement_meters":1234.5677');
  });
});

describe('canonicalBytes', () => {
  it('is UTF-8, so a CJK document is longer in bytes than in characters', () => {
    const configuration = { ...demoStudy, title: '研究' };
    const bytes = canonicalBytes(configuration);
    expect(bytes).toBeInstanceOf(Uint8Array);
    expect(bytes.length).toBe(canonicalize(configuration).length + 4);
    expect(new TextDecoder().decode(bytes)).toBe(canonicalize(configuration));
  });
});
