<script lang="ts">
  import type { OccurrenceAutomation } from '$lib/particeps/types';
  import IdField from '$lib/ui/IdField.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import Select from './EditorSelectField.svelte';
  import NumberField from './AutomationNumberField.svelte';
  import DurationClockField from './DurationClockField.svelte';
  import EvaluationClockField from './EvaluationClockField.svelte';
  import AutomationScheduleEditor from './AutomationScheduleEditor.svelte';
  import EventMatcherEditor from './EventMatcherEditor.svelte';
  import MatcherListEditor from './MatcherListEditor.svelte';
  import StateConditionEditor from './StateConditionEditor.svelte';
  import WindowThresholdEditor from './WindowThresholdEditor.svelte';
  import { createAutomationTrigger } from './editor-model';
  let { value, path, locale, durationHours, onrename }: { value: OccurrenceAutomation; path: string; locale: 'en' | 'zh-TW'; durationHours: number; onrename: (next: string) => void } = $props();
  const zh = $derived(locale === 'zh-TW');
</script>
<div class="stack" data-issue-host={path}>
  <IdField label={zh ? '觸發規則 ID' : 'Occurrence rule ID'} path={`${path}.id`} value={value.id} onchange={onrename} />
  <Select label={zh ? '觸發類型' : 'Trigger type'} path={`${path}.trigger.type`} value={value.trigger.type} hint={zh ? '更換類型會重設這一條觸發條件；其他規則保留。' : 'Changing type resets this trigger; other rules are preserved.'} options={[
    { value: 'schedule', label: zh ? '排程' : 'Schedule' }, { value: 'event_match', label: zh ? '事件比對' : 'Event match' },
    { value: 'condition_rising_edge', label: zh ? '條件由不成立變為成立' : 'Condition becomes true' },
    { value: 'sequence', label: zh ? '事件順序' : 'Event sequence' }, { value: 'window_threshold', label: zh ? '移動視窗門檻' : 'Rolling window threshold' }
  ]} onchange={(type) => value.trigger = createAutomationTrigger(type)} />
  {#if value.trigger.type === 'schedule'}
    <AutomationScheduleEditor value={value.trigger.schedule} path={`${path}.trigger.schedule`} {locale} {durationHours} onchange={(next) => { if (value.trigger.type === 'schedule') value.trigger.schedule = next; }} />
  {:else if value.trigger.type === 'event_match'}
    {@const trigger = value.trigger}
    <EventMatcherEditor value={trigger.selector} path={`${path}.trigger.selector`} {locale} onchange={(next) => trigger.selector = next} />
    <EvaluationClockField value={trigger.evaluation_clock} matchers={[trigger.selector]} path={`${path}.trigger.evaluation_clock`} {locale} onchange={(next) => trigger.evaluation_clock = next} />
  {:else if value.trigger.type === 'sequence'}
    {@const trigger = value.trigger}
    <MatcherListEditor value={trigger.steps} path={`${path}.trigger.steps`} label={zh ? '依序事件' : 'Sequence step'} {locale} kind="SEQUENCE_STEP" ordered min={2} />
    <NumberField label={zh ? '必須在幾秒內完成' : 'Complete within (seconds)'} path={`${path}.trigger.within_seconds`} value={trigger.within_seconds} min={1} max={604_800} onchange={(next) => trigger.within_seconds = next} />
    <EvaluationClockField value={trigger.evaluation_clock} matchers={trigger.steps} path={`${path}.trigger.evaluation_clock`} {locale} onchange={(next) => trigger.evaluation_clock = next} />
  {:else if value.trigger.type === 'window_threshold'}
    <WindowThresholdEditor value={value.trigger} path={`${path}.trigger`} {locale} />
  {:else}
    {@const trigger = value.trigger}
    <StateConditionEditor value={trigger.condition} path={`${path}.trigger.condition`} {locale} {durationHours} onchange={(next) => trigger.condition = next} />
  {/if}
  <ToggleField label={zh ? '只在額外條件成立時啟用（guard）' : 'Require an additional guard condition'} path={`${path}.guard`} value={value.guard !== null} onchange={(enabled) => value.guard = enabled ? { type: 'study_session_active' } : null} />
  {#if value.guard}<StateConditionEditor value={value.guard} path={`${path}.guard`} {locale} {durationHours} onchange={(next) => value.guard = next} />{/if}
  <div class="columns">
    <NumberField label={zh ? '活動可使用時間（秒）' : 'Activity availability (seconds)'} path={`${path}.availability_seconds`} value={value.availability_seconds} min={1} max={31_536_000} onchange={(next) => value.availability_seconds = next} />
    <NumberField label={zh ? '這條規則最多觸發次數' : 'Maximum activations for this rule'} path={`${path}.maximum_activations`} value={value.maximum_activations} min={1} max={512} onchange={(next) => value.maximum_activations = next} />
  </div>
  <ToggleField label={zh ? '設定觸發後的冷卻時間' : 'Set a cooldown after activation'} path={`${path}.cooldown`} value={value.cooldown !== null} onchange={(enabled) => value.cooldown = enabled ? { duration_seconds: 60, clock: 'ACTIVE_RUNNING_TIME' } : null} />
  {#if value.cooldown}
    {@const cooldown = value.cooldown}
    <NumberField label={zh ? '冷卻時間（秒）' : 'Cooldown (seconds)'} path={`${path}.cooldown.duration_seconds`} value={cooldown.duration_seconds} min={1} max={31_536_000} onchange={(next) => cooldown.duration_seconds = next} />
    <DurationClockField value={cooldown.clock} path={`${path}.cooldown.clock`} {locale} onchange={(next) => cooldown.clock = next} />
  {/if}
</div>
<style>
  .columns { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr)); gap: var(--sp-4); }
</style>
