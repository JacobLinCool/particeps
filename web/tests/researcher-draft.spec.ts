import { describe, expect, it } from 'vitest';
import { flushSync } from 'svelte';
import { createDraft } from '../src/routes/researcher/draft.svelte';
import { canonicalize } from '$lib/adc/canonical';
import { decodeEnvelope } from '../src/routes/researcher/parse';
import { verify } from '$lib/adc/crypto';

function ready() {
  const draft = createDraft();
  draft.generateSigning();
  draft.generateHpke();
  const c = draft.configuration;
  // No identifier is typed anywhere: the title names the study and the bytes name the file.
  c.title = 'Sleep and screen time';
  c.researcher.name = 'R';
  c.researcher.contact = 'r@example.org';
  c.purpose = 'P';
  c.consent.document_version = 'v1';
  c.consent.summary = 'S';
  c.signer.key_id = 'demo-signer';
  c.export.researcher_key_id = 'demo-export';
  draft.enableCollector('location.v1');
  draft.enableCollector('app_lifecycle.v1');
  return draft;
}

describe('draft', () => {
  it('keeps collectors in the codec order however they are switched on', () => {
    const draft = ready();
    draft.enableCollector('keyboard_touch.v1');
    draft.enableCollector('accelerometer.v1');
    expect(draft.configuration.collectors.map((c) => c.id)).toEqual([
      'app_lifecycle.v1', 'accelerometer.v1', 'location.v1', 'keyboard_touch.v1'
    ]);
    expect(draft.collectorPath('location.v1')).toBe('collectors.2');
  });

  it('names the study from the title and the file from its own bytes', () => {
    const draft = ready();
    expect(draft.experimentId).toBe('sleep-and-screen-time');
    expect(draft.configurationId.startsWith('sleep-and-screen-time-')).toBe(true);
    expect(draft.document.experiment_id).toBe(draft.experimentId);
    expect(draft.document.configuration_id).toBe(draft.configurationId);
    // The editable object is never named. Nothing reads these two off it.
    expect(draft.configuration.experiment_id).toBe('');
    expect(draft.configuration.configuration_id).toBe('');

    const before = draft.configurationId;
    draft.configuration.purpose = 'A different purpose entirely';
    flushSync();
    expect(draft.configurationId).not.toBe(before);
  });

  it('holds the study name once it is in a file, and takes an override at any time', () => {
    const draft = ready();
    expect(draft.sign()).toBe('signed');
    flushSync();
    const latched = draft.experimentId;
    draft.configuration.title = '睡眠與螢幕使用時間';
    flushSync();
    // A second-language arm: same experiment, new title, new configuration.
    expect(draft.experimentId).toBe(latched);
    expect(draft.configurationId.startsWith(`${latched}-`)).toBe(true);

    draft.pinExperimentId('pilot-2026');
    flushSync();
    expect(draft.experimentId).toBe('pilot-2026');
    draft.pinExperimentId('');
    flushSync();
    // Emptied, it derives again — from the title that is there now, which is Chinese and yields
    // no ASCII stem at all.
    expect(draft.experimentId).toMatch(/^study-[0-9a-z]{6}$/);
  });

  it('signs, verifies, and retires the envelope on any edit', () => {
    const draft = ready();
    expect(draft.issues).toEqual([]);
    expect(draft.sign()).toBe('signed');
    const envelope = draft.envelope!;
    expect(envelope).not.toBeNull();
    const decoded = decodeEnvelope(envelope);
    expect(decoded.signerKeyId).toBe('demo-signer');
    expect(verify(decoded.configurationBytes, decoded.signature, draft.configuration.signer.public_key)).toBe(true);
    expect(new TextDecoder().decode(decoded.configurationBytes)).toBe(draft.canonical);
    expect(draft.canonical).toBe(canonicalize(draft.document));

    draft.configuration.title = 'T2';
    flushSync();
    expect(draft.stale).toBe(true);
    expect(draft.envelope).toBeNull();
    expect(draft.signature).toBeNull();
    expect(draft.stateOf('sign')).toBe('partial');
    expect(draft.artifactCount).toBe(2);
  });

  it('holds keys as blocked until both private files are written down', () => {
    const draft = ready();
    expect(draft.stateOf('keys')).toBe('blocked');
    expect(draft.issuesByStep.keys).toEqual([]);
    draft.markKept('signing-private');
    flushSync();
    expect(draft.stateOf('keys')).toBe('blocked');
    draft.markKept('hpke-private');
    flushSync();
    expect(draft.stateOf('keys')).toBe('complete');
  });

  /**
   * Clicking a download anchor starts a save the reader can still dismiss, and no browser reports
   * that it was dismissed. So a click is not a claim the key is anywhere but this tab, and the two
   * artefacts that can be produced again say so the moment they are asked for.
   */
  it('does not treat a started download of a key as a saved key', () => {
    const draft = ready();
    draft.markSent('signing-private');
    draft.markSent('hpke-private');
    flushSync();
    expect(draft.sent['signing-private']).toBe(true);
    expect(draft.saved['signing-private']).toBe(false);
    expect(draft.keysAtRisk).toBe(true);
    expect(draft.stateOf('keys')).toBe('blocked');

    draft.markKept('signing-private');
    draft.markKept('hpke-private');
    flushSync();
    expect(draft.keysAtRisk).toBe(false);
    expect(draft.stateOf('keys')).toBe('complete');
  });

  it('asks nothing of the two artefacts a signature can produce again', () => {
    const draft = ready();
    draft.sign();
    flushSync();
    draft.markSent('canonical');
    flushSync();
    expect(draft.saved.canonical).toBe(true);
  });

  it('starts empty rather than red', () => {
    const draft = createDraft();
    expect(draft.stateOf('keys')).toBe('empty');
    expect(draft.stateOf('study')).toBe('empty');
    expect(draft.stateOf('sign')).toBe('empty');
    expect(draft.stateOf('files')).toBe('empty');
    expect(draft.issues.length).toBeGreaterThan(0);
  });

  it('shows field issues only after a blur or a sign attempt', () => {
    const draft = createDraft();
    expect(draft.visibleIssues('title')).toEqual([]);
    draft.touch('title');
    flushSync();
    expect(draft.visibleIssues('title')).toHaveLength(1);
    expect(draft.visibleIssues('purpose')).toEqual([]);
    draft.sign();
    flushSync();
    expect(draft.visibleIssues('purpose')).toHaveLength(1);
  });

  it('refuses to produce anything when the declared public key is not the held one', () => {
    const draft = ready();
    draft.configuration.signer.public_key =
      'MCowBQYDK2VwAyEA' + 'A'.repeat(43) + '=';
    flushSync();
    expect(draft.sign()).toBe('mismatch');
    expect(draft.envelope).toBeNull();
  });

  it('loads a document and keeps the keys that are held here', () => {
    const draft = ready();
    const before = draft.configuration.signer.public_key;
    draft.sign();
    const envelope = draft.envelope!;
    const other = createDraft();
    other.generateSigning();
    other.generateHpke();
    const mine = other.configuration.signer.public_key;
    other.load(envelope);
    flushSync();
    // The file's own name, inherited — which is what makes the second-language arm automatic.
    expect(other.experimentId).toBe(draft.experimentId);
    other.configuration.title = 'A second-language arm of the same study';
    flushSync();
    expect(other.experimentId).toBe(draft.experimentId);
    expect(other.configuration.signer.public_key).toBe(mine);
    expect(other.configuration.signer.public_key).not.toBe(before);
    expect(other.envelope).toBeNull();
  });
});
