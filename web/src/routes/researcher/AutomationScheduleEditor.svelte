<script lang="ts">
  import type { AutomationSchedule } from '$lib/particeps/types';
  import Button from '$lib/ui/Button.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import Select from './EditorSelectField.svelte';
  import NumberField from './AutomationNumberField.svelte';
  import TimeField from './EditorTimeField.svelte';
  import DurationClockField from './DurationClockField.svelte';
  import { nextRandomWindow } from './random-window';
  import { createAutomationSchedule, moveEditorItem } from './editor-model';
  let { value, path, locale, durationHours, onchange }: { value: AutomationSchedule; path: string; locale: 'en' | 'zh-TW'; durationHours: number; onchange: (value: AutomationSchedule) => void } = $props();
  const zh = $derived(locale === 'zh-TW');
  const nextWindow = $derived(value.type === 'random_window' ? nextRandomWindow(value) : null);
</script>
<div class="stack" data-issue-host={path}>
  <Select label={zh ? '排程類型' : 'Schedule type'} path={`${path}.type`} value={value.type} options={[
    { value: 'one_time', label: zh ? '一次' : 'One time' }, { value: 'interval', label: zh ? '固定間隔' : 'Fixed interval' },
    { value: 'daily_local', label: zh ? '每天當地時間' : 'Daily local time' }, { value: 'random_window', label: zh ? '隨機時窗' : 'Random windows' }
  ]} onchange={(type) => onchange(createAutomationSchedule(type))} />
  {#if value.type === 'one_time'}
    <NumberField label={zh ? '開始後幾分鐘' : 'Minutes after study start'} path={`${path}.offset_minutes`} value={value.offset_minutes} min={0} max={durationHours * 60 - 1} onchange={(next) => value.offset_minutes = next} />
    <DurationClockField value={value.clock} path={`${path}.clock`} {locale} onchange={(next) => value.clock = next} />
  {:else if value.type === 'interval'}
    <NumberField label={zh ? '首次於開始後幾分鐘' : 'First occurrence after study start (minutes)'} path={`${path}.start_offset_minutes`} value={value.start_offset_minutes} min={0} max={durationHours * 60 - 1} onchange={(next) => value.start_offset_minutes = next} />
    <NumberField label={zh ? '間隔（分鐘）' : 'Interval (minutes)'} path={`${path}.interval_minutes`} value={value.interval_minutes} min={1} max={525_600} onchange={(next) => value.interval_minutes = next} />
    <DurationClockField value={value.clock} path={`${path}.clock`} {locale} onchange={(next) => value.clock = next} />
  {:else if value.type === 'daily_local'}
    <TimeField label={zh ? '當地時間' : 'Local time'} path={`${path}.local_time`} value={value.local_time} onchange={(next) => value.local_time = next} />
  {:else}
    <p class="fine faint">{zh ? '每位參與者的實際時間由裝置抽選。這裡只設定允許的時窗與次數，不預先抽選或顯示個人時間。時窗須由早到晚且不能重疊。' : 'Each device draws the participant’s actual times. Configure allowed windows and limits here; no participant times are preselected or displayed. Order windows from early to late without overlap.'}</p>
    <div class="stack" data-issue-host={`${path}.local_windows`}>
      {#each value.local_windows as window, index (window)}
        <div class="window" data-issue-host={`${path}.local_windows.${index}`}>
          <div class="row">
            <strong>{zh ? '時窗' : 'Window'} {index + 1}</strong>
            <IconButton icon="chevron" label={zh ? '時窗往前移' : 'Move window earlier'} disabled={index === 0} onclick={() => moveEditorItem(value.local_windows, index, -1)} />
            <IconButton icon="chevron-down" label={zh ? '時窗往後移' : 'Move window later'} disabled={index === value.local_windows.length - 1} onclick={() => moveEditorItem(value.local_windows, index, 1)} />
            <IconButton icon="trash" label={zh ? '移除時窗' : 'Remove window'} variant="danger" disabled={value.local_windows.length <= 1} onclick={() => value.local_windows.splice(index, 1)} />
          </div>
          <div class="columns">
            <TimeField label={zh ? '當地開始時間' : 'Local start time'} path={`${path}.local_windows.${index}.start_local_time`} value={window.start_local_time} onchange={(next) => window.start_local_time = next} />
            <TimeField label={zh ? '當地結束時間（不含）' : 'Local end time (exclusive)'} path={`${path}.local_windows.${index}.end_local_time`} value={window.end_local_time} onchange={(next) => window.end_local_time = next} />
          </div>
        </div>
      {/each}
      <Button label={zh ? '新增隨機時窗' : 'Add random window'} icon="plus" variant="ghost" disabled={value.local_windows.length >= 8 || nextWindow === null} onclick={() => { if (nextWindow) value.local_windows.push(nextWindow); }} />
    </div>
    <div class="columns">
      <NumberField label={zh ? '每個時窗抽選次數' : 'Occurrences per window'} path={`${path}.occurrences_per_window`} value={value.occurrences_per_window} min={1} max={8} onchange={(next) => value.occurrences_per_window = next} />
      <NumberField label={zh ? '每日最多次數' : 'Maximum per day'} path={`${path}.maximum_occurrences_per_day`} value={value.maximum_occurrences_per_day} min={1} max={64} onchange={(next) => value.maximum_occurrences_per_day = next} />
      <NumberField label={zh ? '整個研究最多次數' : 'Maximum across study'} path={`${path}.maximum_occurrences_total`} value={value.maximum_occurrences_total} min={1} max={512} onchange={(next) => value.maximum_occurrences_total = next} />
      <NumberField label={zh ? '最小間隔（分鐘）' : 'Minimum separation (minutes)'} path={`${path}.minimum_separation_minutes`} value={value.minimum_separation_minutes} min={1} max={1_440} onchange={(next) => value.minimum_separation_minutes = next} />
    </div>
  {/if}
</div>
<style>
  .columns { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 14rem), 1fr)); gap: var(--sp-4); }
  .window { display: grid; gap: var(--sp-4); padding-block-start: var(--sp-4); border-block-start: var(--line-hair) solid var(--rule); }
</style>
