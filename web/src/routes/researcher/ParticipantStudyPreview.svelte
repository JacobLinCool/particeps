<script lang="ts">
  /** Whitelist projection of researcher state into participant-visible preview content. */
  import SurveyPreview from './SurveyPreview.svelte';
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
  <dl>
    <dt>{zh ? '研究人員與聯絡方式' : 'Researcher and contact'}</dt>
    <dd>{draft.configuration.researcher.name} · {draft.configuration.researcher.contact}</dd>
    <dt>{zh ? '參與時長' : 'Participation duration'}</dt><dd>{draft.configuration.duration_hours} {zh ? '小時' : 'hours'}</dd>
    <dt>{zh ? '知情同意' : 'Consent'} · {draft.configuration.consent.document_version}</dt>
    <dd class="authored">{draft.configuration.consent.summary}</dd>
    {#if draft.configuration.upload}<dt>{zh ? '自動傳送目的地' : 'Automatic delivery destination'}</dt><dd>{draft.configuration.upload.endpoint}</dd>{/if}
  </dl>
  <div class="preview__sources">
    <span class="micro faint">{zh ? '可能收集的資料類別' : 'Data categories this study may collect'}</span>
    <ul>
      {#each model.data_category_ids as collectorId (collectorId)}
        <li>{i18n.m.collector[collectorId].name} · {draft.configuration.collectors.find(item => item.id === collectorId)?.required ? (zh ? '必要' : 'Required') : (zh ? '可選' : 'Optional')}</li>
      {/each}
    </ul>
  </div>
  {#if model.shows_traffic_disclosure}
    <Note icon="connection" tone="plain" text={disclosure} />
  {/if}
  {#each draft.configuration.surveys as survey (survey.id)}
    <SurveyPreview {survey} locale={i18n.locale} />
  {/each}
  {#each draft.configuration.interventions.filter(item => item.action.type === 'notification') as item (item.id)}
    {#if item.action.type === 'notification'}<section><strong>{item.action.notification_title}</strong><p class="authored">{item.action.notification_message}</p></section>{/if}
  {/each}
</div>

{#if draft.requiresBlindingConfirmation}
  <ToggleField
    path="review.blinding"
    label={zh
      ? '我確認 Particeps 產生的參與者介面不會揭露實驗分組、觸發條件或調整時機。'
      : 'I confirm that Particeps-generated participant UI does not reveal treatment, trigger conditions, or adjustment timing.'}
    value={draft.blindingConfirmed}
    onchange={(value) => draft.confirmBlinding(value)}
  />
{/if}

<style>
  dl { margin: 0; display: grid; gap: var(--sp-2); } dd { margin: 0 0 var(--sp-4); overflow-wrap: anywhere; } dt { font-weight: 600; } .authored { white-space: pre-wrap; }
  .preview {
    display: grid;
    gap: var(--sp-5);
    padding: var(--sp-6);
    border: var(--line-solid) solid var(--rule);
    border-radius: var(--r-panel);
    background: var(--surface);
  }
  .preview__head { display: grid; gap: var(--sp-3); }
  .preview__head strong { font-size: var(--type-title); line-height: var(--lh-tight); }
  .preview__head p { max-inline-size: 48rem; }
  .preview__sources { display: grid; gap: var(--sp-3); }
  .preview__sources ul { display: flex; flex-wrap: wrap; gap: var(--sp-3); margin: 0; padding: 0; list-style: none; }
  .preview__sources li { padding: var(--sp-2) var(--sp-4); border-radius: 999px; background: var(--surface-sunk); }
</style>
