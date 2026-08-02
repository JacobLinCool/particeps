<script lang="ts">
  /**
   * One source, described in the app's own words.
   *
   * The detail strings are templates the app fills from the signed file, and they stay templates
   * here: `{n}`, `{t}` and `{d}` render as pills carrying the letter rather than as an invented
   * number. That teaches, inside the sentence, that the figures come from the study file and not
   * from the research team's prose — and it keeps this page from ever showing a rate no study set.
   */
  import Glyph from '$lib/ui/Glyph.svelte';
  import Mark from '$lib/ui/Mark.svelte';
  import { m, type MessageKey } from './messages.svelte';
  import { reveal } from './reveal';
  import type { GlyphName } from './content';

  interface Props {
    glyph: GlyphName;
    nameKey: MessageKey;
    detailKey: MessageKey;
    /** Position in the grid, which is the stagger. */
    index?: number;
  }

  let { glyph, nameKey, detailKey, index = 0 }: Props = $props();

  let seen = $state<boolean | undefined>(undefined);

  /** Odd positions are the tokens, because a split on a capturing group keeps what it split on. */
  const parts = $derived(m(detailKey).split(/\{([ntd])\}/));
</script>

<div
  class="source"
  data-in={seen}
  style={`--index: ${index}`}
  use:reveal={(visible) => (seen = visible)}
>
  <span class="source__mark"><Glyph name={glyph} size={22} /></span>

  <div>
    <p class="source__name">{m(nameKey)}</p>
    <p class="source__detail">
      {#each parts as part, at (at)}
        {#if at % 2 === 1}<span class="token">{part.toUpperCase()}</span>{:else}{part}{/if}
      {/each}
    </p>
  </div>

</div>

<style>
  .source {
    display: grid;
    grid-template-columns: 40px 1fr;
    gap: var(--sp-5);
    padding: var(--sp-6);
    background: var(--surface);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
  }

  .source__mark {
    display: grid;
    place-items: center;
    inline-size: 40px;
    block-size: 40px;
    border-radius: var(--r-field);
    background: var(--neutral-wash);
    color: var(--ink-soft);
  }

  .source__name {
    font-size: var(--type-body);
    font-weight: var(--w-medium);
  }

  .source__detail {
    margin-block-start: var(--sp-3);
    font-size: var(--type-fine);
    color: var(--ink-soft);
  }

  /* The letter, not the number. The value is filled in from the signed file, and the pill is what
     says so at the point the sentence would otherwise carry a figure. */
  .token {
    display: inline-block;
    min-inline-size: 1.5em;
    padding-inline: var(--sp-3);
    border-radius: var(--r-chip);
    background: var(--accent-wash);
    color: var(--accent);
    font-family: var(--font-mono);
    font-size: 0.9em;
    text-align: center;
  }

  /* One source earns a heavier edge and one extra line. It is the seal hue rather than the error
     hue: this is a thing to understand before agreeing, not a thing that has gone wrong. */


  @media (prefers-reduced-motion: no-preference) {
    .source[data-in='false'] {
      opacity: 0;
    }

    .source[data-in='true'] {
      animation: tile-in var(--dur-expand) var(--ease-out) both;
      animation-delay: calc(var(--index, 0) * 40ms);
    }

    @keyframes tile-in {
      from {
        opacity: 0;
        translate: 0 8px;
      }
    }
  }
</style>
