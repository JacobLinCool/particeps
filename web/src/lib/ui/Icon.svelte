<script lang="ts">
  /**
   * One component for thirty-odd marks, keyed by name.
   *
   * A mark is decoration by default and reads as nothing to a screen reader, because the great
   * majority sit beside the word they illustrate. Passing `label` turns it into an image with a
   * name — for the handful that stand alone, where the caller has the string from i18n.
   */
  import { ICONS, resolveIcon, type IconRef } from './icons';
  import { cx } from './format';
  import type { Tone } from './types';

  interface Props {
    name: IconRef;
    size?: number;
    tone?: Tone;
    label?: string;
    class?: string;
  }

  let { name, size = 20, tone, label, class: extra }: Props = $props();

  const drawn = $derived(resolveIcon(name));
</script>

{#if drawn}
  <svg
    class={cx('icon', tone && `icon--${tone}`, extra)}
    viewBox="0 0 24 24"
    width={size}
    height={size}
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    role={label ? 'img' : undefined}
    aria-hidden={label ? undefined : 'true'}
    aria-label={label}
    focusable="false"
  >
    <!-- eslint-disable-next-line svelte/no-at-html-tags -- the markup is a constant in icons.ts -->
    {@html ICONS[drawn]}
  </svg>
{/if}
