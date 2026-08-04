<script lang="ts">
  /** All twelve, always. A study uses some of them; which ones is a question only the phone can
   *  answer, and it answers it on the screen before the one that asks for consent. */
  import SourceTile from './SourceTile.svelte';
  import { sourceDetail, sourceName, type SourceEntry } from './content';

  interface Props {
    entries: readonly SourceEntry[];
  }

  let { entries }: Props = $props();
</script>

<div class="sources">
  {#each entries as entry, index (entry.id)}
    <SourceTile
      glyph={entry.glyph}
      nameKey={sourceName(entry.id)}
      detailKey={sourceDetail(entry.id)}
      {index}
    />
  {/each}
</div>

<style>
  .sources {
    display: grid;
    gap: var(--sp-5);
    grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
  }
</style>
