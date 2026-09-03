<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import Field from '$lib/ui/Field.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import Note from '$lib/ui/Note.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import { trafficShapingEnabled, type EventMatcher, type ResourceBindingAutomation, type TrafficShapingProfile } from '$lib/particeps/types';
  import type { Draft } from './draft.svelte';
  import NullableCap from './NullableCapField.svelte';

  let { draft, locale = 'en' }: { draft: Draft; locale?: 'en' | 'zh-TW' } = $props();
  const configuration = $derived(draft.configuration);
  const enabled = $derived(trafficShapingEnabled(configuration.traffic_shaping));
  const copy = $derived(locale === 'zh-TW' ? {
    enable: '調整指定 App 的資料傳輸速度', packages: '目標 App 套件名稱（每行一個）',
    profile: '限速設定', addProfile: '新增限速設定', appUse: '加入「持續使用 3 分鐘後降速」範例',
    upstream: '上傳上限（kbps，留空代表不限速）', downstream: '下載上限（kbps，留空代表不限速）',
    note: '這裡只定義可套用的設定；實際切換由下方的 signed automation 決定。', remove: '移除限速設定'
  } : {
    enable: 'Adjust data-transfer speed for selected apps', packages: 'Target app package names (one per line)',
    profile: 'Traffic profile', addProfile: 'Add traffic profile', appUse: 'Add “slow after 3 minutes of use” example',
    upstream: 'Uplink cap (kbps; empty is unlimited)', downstream: 'Downlink cap (kbps; empty is unlimited)',
    note: 'This defines profiles the runtime may apply. Signed automations below decide when they change.', remove: 'Remove traffic profile'
  });

  function toggle(on: boolean): void {
    if (!on) {
      configuration.traffic_shaping = {};
      configuration.automations = configuration.automations.filter((automation) =>
        automation.type !== 'resource_binding' || automation.resource.kind !== 'actuator' || automation.resource.id !== 'traffic-shaping.v1'
      );
      return;
    }
    configuration.traffic_shaping = {
      target_packages: ['com.example.app'],
      profiles: [
        { id: 'baseline', uplink_kbps: null, downlink_kbps: null },
        { id: 'slow-network', uplink_kbps: 256, downlink_kbps: 1_024 }
      ]
    };
    configuration.automations.push({
      type: 'resource_binding', id: 'bind-traffic-shaping',
      resource: { kind: 'actuator', id: 'traffic-shaping.v1' },
      cases: [{ condition: { type: 'study_session_active' }, profile_id: 'baseline' }],
      default_profile_id: 'baseline'
    });
    configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
  }

  function binding(): ResourceBindingAutomation | null {
    return configuration.automations.find((automation): automation is ResourceBindingAutomation =>
      automation.type === 'resource_binding' && automation.resource.kind === 'actuator' && automation.resource.id === 'traffic-shaping.v1'
    ) ?? null;
  }

  function renameProfile(profile: TrafficShapingProfile, next: string): void {
    if (!trafficShapingEnabled(configuration.traffic_shaping)) return;
    const previous = profile.id; profile.id = next;
    configuration.traffic_shaping.profiles.sort((left, right) => left.id.localeCompare(right.id));
    const owner = binding();
    if (!owner) return;
    if (owner.default_profile_id === previous) owner.default_profile_id = next;
    for (const entry of owner.cases) if (entry.profile_id === previous) entry.profile_id = next;
  }

  function addProfile(): void {
    if (!trafficShapingEnabled(configuration.traffic_shaping)) return;
    const used = new Set(configuration.traffic_shaping.profiles.map((profile) => profile.id));
    let ordinal = 2; while (used.has(`traffic-${ordinal}`)) ordinal += 1;
    configuration.traffic_shaping.profiles.push({ id: `traffic-${ordinal}`, uplink_kbps: null, downlink_kbps: null });
    configuration.traffic_shaping.profiles.sort((left, right) => left.id.localeCompare(right.id));
  }

  function removeProfile(profile: TrafficShapingProfile): void {
    if (!trafficShapingEnabled(configuration.traffic_shaping) || configuration.traffic_shaping.profiles.length <= 1) return;
    configuration.traffic_shaping.profiles.splice(configuration.traffic_shaping.profiles.indexOf(profile), 1);
    const replacement = configuration.traffic_shaping.profiles[0].id; const owner = binding();
    if (!owner) return;
    if (owner.default_profile_id === profile.id) owner.default_profile_id = replacement;
    for (const entry of owner.cases) if (entry.profile_id === profile.id) entry.profile_id = replacement;
  }

  function packageMatcher(type: 'ACTIVITY_RESUMED' | 'ACTIVITY_PAUSED' | 'ACTIVITY_STOPPED', packageName: string): EventMatcher {
    return {
      event: { source_id: 'usage_events.v1', schema_version: 1, event_type: type },
      predicates: [{ field: 'package_name', operator: 'eq', value: packageName }]
    };
  }

  function addAppUseRule(): void {
    if (!trafficShapingEnabled(configuration.traffic_shaping)) return;
    const packageName = configuration.traffic_shaping.target_packages[0];
    const slow = configuration.traffic_shaping.profiles.find((profile) => profile.uplink_kbps !== null || profile.downlink_kbps !== null)
      ?? configuration.traffic_shaping.profiles[0];
    let usage = configuration.collectors.find((collector) => collector.id === 'usage_events.v1');
    if (!usage) {
      draft.enableCollector('usage_events.v1');
      usage = configuration.collectors.find((collector) => collector.id === 'usage_events.v1');
    }
    if (usage) {
      usage.required = true;
      for (const profile of usage.profiles) profile.config.poll_interval_seconds = 15;
      const owner = configuration.automations.find((automation): automation is ResourceBindingAutomation =>
        automation.type === 'resource_binding' && automation.resource.kind === 'collector' && automation.resource.id === 'usage_events.v1'
      );
      if (owner) {
        owner.default_profile_id = usage.profiles[0].id;
        for (const entry of owner.cases) entry.profile_id ??= usage.profiles[0].id;
      }
    }
    const owner = binding();
    if (!owner) return;
    owner.cases = [{
      condition: {
        type: 'held_for', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME',
        condition: {
          type: 'keyed_presence', key_field: 'activity_component_token',
          enter_when: [packageMatcher('ACTIVITY_RESUMED', packageName)],
          exit_when: [packageMatcher('ACTIVITY_PAUSED', packageName), packageMatcher('ACTIVITY_STOPPED', packageName)]
        }
      },
      profile_id: slow.id
    }, { condition: { type: 'study_session_active' }, profile_id: owner.default_profile_id }];
  }
