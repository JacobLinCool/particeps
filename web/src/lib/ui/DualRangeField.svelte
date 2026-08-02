<script lang="ts">
  /**
   * The answer to `minimum_interval_millis in 500..interval_millis`.
   *
   * Two thumbs on one track, with the low thumb structurally unable to pass the high one: the
   * cross-field constraint becomes geometry instead of a message that appears after the fact. The
   * clamp is applied on every input path — drag, preset, keyboard — because a constraint that only
   * holds for the mouse is not a constraint.
   *
   * Both thumbs are rung indices on the adapter's ladder, for the same reason the single-value
   * control's are: a log track over 0.5 s to 1 h reaches 30 103 ms but not 30 000, and the readout
   * then has to print `30.103 sec`, which is the control admitting it cannot say what was meant.
   * With indices the pair is also exactly clampable — `low ≤ high` is an integer comparison — so
   * the promise `commit()` makes holds on every path rather than nearly.
   *
   * No number box, and none is coming: two boxes on one track is two decisions, where the whole
   * argument of this component is that there is one.
   */
  import Field from './Field.svelte';
  import { fieldSource } from './field-context';
  import type { Scale } from './types';

  interface Props {
    label: string;
    /** Both in the unit the schema stores. */
    low: number;
    high: number;
    /** A laddered adapter. See `researcher/scales.ts`. */
    unit: Scale;
    /** The high thumb's own floor, in stored units, independent of the low one. */
    highMin: number;
    /** Accessible names for the two thumbs, from i18n. */
    lowLabel: string;
    highLabel: string;
    lowPath?: string;
    highPath?: string;
    hint?: string;
    onchange: (low: number, high: number) => void;
  }

  let { label, low, high, unit, highMin, lowLabel, highLabel, lowPath, highPath, hint, onchange }: Props =
    $props();

  const source = fieldSource();

  /** A pair on one track is a laddered control by construction; the fallback keeps the type total. */
  const rungs = $derived(unit.ladder ?? [unit.min, unit.max]);

  function nearest(target: number): number {
    let best = 0;
    let distance = Infinity;
    for (let i = 0; i < rungs.length; i++) {
      const away = Math.abs(rungs[i] - target);
      if (away < distance) {
        distance = away;
        best = i;
      }
    }
    return best;
  }

  const lowIndex = $derived(nearest(unit.toHuman(low)));
  const highIndex = $derived(nearest(unit.toHuman(high)));
  /** The first rung the high thumb may stand on, which is where its own schema floor lands. */
  const highFloor = $derived(Math.max(0, rungs.findIndex((rung) => rung >= unit.toHuman(highMin))));

  /** One place decides the pair, so no caller can produce a low above a high. */
  function commit(nextLow: number, nextHigh: number) {
    const boundedHigh = Math.min(rungs.length - 1, Math.max(highFloor, nextHigh));
    const boundedLow = Math.min(boundedHigh, Math.max(0, nextLow));
    onchange(unit.toStored(rungs[boundedLow]), unit.toStored(rungs[boundedHigh]));
  }

  const span = $derived(Math.max(1, rungs.length - 1));
  const lowFill = $derived(lowIndex / span);
  const highFill = $derived(highIndex / span);
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

          {#if unit.presets.length}
            <div class="range__ticks" aria-hidden="true">
              {#each unit.presets as preset (preset)}
                <span
                  class="range__tick"
                  style="inset-inline-start: {(nearest(preset) / span) * 100}%"
                ></span>
              {/each}
            </div>
          {/if}

          <input
            class="range__input range__input--low"
            type="range"
            min="0"
            max={rungs.length - 1}
            step="1"
            value={lowIndex}
            aria-label={lowLabel}
            aria-valuetext={unit.format(rungs[lowIndex])}
            oninput={(event) => commit(Number(event.currentTarget.value), highIndex)}
            onchange={() => lowPath && source.touch?.(lowPath)}
          />
          <input
            class="range__input range__input--high"
            type="range"
            {id}
            min="0"
            max={rungs.length - 1}
            step="1"
            value={highIndex}
            aria-label={highLabel}
            aria-describedby={describedby}
            aria-valuetext={unit.format(rungs[highIndex])}
            oninput={(event) => commit(lowIndex, Number(event.currentTarget.value))}
            onchange={() => highPath && source.touch?.(highPath)}
          />
        </div>

        <div class="range__readout">
          <span class="mono fine">{unit.format(rungs[lowIndex])}</span>
          <span class="faint">–</span>
          <span class="mono fine">{unit.format(rungs[highIndex])}</span>
        </div>
      </div>

      {#if unit.presets.length}
        <div class="range__presets">
          {#each unit.presets as preset (preset)}
            <button
              class="range__preset"
              type="button"
              aria-pressed={preset === unit.toHuman(high)}
              onclick={() => commit(lowIndex, nearest(preset))}
              data-testid={highPath ? `preset-${highPath}-${preset}` : undefined}
            >
              {unit.format(preset)}
            </button>
          {/each}
        </div>
      {/if}
    </div>
  {/snippet}
</Field>
