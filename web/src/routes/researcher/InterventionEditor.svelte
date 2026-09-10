<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import Panel from '$lib/ui/Panel.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import type { InterventionAction, InterventionConfig, OccurrenceAutomation } from '$lib/particeps/types';
  import type { Messages } from '$lib/i18n/types';
  import type { Draft } from './draft.svelte';
  import Select from './EditorSelectField.svelte';
  import OccurrenceEditor from './OccurrenceEditor.svelte';
  import SurveyDefinitionEditor from './SurveyDefinitionEditor.svelte';
  import { blankLocalizedText, createAutomationTrigger, createSurveyQuestion, freshEditorId, removeInterventionReferences } from './editor-model';
  let { draft, m, locale }: { draft: Draft; m: Messages; locale: 'en' | 'zh-TW' } = $props();
  const configuration = $derived(draft.configuration);
  const zh = $derived(locale === 'zh-TW');
  function sortAutomations() { configuration.automations.sort((left, right) => left.id.localeCompare(right.id)); }
  function addOccurrence(id: string) {
    configuration.automations.push({ type: 'occurrence', id: freshEditorId('prompt', configuration.automations.map((item) => item.id)), trigger: createAutomationTrigger('schedule'), guard: null, intervention_id: id, availability_seconds: 3_600, cooldown: null, maximum_activations: 1 });
    sortAutomations();
  }
  function addAction(action: InterventionAction) {
    const id = freshEditorId('intervention', configuration.interventions.map((item) => item.id));
    draft.addIntervention({ id, required: false, action }); addOccurrence(id);
  }
  function addSurvey() {
    const id = freshEditorId('survey', configuration.surveys.map((item) => item.id));
    configuration.surveys.push({ id, title: blankLocalizedText(), description: blankLocalizedText(), questions: [createSurveyQuestion('short_text', 'response')] });
    addAction({ type: 'survey', notification_title: '', notification_message: '', survey_id: id });
  }
  function occurrencesFor(id: string) { return configuration.automations.filter((item): item is OccurrenceAutomation => item.type === 'occurrence' && item.intervention_id === id); }
  function renameIntervention(intervention: InterventionConfig, next: string) {
    const previous = intervention.id; intervention.id = next;
    for (const automation of configuration.automations) if (automation.type === 'occurrence' && automation.intervention_id === previous) automation.intervention_id = next;
    configuration.interventions.sort((left, right) => left.id.localeCompare(right.id));
  }
  function renameSurvey(previous: string, next: string) {
    const survey = configuration.surveys.find((item) => item.id === previous); if (!survey) return;
    survey.id = next;
    for (const intervention of configuration.interventions) if (intervention.action.type === 'survey' && intervention.action.survey_id === previous) intervention.action.survey_id = next;
  }
