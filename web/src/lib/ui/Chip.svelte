<script lang="ts">
  /** A small labelled state, or a small labelled choice. `onclick` decides which. */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Tone } from './types';

  interface Props {
    label: string;
    icon?: IconRef;
    tone?: Extract<Tone, 'accent' | 'signal' | 'caution' | 'danger' | 'binary' | 'voice'>;
    /** Only meaningful with `onclick`: renders as `aria-pressed`. */
    selected?: boolean;
    code?: boolean;
    href?: string;
    testid?: string;
    class?: string;
    onclick?: () => void;
  }

  let { label, icon, tone, selected, code = false, href, testid, class: extra, onclick }: Props =
    $props();

  const klass = $derived(cx('chip', tone && `chip--${tone}`, code && 'chip--code', extra));
</script>

{#if onclick}
  <button
    class={klass}
    type="button"
    aria-pressed={selected}
    {onclick}
    data-testid={testid}
  >
    {#if icon}<Icon name={icon} size={16} />{/if}
    <span>{label}</span>
  </button>
{:else if href}
  <a class={klass} {href} data-testid={testid}>
    {#if icon}<Icon name={icon} size={16} />{/if}
    <span>{label}</span>
  </a>
{:else}
  <span class={klass} data-testid={testid}>
    {#if icon}<Icon name={icon} size={16} />{/if}
    <span>{label}</span>
  </span>
{/if}
