<script lang="ts">
  /** Brand, page switcher, language, and whatever the page needs beside them (the researcher
   *  page puts its load-a-configuration target here). Sticky, because the language control has to
   *  stay reachable from anywhere on a long page. */
  import PageSwitcher from './PageSwitcher.svelte';
  import LocaleMenu from './LocaleMenu.svelte';
  import { i18n } from './i18n.svelte';
  import type { PageId } from './types';
  import type { Snippet } from 'svelte';

  interface Props {
    current: PageId | null;
    /** Usually `base + '/'`. */
    home?: string;
    researcherHref: string;
    participantHref: string;
    trailing?: Snippet;
  }

  let { current, home = '/', researcherHref, participantHref, trailing }: Props = $props();
</script>

<header class="site-header">
  <div class="wrap site-header__bar">
    <div class="site-header__brand">
      <a class="site-header__name" href={home}>{i18n.m.app.name}</a>
      <span class="site-header__tagline">{i18n.m.app.tagline}</span>
    </div>

    <PageSwitcher
      {current}
      label={i18n.m.app.name}
      researcher={{ href: researcherHref, label: i18n.m.app.nav.researcher }}
      participant={{ href: participantHref, label: i18n.m.app.nav.participant }}
    />

    {#if trailing}{@render trailing()}{/if}

    <LocaleMenu />
  </div>
</header>
