<script lang="ts">
  /**
   * The drawing, one headline, one sentence, and two chips. Nothing below the fold is needed to
   * reach the takeaway: collection happens on your phone, you see the full list before agreeing,
   * and the consent screen says whether the study sends anything automatically.
   *
   * The disclaimer sits here rather than in the footer because it frames everything after it.
  */
  import Button from '$lib/ui/Button.svelte';
  import GlanceRow from './GlanceRow.svelte';
  import PhoneDiagram from './PhoneDiagram.svelte';
  import { ANDROID_APK_URL, GLANCE } from './content';
  import { m } from './messages.svelte';
</script>

<div class="lede-block">
  <div class="lede-block__text">
    <h1>{m('hero.title')}</h1>
    <p class="lede">{m('hero.lead')}</p>
    <Button
      href={ANDROID_APK_URL}
      variant="primary"
      icon="download"
      label={m('hero.download')}
      testid="download-android-app"
    />
    <GlanceRow items={GLANCE} />
    <!-- The name and its limits stay together: the first paragraph alone would read as a claim
         about who is in charge, which is the one thing the second paragraph exists to deny. -->
    <p class="fine">{m('hero.naming.name')}</p>
    <p class="fine">{m('hero.naming.limits')}</p>
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
