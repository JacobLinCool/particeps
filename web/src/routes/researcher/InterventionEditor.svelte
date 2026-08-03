<script lang="ts">
  /**
   * Surveys, and the notifications that deliver them: the one part of a study that is a list rather
   * than a document.
   *
   * The kit has no repeated-block primitive and that absence is deliberate — the seven collector
   * cards are a fixed set whose order is participant-visible. What a list needs and a document does
   * not is an identity that survives an edit, so every `{#each}` here is keyed on the block's own
   * object. Keying on `survey.id` re-keyed the block on the first keystroke, destroyed the input
   * under the cursor and dropped focus to `<body>`, which made every ID in a study one character
   * per click; and because the generated ids counted array length, re-adding after a removal minted
   * an id that was still on screen and threw `each_key_duplicate`, which took the whole step down.
   *
   * A remove sits on the thing it removes — `Panel`'s trailing slot for a card, the head row for a
   * nested part — and is named after it, because five controls all called "Remove" are one control
   * repeated five times as far as an element list is concerned.
   *
   * Every control carries the schema path it owns, so a bound that is out of range is said next to
   * the box rather than only on the sign step. Four of them have no component in the kit — select,
   * bare number, time, and a pair under one name — and those are raw elements inside a `Field`,
   * which means each one wires `aria-describedby`, `aria-invalid` and the blur that marks the path
   * touched itself.
   */
  import Button from '$lib/ui/Button.svelte';
  import ChoiceField from '$lib/ui/ChoiceField.svelte';
  import ConfirmDialog from '$lib/ui/ConfirmDialog.svelte';
  import Field from '$lib/ui/Field.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import Note from '$lib/ui/Note.svelte';
  import Panel from '$lib/ui/Panel.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import { fieldSource } from '$lib/ui/field-context';
  import { BOUNDS } from '$lib/adc/types';
  import { tick } from 'svelte';
  import type {
    ChoiceOption,
    InterventionConfig,
    InterventionSchedule,
    LocalizedText,
    RelativeClock,
    SurveyQuestion
  } from '$lib/adc/types';
  import type { UiIssue } from '$lib/ui/types';
  import type { Messages } from '$lib/i18n/types';
  import type { Draft } from './draft.svelte';

  let { draft, m }: { draft: Draft; m: Messages } = $props();

  const configuration = $derived(draft.configuration);
  const copy = $derived(m.intervention);
  const source = fieldSource();
  const uid = $props.id();

  /** A control that owns several schema paths answers for all of them; `Field` reads one out of
   *  context, so the rest are gathered here and handed to it. */
  interface Boxed {
    value: number;
    min: number;
    max: number;
    apply: (value: number) => void;
  }

  interface NumberBox extends Boxed {
    label: string;
    path: string;
    issueHost?: readonly string[];
  }

  interface NumberPair {
    label: string;
    paths: readonly [string, string];
    boxes: readonly [Boxed, Boxed];
  }

  const localized = (text = ''): LocalizedText => ({ default: text, translations: {} });
  const option = (id: string): ChoiceOption => ({ id, label: localized('') });

  /**
   * Array length is not a name. Removing the second of three and adding one back regenerates an id
   * that is still in the document, and a duplicate id is both a validation failure and — before the
   * keys changed — a thrown block. Trigger ids are unique across the whole study, not per
   * intervention, because that is the set `validate` checks them against.
   */
  function freshId(stem: string, taken: Iterable<string>): string {
    const used = new Set(taken);
    let ordinal = 1;
    while (used.has(`${stem}-${ordinal}`)) ordinal += 1;
    return `${stem}-${ordinal}`;
  }

  const triggerIds = () =>
    configuration.interventions.flatMap((intervention) =>
      intervention.triggers.map((trigger) => trigger.id)
    );

  function addSurvey(): void {
    const surveyId = freshId('survey', configuration.surveys.map((survey) => survey.id));
    draft.addSurvey({
      id: surveyId,
      title: localized(''),
      description: localized(''),
      questions: []
    });
    draft.addIntervention(
      baseIntervention({
        type: 'survey',
        notification_title: '',
        notification_message: '',
        survey_id: surveyId
      })
    );
  }

  function addNotification(): void {
    draft.addIntervention(
      baseIntervention({ type: 'notification', notification_title: '', notification_message: '' })
    );
  }

  function baseIntervention(action: InterventionConfig['action']): InterventionConfig {
    return {
      id: freshId('intervention', configuration.interventions.map((item) => item.id)),
      action,
      triggers: [
        {
          id: freshId('trigger', triggerIds()),
          schedule: { type: 'one_time', offset_minutes: 60, clock: 'CALENDAR_TIME' },
          availability_minutes: 60
        }
      ]
    };
  }

  /**
   * A rename is not a delete: an intervention referencing the old id follows it. The reference is by
   * value, so without this the picker goes blank on a keystroke and the study only says why two
   * steps later, as `unknown_reference` on the sign step.
   */
  function renameSurvey(index: number, next: string): void {
    const survey = configuration.surveys[index];
    const previous = survey.id;
    survey.id = next;
    for (const intervention of configuration.interventions) {
      if (intervention.action.type === 'survey' && intervention.action.survey_id === previous) {
        intervention.action.survey_id = next;
      }
    }
  }

  function addQuestion(surveyIndex: number): void {
    const questions = configuration.surveys[surveyIndex].questions;
    questions.push({
      type: 'short_text',
      id: freshId('question', questions.map((question) => question.id)),
      prompt: localized(''),
      required: false,
      maximum_length: 200
    });
  }

  /**
   * Everything the target type still has a home for survives the change. The two choice types
   * differ only in their selection limits, and three authored options with their translations are
   * not recoverable from a mis-click on a select.
   */
  function changeQuestion(
    surveyIndex: number,
    questionIndex: number,
    type: SurveyQuestion['type']
  ): void {
    const previous = configuration.surveys[surveyIndex].questions[questionIndex];
    const common = { id: previous.id, prompt: previous.prompt, required: previous.required };
    const options =
      'options' in previous ? previous.options : [option('option-1'), option('option-2')];
    const next: SurveyQuestion =
      type === 'short_text'
        ? { type, ...common, maximum_length: 200 }
        : type === 'scale'
          ? {
              type,
              ...common,
              minimum: 1,
              maximum: 5,
              minimum_label: localized(''),
              maximum_label: localized('')
            }
          : type === 'single_choice'
            ? { type, ...common, options }
            : {
                type,
                ...common,
                options,
                minimum_selections: previous.required ? 1 : 0,
                maximum_selections: 2
              };
    configuration.surveys[surveyIndex].questions[questionIndex] = next;
  }

  function setTranslation(text: LocalizedText, value: string): void {
    if (value) text.translations['zh-TW'] = value;
    else delete text.translations['zh-TW'];
  }

  function optionsText(options: ChoiceOption[]): string {
    return options
      .map((item) => `${item.id} | ${item.label.default} | ${item.label.translations['zh-TW'] ?? ''}`)
      .join('\n');
  }

  /** The remainder is one field, not the third of three: a translation containing a pipe used to
   *  lose everything after it. */
  function parseOptions(value: string): ChoiceOption[] {
    return value
      .split('\n')
      .filter((line) => line.trim())
      .map((line) => {
        const [id = '', label = '', ...rest] = line.split('|');
        const translated = rest.join('|').trim();
        const translations: Record<string, string> = translated ? { 'zh-TW': translated } : {};
        return { id: id.trim(), label: { default: label.trim(), translations } };
      });
  }

  /** Every path the options textarea answers for, so a bad option id is red beside the box it was
   *  typed into rather than only on the sign step. */
  function optionPaths(base: string, options: ChoiceOption[]): string[] {
    return [
      `${base}.options`,
      ...options.flatMap((item, index) => [
        `${base}.options.${index}.id`,
        `${base}.options.${index}.label.default`,
        `${base}.options.${index}.label.translations.zh-TW`
      ])
    ];
  }

  function addTrigger(interventionIndex: number): void {
    configuration.interventions[interventionIndex].triggers.push({
      id: freshId('trigger', triggerIds()),
      schedule: { type: 'one_time', offset_minutes: 60, clock: 'CALENDAR_TIME' },
      availability_minutes: 60
    });
  }

  /**
   * Both non-daily variants name a `clock`, and the offset means the same minutes from the study's
   * start in both, so replacing the whole object reverted a deliberate choice about when the study
   * fires relative to device uptime without anyone touching that control.
   */
  function changeSchedule(
    interventionIndex: number,
    triggerIndex: number,
    type: InterventionSchedule['type']
  ): void {
    const trigger = configuration.interventions[interventionIndex].triggers[triggerIndex];
    const previous = trigger.schedule;
    const clock: RelativeClock = 'clock' in previous ? previous.clock : 'CALENDAR_TIME';
    const offset =
      previous.type === 'one_time'
        ? previous.offset_minutes
        : previous.type === 'interval'
          ? previous.start_offset_minutes
          : 60;
    trigger.schedule =
      type === 'daily_local'
        ? { type, local_time: '08:00' }
        : type === 'interval'
          ? { type, start_offset_minutes: offset, interval_minutes: 1_440, clock }
          : { type, offset_minutes: offset, clock };
  }

  /**
   * The code survives a trip through anonymous. The placeholder that used to be written in its
   * place passed `ASSIGNED_PARTICIPANT_ID_PATTERN`, so a study whose participant was literally
   * named `participant-code` signed clean; an empty box raises `id_format` where it can be read.
   */
  let assigned = $state('');
  const personalized = $derived(configuration.assigned_participant_id !== null);
  $effect(() => {
    if (configuration.assigned_participant_id) assigned = configuration.assigned_participant_id;
  });

  const IDENTITY = $derived([
    { value: false, label: copy.anonymous },
    { value: true, label: copy.personalized }
  ]);

  const CLOCKS = $derived([
    { value: 'CALENDAR_TIME' as RelativeClock, label: copy.clocks.calendar },
    { value: 'ACTIVE_RUNNING_TIME' as RelativeClock, label: copy.clocks.active }
  ]);

  function issuesFor(paths: readonly string[]): UiIssue[] {
    return paths.flatMap((path) => source.issues(path) as UiIssue[]);
  }

  function touchAll(paths: readonly string[]): void {
    for (const path of paths) source.touch?.(path);
  }

  /**
   * An emptied box is not a zero: `Number('')` is 0 and finite, and committing it would rewrite the
   * field to something the researcher never typed. Nothing is written until the box parses, and the
   * box is put back to the stored value on the way out.
   */
  function commitNumber(
    event: { currentTarget: HTMLInputElement },
    apply: (value: number) => void
  ): void {
    const parsed = event.currentTarget.valueAsNumber;
    if (Number.isFinite(parsed)) apply(parsed);
  }

  /**
   * Every path the box answers for is touched, not only its own. A pair is checked against itself
   * and the schema says so against one end of it — a minimum above the maximum is reported on the
   * `maximum` path — so touching the box that was edited and nothing else left the researcher
   * looking at two numbers that contradict each other with nothing on screen saying so until they
   * happened to visit the other box.
   */
  function restoreNumber(
    event: { currentTarget: HTMLInputElement },
    value: number,
    paths: readonly string[]
  ): void {
    event.currentTarget.value = String(value);
    touchAll(paths);
  }

  /**
   * A removal takes the focused button out of the document, and the browser's answer to that is
   * `<body>`: on a page this long the tab order starts again at the top, several thousand pixels
   * above what was just removed. Focus goes to the block that took the removed one's index, or to
   * the last block when the removed one was last — the index is clamped, so removing the tail lands
   * on the new tail rather than on nothing.
   *
   * `fallback` is the testid of the control that adds another of what was just removed, for when
   * none is left. It has to be named rather than found, because the panel list ends with two add
   * controls and neither position in that row identifies which kind the caller removed.
   */
  async function removeBlock(
    from: EventTarget | null,
    kind: '.part' | '.panel',
    remove: () => void,
    fallback?: string
  ): Promise<void> {
    const block = from instanceof HTMLElement ? from.closest(kind) : null;
    const list = block?.parentElement ?? null;
    const siblings = (host: HTMLElement) =>
      [...host.children].filter((child): child is HTMLElement => child.matches(kind));
    const at = block && list ? siblings(list).indexOf(block as HTMLElement) : -1;
    remove();
    if (!list || at < 0) return;
    await tick();
    const left = siblings(list);
    const next = left[Math.min(at, left.length - 1)];
    const add = fallback
      ? document.querySelector<HTMLElement>(`[data-testid="${fallback}"]`)
      : [...list.querySelectorAll<HTMLElement>('button')].find((button) => !button.closest(kind));
    (next?.querySelector<HTMLElement>('button') ?? add)?.focus();
  }

  /** Removing a survey removes every intervention that delivers it. Confirmed only when there is a
   *  second thing to lose, and both of them are named in the dialog. */
  let cascading: number | null = $state(null);
  /** The button the dialog was opened from, so a confirmed removal can put focus where that button
   *  was rather than where the dialog leaves it. */
  let cascadeFrom: EventTarget | null = null;
  const doomed = $derived(cascading === null ? undefined : configuration.surveys[cascading]?.id);
  const cascade = $derived.by(() => {
    if (doomed === undefined) return [];
    return configuration.interventions
      .filter(
        (intervention) =>
          intervention.action.type === 'survey' && intervention.action.survey_id === doomed
      )
      .map((intervention) => intervention.id);
  });

  function removeSurvey(event: MouseEvent, index: number): void {
    const survey = configuration.surveys[index];
    const delivers = configuration.interventions.some(
      (intervention) =>
        intervention.action.type === 'survey' && intervention.action.survey_id === survey.id
    );
    if (delivers) {
      cascadeFrom = event.currentTarget;
      cascading = index;
    } else void removeBlock(event.currentTarget, '.panel', () => draft.removeSurvey(index), 'survey-add');
  }
