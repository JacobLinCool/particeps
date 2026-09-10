<script lang="ts">
  import type { DurationClock } from '$lib/particeps/types';
  import Select from './EditorSelectField.svelte';
  let { value, path, locale, onchange }: { value: DurationClock; path: string; locale: 'en' | 'zh-TW'; onchange: (value: DurationClock) => void } = $props();
</script>
<Select label={locale === 'zh-TW' ? '時間基準' : 'Duration clock'} {path} {value} {onchange} options={[
  { value: 'ACTIVE_RUNNING_TIME', label: locale === 'zh-TW' ? '實際研究進行時間（暫停時停止計時）' : 'Active running time (excludes pauses)' },
  { value: 'CALENDAR_TIME', label: locale === 'zh-TW' ? '經過的日曆時間（包含暫停）' : 'Calendar time (includes pauses)' }
]} />
