<script lang="ts">
  /** A bounded string. The bound is drawn as a filling line rather than stated, until it matters. */
  import type { FullAutoFill } from 'svelte/elements';
  import Field from './Field.svelte';
  import { fieldSource } from './field-context';

  interface Props {
    label: string;
    value: string;
    max: number;
    path?: string;
    hint?: string;
    multiline?: boolean;
    rows?: number;
    mono?: boolean;
    placeholder?: string;
    autocomplete?: FullAutoFill;
    inputmode?: 'text' | 'email' | 'url' | 'numeric';
    onchange: (value: string) => void;
  }

  let {
    label,
    value,
    max,
    path,
    hint,
    multiline = false,
    rows = 5,
    mono = false,
    placeholder,
    autocomplete,
    inputmode,
    onchange
  }: Props = $props();

  const source = fieldSource();

  function blur() {
    if (path) source.touch?.(path);
  }
</script>

<Field {label} {path} {hint} counter={{ value: value.length, max }}>
  {#snippet children({ id, describedby, invalid })}
    {#if multiline}
      <textarea
        class={mono ? 'input input--area input--mono' : 'input input--area'}
        {id}
        {rows}
        {placeholder}
        maxlength={max}
        aria-describedby={describedby}
        aria-invalid={invalid || undefined}
        {value}
        oninput={(event) => onchange(event.currentTarget.value)}
        onblur={blur}
      ></textarea>
    {:else}
      <input
        class={mono ? 'input input--mono' : 'input'}
        type="text"
        {id}
        {placeholder}
        {autocomplete}
        {inputmode}
        maxlength={max}
        aria-describedby={describedby}
        aria-invalid={invalid || undefined}
        {value}
        oninput={(event) => onchange(event.currentTarget.value)}
        onblur={blur}
      />
    {/if}
  {/snippet}
</Field>
