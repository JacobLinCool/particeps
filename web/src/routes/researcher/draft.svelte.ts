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
 * Two fields are held apart from the rest. `configuration` is the editable object, and nothing in
 * it names it: `experiment_id` and `configuration_id` are derived here (`lib/adc/ids.ts`) and
 * written into `document`, which is what gets validated, canonicalised, signed, and downloaded.
 * Nobody types an identifier, and the property the old section note asked a researcher to maintain
 * by hand — change anything, change the configuration ID — is now true by construction.
 */

import { canonicalBytes, canonicalize } from '$lib/adc/canonical';
import {
  fingerprint as fingerprintOf,
  generateSigningKeyPair,
  sign as signBytes,
  verify,
  type SigningKeyPair
} from '$lib/adc/crypto';
import { encodeEnvelope } from '$lib/adc/envelope';
import { deriveConfigurationId, deriveExperimentId } from '$lib/adc/ids';
import { defaultCollector, emptyConfiguration, validate, type Issue } from '$lib/adc/schema';
import { generateHpkeKeyset, type HpkeKeyset } from '$lib/adc/tink';
import {
  COLLECTOR_ORDER,
  ID_PATTERN,
  type CollectorConfig,
  type CollectorId,
  type PromptConfig,
  type StudyConfiguration
} from '$lib/adc/types';
import type { StepState } from '$lib/ui/types';
import { SvelteSet } from 'svelte/reactivity';
import { estimate } from './estimate';
import { hpkeKeysetFromPrivate, signingKeyPairFromPrivate } from './keys';
import { parseConfiguration } from './parse';
import { stepForPath, type StepId } from './steps';
import type { ArtifactId } from './artifacts';

export type KeyState<T> = { kind: 'empty' } | { kind: 'held'; material: T };

/** What a sign attempt did. `mismatch` is the one failure that must interrupt. */
export type SignOutcome = 'signed' | 'mismatch' | 'failed';

export { COLLECTOR_ORDER };

const NO_ARTIFACTS: Record<ArtifactId, boolean> = {
  'signing-private': false,
  'hpke-private': false,
  canonical: false,
  adccfg: false
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
    configuration.prompts.length > 0 ||
    configuration.upload !== null
  );
}

