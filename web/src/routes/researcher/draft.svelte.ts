/**
 * Everything the researcher is holding, and the one rule that keeps it honest.
 *
 * Nothing here is persisted. A plaintext Ed25519 key in `localStorage` is a worse outcome than a
 * lost tab, and half a draft restored without the key that signs it is a trap rather than a
 * convenience — so the tab is the storage, and the page says so before anyone gets far enough to
 * be hurt by it.
 *
 * Staleness is the rule: `canonicalize(configuration)` is compared against the string that was
 * signed, and any difference retires the signature and the envelope. Comparing the canonical
 * *string* rather than diffing fields is not laziness — those bytes are the only thing that
 * decides whether the signature is still over the right document.
 *
 * Four fields are held apart from the rest. `configuration` is the editable object, and nothing in
 * it names it or names its keys: `experiment_id`, `configuration_id`, `signer.key_id` and
 * `export.researcher_key_id` are derived here (`lib/particeps/ids.ts`) and written into `document`, which
 * is what gets validated, canonicalised, signed, and downloaded. Nobody types an identifier, and
 * the property the old section note asked a researcher to maintain by hand — change anything,
 * change the configuration ID — is now true by construction.
 *
 * The two key names derive from the *document's* public halves rather than from the held
 * `KeyState`, which makes the invariant recomputable by anyone holding only the file: `key_id` is
 * the name of `public_key`, in every document this page emits. It also keeps a loaded configuration
 * whose private key is not held correctly named, which the old code did not — it replaced a loaded
 * file's `signer.public_key` with the held key and left the file's `key_id` naming a signer whose
 * public half had just been swapped out.
 */

import type { ResearchBundle } from '$lib/particeps/bundle';
import {
  canonicalConfigurationBytes,
  canonicalizeConfiguration
} from '$lib/particeps/canonical';
import {
  fingerprint as fingerprintOf,
  generateHpkeKeyPair,
  generateSigningKeyPair,
  sign as signBytes,
  verify,
  type HpkeKeyPair,
  type SigningKeyPair
} from '$lib/particeps/crypto';
import { encodeEnvelope } from '$lib/particeps/envelope';
import {
  deriveConfigurationId,
  deriveExperimentId,
  deriveExportKeyId,
  deriveSignerKeyId
} from '$lib/particeps/ids';
import { requiresBlindingConfirmation as configurationRequiresBlindingConfirmation } from '$lib/particeps/researcher-blinding';
import {
  continuousBinding,
  continuousBindingProfile,
  defaultCollector,
  emptyConfiguration,
  validate,
  type Issue
} from '$lib/particeps/schema';
import {
  COLLECTOR_ORDER,
  ID_PATTERN,
  type CollectorConfig,
  type CollectorId,
  type InterventionConfig,
  type SurveyDefinition,
  type StudyConfiguration
} from '$lib/particeps/types';
import type { StepState } from '$lib/ui/types';
import { SvelteSet } from 'svelte/reactivity';
import type { ArtifactId } from './artifacts';
import { estimate } from './estimate';
import { hpkeKeyPairFromPrivate, signingKeyPairFromPrivate } from './keys';
import { parseConfiguration } from './parse';
import { stepForPath, type StepId } from './steps';

export type KeyState<T> = { kind: 'empty' } | { kind: 'held'; material: T };

/** What a sign attempt did. `mismatch` is the one failure that must interrupt. */
export type SignOutcome = 'signed' | 'mismatch' | 'failed';

export { COLLECTOR_ORDER };

const NO_ARTIFACTS: Record<ArtifactId, boolean> = {
  'signing-private': false,
  'hpke-private': false,
  canonical: false,
  partcfg: false
};

/**
 * Whether the researcher has written anything into the study at all. Only the fields they type
 * count: the instants, the duration, and the quota carry defaults, and a step that is still all
 * defaults draws as empty rather than as a wall of red. Painting an unreached form in `--danger`
 * teaches a reader to skip `--danger`, which is the one colour that has to keep working.
 */
