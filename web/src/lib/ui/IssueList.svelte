<script lang="ts">
  /**
   * Everything wrong, grouped by the step that owns it, with each row a button that navigates
   * there and focuses the control. This is what `validate()` returning all issues rather than
   * throwing on the first is for.
   *
   * Empty state is a single tick. Nothing wrong needs no words.
   */
  import Icon from './Icon.svelte';
  import Mark from './Mark.svelte';
  import { fieldSource } from './field-context';
  import type { IconRef } from './icons';
  import type { UiIssue } from './types';

  interface Props {
    issues: readonly UiIssue[];
    /** Which step an issue belongs to. Unmapped paths should come back as the document group. */
    groupOf?: (issue: UiIssue) => { id: string; label: string; icon?: IconRef };
    /** Humanised field name, e.g. `consent.summary` → "Consent summary". */
    fieldLabel?: (path: string) => string;
    message?: (issue: UiIssue) => string;
    emptyLabel?: string;
    onjump?: (issue: UiIssue) => void;
  }

  let { issues, groupOf, fieldLabel, message, emptyLabel, onjump }: Props = $props();

  const source = fieldSource();
  const say = $derived(message ?? source.message);

  interface Group {
    id: string;
    label: string;
    icon?: IconRef;
    rows: UiIssue[];
  }

  const grouped = $derived.by<Group[]>(() => {
    if (!groupOf) return issues.length ? [{ id: 'all', label: '', rows: [...issues] }] : [];
    const order: Group[] = [];
    for (const issue of issues) {
      const key = groupOf(issue);
      let group = order.find((candidate) => candidate.id === key.id);
      if (!group) {
        group = { ...key, rows: [] };
        order.push(group);
      }
      group.rows.push(issue);
    }
    return order;
  });
</script>

{#if issues.length === 0}
  <p class="issues__clear">
    <Mark kind="check" tone="signal" size={20} />
    {#if emptyLabel}<span>{emptyLabel}</span>{/if}
  </p>
{:else}
  <div class="issues">
    {#each grouped as group (group.id)}
      <div class="issues__group">
        {#if group.label}
          <p class="issues__heading">
            {#if group.icon}<Icon name={group.icon} size={14} tone="faint" />{/if}
            <span>{group.label}</span>
          </p>
        {/if}
        {#each group.rows as issue (issue.path + issue.code)}
          <button
            class="issues__row"
            type="button"
            onclick={() => onjump?.(issue)}
            data-testid={`issue-${issue.code}`}
          >
            <span>{fieldLabel ? fieldLabel(issue.path) : issue.path}</span>
            <span class="faint">{say(issue)}</span>
            <span class="issues__path">{issue.path}</span>
          </button>
        {/each}
      </div>
    {/each}
  </div>
{/if}
