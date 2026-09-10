<script lang="ts">
  import Field from '$lib/ui/Field.svelte';
  import { fieldSource } from '$lib/ui/field-context';
  let { label, value, path, onchange }: {
    label: string; path: string; value: number | null; onchange: (value: number | null) => void;
  } = $props();
  const source = fieldSource();
</script>

<Field {label} {path}>
  {#snippet children({ id, describedby, invalid })}
    <input
      class="input input--mono"
      type="number"
      {id}
      aria-describedby={describedby}
      aria-invalid={invalid || undefined}
      onblur={() => source.touch?.(path)}
      min="1"
      max="1000000"
      step="1"
      value={value ?? ''}
      placeholder="∞"
      oninput={(event) => {
        const raw = event.currentTarget.value;
        if (raw === '') onchange(null);
        else if (Number.isFinite(event.currentTarget.valueAsNumber)) onchange(event.currentTarget.valueAsNumber);
      }}
    />
  {/snippet}
</Field>
