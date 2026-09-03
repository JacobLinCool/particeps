<script lang="ts">
  /** Whitelist projection of researcher state into participant-visible preview content. */
  import Note from '$lib/ui/Note.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import { PARTICIPANT_VPN_DISCLOSURE, participantStudyUiModel } from '$lib/particeps/participant-projection';
  import { i18n } from '$lib/ui/i18n.svelte';
  import type { Draft } from './draft.svelte';

  let { draft }: { draft: Draft } = $props();
  const model = $derived(participantStudyUiModel(draft.configuration));
  const zh = $derived(i18n.locale === 'zh-TW');
  const disclosure = $derived(PARTICIPANT_VPN_DISCLOSURE[i18n.locale]);
</script>

<div class="preview" data-testid="participant-preview">
  <header class="preview__head">
    <span class="micro faint">{zh ? '參與者預覽' : 'Participant preview'}</span>
    <strong>{model.title || (zh ? '研究名稱' : 'Study title')}</strong>
    <p>{model.purpose || (zh ? '研究目的會顯示在這裡。' : 'The study purpose appears here.')}</p>
  </header>
  <div class="preview__sources">
    <span class="micro faint">{zh ? '可能收集的資料類別' : 'Data categories this study may collect'}</span>
    <ul>
      {#each model.data_category_ids as collectorId (collectorId)}
        <li>{i18n.m.collector[collectorId].name}</li>
      {/each}
    </ul>
  </div>
  {#if model.shows_traffic_disclosure}
    <Note icon="connection" tone="plain" text={disclosure} />
  {/if}
</div>

{#if draft.requiresBlindingConfirmation}
  <ToggleField
    label={zh
      ? '我確認 Particeps 產生的參與者介面不會揭露實驗分組、觸發條件或調整時機。'
      : 'I confirm that Particeps-generated participant UI does not reveal treatment, trigger conditions, or adjustment timing.'}
    value={draft.blindingConfirmed}
    onchange={(value) => draft.confirmBlinding(value)}
  />
{/if}

<style>
  .preview {
    display: grid;
    gap: var(--sp-5);
    padding: var(--sp-6);
    border: var(--line-solid) solid var(--rule-strong);
    border-radius: var(--r-panel);
    background: var(--surface);
  }
  .preview__head { display: grid; gap: var(--sp-3); }
  .preview__head strong { font-size: var(--type-title); line-height: var(--lh-tight); }
  .preview__head p { max-inline-size: 48rem; }
  .preview__sources { display: grid; gap: var(--sp-3); }
  .preview__sources ul { display: flex; flex-wrap: wrap; gap: var(--sp-3); margin: 0; padding: 0; list-style: none; }
  .preview__sources li { padding: var(--sp-2) var(--sp-4); border-radius: 999px; background: var(--surface-raised); }
</style>
