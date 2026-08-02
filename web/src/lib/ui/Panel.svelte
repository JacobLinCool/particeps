<script lang="ts">
  /**
   * A card with a subject.
   *
   * The 20px icon in the header is the same mark at the same size as the row it configures, which
   * ties two things together — an accent bar down the left edge would only decorate one.
   */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Snippet } from 'svelte';

  interface Props {
    title?: string;
    icon?: IconRef;
    /** The one thing a reader has to know before filling this in. */
    note?: string;
    variant?: 'raised' | 'flat' | 'sunk';
    headingLevel?: 2 | 3 | 4;
    trailing?: Snippet;
    class?: string;
    testid?: string;
    children: Snippet;
  }

  let {
    title,
    icon,
    note,
    variant = 'raised',
    headingLevel = 2,
    trailing,
    class: extra,
    testid,
    children
  }: Props = $props();
</script>

<section
  class={cx('panel', variant !== 'raised' && `panel--${variant}`, extra)}
  data-testid={testid}
>
  {#if title || icon || trailing}
    <header class="panel__head">
      {#if icon}<Icon name={icon} size={20} tone="accent" />{/if}
      {#if title}
        <svelte:element this={`h${headingLevel}`} class="panel__title">{title}</svelte:element>
      {/if}
      {#if trailing}{@render trailing()}{/if}
    </header>
  {/if}

  {#if note}<p class="panel__note">{note}</p>{/if}

  <div class="panel__body">{@render children()}</div>
</section>
