<script lang="ts" generics="T extends string">
  /**
   * A subset. `transports` is the only one, and its failure mode decides the drawing: an empty
   * set is invalid, so every chip goes red rather than a message appearing under the group. The
   * problem is the set, not any one member of it.
   */
  import Field from './Field.svelte';
  import Icon from './Icon.svelte';
  import { fieldSource } from './field-context';
  import { cx } from './format';
  import type { IconRef } from './icons';

  interface Props {
    label: string;
    value: readonly T[];
    options: readonly { value: T; label: string; icon?: IconRef }[];
    path?: string;
    hint?: string;
    /** Below this, every chip draws as the violation. */
    min?: number;
    onchange: (value: T[]) => void;
  }

  let { label, value, options, path, hint, min = 0, onchange }: Props = $props();

  const source = fieldSource();
  const short = $derived(value.length < min || (path ? source.issues(path).length > 0 : false));

  function toggle(option: T) {
    onchange(value.includes(option) ? value.filter((v) => v !== option) : [...value, option]);
  }
</script>

<!-- `group`, for the same reason `ChoiceField` needs it: a `role="group"` row is not labelable, so
     `for` used to be parked on the first chip — and a `<label for>` beats a button's own contents in
     the naming algorithm, which left Wi-Fi announced as "Transports" and reachable under no name of
     its own. The row is named by the label's id; each chip keeps the name it draws. -->
<Field {label} {path} {hint} group>
  {#snippet children({ labelId, describedby })}
    <div class="row row--tight" role="group" aria-labelledby={labelId} aria-describedby={describedby}>
      {#each options as option (option.value)}
        {@const on = value.includes(option.value)}
        <button
          class={cx('chip', on && 'chip--selected', short && 'chip--danger')}
          type="button"
          aria-pressed={on}
          onclick={() => toggle(option.value)}
          data-testid={path ? `chip-${path}-${option.value}` : undefined}
        >
          <!-- Which chips are in the set was a tinted fill and nothing else, at roughly 1.1:1
               against the surface behind it. The glyph is the answer; the fill is the mood. -->
          {#if option.icon}<Icon name={on ? 'check' : option.icon} size={16} />{/if}
          <span>{option.label}</span>
        </button>
      {/each}
    </div>
  {/snippet}
</Field>
