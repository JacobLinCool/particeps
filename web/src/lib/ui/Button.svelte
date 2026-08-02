<script lang="ts">
  /** A verb. Icon-only controls are `IconButton`, which requires a name; this one carries text. */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Snippet } from 'svelte';

  interface Props {
    label?: string;
    variant?: 'primary' | 'quiet' | 'ghost' | 'danger';
    icon?: IconRef;
    iconEnd?: IconRef;
    /** Rendered as a badge on the control itself, so a disabled action says why. */
    count?: number;
    disabled?: boolean;
    type?: 'button' | 'submit';
    href?: string;
    /** After the act succeeded: the icon becomes a tick in --signal for a beat. */
    settled?: boolean;
    describedby?: string;
    testid?: string;
    class?: string;
    onclick?: (event: MouseEvent) => void;
    children?: Snippet;
  }

  let {
    label,
    variant = 'quiet',
    icon,
    iconEnd,
    count,
    disabled = false,
    type = 'button',
    href,
    settled = false,
    describedby,
    testid,
    class: extra,
    onclick,
    children
  }: Props = $props();

  const klass = $derived(cx('btn', `btn--${variant}`, settled && 'btn--settled', extra));
</script>

{#snippet inner()}
  {#if icon}<Icon name={settled ? 'check' : icon} size={18} />{/if}
  {#if children}{@render children()}{:else if label}<span>{label}</span>{/if}
  {#if typeof count === 'number' && count > 0}<span class="btn__count">{count}</span>{/if}
  {#if iconEnd}<Icon name={iconEnd} size={18} />{/if}
{/snippet}

{#if href}
  <a class={klass} {href} data-testid={testid} aria-describedby={describedby}>
    {@render inner()}
  </a>
{:else}
  <button
    class={klass}
    {type}
    {disabled}
    {onclick}
    data-testid={testid}
    aria-describedby={describedby}
  >
    {@render inner()}
  </button>
{/if}
