import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { automationCheckpointDigest, decodeAutomationCheckpoint, encodeAutomationCheckpoint } from '../src/lib/particeps/automation/checkpoint.ts';
import { compileAutomationProgram } from '../src/lib/particeps/automation/compiler.ts';
import { reduceAutomationBatch } from '../src/lib/particeps/automation/reducer.ts';
import { matches } from '../src/lib/particeps/automation/reducer-state.ts';
import { emptyAutomationCheckpoint, resourceKeyString, type AutomationEvent, type CompiledAutomationProgram, type DurableTimer, type ReducerClock, type ReducerInput, type ResearchTime, type TimerTarget } from '../src/lib/particeps/automation/types.ts';
import { EVENT_SOURCE_REGISTRY_SHA256 } from '../src/lib/particeps/generated/event-source-registry.ts';
import { eventContract } from '../src/lib/particeps/registry.ts';
import type { EventMatcher, StateCondition, StudyConfiguration } from '../src/lib/particeps/types.ts';

const corpus = JSON.parse(readFileSync(
  new URL('../../protocol/v1/automation-reducer-vectors.json', import.meta.url), 'utf8'
)) as Corpus;

describe('authoritative automation reducer conformance', () => {
  it('compares Protocol decimal float event spellings to canonical in-values numerically', () => {
    const identity = {
      source_id: 'accelerometer.v1', schema_version: 1, event_type: 'ACCELEROMETER_SAMPLE'
    } as const;
    const contract = eventContract(identity)?.event;
    if (!contract) throw new Error('missing accelerometer event contract');
    const matcher: EventMatcher = {
      event: identity,
      predicates: [{
        field: 'x_meters_per_second_squared', operator: 'in',
        values: ['0.001', '0.5', '1.0', '1000.0']
      }]
    };
    const configuration = validConfigurationForFloatTest();
    const program: CompiledAutomationProgram = {
      configuration,
      configuration_sha256: '0'.repeat(64),
      study_duration_seconds: 3_600,
      occurrence_automations: [],
      resource_bindings: [],
      contracts: new Map([['accelerometer.v1\u00001\u0000ACCELEROMETER_SAMPLE', contract]]),
      automations: []
    };
    for (const [index, wire] of ['+1', '01', '.5', '1.', '1e-3', '1E+3'].entries()) {
      const now: ResearchTime = {
        wall_time_utc_millis: index + 1,
        elapsed_realtime_nanos: BigInt(index + 1) * 1_000_000n,
        boot_session_id: 'boot-float'
      };
      const event: AutomationEvent = {
        sequence_number: index + 1,
        ...identity,
        observed_time: now,
        primary_source_time: null,
        fields: { x_meters_per_second_squared: wire }
      };
      expect(matches(program, matcher, event), wire).toBe(true);
    }
  });

  it('matches every Kotlin-owned checkpoint and output after every input', () => {
    expect(corpus.format).toBe('particeps-automation-reducer-v1');
    expect(corpus.registry_sha256).toBe(EVENT_SOURCE_REGISTRY_SHA256);
    expect(corpus.batch_semantics.step_boundary)
      .toBe('Each scenario step is one indivisible SourceObservation or EngineCommit reducer batch.');
    for (const scenario of corpus.scenarios) {
      const configuration = {
        ...scenario.configuration,
        upload: Object.keys(scenario.configuration.upload as object).length === 0
          ? null : scenario.configuration.upload
      } as StudyConfiguration;
      const program = compileAutomationProgram(configuration, scenario.configuration_sha256);
      let checkpoint = emptyAutomationCheckpoint();
      const inputs = scenario.steps.map((step) => input(step.input));
      scenario.steps.forEach((step, index) => {
        const result = reduceAutomationBatch(program, checkpoint, [inputs[index]]);
        checkpoint = result.checkpoint;
        expect(expected(result), `${scenario.id} input ${index + 1}`).toEqual(step.expected);
        expect(decodeAutomationCheckpoint(step.expected.checkpoint)).toEqual(checkpoint);
      });
      expect(automationCheckpointDigest(checkpoint)).toBe(scenario.final_checkpoint_sha256);
      for (const range of scenario.stream_partition_ranges) {
        const first = range.first_step - 1;
        const last = range.last_step;
        let boundary = emptyAutomationCheckpoint();
        for (const prefixInput of inputs.slice(0, first)) {
          boundary = reduceAutomationBatch(program, boundary, [prefixInput]).checkpoint;
        }
        let expectedPartition = boundary;
        for (const atomicInput of inputs.slice(first, last)) {
          expectedPartition = reduceAutomationBatch(program, expectedPartition, [atomicInput]).checkpoint;
        }
        for (let split = first + 1; split < last; split += 1) {
          let actualPartition = boundary;
          for (const transportChunk of [inputs.slice(first, split), inputs.slice(split, last)]) {
            for (const atomicInput of transportChunk) {
              actualPartition = reduceAutomationBatch(program, actualPartition, [atomicInput]).checkpoint;
            }
          }
          expect(automationCheckpointDigest(actualPartition), `${scenario.id} transport partition after atomic step ${split}`)
            .toBe(automationCheckpointDigest(expectedPartition));
        }
      }
    }
  });

  it('rejects every shared hostile compiler case', () => {
    const scenarios = new Map(corpus.scenarios.map((scenario) => [scenario.id, scenario]));
    const seen = new Set<string>();
    for (const vector of corpus.compiler_hostile_cases) {
      seen.add(vector.id);
      const base = scenarios.get(vector.base_scenario_id);
      if (!base) throw new Error(`unknown hostile base scenario: ${vector.base_scenario_id}`);
      const configuration = hostileConfiguration(configurationFor(base), vector.mutation);
      if (vector.mutation === 'ALTER_EXIT_PRESENCE_GROUP_CONTRACT') {
        const contract = eventContract({
          source_id: 'usage_events.v1', schema_version: 1, event_type: 'ACTIVITY_PAUSED'
        });
        const presence = contract?.event.trigger.presence;
        if (!presence) throw new Error('shared presence-group hostile needs the generated EXIT contract');
        const mutable = presence as { group_id: string };
        const original = mutable.group_id;
        try {
          mutable.group_id = 'different_presence_group';
          expect(() => compileAutomationProgram(configuration), vector.id).toThrow();
        } finally {
          mutable.group_id = original;
        }
      } else {
        expect(() => compileAutomationProgram(configuration), vector.id).toThrow();
      }
    }
    expect(seen).toEqual(new Set([
      'combined-trigger-guard-condition-node-overflow',
      'global-concurrent-timer-overflow',
      'lifecycle-audit-event-match',
      'presence-condition-kind-mismatch',
      'presence-group-mismatch',
      'presence-key-mismatch',
      'presence-role-inversion',
      'random-window-daily-capacity-overflow',
      'random-window-adjacent-separation-violation',
      'random-window-cyclic-separation-violation',
      'utf16-astral-title-overflow',
      'sixty-five-stateful-resources'
    ]));
  });

  it('rejects schedule materializations that diverge from the signed schedule', () => {
    const scenarios = new Map(corpus.scenarios.map((scenario) => [scenario.id, scenario]));
    for (const vector of corpus.reducer_hostile_cases) {
      const scenario = scenarios.get(vector.scenario_id);
      if (!scenario) throw new Error(`unknown hostile reducer scenario: ${vector.scenario_id}`);
      const program = compileAutomationProgram(configurationFor(scenario), scenario.configuration_sha256);
      const stepIndex = vector.step - 1;
      let checkpoint = emptyAutomationCheckpoint();
      for (const step of scenario.steps.slice(0, stepIndex)) {
        checkpoint = reduceAutomationBatch(program, checkpoint, [input(step.input)]).checkpoint;
      }
      const hostile = mutateReducerInput(input(scenario.steps[stepIndex].input), vector.mutation);
      expect(() => reduceAutomationBatch(program, checkpoint, [hostile]), vector.id).toThrow();
    }
  });

  it('records a time-zone change only on the durable discontinuity input', () => {
    const scenario = corpus.scenarios.find((item) => item.id === 'conditions-resources-and-resets');
    if (!scenario) throw new Error('missing conditions scenario');
    const index = scenario.steps.findIndex((step) => step.input.type === 'CLOCK_DISCONTINUITY');
    expect(index).toBeGreaterThan(0);
    expect(scenario.steps[index - 1].input.clock.zone_id).not.toBe(scenario.steps[index].input.clock.zone_id);
    expect(scenario.steps[index + 1].input.clock.zone_id).toBe(scenario.steps[index].input.clock.zone_id);
  });

  it('keeps desired and applied generation aligned for a delayed enter-exit observation', () => {
    expect(corpus.atomic_batch_cases).toHaveLength(1);
    const vector = corpus.atomic_batch_cases[0];
    expect(vector.expected_desired_resource_relation).toBe('UNCHANGED');
    const scenario = corpus.scenarios.find((item) => item.id === vector.scenario_id);
    if (!scenario) throw new Error(`missing atomic-batch scenario: ${vector.scenario_id}`);
    const program = compileAutomationProgram(configurationFor(scenario), scenario.configuration_sha256);
    let checkpoint = emptyAutomationCheckpoint();
    for (const step of scenario.steps.slice(0, vector.base_checkpoint_after_step)) {
      checkpoint = reduceAutomationBatch(program, checkpoint, [input(step.input)]).checkpoint;
    }
    const batch = vector.inputs.map((recipe): ReducerInput => {
      const source = input(scenario.steps[recipe.source_step - 1].input);
      if (source.type !== 'EVENT') throw new Error('atomic batch recipes must reference events');
      return {
        ...source,
        sequence_number: recipe.sequence_number,
        event: { ...source.event, sequence_number: recipe.sequence_number }
      };
    });
    const key = resourceKeyString(vector.resource);
    const before = checkpoint.desired_resources.get(key)?.desired;
    const result = reduceAutomationBatch(program, checkpoint, batch);
    expect([...result.resource_changes.values()]).toEqual(vector.expected_resource_changes);
    expect(result.checkpoint.desired_resources.get(key)?.desired).toEqual(before);
  });

  it('applies the shared active-time cooldown property', () => {
    expect(corpus.reducer_property_cases).toHaveLength(1);
    const vector = corpus.reducer_property_cases[0];
    expect(vector.mutation).toBe('SET_OCC_EVENT_MAXIMUM_ACTIVATIONS_2');
    const scenario = corpus.scenarios.find((item) => item.id === vector.scenario_id);
    if (!scenario) throw new Error(`missing reducer-property scenario: ${vector.scenario_id}`);
    const configuration = configurationFor(scenario);
    const automation = configuration.automations.find((item) => item.id === vector.expected_automation_id);
    if (!automation || automation.type !== 'occurrence') throw new Error('cooldown property must reference an occurrence');
    automation.maximum_activations = 2;
    const program = compileAutomationProgram(configuration);
    let checkpoint = emptyAutomationCheckpoint();
    let actionCount = -1;
    let suppression: string | null | undefined;
    scenario.steps.slice(0, vector.expected_suppression_step).forEach((step, index) => {
      const result = reduceAutomationBatch(program, checkpoint, [input(step.input)]);
      checkpoint = result.checkpoint;
      if (index + 1 === vector.expected_action_step) actionCount = result.action_requests.length;
      if (index + 1 === vector.expected_suppression_step) {
        suppression = result.audits.find((audit) => audit.automation_id === vector.expected_automation_id)?.suppression_reason;
      }
    });
    expect(actionCount).toBe(vector.expected_action_count_at_step);
    expect(suppression).toBe(vector.expected_suppression_reason);
  });

  it('rejects non-canonical checkpoint encodings', () => {
    for (const scenario of corpus.scenarios) for (const step of scenario.steps) {
      expect(encodeAutomationCheckpoint(decodeAutomationCheckpoint(step.expected.checkpoint))).toBe(step.expected.checkpoint);
      expect(() => decodeAutomationCheckpoint(`${step.expected.checkpoint}=`)).toThrow();
    }
  });
});

