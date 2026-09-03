import { describe, expect, it } from 'vitest';
import { flushSync } from 'svelte';
import { createDraft } from '../src/routes/researcher/draft.svelte';
import { canonicalizeConfiguration } from '$lib/particeps/canonical';
import { decodeEnvelope } from '../src/routes/researcher/parse';
import { verify } from '$lib/particeps/crypto';

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
      'accelerometer.v1', 'app_lifecycle.v1', 'keyboard_touch.v1', 'location.v1'
    ]);
    expect(draft.collectorPath('location.v1')).toBe('collectors.3');
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
    // The envelope names the signer the document names, and the document derived that name from
    // the key it was signed with. Nothing typed it.
    expect(decoded.signerKeyId).toBe(draft.signerKeyId);
    expect(decoded.signerKeyId).toMatch(/^signer-[0-9a-z]{13}$/);
    expect(verify(decoded.configurationBytes, decoded.signature, draft.configuration.signer.public_key)).toBe(true);
    expect(new TextDecoder().decode(decoded.configurationBytes)).toBe(draft.canonical);
    expect(draft.canonical).toBe(canonicalizeConfiguration(draft.document));

    draft.configuration.title = 'T2';
    flushSync();
    expect(draft.stale).toBe(true);
    expect(draft.envelope).toBeNull();
    expect(draft.signature).toBeNull();
    expect(draft.stateOf('sign')).toBe('partial');
    expect(draft.artifactCount).toBe(2);
  });

  it('does not require blinding confirmation for static continuous collection', () => {
    const draft = ready();
    expect(draft.addCollectorProfile('location.v1')).toBe('profile-2');
    flushSync();
    expect(draft.issues).toEqual([]);
    expect(draft.requiresBlindingConfirmation).toBe(false);
    expect(draft.sign()).toBe('signed');
  });

  it('keeps the generated continuous binding live when a collector becomes required', () => {
    const draft = ready();
    const binding = draft.configuration.automations.find((automation) =>
      automation.type === 'resource_binding' && automation.resource.id === 'location.v1'
    );
    if (!binding || binding.type !== 'resource_binding') throw new Error('missing location binding');

    expect(binding.default_profile_id).toBeNull();
    draft.setCollectorRequired('location.v1', true);
    flushSync();
    expect(draft.collector('location.v1')?.required).toBe(true);
    expect(binding.default_profile_id).toBe('continuous');
    expect(draft.issues).toEqual([]);
    expect(draft.requiresBlindingConfirmation).toBe(false);

    draft.setCollectorRequired('location.v1', false);
    flushSync();
    expect(binding.default_profile_id).toBeNull();
    expect(draft.issues).toEqual([]);
  });

  it('does not rewrite a researcher-authored binding when its collector becomes required', () => {
    const draft = ready();
    const binding = draft.configuration.automations.find((automation) =>
      automation.type === 'resource_binding' && automation.resource.id === 'location.v1'
    );
    if (!binding || binding.type !== 'resource_binding') throw new Error('missing location binding');
    binding.cases = [{
      condition: { type: 'elapsed_at_least', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' },
      profile_id: 'continuous'
    }];

    draft.setCollectorRequired('location.v1', true);
    flushSync();
    expect(binding.default_profile_id).toBeNull();
    expect(draft.issues.map((issue) => issue.code)).toContain('trigger_source_liveness');
  });

  it('requires blinding confirmation for a single-profile conditional profile/null resource', () => {
    const draft = ready();
    const binding = draft.configuration.automations.find((automation) =>
      automation.type === 'resource_binding' && automation.resource.id === 'location.v1'
    );
    if (!binding || binding.type !== 'resource_binding') throw new Error('missing location binding');
    binding.cases = [{
      condition: { type: 'elapsed_at_least', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' },
      profile_id: 'continuous'
    }];
    binding.default_profile_id = null;
    flushSync();
    expect(draft.issues).toEqual([]);
    expect(draft.requiresBlindingConfirmation).toBe(true);
    expect(draft.sign()).toBe('failed');
    expect(draft.envelope).toBeNull();
    draft.confirmBlinding(true);
    expect(draft.sign()).toBe('signed');
  });

  it('requires blinding confirmation for occurrence actions and a constant actuator profile', () => {
    const occurrence = ready();
    occurrence.addIntervention({
      id: 'check-in', required: false,
      action: { type: 'notification', notification_title: 'Check in', notification_message: 'How is it going?' }
    });
    occurrence.configuration.automations.push({
      type: 'occurrence', id: 'check-in-once',
      trigger: { type: 'schedule', schedule: { type: 'one_time', offset_minutes: 1, clock: 'ACTIVE_RUNNING_TIME' } },
      guard: null, intervention_id: 'check-in', availability_seconds: 60,
      cooldown: null, maximum_activations: 1
    });
    occurrence.configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
    flushSync();
    expect(occurrence.issues).toEqual([]);
    expect(occurrence.requiresBlindingConfirmation).toBe(true);

    const actuator = ready();
    actuator.configuration.traffic_shaping = {
      target_packages: ['com.example.target'],
      profiles: [{ id: 'constant-cap', uplink_kbps: 256, downlink_kbps: 1024 }]
    };
    actuator.configuration.automations.push({
      type: 'resource_binding', id: 'bind-traffic-shaping',
      resource: { kind: 'actuator', id: 'traffic-shaping.v1' },
      cases: [{ condition: { type: 'study_session_active' }, profile_id: 'constant-cap' }],
      default_profile_id: 'constant-cap'
    });
    actuator.configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
    flushSync();
    expect(actuator.issues).toEqual([]);
    expect(actuator.requiresBlindingConfirmation).toBe(true);
  });

  /**
   * Not `blocked`. The keys exist from the second second now, so a red dot on arrival would be the
   * rail greeting a reader who has done nothing wrong — the exact failure the `pristine` guard
   * exists to prevent. Held-and-unkept is progress, and it is non-blocking: before a signature
   * exists an unsaved key costs one regenerate.
   */
  it('holds keys as partial until both private files are written down', () => {
    const draft = ready();
    expect(draft.stateOf('keys')).toBe('partial');
    expect(draft.issuesByStep.keys).toEqual([]);
    draft.markKept('signing-private');
    flushSync();
    expect(draft.stateOf('keys')).toBe('partial');
    draft.markKept('hpke-private');
    flushSync();
    expect(draft.stateOf('keys')).toBe('complete');
  });

  /**
   * Both key names are properties of key material, so importing a private half reproduces its name
   * exactly — which is what makes a second configuration under the same signer automatic.
   */
  it('names both keys from the key material, and never from the editable object', () => {
    const draft = ready();
    expect(draft.signerKeyId).toMatch(/^signer-[0-9a-z]{13}$/);
    expect(draft.exportKeyId).toMatch(/^export-[0-9a-z]{13}$/);
    expect(draft.document.signer.key_id).toBe(draft.signerKeyId);
    expect(draft.document.export.researcher_key_id).toBe(draft.exportKeyId);
    expect(draft.configuration.signer.key_id).toBe('');
    expect(draft.configuration.export.researcher_key_id).toBe('');

    const signerName = draft.signerKeyId;
    const privateHalf = draft.signing.kind === 'held' ? draft.signing.material.privateKey : '';
    draft.generateSigning();
    flushSync();
    expect(draft.signerKeyId).not.toBe(signerName);
    draft.importSigning(privateHalf);
    flushSync();
    expect(draft.signerKeyId).toBe(signerName);
  });

  /** The escape hatch, for a key that already carries a name from `sign --key-id`. */
  it('takes an override for either key name, and derives again when it is emptied', () => {
    const draft = ready();
    const derived = draft.signerKeyId;
    draft.pinSignerKeyId('lab-signer-2026');
    draft.pinExportKeyId('lab-export-2026');
    flushSync();
    expect(draft.document.signer.key_id).toBe('lab-signer-2026');
    expect(draft.document.export.researcher_key_id).toBe('lab-export-2026');
    // No latching on `sign()`: a key ID follows the key and has nothing to drift from.
    expect(draft.sign()).toBe('signed');
    flushSync();
    expect(draft.signerKeyIdPin).toBe('lab-signer-2026');
    draft.pinSignerKeyId('');
    flushSync();
    expect(draft.signerKeyId).toBe(derived);
  });

  /**
   * A file this page produced adopts nothing — its name is already the name this derivation gives
   * it — so the pin stays empty and the invariant `key_id = H(public_key)` keeps holding. A file
   * from the CLI keeps its hand-written name, which is the continuity case.
   */
  it('adopts a loaded key name only when the file could not have been named here', () => {
    const mine = ready();
    mine.sign();
    const envelope = mine.envelope!;

    const reader = createDraft();
    reader.generateSigning();
    reader.generateHpke();
    reader.load(envelope);
    flushSync();
    expect(reader.signerKeyIdPin).toBe('');
    expect(reader.exportKeyIdPin).toBe('');
    // The loaded document carries the reader's own key, and is named after it.
    expect(reader.document.signer.key_id).toBe(reader.signerKeyId);
    expect(reader.signerKeyId).not.toBe(mine.signerKeyId);

    const cli = ready();
    cli.pinSignerKeyId('lab-signer-2026');
    cli.pinExportKeyId('lab-export-2026');
    flushSync();
    cli.sign();
    const inherited = createDraft();
    inherited.load(cli.envelope!);
    flushSync();
    expect(inherited.signerKeyIdPin).toBe('lab-signer-2026');
    expect(inherited.exportKeyIdPin).toBe('lab-export-2026');
  });

  /**
   * Generating on arrival must not arm the unload guard against somebody who opened the page and
   * closed it again: two keys nobody has committed to anything cost one regenerate, and a browser
   * prompt over that teaches a reader to dismiss browser prompts.
   */
  it('puts keys at risk only once there is work to lose', () => {
    const draft = createDraft();
    draft.ensureKeys();
    flushSync();
    expect(draft.signing.kind).toBe('held');
    expect(draft.hpke.kind).toBe('held');
    expect(draft.keysAtRisk).toBe(false);

    draft.configuration.title = 'Sleep and screen time';
    flushSync();
    expect(draft.keysAtRisk).toBe(true);
  });

  /** Idempotent: it never destroys a key that is already held, including one just imported. */
  it('makes only the keys that do not exist yet', () => {
    const draft = createDraft();
    draft.ensureKeys();
    flushSync();
    const signer = draft.signerKeyId;
    const exported = draft.exportKeyId;
    draft.ensureKeys();
    flushSync();
    expect(draft.signerKeyId).toBe(signer);
    expect(draft.exportKeyId).toBe(exported);
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
    expect(draft.stateOf('keys')).toBe('partial');

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
