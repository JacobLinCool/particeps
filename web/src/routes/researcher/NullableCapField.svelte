<script lang="ts">
  import Field from '$lib/ui/Field.svelte';
  let { label, value, onchange }: {
    label: string; value: number | null; onchange: (value: number | null) => void;
  } = $props();
</script>

<Field {label}>
  {#snippet children({ id })}
    <input
      class="input input--mono"
      type="number"
      {id}
      min="1"
      max="1000000"
      step="1"
      value={value ?? ''}
      placeholder="∞"
      oninput={(event) => {
        const raw = event.currentTarget.value;
        if (raw === '') onchange(null);
        else if (Number.isSafeInteger(event.currentTarget.valueAsNumber)) onchange(event.currentTarget.valueAsNumber);
      }}
    />
  {/snippet}
</Field>