function validConfigurationForFloatTest(): StudyConfiguration {
  const scenario = corpus.scenarios[0];
  if (!scenario) throw new Error('missing conformance scenario');
  return configurationFor(scenario);
}

function configurationFor(scenario: Scenario): StudyConfiguration {
  return {
    ...structuredClone(scenario.configuration),
    upload: Object.keys(scenario.configuration.upload as object).length === 0
      ? null : scenario.configuration.upload
  } as StudyConfiguration;
}

function mutateReducerInput(value: ReducerInput, mutation: string): ReducerInput {
  if (value.type !== 'TIMER_MATERIALIZED') throw new Error('hostile materialization must reference TIMER_MATERIALIZED');
  if (mutation.startsWith('SHIFT_TIMER_TARGET_UTC_BY_')) {
    if (value.timer.target.type !== 'CALENDAR_UTC') throw new Error('hostile target shift requires a calendar timer');
    const delta = Number(mutation.slice('SHIFT_TIMER_TARGET_UTC_BY_'.length));
    return { ...value, timer: { ...value.timer, target: {
      type: 'CALENDAR_UTC', utc_millis: value.timer.target.utc_millis + delta
    } } };
  }
  if (mutation === 'INCREMENT_TIMER_GENERATION') {
    return { ...value, timer: { ...value.timer, generation: value.timer.generation + 1n } };
  }
  throw new Error(`unknown reducer hostile mutation: ${mutation}`);
}

