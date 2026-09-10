<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import Field from '$lib/ui/Field.svelte';
  import Note from '$lib/ui/Note.svelte';
  import { AutomationCompilationError } from '$lib/particeps/automation/compiler';
  import { RESEARCHER_EVENTS } from '$lib/particeps/registry';
  import type { RegistryEventContract, RegistrySourceContract } from '$lib/particeps/generated/event-source-registry';
  import {
    simulate, parseSyntheticTrace, defaultSyntheticFieldValue as sampleValue, SimulationTraceError, SIMULATION_REFERENCE,
    MAXIMUM_TRACE_SECONDS, MAXIMUM_TRACE_EVENTS, type SimulationResult, type SyntheticTrace, type TraceIssueCode
  } from '$lib/particeps/simulator';
  import type { Draft } from './draft.svelte';
  import AutomationNumberField from './AutomationNumberField.svelte';

  let { draft, locale = 'en' }: { draft: Draft; locale?: 'en' | 'zh-TW' } = $props();
  const zh = $derived(locale === 'zh-TW');
  const copy = $derived(zh ? {
    lead: '用合成情境檢查自動化。這是受控測試，結果不代表參與者實際行為。',
    clock: `固定起點：${SIMULATION_REFERENCE.startUtc}；時區：UTC。事件時間為相對起點的秒數。`,
    assumptions: '本工具在線性插值的活動／日曆時間座標間推進，不重播真實暫停或重新啟動。主要來源時間與觀察時間相同。隨機問卷不抽選時間，因此不會出現在觸發結果中。',
    presets: '建立沒有事件的新情境', five: '5 分鐘', hour: '1 小時', day: '24 小時',
    active: '情境結束：活動秒數', calendar: '情境結束：日曆秒數', event: '事件來源與類型',
    atActive: '事件發生：活動秒數', atCalendar: '事件發生：日曆秒數',
    add: '加入合成事件', remove: '移除', fields: '合成欄位值', optional: '選填；留白不傳入', required: '必要欄位',
    sample: '預填值只是測試樣本。請依研究條件調整；欄位使用 protocol 字串格式。',
    noSources: '啟用資料來源後，可加入對應的合成事件。', events: '情境中的事件', noEvents: '沒有合成事件；只評估固定排程與時間條件。',
    advanced: '進階：編輯事件 JSON', trace: '合成事件軌跡（JSON）', run: '執行情境',
    resources: '情境結束時的資源設定', actions: '觸發的活動', inactive: '停止', noActions: '這個情境沒有觸發活動。',
    stale: '研究設定或情境已修改。請重新執行，取得最新結果。', configError: '請先修正研究設定：', error: '無法執行情境；請檢查研究設定與事件資料。'
  } : {
    lead: 'Check automations with a synthetic scenario. This controlled test does not predict participant behavior.',
    clock: `Fixed start: ${SIMULATION_REFERENCE.startUtc}; time zone: UTC. Event coordinates are seconds after the start.`,
    assumptions: 'Active/calendar coordinates are interpolated linearly; actual pauses and restarts are not replayed. Primary source time equals observation time. Random questionnaire times are never sampled, so random schedules do not appear in matched actions.',
    presets: 'Start a new scenario with no events', five: '5 minutes', hour: '1 hour', day: '24 hours',
    active: 'Scenario end: active seconds', calendar: 'Scenario end: calendar seconds', event: 'Event source and type',
    atActive: 'Event at active seconds', atCalendar: 'Event at calendar seconds',
    add: 'Add synthetic event', remove: 'Remove', fields: 'Synthetic field values', optional: 'Optional; blank omits this field', required: 'Required field',
    sample: 'Prefilled values are test samples. Adjust them for the study conditions; fields use protocol wire-format strings.',
    noSources: 'Enable collectors to add their synthetic events.', events: 'Scenario events', noEvents: 'No synthetic events; only deterministic schedules and time conditions are evaluated.',
    advanced: 'Advanced: edit event JSON', trace: 'Synthetic event trace (JSON)', run: 'Run scenario',
    resources: 'Resource state at scenario end', actions: 'Matched activities', inactive: 'Inactive', noActions: 'No activity matched this scenario.',
    stale: 'The study or scenario changed. Run it again to see current results.', configError: 'Fix the study settings first: ', error: 'The scenario could not run. Check the study settings and event data.'
  });
  const issueCopy = $derived<Record<TraceIssueCode, string>>(zh ? {
    invalid_json: 'JSON 語法無效，或物件含重複欄位。', too_large: '資料超過大小限制（情境最多 1 MiB）。',
    object_shape: '物件欄位缺漏、未知，或型別不符。', clock_range: '秒數須為 0–31,536,000 的整數。',
    clock_order: '事件須依時間排序且在情境範圍內；活動時間不能比日曆時間流逝更快。', event_count: '事件須為陣列，最多 2,000 筆。',
    unknown_event: '此事件未在研究者可用的 registry 中定義。', unknown_field: '此事件沒有這個欄位。',
    field_value: '欄位值不符 registry 的型別、範圍或選項。', source_not_enabled: '這個事件的資料來源尚未在研究中啟用。',
    study_duration: '情境活動秒數不能超過研究持續時間。'
  } : {
    invalid_json: 'JSON is malformed or contains duplicate object fields.', too_large: 'Data exceeds its size limit (a scenario is limited to 1 MiB).',
    object_shape: 'An object has missing, unknown or incorrectly typed fields.', clock_range: 'Seconds must be integers from 0 to 31,536,000.',
    clock_order: 'Events must be ordered within the scenario; active time cannot advance faster than calendar time.', event_count: 'Events must be an array with at most 2,000 entries.',
    unknown_event: 'The researcher registry does not define this event.', unknown_field: 'This event does not define that field.',
    field_value: 'The field value violates the registry type, bounds or choices.', source_not_enabled: 'The study has not enabled this event source.',
    study_duration: 'Scenario active seconds cannot exceed the study duration.'
  });

  let input = $state(JSON.stringify({ active_seconds: 300, calendar_seconds: 300, events: [] }, null, 2));
  let completed = $state<{ document: string; input: string; result: SimulationResult } | null>(null);
  let failure = $state<{ document: string; input: string; error: unknown } | null>(null);
  let selectedEvent = $state('');
  let atActive = $state(60);
  let atCalendar = $state(60);
  let fieldValues = $state<Record<string, string>>({});
  const document = $derived(JSON.stringify(draft.document));
  const parsed = $derived.by(() => {
    try {
      const trace = parseSyntheticTrace(input, { validateSemantics: false });
      try { parseSyntheticTrace(input); return { trace, error: null }; }
      catch (error) { return { trace, error }; }
    }
    catch (error) { return { trace: null, error }; }
  });
  const available = $derived(RESEARCHER_EVENTS.filter(({ source }) => source.source_kind === 'COLLECTOR' &&
    draft.configuration.collectors.some((collector) => collector.id === source.source_id)) as { source: RegistrySourceContract; event: RegistryEventContract }[]);
  const selected = $derived(available.find(({ source, event }) => `${source.source_id}:${event.event_type}` === selectedEvent) ?? available[0]);
  const visibleResult = $derived(completed?.input === input && completed.document === document ? completed.result : null);
  const currentError = $derived(parsed.error ?? (failure?.input === input && failure.document === document ? failure.error : null));

  function displayError(error: unknown): string {
    if (error instanceof SimulationTraceError) return `${error.path}: ${issueCopy[error.code]}`;
    if (error instanceof AutomationCompilationError) return `${copy.configError}${error.issues.map((issue) => `${issue.path} (${issue.code})`).join('; ')}`;
    return copy.error;
  }

  function reset(seconds: number): void {
    input = JSON.stringify({ active_seconds: seconds, calendar_seconds: seconds, events: [] }, null, 2);
  }

  function updateTrace(patch: Partial<SyntheticTrace>): void {
    if (parsed.trace) input = JSON.stringify({ ...parsed.trace, ...patch }, null, 2);
  }

  function addEvent(): void {
    if (!selected || !parsed.trace) return;
    const fields = Object.fromEntries(Object.entries(selected.event.fields).flatMap(([name, field]) => {
      const value = fieldValues[name] ?? (field.required ? sampleValue(field) : '');
      return field.required || value !== '' ? [[name, value]] : [];
    }));
    const next: SyntheticTrace = { ...parsed.trace, events: [...parsed.trace.events, {
      source_id: selected.source.source_id, schema_version: selected.source.schema_version,
      event_type: selected.event.event_type, at_active_seconds: atActive, at_calendar_seconds: atCalendar, fields
    }] };
    try {
      parseSyntheticTrace(JSON.stringify(next));
      input = JSON.stringify(next, null, 2);
      failure = null;
    } catch (error) { failure = { document, input, error }; }
  }

  function run(): void {
    try {
      const result = simulate(draft.document, parseSyntheticTrace(input));
      completed = { document, input, result };
      failure = null;
    } catch (error) {
      completed = null;
      failure = { document, input, error };
    }
  }

