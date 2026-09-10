<script lang="ts">
  import type { StateCondition } from '$lib/particeps/types';
  import { eventContract } from '$lib/particeps/registry';
  import Button from '$lib/ui/Button.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import Select from './EditorSelectField.svelte';
  import TimeField from './EditorTimeField.svelte';
  import NumberField from './AutomationNumberField.svelte';
  import DurationClockField from './DurationClockField.svelte';
  import MatcherListEditor from './MatcherListEditor.svelte';
  import WindowThresholdEditor from './WindowThresholdEditor.svelte';
  import StateConditionEditor from './StateConditionEditor.svelte';
  import { createStateCondition, moveEditorItem } from './editor-model';
  let { value, path, locale, durationHours, depth = 1, onchange }: { value: StateCondition; path: string; locale: 'en' | 'zh-TW'; durationHours: number; depth?: number; onchange: (value: StateCondition) => void } = $props();
  const zh = $derived(locale === 'zh-TW');
  const types = $derived([
    { value: 'study_session_active' as const, label: zh ? '研究進行中' : 'Study session is active' },
    { value: 'study_local_window' as const, label: zh ? '研究日與當地時段' : 'Study days and local time' },
    { value: 'elapsed_at_least' as const, label: zh ? '已經過一段時間' : 'Elapsed time' },
    { value: 'event_latch' as const, label: zh ? '事件設定／重設狀態' : 'Event latch (set / reset)' },
    { value: 'keyed_presence' as const, label: zh ? '依識別值配對進入／離開' : 'Keyed presence (enter / exit)' },
    { value: 'held_for' as const, label: zh ? '條件持續成立一段時間' : 'Condition held for a duration' },
    { value: 'window_threshold' as const, label: zh ? '移動視窗門檻' : 'Rolling window threshold' },
    { value: 'all' as const, label: zh ? '全部條件成立（AND）' : 'All conditions (AND)' },
    { value: 'any' as const, label: zh ? '任一條件成立（OR）' : 'Any condition (OR)' },
    { value: 'not' as const, label: zh ? '條件不成立（NOT）' : 'Condition is false (NOT)' }
  ].filter((option) => depth < 8 || !['held_for', 'all', 'any', 'not'].includes(option.value)));
  const keyFields = $derived.by(() => {
    if (value.type !== 'keyed_presence') return [];
    const matchers = [...value.enter_when, ...value.exit_when];
    const fields = Object.entries(eventContract(matchers[0]?.event)?.event.fields ?? {});
    return fields.filter(([name, field]) => field.required && field.keyed_presence_key && matchers.every((matcher) => {
      const candidate = eventContract(matcher.event)?.event.fields[name];
      return candidate?.required && candidate.keyed_presence_key && candidate.wire_type === field.wire_type;
    })).map(([name]) => ({ value: name, label: name }));
  });