function hostileConfiguration(base: StudyConfiguration, mutation: string): StudyConfiguration {
  const configuration = structuredClone(base);
  const addOccurrence = (
    id: string,
    trigger: Extract<StudyConfiguration['automations'][number], { type: 'occurrence' }>['trigger'],
    guard: StateCondition | null = null
  ) => {
    const interventionId = `${id}-intervention`;
    configuration.interventions.push({
      id: interventionId, required: false,
      action: { type: 'notification', notification_title: 'Title', notification_message: 'Message' }
    });
    configuration.automations.push({
      type: 'occurrence', id, trigger, guard, intervention_id: interventionId,
      availability_seconds: 60, cooldown: null, maximum_activations: 1
    });
  };
  if (mutation === 'ADD_DENSE_TRIGGER_AND_GUARD') {
    const dense = (): StateCondition => ({
      type: 'all', conditions: Array.from({ length: 8 }, () => ({
        type: 'any', conditions: Array.from({ length: 4 }, (): StateCondition => ({ type: 'study_session_active' }))
      }))
    });
    addOccurrence('hostile-dense', { type: 'condition_rising_edge', condition: dense() }, dense());
  } else if (mutation === 'ADD_513_CONCURRENT_TIMERS') {
    for (let index = 0; index < 57; index += 1) {
      const guard: StateCondition = {
        type: 'all', conditions: Array.from({ length: 8 }, (): StateCondition => ({
          type: 'held_for', duration_seconds: 1, clock: 'ACTIVE_RUNNING_TIME',
          condition: { type: 'study_session_active' }
        }))
      };
      addOccurrence(`hostile-timer-${index.toString().padStart(2, '0')}`, {
        type: 'schedule', schedule: { type: 'one_time', offset_minutes: 0, clock: 'ACTIVE_RUNNING_TIME' }
      }, guard);
    }
  } else if (mutation === 'USE_AUDIT_ONLY_STUDY_RUNNING_EVENT') {
    addOccurrence('hostile-lifecycle-feedback', {
      type: 'event_match', evaluation_clock: 'OBSERVED_RESEARCH_TIME',
      selector: {
        event: { source_id: 'study_runtime.v1', schema_version: 1, event_type: 'STUDY_RUNNING' },
        predicates: []
      }
    });
  } else if (mutation.startsWith('ADD_RANDOM_')) {
    let windows: { start_local_time: string; end_local_time: string }[];
    let daily: number;
    let separation: number;
    if (mutation === 'ADD_RANDOM_DAILY_CAPACITY_OVERFLOW') {
      [windows, daily, separation] = [[{ start_local_time: '08:00', end_local_time: '09:00' }], 2, 1];
    } else if (mutation === 'ADD_RANDOM_ADJACENT_SEPARATION_VIOLATION') {
      [windows, daily, separation] = [[
        { start_local_time: '08:00', end_local_time: '09:00' },
        { start_local_time: '09:30', end_local_time: '10:30' }
      ], 2, 32];
    } else if (mutation === 'ADD_RANDOM_CYCLIC_SEPARATION_VIOLATION') {
      [windows, daily, separation] = [[
        { start_local_time: '00:30', end_local_time: '01:30' },
        { start_local_time: '23:00', end_local_time: '23:30' }
      ], 2, 62];
    } else throw new Error(`unknown compiler hostile mutation: ${mutation}`);
    addOccurrence('hostile-random', { type: 'schedule', schedule: {
      type: 'random_window', local_windows: windows, occurrences_per_window: 1,
      maximum_occurrences_per_day: daily, maximum_occurrences_total: 10,
      minimum_separation_minutes: separation
    } });
  } else if (mutation === 'SET_61_ASTRAL_TITLE') {
    configuration.title = '😀'.repeat(61);
  } else if (mutation === 'SWAP_PRESENCE_ENTER_EXIT') {
    const presence = firstKeyedPresence(configuration);
    [presence.enter_when, presence.exit_when] = [presence.exit_when, presence.enter_when];
  } else if (mutation === 'SET_PRESENCE_KEY_TO_PACKAGE_NAME') {
    firstKeyedPresence(configuration).key_field = 'package_name';
  } else if (mutation === 'USE_NON_PRESENCE_ENTER_EVENT') {
    firstKeyedPresence(configuration).enter_when[0].event.event_type = 'DEVICE_STARTUP';
  } else if (mutation === 'ALTER_EXIT_PRESENCE_GROUP_CONTRACT') {
    // The shared hostile mutates the generated contract around compilation in the test above.
  } else if (mutation === 'DECLARE_64_COLLECTORS_AND_TRAFFIC') {
    configuration.collectors = Array.from({ length: 64 }, (_, index) => ({
      id: `synthetic_${index.toString().padStart(2, '0')}.v1`, required: false,
      profiles: [{ id: 'continuous', config: {} }]
    })) as unknown as StudyConfiguration['collectors'];
  } else throw new Error(`unknown compiler hostile mutation: ${mutation}`);
  configuration.interventions.sort((left, right) => left.id.localeCompare(right.id));
  configuration.automations.sort((left, right) => left.id.localeCompare(right.id));
  return configuration;
}

