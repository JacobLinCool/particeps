<script lang="ts">
  /**
   * Export first and full width, because it is the one that is always available; then the three
   * that stop collection, in the order the app's dashboard offers them; then the one that cannot
   * be undone.
   *
   * The legend names the two axes once. Three sentences follow the grid, because they are the part
   * the pips genuinely cannot carry: that stopping collection is not the same as stopping
   * delivery, that withdrawing reaches nothing that already left the phone, and that deletion is
   * final.
   */
  import Glyph from '$lib/ui/Glyph.svelte';
  import ControlCard from './ControlCard.svelte';
  import EffectPip from './EffectPip.svelte';
  import { m, type MessageKey } from './messages.svelte';
  import { EFFECT_KEY, type ControlEntry, type Effect } from './content';

  interface Props {
    entries: readonly ControlEntry[];
  }

  let { entries }: Props = $props();

  const EFFECTS: readonly Effect[] = [
    'continues',
    'stops',
    'drains-then-stops',
    'already-stopped',
    'none'
  ];
</script>

<p class="legend">
  <span class="legend__item">
    <Glyph name="sources" size={16} />
    <span>{m('controls.axis.collection')}</span>
  </span>
  <span class="legend__item">
    <Glyph name="send" size={16} />
    <span>{m('controls.axis.sending')}</span>
  </span>
</p>

<!-- The axis legend says what the two columns are; this one says what the shapes in them mean.
     Without it the shapes were readable by a screen reader and by nobody looking at the page. -->
<p class="legend legend--shapes">
  {#each EFFECTS as effect (effect)}
    <span class="legend__item">
      <EffectPip {effect} axis="collection" size={18} />
      <span>{m(EFFECT_KEY[effect])}</span>
    </span>
  {/each}
</p>

<div class="matrix">
  {#each entries as entry (entry.id)}
    <ControlCard
      glyph={entry.glyph}
      labelKey={`controls.label.${entry.id}` as MessageKey}
      noteKey={`controls.note.${entry.id}` as MessageKey}
      collection={entry.collection}
      sending={entry.sending}
    />
  {/each}
</div>

<div class="stack stack--tight">
  <p class="fine">{m('controls.sending')}</p>
  <p class="fine">{m('controls.recall')}</p>
  <p class="fine">{m('controls.irreversible')}</p>
</div>

<style>
  .legend {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-4) var(--sp-7);
    font-size: var(--type-fine);
    color: var(--ink-soft);
  }

  .legend--shapes {
    gap: var(--sp-3) var(--sp-6);
    font-size: var(--type-micro);
    color: var(--ink-faint);
  }

  .legend__item {
    display: inline-flex;
    align-items: center;
    gap: var(--sp-4);
  }

  .matrix {
    display: grid;
    gap: var(--sp-5);
    grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
  }

  .matrix > :global(:first-child) {
    grid-column: 1 / -1;
  }
</style>
