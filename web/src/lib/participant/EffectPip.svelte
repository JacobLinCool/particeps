<script lang="ts">
  /**
   * What one control does to one axis, as a state rather than a word.
   *
   * Five shapes, distinguishable before they are distinguishable by colour: a flow that carries
   * on, a flow that meets a wall, a dashed flow that meets a wall — what is already collected
   * still leaves, and then stops — a bare wall for something that had already stopped, and a plain
   * rule for an axis this control does not touch. The legend above the grid names the two axes
   * once; after that the pips need no labels of their own, only accessible names.
   */
  import { m } from './messages.svelte';
  import { EFFECT_KEY, type Effect } from './content';

  interface Props {
    effect: Effect;
    axis: 'collection' | 'sending';
    size?: number;
  }

  let { effect, axis, size = 22 }: Props = $props();

  const name = $derived(
    `${m(axis === 'collection' ? 'controls.axis.collection' : 'controls.axis.sending')} — ${m(EFFECT_KEY[effect])}`
  );
</script>

<span class="pip" data-effect={effect}>
  <svg
    viewBox="0 0 24 24"
    width={size}
    height={size}
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    role="img"
    aria-label={name}
  >
    {#if effect === 'continues'}
      <path d="M3 12h11" />
      <path d="m14 7 5 5-5 5" />
    {:else if effect === 'stops'}
      <path d="M3 12h9" />
      <path d="M18 5v14" />
    {:else if effect === 'drains-then-stops'}
      <path d="M3 12h9" stroke-dasharray="3 3" />
      <path d="M18 5v14" />
    {:else if effect === 'already-stopped'}
      <path d="M18 5v14" />
    {:else}
      <path d="M5 12h14" />
    {/if}
  </svg>
</span>

<style>
  .pip {
    display: inline-flex;
    color: var(--ink-faint);
  }

  .pip[data-effect='continues'],
  .pip[data-effect='drains-then-stops'] {
    color: var(--accent);
  }

  .pip[data-effect='stops'] {
    color: var(--ink);
  }
</style>