function firstKeyedPresence(configuration: StudyConfiguration): Extract<StateCondition, { type: 'keyed_presence' }> {
  const visit = (condition: StateCondition): Extract<StateCondition, { type: 'keyed_presence' }> | null => {
    switch (condition.type) {
      case 'keyed_presence': return condition;
      case 'held_for': case 'not': return visit(condition.condition);
      case 'all': case 'any':
        for (const child of condition.conditions) {
          const found = visit(child);
          if (found) return found;
        }
        return null;
      default: return null;
    }
  };
  for (const automation of configuration.automations) {
    const conditions = automation.type === 'resource_binding'
      ? automation.cases.map((entry) => entry.condition)
      : [automation.guard, automation.trigger.type === 'condition_rising_edge' ? automation.trigger.condition : null];
    for (const condition of conditions) {
      if (!condition) continue;
      const found = visit(condition);
      if (found) return found;
    }
  }
  throw new Error('shared presence hostile base has no keyed-presence condition');
}

function input(value: InputWire): ReducerInput {
  const common = { type: value.type, sequence_number: value.sequence_number, clock: clock(value.clock) };
  switch (value.type) {
    case 'EVENT': return {
      ...common, type: 'EVENT', event: {
        ...value.event!, observed_time: time(value.event!.observed_time),
        primary_source_time: value.event!.primary_source_time ? time(value.event!.primary_source_time) : null
      }
    };
    case 'LIFECYCLE': return { ...common, type: 'LIFECYCLE', state: value.state! };
    case 'TIMER_DUE': return {
      ...common, type: 'TIMER_DUE', timer_id: value.timer_id!, automation_id: value.automation_id!,
      generation: BigInt(value.generation!), causal_sequence: value.causal_sequence!, target: target(value.target!),
      logical_due: time(value.logical_due!)
    };
    case 'TIMER_MATERIALIZED': return { ...common, type: 'TIMER_MATERIALIZED', timer: timer(value.timer!) };
    case 'QUALITY_GAP': return { ...common, type: 'QUALITY_GAP', source_id: value.source_id! };
    case 'CLOCK_DISCONTINUITY': return {
      ...common, type: 'CLOCK_DISCONTINUITY', restart_resources: value.restart_resources!
    };
  }
}

