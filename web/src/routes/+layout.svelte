<script lang="ts">
  /**
   * The shell: the stylesheet that decides both themes, and the one control that is always
   * reachable. Everything else belongs to the page — a landing whose whole job is a two-way
   * choice cannot afford chrome offering the same two choices above it.
   */
  import '../app.css';
  import LanguageControl from '$lib/ui/LanguageControl.svelte';
  import type { Snippet } from 'svelte';

  let { children }: { children: Snippet } = $props();
</script>

<div class="page">
  <div class="shell">
    <div class="wrap shell__bar"><LanguageControl /></div>
  </div>

  <!-- A div, not a `main`. Each route owns its own `main`, and a page that brings a header and a
       footer needs them outside it or neither is exposed as `banner` or `contentinfo`. -->
  <div class="shell__main">{@render children()}</div>
</div>

<style>
  /* A page that brings its own header brings a language control with it, and two of them on one
     screen is one too many. The bar yields rather than the header having to know about it. */
  .page:has(:global(.site-header)) .shell {
    display: none;
  }

  .shell__bar {
    display: flex;
    justify-content: flex-end;
    padding-block: var(--sp-4);
  }

  .shell__main {
    display: flex;
    flex-direction: column;
    flex: 1;
  }
</style>
