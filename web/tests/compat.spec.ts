/**
 * The byte-compatibility harness: the one test that compares this site against the other
 * implementation instead of against itself.
 *
 * Every case here is encoded twice — once by `src/lib/adc/canonical.ts` in this process, once by
 * `researcher-tools canonicalize` in a JVM — and the two byte strings have to be identical. That is
 * the whole claim the site rests on, because `StudyConfigurationCodec.decode` re-encodes what it
 * parsed and refuses the file unless the bytes come back the same. A configuration this page signs
 * is verified over exactly those bytes on the device, so an encoder that writes merely *plausible*
 * JSON produces files that are correctly signed and rejected everywhere.
 *
 * The last block goes past canonicalisation: it generates a signing key here, signs here, builds the
 * envelope here, and then asks the CLI to verify the result. `check-config` succeeding is the
 * end-to-end statement that a `.adccfg` made in a browser is one the Android app will accept.
 *
 * @vitest-environment node
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { canonicalBytes, canonicalize } from '../src/lib/adc/canonical';
import { encodeEnvelope } from '../src/lib/adc/envelope';
import { fingerprint, generateSigningKeyPair, sign, verify } from '../src/lib/adc/crypto';
import { generateHpkeKeyset } from '../src/lib/adc/tink';
import type {
  CollectorConfig,
  NetworkTransport,
  StudyConfiguration,
  TinkKeyset
} from '../src/lib/adc/types';

const REPOSITORY = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CLI = join(REPOSITORY, 'researcher-tools/build/install/researcher-tools/bin/researcher-tools');

let workspace = '';
let sequence = 0;

beforeAll(() => {
  if (!existsSync(CLI)) {
    execFileSync(join(REPOSITORY, 'gradlew'), [':researcher-tools:installDist'], {
      cwd: REPOSITORY,
      stdio: 'inherit'
    });
  }
  workspace = mkdtempSync(join(tmpdir(), 'adc-compat-'));
}, 600_000);

afterAll(() => workspace && rmSync(workspace, { recursive: true, force: true }));

/** A fresh directory per invocation, because every CLI output path is opened `CREATE_NEW`. */
function scratch(): string {
  const directory = join(workspace, String(sequence++));
  mkdirSync(directory, { recursive: true });
  return directory;
}

