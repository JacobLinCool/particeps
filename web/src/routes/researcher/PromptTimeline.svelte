<script lang="ts">
  /**
   * Prompts on a log axis running to the study's own duration.
   *
   * Relative order is then visible without reordering controls and without reading three numbers,
   * which is what a list of `delay_minutes` values costs. A prompt past the end of the axis is
   * `--caution`: legal, and it will never fire for a participant who finishes on time.
   */
  import { logPosition } from '$lib/ui/format';
  import type { PromptConfig } from '$lib/adc/types';

  interface Props {
    prompts: readonly PromptConfig[];
    durationHours: number;
    label: string;
  }

  let { prompts, durationHours, label }: Props = $props();

  const span = $derived(Math.max(60, durationHours * 60));
</script>

<div class="prompt__timeline" role="img" aria-label={label}>
  <div class="prompt__axis"></div>
  {#each prompts as prompt, index (index)}
    {@const beyond = prompt.delay_minutes > span}
    <span
      class="prompt__tick"
      data-beyond={beyond}
      style="inset-inline-start: {Math.min(100, logPosition(prompt.delay_minutes, 0, span) * 100)}%"
    ></span>
  {/each}
</div>
