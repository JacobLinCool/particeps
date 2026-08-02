<script lang="ts" generics="T extends string | number | boolean">
  /**
   * Two or three named alternatives, shown at once. A segmented control rather than a select,
   * because the whole point is that the options are visible without an act.
   *
   * Generic over the value, so `priority` and `required` share one control without either being
   * stringly typed at the call site.
   *
   * The roving tabindex is the whole radio-group contract, not half of it: `tabindex="-1"` on the
   * unselected option is what stops Tab landing on every segment, and it is only survivable because
   * the arrow keys move between them. Without the handler the unselected option is unreachable and
   * the control cannot be changed from a keyboard at all.
   */
  import Field from './Field.svelte';
  import Icon from './Icon.svelte';
  import type { IconRef } from './icons';

  interface Props {
    value: T;
    options: readonly { value: T; label: string; icon?: IconRef }[];
    /** Omit for a control whose meaning the options already carry (Required / Optional). */
    label?: string;
    path?: string;
    hint?: string;
    /** Accessible name when `label` is omitted. */
    groupLabel?: string;
    onchange: (value: T) => void;
  }

  let { value, options, label, path, hint, groupLabel, onchange }: Props = $props();

  let host: HTMLElement | undefined = $state();

  const selected = $derived(Math.max(0, options.findIndex((option) => option.value === value)));

  /** Arrow keys select as they move, which is what a radio group does everywhere else. */
  function move(to: number) {
    const next = (to + options.length) % options.length;
    onchange(options[next].value);
    host?.querySelectorAll<HTMLButtonElement>('.choice__opt')[next]?.focus();
  }

  function keydown(event: KeyboardEvent) {
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') move(selected + 1);
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') move(selected - 1);
    else if (event.key === 'Home') move(0);
    else if (event.key === 'End') move(options.length - 1);
    else return;
    event.preventDefault();
  }
</script>

{#snippet group(id: string | undefined, describedby: string | undefined)}
  <div
    bind:this={host}
    class="choice"
    role="radiogroup"
    aria-label={label ?? groupLabel}
    aria-describedby={describedby}
    tabindex={-1}
    onkeydown={keydown}
  >
    {#each options as option, index (String(option.value))}
      <button
        class="choice__opt"
        type="button"
        role="radio"
        id={index === 0 ? id : undefined}
        aria-checked={option.value === value}
        tabindex={index === selected ? 0 : -1}
        onclick={() => onchange(option.value)}
        data-testid={path ? `choice-${path}-${String(option.value)}` : undefined}
      >
        {#if option.icon}<Icon name={option.icon} size={16} />{/if}
        <span>{option.label}</span>
      </button>
    {/each}
  </div>
{/snippet}

{#if label}
  <Field {label} {path} {hint}>
    {#snippet children({ id, describedby })}
      {@render group(id, describedby)}
    {/snippet}
  </Field>
{:else}
  {@render group(undefined, undefined)}
{/if}
