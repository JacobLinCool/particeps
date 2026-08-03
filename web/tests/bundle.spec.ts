/**
 * The reader, against bundles built for the occasion.
 *
 * Every case here starts from a fresh HPKE key pair and a bundle sealed to it, because the one
 * thing that must never be committed to this repository is a key that opens a real export. See
 * `tests/seal.ts` for why the fixture is built rather than shelled out for, and `tests/compat.spec.ts`
 * for the half of this claim that involves the JVM.
 *
 * The negative cases are the point. A researcher meets this step holding three files, and the only
 * useful failure is one that names which of the three is wrong — so each case here changes exactly
 * one thing and asserts the name that comes back.
 */

import { describe, expect, it } from 'vitest';
import { MAXIMUM_BUNDLE_BYTES, openBundle, type BundleFailure } from '../src/lib/adc/bundle';
import { generateHpkeKeyset } from '../src/lib/adc/tink';
import type { StudyConfiguration } from '../src/lib/adc/types';
import { bundleJson, seal } from './seal';

/**
 * A study and the key pair it seals to. Fresh on every call, because the one file that must never
 * exist in this repository is a key that opens a real export.
 */
function pair(overrides: Partial<StudyConfiguration> = {}) {
  const keyset = generateHpkeKeyset();
  const configuration: StudyConfiguration = {
    schema_version: 1,
    experiment_id: 'bundle-harness',
    configuration_id: 'bundle-case-001',
    assigned_participant_id: null,
    issued_at: '2026-01-01T00:00:00Z',
    expires_at: '2035-01-01T00:00:00Z',
    minimum_app_version: 1,
    title: 'Bundle harness 研究',
    researcher: { name: 'Harness', contact: 'harness@example.invalid' },
    purpose: 'Prove the browser opens what a phone wrote.',
    duration_hours: 24,
    consent: { document_version: 'harness-1', summary: 'A fixture. Nothing is collected.' },
    collectors: [{ id: 'app_lifecycle.v1', required: true, config: {} }],
    surveys: [],
    interventions: [],
    storage: { maximum_local_bytes: 16 * 1024 * 1024 },
    signer: { key_id: 'harness-signer', public_key: '' },
    export: { researcher_key_id: 'harness-hpke', tink_hpke_public_keyset: keyset.publicKeyset },
    upload: null,
    ...overrides
  };
  return { keyset, configuration };
}

async function refuses(
  bytes: Uint8Array,
  configuration: StudyConfiguration,
  privateKeyset: Parameters<typeof openBundle>[2],
  failure: BundleFailure
) {
  expect(await openBundle(bytes, configuration, privateKeyset)).toEqual({ ok: false, failure });
}