export function createDraft() {
  let configuration = $state<StudyConfiguration>(emptyConfiguration());
  let signing = $state<KeyState<SigningKeyPair>>({ kind: 'empty' });
  let hpke = $state<KeyState<HpkeKeyset>>({ kind: 'empty' });

  let signature = $state.raw<Uint8Array | null>(null);
  let envelope = $state.raw<Uint8Array | null>(null);
  let signedCanonical = $state.raw<string | null>(null);

  let attempted = $state(false);
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
   * one it read. Opening the English `.adccfg`, retyping the prose in Chinese, and re-signing
   * therefore inherits the experiment and regenerates the configuration, with nothing to remember.
   */
  let experimentIdPin = $state('');

  const experimentId = $derived(
    experimentIdPin !== '' ? experimentIdPin : deriveExperimentId(configuration.title)
  );

  /** The document minus its own name, which is what its name is a digest of. */
  const unnamed = $derived({
    ...configuration,
    experiment_id: experimentId,
    configuration_id: ''
  });
  const configurationId = $derived(deriveConfigurationId(experimentId, canonicalize(unnamed)));

  /**
   * What is validated, canonicalised, signed, and downloaded. Never the editable object: the spread
   * is shallow, so every nested `$state` proxy passes through by reference and stays reactive.
   */
  const document = $derived({ ...unnamed, configuration_id: configurationId });

  const canonical = $derived(canonicalize(document));
  const bytes = $derived(canonicalBytes(document));
  const issues = $derived(validate(document));
  const cost = $derived(estimate(document));
  const stale = $derived(signedCanonical !== null && signedCanonical !== canonical);

  const issuesByStep = $derived.by(() => {
    const byStep: Record<StepId, Issue[]> = { keys: [], study: [], sign: [], files: [] };
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
      if (kept.adccfg) total += 1;
    }
    return total;
  });

  function stateOf(step: StepId): StepState {
    const count = issuesByStep[step].length;
    switch (step) {
      case 'keys':
        if (signing.kind === 'empty' && hpke.kind === 'empty') return 'empty';
        if (count > 0) return 'blocked';
        // Held but never written down. Non-blocking — signing is still allowed — but the only copy
        // of the irreplaceable thing is in a browser tab, and the dot says so.
        if (!bothHeld || !keysSaved) return 'blocked';
        return 'complete';
      case 'study':
        if (!studyStarted(configuration)) return 'empty';
        return count > 0 ? 'blocked' : 'complete';
      case 'sign':
        if (count > 0) return 'blocked';
        if (envelope && !stale) return 'complete';
        return signedCanonical !== null || attempted ? 'partial' : 'empty';
      case 'files':
        if (savedCount === 0) return 'empty';
        return savedCount === artifactCount ? 'complete' : 'partial';
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

  return {
    /** The editable object. Its `experiment_id` and `configuration_id` are inert placeholders. */
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
    /** The override's own value, which is what the field is bound to. `''` is "derived". */
    get experimentIdPin() {
      return experimentIdPin;
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
    /** On disk as far as anyone here can know: the researcher said so, or nothing needed saying. */
    get saved() {
      return kept;
    },
    /** A download was started. Not the same claim, and the two secrets keep them apart. */
    get sent() {
      return sent;
    },
    /** A held key with no copy anywhere else. What the unload guard and the ring both watch. */
    get keysAtRisk() {
      return (signing.kind === 'held' || hpke.kind === 'held') && !keysSaved;
    },
    get artifactCount() {
      return artifactCount;
    },
    get savedCount() {
      return savedCount;
    },

    stateOf,
    visibleIssues,

    /** `collectors.2.config.…`, matching the paths `validate` emits. */
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

    /** Empty restores the derived name. Anything else is taken as typed, and `validate` judges it. */
    pinExperimentId(value: string) {
      experimentIdPin = value;
    },

    /** Always in the codec's order, so two otherwise-identical studies stay diffable. */
    enableCollector(id: CollectorId) {
      if (indexOf(id) >= 0) return;
      const rank = COLLECTOR_ORDER.indexOf(id);
      const at = configuration.collectors.findIndex(
        (collector) => COLLECTOR_ORDER.indexOf(collector.id) > rank
      );
      const next = defaultCollector(id);
      if (at < 0) configuration.collectors.push(next);
      else configuration.collectors.splice(at, 0, next);
    },

    disableCollector(id: CollectorId) {
      const index = indexOf(id);
      if (index >= 0) configuration.collectors.splice(index, 1);
    },

    addPrompt(prompt: PromptConfig) {
      configuration.prompts.push(prompt);
    },

    removePrompt(index: number) {
      configuration.prompts.splice(index, 1);
    },

    generateSigning() {
      const pair = generateSigningKeyPair();
      signing = { kind: 'held', material: pair };
      configuration.signer.public_key = pair.publicX509Base64;
      sent['signing-private'] = false;
      kept['signing-private'] = false;
    },

    generateHpke() {
      const keyset = generateHpkeKeyset();
      hpke = { kind: 'held', material: keyset };
      configuration.export.tink_hpke_public_keyset = keyset.publicKeyset;
      sent['hpke-private'] = false;
      kept['hpke-private'] = false;
    },

    /** Both imports derive the public half locally; the file beside it is never trusted for it. */
    importSigning(text: string) {
      const pair = signingKeyPairFromPrivate(text);
      signing = { kind: 'held', material: pair };
      configuration.signer.public_key = pair.publicX509Base64;
      sent['signing-private'] = false;
      kept['signing-private'] = false;
    },

    importHpke(text: string) {
      const keyset = hpkeKeysetFromPrivate(text);
      hpke = { kind: 'held', material: keyset };
      configuration.export.tink_hpke_public_keyset = keyset.publicKeyset;
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
      if (signing.kind === 'held') loaded.signer.public_key = signing.material.publicX509Base64;
      if (hpke.kind === 'held') loaded.export.tink_hpke_public_keyset = hpke.material.publicKeyset;
      configuration = loaded;
      // The file's own name, adopted. A file whose name this editor could not have written is not
      // inherited: the title derives one instead, and the researcher can still override it.
      experimentIdPin = ID_PATTERN.test(loaded.experiment_id) ? loaded.experiment_id : '';
      signature = null;
      envelope = null;
      signedCanonical = null;
      attempted = false;
      touched.clear();
      sent = { ...sent, canonical: false, adccfg: false };
      kept = { ...kept, canonical: false, adccfg: false };
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
      if (signing.kind !== 'held') return 'failed';
      const material = signing.material;
      // One snapshot for the whole act, so the bytes that are signed, the bytes that go in the
      // envelope, and the string staleness is measured against cannot be three different documents.
      const target = document;
      if (target.signer.public_key !== material.publicX509Base64) return 'mismatch';
      const text = canonicalize(target);
      const payload = canonicalBytes(target);
      let produced: Uint8Array;
      let container: Uint8Array;
      try {
        produced = signBytes(payload, material.privatePkcs8Base64);
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
      sent = { ...sent, canonical: false, adccfg: false };
      kept = { ...kept, canonical: false, adccfg: false };
      return 'signed';
    },

    reset() {
      configuration = emptyConfiguration();
      experimentIdPin = '';
      signing = { kind: 'empty' };
      hpke = { kind: 'empty' };
      signature = null;
      envelope = null;
      signedCanonical = null;
      attempted = false;
      sent = { ...NO_ARTIFACTS };
      kept = { ...NO_ARTIFACTS };
      touched.clear();
    }
  };
}

export type Draft = ReturnType<typeof createDraft>;
