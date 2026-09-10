<script lang="ts">
  import type { AutomationTrigger, StateCondition } from '$lib/particeps/types';
  import { eventContract } from '$lib/particeps/registry';
  import TextField from '$lib/ui/TextField.svelte';
  import Select from './EditorSelectField.svelte';
  import NumberField from './AutomationNumberField.svelte';
  import EventMatcherEditor from './EventMatcherEditor.svelte';
  import EvaluationClockField from './EvaluationClockField.svelte';
  let { value, path, locale }: { value: Extract<AutomationTrigger | StateCondition, { type: 'window_threshold' }>; path: string; locale: 'en' | 'zh-TW' } = $props();
  const zh = $derived(locale === 'zh-TW');
  const sumFields = $derived(Object.entries(eventContract(value.selector.event)?.event.fields ?? {}).filter(([, field]) => field.required && field.window_sum && ['int32', 'int64_decimal', 'uint64_decimal'].includes(field.wire_type)).map(([name]) => ({ value: name, label: name })));
</script>
<div class="stack">
  <Select label={zh ? '累計方式' : 'Aggregate'} path={`${path}.aggregate.type`} value={value.aggregate.type} options={[{ value: 'count', label: zh ? '事件次數' : 'Event count' }, { value: 'sum', label: zh ? '數值加總' : 'Field sum' }]} onchange={(type) => value.aggregate = type === 'count' ? { type } : { type, field: sumFields[0]?.value ?? '' }} />
  <EventMatcherEditor value={value.selector} path={`${path}.selector`} {locale} kind={value.aggregate.type === 'sum' ? 'WINDOW_SUM' : 'WINDOW_COUNT'} onchange={(next) => value.selector = next} />
  {#if value.aggregate.type === 'sum'}
    {@const aggregate = value.aggregate}
    <Select label={zh ? '加總欄位' : 'Summed field'} path={`${path}.aggregate.field`} value={aggregate.field} options={sumFields} onchange={(field) => aggregate.field = field} />
  {/if}
  <NumberField label={zh ? '移動視窗（秒）' : 'Rolling window (seconds)'} path={`${path}.window_seconds`} value={value.window_seconds} min={1} max={604_800} onchange={(next) => value.window_seconds = next} />
  <EvaluationClockField value={value.evaluation_clock} matchers={[value.selector]} path={`${path}.evaluation_clock`} {locale} onchange={(next) => value.evaluation_clock = next} />
  <Select label={zh ? '門檻比較' : 'Threshold comparison'} path={`${path}.comparison.operator`} value={value.comparison.operator} options={[
    { value: 'eq', label: '=' }, { value: 'ne', label: '≠' }, { value: 'lt', label: '<' }, { value: 'lte', label: '≤' }, { value: 'gt', label: '>' }, { value: 'gte', label: '≥' }
  ]} onchange={(next) => value.comparison.operator = next} />
  <TextField label={zh ? '整數門檻值' : 'Integer threshold'} path={`${path}.comparison.value`} value={value.comparison.value} max={128} mono onchange={(next) => value.comparison.value = next} />
</div>
