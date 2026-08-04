<script lang="ts">
  /**
   * An optional transport wrapper for an already-signed artifact. The browser neither uploads the
   * `.adccfg` nor contacts a QR service: it derives the immutable join URI and renders the QR
   * locally. Consequently the hosting URL remains the researcher's explicit deployment decision.
   */
  import { createJoinLink } from '$lib/adc/join';
  import CopyButton from '$lib/ui/CopyButton.svelte';
  import Note from '$lib/ui/Note.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import type { Messages } from '$lib/i18n/types';

  interface Props {
    envelope: Uint8Array;
    fingerprint: string;
    assignedParticipantId: string | null;
    m: Messages;
  }

  let { envelope, fingerprint, assignedParticipantId, m }: Props = $props();
  let artifactUrl = $state('');
  let joinLink = $state('');
  let qrSource = $state('');
  let invalid = $state(false);
  let generation = 0;

  $effect(() => {
    const current = ++generation;
    const candidate = artifactUrl.trim();
    joinLink = '';
    qrSource = '';
    invalid = false;
    if (!candidate) return;

    try {
      const link = createJoinLink(candidate, envelope, fingerprint, assignedParticipantId);
      joinLink = link;
      void import('qrcode')
        .then((qrcode) => qrcode.toString(link, {
          type: 'svg',
          errorCorrectionLevel: 'M',
          margin: 2,
          color: { dark: '#111827', light: '#ffffff' }
        }))
        .then((svg) => {
          if (current === generation) {
            qrSource = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
          }
        })
        .catch(() => {
          if (current === generation) invalid = true;
        });
    } catch {
      invalid = true;
    }
  });
</script>

<section class="join panel panel--sunk" aria-labelledby="join-title">
  <div class="panel__head">
    <h3 class="panel__title" id="join-title">{m.researcher.files.join.title}</h3>
  </div>
  <div class="panel__body join__body">
    <div class="stack">
      <TextField
        label={m.researcher.files.join.artifactUrl}
        value={artifactUrl}
        max={2048}
        inputmode="url"
        autocomplete="url"
        placeholder={assignedParticipantId
          ? 'https://example.org/studies/dGhpcy1pcy1hLTEyOC1iaXQtdG9rZW4'
          : 'https://example.org/studies/study.adccfg'}
        hint={assignedParticipantId
          ? m.researcher.files.join.personalizedHint
          : m.researcher.files.join.artifactHint}
        onchange={(value) => (artifactUrl = value)}
      />
      {#if invalid}
        <Note icon="alert" tone="danger" text={m.researcher.files.join.invalid} />
      {:else if joinLink}
        <code class="join__link">{joinLink}</code>
        <div class="row row--tight">
          <CopyButton
            text={() => joinLink}
            label={m.researcher.files.join.copy}
            copiedLabel={m.status.copied}
            failedLabel={m.error.clipboard}
            variant="text"
            testid="copy-join-link"
          />
        </div>
      {/if}
      <Note icon="lock" tone="plain" text={m.researcher.files.join.immutable} />
    </div>

    {#if qrSource && joinLink}
      <img class="join__qr" src={qrSource} alt={m.researcher.files.join.qrAlt} />
    {/if}
  </div>
</section>

<style>
  .join__body {
    display: grid;
    gap: var(--sp-6);
    align-items: start;
  }

  .join__link {
    display: block;
    max-block-size: 7rem;
    overflow: auto;
    overflow-wrap: anywhere;
    padding: var(--sp-4);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: var(--paper);
    font-size: var(--text-micro);
  }

  .join__qr {
    inline-size: min(100%, 16rem);
    block-size: auto;
    padding: var(--sp-3);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    background: #fff;
  }

  @media (min-width: 720px) {
    .join__body {
      grid-template-columns: minmax(0, 1fr) auto;
    }
  }
</style>
