<script lang="ts">
  /**
   * The two pages, side by side. Each carries its own hue as ink plus a 2px underline: --accent on
   * the researcher page, --voice on the participant page.
   *
   * This is the only place the two hues appear together, which is what makes the pairing read as a
   * system rather than as two unrelated colours picked twice.
   */
  import Icon from './Icon.svelte';
  import type { PageId } from './types';

  interface Props {
    current: PageId | null;
    researcher: { href: string; label: string };
    participant: { href: string; label: string };
    /** Names the landmark. Without it three navigations on one page are indistinguishable. */
    label?: string;
  }

  let { current, researcher, participant, label }: Props = $props();
</script>

<nav class="switcher" aria-label={label}>
  <a
    class="switcher__item"
    href={researcher.href}
    aria-current={current === 'researcher' ? 'page' : undefined}
  >
    <Icon name="researcher" size={18} />
    <span class="switcher__label">{researcher.label}</span>
  </a>
  <a
    class="switcher__item switcher__item--voice"
    href={participant.href}
    aria-current={current === 'participant' ? 'page' : undefined}
  >
    <Icon name="participant" size={18} />
    <span class="switcher__label">{participant.label}</span>
  </a>
</nav>
