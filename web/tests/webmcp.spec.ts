import { describe, expect, it, vi } from 'vitest';
import { createResearcherTools, configurationRevision, registerResearcherWebMcp, type ResearcherDraftPort, type ResearcherTool } from '../src/lib/particeps/webmcp';
import { AUTHORING_DEFINITIONS, CHANGE_SCHEMA, validateToolInput } from '../src/lib/particeps/webmcp-schema';
import { continuousBinding, defaultCollector, emptyConfiguration } from '../src/lib/particeps/schema';
import { COLLECTOR_ORDER, type StudyConfiguration, type SurveyDefinition } from '../src/lib/particeps/types';
import { validConfiguration, SIGNING, HPKE } from './fixture';

function setup(initial = validConfiguration()) {
  let configuration = structuredClone(initial);
  const commit = vi.fn((next: StudyConfiguration) => { configuration = next; });
  const afterCommit = vi.fn(async () => {});
  const port: ResearcherDraftPort = { getConfiguration: () => structuredClone(configuration), commitConfiguration: commit, afterCommit };
  const tools = createResearcherTools(port);
  const tool = (name: string) => tools.find((entry) => entry.name === `particeps_${name}`)!;
  const call = async (name: string, input: unknown = {}, signal?: AbortSignal) =>
    await tool(name).execute(input, { signal }) as Record<string, any>;
  const apply = (changes: unknown[], extra: Record<string, unknown> = {}) => call('apply_changes', {
    expected_revision: configurationRevision(configuration), changes, ...extra
  });
  return { port, tools, tool, call, apply, commit, afterCommit, get current() { return configuration; } };
}

const text = (value: string) => ({ default: value, translations: {} });
const survey: SurveyDefinition = {
  id: 'daily-survey', title: text('Daily experience'), description: text('Tell us about your day.'), questions: [
    { type: 'short_text', id: 'text-question', prompt: text('What happened?'), required: false, maximum_length: 500 },
    { type: 'scale', id: 'scale-question', prompt: text('How did you feel?'), required: true, minimum: 1, maximum: 7, minimum_label: text('Low'), maximum_label: text('High') },
    { type: 'single_choice', id: 'single-question', prompt: text('Where?'), required: true, options: [{ id: 'home-choice', label: text('Home') }, { id: 'work-choice', label: text('Work') }] },
    { type: 'multiple_choice', id: 'multi-question', prompt: text('With whom?'), required: false, minimum_selections: 0, maximum_selections: 2, options: [{ id: 'friend-choice', label: text('Friends') }, { id: 'family-choice', label: text('Family') }] }
  ]
};
const surveyChanges = () => [
  { type: 'upsert_survey', survey: structuredClone(survey) },
  { type: 'upsert_intervention', intervention: { id: 'daily-intervention', required: false, action: {
    type: 'survey', survey_id: survey.id, notification_title: 'Daily survey', notification_message: 'Please answer these questions.'
  } } },
  { type: 'upsert_automation', automation: {
    type: 'occurrence', id: 'daily-occurrence', intervention_id: 'daily-intervention',
    trigger: { type: 'schedule', schedule: { type: 'daily_local', local_time: '20:00' } },
    guard: null, availability_seconds: 3_600, cooldown: null, maximum_activations: 7
  } }
];

