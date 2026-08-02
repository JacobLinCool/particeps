<script lang="ts">
  /**
   * The app's dot-and-connector rail, with the app's geometry and different semantics.
   *
   * On the phone a dot means *where you are in a sequence*, because the participant's steps are a
   * disclosure gate and are forward-only. Here a dot means *whether that step's output exists and
   * is valid*: authoring is iterative, every step is reachable at any time, and nothing on the
   * researcher page is a disclosure anyone must be made to read. Same drawing, so a researcher who
   * has used the app recognises the shape; different rules, because the job is different.
   *
   * Without `onnavigate` the rail is a picture — which is the participant page's use, where it
   * shows the shape of setup rather than a position in it.
   */
  import Mark from './Mark.svelte';
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { StepDef, StepState } from './types';

  interface Props {
    steps: readonly StepDef[];
    current?: string | null;
    orientation?: 'vertical' | 'horizontal';
    /** Announced with the step name, e.g. `control.stepPosition`. */
    position?: (at: { index: number; total: number }) => string;
    /**
     * Appended to the accessible name so a red dot says why it is red. The step's id comes last
     * because one state does not always mean one thing: on a step whose output is two files, a
     * partial dot is "two secrets are not on disk", and elsewhere it is ordinary progress.
     */
    stateLabel?: (state: StepState, count: number, id: string) => string;
    /** `auto` drops the names on narrow viewports, as the app does. `always` keeps them, which is
     *  what the participant page wants: there the reader is learning the shape, not navigating. */
    labels?: 'auto' | 'always';
    /** Names the landmark. Required whenever `onnavigate` is given, ignored otherwise. */
    label?: string;
    class?: string;
    onnavigate?: (id: string) => void;
  }

  let {
    steps,
    current = null,
    orientation = 'vertical',
    position,
    stateLabel,
    labels = 'auto',
    label,
    class: extra,
    onnavigate
  }: Props = $props();

  const MARK = {
    empty: { kind: 'pending', tone: 'faint' },
    partial: { kind: 'partial', tone: 'accent' },
    complete: { kind: 'check', tone: 'signal' },
    blocked: { kind: 'blocking', tone: 'danger' }
  } as const;

  const currentIndex = $derived(Math.max(0, steps.findIndex((step) => step.id === current)));
  let roving = $state(0);
  $effect(() => {
    roving = currentIndex;
  });

  let host: HTMLElement | undefined = $state();

  function move(to: number) {
    const next = Math.min(steps.length - 1, Math.max(0, to));
    roving = next;
    host?.querySelectorAll<HTMLButtonElement>('.rail__step')[next]?.focus();
  }

  function keydown(event: KeyboardEvent) {
    const forward = orientation === 'vertical' ? 'ArrowDown' : 'ArrowRight';
    const back = orientation === 'vertical' ? 'ArrowUp' : 'ArrowLeft';
    if (event.key === forward) move(roving + 1);
    else if (event.key === back) move(roving - 1);
    else if (event.key === 'Home') move(0);
    else if (event.key === 'End') move(steps.length - 1);
    else return;
    event.preventDefault();
  }

  function name(step: StepDef, index: number): string {
    const parts = [step.label];
    if (position) parts.push(position({ index: index + 1, total: steps.length }));
    if (stateLabel && step.state) parts.push(stateLabel(step.state, step.count ?? 0, step.id));
    return parts.join(', ');
  }
</script>

<!-- A `nav` when it navigates, a plain `div` when it is a picture: the participant page draws the
     shape of setup with this component, and a navigation landmark containing no links is a
     navigation that goes nowhere.

     The keydown handler belongs on the container because the roving tabindex means only one child
     is in the tab order at a time, and every child under it is a real button. -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<svelte:element
  this={onnavigate ? 'nav' : 'div'}
  bind:this={host}
  class={cx('rail', `rail--${orientation}`, labels === 'always' && 'rail--labelled', extra)}
  aria-label={onnavigate ? label : undefined}
  onkeydown={onnavigate ? keydown : undefined}
>
  {#each steps as step, index (step.id)}
    {#if index > 0}
      <span
        class="rail__link"
        data-filled={steps[index - 1].state === 'complete'}
        aria-hidden="true"
      ></span>
    {/if}

    {#if onnavigate}
      <button
        class="rail__step"
        type="button"
        aria-current={step.id === current ? 'step' : undefined}
        aria-label={name(step, index)}
        tabindex={index === roving ? 0 : -1}
        onclick={() => onnavigate(step.id)}
        data-testid={`rail-${step.id}`}
      >
        <span class="rail__mark">
          <Mark
            kind={MARK[step.state ?? 'empty'].kind}
            tone={MARK[step.state ?? 'empty'].tone}
            size={20}
          />
        </span>
        {#if step.icon}<Icon name={step.icon} size={16} tone="faint" />{/if}
        <span class="rail__label">{step.label}</span>
        {#if step.count}<span class="rail__count">{step.count}</span>{/if}
      </button>
    {:else}
      <span class="rail__step" data-testid={`rail-${step.id}`}>
        <span class="rail__mark">
          <Mark
            kind={MARK[step.state ?? 'empty'].kind}
            tone={MARK[step.state ?? 'empty'].tone}
            size={20}
          />
        </span>
        {#if step.icon}<Icon name={step.icon} size={16} tone="faint" />{/if}
        <span class="rail__label">{step.label}</span>
      </span>
    {/if}
  {/each}
</svelte:element>
