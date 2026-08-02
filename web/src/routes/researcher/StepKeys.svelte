<script lang="ts">
  /**
   * Keys first, not last.
   *
   * The private keys are the artefacts whose loss is worst, so they should be downloadable in the
   * first thirty seconds rather than after twenty minutes of composition. And `signer.key_id` and
   * `export.researcher_key_id` are properties of a key rather than of the study text, so they
   * belong on the key cards.
   */
  import Note from '$lib/ui/Note.svelte';
  import KeyCard from './KeyCard.svelte';
  import { keysetJson } from '$lib/adc/canonical';
  import type { Messages } from '$lib/i18n/types';
  import type { Draft } from './draft.svelte';

  interface Props {
    draft: Draft;
    m: Messages;
    /** A key that could not be generated or read. Lands here, on the step that tried. */
    failure: string;
    onsave: (id: 'signing-private' | 'hpke-private') => void;
    /** Wraps every generate and every import, so neither can fail without saying so. */
    attempt: (act: () => void, onFile: boolean) => void;
  }

  let { draft, m, failure, onsave, attempt }: Props = $props();

  const signing = $derived(draft.signing);
  const hpke = $derived(draft.hpke);
</script>

<div class="stack stack--loose">
  <Note icon="alert" tone="danger" text={m.researcher.how.local.body} />

  <!-- Written for exactly this position, and rendered nowhere until now: someone meeting `HPKE`
       for the first time needs the two jobs named before the two cards mean anything. -->
  <Note icon="info" tone="plain" text={m.researcher.how.keys.body} />

  {#if failure}
    <div role="alert" aria-live="assertive">
      <Note icon="alert" tone="danger" text={failure} />
    </div>
  {/if}

  <div class="keycards">
    <KeyCard
      kind="signing"
      icon="key-sign"
      title={m.researcher.keys.signing.title}
      algorithm={m.researcher.keys.signing.algorithm}
      role={m.researcher.keys.signing.role}
      risk={m.researcher.keys.signing.risk}
      recoverable
      keyId={draft.configuration.signer.key_id}
      keyIdPath="signer.key_id"
      issuePath="signer.public_key"
      keyIdLabel={m.field.label.signerKeyId}
      held={signing.kind === 'held'}
      secret={signing.kind === 'held' ? signing.material.privatePkcs8Base64 : null}
      fingerprint={draft.fingerprint}
      filename={m.file.signingPrivate}
      accept=".key,text/plain"
      sent={draft.sent['signing-private']}
      saved={draft.saved['signing-private']}
      {m}
      onkeyid={(value) => (draft.configuration.signer.key_id = value)}
      ongenerate={() => attempt(() => draft.generateSigning(), false)}
      onimport={(text) => attempt(() => draft.importSigning(text), true)}
      ondownload={() => onsave('signing-private')}
      onkept={() => draft.markKept('signing-private')}
    />

    <KeyCard
      kind="hpke"
      icon="key-open"
      title={m.researcher.keys.export.title}
      algorithm={m.researcher.keys.export.algorithm}
      role={m.researcher.keys.export.role}
      risk={m.researcher.keys.export.risk}
      recoverable={false}
      keyId={draft.configuration.export.researcher_key_id}
      keyIdPath="export.researcher_key_id"
      issuePath="export.tink_hpke_public_keyset"
      keyIdLabel={m.field.label.exportKeyId}
      held={hpke.kind === 'held'}
      secret={hpke.kind === 'held' ? keysetJson(hpke.material.privateKeyset) : null}
      filename={m.file.exportPrivate}
      accept=".json,application/json"
      sent={draft.sent['hpke-private']}
      saved={draft.saved['hpke-private']}
      {m}
      onkeyid={(value) => (draft.configuration.export.researcher_key_id = value)}
      ongenerate={() => attempt(() => draft.generateHpke(), false)}
      onimport={(text) => attempt(() => draft.importHpke(text), true)}
      ondownload={() => onsave('hpke-private')}
      onkept={() => draft.markKept('hpke-private')}
    />
  </div>

  <Note icon="lock" tone="plain" text={m.researcher.keys.handling} />
</div>

<style>
  .keycards {
    display: grid;
    gap: var(--sp-6);
    align-items: start;
  }

  @media (min-width: 760px) {
    .keycards {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
</style>
