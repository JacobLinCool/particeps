<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import Field from '$lib/ui/Field.svelte';
  import Note from '$lib/ui/Note.svelte';
  import { simulate, type SimulationResult, type SyntheticTrace } from '$lib/particeps/simulator';
  import type { Draft } from './draft.svelte';

  let { draft, locale = 'en' }: { draft: Draft; locale?: 'en' | 'zh-TW' } = $props();
  const copy = $derived(locale === 'zh-TW' ? {
    lead: '使用合成事件預覽 automation；不抽選參與者時間，也不控制裝置。',
    trace: '合成事件軌跡（JSON）', run: '模擬', resources: '最後的資源設定', actions: '觸發的一次性活動', error: '無法模擬這份軌跡。'
  } : {
    lead: 'Test signed automations with synthetic events—no real times or device control.',
    trace: 'Synthetic event trace (JSON)', run: 'Simulate', resources: 'Final resource state', actions: 'One-shot actions matched', error: 'This trace could not be simulated.'
  });
  let input = $state(JSON.stringify({ active_seconds: 300, calendar_seconds: 300, events: [] }, null, 2));
  let result = $state<SimulationResult | null>(null);
  let failed = $state(false);

  function run(): void {
    try {
      const trace = JSON.parse(input) as SyntheticTrace;
      result = simulate(draft.document, trace);
      failed = false;
    } catch {
      result = null;
      failed = true;
    }
  }
</script>

<div class="simulator">
  <Note icon="info" tone="plain" text={copy.lead} />
  <Field label={copy.trace}>
    {#snippet children({ id })}
      <textarea class="input input--mono" {id} rows="12" spellcheck="false" bind:value={input}></textarea>
    {/snippet}
  </Field>
  <Button label={copy.run} icon="arrow-right" variant="primary" onclick={run} />
  {#if failed}<Note icon="alert" tone="danger" text={copy.error} />{/if}
  {#if result}
    <div class="result" aria-live="polite">
      <section>
        <h4>{copy.resources}</h4>
        <ul>{#each result.resources as resource (`${resource.kind}:${resource.id}`)}<li><code>{resource.kind}:{resource.id}</code> → <code>{resource.profile_id ?? 'inactive'}</code></li>{/each}</ul>
      </section>
      <section>
        <h4>{copy.actions}</h4>
        {#if result.interventions.length === 0}<p class="fine faint">0</p>{/if}
        <ul>{#each result.interventions as action (`${action.automation_id}:${action.matched_at_seconds}`)}<li><code>{action.intervention_id}</code> · {action.matched_at_seconds}s</li>{/each}</ul>
      </section>
    </div>
  {/if}
</div>

<style>
  .simulator { display: grid; gap: var(--sp-5); }
  .result { display: grid; grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr)); gap: var(--sp-6); }
  .result section { display: grid; gap: var(--sp-3); }
  .result h4 { margin: 0; font-size: var(--type-body); }
  .result ul { margin: 0; padding-inline-start: var(--sp-6); }
</style>
