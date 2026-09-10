<script lang="ts">
  import ArtifactGroup from '$lib/ui/ArtifactGroup.svelte';
  import Button from '$lib/ui/Button.svelte';
  import ConfirmDialog from '$lib/ui/ConfirmDialog.svelte';
  import { i18n } from '$lib/ui/i18n.svelte';
  import { generateSigningKeyPair, generateHpkeKeyPair, type SigningKeyPair, type HpkeKeyPair } from '$lib/particeps/crypto';
  import { deriveSignerKeyId, deriveExportKeyId } from '$lib/particeps/ids';
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
  const zh = $derived(i18n.locale === 'zh-TW');
  let replacing = $state<{ kind: 'signing' | 'hpke'; pair: SigningKeyPair | HpkeKeyPair; previous: string } | null>(null);
  const replacementBody = $derived(replacing ? `${zh ? '目前' : 'Current'}: ${replacing.previous} → ${zh ? '更換為' : 'Replace with'}: ${replacing.kind === 'signing' ? deriveSignerKeyId(replacing.pair.publicKey) : deriveExportKeyId(replacing.pair.publicKey)}. ${zh ? '設定識別碼與簽章將更新。既有檔案仍使用舊金鑰；請先保留舊私鑰，否則既有匯出資料無法解密。' : 'The configuration ID and signature will change. Existing files still use the old key. Retain its private key to decrypt existing exports.'}` : '');

  function regenerate(kind: 'signing' | 'hpke') {
    attempt(() => {
      replacing = { kind, pair: kind === 'signing' ? generateSigningKeyPair() : generateHpkeKeyPair(), previous: kind === 'signing' ? draft.signerKeyId : draft.exportKeyId };
    }, false);
  }

  async function take(kind: 'signing' | 'hpke', file: File) {
    try {
      if (file.size > 128) throw new Error('key_size');
      const text = await file.text();
      attempt(() => kind === 'signing' ? draft.importSigning(text) : draft.importHpke(text), true);
    } catch { attempt(() => { throw new Error('key_read'); }, true); }
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
  <div class="keyfiles" data-issue-host="signer.public_key export.hpke_public_key signing_private_key">
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
  <section aria-label={m.researcher.keys.reuse} data-testid="key-reuse">
    {#if draft.imported}<Note icon="import" tone="plain" text={zh ? '已保留匯入研究的公鑰。請匯入相符的簽署私鑰；解密私鑰只在讀取資料時需要。更換金鑰會建立不同的研究設定。' : 'The imported public keys are preserved. Import the matching signing private key; the export private key is only needed to read data. Replacing a key creates a different configuration.'} />{/if}
    <div class="keyreuse">
      <p class="fine faint">{m.researcher.keys.signing.title}: <code>{draft.signerKeyId}</code><br />{m.researcher.keys.export.title}: <code>{draft.exportKeyId}</code></p>

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
          label={zh ? '更換金鑰…' : 'Replace key…'}
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
          label={zh ? '更換金鑰…' : 'Replace key…'}
          onclick={() => regenerate('hpke')}
          testid="key-generate-hpke"
        />
      </div>
    </div>
  </section>
</div>

<ConfirmDialog
  open={replacing !== null}
  title={m.confirm.replaceKey.title}
  body={replacementBody}
  confirmLabel={m.action.confirm}
  cancelLabel={m.action.cancel}
  onconfirm={() => {
    const next = replacing;
    if (next) attempt(() => next.kind === 'signing' ? draft.replaceSigning(next.pair) : draft.replaceHpke(next.pair), false);
    replacing = null;
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
