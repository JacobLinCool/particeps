<script lang="ts">
  /** Links out, and the one claim this page has to keep: it runs entirely in the browser. */
  import Icon from './Icon.svelte';

  interface Props {
    links?: readonly { href: string; label: string; external?: boolean }[];
    /** Names the footer's landmark, so it is not a third anonymous `navigation`. */
    linksLabel?: string;
    note?: string;
    /** The participant page's `For researchers →`: footer-only, so nobody is routed into
     *  researcher tooling from the body of the page. */
    aside?: { href: string; label: string };
  }

  let { links = [], linksLabel, note, aside }: Props = $props();
</script>

<footer class="site-footer">
  <div class="wrap stack stack--tight">
    {#if links.length}
      <nav class="site-footer__links" aria-label={linksLabel}>
        {#each links as link (link.href)}
          <a
            class="site-footer__link"
            href={link.href}
            rel={link.external ? 'noreferrer' : undefined}
            target={link.external ? '_blank' : undefined}
          >
            <span>{link.label}</span>
            {#if link.external}<Icon name="link-out" size={14} />{/if}
          </a>
        {/each}
      </nav>
    {/if}

    {#if note}<p class="fine">{note}</p>{/if}

    {#if aside}
      <a class="site-footer__link" href={aside.href}>
        <span>{aside.label}</span>
        <Icon name="arrow-right" size={14} />
      </a>
    {/if}
  </div>
</footer>
