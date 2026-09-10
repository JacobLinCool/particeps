<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import Field from '$lib/ui/Field.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import Note from '$lib/ui/Note.svelte';
  import { fieldSource } from '$lib/ui/field-context';
  import StateConditionEditor from './StateConditionEditor.svelte';
  import { trafficShapingEnabled, type ResourceBindingAutomation } from '$lib/particeps/types';
  import type { Draft } from './draft.svelte';

  let { draft, locale = 'en' }: { draft: Draft; locale?: 'en' | 'zh-TW' } = $props();
  const source = fieldSource();
  const configuration = $derived(draft.configuration);
  const bindings = $derived(configuration.automations.filter(
    (automation): automation is ResourceBindingAutomation => automation.type === 'resource_binding'
  ));
  const copy = $derived(locale === 'zh-TW' ? {
    note: '條件會依序判斷，第一個成立的條件決定設定；沒有條件成立時使用預設設定。',
    title: '資源規則', profile: '套用設定', default: '預設設定', add: '新增條件',
    up: '將條件往上移', down: '將條件往下移', remove: '移除條件',
    inactive: '停用',
    collectorSchedule: '每個資料來源可設定獨立時段；預設選「停用」可在時段外停止，或選另一設定降低頻率。「必要」來源在排程開啟時仍須成功收集。背景排程可能延遲。',
    gyroSchedule: '陀螺儀停用後會釋放持續喚醒鎖；只降低頻率仍會保持 CPU 喚醒。'
  } : {
    note: 'Cases run in order. The first true case selects a profile; otherwise use the default.',
    title: 'Resource rule', profile: 'Selected profile', default: 'Default profile', add: 'Add condition',
    up: 'Move condition up', down: 'Move condition down', remove: 'Remove condition',
    inactive: 'Inactive',
    collectorSchedule: 'Each source can use its own time windows. Set the default to Inactive to stop outside them, or select another profile for a lower rate. Required sources must still collect successfully when scheduled on. Background scheduling may be delayed.',
    gyroSchedule: 'Deactivating the gyroscope releases its continuous wake lock. Lowering its rate alone still keeps the CPU awake.'
  });

  function profileIds(binding: ResourceBindingAutomation): string[] {
    if (binding.resource.kind === 'collector') {
      return configuration.collectors.find((collector) => collector.id === binding.resource.id)?.profiles.map((profile) => profile.id) ?? [];
    }
    return trafficShapingEnabled(configuration.traffic_shaping)
      ? configuration.traffic_shaping.profiles.map((profile) => profile.id)
      : [];
  }

  function addCase(binding: ResourceBindingAutomation): void {
    binding.cases.push({ condition: { type: 'elapsed_at_least', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' }, profile_id: profileIds(binding)[0] ?? null });
  }

  function move(binding: ResourceBindingAutomation, index: number, direction: -1 | 1): void {
    const target = index + direction;
    if (target < 0 || target >= binding.cases.length) return;
    const [entry] = binding.cases.splice(index, 1); binding.cases.splice(target, 0, entry);
  }
</script>

<div class="stack" data-issue-host="automations">
  <Note icon="info" tone="plain" text={copy.note} />
  {#each bindings as binding (binding)}
    {@const automationIndex = configuration.automations.indexOf(binding)}
    {@const path = `automations.${automationIndex}`}
    {@const profiles = profileIds(binding)}
    <div class="binding" data-issue-host={path}>
      <div class="binding__identity">
        <IdField label={copy.title} path={`${path}.id`} value={binding.id} onchange={(value) => {
          binding.id = value; configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
        }} />
        <code>{binding.resource.kind}:{binding.resource.id}</code>
      </div>

      {#if binding.resource.kind === 'collector'}
        <Note icon="info" tone="plain" text={copy.collectorSchedule} />
        {#if binding.resource.id === 'gyroscope.v1'}
          <Note icon="info" tone="plain" text={copy.gyroSchedule} />
        {/if}
      {/if}

      {#each binding.cases as entry, caseIndex (entry)}
        <div class="case" data-issue-host={`${path}.cases.${caseIndex}`}>
          <div class="case__order">
            <span class="num">{caseIndex + 1}</span>
            <IconButton icon="chevron" label={copy.up} disabled={caseIndex === 0} onclick={() => move(binding, caseIndex, -1)} />
            <IconButton icon="chevron-down" label={copy.down} disabled={caseIndex === binding.cases.length - 1} onclick={() => move(binding, caseIndex, 1)} />
            <IconButton icon="trash" label={copy.remove} variant="danger" disabled={binding.cases.length <= 1} onclick={() => binding.cases.splice(caseIndex, 1)} />
          </div>
          <StateConditionEditor value={entry.condition} path={`${path}.cases.${caseIndex}.condition`} {locale} durationHours={configuration.duration_hours} onchange={(next) => entry.condition = next} />
          <Field label={copy.profile} path={`${path}.cases.${caseIndex}.profile_id`}>
            {#snippet children({ id, describedby, invalid })}
              <select class="input" {id} aria-describedby={describedby} aria-invalid={invalid || undefined} value={entry.profile_id ?? ''} onblur={() => source.touch?.(`${path}.cases.${caseIndex}.profile_id`)} onchange={(event) => (entry.profile_id = event.currentTarget.value || null)}>
                <option value="">{copy.inactive}</option>
                {#each profiles as profile (profile)}<option value={profile}>{profile}</option>{/each}
              </select>
            {/snippet}
          </Field>
        </div>
      {/each}

      <div class="binding__footer">
        <Button label={copy.add} icon="plus" variant="ghost" disabled={binding.cases.length >= 16} onclick={() => addCase(binding)} />
        <Field label={copy.default} path={`${path}.default_profile_id`}>
          {#snippet children({ id, describedby, invalid })}
            <select class="input" {id} aria-describedby={describedby} aria-invalid={invalid || undefined} onblur={() => source.touch?.(`${path}.default_profile_id`)} value={binding.default_profile_id ?? ''} onchange={(event) => (binding.default_profile_id = event.currentTarget.value || null)}>
              <option value="">{copy.inactive}</option>
              {#each profiles as profile (profile)}<option value={profile}>{profile}</option>{/each}
            </select>
          {/snippet}
        </Field>
      </div>
    </div>
  {/each}
</div>

<style>
  .binding { display: grid; gap: var(--sp-5); padding: var(--sp-5); border-block-start: var(--line-hair) solid var(--rule); }
  .binding__identity { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: var(--sp-5); align-items: end; }
  .case { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-4); align-items: end; padding-block-start: var(--sp-4); border-block-start: var(--line-hair) solid var(--rule); }
  .case__order { display: flex; align-items: center; gap: var(--sp-2); padding-block-end: var(--sp-3); }
  .binding__footer { display: grid; grid-template-columns: auto minmax(10rem, 1fr); gap: var(--sp-5); align-items: end; }
  @media (max-width: 44rem) { .case, .binding__identity, .binding__footer { grid-template-columns: 1fr; } }
</style>