function time(value: TimeWire): ResearchTime {
  return { wall_time_utc_millis: value.wall_time_utc_millis, elapsed_realtime_nanos: BigInt(value.elapsed_realtime_nanos), boot_session_id: value.boot_session_id };
}

function clock(value: ClockWire): ReducerClock {
  return { now: time(value.now), active_elapsed_nanos: BigInt(value.active_elapsed_nanos),
    calendar_elapsed_nanos: BigInt(value.calendar_elapsed_nanos), zone_id: value.zone_id };
}

function target(value: TargetWire): TimerTarget {
  if (value.type === 'CALENDAR_UTC') return { type: value.type, utc_millis: value.utc_millis! };
  if (value.type === 'ACTIVE_ELAPSED') return { type: value.type, elapsed_nanos: BigInt(value.elapsed_nanos!) };
  return { type: value.type, boot_session_id: value.boot_session_id!, elapsed_realtime_nanos: BigInt(value.elapsed_realtime_nanos!) };
}

function timer(value: TimerWire): DurableTimer {
  return { ...value, generation: BigInt(value.generation), target: target(value.target) };
}

function timerJson(value: DurableTimer): TimerWire {
  return { ...value, generation: String(value.generation), target: targetJson(value.target) };
}

function targetJson(value: TimerTarget): TargetWire {
  if (value.type === 'CALENDAR_UTC') return { type: value.type, utc_millis: value.utc_millis };
  if (value.type === 'ACTIVE_ELAPSED') return { type: value.type, elapsed_nanos: String(value.elapsed_nanos) };
  return { type: value.type, boot_session_id: value.boot_session_id, elapsed_realtime_nanos: String(value.elapsed_realtime_nanos) };
}

