<script lang="ts">
  /**
   * The static build ships this as `404.html`, which is the only way a visitor reaches it: there
   * is no server left to fail. So the status is drawn as a figure rather than described, and the
   * one sentence is about the address, not about the site.
   *
   * A status other than 404 can only come from SvelteKit itself, and its message is an English
   * diagnostic rather than prose — it is set in mono to say so.
   */
  import { base } from '$app/paths';
  import { page } from '$app/state';
  import Button from '$lib/ui/Button.svelte';
  import Icon from '$lib/ui/Icon.svelte';
  import { i18n } from '$lib/ui/i18n.svelte';
</script>

<svelte:head>
  <title>{page.status} · {i18n.m.app.name}</title>
</svelte:head>

<main class="wrap fault">
  <Icon name="alert" size={32} tone="caution" />
  <p class="fault__code num">{page.status}</p>

  {#if page.status === 404}
    <h1 class="fault__line">{i18n.m.error.notFound}</h1>
  {:else if page.error?.message}
    <p class="fault__line mono">{page.error.message}</p>
  {/if}

  <Button href="{base}/" variant="primary" label={i18n.m.action.back} />
</main>

<style>
  .fault {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--sp-6);
    flex: 1;
    padding-block: var(--sp-10);
    text-align: center;
    animation: fault-in var(--motion-settle) backwards;
  }

  .fault__code {
    font-family: var(--font-mono);
    font-size: 3.5rem;
    line-height: 1;
    color: var(--ink-faint);
  }

  .fault__line {
    font-size: var(--type-lede);
    font-weight: var(--w-medium);
    color: var(--ink-soft);
  }

  @keyframes fault-in {
    from {
      opacity: 0;
    }
  }
</style>