</script>

<div class="simulator">
  <Note icon="info" tone="plain" text={copy.lead} />
  <div class="reference"><p>{copy.clock}</p><p class="fine faint">{copy.assumptions}</p></div>
  <div class="presets"><p class="fine">{copy.presets}</p><div class="row">
    <Button label={copy.five} onclick={() => reset(300)} />
    <Button label={copy.hour} onclick={() => reset(3_600)} />
    <Button label={copy.day} disabled={draft.configuration.duration_hours < 24} onclick={() => reset(86_400)} />
  </div></div>
  {#if parsed.trace}
    <div class="fields">
      <AutomationNumberField label={copy.active} value={parsed.trace.active_seconds} min={0} max={draft.configuration.duration_hours * 3_600} onchange={(active_seconds) => updateTrace({ active_seconds })} />
      <AutomationNumberField label={copy.calendar} value={parsed.trace.calendar_seconds} min={0} max={MAXIMUM_TRACE_SECONDS} onchange={(calendar_seconds) => updateTrace({ calendar_seconds })} />
    </div>
    <section class="event-builder">
      {#if selected}
        <Field label={copy.event}>
          {#snippet children({ id })}
            <select class="input" {id} value={`${selected.source.source_id}:${selected.event.event_type}`} onchange={(event) => { selectedEvent = event.currentTarget.value; fieldValues = {}; }}>
              {#each available as entry (`${entry.source.source_id}:${entry.event.event_type}`)}
                <option value={`${entry.source.source_id}:${entry.event.event_type}`}>{entry.source.source_id} · {entry.event.event_type}</option>
              {/each}
            </select>
          {/snippet}
        </Field>
        <div class="fields">
          <AutomationNumberField label={copy.atActive} value={atActive} min={0} max={parsed.trace.active_seconds} onchange={(value) => { atActive = value; }} />
          <AutomationNumberField label={copy.atCalendar} value={atCalendar} min={0} max={parsed.trace.calendar_seconds} onchange={(value) => { atCalendar = value; }} />
        </div>
        <details><summary>{copy.fields}</summary><p class="fine faint">{copy.sample}</p><div class="fields">
          {#each Object.entries(selected.event.fields) as [name, field] (name)}
            <Field label={name} hint={`${field.meaning} · ${field.unit} · ${field.required ? copy.required : copy.optional}`}>
              {#snippet children({ id, describedby })}
                {#if field.wire_type === 'enum' || field.wire_type === 'boolean'}
                  <select class="input" {id} aria-describedby={describedby} value={fieldValues[name] ?? (field.required ? sampleValue(field) : '')} onchange={(event) => { fieldValues[name] = event.currentTarget.value; }}>
                    {#if !field.required}<option value="">—</option>{/if}
                    {#each field.wire_type === 'boolean' ? ['false', 'true'] : field.enum_values as option (option)}<option value={option}>{option}</option>{/each}
                  </select>
                {:else}
                  <input class="input input--mono" {id} aria-describedby={describedby} value={fieldValues[name] ?? (field.required ? sampleValue(field) : '')} oninput={(event) => { fieldValues[name] = event.currentTarget.value; }} />
                {/if}
              {/snippet}
            </Field>
          {/each}
        </div></details>
        <div><Button label={copy.add} icon="plus" disabled={parsed.trace.events.length >= MAXIMUM_TRACE_EVENTS} onclick={addEvent} /></div>
      {:else}<p class="fine faint">{copy.noSources}</p>{/if}
    </section>
    <section class="event-list"><h4>{copy.events} · {parsed.trace.events.length}</h4>
      {#if parsed.trace.events.length === 0}<p class="fine faint">{copy.noEvents}</p>{/if}
      {#each parsed.trace.events as event, index (index)}
        <div class="event-row"><span><code>{event.event_type}</code> · {event.at_active_seconds}s / {event.at_calendar_seconds}s</span>
          <Button label={copy.remove} icon="trash" variant="ghost" onclick={() => updateTrace({ events: parsed.trace!.events.filter((_, item) => item !== index) })} />
        </div>
      {/each}
    </section>
  {/if}
  <details open={!!parsed.error}><summary>{copy.advanced}</summary>
    <Field label={copy.trace}>{#snippet children({ id })}<textarea class="input input--mono" {id} rows="12" spellcheck="false" bind:value={input}></textarea>{/snippet}</Field>
  </details>
  <div><Button label={copy.run} icon="arrow-right" variant="primary" disabled={!!parsed.error} onclick={run} /></div>
  {#if currentError}<Note icon="alert" tone="danger" text={displayError(currentError)} />{/if}
  {#if completed && !visibleResult}<Note icon="info" tone="plain" text={copy.stale} />{/if}
  {#if visibleResult}
    <div class="result" aria-live="polite">
      <section><h4>{copy.resources}</h4><ul>{#each visibleResult.resources as resource (`${resource.kind}:${resource.id}`)}<li><code>{resource.id}</code> → <code>{resource.profile_id ?? copy.inactive}</code></li>{/each}</ul></section>
      <section><h4>{copy.actions}</h4>
        {#if visibleResult.interventions.length === 0}<p class="fine faint">{copy.noActions}</p>{/if}
        <ul>{#each visibleResult.interventions as action, index (index)}<li><code>{action.intervention_id}</code> · {action.matched_at_seconds}s</li>{/each}</ul>
      </section>
    </div>
  {/if}
</div>

<style>
  .simulator, .event-builder, .event-list { display: grid; gap: var(--sp-5); }
  .reference p, .presets p, h4 { margin: 0; }
  .reference, .presets { display: grid; gap: var(--sp-3); }
  .row { display: flex; flex-wrap: wrap; gap: var(--sp-3); }
  .fields, .result { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr)); gap: var(--sp-5); }
  .event-builder { padding: var(--sp-5); border: 1px solid var(--line, #d9ddd8); border-radius: var(--radius-md, .5rem); }
  .event-row { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); }
  .event-row span { min-width: 0; overflow-wrap: anywhere; }
  details > summary { cursor: pointer; margin-bottom: var(--sp-4); }
  .result section { display: grid; align-content: start; gap: var(--sp-3); }
  .result h4, .event-list h4 { font-size: var(--type-body); }
  .result ul { margin: 0; padding-inline-start: var(--sp-6); }
</style>
