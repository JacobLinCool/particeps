<script lang="ts">
  /**
   * The three hand-off columns, left to right in order of how far the thing travels: hold, store,
   * send. The spatial ordering plus the colour temperature carries the secrecy classification, so
   * no tile needs a badge saying what kind of thing it is.
   *
   * `hold` draws its edge hatched in --danger. There is no download-all here, by design.
   */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Destination } from './types';
  import type { Snippet } from 'svelte';

  interface Props {
    destination: Destination;
    icon: IconRef;
    title: string;
    /** How many of this group's artefacts have been written to disk in this session. */
    saved: number;
    total: number;
    hint?: string;
    empty?: boolean;
    children: Snippet;
  }

  let { destination, icon, title, saved, total, hint, empty = false, children }: Props = $props();

  const tone = $derived(
    destination === 'hold' ? 'danger' : destination === 'send' ? 'accent' : 'faint'
  );
</script>

<section
  class={cx('group', `group--${destination}`, empty && 'group--empty')}
  aria-label={title}
  data-testid={`group-${destination}`}
>
  <header class="group__head">
    <Icon name={icon} size={18} {tone} />
    <span>{title}</span>
    <span class="group__tally">{saved} / {total}</span>
  </header>

  {#if hint}<p class="fine faint">{hint}</p>{/if}

  <div class="stack stack--tight">{@render children()}</div>
</section>