</script>

{#snippet numberBox(box: NumberBox)}
  <Field label={box.label} path={box.path} issueHost={box.issueHost}>
    {#snippet children({ id, describedby, invalid })}
      <input
        class="input input--num"
        type="number"
        {id}
        min={box.min}
        max={box.max}
        step="1"
        aria-describedby={describedby}
        aria-invalid={invalid || undefined}
        value={box.value}
        oninput={(event) => commitNumber(event, box.apply)}
        onblur={(event) => restoreNumber(event, box.value, [box.path])}
      />
    {/snippet}
  </Field>
{/snippet}

<!-- One name for two boxes, read in the order the name states them. The catalogue has a single
     string for each pair and no separate word for either end, so `role="group"` carries that one
     name to both rather than leaving the second box unnamed and the first one's name polluted with
     the second one's value. -->
{#snippet numberPair(pair: NumberPair)}
  <Field
    label={pair.label}
    path={pair.paths[0]}
    issues={issuesFor(pair.paths)}
    issueHost={pair.paths}
  >
    {#snippet children({ id, describedby, invalid })}
      <div class="row row--tight" role="group" aria-label={pair.label}>
        {#each pair.boxes as box, index (index)}
          <input
            class="input input--num"
            type="number"
            id={index === 0 ? id : undefined}
            aria-label={pair.label}
            min={box.min}
            max={box.max}
            step="1"
            aria-describedby={describedby}
            aria-invalid={invalid || undefined}
            value={box.value}
            oninput={(event) => commitNumber(event, box.apply)}
            onblur={(event) => restoreNumber(event, box.value, pair.paths)}
          />
        {/each}
      </div>
    {/snippet}
  </Field>
{/snippet}

<div class="stack" data-issue-host="interventions">
  <!-- The two alternatives are shown at once rather than hidden behind a select: which of them a
       study is decides what travels with every event it records. The group's name is the field it
       switches on, so the decision is not announced as a menu of its own option names. -->
  <ChoiceField
    value={personalized}
    options={IDENTITY}
    groupLabel={copy.assignedId}
    onchange={(value) => (configuration.assigned_participant_id = value ? assigned : null)}
  />
  {#if personalized}
    <IdField
      label={copy.assignedId}
      path="assigned_participant_id"
      value={configuration.assigned_participant_id ?? ''}
      onchange={(value) => {
        assigned = value;
        configuration.assigned_participant_id = value;
      }}
    />
  {/if}

  {#if configuration.interventions.length > 0}
    <Note icon="clock" tone="plain" text={copy.notificationTiming} />
  {/if}

  {#each configuration.surveys as survey, surveyIndex (survey)}
    {@const path = `surveys.${surveyIndex}`}
    <Panel title={survey.id} icon="document" headingLevel={3}>
      {#snippet trailing()}
        <IconButton
          icon="trash"
          variant="danger"
          label={`${m.control.remove} · ${survey.id}`}
          onclick={(event) => removeSurvey(event, surveyIndex)}
        />
      {/snippet}

      <!-- `Survey` on its own is the picker further down, which chooses one of these; this box is
           the identifier, and it is named the way its two siblings are. -->
      <IdField
        label={`${copy.survey} ID`}
        path={`${path}.id`}
        value={survey.id}
        suggestFrom={survey.title.default}
        suggestLabel={m.control.applySuggestion}
        onchange={(value) => renameSurvey(surveyIndex, value)}
      />
      <TextField
        label={copy.surveyTitle}
        path={`${path}.title.default`}
        value={survey.title.default}
        max={BOUNDS.surveyText[1]}
        onchange={(value) => (survey.title.default = value)}
      />
      <TextField
        label={`${copy.surveyTitle} · zh-TW`}
        path={`${path}.title.translations.zh-TW`}
        value={survey.title.translations['zh-TW'] ?? ''}
        max={BOUNDS.surveyText[1]}
        onchange={(value) => setTranslation(survey.title, value)}
      />
      <TextField
        label={copy.surveyDescription}
        path={`${path}.description.default`}
        value={survey.description.default}
        max={BOUNDS.surveyText[1]}
        multiline
        rows={3}
        onchange={(value) => (survey.description.default = value)}
      />
      <TextField
        label={`${copy.surveyDescription} · zh-TW`}
        path={`${path}.description.translations.zh-TW`}
        value={survey.description.translations['zh-TW'] ?? ''}
        max={BOUNDS.surveyText[1]}
        multiline
        rows={3}
        onchange={(value) => setTranslation(survey.description, value)}
      />

      <!-- `surveys.<i>.questions` is an array-level issue with no control of its own, and the add
           button is what an issue row focuses when it lands here. -->
      <div class="stack" data-issue-host={`${path}.questions`}>
        {#each survey.questions as question, questionIndex (question)}
          {@const qPath = `${path}.questions.${questionIndex}`}
          {@const nameId = `${uid}-q${surveyIndex}-${questionIndex}`}
          <div class="part" role="group" aria-labelledby={nameId}>
            <div class="part__head">
              <span class="part__name mono" id={nameId}>{question.id}</span>
              <IconButton
                icon="trash"
                variant="danger"
                size={18}
                label={`${m.control.remove} · ${question.id}`}
                onclick={(event) =>
                  removeBlock(event.currentTarget, '.part', () =>
                    survey.questions.splice(questionIndex, 1)
                  )}
              />
            </div>

            <IdField
              label={copy.question}
              path={`${qPath}.id`}
              value={question.id}
              onchange={(value) => (question.id = value)}
            />

            <!-- A select rather than segments: four alternatives at this depth shrink until their
                 labels wrap, and an `<option>` is the one place a long name costs nothing. -->
            <Field label={copy.questionType}>
              {#snippet children({ id, describedby })}
                <select
                  class="input pick"
                  {id}
                  aria-describedby={describedby}
                  value={question.type}
                  onchange={(event) =>
                    changeQuestion(
                      surveyIndex,
                      questionIndex,
                      event.currentTarget.value as SurveyQuestion['type']
                    )}
                >
                  <option value="short_text">{copy.types.shortText}</option>
                  <option value="scale">{copy.types.scale}</option>
                  <option value="single_choice">{copy.types.singleChoice}</option>
                  <option value="multiple_choice">{copy.types.multipleChoice}</option>
                </select>
              {/snippet}
            </Field>

            <TextField
              label={copy.prompt}
              path={`${qPath}.prompt.default`}
              value={question.prompt.default}
              max={BOUNDS.surveyText[1]}
              onchange={(value) => (question.prompt.default = value)}
            />
            <TextField
              label={`${copy.prompt} · zh-TW`}
              path={`${qPath}.prompt.translations.zh-TW`}
              value={question.prompt.translations['zh-TW'] ?? ''}
              max={BOUNDS.surveyText[1]}
              onchange={(value) => setTranslation(question.prompt, value)}
            />
            <ToggleField
              label={copy.required}
              value={question.required}
              onchange={(value) => (question.required = value)}
            />

            {#if question.type === 'short_text'}
              {@render numberBox({
                label: copy.maximumLength,
                path: `${qPath}.maximum_length`,
                value: question.maximum_length,
                min: BOUNDS.shortTextMaximumLength[0],
                max: BOUNDS.shortTextMaximumLength[1],
                apply: (value) => (question.maximum_length = value)
              })}
            {:else if question.type === 'scale'}
              {@render numberPair({
                label: copy.scaleBounds,
                paths: [`${qPath}.minimum`, `${qPath}.maximum`],
                boxes: [
                  {
                    value: question.minimum,
                    min: -1_000,
                    max: 1_000,
                    apply: (value) => (question.minimum = value)
                  },
                  {
                    value: question.maximum,
                    min: -1_000,
                    max: 1_000,
                    apply: (value) => (question.maximum = value)
                  }
                ]
              })}
              <Field
                label={copy.endpointLabels}
                path={`${qPath}.minimum_label.default`}
                issues={issuesFor([
                  `${qPath}.minimum_label.default`,
                  `${qPath}.maximum_label.default`
                ])}
                issueHost={[`${qPath}.maximum_label.default`]}
              >
                {#snippet children({ id, describedby, invalid })}
                  <div class="row row--tight" role="group" aria-label={copy.endpointLabels}>
                    <input
                      class="input grow"
                      type="text"
                      {id}
                      aria-label={copy.endpointLabels}
                      aria-describedby={describedby}
                      aria-invalid={invalid || undefined}
                      maxlength={BOUNDS.surveyText[1]}
                      value={question.minimum_label.default}
                      oninput={(event) =>
                        (question.minimum_label.default = event.currentTarget.value)}
                      onblur={() => source.touch?.(`${qPath}.minimum_label.default`)}
                    />
                    <input
                      class="input grow"
                      type="text"
                      aria-label={copy.endpointLabels}
                      aria-describedby={describedby}
                      aria-invalid={invalid || undefined}
                      maxlength={BOUNDS.surveyText[1]}
                      value={question.maximum_label.default}
                      oninput={(event) =>
                        (question.maximum_label.default = event.currentTarget.value)}
                      onblur={() => source.touch?.(`${qPath}.maximum_label.default`)}
                    />
                  </div>
                {/snippet}
              </Field>
            {:else}
              {@const paths = optionPaths(qPath, question.options)}
              <!-- The selection limits are a statement about this list: shortening it is what puts
                   them out of range, so the blur that commits the list is what has to show it. A
                   list edited down to nothing left "at most 2 of 0" on screen, unremarked. They are
                   touched here and shown on their own control, which is where they are read. -->
              {@const limits =
                question.type === 'multiple_choice'
                  ? [`${qPath}.minimum_selections`, `${qPath}.maximum_selections`]
                  : []}
              <Field
                label={copy.options}
                path={`${qPath}.options`}
                issues={issuesFor(paths)}
                issueHost={paths}
              >
                {#snippet children({ id, describedby, invalid })}
                  <textarea
                    class="input input--area input--mono"
                    {id}
                    rows={4}
                    aria-describedby={describedby}
                    aria-invalid={invalid || undefined}
                    value={optionsText(question.options)}
                    onchange={(event) =>
                      (question.options = parseOptions(event.currentTarget.value))}
                    onblur={() => touchAll([...paths, ...limits])}
                  ></textarea>
                {/snippet}
              </Field>
              {#if question.type === 'multiple_choice'}
                {@render numberPair({
                  label: copy.selectionBounds,
                  paths: [`${qPath}.minimum_selections`, `${qPath}.maximum_selections`],
                  boxes: [
                    {
                      value: question.minimum_selections,
                      min: 0,
                      max: question.options.length,
                      apply: (value) => (question.minimum_selections = value)
                    },
                    {
                      value: question.maximum_selections,
                      min: 1,
                      /* The DOM attribute only, and deliberately not the schema's bound: a list too
                         short to choose two from is a fault in the list, which the options box above
                         reports. Left at `options.length` this box would declare max 0 against its
                         own min 1 — a spinbutton with no satisfiable value, stating a second and
                         invented fault. `validate` still holds it to [1, options.length] and still
                         reports that, so nothing here widens what may be signed. */
                      max: Math.max(1, question.options.length),
                      apply: (value) => (question.maximum_selections = value)
                    }
                  ]
                })}
              {/if}
            {/if}
          </div>
        {/each}

        <Button
          variant="quiet"
          icon="plus"
          label={copy.addQuestion}
          onclick={() => addQuestion(surveyIndex)}
        />
      </div>
    </Panel>
  {/each}

  {#each configuration.interventions as intervention, interventionIndex (intervention)}
    {@const path = `interventions.${interventionIndex}`}
    {@const action = intervention.action}
    <Panel title={intervention.id} icon="bell" headingLevel={3}>
      {#snippet trailing()}
        <IconButton
          icon="trash"
          variant="danger"
          label={`${m.control.remove} · ${intervention.id}`}
          onclick={(event) =>
            removeBlock(
              event.currentTarget,
              '.panel',
              () => draft.removeIntervention(interventionIndex),
              'intervention-add'
            )}
        />
      {/snippet}

      <!-- The section's own heading is directly above this box; on its own it named the box after
           the whole section rather than after what the box holds. The catalogue has no singular
           word for one intervention, so the identifier is said the way `Question ID` is. -->
      <IdField
        label={`${m.researcher.study.section.interventions.title} ID`}
        path={`${path}.id`}
        value={intervention.id}
        onchange={(value) => (intervention.id = value)}
      />

      {#if action.type === 'survey'}
        <Field label={copy.survey} path={`${path}.action.survey_id`}>
          {#snippet children({ id, describedby, invalid })}
            <select
              class="input pick"
              {id}
              aria-describedby={describedby}
              aria-invalid={invalid || undefined}
              value={action.survey_id}
              onchange={(event) => (action.survey_id = event.currentTarget.value)}
              onblur={() => source.touch?.(`${path}.action.survey_id`)}
            >
              {#each configuration.surveys as survey (survey)}
                <option value={survey.id}>{survey.id}</option>
              {/each}
            </select>
          {/snippet}
        </Field>
      {/if}

      <TextField
        label={copy.notificationTitle}
        path={`${path}.action.notification_title`}
        value={action.notification_title}
        max={BOUNDS.notificationTitle[1]}
        onchange={(value) => (action.notification_title = value)}
      />
      <TextField
        label={copy.notificationMessage}
        path={`${path}.action.notification_message`}
        value={action.notification_message}
        max={BOUNDS.notificationMessage[1]}
        multiline
        rows={3}
        onchange={(value) => (action.notification_message = value)}
      />

      <div class="stack" data-issue-host={`${path}.triggers`}>
        {#each intervention.triggers as trigger, triggerIndex (trigger)}
          {@const tPath = `${path}.triggers.${triggerIndex}`}
          {@const schedule = trigger.schedule}
          {@const nameId = `${uid}-t${interventionIndex}-${triggerIndex}`}
          <div class="part" role="group" aria-labelledby={nameId}>
            <div class="part__head">
              <span class="part__name mono" id={nameId}>{trigger.id}</span>
              <IconButton
                icon="trash"
                variant="danger"
                size={18}
                label={`${m.control.remove} · ${trigger.id}`}
                onclick={(event) =>
                  removeBlock(event.currentTarget, '.part', () =>
                    intervention.triggers.splice(triggerIndex, 1)
                  )}
              />
            </div>

            <IdField
              label={copy.trigger}
              path={`${tPath}.id`}
              value={trigger.id}
              onchange={(value) => (trigger.id = value)}
            />

            <Field label={copy.scheduleType}>
              {#snippet children({ id, describedby })}
                <select
                  class="input pick"
                  {id}
                  aria-describedby={describedby}
                  value={schedule.type}
                  onchange={(event) =>
                    changeSchedule(
                      interventionIndex,
                      triggerIndex,
                      event.currentTarget.value as InterventionSchedule['type']
                    )}
                >
                  <option value="one_time">{copy.schedules.oneTime}</option>
                  <option value="interval">{copy.schedules.interval}</option>
                  <option value="daily_local">{copy.schedules.dailyLocal}</option>
                </select>
              {/snippet}
            </Field>

            {@render numberBox({
              label: copy.availability,
              path: `${tPath}.availability_minutes`,
              value: trigger.availability_minutes,
              min: BOUNDS.availabilityMinutes[0],
              max: BOUNDS.availabilityMinutes[1],
              apply: (value) => (trigger.availability_minutes = value)
            })}

            {#if schedule.type === 'daily_local'}
              <Field label={copy.localTime} path={`${tPath}.schedule.local_time`}>
                {#snippet children({ id, describedby, invalid })}
                  <input
                    class="input input--mono clock"
                    type="time"
                    {id}
                    aria-describedby={describedby}
                    aria-invalid={invalid || undefined}
                    value={schedule.local_time}
                    oninput={(event) => (schedule.local_time = event.currentTarget.value)}
                    onblur={() => source.touch?.(`${tPath}.schedule.local_time`)}
                  />
                {/snippet}
              </Field>
            {:else}
              <!-- Segments rather than a select: both names are sentences about what the clock does
                   with a pause, and a select drew them at a width that cut the closing bracket off. -->
              <ChoiceField
                label={copy.clock}
                value={schedule.clock}
                options={CLOCKS}
                onchange={(value) => (schedule.clock = value)}
              />
              {#if schedule.type === 'one_time'}
                {@render numberBox({
                  label: copy.offset,
                  path: `${tPath}.schedule.offset_minutes`,
                  issueHost: [`${tPath}.schedule`],
                  value: schedule.offset_minutes,
                  min: 0,
                  max: 525_599,
                  apply: (value) => (schedule.offset_minutes = value)
                })}
              {:else}
                {@render numberBox({
                  label: copy.offset,
                  path: `${tPath}.schedule.start_offset_minutes`,
                  issueHost: [`${tPath}.schedule`],
                  value: schedule.start_offset_minutes,
                  min: 0,
                  max: 525_599,
                  apply: (value) => (schedule.start_offset_minutes = value)
                })}
                {@render numberBox({
                  label: copy.interval,
                  path: `${tPath}.schedule.interval_minutes`,
                  value: schedule.interval_minutes,
                  min: 1,
                  max: 525_600,
                  apply: (value) => (schedule.interval_minutes = value)
                })}
              {/if}
            {/if}
          </div>
        {/each}

        <Button
          variant="quiet"
          icon="plus"
          label={copy.addTrigger}
          onclick={() => addTrigger(interventionIndex)}
        />
      </div>
    </Panel>
  {/each}

  <!-- A survey with nothing delivering it is still a survey on screen, so the empty line is about
       the section rather than about the interventions array alone. -->
  {#if configuration.interventions.length === 0 && configuration.surveys.length === 0}
    <Note icon="info" tone="plain" text={copy.empty} />
  {/if}

  <div class="row">
    <Button
      variant="quiet"
      icon="plus"
      label={copy.addNotification}
      testid="intervention-add"
      onclick={addNotification}
    />
    <Button
      variant="quiet"
      icon="plus"
      label={copy.addSurvey}
      testid="survey-add"
      onclick={addSurvey}
    />
  </div>
</div>

<!-- Both halves of what is about to go, each named: the survey in the title, and under it the
     interventions that deliver it and cannot outlive it. A bare list of ids said neither. -->
<ConfirmDialog
  open={cascading !== null}
  title={doomed ? `${m.control.remove} · ${copy.survey} · ${doomed}` : m.control.remove}
  body={`${m.researcher.study.section.interventions.title} · ${cascade.join(' · ')}`}
  confirmLabel={m.action.confirm}
  cancelLabel={m.action.cancel}
  onconfirm={() => {
    const index = cascading;
    const from = cascadeFrom;
    cascading = null;
    if (index !== null) void removeBlock(from, '.panel', () => draft.removeSurvey(index), 'survey-add');
  }}
  oncancel={() => (cascading = null)}
/>

<style>
  /* A question and a schedule are parts of the card they sit in, not cards of their own. The sunk
     surface is what gives the fields inside them an edge: `.input` is `--surface`, which against a
     panel is the same colour twice and a 1px hairline between them. Shape follows `.collector__body`
     and `.group` — the two inner regions the page CSS already draws. */
  .part {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
    padding: var(--sp-5);
    background: var(--surface-sunk);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
  }

  .part__head {
    display: flex;
    align-items: center;
    gap: var(--sp-4);
    min-block-size: var(--tap-min);
  }

  /* The block's own name, which is also what its remove button is named after. Monospace because it
     is an identifier, and `auto` because the button belongs at the far end of the row. */
  .part__name {
    margin-inline-end: auto;
    font-size: var(--type-fine);
    color: var(--ink-soft);
  }

  /* A select is as wide as the longest thing in it and no wider, and a clock is as wide as a time;
     `.input` is a block field. `align-self` is the half that does the work: `.field` is a column
     flex container, so its children stretch and `inline-size: auto` resolves to the full width of
     the field however it is written. */
  .pick,
  .clock {
    align-self: flex-start;
    inline-size: auto;
    max-inline-size: 100%;
  }
</style>