function expected(result: ReturnType<typeof reduceAutomationBatch>): ExpectedWire {
  return {
    checkpoint: encodeAutomationCheckpoint(result.checkpoint),
    checkpoint_sha256: automationCheckpointDigest(result.checkpoint),
    actions: result.action_requests,
    timer_intents: result.timer_intents.map((intent) => intent.type === 'SCHEDULE'
      ? { type: intent.type, timer: timerJson(intent.timer) }
      : { type: intent.type, timer_id: intent.timer_id, generation: String(intent.generation) }),
    timer_production_requests: result.timer_production_requests.map((request) => ({
      automation_id: request.automation.id, schedule_type: request.schedule.type,
      causal_sequence: request.causal_sequence, current_generation: String(request.current_generation),
      pending_timer_id: request.pending_timer?.id ?? null,
      materialized_producer_keys: request.materialized.map((summary) => summary.producer_key)
    })),
    resource_changes: [...result.resource_changes.values()]
      .sort((left, right) => `${left.key.kind.toLowerCase()}\0${left.key.id}`.localeCompare(`${right.key.kind.toLowerCase()}\0${right.key.id}`))
      .map(({ key, desired }) => ({ kind: key.kind, id: key.id, generation: String(desired.generation), profile_id: desired.profile_id })),
    audits: result.audits
  };
}

