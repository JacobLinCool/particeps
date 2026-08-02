<script lang="ts">
  /**
   * The drawing, one headline, one sentence, and the four chips. Nothing below the fold is needed
   * to reach the takeaway: it is collected on your phone, you see the full list before you agree,
   * you check one fingerprint, you can stop at any time.
   *
   * The disclaimer sits here rather than in the footer because it frames everything after it.
   */
  import GlanceRow from './GlanceRow.svelte';
  import PhoneDiagram from './PhoneDiagram.svelte';
  import { GLANCE } from './content';
  import { m } from './messages.svelte';
</script>

<div class="lede-block">
  <div class="lede-block__text">
    <h1>{m('hero.title')}</h1>
    <p class="lede">{m('hero.lead')}</p>
    <GlanceRow items={GLANCE} />
    <p class="fine faint">{m('hero.disclaimer')}</p>
  </div>

  <!-- `both`, because which of the two a reader has is a property of their study rather than of
       this app. The drawing that shows one arrow is section 4's, where the reader has been told how
       to find out which one is theirs. -->
  <PhoneDiagram
    inbound={['motion', 'location', 'keyboard']}
    outbound="both"
    captionKey="hero.caption"
  />
</div>

<style>
  .lede-block {
    display: grid;
    gap: var(--sp-8);
    align-items: center;
    padding-block: var(--sp-9) var(--sp-8);
  }

  @media (min-width: 54rem) {
    .lede-block {
      grid-template-columns: 1fr minmax(0, 21rem);
    }
  }

  .lede-block__text {
    display: flex;
    flex-direction: column;
    gap: var(--sp-6);
    align-items: start;
  }
</style>