describe('openBundle', () => {
  it('opens a bundle sealed to the study, byte for byte', async () => {
    const { keyset, configuration } = pair();
    const text = bundleJson(configuration, { events: 3, assignedParticipantId: 'A-017' });
    const result = await openBundle(
      await seal(configuration, text),
      configuration,
      keyset.privateKeyset
    );
    if (!result.ok) throw new Error(`refused: ${result.failure}`);
    expect(result.bundle.text).toBe(text);
    expect(result.bundle.keyId).toBe('harness-hpke');
    expect(result.bundle.bytes).toBe(new TextEncoder().encode(text).length);
    const experiment = result.bundle.document.experiment;
    expect(experiment.events).toHaveLength(3);
    expect(experiment.events[0].fields).toEqual({ x: '0.2', y: '-1.2', z: '9.82' });
    expect(experiment.assigned_participant_id).toBe('A-017');
    expect(experiment.first_sequence_number).toBe(1);
    expect(experiment.last_sequence_number).toBe(3);
  });

  /** The anonymous study writes no such key, and an absent key is not a malformed bundle. */
  it('reads an absent assigned participant as none', async () => {
    const { keyset, configuration } = pair();
    const text = bundleJson(configuration, { events: 0 });
    // The configuration inlined alongside carries its own `assigned_participant_id: null`, so the
    // absence being asserted is the experiment block's, which is where the phone omits the key.
    expect(Object.keys(JSON.parse(text).experiment)).not.toContain('assigned_participant_id');
    const result = await openBundle(
      await seal(configuration, text),
      configuration,
      keyset.privateKeyset
    );
    if (!result.ok) throw new Error(`refused: ${result.failure}`);
    expect(result.bundle.document.experiment.assigned_participant_id).toBeNull();
    expect(result.bundle.document.experiment.events).toEqual([]);
    expect(result.bundle.document.experiment.last_sequence_number).toBe(0);
  });

  /**
   * The Kotlin side reads the body in 64 KiB chunks and calls `doFinal` once, so a bundle spanning
   * several of those windows still carries exactly one tag. A reader that expected a frame per chunk
   * would open the first window of this and nothing else.
   */
  it('opens a body several read windows wide with one tag', async () => {
    const { keyset, configuration } = pair();
    const text = bundleJson(configuration, { events: 1_000 });
    expect(text.length).toBeGreaterThan(4 * 65_536);
    const bytes = await seal(configuration, text);
    const result = await openBundle(bytes, configuration, keyset.privateKeyset);
    if (!result.ok) throw new Error(`refused: ${result.failure}`);
    expect(result.bundle.text).toBe(text);
    // Body minus plaintext is one 16-byte tag, whatever the size.
    const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const bodyAt = 26 + header.getUint16(8) + header.getInt32(10);
    expect(bytes.length - bodyAt - result.bundle.bytes).toBe(16);
  });

  it('carries the whole-study count beside the window this file holds', async () => {
    const { keyset, configuration } = pair();
    // A scheduled upload sends a slice: sequences 501–503 out of 900 the device has recorded.
    const text = bundleJson(configuration, { events: 3, firstSequenceNumber: 501, lifetime: 900 });
    const result = await openBundle(
      await seal(configuration, text),
      configuration,
      keyset.privateKeyset
    );
    if (!result.ok) throw new Error(`refused: ${result.failure}`);
    expect(result.bundle.document.experiment.first_sequence_number).toBe(501);
    expect(result.bundle.document.experiment.last_sequence_number).toBe(503);
    expect(result.bundle.document.experiment.next_sequence_number).toBe(901);
  });

  describe('refuses, by name', () => {
    it('a file that is not a bundle', async () => {
      const { keyset, configuration } = pair();
      await refuses(
        new TextEncoder().encode('{"not":"a bundle"}'),
        configuration,
        keyset.privateKeyset,
        'not_a_bundle'
      );
    });

    it('a header with nothing after it', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }));
      await refuses(bytes.slice(0, 20), configuration, keyset.privateKeyset, 'not_a_bundle');
      await refuses(bytes.slice(0, 40), configuration, keyset.privateKeyset, 'not_a_bundle');
    });

    /** A length that lies is refused before it is used to slice anything. */
    it('a key id length outside the writer’s bounds', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }));
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      view.setUint16(8, 2);
      await refuses(bytes, configuration, keyset.privateKeyset, 'not_a_bundle');
      view.setUint16(8, 65);
      await refuses(bytes, configuration, keyset.privateKeyset, 'not_a_bundle');
    });

    it('a wrapped key length read as a negative int32', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }));
      new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).setUint32(10, 0xffff_ffff);
      await refuses(bytes, configuration, keyset.privateKeyset, 'not_a_bundle');
    });

    it('a file larger than this tab opens', async () => {
      const { keyset, configuration } = pair();
      // Only the length is inspected before the ceiling, so a sparse array proves the ordering
      // without allocating a quarter of a gigabyte of real ciphertext.
      const huge = { length: MAXIMUM_BUNDLE_BYTES + 1 } as unknown as Uint8Array;
      await refuses(huge, configuration, keyset.privateKeyset, 'too_large');
    });

    /** The commonest mistake of the three, and the CLI names it before touching any crypto too. */
    it('a bundle from another study', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }), {
        keyId: 'other-hpke'
      });
      await refuses(bytes, configuration, keyset.privateKeyset, 'wrong_study');
    });

    it('last month’s private key', async () => {
      const { configuration } = pair();
      const stale = generateHpkeKeyset();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }));
      await refuses(bytes, configuration, stale.privateKeyset, 'wrong_key');
    });

    /**
     * The same key id in front of a different scalar. The prefix check passes and the derived
     * public key does not match the study's, which is the check that catches a keyset somebody
     * renumbered by hand.
     */
    it('a private key whose id matches but whose scalar does not', async () => {
      const { keyset, configuration } = pair();
      const impostor = generateHpkeKeyset();
      impostor.privateKeyset.primaryKeyId = keyset.publicKeyset.primaryKeyId;
      impostor.privateKeyset.key[0].keyId = keyset.publicKeyset.primaryKeyId;
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }));
      await refuses(bytes, configuration, impostor.privateKeyset, 'wrong_key');
    });

    it('a keyset naming another suite', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }));
      const broken = structuredClone(keyset.privateKeyset);
      broken.key[0].keyData.typeUrl = 'type.googleapis.com/google.crypto.tink.EciesPrivateKey';
      await refuses(bytes, configuration, broken, 'wrong_key');
    });

    /**
     * A personalised study issues one configuration per participant, so this is the shape of
     * holding the wrong one. The context is the wrap's `info` as well as the body's AAD, and the
     * wrap opens first — which is why this is `unwrap_failed` rather than a body tag failure.
     */
    it('another participant’s configuration', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 1 }));
      const other = { ...configuration, configuration_id: 'bundle-case-002' };
      await refuses(bytes, other, keyset.privateKeyset, 'unwrap_failed');
    });

    it('a bundle altered after the phone wrote it', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, bundleJson(configuration, { events: 3 }));
      bytes[bytes.length - 1] ^= 1;
      await refuses(bytes, configuration, keyset.privateKeyset, 'tag_failed');
    });

    it('a plaintext that is not this format', async () => {
      const { keyset, configuration } = pair();
      const bytes = await seal(configuration, JSON.stringify({ format: 'something-else' }));
      await refuses(bytes, configuration, keyset.privateKeyset, 'unreadable');
    });

    /** The summary maps over `events`; a document that cannot be mapped over is refused whole. */
    it('a document whose events are not events', async () => {
      const { keyset, configuration } = pair();
      const document = JSON.parse(bundleJson(configuration, { events: 1 }));
      document.experiment.events[0].fields.x = 0.2;
      await refuses(
        await seal(configuration, JSON.stringify(document)),
        configuration,
        keyset.privateKeyset,
        'unreadable'
      );
    });
  });

  /** Nothing renders before the tag verifies, so nothing partial may come back either. */
  it('returns no document on any failure', async () => {
    const { keyset, configuration } = pair();
    const bytes = await seal(configuration, bundleJson(configuration, { events: 2 }));
    bytes[bytes.length - 2] ^= 0x40;
    const result = await openBundle(bytes, configuration, keyset.privateKeyset);
    expect(result.ok).toBe(false);
    expect(result).not.toHaveProperty('bundle');
  });
});
