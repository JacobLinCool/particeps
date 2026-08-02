<script lang="ts">
  /**
   * Three columns, left to right in order of how far the thing travels: hold, store, send. The
   * spatial ordering and the colour temperature carry the secrecy classification, so no tile needs
   * a badge saying what kind of thing it is.
   *
   * The canonical JSON gets its own column and is not a nicety. `researcher-tools decrypt --config`
   * takes the canonical configuration rather than the envelope, and no command extracts one from an
   * `.adccfg`: a researcher who downloads only the signed file cannot decrypt their own data.
   *
   * No zip and no download-all. Browsers handle sequential downloads badly, the CLI refuses to
   * overwrite for the same reason, and two private keys are two decisions.
   */
  import ArtifactGroup from '$lib/ui/ArtifactGroup.svelte';
  import Button from '$lib/ui/Button.svelte';
  import CopyButton from '$lib/ui/CopyButton.svelte';
  import DownloadTile from '$lib/ui/DownloadTile.svelte';
  import Fingerprint from '$lib/ui/Fingerprint.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import Note from '$lib/ui/Note.svelte';
  import { ARTIFACTS, artifactBytes, type ArtifactId, type ArtifactSource } from './artifacts';
  import { keysetJson } from '$lib/adc/canonical';
  import type { Draft } from './draft.svelte';
  import type { Messages } from '$lib/i18n/types';

  interface Props {
    draft: Draft;
    m: Messages;
    onsave: (id: ArtifactId) => void;
    onsign: () => void;
  }

  let { draft, m, onsave, onsign }: Props = $props();

  const source = $derived<ArtifactSource>({
    signingPrivate:
      draft.signing.kind === 'held' ? draft.signing.material.privatePkcs8Base64 : null,
    hpkePrivate: draft.hpke.kind === 'held' ? keysetJson(draft.hpke.material.privateKeyset) : null,
    canonical: draft.canonicalBytes,
    envelope: draft.envelope
  });

  const sizes = $derived(
    Object.fromEntries(
      ARTIFACTS.map((artifact) => [artifact.id, artifactBytes(artifact.id, source)?.length ?? 0])
    ) as Record<ArtifactId, number>
  );

  const holdSaved = $derived(
    (draft.saved['signing-private'] ? 1 : 0) + (draft.saved['hpke-private'] ? 1 : 0)
  );
  const signed = $derived(draft.envelope !== null);

  /** The block that actually gets pasted into an information sheet: the value, and why it matters. */
  const recruitment = $derived(
    `${draft.fingerprint ?? ''}\n\n${m.participant.how.fingerprint.body}`
  );
</script>