describe('WebMCP shared researcher authoring', () => {
  it('publishes five semantically named tools and the full registry-backed catalog', async () => {
    const session = setup();
    expect(session.tools.map((tool) => tool.name)).toEqual([
      'particeps_get_authoring_catalog', 'particeps_read_draft', 'particeps_apply_changes',
      'particeps_validate_draft', 'particeps_preview_timeline'
    ]);
    const catalog = await session.call('get_authoring_catalog');
    expect(catalog.collectors.map((collector: { id: string }) => collector.id)).toEqual(COLLECTOR_ORDER);
    expect(catalog.events.length).toBeGreaterThan(10);
    expect(catalog.events.every((event: any) => event.trigger.scope === 'RESEARCHER')).toBe(true);
    expect(catalog.schemas.condition.oneOf).toHaveLength(10);
    expect(catalog.schemas.schedule.oneOf).toHaveLength(4);
    expect(catalog.schemas.survey.properties.questions.items.oneOf).toHaveLength(4);
    expect(session.tool('apply_changes').annotations.readOnlyHint).toBe(false);
    expect(session.tool('read_draft').annotations.readOnlyHint).toBe(true);
  });

  it('keeps key material and participant assignment outside read and edit tools', async () => {
    const session = setup(validConfiguration({ assigned_participant_id: 'participant-secret-42' }));
    const read = await session.call('read_draft');
    const serialized = JSON.stringify(read);
    for (const value of [SIGNING.privateKey, HPKE.privateKey, SIGNING.publicKey, HPKE.publicKey, 'participant-secret-42']) {
      expect(serialized).not.toContain(value);
    }
    const result = await session.apply([{ type: 'set_study', study: { title: 'Changed title' } }]);
    expect(result.ok).toBe(true);
    expect(session.current.signer).toEqual(validConfiguration().signer);
    expect(session.current.export).toEqual(validConfiguration().export);
    expect(session.current.assigned_participant_id).toBe('participant-secret-42');
    expect(session.current.configuration_id).toBe('protocol-study-000001');
    for (const field of ['signer', 'export', 'assigned_participant_id', 'experiment_id', 'configuration_id']) {
      const result = await session.apply([{ type: 'set_study', study: { [field]: 'forbidden' } }]);
      expect(result.error.code).toBe('invalid_input');
    }
  });

  it('accepts unfinished basic metadata but rejects nonempty malformed fields', async () => {
    const session = setup(emptyConfiguration());
    const collector = defaultCollector('battery_state.v1');
    const result = await session.apply([
      { type: 'upsert_collector', collector },
      { type: 'upsert_automation', automation: continuousBinding(collector) }
    ]);
    expect(result.ok).toBe(true);
    expect(result.validation.ready_to_compile).toBe(false);
    expect(result.validation.incomplete).toContainEqual(expect.objectContaining({ path: 'title', code: 'number_range' }));
    expect(result.validation.blocking).toEqual([]);
    const invalid = await session.apply([{ type: 'set_study', study: { researcher: { name: '', contact: 'x' } } }]);
    expect(invalid.error.code).toBe('invalid_configuration');
    expect(session.current.researcher.contact).toBe('');
  });

  it('configures all 15 Android collectors and their bindings in one valid commit', async () => {
    const session = setup(validConfiguration({ collectors: [], automations: [] }));
    const changes = [...COLLECTOR_ORDER].reverse().flatMap((id) => {
      const collector = defaultCollector(id);
      return [{ type: 'upsert_collector', collector }, { type: 'upsert_automation', automation: continuousBinding(collector) }];
    });
    const result = await session.apply(changes);
    expect(result.ok).toBe(true);
    expect(result.validation.ready_to_compile).toBe(true);
    expect(session.current.collectors.map((collector) => collector.id)).toEqual(COLLECTOR_ORDER);
    expect(session.commit).toHaveBeenCalledTimes(1);
    expect(session.afterCommit).toHaveBeenCalledTimes(1);
  });

  it('rejects unknown collector profile properties and incomplete atomic dependencies without partial writes', async () => {
    const session = setup();
    const original = structuredClone(session.current);
    const collector = defaultCollector('accelerometer.v1');
    const missingBinding = await session.apply([
      { type: 'set_study', study: { title: 'Must not be applied' } }, { type: 'upsert_collector', collector }
    ]);
    expect(missingBinding.error.code).toBe('invalid_configuration');
    expect(session.current).toEqual(original);
    const malformed = structuredClone(collector) as any;
    malformed.profiles[0].config.private_key = 'never-print-this';
    const invalid = await session.apply([{ type: 'upsert_collector', collector: malformed }]);
    expect(invalid.error.code).toBe('invalid_input');
    expect(JSON.stringify(invalid)).not.toContain('never-print-this');
    expect(session.commit).not.toHaveBeenCalled();
  });

  it('authors a complete multilingual four-question survey and linked scheduled occurrence atomically', async () => {
    const session = setup();
    const changes = surveyChanges();
    changes[0].survey!.title.translations['zh-TW'] = '每日體驗';
    const result = await session.apply(changes);
    expect(result.ok).toBe(true);
    expect(result.validation.ready_to_compile).toBe(true);
    expect(session.current.surveys[0].questions.map((question) => question.id)).toEqual(survey.questions.map((question) => question.id));
    expect(session.current.surveys[0].title.translations['zh-TW']).toBe('每日體驗');
    const timeline = await session.call('preview_timeline', { startDate: '2026-09-11', timeZone: 'Asia/Taipei' });
    expect(timeline.ok).toBe(true);
    expect(timeline.timeline.events).toEqual(expect.arrayContaining([expect.objectContaining({ localTime: '20:00', questionCount: 4 })]));
  });

  it('previews a batch without writing and uses the same validation as a real edit', async () => {
    const session = setup();
    const before = structuredClone(session.current);
    const result = await session.apply(surveyChanges(), { dry_run: true });
    expect(result.ok).toBe(true);
    expect(result.applied).toBe(false);
    expect(result.configuration.surveys).toHaveLength(1);
    expect(session.current).toEqual(before);
    expect(session.commit).not.toHaveBeenCalled();
  });

  it('rejects stale revisions after another UI edit', async () => {
    const session = setup();
    const read = await session.call('read_draft');
    session.current.title = 'A researcher changed the title';
    const result = await session.apply([{ type: 'set_study', study: { title: 'Agent edit' } }], { expected_revision: read.revision });
    expect(result.error.code).toBe('revision_conflict');
    expect(session.current.title).toBe('A researcher changed the title');
    expect(session.commit).not.toHaveBeenCalled();
  });

  it('rejects deleting resources that are still referenced and accepts coordinated removals', async () => {
    const session = setup();
    const rejected = await session.apply([{ type: 'remove_collector', id: 'location.v1' }]);
    expect(rejected.error.code).toBe('invalid_configuration');
    expect(session.current.collectors).toHaveLength(2);
    const applied = await session.apply([{ type: 'remove_collector', id: 'location.v1' }, { type: 'remove_automation', id: 'bind-location' }]);
    expect(applied.ok).toBe(true);
    expect(session.current.collectors).toHaveLength(1);
  });

  it('prevents unknown events, dead trigger sources and cyclic resource dependencies', async () => {
    const session = setup();
    const changes = surveyChanges();
    const occurrence = changes[2].automation as any;
    occurrence.trigger = { type: 'event_match', selector: {
      event: { source_id: 'location.v1', schema_version: 1, event_type: 'LOCATION_FIX' }, predicates: []
    }, evaluation_clock: 'OBSERVED_RESEARCH_TIME' };
    const rejected = await session.apply(changes);
    expect(rejected.error.code).toBe('invalid_configuration');
    expect(rejected.error.issues.some((issue: any) => issue.code === 'trigger_source_liveness')).toBe(true);
    occurrence.trigger.selector.event.event_type = 'UNKNOWN_EVENT';
    expect((await session.apply(changes)).error.issues.some((issue: any) => issue.code === 'unknown_event')).toBe(true);
    const binding = structuredClone(session.current.automations[1]) as any;
    binding.cases[0].condition = { type: 'event_latch', set_when: [occurrence.trigger.selector], reset_when: [occurrence.trigger.selector] };
    occurrence.trigger.selector.event.event_type = 'LOCATION_FIX';
    const cycle = await session.apply([{ type: 'upsert_automation', automation: binding }]);
    expect(cycle.error.code).toBe('invalid_configuration');
  });

  it('supports collection windows, traffic profiles and upload/storage settings', async () => {
    const session = setup();
    const binding = structuredClone(session.current.automations[1]) as any;
    binding.cases[0].condition = { type: 'study_local_window', first_day: 1, last_day: 1, start_local_time: '08:00', end_local_time: '18:00' };
    const result = await session.apply([
      { type: 'upsert_automation', automation: binding },
      { type: 'set_storage', storage: { maximum_local_bytes: 536_870_912 } },
      { type: 'set_upload', upload: { endpoint: 'https://research.example.org/bundles', interval_minutes: 60, allow_metered: false } },
      { type: 'set_traffic_shaping', traffic_shaping: { target_packages: 'all', profiles: [{ id: 'slow-profile', uplink_kbps: 100, downlink_kbps: 250 }] } },
      { type: 'upsert_automation', automation: { type: 'resource_binding', id: 'bind-traffic', resource: { kind: 'actuator', id: 'traffic-shaping.v1' },
        cases: [{ condition: { type: 'study_session_active' }, profile_id: 'slow-profile' }], default_profile_id: null } }
    ]);
    expect(result.ok).toBe(true);
    expect(session.current.upload?.interval_minutes).toBe(60);
    expect(session.current.storage.maximum_local_bytes).toBe(536_870_912);
    expect((await session.call('preview_timeline')).timeline.lanes).toHaveLength(3);
  });

  it('keeps random windows uncertain instead of sampling participant times', async () => {
    const session = setup();
    const changes = surveyChanges();
    (changes[2].automation as any).trigger.schedule = {
      type: 'random_window', local_windows: [{ start_local_time: '09:00', end_local_time: '12:00' }],
      occurrences_per_window: 2, maximum_occurrences_per_day: 2, maximum_occurrences_total: 2, minimum_separation_minutes: 30
    };
    expect((await session.apply(changes)).ok).toBe(true);
    const result = await session.call('preview_timeline');
    expect(result.timeline.events).toHaveLength(0);
    expect(result.timeline.randomWindows).toHaveLength(1);
    expect(result.timeline.randomWindows[0]).toMatchObject({ startTime: '09:00', endTime: '12:00' });
  });

  it('honors cancellation and handles invalid date/timezone arguments without writes', async () => {
    const session = setup();
    const controller = new AbortController();
    controller.abort();
    const result = await session.call('apply_changes', { expected_revision: configurationRevision(session.current), changes: [{ type: 'set_study', study: { title: 'Canceled' } }] }, controller.signal);
    expect(result.error.code).toBe('canceled');
    expect(session.commit).not.toHaveBeenCalled();
    expect((await session.call('preview_timeline', { startDate: '2026-02-30' })).ok).toBe(false);
    expect((await session.call('preview_timeline', { timeZone: 'Not/A-TimeZone' })).ok).toBe(false);
  });

  it('rejects oversized, cyclic, deep, unknown and non-JSON input before touching the draft', async () => {
    const session = setup();
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    let deep: unknown = {};
    for (let index = 0; index < 40; index++) deep = { nested: deep };
    for (const input of [undefined, null, [], { extra: true }, cyclic, deep, { bad: 1n }, { bad: () => 1 },
      { bad: new Date() }, JSON.parse('{"__proto__":{"polluted":true}}'), { bad: 'x'.repeat(1_048_577) }]) {
      const result = await session.tool('read_draft').execute(input) as Record<string, any>;
      expect(result.ok).toBe(false);
      expect(result.error.code).toBe('invalid_input');
    }
    const tooMany = await session.apply(Array.from({ length: 65 }, () => ({ type: 'set_study', study: { title: 'Test' } })));
    expect(tooMany.error.code).toBe('invalid_input');
    expect(session.commit).not.toHaveBeenCalled();
  });

  it('schema checks every full fixture object using the same definitions advertised to agents', () => {
    const fixture = validConfiguration();
    for (const [definition, objects] of Object.entries({
      collector: fixture.collectors, binding: fixture.automations, survey: [survey]
    })) for (const value of objects) {
      expect(validateToolInput(value, { ...AUTHORING_DEFINITIONS[definition], $defs: AUTHORING_DEFINITIONS })).toEqual([]);
    }
    expect(CHANGE_SCHEMA.additionalProperties).toBe(false);
  });
});

