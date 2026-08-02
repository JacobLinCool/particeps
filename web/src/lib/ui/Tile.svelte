<script lang="ts">
  /**
   * One artefact: a mark, a filename, and what it is.
   *
   * The filename is monospace because it gets typed into `researcher-tools sign --private …`; the
   * byte count is monospace because it gets compared against the byte pane and against what lands
   * on disk. Both are identifiers, not prose.
   *
   * `secret` is not a decoration. A private key is a different kind of object from everything else
   * on the page, and the border says so before the sentence does.
   */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Tone } from './types';
  import type { Snippet } from 'svelte';

  interface Props {
    icon: IconRef;
    /** The filename. Rendered as-is: it is what appears on disk. */
    name: string;
    tone?: Tone;
    meta?: Snippet;
    /** Sentence for the strip under the meta line. The one place a sentence is unavoidable. */
    warning?: string;
    secret?: boolean;
    /** Nothing to download yet. */
    empty?: boolean;
    /** About to fill. Replaces a spinner, which would flash for two frames and read as an error. */
    awaiting?: boolean;
    testid?: string;
    trailing?: Snippet;
  }

  let {
    icon,
    name,
    tone = 'soft',
    meta,
    warning,
    secret = false,
    empty = false,
    awaiting = false,
    testid,
    trailing
  }: Props = $props();

  const wash: Record<string, string> = {
    ink: 'var(--neutral-wash)',
    soft: 'var(--neutral-wash)',
    faint: 'var(--neutral-wash)',
    accent: 'var(--accent-wash)',
    signal: 'var(--signal-wash)',
    caution: 'var(--caution-wash)',
    danger: 'var(--danger-wash)',
    binary: 'var(--binary-wash)',
    voice: 'var(--voice-wash)'
  };
</script>

<div
  class={cx(
    'tile',
    secret && 'tile--secret',
    empty && 'tile--empty',
    awaiting && 'tile--awaiting'
  )}
  style="--tile-ink: var(--{tone === 'soft' || tone === 'faint' || tone === 'ink'
    ? 'ink-soft'
    : tone}); --tile-wash: {wash[tone]}"
  data-testid={testid}
>
  <span class="tile__mark"><Icon name={icon} size={24} /></span>

  <div class="tile__text">
    <div class="tile__name">{name}</div>
    {#if meta}<div class="tile__meta">{@render meta()}</div>{/if}
  </div>

  <!-- One grid cell, however many controls a caller puts in it. Rendered straight into the tile,
       a snippet holding a button *and* a live region became a fourth column, and the column it took
       the width from was the text — which is how `0 B · Ed25519` came to wrap in a 79px slot. -->
  {#if trailing}<div class="tile__actions">{@render trailing()}</div>{/if}

  {#if warning}
    <p class="tile__warn">
      <Icon name="alert" size={16} />
      <span>{warning}</span>
    </p>
  {/if}
</div>
