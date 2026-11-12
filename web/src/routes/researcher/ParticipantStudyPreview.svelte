<script lang="ts">
  /**
   * Participant-visible preview content. Particeps-generated content comes only from the whitelist
   * projection; researcher-authored signed text (researcher, consent, surveys, notification copy)
   * is read from the draft because the App shows it verbatim.
   */
  import SurveyPreview from './SurveyPreview.svelte';
  import Note from '$lib/ui/Note.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import {
    PARTICIPANT_REQUESTED_WARNING,
    PARTICIPANT_TIER_LABELS,
    PARTICIPANT_VPN_DISCLOSURE,
    participantStudyUiModel
  } from '$lib/particeps/participant-projection';
  import { disclosureMayRevealSchedule } from '$lib/particeps/researcher-blinding';
  import { i18n } from '$lib/ui/i18n.svelte';
  import type { Draft } from './draft.svelte';

  let { draft }: { draft: Draft } = $props();
  const model = $derived(participantStudyUiModel(draft.configuration));
  const zh = $derived(i18n.locale === 'zh-TW');
  const disclosure = $derived(PARTICIPANT_VPN_DISCLOSURE[i18n.locale]);
  const revealsSchedule = $derived(disclosureMayRevealSchedule(draft.configuration));
  const tierLabels = $derived(PARTICIPANT_TIER_LABELS[i18n.locale]);
  const requestedWarning = $derived(PARTICIPANT_REQUESTED_WARNING[i18n.locale]);
  /** Researcher-authored and signed, so read from the draft and shown verbatim, as the App does. */
  const rationaleOf = (id: string) =>
    draft.configuration.collectors.find((item) => item.id === id)?.rationale ?? null;
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
    {#if model.upload_destination_host}<dt>{zh ? '自動傳送目的地' : 'Automatic delivery destination'}</dt><dd>{model.upload_destination_host}</dd>{/if}
  </dl>
  <div class="preview__sources">
    <span class="micro faint">{zh ? '可能收集的資料類別' : 'Data categories this study may collect'}</span>
    <ul>
      {#each model.data_category_ids as collectorId, index (collectorId)}
        {@const tier = model.data_category_tiers[index]}
        {@const rationale = rationaleOf(collectorId)}
        <li data-testid={`preview-category-${collectorId}`} data-tier={tier}>
          <span class="preview__category">{i18n.m.collector[collectorId].name} · <span class="preview__tier">{tierLabels[tier]}</span></span>
          {#if rationale}<span class="authored preview__rationale">{rationale}</span>{/if}
          {#if tier === 'requested'}<span class="preview__warning">{requestedWarning}</span>{/if}
        </li>
      {/each}
    </ul>
  </div>
  <!-- What the signed disclosure policy lets the App show, said as a fact about this study. -->
  <div class="preview__views" data-testid="preview-disclosure">
    <span class="micro faint">{zh ? '參與者可以看到' : 'Participants can see'}</span>
    <ul>
      <li>{zh ? '每類資料目前是否開啟，以及能否關閉' : 'Whether each category is on, and whether it can be turned off'}</li>
      {#if model.disclosure.approximate_frequency}
        <li data-testid="preview-frequency">{zh ? '每類資料大約多久收集一次' : 'Roughly how often each category collects data'}</li>
      {/if}
      {#if model.disclosure.collection_timeline}
        <li data-testid="preview-timeline">{zh ? '每類資料何時收集的時間軸，包括暫停的時段' : 'A timeline of when each category collected data, including paused periods'}</li>
      {/if}
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
    label={revealsSchedule
      ? zh
        ? '我確認 Particeps 產生的參與者介面，包括收集頻率與收集時間軸，不會揭露實驗分組、觸發條件或調整時機。'
        : 'I confirm that Particeps-generated participant UI, including the collection frequency and timeline views, does not reveal treatment, trigger conditions, or adjustment timing.'
      : zh
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
  .preview__sources li { display: grid; gap: var(--sp-2); padding: var(--sp-3) var(--sp-4); border-radius: var(--r-field); background: var(--surface-sunk); max-inline-size: 100%; }
  .preview__tier { font-weight: 600; }
  .preview__rationale { font-size: var(--type-micro); }
  .preview__warning { font-size: var(--type-micro); color: var(--caution-ink); }
  .preview__views { display: grid; gap: var(--sp-3); }
  .preview__views ul { margin: 0; padding-inline-start: var(--sp-6); display: grid; gap: var(--sp-2); }
</style>
