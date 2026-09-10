<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import Field from '$lib/ui/Field.svelte';
  import IdField from '$lib/ui/IdField.svelte';
  import IconButton from '$lib/ui/IconButton.svelte';
  import Note from '$lib/ui/Note.svelte';
  import ToggleField from '$lib/ui/ToggleField.svelte';
  import { trafficShapingEnabled, type ResourceBindingAutomation, type TrafficShapingProfile } from '$lib/particeps/types';
  import type { Draft } from './draft.svelte';
  import NullableCap from './NullableCapField.svelte';
  import { canonicalize } from '$lib/particeps/canonical';
  import { freshEditorId } from './editor-model';
  import { applyTrafficExample, reviewTrafficExample, undoTrafficExample, type TrafficExampleReview } from './traffic-example';

  let { draft, locale = 'en' }: { draft: Draft; locale?: 'en' | 'zh-TW' } = $props();
  const configuration = $derived(draft.configuration);
  const enabled = $derived(trafficShapingEnabled(configuration.traffic_shaping));
  const copy = $derived(locale === 'zh-TW' ? {
    enable: '調整 App 的資料傳輸速度', allApps: '套用到所有 App', packages: '目標 App 套件名稱（每行一個）',
    profile: '限速設定', addProfile: '新增限速設定', appUse: '檢閱「持續使用 3 分鐘後降速」範例變更',
    upstream: '上傳上限（kbps，留空代表不限速）', downstream: '下載上限（kbps，留空代表不限速）',
    note: '這裡只定義可套用的設定；實際切換由下方的 signed automation 決定。', remove: '移除限速設定'
  } : {
    enable: 'Adjust app data-transfer speed', allApps: 'Apply to all apps', packages: 'Target app package names (one per line)',
    profile: 'Traffic profile', addProfile: 'Add traffic profile', appUse: 'Review “slow after 3 minutes of use” example',
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
      type: 'resource_binding', id: freshEditorId('bind-traffic-shaping', configuration.automations.map((item) => item.id)),
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

  let review = $state<TrafficExampleReview | null>(null);
  let appliedReview = $state<TrafficExampleReview | null>(null);
  let appliedFingerprint = $state('');
  let exampleError = $state('');
  const staleReview = $derived(review !== null && canonicalize(configuration) !== review.basis);
  const undoAvailable = $derived(appliedReview !== null && canonicalize({ collectors: configuration.collectors, automations: configuration.automations }) === appliedFingerprint);
  function describeError(error: unknown) {
    const code = error instanceof Error ? error.message : String(error);
    const messages: Record<string, [string, string]> = {
      example_requires_capped_profile: ['請先建立至少一個有限速上限的設定檔。', 'Create at least one profile with a speed cap first.'],
      example_requires_slower_profile: ['請建立一個比預設設定更慢的限速設定檔；至少一個方向須降低上限，另一個方向不能提高上限。', 'Create a profile slower than the default: reduce at least one directional cap without increasing the other.'],
      example_requires_default_profile: ['請先在資源規則選擇有效的限速預設設定檔。', 'Choose a valid default traffic profile in resource rules first.'],
      example_preview_stale: ['研究已變更，請重新檢閱範例。', 'The study changed. Review the example again.'],
      example_undo_stale: ['資料來源或規則已再次修改，無法直接復原此範例。', 'Sources or rules changed again; this example can no longer be undone directly.']
    };
    return messages[code]?.[locale === 'zh-TW' ? 0 : 1] ?? (locale === 'zh-TW' ? `請先修正資料來源與規則設定：${code}` : `Resolve source and rule settings first: ${code}`);
  }
  function previewExample() {
    try { review = reviewTrafficExample(configuration); exampleError = ''; }
    catch (error) { exampleError = describeError(error); }
  }
  function applyExample() {
    if (!review) return;
    try { appliedFingerprint = applyTrafficExample(configuration, review); appliedReview = review; review = null; exampleError = ''; }
    catch (error) { exampleError = describeError(error); }
  }
  function undoExample() {
    if (!appliedReview) return;
    try { undoTrafficExample(configuration, appliedReview, appliedFingerprint); appliedReview = null; exampleError = ''; }
    catch (error) { exampleError = describeError(error); }
  }
</script>

<div class="stack">
  <ToggleField path="traffic_shaping" label={copy.enable} value={enabled} onchange={toggle} />
  {#if enabled && trafficShapingEnabled(configuration.traffic_shaping)}
    {@const shaping = configuration.traffic_shaping}
    <Note icon="info" tone="plain" text={copy.note} />
    <ToggleField label={copy.allApps} value={shaping.target_packages === 'all'} onchange={(all) => {
      shaping.target_packages = all ? 'all' : ['com.example.app'];
    }} />
    {#if shaping.target_packages !== 'all'}
    <Field label={copy.packages} path="traffic_shaping.target_packages">
      {#snippet children({ id, describedby, invalid })}
        <textarea
          class="input input--mono"
          {id}
          rows="4"
          aria-describedby={describedby}
          aria-invalid={invalid || undefined}
          value={shaping.target_packages === 'all' ? '' : shaping.target_packages.join('\n')}
          onblur={(event) => {
            shaping.target_packages = [...new Set(event.currentTarget.value.split('\n').map((item) => item.trim()).filter(Boolean))].sort();
          }}
        ></textarea>
      {/snippet}
    </Field>
    {/if}

    <div class="traffic-profiles" data-issue-host="traffic_shaping.profiles">
      {#each shaping.profiles as profile (profile)}
        {@const index = shaping.profiles.indexOf(profile)}
        <div class="traffic-profile" data-issue-host={`traffic_shaping.profiles.${index}`}>
          <div class="traffic-profile__head">
            <IdField label={copy.profile} path={`traffic_shaping.profiles.${index}.id`} value={profile.id} onchange={(value) => renameProfile(profile, value)} />
            {#if shaping.profiles.length > 1}
              <IconButton icon="trash" label={copy.remove} variant="danger" onclick={() => removeProfile(profile)} />
            {/if}
          </div>
          <NullableCap path={`traffic_shaping.profiles.${index}.uplink_kbps`} label={copy.upstream} value={profile.uplink_kbps} onchange={(value) => (profile.uplink_kbps = value)} />
          <NullableCap path={`traffic_shaping.profiles.${index}.downlink_kbps`} label={copy.downstream} value={profile.downlink_kbps} onchange={(value) => (profile.downlink_kbps = value)} />
        </div>
      {/each}
    </div>
    <div class="row">
      <Button label={copy.addProfile} icon="plus" disabled={shaping.profiles.length >= 64} onclick={addProfile} />
      {#if shaping.target_packages !== 'all' && shaping.target_packages.length > 0}
        <Button label={copy.appUse} icon="clock" variant="quiet" onclick={previewExample} />
      {/if}
    </div>
    {#if review}
      <div class="example-review" aria-live="polite">
        <h4>{locale === 'zh-TW' ? '套用前檢閱變更' : 'Review changes before applying'}</h4>
        <p>{locale === 'zh-TW' ? '此範例會替換全部限速條件。它也會啟用必要的 App 使用事件來源，將所有輪詢設定改為 15 秒，並讓該來源在整個研究中保持啟用。其他資料來源與研究活動保留。' : 'This example replaces all traffic conditions. It also enables required app usage events, changes every usage polling profile to 15 seconds, and keeps that source active throughout the study. Other sources and activities are preserved.'}</p>
        <details open>
          <summary>{locale === 'zh-TW' ? '完整變更內容' : 'Full change details'}</summary>
          <div class="example-diff">
            <div><strong>{locale === 'zh-TW' ? '套用前' : 'Before'}</strong><pre>{JSON.stringify(review.changedBefore, null, 2)}</pre></div>
            <div><strong>{locale === 'zh-TW' ? '套用後' : 'After'}</strong><pre>{JSON.stringify(review.changedAfter, null, 2)}</pre></div>
          </div>
        </details>
        {#if staleReview}<p role="status">{locale === 'zh-TW' ? '研究已變更，請重新檢閱。' : 'The study has changed. Review it again.'}</p>{/if}
        <div class="row">
          <Button label={locale === 'zh-TW' ? '套用已檢閱的替換' : 'Apply reviewed replacement'} disabled={staleReview} onclick={applyExample} />
          <Button label={locale === 'zh-TW' ? '取消' : 'Cancel'} variant="ghost" onclick={() => review = null} />
        </div>
      </div>
    {/if}
    {#if appliedReview}
      <div class="row" aria-live="polite">
        <p class="fine">{locale === 'zh-TW' ? '範例已套用。' : 'Example applied.'}</p>
        <Button label={locale === 'zh-TW' ? '復原範例變更' : 'Undo example changes'} variant="ghost" disabled={!undoAvailable} onclick={undoExample} />
        {#if !undoAvailable}<p class="fine faint">{locale === 'zh-TW' ? '資料來源或規則已再次修改，直接復原已停用。' : 'Sources or rules were edited again, so direct undo is disabled.'}</p>{/if}
      </div>
    {/if}
    {#if exampleError}<p role="alert">{exampleError}</p>{/if}
  {/if}
</div>

<style>
  .example-review { display: grid; gap: var(--sp-4); padding-block: var(--sp-5); border-block-start: var(--line-hair) solid var(--rule); }
  .example-review h4, .example-review p { margin: 0; }
  .example-diff { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 18rem), 1fr)); gap: var(--sp-4); }
  .example-diff > div { min-inline-size: 0; }
  .example-diff pre { overflow: auto; max-block-size: 24rem; font-size: var(--type-fine); }
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
