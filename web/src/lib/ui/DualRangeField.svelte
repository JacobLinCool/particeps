<script lang="ts">
  /**
   * The answer to `minimum_interval_millis in 500..interval_millis`.
   *
   * Two thumbs on one track, with the low thumb structurally unable to pass the high one: the
   * cross-field constraint becomes geometry instead of a message that appears after the fact. The
   * clamp is applied on every input path — drag, type, preset, keyboard — because a constraint
   * that only holds for the mouse is not a constraint.
   */
  import Field from './Field.svelte';
  import { fieldSource } from './field-context';
  import { logPosition, logValue } from './format';

  interface Props {
    label: string;
    low: number;
    high: number;
    min: number;
    max: number;
    /** The high thumb's own floor, independent of the low one. */
    highMin: number;
    format: (value: number) => string;
    /** Accessible names for the two thumbs, from i18n. */
    lowLabel: string;
    highLabel: string;
    lowPath?: string;
    highPath?: string;
    hint?: string;
    scale?: 'linear' | 'log';
    presets?: readonly number[];
    onchange: (low: number, high: number) => void;
  }

  let {
    label,
    low,
    high,
    min,
    max,
    highMin,
    format,
    lowLabel,
    highLabel,
    lowPath,
    highPath,
    hint,
    scale = 'log',
    presets,
    onchange
  }: Props = $props();

  const source = fieldSource();

  function positionOf(v: number): number {
    return scale === 'log' ? logPosition(v, min, max) : (v - min) / (max - min || 1);
  }

  function valueAt(position: number): number {
    const raw = scale === 'log' ? logValue(position, min, max) : min + position * (max - min);
    return Math.min(max, Math.max(min, Math.round(raw)));
  }

  /** One place decides the pair, so no caller can produce a low above a high. */
  function commit(nextLow: number, nextHigh: number) {
    const boundedHigh = Math.min(max, Math.max(highMin, nextHigh));
    const boundedLow = Math.min(boundedHigh, Math.max(min, nextLow));
    onchange(boundedLow, boundedHigh);
  }

  const lowFill = $derived(Math.min(1, Math.max(0, positionOf(low))));
  const highFill = $derived(Math.min(1, Math.max(0, positionOf(high))));
</script>

<!-- One `Field`, two schema paths. `location_interval_order` is reported against the low thumb, so
     the shell answers for both or that issue row lands nowhere. -->
<Field {label} path={highPath} {hint} issueHost={[lowPath, highPath]}>
  {#snippet children({ id, describedby })}
    <div class="range range--dual">
      <div class="range__line">
        <div class="range__track">
          <div
            class="range__fill"
            style="inset-inline-start: {lowFill * 100}%; inline-size: {(highFill - lowFill) * 100}%"
          ></div>

          {#if presets}
            <div class="range__ticks" aria-hidden="true">
              {#each presets as preset (preset)}
                <span class="range__tick" style="inset-inline-start: {positionOf(preset) * 100}%"
                ></span>
              {/each}
            </div>
          {/if}

          <input
            class="range__input range__input--low"
            type="range"
            min="0"
            max="1000"
            step="1"
            value={Math.round(lowFill * 1000)}
            aria-label={lowLabel}
            aria-valuetext={format(low)}
            oninput={(event) => commit(valueAt(Number(event.currentTarget.value) / 1000), high)}
            onchange={() => lowPath && source.touch?.(lowPath)}
          />
          <input
            class="range__input range__input--high"
            type="range"
            {id}
            min="0"
            max="1000"
            step="1"
            value={Math.round(highFill * 1000)}
            aria-label={highLabel}
            aria-describedby={describedby}
            aria-valuetext={format(high)}
            oninput={(event) => commit(low, valueAt(Number(event.currentTarget.value) / 1000))}
            onchange={() => highPath && source.touch?.(highPath)}
          />
        </div>

        <div class="range__readout">
          <span class="mono fine">{format(low)}</span>
          <span class="faint">–</span>
          <span class="mono fine">{format(high)}</span>
        </div>
      </div>

      {#if presets?.length}
        <div class="range__presets">
          {#each presets as preset (preset)}
            <button
              class="range__preset"
              type="button"
              aria-pressed={preset === high}
              onclick={() => commit(low, preset)}
              data-testid={highPath ? `preset-${highPath}-${preset}` : undefined}
            >
              {format(preset)}
            </button>
          {/each}
        </div>
      {/if}
    </div>
  {/snippet}
</Field>
