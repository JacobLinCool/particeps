<script lang="ts">
  /**
   * One step's panel. It crossfades and translates 8px in the direction of travel, and moves
   * focus to its own heading — a step change that leaves focus behind strands a keyboard reader on
   * a control that no longer exists.
   *
   * Under reduced motion the translate is dropped and the crossfade shortens; base.css handles
   * that at the class level, so nothing here has to ask.
   */
  import Icon from './Icon.svelte';
  import type { IconRef } from './icons';
  import type { Snippet } from 'svelte';

  interface Props {
    id: string;
    title: string;
    icon?: IconRef;
    /** 1 forward, -1 back. Decides which way the panel comes in from. */
    direction?: 1 | -1;
    trailing?: Snippet;
    children: Snippet;
  }

  let { id, title, icon, direction = 1, trailing, children }: Props = $props();

  let heading: HTMLHeadingElement | undefined = $state();
  let entered = false;

  // Focus follows a step change, not the first paint: taking focus on load moves a reader who
  // never asked to be moved.
  $effect(() => {
    id;
    if (entered) heading?.focus();
    else entered = true;
  });
</script>

{#key id}
  <section class="steppanel" data-direction={direction} data-testid={`step-${id}`}>
    <header class="steppanel__head">
      {#if icon}<Icon name={icon} size={22} tone="accent" />{/if}
      <h1 class="steppanel__title" bind:this={heading} tabindex="-1">{title}</h1>
      {#if trailing}<span class="grow"></span>{@render trailing()}{/if}
    </header>
    {@render children()}
  </section>
{/key}
