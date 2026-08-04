<script lang="ts">
  /**
   * The step that arrives done.
   *
   * Both key pairs are generated on mount, by `+page.svelte`, so nothing on this step is a decision
   * a researcher has any basis for making. Both names derive from the key material, so nothing is
   * typed either. What is left is the one thing that genuinely cannot be automated — the part where
   * the files land on the researcher's disk — and one sentence saying which key does what.
   *
   * Two arguments put it first in the rail, and neither of them is "download the irreplaceable
   * thing in the first thirty seconds". That was false: an export key is worthless until its public
   * half is inside a signed file that reached a phone, and losing it before that costs one
   * regenerate.
   *
   *   1. Sequence. Importing a key rewrites `signer.public_key`, which moves `configuration_id` and
   *      retires any signature. Put that on the files step and the cross-language workflow sits
   *      after the signature it invalidates.
   *   2. Attention. Folded into the hand-off screen, the one irreversible fact on the site would be
   *      the sixth element of a page that already carries three columns, four tiles, a fingerprint
   *      plaque, a print control and a pilot caution — read by someone in "let me download things"
   *      mode. A step whose whole surface is two files and one sentence gets read.
   *
   * The asymmetry between the two keys is drawn rather than written. They are the same object — a
   * `DownloadTile` in one hold `ArtifactGroup`, the pair the researcher meets again on the files
   * step — and they differ in one line, in the same slot, opening with the same word:
   *
   *     ⟳  Lost: make a new one.     soft mark, no wash
   *     ⃠  Lost: data unreadable.    danger mark, danger wash, hatched group edge
   *
   * The eye compares the second half only. Nothing else on this step competes for red, which is
   * what makes that comparison legible: the "nothing is backed up" banner is said by the page lede
   * one line above, by the site footer, by the unload guard and by the leave dialog, and the
   * handling advice moved to the files step where files are being filed.
   */
  import ArtifactGroup from '$lib/ui/ArtifactGroup.svelte';
  import Button from '$lib/ui/Button.svelte';
  import ConfirmDialog from '$lib/ui/ConfirmDialog.svelte';
  import Disclosure from '$lib/ui/Disclosure.svelte';
  import DownloadTile from '$lib/ui/DownloadTile.svelte';
  import DropTarget from '$lib/ui/DropTarget.svelte';
  import Fingerprint from '$lib/ui/Fingerprint.svelte';
  import Note from '$lib/ui/Note.svelte';
  import { artifactFilename } from './artifacts';
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

  const names = $derived({ signerKeyId: draft.signerKeyId, exportKeyId: draft.exportKeyId });
  const signingName = $derived(artifactFilename('signing-private', m, names));
  const hpkeName = $derived(artifactFilename('hpke-private', m, names));

  // Only the two private halves are made here, so the byte counts are computed from them directly
  // rather than by pulling the canonical document through `artifactBytes` on a step that has none.
  const encoder = new TextEncoder();
  const signingBytes = $derived(
    signing.kind === 'held' ? encoder.encode(signing.material.privateKey).length : 0
  );
  const hpkeBytes = $derived(
    hpke.kind === 'held' ? encoder.encode(hpke.material.privateKey).length : 0
  );

  const savedCount = $derived(
    (draft.saved['signing-private'] ? 1 : 0) + (draft.saved['hpke-private'] ? 1 : 0)
  );

  /**
   * Generating over a held key destroys it. The question is only worth asking when this tab holds
   * the only copy — which on the common path it does not, because the researcher downloaded first.
   */
  let replacing = $state<'signing' | 'hpke' | null>(null);

  function regenerate(kind: 'signing' | 'hpke') {
    const held = kind === 'signing' ? signing.kind === 'held' : hpke.kind === 'held';
    const kept = draft.saved[kind === 'signing' ? 'signing-private' : 'hpke-private'];
    if (held && !kept) replacing = kind;
    else generate(kind);
  }

  function generate(kind: 'signing' | 'hpke') {
    attempt(() => (kind === 'signing' ? draft.generateSigning() : draft.generateHpke()), false);
  }

  async function take(kind: 'signing' | 'hpke', file: File) {
    const text = await file.text();
    attempt(() => (kind === 'signing' ? draft.importSigning(text) : draft.importHpke(text)), true);
  }
</script>