describe('native WebMCP registration lifecycle', () => {
  it('reports unsupported without touching navigator or creating a shim', async () => {
    const statuses: string[] = [];
    const registration = registerResearcherWebMcp(setup().port, undefined, (status) => statuses.push(status));
    expect(await registration.ready).toBe('unsupported');
    expect(statuses).toEqual(['unsupported']);
    registration.stop();
    registration.stop();
    expect(statuses).toEqual(['unsupported', 'stopped']);
  });

  it('registers through document model context and aborts every tool on cleanup', async () => {
    const registered = new Map<string, ResearcherTool>();
    const signals: AbortSignal[] = [];
    const statuses: string[] = [];
    const registration = registerResearcherWebMcp(setup().port, { modelContext: {
      async registerTool(tool, { signal }) {
        registered.set(tool.name, tool);
        signals.push(signal);
        signal.addEventListener('abort', () => registered.delete(tool.name), { once: true });
      }
    } }, (status) => statuses.push(status));
    expect(await registration.ready).toBe('ready');
    expect(registered.size).toBe(5);
    expect(new Set(signals).size).toBe(1);
    registration.stop();
    expect(registered.size).toBe(0);
    expect(signals.every((signal) => signal.aborted)).toBe(true);
    expect(statuses).toEqual(['registering', 'ready', 'stopped']);
  });

  it('rolls back partial registration when the browser rejects a tool', async () => {
    const registered = new Map<string, ResearcherTool>();
    const statuses: string[] = [];
    let calls = 0;
    const registration = registerResearcherWebMcp(setup().port, { modelContext: {
      async registerTool(tool, { signal }) {
        if (++calls === 3) throw new Error('browser rejected registration');
        registered.set(tool.name, tool);
        signal.addEventListener('abort', () => registered.delete(tool.name), { once: true });
      }
    } }, (status) => statuses.push(status));
    expect(await registration.ready).toBe('failed');
    expect(registered.size).toBe(0);
    expect(calls).toBe(3);
    expect(statuses).toEqual(['registering', 'failed']);
  });

  it('handles navigation while asynchronous registration is still pending', async () => {
    let finish: () => void = () => {};
    const statuses: string[] = [];
    const registerTool = vi.fn(async () => await new Promise<void>((resolve) => { finish = resolve; }));
    const registration = registerResearcherWebMcp(setup().port, { modelContext: { registerTool } }, (status) => statuses.push(status));
    registration.stop();
    finish();
    expect(await registration.ready).toBe('stopped');
    expect(registerTool).toHaveBeenCalledTimes(1);
    expect(statuses).toEqual(['registering', 'stopped']);
  });
});
