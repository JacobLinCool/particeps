<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import Field from '$lib/ui/Field.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import Note from '$lib/ui/Note.svelte';
  import NumberField from './AutomationNumberField.svelte';
  import { trafficShapingEnabled, type EventMatcher, type ResourceBindingAutomation, type StateCondition } from '$lib/particeps/types';
  import type { Draft } from './draft.svelte';

  let { draft, locale = 'en' }: { draft: Draft; locale?: 'en' | 'zh-TW' } = $props();
  const configuration = $derived(draft.configuration);
  const bindings = $derived(configuration.automations.filter(
    (automation): automation is ResourceBindingAutomation => automation.type === 'resource_binding'
  ));
  const copy = $derived(locale === 'zh-TW' ? {
    note: '條件會依序判斷，第一個成立的條件決定設定；沒有條件成立時使用預設設定。',
    title: '資源規則', case: '條件', profile: '套用設定', default: '預設設定', add: '新增條件',
    active: '研究進行中', elapsed: '已經過一段時間', app: '持續使用目標 App',
    up: '將條件往上移', down: '將條件往下移', remove: '移除條件', seconds: '秒數',
    held: '持續秒數', inactive: '停用'
  } : {
    note: 'Cases run in order. The first true case selects a profile; otherwise use the default.',
    title: 'Resource rule', case: 'Condition', profile: 'Selected profile', default: 'Default profile', add: 'Add condition',
    active: 'Study session is active', elapsed: 'Elapsed time', app: 'Target app held in use',
    up: 'Move condition up', down: 'Move condition down', remove: 'Remove condition', seconds: 'Seconds',
    held: 'Held for seconds', inactive: 'Inactive'
  });

  function profileIds(binding: ResourceBindingAutomation): string[] {
    if (binding.resource.kind === 'collector') {
      return configuration.collectors.find((collector) => collector.id === binding.resource.id)?.profiles.map((profile) => profile.id) ?? [];
    }
    return trafficShapingEnabled(configuration.traffic_shaping)
      ? configuration.traffic_shaping.profiles.map((profile) => profile.id)
      : [];
  }

  function conditionKind(condition: StateCondition): 'active' | 'elapsed' | 'app' {
    if (condition.type === 'elapsed_at_least') return 'elapsed';
    if (condition.type === 'held_for' && condition.condition.type === 'keyed_presence') return 'app';
    return 'active';
  }

  function packageMatcher(type: 'ACTIVITY_RESUMED' | 'ACTIVITY_PAUSED' | 'ACTIVITY_STOPPED', packageName: string): EventMatcher {
    return { event: { source_id: 'usage_events.v1', schema_version: 1, event_type: type }, predicates: [
      { field: 'package_name', operator: 'eq', value: packageName }
    ] };
  }

  function condition(type: 'active' | 'elapsed' | 'app'): StateCondition {
    if (type === 'active') return { type: 'study_session_active' };
    if (type === 'elapsed') return { type: 'elapsed_at_least', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME' };
    const packageName = trafficShapingEnabled(configuration.traffic_shaping)
      ? configuration.traffic_shaping.target_packages[0]
      : 'com.example.app';
    return {
      type: 'held_for', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME',
      condition: {
        type: 'keyed_presence', key_field: 'activity_component_token',
        enter_when: [packageMatcher('ACTIVITY_RESUMED', packageName)],
        exit_when: [packageMatcher('ACTIVITY_PAUSED', packageName), packageMatcher('ACTIVITY_STOPPED', packageName)]
      }
    };
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

<div class="stack">
  <Note icon="info" tone="plain" text={copy.note} />
  {#each bindings as binding (binding)}
    {@const automationIndex = configuration.automations.indexOf(binding)}
    {@const path = `automations.${automationIndex}`}
    {@const profiles = profileIds(binding)}
    <div class="binding">
      <div class="binding__identity">
        <IdField label={copy.title} path={`${path}.id`} value={binding.id} onchange={(value) => {
          binding.id = value; configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
        }} />
        <code>{binding.resource.kind}:{binding.resource.id}</code>
      </div>

      {#each binding.cases as entry, caseIndex (entry)}
        <div class="case">
          <div class="case__order">
            <span class="num">{caseIndex + 1}</span>
            <IconButton icon="chevron" label={copy.up} disabled={caseIndex === 0} onclick={() => move(binding, caseIndex, -1)} />
            <IconButton icon="chevron-down" label={copy.down} disabled={caseIndex === binding.cases.length - 1} onclick={() => move(binding, caseIndex, 1)} />
            <IconButton icon="trash" label={copy.remove} variant="danger" onclick={() => binding.cases.splice(caseIndex, 1)} />
          </div>
          <Field label={copy.case} path={`${path}.cases.${caseIndex}.condition`}>
            {#snippet children({ id, describedby, invalid })}
              <select class="input" {id} aria-describedby={describedby} aria-invalid={invalid || undefined} value={conditionKind(entry.condition)} onchange={(event) => (entry.condition = condition(event.currentTarget.value as 'active' | 'elapsed' | 'app'))}>
                <option value="active">{copy.active}</option>
                <option value="elapsed">{copy.elapsed}</option>
                {#if binding.resource.kind === 'actuator'}<option value="app">{copy.app}</option>{/if}
              </select>
            {/snippet}
          </Field>
          {#if entry.condition.type === 'elapsed_at_least'}
            {@const elapsed = entry.condition}
            <NumberField label={copy.seconds} value={elapsed.duration_seconds} min={1} max={configuration.duration_hours * 3_600} onchange={(value) => (elapsed.duration_seconds = value)} />
          {:else if entry.condition.type === 'held_for'}
            {@const held = entry.condition}
            <NumberField label={copy.held} value={held.duration_seconds} min={1} max={configuration.duration_hours * 3_600} onchange={(value) => (held.duration_seconds = value)} />
          {/if}
          <Field label={copy.profile} path={`${path}.cases.${caseIndex}.profile_id`}>
            {#snippet children({ id, describedby, invalid })}
              <select class="input" {id} aria-describedby={describedby} aria-invalid={invalid || undefined} value={entry.profile_id ?? ''} onchange={(event) => (entry.profile_id = event.currentTarget.value || null)}>
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
          {#snippet children({ id })}
            <select class="input" {id} value={binding.default_profile_id ?? ''} onchange={(event) => (binding.default_profile_id = event.currentTarget.value || null)}>
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
  .binding { display: grid; gap: var(--sp-5); padding: var(--sp-5); border: var(--line-hair) solid var(--rule); border-radius: var(--r-panel); }
  .binding__identity { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: var(--sp-5); align-items: end; }
  .case { display: grid; grid-template-columns: auto minmax(10rem, 1fr) minmax(8rem, .7fr); gap: var(--sp-4); align-items: end; padding-block-start: var(--sp-4); border-block-start: var(--line-hair) solid var(--rule); }
  .case__order { display: flex; align-items: center; gap: var(--sp-2); padding-block-end: var(--sp-3); }
  .binding__footer { display: grid; grid-template-columns: auto minmax(10rem, 1fr); gap: var(--sp-5); align-items: end; }
  @media (max-width: 44rem) { .case, .binding__identity, .binding__footer { grid-template-columns: 1fr; } }
</style>
