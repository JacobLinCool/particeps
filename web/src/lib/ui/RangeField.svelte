<script lang="ts">
  /**
   * The bounded value control, and the thing that keeps step 3 from being a form.
   *
   * The track spans the entire legal range, so the bounds are visible without being read. The
   * readout is editable, because some values are known exactly. The presets are real buttons
   * showing real values — they are what turns an unbounded number into the small set of choices
   * the decision is actually made from, and they are the fastest keyboard path.
   *
   * Two mappings, and the reason for each:
   *   log     a linear track crowds every usable value into one edge for ranges like 5 000 to
   *           1 000 000. Offset by 1 so a range including 0 still works.
   *   invert  `sampling_period_us` is a period, and the researcher is choosing a rate. Left to
   *           right must be 1 Hz to 200 Hz, which is right to left in microseconds. A rate
   *           control that runs backwards is a bug nobody notices until the pilot.
   *
   * Ticks are `aria-hidden` decoration; the preset buttons are the reachable affordance.
   */
  import Field from './Field.svelte';
  import { fieldSource } from './field-context';
  import { logPosition, logValue } from './format';
  import type { IconRef } from './icons';

  interface Props {
    label: string;
    value: number;
    min: number;
    max: number;
    /** Humanised, from `lib/researcher/units.ts`. What the participant will be shown. */
    format: (value: number) => string;
    path?: string;
    hint?: string;
    step?: number;
    scale?: 'linear' | 'log';
    invert?: boolean;
    presets?: readonly number[];
    /**
     * Whether the exact value is worth typing. True for a count in the unit a person thinks in — 24
     * hours, 5 minutes, 60 Hz. False where the stored unit is not that unit: a storage quota is
     * chosen as "1 GiB", and offering `1073741824` as the box to edit asks a researcher to do
     * arithmetic to say something the presets already say.
     */
    numericInput?: boolean;
    icon?: IconRef;
    /** Marks the readout --caution without raising an issue: legal, but worth a second look. */
    caution?: boolean;
    onchange: (value: number) => void;
  }

  let {
    label,
    value,
    min,
    max,
    format,
    path,
    hint,
    step = 1,
    scale = 'linear',
    invert = false,
    presets,
    icon,
    caution = false,
    onchange,
    numericInput = true
  }: Props = $props();

  const source = fieldSource();

  /** Position space is needed for both log and inversion; linear-and-upright uses the native
   *  value space, where arrow keys move by `step` and nothing has to be remapped. */
  const positional = $derived(scale === 'log' || invert);

  function quantise(raw: number): number {
    const snapped = Math.round(raw / step) * step;
    const clamped = Math.min(max, Math.max(min, snapped));
    return step < 1 ? Number(clamped.toFixed(String(step).split('.')[1]?.length ?? 1)) : clamped;
  }

  function positionOf(v: number): number {
    const raw = scale === 'log' ? logPosition(v, min, max) : (v - min) / (max - min || 1);
    return invert ? 1 - raw : raw;
  }

  function valueAt(position: number): number {
    const raw = invert ? 1 - position : position;
    return quantise(scale === 'log' ? logValue(raw, min, max) : min + raw * (max - min));
  }

  const fill = $derived(Math.min(1, Math.max(0, positionOf(value))));

  let draft = $state<string | null>(null);
  const outside = $derived(
    draft !== null && draft !== '' && (Number(draft) < min || Number(draft) > max)
  );

  function commitDraft() {
    if (draft !== null) {
      const parsed = Number(draft);
      if (Number.isFinite(parsed)) onchange(quantise(parsed));
      draft = null;
    }
    if (path) source.touch?.(path);
  }
</script>

<Field {label} {path} {hint} {icon}>
  {#snippet children({ id, describedby, invalid })}
    <div class="range">
      <div class="range__line">
        <div class="range__track">
          <div
            class="range__fill"
            data-level={caution || outside ? 'caution' : undefined}
            style="inset-inline-start: 0; inline-size: {fill * 100}%"
          ></div>

          {#if presets}
            <div class="range__ticks" aria-hidden="true">
              {#each presets as preset (preset)}
                <span class="range__tick" style="inset-inline-start: {positionOf(preset) * 100}%"
                ></span>
              {/each}
            </div>
          {/if}

          {#if positional}
            <input
              class="range__input"
              type="range"
              {id}
              min="0"
              max="1000"
              step="1"
              value={Math.round(fill * 1000)}
              aria-describedby={describedby}
              aria-invalid={invalid || undefined}
              aria-valuetext={format(value)}
              oninput={(event) => onchange(valueAt(Number(event.currentTarget.value) / 1000))}
              onchange={() => path && source.touch?.(path)}
            />
          {:else}
            <input
              class="range__input"
              type="range"
              {id}
              {min}
              {max}
              {step}
              {value}
              aria-describedby={describedby}
              aria-invalid={invalid || undefined}
              aria-valuetext={format(value)}
              oninput={(event) => onchange(quantise(Number(event.currentTarget.value)))}
              onchange={() => path && source.touch?.(path)}
            />
          {/if}
        </div>

        <div class="range__readout" class:range__readout--human={!numericInput}>
          {#if numericInput}
            <input
              class="input input--num"
              type="number"
              {min}
              {max}
              {step}
              aria-label={label}
              aria-invalid={outside || undefined}
              value={draft ?? value}
              oninput={(event) => {
                draft = event.currentTarget.value;
              }}
              onblur={commitDraft}
            />
          {/if}
          <span class="range__human">{format(value)}</span>
        </div>
      </div>

      {#if presets?.length}
        <div class="range__presets">
          {#each presets as preset (preset)}
            <button
              class="range__preset"
              type="button"
              aria-pressed={preset === value}
              onclick={() => onchange(quantise(preset))}
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
