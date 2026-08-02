<script lang="ts">
  /**
   * A whole number a researcher already knows exactly — a versionCode, not a position on a range
   * they are exploring, which is what `RangeField` is for.
   *
   * `Stepper` would be the shared component for this, but its two arrow buttons each need an
   * accessible name and the catalogue has no word for either direction. A bare number entry needs
   * no word beyond its own label.
   */
  import Field from '$lib/ui/Field.svelte';
  import { fieldSource } from '$lib/ui/field-context';
  import type { IconRef } from '$lib/ui/icons';

  interface Props {
    label: string;
    value: number;
    min: number;
    path?: string;
    hint?: string;
    icon?: IconRef;
    onchange: (value: number) => void;
  }

  let { label, value, min, path, hint, icon, onchange }: Props = $props();

  const source = fieldSource();
  let draft = $state<string | null>(null);

  /** Never rewritten mid-keystroke: deleting a digit to type another would fight the clamp. */
  function commit() {
    if (draft !== null) {
      const parsed = Number(draft);
      if (Number.isFinite(parsed)) onchange(Math.max(min, Math.round(parsed)));
      draft = null;
    }
    if (path) source.touch?.(path);
  }
</script>

<Field {label} {path} {hint} {icon}>
  {#snippet children({ id, describedby, invalid })}
    <input
      class="input input--num"
      type="number"
      inputmode="numeric"
      {id}
      {min}
      step="1"
      aria-describedby={describedby}
      aria-invalid={invalid || undefined}
      value={draft ?? String(value)}
      oninput={(event) => {
        draft = event.currentTarget.value;
      }}
      onblur={commit}
    />
  {/snippet}
</Field>
