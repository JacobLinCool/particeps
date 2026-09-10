<script lang="ts">
  import type { EventMatcher, FieldOperator, FieldPredicate } from '$lib/particeps/types';
  import type { ConditionKind } from '$lib/particeps/generated/event-source-registry';
  import { RESEARCHER_EVENTS, eventContract } from '$lib/particeps/registry';
  import Button from '$lib/ui/Button.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import Select from './EditorSelectField.svelte';
  import PredicateValueField from './PredicateValueField.svelte';
  import { createFieldPredicate, eventIdentityKey } from './editor-model';
  let { value, path, locale, kind = 'EVENT_MATCH', onchange }: { value: EventMatcher; path: string; locale: 'en' | 'zh-TW'; kind?: ConditionKind; onchange: (value: EventMatcher) => void } = $props();
  const zh = $derived(locale === 'zh-TW');
  const contract = $derived(eventContract(value.event));
  const events = $derived(RESEARCHER_EVENTS.filter(({ event }) => (event.trigger.condition_kinds as readonly ConditionKind[]).includes(kind)));
  const fields = $derived(Object.entries(contract?.event.fields ?? {}).filter(([, field]) => field.operators.length > 0));
  const available = $derived(fields.filter(([name]) => !value.predicates.some((predicate) => predicate.field === name)));
  const operatorLabels = $derived<Record<FieldOperator, string>>({ eq: zh ? '等於' : 'equals', ne: zh ? '不等於' : 'does not equal', lt: zh ? '小於' : 'less than', lte: zh ? '小於或等於' : 'at most', gt: zh ? '大於' : 'greater than', gte: zh ? '大於或等於' : 'at least', in: zh ? '在以下值之中' : 'is one of' });
  function selectEvent(key: string) {
    const selected = events.find(({ source, event }) => `${source.source_id}:${source.schema_version}:${event.event_type}` === key);
    if (selected) onchange({ event: { source_id: selected.source.source_id, schema_version: selected.source.schema_version, event_type: selected.event.event_type }, predicates: [] });
  }
  function changeOperator(predicate: FieldPredicate, index: number, operator: FieldOperator) {
    const first = predicate.operator === 'in' ? predicate.values[0] ?? '' : predicate.value;
    value.predicates[index] = operator === 'in' ? { field: predicate.field, operator, values: [first] } : { field: predicate.field, operator, value: first };
  }
</script>
<div class="matcher stack" data-issue-host={path}>
  <Select label={zh ? '事件' : 'Event'} path={`${path}.event`} value={eventIdentityKey(value)} options={events.map(({ source, event }) => ({ value: `${source.source_id}:${source.schema_version}:${event.event_type}`, label: `${source.source_id} · ${event.event_type}` }))} hint={zh ? '更換事件會清除這個事件的欄位篩選。' : 'Changing the event clears its field predicates.'} onchange={selectEvent} />
  {#if contract?.source.source_kind === 'COLLECTOR'}
    <p class="fine faint">{zh ? `事件來源 ${contract.source.source_id} 必須設為必要，並在整個研究進行期間啟用。` : `Event source ${contract.source.source_id} must be required and remain active throughout the study.`}{contract.source.source_id === 'usage_events.v1' ? (zh ? ' 每個設定檔的輪詢間隔必須為 15 秒。' : ' Every profile must poll every 15 seconds.') : ''}</p>
  {/if}
  <div class="stack" data-issue-host={`${path}.predicates`}>
    {#each value.predicates as predicate, index (predicate)}
      {@const predicatePath = `${path}.predicates.${index}`}
      {@const field = contract?.event.fields[predicate.field]}
      <div class="predicate" data-issue-host={predicatePath}>
        <div class="predicate__head">
          <Select label={zh ? '事件欄位' : 'Event field'} path={`${predicatePath}.field`} value={predicate.field} options={fields.filter(([name]) => name === predicate.field || !value.predicates.some((entry) => entry.field === name)).map(([name]) => ({ value: name, label: name }))} onchange={(name) => value.predicates[index] = createFieldPredicate(name, value)} />
          <Select label={zh ? '比較方式' : 'Operator'} path={`${predicatePath}.operator`} value={predicate.operator} options={(field?.operators ?? []).map((operator) => ({ value: operator, label: operatorLabels[operator] }))} onchange={(operator) => changeOperator(predicate, index, operator)} />
          <IconButton icon="trash" label={zh ? '移除欄位篩選' : 'Remove field predicate'} variant="danger" onclick={() => value.predicates.splice(index, 1)} />
        </div>
        {#if field}
          <p class="fine faint">{field.meaning}</p>
          {#if predicate.operator === 'in'}
            <div class="stack" data-issue-host={`${predicatePath}.values`}>
              {#each predicate.values as literal, literalIndex}
                <div class="literal">
                  <PredicateValueField {field} {locale} path={`${predicatePath}.values.${literalIndex}`} value={literal} onchange={(next) => predicate.values[literalIndex] = next} />
                  <IconButton icon="trash" label={zh ? '移除比較值' : 'Remove value'} variant="danger" onclick={() => predicate.values.splice(literalIndex, 1)} />
                </div>
              {/each}
              <div class="row">
                <Button label={zh ? '新增比較值' : 'Add value'} icon="plus" variant="ghost" disabled={predicate.values.length >= 64} onclick={() => predicate.values.push('')} />
                <Button label={zh ? '依字典順序排列值' : 'Sort values lexically'} variant="ghost" onclick={() => predicate.values.sort()} />
              </div>
            </div>
          {:else}
            <PredicateValueField {field} {locale} path={`${predicatePath}.value`} value={predicate.value} onchange={(next) => predicate.value = next} />
          {/if}
        {:else}
          <TextField label={zh ? '未識別欄位的原始值' : 'Unrecognized field value'} path={`${predicatePath}.value`} value={predicate.operator === 'in' ? predicate.values.join('\n') : predicate.value} max={4_096} onchange={(next) => { if (predicate.operator === 'in') predicate.values = next.split('\n'); else predicate.value = next; }} />
        {/if}
      </div>
    {/each}
    <Button label={zh ? '新增欄位篩選' : 'Add field predicate'} icon="plus" variant="ghost" disabled={available.length === 0 || value.predicates.length >= 16} onclick={() => value.predicates.push(createFieldPredicate(available[0][0], value))} />
  </div>
</div>
<style>
  .matcher { min-inline-size: 0; }
  .predicate { display: grid; gap: var(--sp-3); padding-block-start: var(--sp-4); border-block-start: var(--line-hair) solid var(--rule); }
  .predicate__head { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: var(--sp-3); align-items: end; }
  .literal { display: flex; gap: var(--sp-3); align-items: end; }
  .literal > :global(.field) { flex: 1; min-inline-size: 0; }
  @media (max-width: 40rem) { .predicate__head { grid-template-columns: 1fr; } }
</style>