<div class="stack stack--loose">
  <!-- Generation happens on arrival now, so this catches a failure the researcher did not cause:
       an insecure context or a browser without the primitives. The import path below is then the
       working fallback, which is the failure mode teaching the escape hatch. -->
  {#if failure}
    <div role="alert" aria-live="assertive">
      <Note icon="alert" tone="danger" text={failure} />
    </div>
  {/if}

  <!-- The step's one orientation line, and it maps positionally onto the two tiles below. -->
  <Note icon="info" tone="plain" text={m.researcher.how.keys.body} />

  <!-- Both paths are produced by the generator rather than typed into a control, so this block is
       what an issue row scrolls to when either key is missing. -->
  <div class="keyfiles" data-issue-host="signer.public_key export.hpke_public_key">
    <ArtifactGroup
      destination="hold"
      icon="lock"
      title={m.researcher.files.keep}
      saved={savedCount}
      total={2}
      empty={signing.kind !== 'held' && hpke.kind !== 'held'}
    >
      <DownloadTile
        icon="key-sign"
        filename={signingName}
        bytes={signingBytes}
        detail={m.researcher.keys.signing.algorithm}
        warning={m.researcher.keys.signing.risk}
        warningIcon="recover"
        warningTone="soft"
        tone="danger"
        secret
        sent={draft.sent['signing-private']}
        saved={draft.saved['signing-private']}
        keptLabel={m.action.confirmSaved}
        disabled={signing.kind !== 'held'}
        label={`${m.action.download} ${signingName}`}
        savedLabel={signingName}
        testid="key-download-signing"
        ondownload={() => onsave('signing-private')}
        onkept={() => draft.markKept('signing-private')}
      />
      <DownloadTile
        icon="key-open"
        filename={hpkeName}
        bytes={hpkeBytes}
        detail={m.researcher.keys.export.algorithm}
        warning={m.researcher.keys.export.risk}
        warningIcon="no-recover"
        warningTone="danger"
        tone="danger"
        secret
        sent={draft.sent['hpke-private']}
        saved={draft.saved['hpke-private']}
        keptLabel={m.action.confirmSaved}
        disabled={hpke.kind !== 'held'}
        label={`${m.action.download} ${hpkeName}`}
        savedLabel={hpkeName}
        testid="key-download-hpke"
        ondownload={() => onsave('hpke-private')}
        onkept={() => draft.markKept('hpke-private')}
      />
    </ArtifactGroup>
  </div>

  <!-- The rare path, and the twin of the sign step's identifier override: nobody is asked to bring
       a key, which is not the same as nobody being allowed to. A second configuration under the
       same signer is what a study recruiting in two languages needs, and the fingerprint lives here
       because on this path it is the check that the imported key is the right one. -->
  <Disclosure label={m.researcher.keys.reuse} icon="import" testid="key-reuse">
    <div class="keyreuse">
      <p class="fine faint">{m.researcher.keys.reuseNote}</p>

      <div class="row row--tight">
        <DropTarget
          label={m.researcher.keys.signing.title}
          filename={m.file.signingPrivate}
          accept=".key,text/plain"
          onfile={(file) => take('signing', file)}
          testid="key-import-signing"
        />
        <Button
          variant="ghost"
          icon="key"
          label={m.action.generate}
          onclick={() => regenerate('signing')}
          testid="key-generate-signing"
        />
      </div>

      {#if draft.fingerprint}
        <p class="keyreuse__print">
          <span class="fine faint">{m.field.label.fingerprint}</span>
          <Fingerprint value={draft.fingerprint} size="inline" testid="fingerprint-signing" />
        </p>
      {/if}

      <div class="row row--tight">
        <DropTarget
          label={m.researcher.keys.export.title}
          filename={m.file.exportPrivate}
          accept=".key,text/plain"
          onfile={(file) => take('hpke', file)}
          testid="key-import-hpke"
        />
        <Button
          variant="ghost"
          icon="key"
          label={m.action.generate}
          onclick={() => regenerate('hpke')}
          testid="key-generate-hpke"
        />
      </div>
    </div>
  </Disclosure>
</div>

<ConfirmDialog
  open={replacing !== null}
  title={m.confirm.replaceKey.title}
  body={replacing === 'hpke' ? m.researcher.keys.export.risk : m.confirm.replaceKey.body}
  confirmLabel={m.action.confirm}
  cancelLabel={m.action.cancel}
  onconfirm={() => {
    const kind = replacing;
    replacing = null;
    if (kind) generate(kind);
  }}
  oncancel={() => (replacing = null)}
/>

<style>
  /* 34rem, the measure `.note` is capped at. Two tiles in the full 788px panel read as two banners;
     at the measure they read as two files. The group's own `.stack--tight` keeps them stacked,
     which is what puts the two mark lines vertically adjacent at the same x. */
  .keyfiles {
    max-inline-size: var(--measure);
  }

  /* `.disclosure__body` carries no padding of its own — the sign step's fields bring theirs — so a
     body made of drop targets and a fingerprint has to bring its own. */
  .keyreuse {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
    padding: var(--sp-5);
  }

  .keyreuse__print {
    display: flex;
    align-items: baseline;
    gap: var(--sp-4);
    flex-wrap: wrap;
  }
</style>
