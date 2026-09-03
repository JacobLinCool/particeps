import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  bundleContext,
  isRuntimeComponentKind,
  openBundle,
  verifySourceObservationEventOrder
} from '../src/lib/particeps/bundle';
import { canonicalize } from '../src/lib/particeps/canonical';
import { generateHpkeKeyPair } from '../src/lib/particeps/crypto';
import type { StudyConfiguration } from '../src/lib/particeps/types';
import { parseConfiguration } from '../src/routes/researcher/parse';

type BundleVector = {
  container_hex: string;
  document_jcs_utf8_hex: string;
  researcher_private_key_base64url: string;
};

const corpus = JSON.parse(
  readFileSync(new URL('../../protocol/v1/conformance-vectors.json', import.meta.url), 'utf8')
) as {
  hostile: Array<{ entrypoint: string; id: string; input_hex: string }>;
  valid: {
    bundle: BundleVector;
    signed_configuration: { canonical_jcs_utf8_hex: string };
  };
};
const bytes = (value: string) => Uint8Array.from(Buffer.from(value, 'hex'));
const configuration = parseConfiguration(
  bytes(corpus.valid.signed_configuration.canonical_jcs_utf8_hex)
);
const bundle = corpus.valid.bundle;
const container = bytes(bundle.container_hex);

describe('PTCEXP01 Protocol v1 EngineCommit reader', () => {
  it('keeps random selection as an engine input rather than a component kind', () => {
    expect(isRuntimeComponentKind('TIMER')).toBe(true);
    expect(isRuntimeComponentKind('AUTOMATION_CHECKPOINT')).toBe(true);
    expect(isRuntimeComponentKind('RANDOM_SELECTION')).toBe(false);
  });

  it('allows only the exact pending-barrier manifest/event rotation', () => {
    const observation = (
      eventSequence: string,
      admissionKind: 'NORMAL' | 'BARRIER_FLUSH' = 'NORMAL'
    ) => ({
      admission_kind: admissionKind,
      event_count: 1,
      first_event_sequence: eventSequence,
      last_event_sequence: eventSequence
    });

    expect(verifySourceObservationEventOrder(
      [observation('5'), observation('6')],
      null
    )).toBe(true);
    expect(verifySourceObservationEventOrder(
      [observation('6'), observation('5', 'BARRIER_FLUSH')],
      'f'.repeat(64)
    )).toBe(true);
    expect(verifySourceObservationEventOrder(
      [observation('7'), observation('6'), observation('5', 'BARRIER_FLUSH')],
      'f'.repeat(64)
    )).toBe(false);
  });

  it('opens the authenticated commit range and preserves the current event envelope', async () => {
    const result = await openBundle(
      container,
      configuration,
      bundle.researcher_private_key_base64url
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.bundle.document.experiment).toMatchObject({
      commit_count: '3',
      event_count: '5',
      first_commit_sequence: '1',
      last_commit_sequence: '3',
      state: 'RUNNING'
    });
    expect(result.bundle.document.experiment.commits[2].events[0]).toMatchObject({
      condition_epoch_id: '00000000-0000-4000-8000-000000000023',
      event_type: 'BATTERY_STATE',
      schema_version: 1,
      source_id: 'battery_state.v1'
    });
    expect(
      result.bundle.document.experiment.commits[2].events[0].observed_time
        .elapsed_realtime_nanos
    ).toBe('2000');
    expect(canonicalize(JSON.parse(result.bundle.text))).toBe(result.bundle.text);
  });

  it('rejects every authenticated commit, observation, registry, and epoch hostile', async () => {
    const relevant = corpus.hostile.filter((vector) =>
      vector.entrypoint === 'bundle' && /(?:commit|checkpoint|event|observation|ordinal|epoch|registry|flat)/
        .test(vector.id)
    );
    expect(relevant.length).toBeGreaterThan(10);
    for (const vector of relevant) {
      await expect(openBundle(
        bytes(vector.input_hex),
        configuration,
        bundle.researcher_private_key_base64url
      ), vector.id).resolves.toEqual({ ok: false, failure: 'unreadable' });
    }
  });

  it('uses one JCS context for HPKE info and content AAD', () => {
    expect(new TextDecoder().decode(bundleContext(
      '00112233-4455-4677-8899-aabbccddeeff',
      '00'.repeat(32),
      'protocol-export'
    ))).toBe(
      '{"bundle_format":"particeps-research-bundle-v1","bundle_id":' +
      '"00112233-4455-4677-8899-aabbccddeeff","configuration_sha256":"' +
      '00'.repeat(32) +
      '","researcher_key_id":"protocol-export"}'
    );
  });

  it('distinguishes wrong configuration, wrong key, HPKE corruption, and body corruption', async () => {
    const wrongConfiguration = {
      ...configuration,
      configuration_id: 'other-config'
    } satisfies StudyConfiguration;
    expect(await openBundle(
      container,
      wrongConfiguration,
      bundle.researcher_private_key_base64url
    )).toEqual({ ok: false, failure: 'wrong_study' });
    expect(await openBundle(
      container,
      configuration,
      generateHpkeKeyPair().privateKey
    )).toEqual({ ok: false, failure: 'wrong_key' });

    const wrapped = container.slice();
    const wrappedAt = 70 + new DataView(container.buffer).getUint16(56);
    wrapped[wrappedAt + 40] ^= 1;
    expect(await openBundle(
      wrapped,
      configuration,
      bundle.researcher_private_key_base64url
    )).toEqual({ ok: false, failure: 'unwrap_failed' });

    const body = container.slice();
    body[body.length - 1] ^= 1;
    expect(await openBundle(
      body,
      configuration,
      bundle.researcher_private_key_base64url
    )).toEqual({ ok: false, failure: 'tag_failed' });
  });

  it('rejects truncated and retired container identities before decryption', async () => {
    const truncated = new Uint8Array(64);
    truncated.set(new TextEncoder().encode('PTCEXP01'));
    expect(await openBundle(
      truncated,
      configuration,
      bundle.researcher_private_key_base64url
    )).toEqual({ ok: false, failure: 'not_a_bundle' });

    const retired = container.slice();
    // Deliberate hostile fixture for the retired product identity.
    retired.set(new TextEncoder().encode('ADCEXP01'));
    expect(await openBundle(
      retired,
      configuration,
      bundle.researcher_private_key_base64url
    )).toEqual({ ok: false, failure: 'not_a_bundle' });
  });
});
