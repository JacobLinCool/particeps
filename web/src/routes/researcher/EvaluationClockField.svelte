<script lang="ts">
  import type { EvaluationClock, EventMatcher } from '$lib/particeps/types';
  import { eventContract } from '$lib/particeps/registry';
  import Select from './EditorSelectField.svelte';
  let { value, matchers, path, locale, onchange }: { value: EvaluationClock; matchers: EventMatcher[]; path: string; locale: 'en' | 'zh-TW'; onchange: (value: EvaluationClock) => void } = $props();
  const clocks = $derived((['OBSERVED_RESEARCH_TIME', 'PRIMARY_SOURCE_TIME'] as const).filter((clock) => matchers.every((matcher) => eventContract(matcher.event)?.event.clock.automation_time_inputs.includes(clock))));
</script>
<Select label={locale === 'zh-TW' ? '事件時間基準' : 'Event evaluation clock'} {path} {value} {onchange} options={clocks.map((clock) => ({ value: clock, label: clock === 'OBSERVED_RESEARCH_TIME' ? (locale === 'zh-TW' ? '研究觀察時間' : 'Observed research time') : (locale === 'zh-TW' ? '來源原始時間' : 'Primary source time') }))} />
