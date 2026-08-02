<script lang="ts">
  /** A `Toggle` with a schema path, so it can carry a hint and an issue like any other field.
   *  The label stays on the switch — a switch with a separate label is two things to hit. */
  import Toggle from './Toggle.svelte';
  import Icon from './Icon.svelte';
  import { fieldSource } from './field-context';
  import { cx } from './format';

  interface Props {
    label: string;
    value: boolean;
    path?: string;
    hint?: string;
    caution?: boolean;
    disabled?: boolean;
    onchange: (value: boolean) => void;
  }

  let { label, value, path, hint, caution = false, disabled = false, onchange }: Props = $props();

  const source = fieldSource();
  const uid = $props.id();
  const shown = $derived(path ? source.issues(path) : []);
  const hintId = $derived(hint ? `${uid}-hint` : undefined);
</script>

<div
  class={cx('field', shown.length > 0 && 'field--invalid')}
  data-testid={path ? `field-${path}` : undefined}
>
  <Toggle
    {label}
    checked={value}
    {caution}
    {disabled}
    describedby={hintId}
    {onchange}
    testid={path ? `toggle-${path}` : undefined}
  />
  {#if hint}<p class="field__hint" id={hintId}>{hint}</p>{/if}
  {#each shown as issue (issue.code)}
    <div class="field__note field__note--issue" data-testid={`issue-${issue.code}`}>
      <Icon name="alert" size={14} tone="danger" />
      <span>{source.message(issue)}</span>
    </div>
  {/each}
</div>