</script>
<div class="condition stack" data-issue-host={path}>
  <Select label={zh ? '條件類型' : 'Condition type'} path={`${path}.type`} value={value.type} options={types} hint={zh ? '更換類型會重設這一個條件的內容。' : 'Changing type resets this condition.'} onchange={(type) => onchange(createStateCondition(type))} />
  {#if value.type === 'study_local_window'}
    <div class="columns">
      <NumberField label={zh ? '起始研究日' : 'First study day'} path={`${path}.first_day`} value={value.first_day} min={1} max={366} onchange={(next) => value.first_day = next} />
      <NumberField label={zh ? '最後研究日' : 'Last study day'} path={`${path}.last_day`} value={value.last_day} min={value.first_day} max={366} onchange={(next) => value.last_day = next} />
      <TimeField label={zh ? '當地開始時間' : 'Local start time'} path={`${path}.start_local_time`} value={value.start_local_time} onchange={(next) => value.start_local_time = next} />
      <TimeField label={zh ? '當地結束時間（不含）' : 'Local end time (exclusive)'} path={`${path}.end_local_time`} value={value.end_local_time} onchange={(next) => value.end_local_time = next} />
    </div>
  {:else if value.type === 'elapsed_at_least' || value.type === 'held_for'}
    <NumberField label={zh ? '持續時間（秒）' : 'Duration (seconds)'} path={`${path}.duration_seconds`} value={value.duration_seconds} min={1} max={durationHours * 3_600} onchange={(next) => value.duration_seconds = next} />
    <DurationClockField value={value.clock} path={`${path}.clock`} {locale} onchange={(next) => value.clock = next} />
    {#if value.type === 'held_for'}
      <div class="child"><StateConditionEditor value={value.condition} path={`${path}.condition`} {locale} {durationHours} depth={depth + 1} onchange={(next) => value.condition = next} /></div>
    {/if}
  {:else if value.type === 'event_latch'}
    <MatcherListEditor value={value.set_when} path={`${path}.set_when`} label={zh ? '使狀態成立的事件' : 'Set when'} {locale} />
    <MatcherListEditor value={value.reset_when} path={`${path}.reset_when`} label={zh ? '使狀態重設的事件' : 'Reset when'} {locale} />
  {:else if value.type === 'keyed_presence'}
    <MatcherListEditor value={value.enter_when} path={`${path}.enter_when`} label={zh ? '進入事件' : 'Enter when'} {locale} kind="KEYED_PRESENCE_ENTER" />
    <MatcherListEditor value={value.exit_when} path={`${path}.exit_when`} label={zh ? '離開事件' : 'Exit when'} {locale} kind="KEYED_PRESENCE_EXIT" />
    <Select label={zh ? '配對識別欄位' : 'Presence key field'} path={`${path}.key_field`} value={value.key_field} options={keyFields} onchange={(next) => value.key_field = next} />
  {:else if value.type === 'window_threshold'}
    <WindowThresholdEditor {value} {path} {locale} />
  {:else if value.type === 'all' || value.type === 'any'}
    <div class="stack" data-issue-host={`${path}.conditions`}>
      {#each value.conditions as condition, index (condition)}
        <div class="child">
          <div class="row">
            <strong>{zh ? '子條件' : 'Child condition'} {index + 1}</strong>
            <IconButton icon="chevron" label={zh ? '條件往上移' : 'Move condition up'} disabled={index === 0} onclick={() => moveEditorItem(value.conditions, index, -1)} />
            <IconButton icon="chevron-down" label={zh ? '條件往下移' : 'Move condition down'} disabled={index === value.conditions.length - 1} onclick={() => moveEditorItem(value.conditions, index, 1)} />
            <IconButton icon="trash" label={zh ? '移除子條件' : 'Remove child condition'} variant="danger" disabled={value.conditions.length <= 2} onclick={() => value.conditions.splice(index, 1)} />
          </div>
          <StateConditionEditor value={condition} path={`${path}.conditions.${index}`} {locale} {durationHours} depth={depth + 1} onchange={(next) => value.conditions[index] = next} />
        </div>
      {/each}
      <Button label={zh ? '新增子條件' : 'Add child condition'} icon="plus" variant="ghost" disabled={value.conditions.length >= 8 || depth >= 8} onclick={() => value.conditions.push({ type: 'study_session_active' })} />
    </div>
  {:else if value.type === 'not'}
    <div class="child"><StateConditionEditor value={value.condition} path={`${path}.condition`} {locale} {durationHours} depth={depth + 1} onchange={(next) => value.condition = next} /></div>
  {/if}
</div>
<style>
  .condition { min-inline-size: 0; }
  .columns { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 12rem), 1fr)); gap: var(--sp-4); }
  .child { display: grid; gap: var(--sp-4); border-inline-start: var(--line-hair) solid var(--rule); padding-inline-start: var(--sp-4); }
  @media (max-width: 40rem) { .child { padding-inline-start: var(--sp-2); } }
</style>
