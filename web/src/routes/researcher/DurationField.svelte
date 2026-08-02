<script lang="ts">
  /**
   * How long one participant runs: the five spans a study is actually designed in, and a number box
   * for everything else.
   *
   * This was a log range across 1 to 8 760 hours, which put a day, three days and a week inside one
   * thumb width and made the common answer the hardest one to hit. The choices are shorter to read
   * than the track was, and they are the fastest keyboard path to any of them.
   *
   * The chip labels come from `units.hours`, which is `CollectorSummary.kt`'s humaniser — so this
   * control adds no string to either catalogue, and a chip says `7 days` in the words the
   * participant's own screen would use.
   */
  import Field from '$lib/ui/Field.svelte';
  import { fieldSource } from '$lib/ui/field-context';

  interface Props {
    label: string;
    value: number;
    min: number;
    max: number;
    /** `units.hours`. What the participant will be shown, not the number that was typed. */
    format: (value: number) => string;
    path?: string;
    hint?: string;
    presets?: readonly number[];
    onchange: (value: number) => void;
  }

  let { label, value, min, max, format, path, hint, presets, onchange }: Props = $props();

  const source = fieldSource();

  /** Never rewritten mid-keystroke: deleting a digit to type another would fight the clamp. */
  let draft = $state<string | null>(null);

  function commit() {
    if (draft !== null) {
      const parsed = Number(draft);
      if (Number.isFinite(parsed)) {
        onchange(Math.min(max, Math.max(min, Math.round(parsed))));
      }
      draft = null;
    }
    if (path) source.touch?.(path);
  }
</script>

<Field {label} {path} {hint} icon="person">
  {#snippet children({ id, describedby, invalid })}
    <div class="duration">
      <div class="duration__readout">
        <input
          class="input input--num"
          type="number"
          inputmode="numeric"
          {id}
          {min}
          {max}
          step="1"
          aria-describedby={describedby}
          aria-invalid={invalid || undefined}
          value={draft ?? String(value)}
          oninput={(event) => {
            draft = event.currentTarget.value;
          }}
          onblur={commit}
        />
        <span class="duration__human">{format(value)}</span>
      </div>

      {#if presets?.length}
        <!-- The preset row from `controls.css`, unchanged. A preset row is the same object here,
             and a second copy of it under another name would drift from the first. -->
        <div class="range__presets">
          {#each presets as preset (preset)}
            <button
              class="range__preset"
              type="button"
              aria-pressed={preset === value}
              onclick={() => onchange(preset)}
              data-testid={path ? `preset-${path}-${preset}` : undefined}
            >
              {format(preset)}
            </button>
          {/each}
        </div>
      {/if}
    </div>
  {/snippet}
</Field>

<style>
  .duration {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
  }

  .duration__readout {
    display: flex;
    align-items: baseline;
    gap: var(--sp-4);
  }

  .duration__human {
    font-size: var(--type-fine);
    color: var(--ink-faint);
  }
</style>