function studyStarted(configuration: StudyConfiguration): boolean {
  return (
    configuration.title !== '' ||
    configuration.purpose !== '' ||
    configuration.researcher.name !== '' ||
    configuration.researcher.contact !== '' ||
    configuration.consent.document_version !== '' ||
    configuration.consent.summary !== '' ||
    configuration.collectors.length > 0 ||
    configuration.surveys.length > 0 ||
    configuration.interventions.length > 0 ||
    configuration.automations.length > 0 ||
    Object.keys(configuration.traffic_shaping).length > 0 ||
    configuration.assigned_participant_id !== null ||
    configuration.upload !== null
  );
}

export function createDraft() {
  let configuration = $state<StudyConfiguration>(emptyConfiguration());
  let signing = $state<KeyState<SigningKeyPair>>({ kind: 'empty' });
  let hpke = $state<KeyState<HpkeKeyPair>>({ kind: 'empty' });

  let signature = $state.raw<Uint8Array | null>(null);
  let envelope = $state.raw<Uint8Array | null>(null);
  let signedCanonical = $state.raw<string | null>(null);

  /**
   * A returned export, decrypted. It lives here rather than in the step that opened it for two
   * reasons, and only one of them is the rail: `stateOf` is the rail's only source, so a bundle
   * held in a component draws a permanently empty dot. The other is `reset()`. `Start over` says it
   * discards everything in this tab, and a participant's data still on screen after it would make
   * that sentence false — which on this page is the one sentence that cannot be false.
   *
   * `$state.raw` because nothing mutates it. A bundle is replaced whole or dropped whole, and a
   * deep proxy over 900 events would be paid for on every read.
   */
  let bundle = $state.raw<ResearchBundle | null>(null);

  let attempted = $state(false);
  let blindingConfirmed = $state(false);
  /**
   * Two states, because a browser cannot tell you a file reached the disk. Clicking a download
   * anchor starts a save the reader can still cancel, and a save sheet dismissed leaves nothing
   * behind — so a click sets `sent`, and only the researcher can set `kept`. For the two derivable
   * artefacts that distinction does not matter and `sent` is promoted straight through; for the two
   * private keys it is the difference between a green tick and a key that no longer exists.
   */
  let sent = $state<Record<ArtifactId, boolean>>({ ...NO_ARTIFACTS });
  let kept = $state<Record<ArtifactId, boolean>>({ ...NO_ARTIFACTS });
  const touched = new SvelteSet<string>();

  /**
   * `''` means "derive from the title". Anything else is what the study is called, verbatim.
   *
   * Two forces pull against each other: the id has to come from nothing but the title, and it must
   * not move when the title changes for a second-language arm — same experiment, different title,
   * different configuration. So it derives until it is real and then latches: `sign()` pins it,
   * because that is the moment it enters a file somebody else will hold, and `load()` adopts the
   * one it read. Opening the English `.partcfg`, retyping the prose in Chinese, and re-signing
   * therefore inherits the experiment and regenerates the configuration, with nothing to remember.
   */
  let experimentIdPin = $state('');

  /**
   * The same escape hatch for the two key names, and for the same reason: a key that already
   * carries a name from `sign --key-id lab-signer-2026` must not be silently renamed by this page.
   * `docs/data-dictionary.md` makes the signer block the way provenance travels into the dataset,
   * and two names for one key across two arms of one study is the failure derivation exists to
   * prevent. `hpke-keygen` takes no `--key-id` at all, so a CLI-era export name is always
   * hand-written and always at risk of the same thing.
   *
   * Unlike the experiment, neither latches on `sign()`. An experiment ID follows the title and has
   * to stop when the title moves for a second-language arm; a key ID follows the key and has
   * nothing to drift from.
   */
  let signerKeyIdPin = $state('');
  let exportKeyIdPin = $state('');

  const experimentId = $derived(
    experimentIdPin !== '' ? experimentIdPin : deriveExperimentId(configuration.title)
  );

  // Separate deriveds, keyed only on the public halves, so they recompute when a key changes and
  // not on every keystroke in the study text.
  const derivedSignerKeyId = $derived(deriveSignerKeyId(configuration.signer.public_key));
  const derivedExportKeyId = $derived(
    deriveExportKeyId(configuration.export.hpke_public_key)
  );
  const signerKeyId = $derived(signerKeyIdPin !== '' ? signerKeyIdPin : derivedSignerKeyId);
  const exportKeyId = $derived(exportKeyIdPin !== '' ? exportKeyIdPin : derivedExportKeyId);

  /** The document minus its own name, which is what its name is a digest of. */
  const unnamed = $derived({
    ...configuration,
    experiment_id: experimentId,
    configuration_id: '',
    signer: { ...configuration.signer, key_id: signerKeyId },
    export: { ...configuration.export, researcher_key_id: exportKeyId }
  });
  const configurationId = $derived(
    deriveConfigurationId(experimentId, canonicalizeConfiguration(unnamed))
  );

  /**
   * What is validated, canonicalised, signed, and downloaded. Never the editable object: the spread
   * is shallow, so collection and intervention arrays pass through as the `$state` proxies they
   * are and stay reactive.
   *
   * `signer` and `export` are the exception — they are replaced by fresh literals above, so that
   * each carries a derived name rather than the inert placeholder in the editable object. Nothing is
   * lost by it: the spreads read `configuration.signer` and `configuration.export` inside the
   * `$derived`, which is what registers the dependency, so an edit to either still lands here.
   */
  const document = $derived({ ...unnamed, configuration_id: configurationId });

  const canonical = $derived(canonicalizeConfiguration(document));
  const bytes = $derived(canonicalConfigurationBytes(document));
  const issues = $derived(validate(document));
  const cost = $derived(estimate(document));
  const stale = $derived(signedCanonical !== null && signedCanonical !== canonical);
  const requiresBlindingConfirmation = $derived(
    configurationRequiresBlindingConfirmation(configuration)
  );

  const issuesByStep = $derived.by(() => {
    const byStep: Record<StepId, Issue[]> = { keys: [], study: [], sign: [], files: [], read: [] };
    for (const issue of issues) byStep[stepForPath(issue.path)].push(issue);
    return byStep;
  });

  const issuesByPath = $derived.by(() => {
    const byPath = new Map<string, Issue[]>();
    for (const issue of issues) {
      const list = byPath.get(issue.path);
      if (list) list.push(issue);
      else byPath.set(issue.path, [issue]);
    }
    return byPath;
  });

  /**
   * The key's face, not a product of signing: it exists the moment the signing key does, which is
   * when a researcher can start putting it into recruitment material.
   */
  const fingerprint = $derived(
    configuration.signer.public_key ? fingerprintOf(configuration.signer.public_key) : null
  );

  const bothHeld = $derived(signing.kind === 'held' && hpke.kind === 'held');
  const keysSaved = $derived(kept['signing-private'] && kept['hpke-private']);

  /**
   * Nothing has happened yet: no key exists, no study text, no attempt to sign. Every issue on the
   * document at that moment is a field nobody has reached, and a rail that greets a reader with a
   * red dot is a rail they learn to stop reading.
   *
   * The sign step needs this now because both key names are derived from key material, so on a page
   * holding no keys their `required` issues are the keys step's own emptiness restated at a second
   * address — and `stepForPath` sends them here, where the only control that can answer them is.
   * On the page proper this is false one microtask in, because the keys are made on mount.
   */
  const untouched = $derived(
    signing.kind === 'empty' &&
      hpke.kind === 'empty' &&
      !studyStarted(configuration) &&
      !attempted
  );

  /** The two whose loss is unrecoverable, and the only two that need acknowledging. */
  const SECRETS: readonly ArtifactId[] = ['signing-private', 'hpke-private'];
  const isSecret = (id: ArtifactId) => SECRETS.includes(id);

  /** How many artefacts exist at all: two before signing, four after. */
  const artifactCount = $derived(envelope && !stale ? 4 : 2);

  const savedCount = $derived.by(() => {
    let total = 0;
    if (kept['signing-private']) total += 1;
    if (kept['hpke-private']) total += 1;
    if (artifactCount === 4) {
      if (kept.canonical) total += 1;
      if (kept.partcfg) total += 1;
    }
    return total;
  });

  function stateOf(step: StepId): StepState {
    const count = issuesByStep[step].length;
    switch (step) {
      case 'keys':
        // Nothing held at all now means generation failed, because the page generates on arrival.
        if (signing.kind === 'empty' && hpke.kind === 'empty') return 'empty';
        if (count > 0) return 'blocked';
        // Held but never written down. `partial`, not `blocked`: with keys existing from the second
        // second, `blocked` would paint the rail on arrival — the exact thing the `pristine` guard
        // exists to prevent. Signing is still allowed, and before a signature exists an unsaved key
        // costs one regenerate.
        if (!bothHeld || !keysSaved) return 'partial';
        return 'complete';
      case 'study':
        if (!studyStarted(configuration)) return 'empty';
        return count > 0 ? 'blocked' : 'complete';
      case 'sign':
        if (count > 0 && !untouched) return 'blocked';
        if (envelope && !stale) return 'complete';
        return signedCanonical !== null || attempted ? 'partial' : 'empty';
      case 'files':
        if (savedCount === 0) return 'empty';
        return savedCount === artifactCount ? 'complete' : 'partial';
      case 'read':
        // Two states, because there is no third honest one. This step owns no schema path, so it
        // has nothing that can be `blocked`, and it produces no artefact that can be half saved —
        // a decryption either verified its tag or produced nothing at all. `complete` here does not
        // mean the study is done; it means a bundle is open in this tab, which is what
        // `researcher.read.opened` says on the dot rather than borrowing `Nothing to fix`.
        return bundle ? 'complete' : 'empty';
    }
  }

  /** Field issues appear on blur, or everywhere at once the moment a sign is attempted. */
  function visibleIssues(path: string): Issue[] {
    if (!attempted && !touched.has(path)) return [];
    return issuesByPath.get(path) ?? [];
  }

  function indexOf(id: CollectorId): number {
    return configuration.collectors.findIndex((collector) => collector.id === id);
  }

  function generateSigning() {
    const pair = generateSigningKeyPair();
    signing = { kind: 'held', material: pair };
    configuration.signer.public_key = pair.publicKey;
    sent['signing-private'] = false;
    kept['signing-private'] = false;
  }

  function generateHpke() {
    const pair = generateHpkeKeyPair();
    hpke = { kind: 'held', material: pair };
    configuration.export.hpke_public_key = pair.publicKey;
    sent['hpke-private'] = false;
    kept['hpke-private'] = false;
  }

  /**
   * A file's name is only adopted from a file that could not have been named here: legal, and not
   * already the name this derivation gives that file's own key. A configuration this page produced
   * adopts nothing, so the pin stays empty and the invariant keeps holding; a CLI-made one keeps
   * its historical name, which is the continuity case. Adopting unconditionally would pin every
   * file and then carry a stale name onto the next key the researcher imports.
   */
  function adoptedName(value: string, derived: string): string {
    return ID_PATTERN.test(value) && value !== derived ? value : '';
  }

  return {
    /**
     * The editable object. Its `experiment_id`, `configuration_id`, `signer.key_id` and
     * `export.researcher_key_id` are inert placeholders; the derived four live on `document`.
     */
    get configuration() {
      return configuration;
    },
    /** The document as it will be signed, with both identifiers in it. */
    get document() {
      return document;
    },
    get experimentId() {
      return experimentId;
    },
    get configurationId() {
      return configurationId;
    },
    /** What the two keys are called. Read off the document, so a loaded file is named correctly. */
    get signerKeyId() {
      return signerKeyId;
    },
    get exportKeyId() {
      return exportKeyId;
    },
    /** The override's own value, which is what the field is bound to. `''` is "derived". */
    get experimentIdPin() {
      return experimentIdPin;
    },
    get signerKeyIdPin() {
      return signerKeyIdPin;
    },
    get exportKeyIdPin() {
      return exportKeyIdPin;
    },
    get signing() {
      return signing;
    },
    get hpke() {
      return hpke;
    },
    get issues() {
      return issues;
    },
    get issuesByStep() {
      return issuesByStep;
    },
    get canonical() {
      return canonical;
    },
    get canonicalBytes() {
      return bytes;
    },
    get estimate() {
      return cost;
    },
    get fingerprint() {
      return fingerprint;
    },
    get signature() {
      return stale ? null : signature;
    },
    get envelope() {
      return stale ? null : envelope;
    },
    /** Signed once, then edited: the sign step drops back and the hand-off empties out. */
    get stale() {
      return stale;
    },
    get attempted() {
      return attempted;
    },
    get blindingConfirmed() {
      return blindingConfirmed;
    },
    get requiresBlindingConfirmation() {
      return requiresBlindingConfirmation;
    },
    /** On disk as far as anyone here can know: the researcher said so, or nothing needed saying. */
    get saved() {
      return kept;
    },
    /** A download was started. Not the same claim, and the two secrets keep them apart. */
    get sent() {
      return sent;
    },
    /**
     * A held key with no copy anywhere else, over something the researcher has committed to. What
     * the unload guard and the leave dialog watch.
     *
     * The last clause is what keeps generating on arrival from arming `beforeunload` against
     * somebody who opened the page and closed it again: two keys nobody has written a study around
     * cost one regenerate, and a browser prompt over that teaches a reader to dismiss browser
     * prompts. Once the study text exists or a signature has been made, losing them costs the work.
     */
    get keysAtRisk() {
      return (
        (signing.kind === 'held' || hpke.kind === 'held') &&
        !keysSaved &&
        (studyStarted(configuration) || signedCanonical !== null)
      );
    },
    /** Participant-supplied content; null until AEAD and the complete Protocol document verify. */
    get bundle() {
      return bundle;
    },
    get artifactCount() {
      return artifactCount;
    },
    get savedCount() {
      return savedCount;
    },

    stateOf,
    visibleIssues,

    /** `collectors.2.profiles.0.config.…`, matching the paths `validate` emits. */
    collectorPath(id: CollectorId): string {
      const index = indexOf(id);
      return index < 0 ? `collectors.${id}` : `collectors.${index}`;
    },

    collector(id: CollectorId): CollectorConfig | null {
      return configuration.collectors.find((candidate) => candidate.id === id) ?? null;
    },

    touch(path: string) {
      touched.add(path);
    },

    confirmBlinding(value: boolean) {
      blindingConfirmed = value;
    },

    /** Empty restores the derived name. Anything else is taken as typed, and `validate` judges it. */
    pinExperimentId(value: string) {
      experimentIdPin = value;
    },

    pinSignerKeyId(value: string) {
      signerKeyIdPin = value;
    },

    pinExportKeyId(value: string) {
      exportKeyIdPin = value;
    },

    /** Always in the codec's order, so two otherwise-identical studies stay diffable. */
    enableCollector(id: CollectorId) {
      if (indexOf(id) >= 0) return;
      const next = defaultCollector(id);
      configuration.collectors.push(next);
      configuration.collectors.sort((left, right) => left.id.localeCompare(right.id));
      configuration.automations.push(continuousBinding(next));
      configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
    },

    disableCollector(id: CollectorId) {
      const index = indexOf(id);
      if (index >= 0) configuration.collectors.splice(index, 1);
      configuration.automations = configuration.automations.filter(
        (automation) => automation.type !== 'resource_binding' ||
          automation.resource.kind !== 'collector' || automation.resource.id !== id
      );
    },

    setCollectorRequired(id: CollectorId, required: boolean) {
      const collector = configuration.collectors.find((candidate) => candidate.id === id);
      if (!collector) return;
      collector.required = required;

      // Keep the generated continuous-collection macro valid as the researcher changes whether
      // this resource is mandatory. Custom bindings are never rewritten behind their author's
      // back; their own validation issue remains visible until every inactive outcome is resolved.
      const owner = configuration.automations.find((automation) =>
        automation.type === 'resource_binding' &&
        automation.resource.kind === 'collector' &&
        automation.resource.id === id
      );
      if (!owner || owner.type !== 'resource_binding') return;
      const profileId = continuousBindingProfile(owner);
      if (profileId !== null) owner.default_profile_id = required ? profileId : null;
    },

    addCollectorProfile(id: CollectorId): string | null {
      const collector = configuration.collectors.find((candidate) => candidate.id === id);
      if (!collector || collector.profiles.length >= 64) return null;
      const used = new Set(collector.profiles.map((profile) => profile.id));
      let ordinal = 2;
      while (used.has(`profile-${ordinal}`)) ordinal += 1;
      const profileId = `profile-${ordinal}`;
      collector.profiles.push({
        id: profileId,
        config: structuredClone(collector.profiles[0].config)
      } as never);
      collector.profiles.sort((left, right) => left.id.localeCompare(right.id));
      return profileId;
    },

    renameCollectorProfile(id: CollectorId, previous: string, next: string) {
      const collector = configuration.collectors.find((candidate) => candidate.id === id);
      const profile = collector?.profiles.find((candidate) => candidate.id === previous);
      if (!collector || !profile) return;
      profile.id = next;
      collector.profiles.sort((left, right) => left.id.localeCompare(right.id));
      for (const automation of configuration.automations) {
        if (automation.type !== 'resource_binding' || automation.resource.kind !== 'collector' || automation.resource.id !== id) continue;
        if (automation.default_profile_id === previous) automation.default_profile_id = next;
        for (const entry of automation.cases) if (entry.profile_id === previous) entry.profile_id = next;
      }
    },

    removeCollectorProfile(id: CollectorId, profileId: string) {
      const collector = configuration.collectors.find((candidate) => candidate.id === id);
      if (!collector || collector.profiles.length <= 1) return;
      const index = collector.profiles.findIndex((profile) => profile.id === profileId);
      if (index < 0) return;
      collector.profiles.splice(index, 1);
      const replacement = collector.profiles[0].id;
      for (const automation of configuration.automations) {
        if (automation.type !== 'resource_binding' || automation.resource.kind !== 'collector' || automation.resource.id !== id) continue;
        if (automation.default_profile_id === profileId) automation.default_profile_id = replacement;
        for (const entry of automation.cases) if (entry.profile_id === profileId) entry.profile_id = replacement;
      }
    },

    addSurvey(survey: SurveyDefinition) {
      configuration.surveys.push(survey);
    },

    removeSurvey(index: number) {
      const removed = configuration.surveys[index]?.id;
      configuration.surveys.splice(index, 1);
      configuration.interventions = configuration.interventions.filter(
        (item) => item.action.type !== 'survey' || item.action.survey_id !== removed
      );
    },

    addIntervention(intervention: InterventionConfig) {
      configuration.interventions.push(intervention);
      configuration.interventions.sort((left, right) => left.id.localeCompare(right.id));
    },

    removeIntervention(index: number) {
      const id = configuration.interventions[index]?.id;
      configuration.interventions.splice(index, 1);
      configuration.automations = configuration.automations.filter(
        (automation) => automation.type !== 'occurrence' || automation.intervention_id !== id
      );
    },

    generateSigning,
    generateHpke,

    /**
     * Whichever key does not exist yet. The page calls this once on mount, because a researcher
     * pressing a button to make a key they were always going to need is a decision that is not one.
     * Idempotent, so it never destroys a key that is already held — including one just imported.
     */
    ensureKeys() {
      if (signing.kind === 'empty') generateSigning();
      if (hpke.kind === 'empty') generateHpke();
    },

    /** Both imports derive the public half locally; the file beside it is never trusted for it. */
    importSigning(text: string) {
      const pair = signingKeyPairFromPrivate(text);
      signing = { kind: 'held', material: pair };
      configuration.signer.public_key = pair.publicKey;
      sent['signing-private'] = false;
      kept['signing-private'] = false;
    },

    importHpke(text: string) {
      const pair = hpkeKeyPairFromPrivate(text);
      hpke = { kind: 'held', material: pair };
      configuration.export.hpke_public_key = pair.publicKey;
      sent['hpke-private'] = false;
      kept['hpke-private'] = false;
    },

    /**
     * Loading replaces the document, then re-attaches whichever public halves are held here: a
     * loaded file's signer is only usable by whoever holds its private key, and this page cannot
     * know whether that is the same person sitting in front of it.
     */
    load(source: Uint8Array) {
      const loaded = parseConfiguration(source);
      // Measured against the file's *own* public halves, before the two lines below replace them:
      // the question is whether this file names its keys the way this page would have.
      signerKeyIdPin = adoptedName(
        loaded.signer.key_id,
        deriveSignerKeyId(loaded.signer.public_key)
      );
      exportKeyIdPin = adoptedName(
        loaded.export.researcher_key_id,
        deriveExportKeyId(loaded.export.hpke_public_key)
      );
      if (signing.kind === 'held') loaded.signer.public_key = signing.material.publicKey;
      if (hpke.kind === 'held') loaded.export.hpke_public_key = hpke.material.publicKey;
      configuration = loaded;
      // The file's own name, adopted. A file whose name this editor could not have written is not
      // inherited: the title derives one instead, and the researcher can still override it.
      experimentIdPin = ID_PATTERN.test(loaded.experiment_id) ? loaded.experiment_id : '';
      signature = null;
      envelope = null;
      signedCanonical = null;
      attempted = false;
      blindingConfirmed = false;
      touched.clear();
      sent = { ...sent, canonical: false, partcfg: false };
      kept = { ...kept, canonical: false, partcfg: false };
    },

    /**
     * The whole result of a decryption, or `null` to drop it. Assigned in one move because that is
     * the CLI's guarantee reproduced: `researcher-tools decrypt` stages its output and publishes
     * nothing until AEAD and the complete closed-world document verify. Nothing partial reaches
     * this field either.
     */
    holdBundle(value: ResearchBundle | null) {
      bundle = value;
    },

    /**
     * A download was started. For the two derivable artefacts that is the whole story; for a
     * private key it is not, so the tile keeps its ring until {@link markKept}.
     */
    markSent(id: ArtifactId) {
      sent[id] = true;
      if (!isSecret(id)) kept[id] = true;
    },

    /** The researcher says the file is on their disk. The only claim a browser cannot make. */
    markKept(id: ArtifactId) {
      kept[id] = true;
    },

    /**
     * `researcher-tools sign`, step for step. The self-check is the CLI's own guard: a signature
     * that does not verify against the `signer.public_key` already inside the configuration makes a
     * file that signs cleanly here and fails on every device, so nothing at all is produced.
     */
    sign(): SignOutcome {
      attempted = true;
      if (requiresBlindingConfirmation && !blindingConfirmed) return 'failed';
      if (signing.kind !== 'held') return 'failed';
      const material = signing.material;
      // One snapshot for the whole act, so the bytes that are signed, the bytes that go in the
      // envelope, and the string staleness is measured against cannot be three different documents.
      const target = document;
      if (target.signer.public_key !== material.publicKey) return 'mismatch';
      const text = canonicalizeConfiguration(target);
      const payload = canonicalConfigurationBytes(target);
      let produced: Uint8Array;
      let container: Uint8Array;
      try {
        produced = signBytes(payload, material.privateKey);
        if (!verify(payload, produced, target.signer.public_key)) return 'mismatch';
        container = encodeEnvelope(target.signer.key_id, payload, produced);
      } catch {
        return 'failed';
      }
      signature = produced;
      envelope = container;
      signedCanonical = text;
      // The name is now in a file somebody else will hold, so it stops following the title. After
      // this, editing the prose moves `configuration_id` — which is correct — and nothing else.
      if (experimentIdPin === '') experimentIdPin = target.experiment_id;
      sent = { ...sent, canonical: false, partcfg: false };
      kept = { ...kept, canonical: false, partcfg: false };
      return 'signed';
    },

    reset() {
      configuration = emptyConfiguration();
      experimentIdPin = '';
      signerKeyIdPin = '';
      exportKeyIdPin = '';
      signing = { kind: 'empty' };
      hpke = { kind: 'empty' };
      signature = null;
      envelope = null;
      signedCanonical = null;
      attempted = false;
      blindingConfirmed = false;
      sent = { ...NO_ARTIFACTS };
      kept = { ...NO_ARTIFACTS };
      bundle = null;
      touched.clear();
    }
  };
}

export type Draft = ReturnType<typeof createDraft>;
