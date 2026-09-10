<script lang="ts" generics="T extends string">
  import Field from '$lib/ui/Field.svelte';
  import { fieldSource } from '$lib/ui/field-context';
  let { label, value, path, hint, options, onchange }: { label: string; value: T; path: string; hint?: string; options: readonly { value: T; label: string }[]; onchange: (value: T) => void } = $props();
  const source = fieldSource();
</script>
<Field {label} {path} {hint}>
  {#snippet children({ id, describedby, invalid })}
    <select class="input" {id} aria-describedby={describedby} aria-invalid={invalid || undefined} {value} onchange={(event) => onchange(event.currentTarget.value as T)} onblur={() => source.touch?.(path)}>
      {#if !options.some((option) => option.value === value)}<option {value}>{value}</option>{/if}
      {#each options as option (option.value)}<option value={option.value}>{option.label}</option>{/each}
    </select>
  {/snippet}
</Field>
