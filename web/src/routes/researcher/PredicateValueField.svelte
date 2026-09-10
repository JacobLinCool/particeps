<script lang="ts">
  import type { RegistryFieldContract } from '$lib/particeps/generated/event-source-registry';
  import TextField from '$lib/ui/TextField.svelte';
  import Select from './EditorSelectField.svelte';
  let { value, field, path, locale, onchange }: { value: string; field: RegistryFieldContract; path: string; locale: 'en' | 'zh-TW'; onchange: (value: string) => void } = $props();
  const label = $derived(locale === 'zh-TW' ? '比較值' : 'Value');
  const values = $derived(field.wire_type === 'boolean' ? ['true', 'false'] : field.enum_values);
  const hint = $derived([field.wire_type, field.unit !== 'none' ? field.unit : '', field.minimum !== null ? `≥ ${field.minimum}` : '', field.maximum !== null ? `≤ ${field.maximum}` : ''].filter(Boolean).join(' · '));
</script>
{#if field.wire_type === 'enum' || field.wire_type === 'boolean'}
  <Select {label} {path} {value} {hint} options={values.map((item) => ({ value: item, label: item }))} {onchange} />
{:else}
  <TextField {label} {path} {value} {hint} max={field.maximum_length ?? 4_096} mono {onchange} />
{/if}