interface CompilerHostileCase {
  id: string; base_scenario_id: string; mutation: string; expected_rejection: string;
}
interface ReducerHostileCase { id: string; scenario_id: string; step: number; mutation: string }
interface ReducerPropertyCase {
  id: string; scenario_id: string; mutation: string; expected_automation_id: string;
  expected_action_step: number; expected_action_count_at_step: number;
  expected_suppression_step: number; expected_suppression_reason: string;
}
interface AtomicBatchCase {
  id: string; scenario_id: string; base_checkpoint_after_step: number;
  expected_desired_resource_relation: 'UNCHANGED'; expected_resource_changes: object[];
  resource: { kind: 'COLLECTOR' | 'ACTUATOR'; id: string };
  inputs: { source_step: number; sequence_number: number }[];
}
interface Corpus {
  format: string; registry_sha256: string; compiler_hostile_cases: CompilerHostileCase[];
  batch_semantics: { step_boundary: string; partition_rule: string }; atomic_batch_cases: AtomicBatchCase[];
  reducer_hostile_cases: ReducerHostileCase[]; reducer_property_cases: ReducerPropertyCase[]; scenarios: Scenario[];
}
interface Scenario {
  id: string; configuration_sha256: string; configuration: StudyConfiguration & { upload: object };
  final_checkpoint_sha256: string; stream_partition_ranges: { first_step: number; last_step: number }[];
  steps: { input: InputWire; expected: ExpectedWire }[];
}
interface TimeWire { wall_time_utc_millis: number; elapsed_realtime_nanos: string; boot_session_id: string }
interface ClockWire { now: TimeWire; active_elapsed_nanos: string; calendar_elapsed_nanos: string; zone_id: string }
interface TargetWire {
  type: 'CALENDAR_UTC' | 'ACTIVE_ELAPSED' | 'SAME_BOOT_MONOTONIC'; utc_millis?: number; elapsed_nanos?: string;
  boot_session_id?: string; elapsed_realtime_nanos?: string;
}
interface TimerWire {
  id: string; automation_id: string; generation: string; causal_sequence: number; producer_key: string;
  target: TargetWire; logical_deadline_utc_millis: number | null; expires_at_utc_millis: number | null;
}
interface InputWire {
  type: ReducerInput['type']; sequence_number: number; clock: ClockWire; event?: {
    sequence_number: number; source_id: string; schema_version: number; event_type: string;
    observed_time: TimeWire; primary_source_time: TimeWire | null; fields: Record<string, string>;
  }; state?: Extract<ReducerInput, { type: 'LIFECYCLE' }>['state']; timer_id?: string; automation_id?: string;
  generation?: string; causal_sequence?: number; target?: TargetWire; logical_due?: TimeWire; timer?: TimerWire; source_id?: string;
  restart_resources?: { kind: 'COLLECTOR' | 'ACTUATOR'; id: string }[];
}
interface ExpectedWire {
  checkpoint: string; checkpoint_sha256: string; actions: object[]; timer_intents: object[];
  timer_production_requests: object[]; resource_changes: object[]; audits: object[];
}