</script>

<div class="stack">
  <ToggleField label={copy.enable} value={enabled} onchange={toggle} />
  {#if enabled && trafficShapingEnabled(configuration.traffic_shaping)}
    {@const shaping = configuration.traffic_shaping}
    <Note icon="info" tone="plain" text={copy.note} />
    <Field label={copy.packages} path="traffic_shaping.target_packages">
      {#snippet children({ id, describedby, invalid })}
        <textarea
          class="input input--mono"
          {id}
          rows="4"
          aria-describedby={describedby}
          aria-invalid={invalid || undefined}
          value={shaping.target_packages.join('\n')}
          onblur={(event) => {
            shaping.target_packages = [...new Set(event.currentTarget.value.split('\n').map((item) => item.trim()).filter(Boolean))].sort();
          }}
        ></textarea>
      {/snippet}
    </Field>

    <div class="traffic-profiles">
      {#each shaping.profiles as profile (profile)}
        {@const index = shaping.profiles.indexOf(profile)}
        <div class="traffic-profile">
          <div class="traffic-profile__head">
            <IdField label={copy.profile} path={`traffic_shaping.profiles.${index}.id`} value={profile.id} onchange={(value) => renameProfile(profile, value)} />
            {#if shaping.profiles.length > 1}
              <IconButton icon="trash" label={copy.remove} variant="danger" onclick={() => removeProfile(profile)} />
            {/if}
          </div>
          <NullableCap label={copy.upstream} value={profile.uplink_kbps} onchange={(value) => (profile.uplink_kbps = value)} />
          <NullableCap label={copy.downstream} value={profile.downlink_kbps} onchange={(value) => (profile.downlink_kbps = value)} />
        </div>
      {/each}
    </div>
    <div class="row">
      <Button label={copy.addProfile} icon="plus" onclick={addProfile} />
      <Button label={copy.appUse} icon="clock" variant="quiet" onclick={addAppUseRule} />
    </div>
  {/if}
</div>

<style>
  .traffic-profiles { display: grid; gap: var(--sp-5); }
  .traffic-profile {
    display: grid;
    gap: var(--sp-4);
    padding: var(--sp-5);
    border: var(--line-hair) solid var(--rule);
    border-radius: var(--r-panel);
    background: var(--surface);
  }
  .traffic-profile__head { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: var(--sp-4); align-items: end; }
</style>
