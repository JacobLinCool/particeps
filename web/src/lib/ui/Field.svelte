<script lang="ts">
  /**
   * The shell every control sits in: label, hint, counter, and whatever is wrong.
   *
   * Issues arrive through context rather than props (see `field-context.ts`), and the source has
   * already decided whether this path's issues are visible — a field marks itself on blur or on a
   * sign attempt, never while it has focus. The children snippet receives the ids it needs, so a
   * custom body still wires `aria-describedby` correctly without the shell reaching into it.
   */
  import Icon from './Icon.svelte';
  import { fieldSource } from './field-context';
  import { cx } from './format';
  import type { IconRef } from './icons';
  import type { UiIssue } from './types';
  import type { Snippet } from 'svelte';

  export interface FieldParts {
    id: string;
    /** For a body that is not one labelable element: name it with `aria-labelledby`. */
    labelId: string;
    describedby: string | undefined;
    invalid: boolean;
  }

  interface Props {
    label: string;
    /** The schema path this control owns. Omit for controls with no schema home. */
    path?: string;
    hint?: string;
    icon?: IconRef;
    /** Bound-relative fill. The number shows only past 75%; below that the line is the answer. */
    counter?: { value: number; max: number };
    /** Overrides context, for the rare control whose issues are not path-addressed. */
    issues?: readonly UiIssue[];
    /** Nothing is wrong, but something is worth knowing. Never blocks. */
    advisory?: string | null;
    /** Extra schema paths this shell answers for, so an issue row has somewhere to land. */
    issueHost?: readonly (string | undefined)[];
    /**
     * The body is a group of controls rather than one control. `for` has nothing to point at then:
     * a `role="radiogroup"` div is not labelable, and putting the id on the first control inside it
     * makes the field's label that control's accessible name instead of its own. Such a body takes
     * `labelId` and names itself, and the label is drawn without a `for` that would dangle.
     */
    group?: boolean;
    class?: string;
    children: Snippet<[FieldParts]>;
  }

  let {
    label,
    path,
    hint,
    icon,
    counter,
    issues,
    advisory,
    issueHost,
    group = false,
    class: extra,
    children
  }: Props = $props();

  const source = fieldSource();
  const uid = $props.id();

  const shown = $derived(issues ?? (path ? source.issues(path) : []));
  const advice = $derived(advisory ?? (path && source.advisory ? source.advisory(path) : null));
  const invalid = $derived(shown.length > 0);

  const id = $derived(`${uid}-control`);
  const labelId = $derived(`${uid}-label`);
  const hintId = $derived(`${uid}-hint`);
  const issueId = $derived(`${uid}-issue`);

  const describedby = $derived(
    [hint ? hintId : null, invalid ? issueId : null].filter(Boolean).join(' ') || undefined
  );

  const ratio = $derived(counter && counter.max > 0 ? counter.value / counter.max : 0);
  const level = $derived(ratio > 1 ? 'over' : ratio > 0.9 ? 'near' : 'under');
</script>

<div
  class={cx('field', invalid && 'field--invalid', !invalid && advice && 'field--advisory', extra)}
  data-testid={path ? `field-${path}` : undefined}
  data-issue-host={issueHost?.filter(Boolean).join(' ') || undefined}
>
  <div class="field__head">
    {#if icon}<Icon name={icon} size={16} tone="faint" />{/if}
    {#if group}
      <span class="field__label" id={labelId}>{label}</span>
    {:else}
      <label class="field__label" id={labelId} for={id}>{label}</label>
    {/if}
    {#if counter}
      <span class="field__counter">
        {#if ratio > 0.75}
          <span class="num">{counter.value} / {counter.max}</span>
        {/if}
      </span>
    {/if}
  </div>

  {@render children({ id, labelId, describedby, invalid })}

  {#if counter}
    <div class="counterline" aria-hidden="true">
      <div
        class="counterline__fill"
        data-level={level}
        style="transform: scaleX({Math.min(1, ratio)})"
      ></div>
    </div>
  {/if}

  {#if hint}
    <p class="field__hint" id={hintId}>{hint}</p>
  {/if}

  {#if invalid}
    <div class="field__note field__note--issue" id={issueId}>
      <Icon name="alert" size={14} tone="danger" />
      <span>
        {#each shown as issue (issue.path + issue.code)}
          <span data-testid={`issue-${issue.code}`}>{source.message(issue)}</span>
        {/each}
      </span>
    </div>
  {:else if advice}
    <div class="field__note field__note--advisory">
      <Icon name="info" size={14} tone="caution" />
      <span>{advice}</span>
    </div>
  {/if}
</div>
