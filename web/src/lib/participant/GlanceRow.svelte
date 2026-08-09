<script lang="ts">
  /** The ten-second summary and the navigation at once: two ordinary anchors, each naming a
   *  section by its glyph before its words. */
  import Glyph from '$lib/ui/Glyph.svelte';
  import { m, type MessageKey } from './messages.svelte';
  import type { GlyphName } from './content';

  interface Props {
    items: readonly { href: string; glyph: GlyphName; labelKey: MessageKey }[];
  }

  let { items }: Props = $props();
</script>

<nav class="glance">
  {#each items as item (item.href)}
    <a class="glance__chip" href={item.href}>
      <Glyph name={item.glyph} size={18} />
      <span>{m(item.labelKey)}</span>
    </a>
  {/each}
</nav>

<style>
  .glance {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-4);
  }

  .glance__chip {
    display: inline-flex;
    align-items: center;
    gap: var(--sp-4);
    min-block-size: var(--tap-min);
    padding-inline: var(--sp-6);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-pill);
    background: var(--surface);
    color: var(--ink);
    font-size: var(--type-fine);
    text-decoration: none;
    transition:
      border-color var(--motion-state),
      background-color var(--motion-state),
      translate var(--motion-state);
  }

  .glance__chip :global(.icon) {
    color: var(--accent);
  }

  .glance__chip:hover {
    border-color: var(--accent);
    background: var(--accent-wash);
    translate: 0 -1px;
  }
</style>
