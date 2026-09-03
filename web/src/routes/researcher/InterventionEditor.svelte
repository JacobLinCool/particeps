<script lang="ts">
  /** Reusable one-shot actions and their independently owned occurrence automations. */
  import Button from '$lib/ui/Button.svelte';
  import ChoiceField from '$lib/ui/ChoiceField.svelte';
  import Field from '$lib/ui/Field.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import Panel from '$lib/ui/Panel.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import NumberField from './AutomationNumberField.svelte';
  import { RESEARCHER_EVENTS } from '$lib/particeps/registry';
  import type {
    AutomationSchedule,
    AutomationTrigger,
    DurationClock,
    EventMatcher,
    InterventionAction,
    InterventionConfig,
    OccurrenceAutomation
  } from '$lib/particeps/types';
  import type { Messages } from '$lib/i18n/types';
  import type { Draft } from './draft.svelte';

  let { draft, m, locale }: { draft: Draft; m: Messages; locale: 'en' | 'zh-TW' } = $props();
  const configuration = $derived(draft.configuration);
  const uid = $props.id();

  const CLOCKS = $derived([
    { value: 'ACTIVE_RUNNING_TIME' as DurationClock, label: m.intervention.clocks.active },
    { value: 'CALENDAR_TIME' as DurationClock, label: m.intervention.clocks.calendar }
  ]);
  const SCHEDULE_TYPES = $derived([
    { value: 'one_time', label: m.intervention.schedules.oneTime },
    { value: 'interval', label: m.intervention.schedules.interval },
    { value: 'daily_local', label: m.intervention.schedules.dailyLocal },
    { value: 'random_window', label: m.intervention.schedules.randomWindow }
  ] as const);
  const copy = $derived(locale === 'zh-TW' ? {
    interventionId: '研究活動 ID', automationId: 'Automation ID', trigger: '觸發條件',
    schedule: '排程', event: '事件', condition: '條件', elapsed: '已經過秒數',
    availability: '可使用時間（秒）', maximum: '最多觸發次數'
  } : {
    interventionId: 'Intervention ID', automationId: 'Automation ID', trigger: 'Trigger',
    schedule: 'Schedule', event: 'Event', condition: 'Condition', elapsed: 'Elapsed seconds',
    availability: 'Availability (seconds)', maximum: 'Maximum activations'
  });
  const TRIGGER_TYPES = $derived([
    { value: 'schedule', label: copy.schedule },
    { value: 'event_match', label: copy.event },
    { value: 'condition_rising_edge', label: copy.condition }
  ] as const);

  function freshId(stem: string, values: Iterable<string>): string {
    const used = new Set(values); let ordinal = 1;
    while (used.has(`${stem}-${ordinal}`)) ordinal += 1;
    return `${stem}-${ordinal}`;
  }

  function defaultSchedule(): AutomationSchedule {
    return { type: 'one_time', offset_minutes: 60, clock: 'ACTIVE_RUNNING_TIME' };
  }

  function addAction(action: InterventionAction): void {
    const id = freshId('intervention', configuration.interventions.map((item) => item.id));
    const intervention: InterventionConfig = { id, required: false, action };
    draft.addIntervention(intervention);
    configuration.automations.push({
      type: 'occurrence',
      id: freshId('prompt', configuration.automations.map((item) => item.id)),
      trigger: { type: 'schedule', schedule: defaultSchedule() },
      guard: null,
      intervention_id: id,
      availability_seconds: 3_600,
      cooldown: null,
      maximum_activations: 1
    });
    sortAutomations();
  }

  function addNotification(): void {
    addAction({ type: 'notification', notification_title: '', notification_message: '' });
  }

  function addSurvey(): void {
    const surveyId = freshId('survey', configuration.surveys.map((item) => item.id));
    configuration.surveys.push({
      id: surveyId,
      title: { default: '', translations: {} },
      description: { default: '', translations: {} },
      questions: [{
        type: 'short_text', id: 'response', prompt: { default: '', translations: {} },
        required: false, maximum_length: 200
      }]
    });
    addAction({ type: 'survey', notification_title: '', notification_message: '', survey_id: surveyId });
  }

  function occurrenceFor(id: string): OccurrenceAutomation | null {
    return configuration.automations.find(
      (item): item is OccurrenceAutomation => item.type === 'occurrence' && item.intervention_id === id
    ) ?? null;
  }

  function renameIntervention(previous: string, next: string): void {
    const intervention = configuration.interventions.find((item) => item.id === previous);
    if (!intervention) return;
    intervention.id = next;
    for (const automation of configuration.automations) {
      if (automation.type === 'occurrence' && automation.intervention_id === previous) automation.intervention_id = next;
    }
    configuration.interventions.sort((left, right) => left.id.localeCompare(right.id));
  }

  function removeIntervention(intervention: InterventionConfig): void {
    const surveyId = intervention.action.type === 'survey' ? intervention.action.survey_id : null;
    const index = configuration.interventions.indexOf(intervention);
    if (index >= 0) draft.removeIntervention(index);
    if (surveyId !== null) {
      const surveyIndex = configuration.surveys.findIndex((survey) => survey.id === surveyId);
      if (surveyIndex >= 0) configuration.surveys.splice(surveyIndex, 1);
    }
  }

  function changeTrigger(
    automation: OccurrenceAutomation,
    type: 'schedule' | 'event_match' | 'condition_rising_edge'
  ): void {
    if (type === 'schedule') automation.trigger = { type, schedule: defaultSchedule() };
    else if (type === 'condition_rising_edge') automation.trigger = {
      type, condition: { type: 'elapsed_at_least', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' }
    };
    else {
      const first = RESEARCHER_EVENTS[0];
      automation.trigger = {
        type, selector: matcher(first.source.source_id, first.source.schema_version, first.event.event_type),
        evaluation_clock: first.event.clock.automation_time_inputs[0]
      };
    }
  }

  function changeSchedule(automation: OccurrenceAutomation, type: AutomationSchedule['type']): void {
    if (automation.trigger.type !== 'schedule') return;
    automation.trigger.schedule = type === 'one_time'
      ? { type, offset_minutes: 60, clock: 'ACTIVE_RUNNING_TIME' }
      : type === 'interval'
        ? { type, start_offset_minutes: 60, interval_minutes: 1_440, clock: 'ACTIVE_RUNNING_TIME' }
        : type === 'daily_local'
          ? { type, local_time: '09:00' }
          : {
              type, local_windows: [{ start_local_time: '09:00', end_local_time: '12:00' }],
              occurrences_per_window: 1, maximum_occurrences_per_day: 1,
              maximum_occurrences_total: 14, minimum_separation_minutes: 60
            };
  }

  function matcher(sourceId: string, schemaVersion: number, eventType: string): EventMatcher {
    return { event: { source_id: sourceId, schema_version: schemaVersion, event_type: eventType }, predicates: [] };
  }

  function selectEvent(automation: OccurrenceAutomation, key: string): void {
    if (automation.trigger.type !== 'event_match') return;
    const selected = RESEARCHER_EVENTS.find(({ source, event }) =>
      `${source.source_id}:${source.schema_version}:${event.event_type}` === key
    );
    if (!selected) return;
    automation.trigger.selector = matcher(selected.source.source_id, selected.source.schema_version, selected.event.event_type);
    automation.trigger.evaluation_clock = selected.event.clock.automation_time_inputs[0];
  }

  function sortAutomations(): void {
    configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
  }

  function eventKey(automation: OccurrenceAutomation): string {
    if (automation.trigger.type !== 'event_match') return '';
    const event = automation.trigger.selector.event;
    return `${event.source_id}:${event.schema_version}:${event.event_type}`;
  }
</script>

<div class="stack">
  {#if configuration.interventions.length === 0}
    <p class="fine faint">{m.intervention.empty}</p>
  {/if}

  {#each configuration.interventions as intervention (intervention)}
    {@const index = configuration.interventions.indexOf(intervention)}
    {@const path = `interventions.${index}`}
    {@const automation = occurrenceFor(intervention.id)}
    <Panel title={m.intervention.one} icon="bell">
      {#snippet trailing()}
        <IconButton icon="trash" label={m.control.remove} variant="danger" onclick={() => removeIntervention(intervention)} />
      {/snippet}

      <div class="stack">
        <IdField
          label={copy.interventionId}
          path={`${path}.id`}
          value={intervention.id}
          onchange={(value) => renameIntervention(intervention.id, value)}
        />
        <ToggleField
          label={m.field.label.required}
          path={`${path}.required`}
          value={intervention.required}
          onchange={(value) => (intervention.required = value)}
        />
        <TextField
          label={m.intervention.notificationTitle}
          path={`${path}.action.notification_title`}
          value={intervention.action.notification_title}
          max={120}
          onchange={(value) => (intervention.action.notification_title = value)}
        />
        <TextField
          label={m.intervention.notificationMessage}
          path={`${path}.action.notification_message`}
          value={intervention.action.notification_message}
          max={500}
          multiline
          onchange={(value) => (intervention.action.notification_message = value)}
        />

        {#if intervention.action.type === 'survey'}
          {@const surveyAction = intervention.action}
          {@const survey = configuration.surveys.find((item) => item.id === surveyAction.survey_id)}
          {#if survey}
            <TextField label={m.intervention.surveyTitle} path={`surveys.${configuration.surveys.indexOf(survey)}.title.default`} value={survey.title.default} max={2_000} onchange={(value) => (survey.title.default = value)} />
            <TextField label={m.intervention.surveyDescription} path={`surveys.${configuration.surveys.indexOf(survey)}.description.default`} value={survey.description.default} max={2_000} multiline onchange={(value) => (survey.description.default = value)} />
            {#if survey.questions[0]?.type === 'short_text'}
              <TextField label={m.intervention.prompt} path={`surveys.${configuration.surveys.indexOf(survey)}.questions.0.prompt.default`} value={survey.questions[0].prompt.default} max={2_000} onchange={(value) => (survey.questions[0].prompt.default = value)} />
            {/if}
          {/if}
        {/if}

        {#if automation}
          {@const automationIndex = configuration.automations.indexOf(automation)}
          {@const automationPath = `automations.${automationIndex}`}
          <div class="automation" id={`${uid}-${automation.id}`}>
            <IdField
              label={copy.automationId}
              path={`${automationPath}.id`}
              value={automation.id}
              onchange={(value) => { automation.id = value; sortAutomations(); }}
            />
            <ChoiceField
              label={copy.trigger}
              path={`${automationPath}.trigger.type`}
              value={automation.trigger.type === 'sequence' || automation.trigger.type === 'window_threshold' ? 'event_match' : automation.trigger.type}
              options={TRIGGER_TYPES}
              onchange={(value) => changeTrigger(automation, value)}
            />

            {#if automation.trigger.type === 'schedule'}
              {@const schedule = automation.trigger.schedule}
              <ChoiceField label={m.intervention.scheduleType} path={`${automationPath}.trigger.schedule.type`} value={schedule.type} options={SCHEDULE_TYPES} onchange={(value) => changeSchedule(automation, value)} />
              {#if schedule.type === 'one_time'}
                <NumberField label={m.intervention.offset} path={`${automationPath}.trigger.schedule.offset_minutes`} value={schedule.offset_minutes} min={0} max={configuration.duration_hours * 60 - 1} onchange={(value) => (schedule.offset_minutes = value)} />
                <ChoiceField label={m.intervention.clock} value={schedule.clock} options={CLOCKS} onchange={(value) => (schedule.clock = value)} />
              {:else if schedule.type === 'interval'}
                <NumberField label={m.intervention.offset} value={schedule.start_offset_minutes} min={0} max={configuration.duration_hours * 60 - 1} onchange={(value) => (schedule.start_offset_minutes = value)} />
                <NumberField label={m.intervention.interval} value={schedule.interval_minutes} min={1} max={525_600} onchange={(value) => (schedule.interval_minutes = value)} />
                <ChoiceField label={m.intervention.clock} value={schedule.clock} options={CLOCKS} onchange={(value) => (schedule.clock = value)} />
              {:else if schedule.type === 'daily_local'}
                <Field label={m.intervention.localTime} path={`${automationPath}.trigger.schedule.local_time`}>
                  {#snippet children({ id, describedby, invalid })}
                    <input class="input" type="time" {id} aria-describedby={describedby} aria-invalid={invalid || undefined} value={schedule.local_time} oninput={(event) => (schedule.local_time = event.currentTarget.value)} />
                  {/snippet}
                </Field>
              {:else}
                <NumberField label={m.intervention.totalMaximum} value={schedule.maximum_occurrences_total} min={1} max={512} onchange={(value) => (schedule.maximum_occurrences_total = value)} />
              {/if}
            {:else if automation.trigger.type === 'event_match'}
              <Field label={copy.event} path={`${automationPath}.trigger.selector.event`}>
                {#snippet children({ id, describedby, invalid })}
                  <select class="input" {id} aria-describedby={describedby} aria-invalid={invalid || undefined} value={eventKey(automation)} onchange={(event) => selectEvent(automation, event.currentTarget.value)}>
                    {#each RESEARCHER_EVENTS as option (`${option.source.source_id}:${option.event.event_type}`)}
                      <option value={`${option.source.source_id}:${option.source.schema_version}:${option.event.event_type}`}>{option.source.source_id} · {option.event.event_type}</option>
                    {/each}
                  </select>
                {/snippet}
              </Field>
            {:else if automation.trigger.type === 'condition_rising_edge' && automation.trigger.condition.type === 'elapsed_at_least'}
              {@const elapsedCondition = automation.trigger.condition}
              <NumberField label={copy.elapsed} value={elapsedCondition.duration_seconds} min={1} max={configuration.duration_hours * 3_600} onchange={(value) => (elapsedCondition.duration_seconds = value)} />
              <ChoiceField label={m.intervention.clock} value={elapsedCondition.clock} options={CLOCKS} onchange={(value) => (elapsedCondition.clock = value)} />
            {/if}

            <div class="automation__limits">
              <NumberField label={copy.availability} path={`${automationPath}.availability_seconds`} value={automation.availability_seconds} min={1} max={31_536_000} onchange={(value) => (automation.availability_seconds = value)} />
              <NumberField label={copy.maximum} path={`${automationPath}.maximum_activations`} value={automation.maximum_activations} min={1} max={512} onchange={(value) => (automation.maximum_activations = value)} />
            </div>
          </div>
        {/if}
      </div>
    </Panel>
  {/each}

  <div class="row">
    <Button label={m.intervention.addNotification} icon="plus" onclick={addNotification} testid="add-notification" />
    <Button label={m.intervention.addSurvey} icon="plus" onclick={addSurvey} testid="add-survey" />
  </div>
</div>

<style>
  .automation {
    display: grid;
    gap: var(--sp-5);
    padding-block-start: var(--sp-5);
    border-block-start: var(--line-hair) solid var(--rule);
  }

  .automation__limits {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
    gap: var(--sp-5);
  }
</style>