function runCli(...args: string[]): string {
  try {
    return execFileSync(CLI, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (failure) {
    const detail = failure as { stderr?: string; stdout?: string; message?: string };
    throw new Error(
      `researcher-tools ${args.join(' ')}\n${detail.stderr || detail.stdout || detail.message}`
    );
  }
}

/**
 * The configuration as ordinary JSON, which is what a researcher would have written by hand. The
 * only translation is the absent upload block: the schema spells "no upload" as an empty object,
 * and `null` is not a shape the decoder accepts.
 */
function wireJson(configuration: StudyConfiguration): string {
  return JSON.stringify({ ...configuration, upload: configuration.upload ?? {} }, null, 2);
}

function cliCanonicalize(configuration: StudyConfiguration): Uint8Array {
  const directory = scratch();
  const input = join(directory, 'study.json');
  const output = join(directory, 'canonical.json');
  writeFileSync(input, wireJson(configuration), 'utf8');
  runCli('canonicalize', '--input', input, '--output', output);
  return readFileSync(output);
}

/** Where the two encodings part company, with enough either side to see what happened. */
function difference(site: Uint8Array, cli: Uint8Array): string {
  const text = new TextDecoder();
  const limit = Math.max(site.length, cli.length);
  for (let index = 0; index < limit; index++) {
    if (index < site.length && index < cli.length && site[index] === cli[index]) continue;
    const from = Math.max(0, index - 48);
    const at = (bytes: Uint8Array) =>
      index < bytes.length ? `0x${bytes[index].toString(16).padStart(2, '0')}` : 'end of output';
    return [
      `first difference at byte ${index} (site ${site.length} bytes, cli ${cli.length} bytes)`,
      `  site ${at(site)} ${JSON.stringify(text.decode(site.subarray(from, index + 48)))}`,
      `  cli  ${at(cli)} ${JSON.stringify(text.decode(cli.subarray(from, index + 48)))}`
    ].join('\n');
  }
  return 'identical';
}

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

const DEMO_PUBLIC_KEY = 'MCowBQYDK2VwAyEAsRSaTpZmTSBL7eN6nS/HBsNmLM8n1hdRmIt1vtLZsC0=';

const BASE: StudyConfiguration = {
  schema_version: 1,
  experiment_id: 'compat-harness',
  configuration_id: 'compat-case-001',
  issued_at: '2026-01-01T00:00:00Z',
  expires_at: '2035-01-01T00:00:00Z',
  minimum_app_version: 1,
  title: 'Byte compatibility harness',
  researcher: { name: 'Harness', contact: 'harness@example.invalid' },
  purpose: 'Prove the browser encoder and the JVM encoder write the same bytes.',
  duration_hours: 24,
  consent: { document_version: 'harness-1', summary: 'A fixture. Nothing is collected.' },
  collectors: [{ id: 'app_lifecycle.v1', required: true, config: {} }],
  prompts: [],
  storage: { maximum_local_bytes: 16 * 1024 * 1024 },
  signer: { key_id: 'compat-signer', public_key: DEMO_PUBLIC_KEY },
  export: { researcher_key_id: 'compat-hpke', tink_hpke_public_keyset: DEMO_KEYSET },
  upload: null
};

function study(overrides: Partial<StudyConfiguration>): StudyConfiguration {
  return { ...BASE, ...overrides };
}

function everyCollector(required: boolean): CollectorConfig[] {
  return [
    { id: 'app_lifecycle.v1', required, config: {} },
    {
      id: 'accelerometer.v1',
      required,
      config: { sampling_period_us: 100_000, maximum_report_latency_us: 1_000_000 }
    },
    { id: 'network_state.v1', required, config: { include_bandwidth_estimates: true } },
    {
      id: 'network_usage.v1',
      required,
      config: { transports: ['mobile', 'wifi'], poll_interval_minutes: 5 }
    },
    { id: 'usage_events.v1', required, config: { poll_interval_minutes: 15 } },
    {
      id: 'location.v1',
      required,
      config: {
        interval_millis: 10_000,
        minimum_interval_millis: 5_000,
        maximum_batch_delay_millis: 30_000,
        minimum_displacement_meters: 5,
        priority: 'BALANCED'
      }
    },
    { id: 'keyboard_touch.v1', required, config: { trajectory_sampling_hz: 60 } }
  ];
}

type LocationConfig = Extract<CollectorConfig, { id: 'location.v1' }>['config'];

function location(overrides: Partial<LocationConfig>): StudyConfiguration {
  return study({
    collectors: [
      {
        id: 'location.v1',
        required: false,
        config: {
          interval_millis: 10_000,
          minimum_interval_millis: 5_000,
          maximum_batch_delay_millis: 30_000,
          minimum_displacement_meters: 5,
          priority: 'BALANCED',
          ...overrides
        }
      }
    ]
  });
}

function usage(transports: NetworkTransport[]): StudyConfiguration {
  return study({
    collectors: [
      { id: 'network_usage.v1', required: true, config: { transports, poll_interval_minutes: 30 } }
    ]
  });
}

/** Written by code point rather than as escapes, so nothing in this file can be read two ways. */
const character = (code: number) => String.fromCharCode(code);
const LINE_SEPARATOR = character(0x2028);
const PARAGRAPH_SEPARATOR = character(0x2029);
const DELETE = character(0x7f);

/** Every code point Gson escapes as `\u00xx`, plus the five with short forms, in order. */
const CONTROLS = Array.from({ length: 0x20 }, (_, code) => character(code)).join('');

/**
 * Everything Gson's escape table has an opinion about, in one string: the whole control range, the
 * two characters it escapes above `0x20`, and a spread of characters it deliberately leaves raw
 * because the writer is not in HTML-safe mode.
 */
const NASTY =
  '研究「同意」書 😀🇹🇼 — "quoted" \\backslash\\ /slash/ <tag> & = \' ' +
  CONTROLS +
  ' ' +
  LINE_SEPARATOR +
  'line' +
  PARAGRAPH_SEPARATOR +
  'para ' +
  DELETE +
  ' ünïcödé ½ ∑ 🇯🇵👩‍👩‍👧‍👦';

/** The demonstration study the CLI ships, held the way the site holds it. */
const DEMO: StudyConfiguration = {
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
  consent: {
    document_version: 'demo-1',
    summary:
      "This demonstration can collect precise location, motion, network state, aggregate Wi-Fi/mobile usage, app and screen usage events, this app's lifecycle, and touch dynamics made inside the optional research keyboard. It never records keyboard text. Data stays encrypted on this device until you choose Export. You can pause, withdraw, export repeatedly, or delete local data."
  },
  collectors: [
    { id: 'app_lifecycle.v1', required: true, config: {} },
    {
      id: 'accelerometer.v1',
      required: true,
      config: { sampling_period_us: 100_000, maximum_report_latency_us: 1_000_000 }
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
        interval_millis: 10_000,
        minimum_interval_millis: 5_000,
        maximum_batch_delay_millis: 30_000,
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
  storage: { maximum_local_bytes: 16_777_216 },
  signer: { key_id: 'demo-signer-2026', public_key: DEMO_PUBLIC_KEY },
  export: { researcher_key_id: 'demo-hpke-2026', tink_hpke_public_keyset: DEMO_KEYSET },
  upload: null
};

/**
 * The values that separate `Float.toString` from every shorter implementation: the two the schema
 * will actually see, the ones whose double rendering differs from their float one, the tie Java
 * breaks towards the even digit, and both scientific-notation switches the bounds can still reach.
 */
const DISPLACEMENTS = [
  0, 5, 0.25, 1234.5678, 0.1, 0.3, 9999.999, 10_000, 4618.53125, 0.001, 0.0001, 1e-5, 1.4e-45
];

const CASES: Array<{ name: string; configuration: StudyConfiguration }> = [
  { name: 'the demonstration study the CLI ships', configuration: DEMO },
  {
    name: 'every collector, all required, with upload and prompts',
    configuration: study({
      collectors: everyCollector(true),
      prompts: [
        { id: 'check-in-one', delay_minutes: 60, message: 'Still running?' },
        { id: 'check-in-two', delay_minutes: 1_440, message: '研究の確認 😀' }
      ],
      upload: {
        endpoint: 'https://uploads.example.invalid/v1',
        interval_minutes: 60,
        allow_metered: true
      }
    })
  },
  {
    name: 'every collector, none required, no upload, no prompts',
    configuration: study({ collectors: everyCollector(false), prompts: [], upload: null })
  },

  ...everyCollector(true).map((collector) => ({
    name: `only ${collector.id}, required`,
    configuration: study({ collectors: [collector] })
  })),
  ...everyCollector(false).map((collector) => ({
    name: `only ${collector.id}, optional`,
    configuration: study({ collectors: [collector] })
  })),

  ...DISPLACEMENTS.map((meters) => ({
    name: `minimum_displacement_meters ${meters}`,
    configuration: location({ minimum_displacement_meters: meters })
  })),

  {
    name: 'text: CJK, emoji, quotes, backslashes, newlines and the whole control range',
    configuration: study({
      title: '研究 "「」" \\ <b>&</b> 😀 line\nbreak\ttab',
      researcher: { name: '林\t"Lin"\\研究員 😀', contact: 'mail@example.invalid\n<研究>' },
      purpose: NASTY,
      consent: { document_version: 'v1\\"研究"', summary: NASTY },
      prompts: [{ id: 'nasty-prompt', delay_minutes: 5, message: NASTY.slice(0, 200) }],
      upload: {
        endpoint: 'https://uploads.example.invalid/v1?a=b&c=d#研究',
        interval_minutes: 15,
        allow_metered: false
      }
    })
  },
  {
    name: 'text: the characters Gson leaves raw because it is not HTML-safe',
    configuration: study({
      title: `< > & = ' / ${DELETE}`,
      purpose: `<script>a&&b='c'</script>${DELETE}`
    })
  },
  {
    name: 'text: nothing but a line separator and a paragraph separator',
    configuration: study({
      title: LINE_SEPARATOR + PARAGRAPH_SEPARATOR,
      consent: { document_version: LINE_SEPARATOR, summary: PARAGRAPH_SEPARATOR }
    })
  },

  {
    name: 'bounds: every numeric field at its minimum',
    configuration: study({
      minimum_app_version: 1,
      duration_hours: 1,
      title: 'A',
      researcher: { name: 'A', contact: 'a@b' },
      purpose: 'P',
      consent: { document_version: 'v', summary: 'S' },
      experiment_id: 'a-b',
      configuration_id: 'a-b',
      signer: { key_id: 'a-b', public_key: 'A'.repeat(32) },
      export: { researcher_key_id: 'a-b', tink_hpke_public_keyset: DEMO_KEYSET },
      storage: { maximum_local_bytes: 8 * 1024 * 1024 },
      collectors: [
        {
          id: 'accelerometer.v1',
          required: false,
          config: { sampling_period_us: 5_000, maximum_report_latency_us: 0 }
        },
        {
          id: 'network_usage.v1',
          required: false,
          config: { transports: ['mobile'], poll_interval_minutes: 1 }
        },
        { id: 'usage_events.v1', required: false, config: { poll_interval_minutes: 1 } },
        {
          id: 'location.v1',
          required: false,
          config: {
            interval_millis: 1_000,
            minimum_interval_millis: 500,
            maximum_batch_delay_millis: 0,
            minimum_displacement_meters: 0,
            priority: 'BALANCED'
          }
        },
        { id: 'keyboard_touch.v1', required: false, config: { trajectory_sampling_hz: 1 } }
      ],
      prompts: [{ id: 'a-b', delay_minutes: 1, message: 'M' }],
      upload: { endpoint: 'https://a', interval_minutes: 1, allow_metered: false }
    })
  },
  {
    name: 'bounds: every numeric field at its maximum',
    configuration: study({
      minimum_app_version: 2_147_483_647,
      duration_hours: 8_760,
      title: 'T'.repeat(120),
      researcher: { name: 'N'.repeat(120), contact: 'C'.repeat(240) },
      purpose: 'P'.repeat(2_000),
      consent: { document_version: 'V'.repeat(64), summary: 'S'.repeat(8_000) },
      experiment_id: `a${'b'.repeat(63)}`,
      configuration_id: `9${'-c9'.repeat(21)}`,
      signer: { key_id: `z${'-y9'.repeat(21)}`, public_key: 'K'.repeat(1_024) },
      storage: { maximum_local_bytes: 8 * 1024 * 1024 * 1024 },
      collectors: [
        {
          id: 'accelerometer.v1',
          required: true,
          config: { sampling_period_us: 1_000_000, maximum_report_latency_us: 60_000_000 }
        },
        {
          id: 'network_usage.v1',
          required: true,
          config: { transports: ['mobile', 'wifi'], poll_interval_minutes: 1_440 }
        },
        { id: 'usage_events.v1', required: true, config: { poll_interval_minutes: 1_440 } },
        {
          id: 'location.v1',
          required: true,
          config: {
            interval_millis: 3_600_000,
            minimum_interval_millis: 3_600_000,
            maximum_batch_delay_millis: 86_400_000,
            minimum_displacement_meters: 10_000,
            priority: 'HIGH_ACCURACY'
          }
        },
        { id: 'keyboard_touch.v1', required: true, config: { trajectory_sampling_hz: 120 } }
      ],
      prompts: [{ id: `p${'-q8'.repeat(21)}`, delay_minutes: 525_600, message: 'M'.repeat(500) }],
      upload: {
        endpoint: `https://e.invalid/${'a'.repeat(2_030)}`,
        interval_minutes: 10_080,
        allow_metered: true
      }
    })
  },

  { name: 'transports: mobile only', configuration: usage(['mobile']) },
  { name: 'transports: wifi only', configuration: usage(['wifi']) },
  { name: 'transports: written in the other order', configuration: usage(['wifi', 'mobile']) },
  { name: 'transports: repeated', configuration: usage(['wifi', 'wifi']) },

  { name: 'prompts: absent', configuration: study({ prompts: [] }) },
  {
    name: 'prompts: three of them, at both delay bounds',
    configuration: study({
      prompts: [
        { id: 'first-prompt', delay_minutes: 1, message: 'A' },
        { id: 'second-prompt', delay_minutes: 262_800, message: '請確認研究仍在執行 😀' },
        { id: 'third-prompt', delay_minutes: 525_600, message: 'Z'.repeat(500) }
      ]
    })
  },

  {
    name: 'upload: present, metered allowed, shortest interval',
    configuration: study({
      upload: {
        endpoint: 'https://uploads.example.invalid/ingest',
        interval_minutes: 1,
        allow_metered: true
      }
    })
  },
  { name: 'upload: absent', configuration: study({ upload: null }) },

  {
    name: 'instants: fractional seconds Instant.toString keeps',
    configuration: study({
      issued_at: '2026-01-01T00:00:00.123456789Z',
      expires_at: '2035-06-30T23:59:59.999999999Z'
    })
  },
  {
    name: 'instants: spellings Instant.toString rewrites',
    configuration: study({
      issued_at: '2025-12-31T16:00:00.000-08:00',
      expires_at: '2035-01-01T00:00:00.1Z'
    })
  },
  {
    name: 'instants: a leap day and the end of a year',
    configuration: study({ issued_at: '2028-02-29T23:59:59Z', expires_at: '2035-12-31T23:59:59Z' })
  },

  {
    name: 'keyset: a key ID above the signed 32-bit range',
    configuration: study({
      export: {
        researcher_key_id: 'compat-hpke',
        tink_hpke_public_keyset: {
          primaryKeyId: 4_294_967_295,
          key: [
            {
              keyData: {
                typeUrl: 'type.googleapis.com/google.crypto.tink.HpkePublicKey',
                value: 'EgYIARABGAIaIBpyQ3w4fFx9XgEUx5kyzZaIPXLq7aYU6RJ+y9+rGNEA',
                keyMaterialType: 'ASYMMETRIC_PUBLIC'
              },
              status: 'ENABLED',
              keyId: 4_294_967_295,
              outputPrefixType: 'TINK'
            }
          ]
        }
      }
    })
  },
  {
    name: 'keyset: fields in another order, which the codec has to preserve',
    configuration: study({
      export: {
        researcher_key_id: 'compat-hpke',
        tink_hpke_public_keyset: {
          key: [
            {
              outputPrefixType: 'TINK',
              keyId: 218992727,
              status: 'ENABLED',
              keyData: {
                keyMaterialType: 'ASYMMETRIC_PUBLIC',
                value: 'EgYIARABGAIaIBpyQ3w4fFx9XgEUx5kyzZaIPXLq7aYU6RJ+y9+rGNEA',
                typeUrl: 'type.googleapis.com/google.crypto.tink.HpkePublicKey'
              }
            }
          ],
          primaryKeyId: 218992727
        } as unknown as TinkKeyset
      }
    })
  }
];

describe('canonicalize matches researcher-tools byte for byte', () => {
  for (const testCase of CASES) {
    it(
      testCase.name,
      () => {
        const site = canonicalBytes(testCase.configuration);
        const report = difference(site, cliCanonicalize(testCase.configuration));
        expect(report).toBe('identical');
      },
      60_000
    );
  }
});

/**
 * Past canonicalisation: a study whose keys were made here, signed here, packaged here, and handed
 * to the verifier the app uses. Ed25519 is deterministic, so the JVM signing the same bytes with
 * the same key has to produce the same signature — which makes the envelope comparison an equality
 * rather than merely "both of them verify".
 */
describe('a browser-made .adccfg is one the app accepts', () => {
  it(
    'verifies self-certifying and pinned, and matches what the CLI would have signed',
    () => {
      const signing = generateSigningKeyPair();
      const hpke = generateHpkeKeyset();
      const keyId = 'web-made-signer';

      const configuration = study({
        experiment_id: 'browser-end-to-end',
        configuration_id: 'browser-config-001',
        title: '瀏覽器簽署的研究 😀 "end to end"',
        consent: { document_version: 'e2e-1', summary: NASTY },
        collectors: everyCollector(true),
        prompts: [{ id: 'e2e-prompt', delay_minutes: 30, message: '請確認 😀' }],
        signer: { key_id: keyId, public_key: signing.publicX509Base64 },
        export: { researcher_key_id: 'browser-hpke', tink_hpke_public_keyset: hpke.publicKeyset },
        upload: {
          endpoint: 'https://uploads.example.invalid/ingest',
          interval_minutes: 120,
          allow_metered: false
        }
      });

      const bytes = canonicalBytes(configuration);
      const signature = sign(bytes, signing.privatePkcs8Base64);
      expect(verify(bytes, signature, signing.publicX509Base64)).toBe(true);
      const envelope = encodeEnvelope(keyId, bytes, signature);

      const directory = scratch();
      const envelopePath = join(directory, 'study.adccfg');
      const publicPath = join(directory, 'signer.pub');
      const privatePath = join(directory, 'signer.key');
      const canonicalPath = join(directory, 'canonical.json');
      const cliEnvelopePath = join(directory, 'cli.adccfg');
      writeFileSync(envelopePath, envelope);
      writeFileSync(publicPath, signing.publicX509Base64, 'utf8');
      writeFileSync(privatePath, signing.privatePkcs8Base64, 'utf8');
      writeFileSync(canonicalPath, bytes);

      const selfCertifying = runCli(
        'check-config',
        '--envelope',
        envelopePath,
        '--now',
        '2026-06-01T00:00:00Z'
      );
      expect(selfCertifying).toContain('valid browser-end-to-end browser-config-001');
      expect(selfCertifying).toContain(`signer ${keyId} ${fingerprint(signing.publicX509Base64)}`);
      expect(selfCertifying).toContain('pinned no (self-certifying)');

      const pinned = runCli(
        'check-config',
        '--envelope',
        envelopePath,
        '--public',
        publicPath,
        '--key-id',
        keyId,
        '--app-version',
        '1',
        '--now',
        '2026-06-01T00:00:00Z'
      );
      expect(pinned).toContain('pinned yes');

      runCli(
        'sign',
        '--config',
        canonicalPath,
        '--private',
        privatePath,
        '--key-id',
        keyId,
        '--output',
        cliEnvelopePath
      );
      expect(difference(envelope, readFileSync(cliEnvelopePath))).toBe('identical');
    },
    120_000
  );

  /**
   * `JSON.stringify(-0)` is `0`, so a negative zero cannot reach the CLI through an ordinary JSON
   * file and cannot be a case in the table above. It can still reach a number input, and both ends
   * agree it is `-0.0` — which the round trip below is the proof of.
   */
  it(
    'writes a negative zero displacement the way the device re-encodes it',
    () => {
      const bytes = canonicalBytes(location({ minimum_displacement_meters: -0 }));
      expect(new TextDecoder().decode(bytes)).toContain('"minimum_displacement_meters":-0.0');
      const directory = scratch();
      const input = join(directory, 'canonical.json');
      const output = join(directory, 'roundtrip.json');
      writeFileSync(input, bytes);
      runCli('canonicalize', '--input', input, '--output', output);
      expect(difference(bytes, readFileSync(output))).toBe('identical');
    },
    60_000
  );

  it(
    'canonicalises its own output to itself, which is what the device checks',
    () => {
      const directory = scratch();
      const canonicalPath = join(directory, 'canonical.json');
      const output = join(directory, 'roundtrip.json');
      writeFileSync(canonicalPath, canonicalize(DEMO), 'utf8');
      runCli('canonicalize', '--input', canonicalPath, '--output', output);
      expect(difference(canonicalBytes(DEMO), readFileSync(output))).toBe('identical');
    },
    60_000
  );
});
