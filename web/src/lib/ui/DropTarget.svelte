<script lang="ts">
  /**
   * A real button that opens a file picker. Drop is an enhancement layered on top, never the only
   * route in — a drag is not available to a keyboard, and on a phone it is not available at all.
   *
   * The file is handed over unread: a private key wants text, a `.partcfg` wants bytes, and this
   * component has no business deciding which.
   */
  import Icon from './Icon.svelte';
  import type { IconRef } from './icons';

  interface Props {
    label: string;
    /** The filename this target expects, shown in monospace. It is a fact, not an instruction. */
    filename?: string;
    accept?: string;
    icon?: IconRef;
    multiple?: boolean;
    testid?: string;
    onfile: (file: File) => void;
  }

  let { label, filename, accept, icon = 'import', multiple = false, testid, onfile }: Props =
    $props();

  let input: HTMLInputElement | undefined = $state();
  let over = $state(false);

  function take(list: FileList | null | undefined) {
    if (!list) return;
    for (const file of Array.from(list)) onfile(file);
  }
</script>

<button
  class="drop"
  type="button"
  data-over={over}
  data-testid={testid}
  onclick={() => input?.click()}
  ondragover={(event) => {
    event.preventDefault();
    over = true;
  }}
  ondragleave={() => (over = false)}
  ondrop={(event) => {
    event.preventDefault();
    over = false;
    take(event.dataTransfer?.files);
  }}
>
  <Icon name={icon} size={18} tone="faint" />
  <span>{label}</span>
  {#if filename}<span class="drop__name">{filename}</span>{/if}
</button>

<input
  bind:this={input}
  class="sr"
  type="file"
  {accept}
  {multiple}
  tabindex="-1"
  aria-hidden="true"
  onchange={(event) => {
    take(event.currentTarget.files);
    event.currentTarget.value = '';
  }}
/>
