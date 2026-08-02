<script lang="ts">
  import type { Messages } from '$lib/i18n/types';
  import type {
    ChoiceOption,
    InterventionConfig,
    InterventionSchedule,
    LocalizedText,
    SurveyQuestion
  } from '$lib/adc/types';
  import type { Draft } from './draft.svelte';

  let { draft, m }: { draft: Draft; m: Messages } = $props();
  const configuration = $derived(draft.configuration);
  const copy = $derived(m.intervention);

  const localized = (text = ''): LocalizedText => ({ default: text, translations: {} });
  const option = (id: string): ChoiceOption => ({ id, label: localized('') });

  function addSurvey(): void {
    const number = configuration.surveys.length + 1;
    const surveyId = `survey-${number}`;
    draft.addSurvey({
      id: surveyId,
      title: localized(''),
      description: localized(''),
      questions: []
    });
    draft.addIntervention(baseIntervention(`intervention-${configuration.interventions.length + 1}`, {
      type: 'survey', notification_title: '', notification_message: '', survey_id: surveyId
    }));
  }

  function addNotification(): void {
    draft.addIntervention(baseIntervention(`intervention-${configuration.interventions.length + 1}`, {
      type: 'notification', notification_title: '', notification_message: ''
    }));
  }

  function baseIntervention(id: string, action: InterventionConfig['action']): InterventionConfig {
    return {
      id,
      action,
      triggers: [{
        id: `trigger-${configuration.interventions.length + 1}`,
        schedule: { type: 'one_time', offset_minutes: 60, clock: 'CALENDAR_TIME' },
        availability_minutes: 60
      }]
    };
  }

  function addQuestion(surveyIndex: number): void {
    const questions = configuration.surveys[surveyIndex].questions;
    questions.push({
      type: 'short_text', id: `question-${questions.length + 1}`, prompt: localized(''),
      required: false, maximum_length: 200
    });
  }

  function changeQuestion(surveyIndex: number, questionIndex: number, type: SurveyQuestion['type']): void {
    const previous = configuration.surveys[surveyIndex].questions[questionIndex];
    const common = { id: previous.id, prompt: previous.prompt, required: previous.required };
    const next: SurveyQuestion = type === 'short_text'
      ? { type, ...common, maximum_length: 200 }
      : type === 'scale'
        ? { type, ...common, minimum: 1, maximum: 5, minimum_label: localized(''), maximum_label: localized('') }
        : type === 'single_choice'
          ? { type, ...common, options: [option('option-1'), option('option-2')] }
          : {
              type, ...common, options: [option('option-1'), option('option-2')],
              minimum_selections: previous.required ? 1 : 0, maximum_selections: 2
            };
    configuration.surveys[surveyIndex].questions[questionIndex] = next;
  }

  function setTranslation(text: LocalizedText, value: string): void {
    if (value) text.translations['zh-TW'] = value;
    else delete text.translations['zh-TW'];
  }

  function optionsText(options: ChoiceOption[]): string {
    return options.map((item) => `${item.id} | ${item.label.default} | ${item.label.translations['zh-TW'] ?? ''}`).join('\n');
  }

  function parseOptions(value: string): ChoiceOption[] {
    return value.split('\n').filter((line) => line.trim()).map((line) => {
      const [id = '', label = '', translated = ''] = line.split('|').map((part) => part.trim());
      const translations: Record<string, string> = translated ? { 'zh-TW': translated } : {};
      return { id, label: { default: label, translations } };
    });
  }

  function addTrigger(interventionIndex: number): void {
    const intervention = configuration.interventions[interventionIndex];
    intervention.triggers.push({
      id: `trigger-${interventionIndex + 1}-${intervention.triggers.length + 1}`,
      schedule: { type: 'one_time', offset_minutes: 60, clock: 'CALENDAR_TIME' },
      availability_minutes: 60
    });
  }

  function changeSchedule(interventionIndex: number, triggerIndex: number, type: InterventionSchedule['type']): void {
    configuration.interventions[interventionIndex].triggers[triggerIndex].schedule = type === 'daily_local'
      ? { type, local_time: '08:00' }
      : type === 'interval'
        ? { type, start_offset_minutes: 60, interval_minutes: 1_440, clock: 'CALENDAR_TIME' }
        : { type, offset_minutes: 60, clock: 'CALENDAR_TIME' };
  }
</script>

