<script lang="ts">
  import Field from '$lib/ui/Field.svelte';
  import { fieldSource } from '$lib/ui/field-context';

  let {
    label, value, min, max, path, onchange
  }: {
    label: string;
    value: number;
    min: number;
    max: number;
    path?: string;
    onchange: (value: number) => void;
  } = $props();
  const source = fieldSource();
</script>

<Field {label} {path}>
  {#snippet children({ id, describedby, invalid })}
    <input
      class="input input--mono"
      type="number"
      {id}
      {min}
      {max}
      step="1"
      {value}
      onblur={(event) => { if (!Number.isFinite(event.currentTarget.valueAsNumber)) event.currentTarget.value = String(value); if (path) source.touch?.(path); }}
      aria-describedby={describedby}
      aria-invalid={invalid || undefined}
      oninput={(event) => {
        const next = event.currentTarget.valueAsNumber;
        if (Number.isFinite(next)) onchange(next);
      }}
    />
  {/snippet}
</Field>
