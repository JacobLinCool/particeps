<script lang="ts">
  /** Six one-line reasons to stop. This is the only place on the page in the error colour, which
   *  is what makes it worth reading. */
  import Mark from '$lib/ui/Mark.svelte';
  import { m, type MessageKey } from './messages.svelte';

  interface Props {
    keys: readonly MessageKey[];
  }

  let { keys }: Props = $props();
</script>

<ul class="flags">
  {#each keys as key (key)}
    <li class="flags__row">
      <Mark kind="blocking" tone="danger" size={14} />
      <span>{m(key)}</span>
    </li>
  {/each}
</ul>

<style>
  .flags {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }

  .flags__row {
    display: flex;
    align-items: start;
    gap: var(--sp-5);
    padding: var(--sp-4) var(--sp-5);
    background: var(--surface);
    border-inline-start: var(--line-solid) solid var(--danger);
    border-radius: 0 var(--r-field) var(--r-field) 0;
    font-size: var(--type-fine);
  }
</style>
