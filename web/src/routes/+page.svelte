<script lang="ts">
  /**
   * The fork. A visitor has come here as one of two people and the page has to settle which
   * before it says anything else, so the two choices are the page: equal footprint, equal weight,
   * and separated on three channels at once — the mark, the hue, and the shape of the plate the
   * mark sits in. Square and accent is the researcher, round and voice is the participant, which
   * is the pairing `PageSwitcher` uses everywhere else on the site.
   *
   * The role is the caption. The line under it is the moment each reader is actually in, because
   * "Researcher" alone is guessable only by someone who already knows which tool this is.
   */
  import { base } from '$app/paths';
  import Icon from '$lib/ui/Icon.svelte';
  import SiteFooter from '$lib/ui/SiteFooter.svelte';
  import { i18n } from '$lib/ui/i18n.svelte';

  const REPOSITORY = 'https://github.com/JacobLinCool/android-data-collector';
  const doc = (name: string) => `${REPOSITORY}/blob/main/docs/${name}.md`;

  const links = $derived([
    { href: doc('researcher-guide'), label: i18n.m.link.researcherGuide, external: true },
    { href: doc('participant-guide'), label: i18n.m.link.participantGuide, external: true },
    { href: doc('threat-model'), label: i18n.m.link.threatModel, external: true },
    { href: REPOSITORY, label: i18n.m.link.source, external: true }
  ]);
</script>

<svelte:head>
  <title>{i18n.m.app.name}</title>
  <meta name="description" content={i18n.m.app.tagline} />
</svelte:head>

<main class="wrap landing">
  <div class="hero">
    <Icon name="seal" size={56} tone="accent" class="hero__mark" />
    <h1>{i18n.m.app.name}</h1>
    <p class="hero__tagline">{i18n.m.app.tagline}</p>
  </div>

  <nav class="fork" aria-label={i18n.m.app.tagline}>
    <a class="choice" href="{base}/researcher/">
      <span class="choice__plate"><Icon name="researcher" size={40} /></span>
      <span class="choice__role">{i18n.m.app.nav.researcher}</span>
      <span class="choice__act">{i18n.m.researcher.title}</span>
    </a>

    <a class="choice choice--voice" href="{base}/participant/">
      <span class="choice__plate"><Icon name="participant" size={40} /></span>
      <span class="choice__role">{i18n.m.app.nav.participant}</span>
      <span class="choice__act">{i18n.m.participant.title}</span>
    </a>
  </nav>
</main>

<SiteFooter {links} linksLabel={i18n.m.app.name} />

<style>
  .landing {
    display: flex;
    flex-direction: column;
    justify-content: center;
    flex: 1;
    max-inline-size: 52rem;
    padding-block: var(--sp-8) var(--sp-10);
    gap: var(--sp-10);
  }

  .hero {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-5);
    text-align: center;
  }

  .hero :global(.hero__mark) {
    animation: seal-in var(--motion-settle) backwards;
  }

  .hero__tagline {
    font-size: var(--type-lede);
    color: var(--ink-faint);
    margin-inline: auto;
  }

  @keyframes seal-in {
    from {
      opacity: 0;
      scale: 0.8;
    }
  }

  .fork {
    display: grid;
    grid-template-columns: 1fr;
    gap: var(--sp-6);
  }

  @media (min-width: 40rem) {
    .fork {
      grid-template-columns: 1fr 1fr;
      gap: var(--sp-7);
    }
  }

  .choice {
    --hue: var(--accent);
    --hue-wash: var(--accent-wash);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-5);
    padding: var(--sp-9) var(--sp-7);
    background: var(--surface);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
    box-shadow: var(--shadow-rest);
    color: var(--ink);
    text-align: center;
    text-decoration: none;
    animation: choice-in var(--motion-panel) backwards;
    transition:
      translate var(--motion-settle),
      border-color var(--motion-state),
      box-shadow var(--motion-settle);
  }

  .choice--voice {
    --hue: var(--voice);
    --hue-wash: var(--voice-wash);
  }

  .choice:hover {
    translate: 0 -3px;
    border-color: var(--hue);
    box-shadow: var(--shadow-lift);
  }

  .choice:active {
    translate: 0 -1px;
  }

  /* Square against round, so the two read apart with the hue channel switched off. */
  .choice__plate {
    display: grid;
    place-items: center;
    inline-size: 84px;
    block-size: 84px;
    border-radius: var(--r-panel);
    background: var(--hue-wash);
    color: var(--hue);
    transition:
      scale var(--motion-settle),
      background-color var(--motion-state);
  }

  .choice--voice .choice__plate {
    border-radius: var(--r-pill);
  }

  .choice:hover .choice__plate {
    scale: 1.06;
  }

  .choice__role {
    font-size: var(--type-title);
    font-weight: var(--w-bold);
    line-height: var(--lh-tight);
  }

  .choice__act {
    font-size: var(--type-fine);
    color: var(--ink-faint);
  }

  @keyframes choice-in {
    from {
      opacity: 0;
      translate: 0 10px;
    }
  }

  /* The stagger is the only thing here that would still be waiting after the tokens collapse
     every duration to a millisecond, so it is the only thing that asks first. */
  @media (prefers-reduced-motion: no-preference) {
    .choice--voice {
      animation-delay: 70ms;
    }
  }
</style>
