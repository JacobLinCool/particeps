<script lang="ts">
  /**
   * One profile-independent data category, described in participant language.
   *
   * This public page has no signed study, so it cannot project cadence, thresholds, or any other
   * named-profile setting. Study-specific participant UI uses the same category boundary.
   */
  import Glyph from '$lib/ui/Glyph.svelte';
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
    <p class="source__detail">{m(detailKey)}</p>
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
