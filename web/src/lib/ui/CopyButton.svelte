<script lang="ts">
  /**
   * Copy, confirmed on the control that did it.
   *
   * The mark becomes a tick in --signal for two seconds and then returns. No toast: a floating
   * notification puts the confirmation somewhere other than where the reader is looking, and it
   * outlives the act it is confirming. The same confirmation goes into a polite live region,
   * because a colour change is not an announcement.
   */
  import Icon from './Icon.svelte';
  import IconButton from './IconButton.svelte';
  import Button from './Button.svelte';

  interface Props {
    /** A function when the value is derived, so the copy is of what is on screen now. */
    text: string | (() => string);
    label: string;
    /** Announced and, in `text` variant, shown. From `status.copied`. */
    copiedLabel: string;
    /** From `error.clipboard`: the clipboard can refuse, and silence would look like success. */
    failedLabel?: string;
    variant?: 'icon' | 'text';
    float?: boolean;
    testid?: string;
  }

  let { text, label, copiedLabel, failedLabel, variant = 'icon', float = false, testid }: Props =
    $props();

  let phase = $state<'idle' | 'copied' | 'failed'>('idle');
  let announcement = $state('');
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function copy() {
    const value = typeof text === 'function' ? text() : text;
    try {
      await navigator.clipboard.writeText(value);
      phase = 'copied';
      announcement = copiedLabel;
    } catch {
      phase = 'failed';
      announcement = failedLabel ?? '';
    }
    clearTimeout(timer);
    timer = setTimeout(() => {
      phase = 'idle';
      announcement = '';
    }, 2000);
  }

  $effect(() => () => clearTimeout(timer));
</script>

{#if variant === 'icon'}
  <IconButton
    icon="copy"
    {label}
    settled={phase === 'copied'}
    variant={float ? 'float' : 'ghost'}
    tone={phase === 'failed' ? 'danger' : undefined}
    onclick={copy}
    {testid}
  />
{:else}
  <Button
    variant="quiet"
    icon="copy"
    label={phase === 'copied' ? copiedLabel : label}
    settled={phase === 'copied'}
    onclick={copy}
    {testid}
  />
{/if}

<span class="sr" aria-live="polite">{announcement}</span>

{#if phase === 'failed' && failedLabel}
  <span class="fine" style="color: var(--danger-ink)">
    <Icon name="alert" size={14} tone="danger" />
    {failedLabel}
  </span>
{/if}