</script>
<div class="stack" data-issue-host="interventions automations">
  {#if configuration.interventions.length === 0}<p class="fine faint">{m.intervention.empty}</p>{/if}
  {#each configuration.interventions as intervention (intervention)}
    {@const path = `interventions.${configuration.interventions.indexOf(intervention)}`}
    {@const occurrences = occurrencesFor(intervention.id)}
    <Panel title={intervention.action.notification_title || m.intervention.one} icon="bell">
      {#snippet trailing()}<IconButton icon="trash" label={m.control.remove} variant="danger" onclick={() => removeInterventionReferences(configuration, intervention.id)} />{/snippet}
      <div class="stack" data-issue-host={path}>
        <IdField label={zh ? '研究活動 ID' : 'Intervention ID'} path={`${path}.id`} value={intervention.id} onchange={(next) => renameIntervention(intervention, next)} />
        <Select label={zh ? '活動類型' : 'Activity type'} path={`${path}.action.type`} value={intervention.action.type} options={[{ value: 'notification', label: zh ? '通知' : 'Notification' }, { value: 'survey', label: zh ? '問卷' : 'Survey' }]} onchange={(type) => {
          const action = intervention.action;
          if (type === 'notification') intervention.action = { type, notification_title: action.notification_title, notification_message: action.notification_message };
          else {
            let survey = configuration.surveys[0];
            if (!survey) {
              survey = { id: freshEditorId('survey', configuration.surveys.map((item) => item.id)), title: blankLocalizedText(), description: blankLocalizedText(), questions: [createSurveyQuestion('short_text', 'response')] };
              configuration.surveys.push(survey);
            }
            intervention.action = { type, notification_title: action.notification_title, notification_message: action.notification_message, survey_id: survey.id };
          }
        }} />
        <ToggleField label={m.field.label.required} path={`${path}.required`} value={intervention.required} onchange={(next) => intervention.required = next} />
        <TextField label={m.intervention.notificationTitle} path={`${path}.action.notification_title`} value={intervention.action.notification_title} max={120} onchange={(next) => intervention.action.notification_title = next} />
        <TextField label={m.intervention.notificationMessage} path={`${path}.action.notification_message`} value={intervention.action.notification_message} max={500} multiline onchange={(next) => intervention.action.notification_message = next} />
        {#if intervention.action.type === 'survey'}
          {@const action = intervention.action}
          <Select label={zh ? '使用問卷' : 'Survey to present'} path={`${path}.action.survey_id`} value={action.survey_id} options={configuration.surveys.map((survey) => ({ value: survey.id, label: `${survey.title.default || survey.id} · ${survey.id}` }))} onchange={(next) => action.survey_id = next} />
          <p class="fine faint">{zh ? '在下方「問卷內容」編輯全部題目。相同問卷可以由多個研究活動共用。' : 'Edit all questions under Survey content below. Multiple activities can share one survey.'}</p>
        {/if}
        <h4>{zh ? `觸發規則（${occurrences.length}）` : `Occurrence rules (${occurrences.length})`}</h4>
        {#each occurrences as automation (automation)}
          {@const automationPath = `automations.${configuration.automations.indexOf(automation)}`}
          <details class="occurrence" open={occurrences.length === 1} data-issue-host={automationPath}>
            <summary>{automation.id} <span class="fine faint">· {automation.trigger.type === 'schedule' ? automation.trigger.schedule.type : automation.trigger.type}</span></summary>
            <div class="stack">
              <div class="row"><Button label={zh ? '移除這條觸發規則' : 'Remove this occurrence rule'} icon="trash" variant="ghost" onclick={() => configuration.automations.splice(configuration.automations.indexOf(automation), 1)} /></div>
              <OccurrenceEditor value={automation} path={automationPath} {locale} durationHours={configuration.duration_hours} onrename={(next) => { automation.id = next; sortAutomations(); }} />
            </div>
          </details>
        {/each}
        <Button label={zh ? '為這個活動新增觸發規則' : 'Add occurrence rule to this activity'} icon="plus" variant="ghost" disabled={configuration.automations.length >= 128} onclick={() => addOccurrence(intervention.id)} />
      </div>
    </Panel>
  {/each}
  <div class="row">
    <Button label={m.intervention.addNotification} icon="plus" disabled={configuration.interventions.length >= 128 || configuration.automations.length >= 128} onclick={() => addAction({ type: 'notification', notification_title: '', notification_message: '' })} testid="add-notification" />
    <Button label={m.intervention.addSurvey} icon="plus" disabled={configuration.interventions.length >= 128 || configuration.surveys.length >= 128 || configuration.automations.length >= 128} onclick={addSurvey} testid="add-survey" />
  </div>
  {#if configuration.surveys.length > 0}
    <h3>{zh ? '問卷內容' : 'Survey content'}</h3>
    {#each configuration.surveys as survey, index (survey)}
      {@const references = configuration.interventions.filter((item) => item.action.type === 'survey' && item.action.survey_id === survey.id)}
      <Panel title={survey.title.default || survey.id} icon="document">
        {#snippet trailing()}{#if references.length === 0}<IconButton icon="trash" label={zh ? '移除未使用的問卷' : 'Remove unused survey'} variant="danger" onclick={() => configuration.surveys.splice(index, 1)} />{/if}{/snippet}
        <div class="stack">
          <p class="fine faint">{zh ? `使用此問卷的研究活動：${references.map((item) => item.id).join('、') || '尚無'}` : `Activities using this survey: ${references.map((item) => item.id).join(', ') || 'none'}`}</p>
          <SurveyDefinitionEditor value={survey} path={`surveys.${index}`} {locale} onrename={(next) => renameSurvey(survey.id, next)} />
        </div>
      </Panel>
    {/each}
  {/if}
</div>
<style>
  .occurrence { padding-block: var(--sp-3); border-block-start: var(--line-hair) solid var(--rule); }
  summary { cursor: pointer; padding-block: var(--sp-3); overflow-wrap: anywhere; }
  h3, h4 { margin: 0; }
</style>
