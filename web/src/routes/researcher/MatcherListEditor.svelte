<script lang="ts">
  import type { EventMatcher } from '$lib/particeps/types';
  import type { ConditionKind } from '$lib/particeps/generated/event-source-registry';
  import Button from '$lib/ui/Button.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import EventMatcherEditor from './EventMatcherEditor.svelte';
  import { createEventMatcher, moveEditorItem } from './editor-model';
  let { value, path, label, locale, kind = 'EVENT_MATCH', ordered = false, min = 1 }: { value: EventMatcher[]; path: string; label: string; locale: 'en' | 'zh-TW'; kind?: ConditionKind; ordered?: boolean; min?: number } = $props();
  const zh = $derived(locale === 'zh-TW');
</script>
<div class="stack" data-issue-host={path}>
  <strong>{label}</strong>
  {#each value as matcher, index (matcher)}
    <div class="matcher-row">
      <div class="row">
        <span>{label} {index + 1}</span>
        {#if ordered}
          <IconButton icon="chevron" label={zh ? '事件往前移' : 'Move event earlier'} disabled={index === 0} onclick={() => moveEditorItem(value, index, -1)} />
          <IconButton icon="chevron-down" label={zh ? '事件往後移' : 'Move event later'} disabled={index === value.length - 1} onclick={() => moveEditorItem(value, index, 1)} />
        {/if}
        <IconButton icon="trash" label={zh ? '移除事件' : 'Remove event'} variant="danger" disabled={value.length <= min} onclick={() => value.splice(index, 1)} />
      </div>
      <EventMatcherEditor value={matcher} path={`${path}.${index}`} {locale} {kind} onchange={(next) => value[index] = next} />
    </div>
  {/each}
  <Button label={zh ? '新增事件' : 'Add event'} icon="plus" variant="ghost" disabled={value.length >= 16} onclick={() => value.push(createEventMatcher(kind))} />
</div>
<style>
  .matcher-row { display: grid; gap: var(--sp-4); padding-block: var(--sp-3); border-block-start: var(--line-hair) solid var(--rule); }
</style>
