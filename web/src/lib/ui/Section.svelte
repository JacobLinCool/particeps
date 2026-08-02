<script lang="ts">
  /**
   * A page section separated by ground rather than by a card, and the anchor target the glance
   * chips point at. `raised` is for the one block that describes what the reader does rather than
   * what the app does — the surface says so before the heading is read.
   */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Snippet } from 'svelte';

  interface Props {
    id: string;
    title: string;
    /** The schema path this section owns, for issues no single control can host. */
    path?: string;
    icon?: IconRef;
    lead?: string;
    variant?: 'plain' | 'raised';
    headingLevel?: 2 | 3;
    class?: string;
    children: Snippet;
  }

  let {
    id,
    title,
    path,
    icon,
    lead,
    variant = 'plain',
    headingLevel = 2,
    class: extra,
    children
  }: Props = $props();
</script>

<section
  {id}
  class={cx('section', variant === 'raised' && 'section--raised', extra)}
  data-issue-host={path}
>
  <header class="section__head">
    {#if icon}<Icon name={icon} size={22} tone="accent" />{/if}
    <svelte:element this={`h${headingLevel}`}>{title}</svelte:element>
  </header>
  {#if lead}<p class="section__lead">{lead}</p>{/if}
  {@render children()}
</section>