<div class="identity">
  <label>
    <span>{copy.anonymous} / {copy.personalized}</span>
    <select
      value={configuration.assigned_participant_id === null ? 'anonymous' : 'personalized'}
      onchange={(event) => configuration.assigned_participant_id = event.currentTarget.value === 'anonymous' ? null : 'participant-code'}
    >
      <option value="anonymous">{copy.anonymous}</option>
      <option value="personalized">{copy.personalized}</option>
    </select>
  </label>
  {#if configuration.assigned_participant_id !== null}
    <label><span>{copy.assignedId}</span><input bind:value={configuration.assigned_participant_id} /></label>
  {/if}
</div>

{#if configuration.interventions.length === 0}
  <p class="empty">{copy.empty}</p>
{/if}
<p class="timing">{copy.notificationTiming}</p>

{#each configuration.surveys as survey, surveyIndex (survey.id)}
  <fieldset class="card">
    <legend>{copy.survey} · {survey.id}</legend>
    <div class="grid two">
      <label><span>ID</span><input bind:value={survey.id} /></label>
      <button type="button" class="remove" onclick={() => draft.removeSurvey(surveyIndex)}>{m.control.remove}</button>
      <label><span>{copy.surveyTitle}</span><input bind:value={survey.title.default} /></label>
      <label><span>{copy.surveyTitle} · zh-TW</span><input value={survey.title.translations['zh-TW'] ?? ''} onchange={(event) => setTranslation(survey.title, event.currentTarget.value)} /></label>
      <label><span>{copy.surveyDescription}</span><textarea bind:value={survey.description.default}></textarea></label>
      <label><span>{copy.surveyDescription} · zh-TW</span><textarea value={survey.description.translations['zh-TW'] ?? ''} onchange={(event) => setTranslation(survey.description, event.currentTarget.value)}></textarea></label>
    </div>
    {#each survey.questions as question, questionIndex (question.id)}
      <div class="question">
        <div class="grid two">
          <label><span>{copy.question}</span><input bind:value={question.id} /></label>
          <label><span>{copy.questionType}</span>
            <select value={question.type} onchange={(event) => changeQuestion(surveyIndex, questionIndex, event.currentTarget.value as SurveyQuestion['type'])}>
              <option value="short_text">{copy.types.shortText}</option>
              <option value="scale">{copy.types.scale}</option>
              <option value="single_choice">{copy.types.singleChoice}</option>
              <option value="multiple_choice">{copy.types.multipleChoice}</option>
            </select>
          </label>
          <label><span>{copy.prompt}</span><input bind:value={question.prompt.default} /></label>
          <label><span>{copy.prompt} · zh-TW</span><input value={question.prompt.translations['zh-TW'] ?? ''} onchange={(event) => setTranslation(question.prompt, event.currentTarget.value)} /></label>
        </div>
        <label class="check"><input type="checkbox" bind:checked={question.required} /> {copy.required}</label>
        {#if question.type === 'short_text'}
          <label><span>{copy.maximumLength}</span><input type="number" min="1" max="4000" bind:value={question.maximum_length} /></label>
        {:else if question.type === 'scale'}
          <div class="grid two">
            <label><span>{copy.scaleBounds}</span><div class="pair"><input type="number" bind:value={question.minimum} /><input type="number" bind:value={question.maximum} /></div></label>
            <label><span>{copy.endpointLabels}</span><div class="pair"><input bind:value={question.minimum_label.default} /><input bind:value={question.maximum_label.default} /></div></label>
          </div>
        {:else}
          <label><span>{copy.options}</span><textarea value={optionsText(question.options)} onchange={(event) => question.options = parseOptions(event.currentTarget.value)}></textarea></label>
          {#if question.type === 'multiple_choice'}
            <label><span>{copy.selectionBounds}</span><div class="pair"><input type="number" min="0" bind:value={question.minimum_selections} /><input type="number" min="1" bind:value={question.maximum_selections} /></div></label>
          {/if}
        {/if}
        <button type="button" class="remove" onclick={() => survey.questions.splice(questionIndex, 1)}>{m.control.remove}</button>
      </div>
    {/each}
    <button type="button" class="add" onclick={() => addQuestion(surveyIndex)}>＋ {copy.addQuestion}</button>
  </fieldset>
{/each}

{#each configuration.interventions as intervention, interventionIndex (intervention.id)}
  <fieldset class="card">
    <legend>{intervention.action.type === 'survey' ? copy.survey : copy.addNotification} · {intervention.id}</legend>
    <div class="grid two">
      <label><span>ID</span><input bind:value={intervention.id} /></label>
      {#if intervention.action.type === 'survey'}
        <label><span>{copy.survey}</span><select bind:value={intervention.action.survey_id}>{#each configuration.surveys as survey}<option value={survey.id}>{survey.id}</option>{/each}</select></label>
      {/if}
      <label><span>{copy.notificationTitle}</span><input bind:value={intervention.action.notification_title} /></label>
      <label><span>{copy.notificationMessage}</span><input bind:value={intervention.action.notification_message} /></label>
    </div>
    {#each intervention.triggers as trigger, triggerIndex (trigger.id)}
      <div class="trigger grid three">
        <label><span>{copy.trigger}</span><input bind:value={trigger.id} /></label>
        <label><span>{copy.scheduleType}</span><select value={trigger.schedule.type} onchange={(event) => changeSchedule(interventionIndex, triggerIndex, event.currentTarget.value as InterventionSchedule['type'])}><option value="one_time">{copy.schedules.oneTime}</option><option value="interval">{copy.schedules.interval}</option><option value="daily_local">{copy.schedules.dailyLocal}</option></select></label>
        <label><span>{copy.availability}</span><input type="number" min="1" bind:value={trigger.availability_minutes} /></label>
        {#if trigger.schedule.type === 'daily_local'}
          <label><span>{copy.localTime}</span><input type="time" bind:value={trigger.schedule.local_time} /></label>
        {:else}
          <label><span>{copy.clock}</span><select bind:value={trigger.schedule.clock}><option value="CALENDAR_TIME">{copy.clocks.calendar}</option><option value="ACTIVE_RUNNING_TIME">{copy.clocks.active}</option></select></label>
          {#if trigger.schedule.type === 'one_time'}
            <label><span>{copy.offset}</span><input type="number" min="0" bind:value={trigger.schedule.offset_minutes} /></label>
          {:else}
            <label><span>{copy.offset}</span><input type="number" min="0" bind:value={trigger.schedule.start_offset_minutes} /></label>
          {/if}
          {#if trigger.schedule.type === 'interval'}<label><span>{copy.interval}</span><input type="number" min="1" bind:value={trigger.schedule.interval_minutes} /></label>{/if}
        {/if}
        <button type="button" class="remove" onclick={() => intervention.triggers.splice(triggerIndex, 1)}>{m.control.remove}</button>
      </div>
    {/each}
    <div class="actions"><button type="button" class="add" onclick={() => addTrigger(interventionIndex)}>＋ {copy.addTrigger}</button><button type="button" class="remove" onclick={() => draft.removeIntervention(interventionIndex)}>{m.control.remove}</button></div>
  </fieldset>
{/each}

<div class="actions"><button type="button" class="add" data-testid="intervention-add" onclick={addNotification}>＋ {copy.addNotification}</button><button type="button" class="add" data-testid="survey-add" onclick={addSurvey}>＋ {copy.addSurvey}</button></div>

<style>
  .identity,.card,.question,.trigger{display:grid;gap:var(--space-3)}
  .identity,.card{padding:var(--space-4);border:1px solid var(--line);border-radius:var(--radius-3);background:var(--surface)}
  .card{margin-block:var(--space-4)} legend{padding-inline:var(--space-2);font-weight:700}
  .grid{display:grid;gap:var(--space-3)}.two{grid-template-columns:repeat(2,minmax(0,1fr))}.three{grid-template-columns:repeat(3,minmax(0,1fr))}
  label{display:grid;gap:6px;font-size:var(--text-sm)} input,select,textarea{width:100%;min-height:42px;padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:var(--ground);color:var(--ink)} textarea{min-height:74px;resize:vertical}
  .question,.trigger{padding:var(--space-3);border-inline-start:3px solid var(--accent);background:var(--ground)}.pair,.actions{display:flex;gap:var(--space-2)}.pair>*{min-width:0}.check{display:flex;align-items:center}.check input{width:auto;min-height:auto}
  button{min-height:40px;border-radius:8px;padding:8px 12px}.add{border:1px solid var(--accent);background:transparent;color:var(--accent)}.remove{justify-self:end;border:0;background:transparent;color:var(--danger)}.empty,.timing{color:var(--ink-muted)}
  @media(max-width:720px){.two,.three{grid-template-columns:1fr}.actions{flex-wrap:wrap}}
</style>
