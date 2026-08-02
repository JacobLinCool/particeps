<script lang="ts">
  /**
   * The guide, the source, the one claim this page has to keep, and the researcher link.
   *
   * That link is footer-only. A participant is never routed into researcher tooling from the body
   * of the page, and there is no contact address here either: the research team is the contact,
   * and offering a second one invites a participant to route around them.
   */
  import SiteFooter from '$lib/ui/SiteFooter.svelte';
  import { PARTICIPANT_GUIDE, REPOSITORY } from './content';
  import { m } from './messages.svelte';
  import { base } from '$app/paths';
</script>

<SiteFooter
  links={[
    { href: PARTICIPANT_GUIDE, label: m('footer.guide'), external: true },
    { href: REPOSITORY, label: m('footer.source'), external: true }
  ]}
  note={m('footer.note')}
  aside={{ href: `${base}/researcher/`, label: m('footer.researchers') }}
/>
