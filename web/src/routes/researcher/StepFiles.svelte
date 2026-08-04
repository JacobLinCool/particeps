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
  import JoinLinkPanel from './JoinLinkPanel.svelte';
  import {
    ARTIFACTS,
    artifactBytes,
    artifactFilename,
    type ArtifactId,
    type ArtifactSource
  } from './artifacts';
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
      draft.signing.kind === 'held' ? draft.signing.material.privateKey : null,
    hpkePrivate: draft.hpke.kind === 'held' ? draft.hpke.material.privateKey : null,
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

  const names = $derived({ signerKeyId: draft.signerKeyId, exportKeyId: draft.exportKeyId });
  const signingName = $derived(artifactFilename('signing-private', m, names));
  const hpkeName = $derived(artifactFilename('hpke-private', m, names));

  /** The block that actually gets pasted into an information sheet: the value, and why it matters. */
  const recruitment = $derived(
    `${draft.fingerprint ?? ''}\n\n${m.participant.how.fingerprint.body}`
  );
</script>

<div class="stack stack--loose" data-print="hide">
  <div class="handoff">
    <!-- The pair the researcher met on step one, literally: same two tiles, same two mark lines,
         same order. The hint is the site's only leak-side advice, and it belongs where files are
         being filed rather than where they are being made. -->
    <ArtifactGroup
      destination="hold"
      icon="lock"
      title={m.researcher.files.keep}
      hint={m.researcher.keys.handling}
      saved={holdSaved}
      total={2}
      empty={draft.signing.kind !== 'held' && draft.hpke.kind !== 'held'}
    >
      <DownloadTile
        icon="key-sign"
        filename={signingName}
        bytes={sizes['signing-private']}
        detail={m.researcher.keys.signing.algorithm}
        warning={m.researcher.keys.signing.risk}
        warningIcon="recover"
        warningTone="soft"
        tone="danger"
        secret
        sent={draft.sent['signing-private']}
        saved={draft.saved['signing-private']}
        keptLabel={m.action.confirmSaved}
        disabled={draft.signing.kind !== 'held'}
        label={`${m.action.download} ${signingName}`}
        savedLabel={signingName}
        testid="download-signing-private"
        ondownload={() => onsave('signing-private')}
        onkept={() => draft.markKept('signing-private')}
      />
      <DownloadTile
        icon="key-open"
        filename={hpkeName}
        bytes={sizes['hpke-private']}
        detail={m.researcher.keys.export.algorithm}
        warning={m.researcher.keys.export.risk}
        warningIcon="no-recover"
        warningTone="danger"
        tone="danger"
        secret
        sent={draft.sent['hpke-private']}
        saved={draft.saved['hpke-private']}
        keptLabel={m.action.confirmSaved}
        disabled={draft.hpke.kind !== 'held'}
        label={`${m.action.download} ${hpkeName}`}
        savedLabel={hpkeName}
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
    </ArtifactGroup>
  </div>

  <!-- The fingerprint is a band under the three columns rather than a fifth thing inside the send
       one. Two reasons, and the second is why it moved: the plaque exists for eye-comparison
       against something printed, and eight groups of four get one unbroken row here where a 262px
       column broke them into two; and the two sentences beside it are the only prose on this step,
       which in that column had 194px to sit in and needed three lines. `this` still points at the
       plaque, because the plaque is directly above it. -->
  {#if draft.fingerprint}
    <div class="publish">
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
    </div>
  {/if}

  {#if draft.envelope && draft.fingerprint}
    <JoinLinkPanel
      envelope={draft.envelope}
      fingerprint={draft.fingerprint}
      assignedParticipantId={draft.configuration.assigned_participant_id}
      {m}
    />
  {/if}

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

  .publish {
    display: flex;
    flex-direction: column;
    align-items: start;
    gap: var(--sp-5);
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

  /* At that width the download control and the text are competing for about 130px, and the text
     loses: `0 B · Ed25519` is thirteen characters and had 79px to sit in. The control takes its own
     row instead — the tile grows by one button height and nothing has to be read twice. */
  @container (max-width: 340px) {
    .handoff :global(.tile) {
      grid-template-columns: 40px minmax(0, 1fr);
    }

    .handoff :global(.tile__actions) {
      grid-column: 1 / -1;
      justify-self: end;
    }
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
