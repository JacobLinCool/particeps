import { describe, expect, it } from 'vitest';
import demoSigningPrivateKey from '../../researcher-tools/examples/INSECURE-demo-signing-private.key?raw';
import demoHpkePrivateKeyset from '../../researcher-tools/examples/INSECURE-demo-hpke-private.json?raw';
import demoStudyJson from '../../researcher-tools/examples/demo-study.json?raw';
import { canonicalBytes, canonicalize, keysetJson } from '$lib/adc/canonical';
import { encodeEnvelope } from '$lib/adc/envelope';
import { fingerprint, generateSigningKeyPair, sign, verify } from '$lib/adc/crypto';
import { generateHpkeKeyset } from '$lib/adc/tink';
import { emptyConfiguration, validate } from '$lib/adc/schema';
import { hpkeKeysetFromPrivate, signingKeyPairFromPrivate } from '../src/routes/researcher/keys';
import { decodeEnvelope, parseConfiguration } from '../src/routes/researcher/parse';
import { units } from '../src/routes/researcher/units';
import { estimate, intensityOf } from '../src/routes/researcher/estimate';
import { stepForPath } from '../src/routes/researcher/steps';
import { en } from '$lib/i18n/en';
import { zhTW } from '$lib/i18n/zh-TW';

const demoStudy = JSON.parse(demoStudyJson);
const demoStudyBytes = new TextEncoder().encode(demoStudyJson);

describe('key import', () => {
  it('derives the same public half the CLI would, from the committed demo key', () => {
    const pair = signingKeyPairFromPrivate(demoSigningPrivateKey);
    expect(pair.publicX509Base64).toBe(demoStudy.signer.public_key);
    expect(pair.privatePkcs8Base64).toBe(demoSigningPrivateKey.trim());
    expect(fingerprint(pair.publicX509Base64)).toBe(fingerprint(demoStudy.signer.public_key));
  });

  it('rebuilds the demo HPKE keyset from its private half, byte for byte', () => {
    const keyset = hpkeKeysetFromPrivate(demoHpkePrivateKeyset);
    expect(keysetJson(keyset.privateKeyset)).toBe(demoHpkePrivateKeyset.trim());
    expect(keysetJson(keyset.publicKeyset)).toBe(
      keysetJson(demoStudy.export.tink_hpke_public_keyset)
    );
  });
});

describe('parse', () => {
  it('round-trips the demo study through canonicalize', () => {
    const configuration = parseConfiguration(demoStudyBytes);
    expect(validate(configuration)).toEqual([]);
    const again = parseConfiguration(canonicalBytes(configuration));
    expect(canonicalize(again)).toBe(canonicalize(configuration));
  });

  it('round-trips an envelope', () => {
    const configuration = parseConfiguration(demoStudyBytes);
    const payload = canonicalBytes(configuration);
    const pair = signingKeyPairFromPrivate(demoSigningPrivateKey);
    const signature = sign(payload, pair.privatePkcs8Base64);
    const envelope = encodeEnvelope(configuration.signer.key_id, payload, signature);
    const decoded = decodeEnvelope(envelope);
    expect(decoded.signerKeyId).toBe(configuration.signer.key_id);
    expect(decoded.configurationBytes).toEqual(payload);
    expect(verify(decoded.configurationBytes, decoded.signature, pair.publicX509Base64)).toBe(true);
    expect(canonicalize(parseConfiguration(envelope))).toBe(canonicalize(configuration));
  });

  it('refuses bytes that are not a configuration', () => {
    expect(() => parseConfiguration(new TextEncoder().encode('{"a":1}'))).toThrow();
    expect(() => parseConfiguration(new TextEncoder().encode('nope'))).toThrow();
  });
});

