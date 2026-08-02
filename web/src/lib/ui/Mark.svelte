<script lang="ts">
  /**
   * The app's `CheckMark`, `PendingMark`, and status dot, redrawn at the same weights.
   *
   * The five kinds differ in shape before they differ in colour, which is what lets the rail and
   * the receipt stay legible with the hue channel switched off: a filled disc with a tick is done,
   * a hollow ring is not started, a ring with an arc is partway, a filled disc alone is blocked.
   */
  import { cx } from './format';
  import type { Tone } from './types';

  interface Props {
    kind: 'check' | 'pending' | 'blocking' | 'partial' | 'dot' | 'cross';
    tone?: Tone;
    size?: number;
    /** Standalone marks need a name; marks beside their own label do not. */
    label?: string;
    class?: string;
  }

  let { kind, tone = 'accent', size = 16, label, class: extra }: Props = $props();
</script>

<svg
  class={cx('mark', `mark--${kind}`, `icon--${tone}`, extra)}
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
  {#if kind === 'check'}
    <circle cx="12" cy="12" r="10" fill="currentColor" stroke="none" />
    <path d="m7.4 12.3 3.1 3.1 6.1-6.8" stroke="var(--surface)" />
  {:else if kind === 'cross'}
    <circle cx="12" cy="12" r="10" fill="currentColor" stroke="none" />
    <path d="M8.4 8.4 15.6 15.6M15.6 8.4 8.4 15.6" stroke="var(--surface)" />
  {:else if kind === 'blocking'}
    <circle cx="12" cy="12" r="10" fill="currentColor" stroke="none" />
  {:else if kind === 'partial'}
    <circle cx="12" cy="12" r="9" stroke="var(--rule)" />
    <path d="M12 3a9 9 0 0 1 9 9" />
  {:else if kind === 'dot'}
    <circle cx="12" cy="12" r="5" fill="currentColor" stroke="none" />
  {:else}
    <circle cx="12" cy="12" r="9" />
  {/if}
</svg>
