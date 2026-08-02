<script lang="ts">
  /**
   * A mark and a sentence, on a wash.
   *
   * This is where the one unavoidable sentence goes — the places on the site where a picture
   * genuinely cannot carry the meaning, because the thing being said is about consequence rather
   * than state. The mark says *stop* or *note*; the sentence says *what*.
   */
  import Icon from './Icon.svelte';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { Snippet } from 'svelte';

  interface Props {
    icon?: IconRef;
    tone?: 'neutral' | 'accent' | 'caution' | 'danger' | 'signal' | 'voice' | 'plain';
    text?: string;
    /** So a control elsewhere can point at this sentence with `aria-describedby`. A consequence
     *  said once for a section is still the description of every control that causes it. */
    id?: string;
    class?: string;
    children?: Snippet;
  }

  let { icon, tone = 'neutral', text, id, class: extra, children }: Props = $props();

  const iconTone = $derived(
    tone === 'caution'
      ? 'caution'
      : tone === 'danger'
        ? 'danger'
        : tone === 'signal'
          ? 'signal'
          : tone === 'accent'
            ? 'accent'
            : tone === 'voice'
              ? 'voice'
              : 'faint'
  );
</script>

<p {id} class={cx('note', tone !== 'neutral' && `note--${tone}`, extra)}>
  {#if icon}<Icon name={icon} size={16} tone={iconTone} />{/if}
  <span>{#if children}{@render children()}{:else}{text}{/if}</span>
</p>
