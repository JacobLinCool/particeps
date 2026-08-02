<script lang="ts">
  /** Collapsed by default, and never used to hide something a reader has to see. The chevron
   *  rotates rather than swapping glyphs, so the control's identity survives the state change. */
  import Icon from './Icon.svelte';
  import type { IconRef } from './icons';
  import { untrack, type Snippet } from 'svelte';

  interface Props {
    label: string;
    icon?: IconRef;
    open?: boolean;
    trailing?: Snippet;
    testid?: string;
    children: Snippet;
  }

  let { label, icon, open = false, trailing, testid, children }: Props = $props();

  let expanded = $state(untrack(() => open));
  const uid = $props.id();
</script>

<div class="disclosure" data-testid={testid}>
  <button
    class="disclosure__head"
    type="button"
    aria-expanded={expanded}
    aria-controls={uid}
    onclick={() => (expanded = !expanded)}
  >
    {#if icon}<Icon name={icon} size={16} tone="faint" />{/if}
    <span>{label}</span>
    {#if trailing}{@render trailing()}{/if}
    <Icon name="chevron" size={16} class="disclosure__chevron" />
  </button>
  {#if expanded}
    <div class="disclosure__body" id={uid}>{@render children()}</div>
  {/if}
</div>