<div class="stack stack--loose" data-print="hide">
  <div class="handoff">
    <ArtifactGroup
      destination="hold"
      icon="lock"
      title={m.researcher.files.keep}
      saved={holdSaved}
      total={2}
      empty={draft.signing.kind !== 'held' && draft.hpke.kind !== 'held'}
    >
      <DownloadTile
        icon="key-sign"
        filename={m.file.signingPrivate}
        bytes={sizes['signing-private']}
        detail={m.researcher.keys.signing.algorithm}
        tone="danger"
        secret
        sent={draft.sent['signing-private']}
        saved={draft.saved['signing-private']}
        keptLabel={m.action.confirmSaved}
        disabled={draft.signing.kind !== 'held'}
        label={`${m.action.download} ${m.file.signingPrivate}`}
        savedLabel={m.file.signingPrivate}
        testid="download-signing-private"
        ondownload={() => onsave('signing-private')}
        onkept={() => draft.markKept('signing-private')}
      />
      <DownloadTile
        icon="key-open"
        filename={m.file.exportPrivate}
        bytes={sizes['hpke-private']}
        detail={m.researcher.keys.export.algorithm}
        tone="danger"
        secret
        sent={draft.sent['hpke-private']}
        saved={draft.saved['hpke-private']}
        keptLabel={m.action.confirmSaved}
        disabled={draft.hpke.kind !== 'held'}
        label={`${m.action.download} ${m.file.exportPrivate}`}
        savedLabel={m.file.exportPrivate}
        warning={m.researcher.keys.export.risk}
        testid="download-hpke-private"
        ondownload={() => onsave('hpke-private')}
        onkept={() => draft.markKept('hpke-private')}
      />
    </ArtifactGroup>

    <ArtifactGroup
      destination="store"
      icon="archive"
      title={m.researcher.sign.canonical}
      hint={m.researcher.files.archive}
      saved={draft.saved.canonical ? 1 : 0}
      total={1}
      empty={!signed}
    >
      <DownloadTile
        icon="json"
        filename={m.file.canonical}
        bytes={signed ? sizes.canonical : 0}
        saved={draft.saved.canonical}
        disabled={!signed}
        label={`${m.action.download} ${m.file.canonical}`}
        savedLabel={m.file.canonical}
        testid="download-canonical"
        ondownload={() => onsave('canonical')}
      />
    </ArtifactGroup>

    <ArtifactGroup
      destination="send"
      icon="send"
      title={m.researcher.files.distribute}
      saved={draft.saved.adccfg ? 1 : 0}
      total={1}
      empty={!signed}
    >
      <DownloadTile
        icon="package"
        filename={m.file.signed}
        bytes={sizes.adccfg}
        tone="accent"
        saved={draft.saved.adccfg}
        disabled={!signed}
        label={`${m.action.download} ${m.file.signed}`}
        savedLabel={m.file.signed}
        testid="download-adccfg"
        ondownload={() => onsave('adccfg')}
      />

      {#if draft.fingerprint}
        <Fingerprint
          value={draft.fingerprint}
          size="plaque"
          copyable
          copyLabel={m.control.copyFingerprint}
          copiedLabel={m.status.copied}
        />
        <Note icon="info" tone="plain" text={m.researcher.sign.publish} />
        <Note icon="send" tone="plain" text={m.researcher.files.publish} />
        <div class="row row--tight">
          <CopyButton
            text={() => recruitment}
            label={m.action.copy}
            copiedLabel={m.status.copied}
            failedLabel={m.error.clipboard}
            variant="text"
            testid="copy-recruitment"
          />
          <!-- Printing is publishing, which is why the catalogue's one sentence about publishing
               the fingerprint is also this control's name. -->
          <IconButton
            icon="print"
            label={m.control.print}
            onclick={() => window.print()}
            testid="print"
          />
        </div>
      {/if}
    </ArtifactGroup>
  </div>

  {#if !signed}
    <div class="row">
      <Note icon="info" tone="plain" text={m.empty.files} />
      <Button variant="primary" icon="seal" label={m.action.sign} onclick={onsign} />
    </div>
  {/if}

  <Note icon="alert" tone="caution" text={m.researcher.files.pilot} />
</div>

<!-- Print: the study, the contact, and the eight groups, at a size someone can compare across a
     room. Everything else on the page is hidden by the global print rules. -->
<div class="sheet" data-print="only" aria-hidden="true">
  <h2>{draft.configuration.title}</h2>
  <p>{draft.configuration.researcher.name} · {draft.configuration.researcher.contact}</p>
  {#if draft.fingerprint}
    <Fingerprint value={draft.fingerprint} size="plaque" testid="print-fingerprint" />
  {/if}
</div>

<style>
  .handoff {
    display: grid;
    gap: var(--sp-6);
    align-items: start;
  }

  @media (min-width: 900px) {
    .handoff {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }

  /* Three columns inside a 64rem panel leave a tile about 250px wide, and the shared sheet lets a
     filename break at any character to guarantee it never overflows. Here it never has to: these
     names break at their own hyphens, which is where a reader would break them. */
  .handoff :global(.tile) {
    grid-template-columns: 40px minmax(0, 1fr) auto;
    gap: var(--sp-4);
    padding: var(--sp-5);
  }

  .handoff :global(.tile__mark) {
    inline-size: 40px;
    block-size: 40px;
  }

  .handoff :global(.tile__name) {
    overflow-wrap: break-word;
  }

  /* Eye-comparison against a printed sheet is the whole point of the plaque, and eight groups
     packed to fit break as 7 + 1 or 3 + 3 + 2 — three different shapes for the same value. Where
     the column is wide enough for four groups but not eight, it becomes two rows of four. The
     query is on the column, not the viewport, because the column is what decides. */
  .handoff > :global(*) {
    container-type: inline-size;
  }

  @container (min-width: 300px) and (max-width: 620px) {
    .handoff :global(.fingerprint--plaque) {
      display: grid;
      grid-template-columns: repeat(4, auto);
      justify-content: center;
    }
  }

  .sheet {
    display: none;
  }

  @media print {
    .sheet :global(.fingerprint--plaque) {
      font-size: 24pt;
    }
  }
</style>
