<script lang="ts">
  /** One control, labelled with the app's own button string so the card and the button on the
   *  phone are recognisably the same thing, and carrying its two effects as pips. */
  import Glyph from '$lib/ui/Glyph.svelte';
  import EffectPip from './EffectPip.svelte';
  import { m, type MessageKey } from './messages.svelte';
  import type { Effect, GlyphName } from './content';

  interface Props {
    glyph: GlyphName;
    labelKey: MessageKey;
    noteKey: MessageKey;
    collection: Effect;
    sending: Effect;
  }

  let { glyph, labelKey, noteKey, collection, sending }: Props = $props();
</script>

<div class="control">
  <div class="control__head">
    <Glyph name={glyph} size={20} tone="accent" />
    <h3 class="control__label">{m(labelKey)}</h3>
  </div>

  <p class="control__note">{m(noteKey)}</p>

  <div class="control__pips">
    <EffectPip effect={collection} axis="collection" />
    <EffectPip effect={sending} axis="sending" />
  </div>
</div>

<style>
  .control {
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
    padding: var(--sp-6);
    background: var(--surface);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
  }

  .control__head {
    display: flex;
    align-items: center;
    gap: var(--sp-5);
  }

  .control__label {
    font-size: var(--type-body);
    font-weight: var(--w-medium);
  }

  .control__note {
    font-size: var(--type-fine);
    color: var(--ink-soft);
    margin-block-end: auto;
  }

  .control__pips {
    display: flex;
    gap: var(--sp-7);
    padding-block-start: var(--sp-4);
    border-block-start: var(--line-hair) solid var(--rule);
  }
</style>
