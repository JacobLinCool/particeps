<script lang="ts">
  /**
   * The bounded value control, and the thing that keeps step 3 from being a form.
   *
   * The track spans the entire legal range, so the bounds are visible without being read. The
   * presets are real buttons showing real values — they are what turns an unbounded number into the
   * small set of choices the decision is actually made from, and they are the fastest keyboard
   * path.
   *
   * Everything about how the number is said comes off one adapter (`researcher/scales.ts`): the
   * unit it is edited in, the word for that unit, the bounds, the step, the chips, the humaniser,
   * and whether there is a box at all. `value` in and `onchange` out are in the unit the *schema*
   * stores; every other number inside this component is in the unit a *person* uses, and the two
   * are converted at those two edges and nowhere else. That is the whole reason the adapter exists:
   * a researcher was being shown `100000` beside `10 Hz`, and asked to type in it.
   *
   * Three tracks, and the reason for each:
   *   ladder    the reachable set is a list of named rungs and the slider indexes it, one rung per
   *             arrow press. A log slider cannot be dragged to a round number — it reaches 716,
   *             722 and 727 minutes but not 720 — so a control with no box, where the slider is the
   *             only way to a non-chip value, cannot be a continuum and stay honest.
   *   log       a linear track crowds every usable value into one edge for a range like 1 to 200.
   *             Offset by 1 so a range including 0 still works.
   *   linear    the native value space, where arrow keys move by `step` and nothing is remapped.
   *
   * Ticks are `aria-hidden` decoration; the preset buttons are the reachable affordance.
   */
  import Field from './Field.svelte';
  import { fieldSource } from './field-context';
  import { logPosition, logValue } from './format';
  import type { IconRef } from './icons';
  import type { Scale } from './types';

  interface Props {
    label: string;
    /** In the unit the schema stores. The only number on this boundary that is. */
    value: number;
    /** Unit, bounds, chips and humaniser in one object. See `researcher/scales.ts`. */
    unit: Scale;
    path?: string;
    hint?: string;
    icon?: IconRef;
    /** Marks the readout --caution without raising an issue: legal, but worth a second look. */
    caution?: boolean;
    onchange: (value: number) => void;
  }

  let { label, value, unit, path, hint, icon, caution = false, onchange }: Props = $props();

  const source = fieldSource();

  /** Everything below this line is in control space. */
  const human = $derived(unit.toHuman(value));
  const rungs = $derived(unit.ladder);

  function nearestRung(ladder: readonly number[], target: number): number {
    let best = 0;
    let distance = Infinity;
    for (let i = 0; i < ladder.length; i++) {
      const away = Math.abs(ladder[i] - target);
      if (away < distance) {
        distance = away;
        best = i;
      }
    }
    return best;
  }

  function quantise(raw: number): number {
    const snapped = Math.round(raw / unit.step) * unit.step;
    const clamped = Math.min(unit.max, Math.max(unit.min, snapped));
    return unit.step < 1
      ? Number(clamped.toFixed(String(unit.step).split('.')[1]?.length ?? 1))
      : clamped;
  }

  /** The one gate every input path goes through: nothing off the lattice, nothing off the ladder. */
  function snap(raw: number): number {
    return rungs ? rungs[nearestRung(rungs, raw)] : quantise(raw);
  }

  function commit(rawHuman: number) {
    onchange(unit.toStored(snap(rawHuman)));
  }

  const index = $derived(rungs ? nearestRung(rungs, human) : 0);

  function positionOf(h: number): number {
    if (rungs) return rungs.length > 1 ? nearestRung(rungs, h) / (rungs.length - 1) : 0;
    return unit.scale === 'log'
      ? logPosition(h, unit.min, unit.max)
      : (h - unit.min) / (unit.max - unit.min || 1);
  }

  function valueAt(position: number): number {
    return quantise(
      unit.scale === 'log'
        ? logValue(position, unit.min, unit.max)
        : unit.min + position * (unit.max - unit.min)
    );
  }

  const fill = $derived(Math.min(1, Math.max(0, positionOf(human))));

  /**
   * The readout row: `[ 60 ] sec   1 min`. The echo is dropped when it only restates the box —
   * which it does on every control whose humaniser is the affix — so most rows are `[ 50 ] Hz` and
   * nothing more. It survives where it says something the box cannot, which in practice is report
   * latency at 60 seconds reading `1 min`.
   */
  const literal = $derived(`${human} ${unit.affix}`);
  const echo = $derived(unit.format(human));

  /** Same number, same unit, different spelling — `50.0 m` against `50 m` — is still a restatement. */
  function restates(text: string, count: number, affix: string): boolean {
    if (text === String(count)) return true;
    if (!affix || !text.endsWith(affix)) return false;
    const head = text.slice(0, -affix.length).trim();
    return head !== '' && Number(head) === count;
  }

  const showEcho = $derived(unit.box && !restates(echo, human, unit.affix));
  const valuetext = $derived(unit.box ? (showEcho ? `${literal}, ${echo}` : literal) : echo);

  let draft = $state<string | null>(null);
  const outside = $derived(
    draft !== null &&
      draft.trim() !== '' &&
      (Number(draft) < unit.min || Number(draft) > unit.max)
  );

  /**
   * Out of range clamps to the bound, because the bound is the schema's and a clamped value is
   * always valid. An empty box restores the current value rather than committing zero: `Number('')`
   * is 0 and finite, which on report latency silently turned batching off.
   */
  function commitDraft() {
    if (draft !== null) {
      const text = draft.trim();
      draft = null;
      if (text !== '') {
        const parsed = Number(text);
        if (Number.isFinite(parsed)) commit(parsed);
      }
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

          {#if unit.presets.length}
            <div class="range__ticks" aria-hidden="true">
              {#each unit.presets as preset (preset)}
                <span class="range__tick" style="inset-inline-start: {positionOf(preset) * 100}%"
                ></span>
              {/each}
            </div>
          {/if}

          {#if rungs}
            <input
              class="range__input"
              type="range"
              {id}
              min="0"
              max={rungs.length - 1}
              step="1"
              value={index}
              aria-describedby={describedby}
              aria-invalid={invalid || undefined}
              aria-valuetext={valuetext}
              oninput={(event) => commit(rungs[Number(event.currentTarget.value)])}
              onchange={() => path && source.touch?.(path)}
            />
          {:else if unit.scale === 'log'}
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
              aria-valuetext={valuetext}
              oninput={(event) => commit(valueAt(Number(event.currentTarget.value) / 1000))}
              onchange={() => path && source.touch?.(path)}
            />
          {:else}
            <input
              class="range__input"
              type="range"
              {id}
              min={unit.min}
              max={unit.max}
              step={unit.step}
              value={human}
              aria-describedby={describedby}
              aria-invalid={invalid || undefined}
              aria-valuetext={valuetext}
              oninput={(event) => commit(Number(event.currentTarget.value))}
              onchange={() => path && source.touch?.(path)}
            />
          {/if}
        </div>

        <div class="range__readout" class:range__readout--human={!unit.box}>
          {#if unit.box}
            <input
              class="input input--num"
              type="number"
              min={unit.min}
              max={unit.max}
              step={unit.step}
              aria-label={`${label} ${unit.affix}`}
              aria-invalid={outside || undefined}
              value={draft ?? human}
              oninput={(event) => {
                draft = event.currentTarget.value;
              }}
              onblur={commitDraft}
            />
            <span class="range__affix">{unit.affix}</span>
          {/if}
          {#if !unit.box || showEcho}
            <span class="range__human">{echo}</span>
          {/if}
        </div>
      </div>

      {#if unit.presets.length}
        <div class="range__presets">
          {#each unit.presets as preset (preset)}
            <button
              class="range__preset"
              type="button"
              aria-pressed={preset === human}
              onclick={() => commit(preset)}
              data-testid={path ? `preset-${path}-${preset}` : undefined}
            >
              {unit.format(preset)}
            </button>
          {/each}
        </div>
      {/if}
    </div>
  {/snippet}
</Field>
