<script lang="ts">
  import Field from '$lib/ui/Field.svelte';
  import { fieldSource } from '$lib/ui/field-context';
  let { label, value, path, onchange }: { label: string; value: string; path: string; onchange: (value: string) => void } = $props();
  const source = fieldSource();
</script>
<Field {label} {path}>
  {#snippet children({ id, describedby, invalid })}
    <input class="input" type="time" {id} {value} aria-describedby={describedby} aria-invalid={invalid || undefined} oninput={(event) => onchange(event.currentTarget.value)} onblur={() => source.touch?.(path)} />
  {/snippet}
</Field>
