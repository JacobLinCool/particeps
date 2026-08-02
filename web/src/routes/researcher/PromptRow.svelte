<script lang="ts">
  /** One prompt. The delay spans the row because it is the field that decides where the tick on
   *  the timeline above lands, and the timeline is the point of having a row at all. */
  import IdField from '$lib/ui/IdField.svelte';
  import TextField from '$lib/ui/TextField.svelte';
  import RangeField from '$lib/ui/RangeField.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import { BOUNDS, type PromptConfig } from '$lib/adc/types';
  import { PRESETS } from './presets';
  import type { Messages } from '$lib/i18n/types';
  import type { Units } from './units';

  interface Props {
    prompt: PromptConfig;
    index: number;
    m: Messages;
    units: Units;
    onremove: () => void;
  }

  let { prompt, index, m, units, onremove }: Props = $props();

  const path = $derived(`prompts.${index}`);
</script>

<div class="prompt">
  <IdField
    label={m.field.label.promptId}
    path={`${path}.id`}
    value={prompt.id}
    onchange={(value) => (prompt.id = value)}
  />

  <TextField
    label={m.field.label.promptMessage}
    path={`${path}.message`}
    value={prompt.message}
    max={BOUNDS.promptMessage[1]}
    onchange={(value) => (prompt.message = value)}
  />

  <IconButton
    icon="cross"
    label={`${m.control.remove} ${prompt.id}`}
    variant="danger"
    onclick={onremove}
  />

  <div class="prompt__delay">
    <RangeField
      label={m.field.label.promptDelay}
      hint={m.field.hint.promptDelay}
      path={`${path}.delay_minutes`}
      value={prompt.delay_minutes}
      min={BOUNDS.promptDelayMinutes[0]}
      max={BOUNDS.promptDelayMinutes[1]}
      scale="log"
      icon="clock"
      format={units.minutes}
      presets={PRESETS.delay_minutes}
      onchange={(value) => (prompt.delay_minutes = value)}
    />
  </div>
</div>

<style>
  .prompt__delay {
    grid-column: 1 / -1;
  }
</style>
