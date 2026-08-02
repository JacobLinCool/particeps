<script lang="ts">
  /**
   * The two key cards are the same card on purpose. The differences are the message.
   *
   * Signing carries a plain circular arrow: losing it means minting a new key and re-signing.
   * Export carries the same arrow with a stroke through it, in `--danger`, and its enclosure is
   * bordered and hatched. The whole proposition is one comparison — *these two are identical
   * except one is red and its recovery arrow is crossed out* — and the sentence under the crossed
   * arrow is the catalogue's one line about it, not a paragraph nobody will read.
   *
   * A held key that has never been downloaded pulses a `--caution` ring around its download
   * control, because at that moment the only copy of an irreplaceable thing is in a browser tab.
   */
  import Button from '$lib/ui/Button.svelte';
  import ConfirmDialog from '$lib/ui/ConfirmDialog.svelte';
  import CopyButton from '$lib/ui/CopyButton.svelte';
  import Disclosure from '$lib/ui/Disclosure.svelte';
  import DropTarget from '$lib/ui/DropTarget.svelte';
  import Fingerprint from '$lib/ui/Fingerprint.svelte';
  import Icon from '$lib/ui/Icon.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import Note from '$lib/ui/Note.svelte';
  import type { IconRef } from '$lib/ui/icons';
  import type { Messages } from '$lib/i18n/types';

  interface Props {
    kind: 'signing' | 'hpke';
    icon: IconRef;
    title: string;
    algorithm: string;
    role: string;
    risk: string;
    /** Signing keys can be replaced; an export key cannot, and the mark says which. */
    recoverable: boolean;
    keyId: string;
    keyIdPath: string;
    keyIdLabel: string;
    /** The schema path this card answers for, for issues no control on it owns. */
    issuePath: string;
    held: boolean;
    /** The private half, base64 or keyset JSON. Revealed only behind the disclosure. */
    secret: string | null;
    fingerprint?: string | null;
    filename: string;
    accept: string;
    /** A download was started. Not the same claim as `saved`, and this card keeps them apart. */
    sent: boolean;
    saved: boolean;
    m: Messages;
    onkeyid: (value: string) => void;
    ongenerate: () => void;
    onimport: (text: string) => void;
    ondownload: () => void;
    onkept: () => void;
  }

  let {
    kind,
    icon,
    title,
    algorithm,
    role,
    risk,
    recoverable,
    keyId,
    keyIdPath,
    keyIdLabel,
    issuePath,
    held,
    secret,
    fingerprint = null,
    filename,
    accept,
    sent,
    saved,
    m,
    onkeyid,
    ongenerate,
    onimport,
    ondownload,
    onkept
  }: Props = $props();

  let replacing = $state(false);

  async function take(file: File) {
    onimport(await file.text());
  }

  /**
   * Generating over a held key destroys it, and the ghost control that does it sits a thumb's width
   * from the one that saves it. `Start over` confirms for the same loss; so does this.
   */
  function regenerate() {
    if (held) replacing = true;
    else ongenerate();
  }
</script>

<!-- `issuePath` is the schema path whose issue has no field to sit on: `signer.public_key` and
     `export.tink_hpke_public_keyset` are produced by the generator, not typed into a control, so the
     card itself is what an issue row scrolls to. -->
<section
  class="keycard"
  class:keycard--secret={!recoverable}
  aria-label={title}
  data-testid={`key-${kind}`}
  data-issue-host={issuePath}
>
  <header class="keycard__head">
    <Icon name={icon} size={22} tone={held ? 'accent' : 'faint'} />
    <span class="keycard__title">{title}</span>
    <span class="keycard__algorithm">{algorithm}</span>
  </header>

  <p class="keycard__recovery" class:keycard__recovery--none={!recoverable}>
    <Icon name={recoverable ? 'recover' : 'no-recover'} size={18} tone={recoverable ? 'soft' : 'danger'} />
    <span>{risk}</span>
  </p>

  <Note icon="info" tone="plain" text={role} />

  <IdField
    label={keyIdLabel}
    hint={m.field.hint.id}
    path={keyIdPath}
    value={keyId}
    onchange={onkeyid}
  />

  {#if held}
    {#if fingerprint}
      <Fingerprint value={fingerprint} size="inline" testid={`fingerprint-${kind}`} />
    {/if}

    <div class="keycard__actions">
      <span
        class={saved ? undefined : 'pulse'}
        data-loop={saved ? undefined : ''}
        data-loop-still="ring"
      >
        <Button
          variant="primary"
          icon="download"
          label={m.action.download}
          settled={saved}
          onclick={ondownload}
          testid={`key-download-${kind}`}
        />
      </span>
      <Button
        variant="ghost"
        icon="key"
        label={m.action.generate}
        onclick={regenerate}
        testid={`key-generate-${kind}`}
      />
    </div>

    <!-- A browser cannot see the disk, and a dismissed save sheet writes nothing. The ring stays
         until the one party who can tell says so, because the alternative is a green tick over a
         key that no longer exists anywhere. -->
    {#if sent && !saved}
      <div class="keycard__actions">
        <Button
          variant="quiet"
          icon="check"
          label={m.action.confirmSaved}
          onclick={onkept}
          testid={`key-kept-${kind}`}
        />
      </div>
    {/if}

    {#if secret}
      <Disclosure label={m.control.reveal} icon="eye">
        <div class="keycard__well">{secret}</div>
        <div class="row row--tight">
          <CopyButton
            text={secret}
            label={m.action.copy}
            copiedLabel={m.status.copied}
            failedLabel={m.error.clipboard}
            variant="text"
          />
        </div>
      </Disclosure>
    {/if}

    <Note icon="alert" tone="caution" text={m.researcher.keys.replace} />
  {:else}
    <div class="keycard__actions">
      <Button
        variant="primary"
        icon="key"
        label={m.action.generate}
        onclick={regenerate}
        testid={`key-generate-${kind}`}
      />
    </div>
    <!-- Import is what makes a second configuration under the same signer possible, which is what
         a study recruiting in two languages needs. -->
    <DropTarget label={filename} {accept} onfile={take} testid={`key-import-${kind}`} />
  {/if}
</section>

<ConfirmDialog
  open={replacing}
  title={m.confirm.replaceKey.title}
  body={recoverable ? m.confirm.replaceKey.body : m.researcher.keys.export.risk}
  confirmLabel={m.action.confirm}
  cancelLabel={m.action.cancel}
  onconfirm={() => {
    replacing = false;
    ongenerate();
  }}
  oncancel={() => (replacing = false)}
/>