describe('generated artefacts', () => {
  it('signs what it generates and verifies against the declared public key', () => {
    const pair = generateSigningKeyPair();
    const keyset = generateHpkeKeyset();
    const configuration = emptyConfiguration();
    configuration.experiment_id = 'demo-study';
    configuration.configuration_id = 'demo-study-v1';
    configuration.title = 'T';
    configuration.researcher = { name: 'R', contact: 'r@example.org' };
    configuration.purpose = 'P';
    configuration.consent = { document_version: 'v1', summary: 'S' };
    configuration.collectors = [{ id: 'app_lifecycle.v1', required: false, config: {} }];
    configuration.signer = { key_id: 'demo-signer', public_key: pair.publicX509Base64 };
    configuration.export = {
      researcher_key_id: 'demo-export',
      tink_hpke_public_keyset: keyset.publicKeyset
    };
    expect(validate(configuration)).toEqual([]);
    const payload = canonicalBytes(configuration);
    const signature = sign(payload, pair.privatePkcs8Base64);
    expect(verify(payload, signature, configuration.signer.public_key)).toBe(true);
    const envelope = encodeEnvelope(configuration.signer.key_id, payload, signature);
    expect(decodeEnvelope(envelope).configurationBytes).toEqual(payload);
    // The two private artefacts carry no trailing newline.
    expect(pair.privatePkcs8Base64.endsWith('\n')).toBe(false);
    expect(keysetJson(keyset.privateKeyset).endsWith('\n')).toBe(false);
  });
});

describe('units and estimate', () => {
  for (const [locale, m] of [['en', en], ['zh-TW', zhTW]] as const) {
    it(`humanises in ${locale}`, () => {
      const u = units(m, locale);
      expect(u.minutes(1440)).toMatch(/1/);
      expect(u.minutes(60)).toBe(`1 ${m.unit.hours}`);
      expect(u.minutes(15)).toBe(`15 ${m.unit.minutes}`);
      expect(u.millis(0)).toBe('0');
      expect(u.millis(10000)).toMatch(/10/);
      expect(u.hertz(10)).toBe(`10 ${m.unit.hertz}`);
      // The float32 that will be written, not the double that was typed.
      expect(u.metres(1234.5678)).toBe(`1234.5677 ${m.unit.metres}`);
      expect(u.metres(0)).toBe(`0.0 ${m.unit.metres}`);
      expect(u.bytes(64 * 1024 * 1024)).toBe('64 MiB');
      expect(u.about('x')).toBe('≈ x');
    });
  }

  it('rates the accelerometer as the expensive one', () => {
    const configuration = emptyConfiguration();
    configuration.collectors = [
      { id: 'accelerometer.v1', required: false, config: { sampling_period_us: 5_000, maximum_report_latency_us: 0 } },
      { id: 'app_lifecycle.v1', required: false, config: {} }
    ];
    const result = estimate(configuration);
    expect(result.eventsPerHour).toBeGreaterThan(700_000);
    expect(result.hoursToQuota).toBeLessThan(2);
    expect(intensityOf(720_000)).toBe(4);
    expect(intensityOf(20)).toBe(1);
  });
});

describe('paths', () => {
  it('routes every issue a document can raise to a named step', () => {
    for (const path of [
      'experiment_id', 'consent.summary', 'collectors.2.config.interval_millis',
      'prompts.0.id', 'storage.maximum_local_bytes', 'upload.endpoint',
      'signer.key_id', 'export.tink_hpke_public_keyset', ''
    ]) {
      expect(['keys', 'study', 'sign', 'files']).toContain(stepForPath(path));
    }
    expect(stepForPath('signer.public_key')).toBe('keys');
    expect(stepForPath('')).toBe('sign');
    expect(stepForPath('collectors.2.config.interval_millis')).toBe('study');
  });
});

describe('issue messages', () => {
  it('covers every code validate can emit, in both catalogues', () => {
    const configuration = emptyConfiguration();
    configuration.collectors = [
      { id: 'network_usage.v1', required: false, config: { transports: [], poll_interval_minutes: 0 } },
      { id: 'location.v1', required: false, config: { interval_millis: 1000, minimum_interval_millis: 3000, maximum_batch_delay_millis: 0, minimum_displacement_meters: -1, priority: 'BALANCED' } }
    ];
    configuration.prompts = [{ id: 'a', delay_minutes: 0, message: '' }, { id: 'a', delay_minutes: 1, message: 'x' }];
    configuration.upload = { endpoint: 'http://x', interval_minutes: 0, allow_metered: false };
    configuration.expires_at = configuration.issued_at;
    for (const m of [en, zhTW]) {
      for (const issue of validate(configuration)) {
        expect(m.issue, issue.code).toHaveProperty(issue.code);
      }
    }
  });
});
