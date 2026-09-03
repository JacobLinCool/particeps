<script lang="ts">
  import Field from '$lib/ui/Field.svelte';

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
      aria-describedby={describedby}
      aria-invalid={invalid || undefined}
      oninput={(event) => {
        const next = event.currentTarget.valueAsNumber;
        if (Number.isSafeInteger(next)) onchange(next);
      }}
    />
  {/snippet}
</Field>
