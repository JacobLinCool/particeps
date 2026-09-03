<script lang="ts">
  /**
   * The participant page: one column, no tabs, no accordions, in the order the questions arrive.
   *
   * The setup section follows the five steps the phone asks in, so reading it is a rehearsal of
   * the app. The sections after it answer what the study records, where the data goes, and when a
   * participant should stop and ask for help.
   *
   * Nothing here depicts the app with a screenshot. A screenshot is a picture of English text a
   * Traditional Chinese reader cannot read, and it is wrong on the next release; every drawing on
   * this page is built from the glyph vocabulary the phone itself draws, so it renders in the
   * reader's own language and follows the theme.
   *
   * The header carries `site-header`, which is how the layout knows to drop its own language bar:
   * a page that brings its own header brings a language control with it.
   */
  import LanguageControl from '$lib/ui/LanguageControl.svelte';
  import Section from '$lib/ui/Section.svelte';
  import Hero from '$lib/participant/Hero.svelte';
  import SetupPreview from '$lib/participant/SetupPreview.svelte';
  import SourceGrid from '$lib/participant/SourceGrid.svelte';
  import DeliveryCard from '$lib/participant/DeliveryCard.svelte';
  import FlagList from '$lib/participant/FlagList.svelte';
  import PageFooter from '$lib/participant/PageFooter.svelte';
  import {
    FLAGS,
    PARTICIPANT_GUIDE,
    SOURCES
  } from '$lib/participant/content';
  import { m } from '$lib/participant/messages.svelte';
  import { i18n } from '$lib/ui/i18n.svelte';
</script>

<svelte:head>
  <title>{m('hero.title')} · {i18n.m.app.name}</title>
  <meta name="description" content={m('hero.lead')} />
</svelte:head>

<a class="skip" href="#main">{i18n.m.action.skip}</a>

<header class="site-header">
  <div class="wrap site-header__bar">
    <div class="site-header__brand">
      <span class="site-header__name">{i18n.m.app.name}</span>
      <span class="site-header__tagline">{i18n.m.participant.title}</span>
    </div>
    <LanguageControl />
  </div>
</header>

<main id="main" class="wrap">
  <Hero />

  <Section id="setup" icon="clock" title={m('setup.title')} lead={m('setup.lead')}>
    <SetupPreview />
  </Section>

  <Section id="collect" icon="sources" title={m('sources.title')} lead={m('sources.lead')}>
    <SourceGrid entries={SOURCES} />
    <p class="fine">
      <a href={PARTICIPANT_GUIDE} target="_blank" rel="noreferrer">{m('sources.moreLink')}</a>
    </p>
  </Section>

  <Section id="where" icon="lock" title={m('delivery.title')} lead={m('delivery.lead')}>
    <div class="pair">
      <DeliveryCard mode="local" />
      <DeliveryCard mode="upload" />
    </div>
    <div class="stack stack--tight">
      <p class="fine">{m('delivery.sealed')}</p>
      <p class="fine">{m('delivery.exportable')}</p>
    </div>
  </Section>

  <Section id="flags" icon="alert" title={m('flags.title')}>
    <FlagList keys={FLAGS} />
  </Section>

  <p class="coda">{m('coda')}</p>
</main>

<PageFooter />

<style>
  /* Always both, never a toggle: hiding one behind a control lets a visitor leave without ever
     seeing the upload case, and an absence is not a disclosure. */
  .pair {
    display: grid;
    gap: var(--sp-6);
    align-items: stretch;
  }

  @media (min-width: 54rem) {
    .pair {
      grid-template-columns: 1fr 1fr;
    }
  }

  .coda {
    max-inline-size: 30rem;
    margin-inline: auto;
    padding-block: var(--sp-10);
    font-size: var(--type-title);
    line-height: var(--lh-tight);
    text-align: center;
    text-wrap: balance;
  }
</style>
