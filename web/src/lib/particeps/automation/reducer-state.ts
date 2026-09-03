import type {
  Aggregate,
  DurationClock,
  EvaluationClock,
  EventMatcher,
  FieldOperator,
  NumericComparison,
  OccurrenceAutomation,
  StateCondition
} from '../types';
import { eventIdentity } from './compiler';
import { actionId, deterministicDigest, timerId } from './checkpoint';
import type {
  ActionRequest,
  AutomationAudit,
  AutomationCheckpoint,
  AutomationEvent,
  CompiledAutomationProgram,
  DesiredProfile,
  DurableTimer,
  ReducerClock,
  ReducerInput,
  ResearchTime,
  ResourceKey,
  SuppressionReason,
  TimerIntent,
  TimerProductionRequest,
  TimerTarget
} from './types';
import { decodeEventWireFieldValue, decodePredicateFieldValue, type DecodedEventFieldValue } from '../registry';
import { resourceKeyString } from './types';
import { requireValidScheduleTimer } from './timers';

export interface TriggerMatch {
  causal_identity: string;
  logical_time: ResearchTime;
  logical_deadline_utc_millis: number | null;
  trigger_kind: string;
}

export type DueResolution =
  | { type: 'NONE' | 'DEFERRED' | 'STALE' }
  | { type: 'ACCEPTED'; timer: DurableTimer };

const ACTIVE_STATES = new Set(['ACTIVATING', 'RUNNING']);
const RESET_STATES = new Set(['PAUSING', 'PAUSED', 'COMPLETED', 'WITHDRAWN']);
const NANO = 1_000_000_000n;
const MILLI_NANO = 1_000_000n;
const CONDITION_PREFIX = 'condition:';

export class MutableAutomationState {
  evaluated = 0;
  lifecycle: AutomationCheckpoint['lifecycle'];
  studyStart: number | null;
  lastActive: bigint;
  lastCalendar: bigint;
  readonly latch: Map<string, boolean>;
  readonly presence: Map<string, Set<string>>;
  readonly held: Map<string, bigint>;
  readonly prior: Map<string, boolean>;
  readonly windows: AutomationCheckpoint['windows'];
  readonly sequences: AutomationCheckpoint['sequences'];
  readonly counts: Map<string, number>;
  readonly cooldowns: AutomationCheckpoint['cooldown_marks'];
  readonly desired: AutomationCheckpoint['desired_resources'];
  readonly timers: Map<string, DurableTimer>;
  readonly generations: Map<string, bigint>;
  readonly materialized: AutomationCheckpoint['materialized_timers'];
  readonly latest = new Map<string, boolean>();
  readonly forcedRestarts = new Set<string>();
  private currentSequence = 0;

  constructor(checkpoint: AutomationCheckpoint) {
    this.evaluated = checkpoint.evaluated_through_sequence;
    this.lifecycle = checkpoint.lifecycle; this.studyStart = checkpoint.study_start_utc_millis;
    this.lastActive = checkpoint.last_active_elapsed_nanos; this.lastCalendar = checkpoint.last_calendar_elapsed_nanos;
    this.latch = new Map(checkpoint.latch_values);
    this.presence = new Map([...checkpoint.presence_keys].map(([key, values]) => [key, new Set(values)]));
    this.held = new Map(checkpoint.held_since_nanos); this.prior = new Map(checkpoint.prior_condition_values);
    this.windows = new Map([...checkpoint.windows].map(([key, values]) => [key, values.map((value) => ({ ...value }))]));
    this.sequences = new Map([...checkpoint.sequences].map(([key, values]) => [key, values.map((value) => ({ ...value }))]));
    this.counts = new Map(checkpoint.activation_counts); this.cooldowns = new Map(checkpoint.cooldown_marks);
    this.desired = new Map([...checkpoint.desired_resources].map(([key, value]) => [key, { key: { ...value.key }, desired: { ...value.desired } }]));
    this.timers = new Map([...checkpoint.timers].map(([key, value]) => [key, cloneTimer(value)]));
    this.generations = new Map(checkpoint.timer_generations);
    this.materialized = new Map([...checkpoint.materialized_timers].map(([key, values]) => [key, values.map((value) => ({ ...value }))]));
  }

  begin(input: ReducerInput): void {
    if (input.clock.active_elapsed_nanos < this.lastActive || input.clock.calendar_elapsed_nanos < this.lastCalendar) {
      throw new Error('automation_clock_moved_backward');
    }
    this.currentSequence = input.sequence_number; this.latest.clear();
  }

  finish(input: ReducerInput): void {
    this.evaluated = input.sequence_number;
    this.lastActive = input.clock.active_elapsed_nanos; this.lastCalendar = input.clock.calendar_elapsed_nanos;
  }

  applyLifecycle(state: AutomationCheckpoint['lifecycle'], clock: ReducerClock, intents: TimerIntent[]): void {
    if (state !== this.lifecycle && !allowedDestinations(this.lifecycle).has(state)) throw new Error('automation_invalid_lifecycle');
    this.lifecycle = state;
    if (state === 'ACTIVATING' && this.studyStart === null) this.studyStart = clock.now.wall_time_utc_millis;
    if (RESET_STATES.has(state)) this.resetSession(intents);
  }

  resetSession(intents: TimerIntent[]): void {
    this.latch.clear(); this.presence.clear(); this.held.clear(); this.prior.clear(); this.windows.clear(); this.sequences.clear();
    for (const timer of [...this.timers.values()]) if (timer.producer_key.startsWith(CONDITION_PREFIX)) {
      intents.push({ type: 'RETIRE', timer_id: timer.id, generation: timer.generation }); this.timers.delete(timer.id);
    }
  }

  resetCalendar(intents: TimerIntent[]): void {
    const retired = [...this.timers.values()].filter((timer) => timer.target.type === 'CALENDAR_UTC');
    for (const timer of retired) { intents.push({ type: 'RETIRE', timer_id: timer.id, generation: timer.generation }); this.timers.delete(timer.id); }
    const keys = new Set(retired.map((timer) => timer.producer_key));
    for (const [automation, summaries] of this.materialized) {
      const kept = summaries.filter((summary) => !keys.has(summary.producer_key));
      if (kept.length) this.materialized.set(automation, kept); else this.materialized.delete(automation);
    }
  }

  restartResources(program: CompiledAutomationProgram, resources: readonly ResourceKey[]): void {
    const declared = new Set(program.resource_bindings.map((binding) =>
      resourceKeyString({ kind: binding.resource.kind.toUpperCase() as ResourceKey['kind'], id: binding.resource.id })));
    for (const resource of resources) {
      const wire = resourceKeyString(resource);
      if (!declared.has(wire)) throw new Error('automation_clock_restart_undeclared_resource');
      this.forcedRestarts.add(wire);
    }
  }

  acceptDue(input: Extract<ReducerInput, { type: 'TIMER_DUE' }>, intents: TimerIntent[]): DueResolution {
    const timer = this.timers.get(input.timer_id);
    if (!timer || timer.generation !== input.generation) {
      intents.push({ type: 'RETIRE', timer_id: input.timer_id, generation: input.generation }); return { type: 'STALE' };
    }
    if (timer.automation_id !== input.automation_id || timer.causal_sequence !== input.causal_sequence ||
      !targetEquals(timer.target, input.target)) throw new Error('automation_timer_mismatch');
    if (!researchTimeEquals(input.logical_due, timerAuditCoordinate(timer))) throw new Error('automation_timer_logical_target');
    if (this.lifecycle !== 'RUNNING') return { type: 'DEFERRED' };
    if (!isDue(timer.target, input.clock)) throw new Error('automation_timer_not_due');
    this.timers.delete(timer.id); intents.push({ type: 'RETIRE', timer_id: timer.id, generation: timer.generation });
    const summaries = this.materialized.get(timer.automation_id);
    if (summaries) this.materialized.set(timer.automation_id, summaries.map((summary) =>
      summary.producer_key === timer.producer_key ? { ...summary, terminal: true } : summary));
    return { type: 'ACCEPTED', timer };
  }

  materialize(program: CompiledAutomationProgram, input: Extract<ReducerInput, { type: 'TIMER_MATERIALIZED' }>, intents: TimerIntent[]): void {
    if (this.lifecycle !== 'RUNNING') throw new Error('automation_materialize_not_running');
    const timer = input.timer;
    const automation = program.occurrence_automations.find((candidate) => candidate.id === timer.automation_id);
    if (!automation || automation.trigger.type !== 'schedule') throw new Error('automation_timer_non_schedule');
    if (timer.id !== timerId(program.configuration_sha256, timer.automation_id, timer.producer_key)) throw new Error('automation_timer_identity');
    const existing = this.timers.get(timer.id);
    if (existing) { if (!timerEquals(existing, timer)) throw new Error('automation_timer_conflict'); return; }
    if (timer.generation !== (this.generations.get(timer.automation_id) ?? 0n) + 1n) throw new Error('automation_timer_generation');
    if ((this.materialized.get(timer.automation_id) ?? []).some((summary) => summary.producer_key === timer.producer_key)) {
      throw new Error('automation_timer_producer_reused');
    }
    if (timer.causal_sequence > this.evaluated) throw new Error('automation_timer_causal_not_evaluated');
    if (this.studyStart === null) throw new Error('automation_timer_study_start_absent');
    requireValidScheduleTimer({
      configuration_sha256: program.configuration_sha256, automation, schedule: automation.trigger.schedule,
      clock: input.clock, study_start_utc_millis: this.studyStart,
      study_deadline_utc_millis: this.studyStart + program.study_duration_seconds * 1000,
      causal_sequence: timer.causal_sequence, current_generation: this.generations.get(timer.automation_id) ?? 0n,
      session_state: this.lifecycle, pending_timer: null,
      materialized: (this.materialized.get(timer.automation_id) ?? []).map((value) => ({ ...value }))
    }, timer);
    this.timers.set(timer.id, cloneTimer(timer)); this.generations.set(timer.automation_id, timer.generation);
    const summaries = this.materialized.get(timer.automation_id) ?? [];
    summaries.push({ producer_key: timer.producer_key, selected_utc_millis: timer.logical_deadline_utc_millis ?? 0, terminal: false });
    this.materialized.set(timer.automation_id, summaries); intents.push({ type: 'SCHEDULE', timer: cloneTimer(timer) });
  }

  evaluateTrigger(program: CompiledAutomationProgram, automation: OccurrenceAutomation, root: string, input: ReducerInput, due: DurableTimer | null, intents: TimerIntent[]): TriggerMatch[] {
    const trigger = automation.trigger;
    if (trigger.type === 'event_match') {
      return input.type === 'EVENT' && matches(program, trigger.selector, input.event)
        ? [eventMatch(input.event, trigger.evaluation_clock, 'event_match')] : [];
    }
    if (trigger.type === 'sequence') return this.processSequence(program, automation.id, trigger, input);
    if (trigger.type === 'window_threshold') {
      const path = `${root}:trigger:window`;
      const value = this.updateWindow(program, path, trigger.selector, trigger.window_seconds, trigger.evaluation_clock,
        trigger.aggregate, trigger.comparison, input, intents, automation.id);
      const edge = `${root}:trigger:window-edge`; const previous = this.prior.get(edge) ?? false; this.prior.set(edge, value);
      const entries = this.windows.get(path) ?? [];
      return !previous && value && input.type === 'EVENT' && entries.length
        ? [{ causal_identity: `range:${entries[0].sequence_number}:${input.event.sequence_number}`,
          logical_time: eventTime(input.event, trigger.evaluation_clock), logical_deadline_utc_millis: null,
          trigger_kind: 'window_threshold' }] : [];
    }
    if (trigger.type === 'condition_rising_edge') {
      const path = `${root}:trigger:condition`;
      const value = this.condition(program, trigger.condition, path, input, intents, automation.id);
      const edge = `${path}-edge`; const previous = this.prior.get(edge) ?? false; this.prior.set(edge, value);
      return !previous && value ? [conditionMatch(input, due)] : [];
    }
    return due?.automation_id === automation.id && !due.producer_key.startsWith(CONDITION_PREFIX)
      ? [timerMatch(input, due)] : [];
  }

  condition(program: CompiledAutomationProgram, condition: StateCondition, path: string, input: ReducerInput, intents: TimerIntent[], automationId: string): boolean {
    switch (condition.type) {
      case 'study_session_active': return ACTIVE_STATES.has(this.lifecycle);
      case 'event_latch': {
        if (input.type === 'EVENT') {
          const reset = condition.reset_when.some((matcher) => matches(program, matcher, input.event));
          const set = condition.set_when.some((matcher) => matches(program, matcher, input.event));
          if (reset) this.latch.set(path, false); else if (set) this.latch.set(path, true);
        }
        return this.latch.get(path) ?? false;
      }
      case 'keyed_presence': {
        const keys = this.presence.get(path) ?? new Set<string>(); this.presence.set(path, keys);
        if (input.type === 'EVENT') {
          const exits = condition.exit_when.some((matcher) => matches(program, matcher, input.event));
          const enters = condition.enter_when.some((matcher) => matches(program, matcher, input.event));
          const key = input.event.fields[condition.key_field];
          if ((exits || enters) && key === undefined) throw new Error('automation_presence_key_missing');
          if (exits) keys.delete(key); else if (enters) {
            if (keys.size >= 256 && !keys.has(key)) throw new Error('automation_presence_bound'); keys.add(key);
          }
        }
        return keys.size > 0;
      }
      case 'held_for': {
        const child = this.condition(program, condition.condition, `${path}:child`, input, intents, automationId);
        const now = durationNanos(condition.clock, input.clock);
        if (!child) { this.held.delete(path); this.retireConditionTimer(path, intents); return false; }
        const since = this.held.get(path) ?? now; this.held.set(path, since);
        const due = since + BigInt(condition.duration_seconds) * NANO;
        if (now >= due) { this.retireConditionTimer(path, intents); return true; }
        this.ensureConditionTimer(program, automationId, path, condition.clock, due, intents); return false;
      }
      case 'elapsed_at_least': {
        const now = durationNanos(condition.clock, input.clock); const due = BigInt(condition.duration_seconds) * NANO;
        if (now >= due) { this.retireConditionTimer(path, intents); return true; }
        this.ensureConditionTimer(program, automationId, path, condition.clock, due, intents); return false;
      }
      case 'window_threshold': return this.updateWindow(program, path, condition.selector, condition.window_seconds,
        condition.evaluation_clock, condition.aggregate, condition.comparison, input, intents, automationId);
      case 'all': return condition.conditions.map((child, index) => this.condition(program, child, `${path}:${index}`, input, intents, automationId)).every(Boolean);
      case 'any': return condition.conditions.map((child, index) => this.condition(program, child, `${path}:${index}`, input, intents, automationId)).some(Boolean);
      case 'not': return !this.condition(program, condition.condition, `${path}:not`, input, intents, automationId);
    }
  }

  requestAction(program: CompiledAutomationProgram, automation: OccurrenceAutomation, match: TriggerMatch, guard: boolean, clock: ReducerClock): { request: ActionRequest | null; audit: AutomationAudit } {
    const count = this.counts.get(automation.id) ?? 0;
    let suppression: SuppressionReason | null = null;
    if (count >= automation.maximum_activations) suppression = 'MAXIMUM_ACTIVATIONS';
    else if (!guard) suppression = 'GUARD_FALSE';
    else if (this.cooldownActive(automation, clock)) suppression = 'COOLDOWN';
    else if (match.logical_time.wall_time_utc_millis + automation.availability_seconds * 1000 <= clock.now.wall_time_utc_millis) suppression = 'EXPIRED';
    const audit = { automation_id: automation.id, matched: true, suppression_reason: suppression, causal_identity: match.causal_identity };
    if (suppression) return { request: null, audit };
    const id = actionId(program.configuration_sha256, automation.id, automation.intervention_id, match.trigger_kind,
      match.causal_identity, match.logical_deadline_utc_millis?.toString() ?? '');
    const studyDeadline = required(this.studyStart) + program.study_duration_seconds * 1000;
    const expires = Math.min(match.logical_time.wall_time_utc_millis + automation.availability_seconds * 1000, studyDeadline);
    this.counts.set(automation.id, count + 1);
    this.cooldowns.set(automation.id, { active_elapsed_nanos: clock.active_elapsed_nanos, calendar_elapsed_nanos: clock.calendar_elapsed_nanos });
    return { request: { action_id: id, automation_id: automation.id, intervention_id: automation.intervention_id,
      causal_identity: match.causal_identity, logical_deadline_utc_millis: match.logical_deadline_utc_millis,
      expires_at_utc_millis: expires }, audit };
  }

  remember(path: string, value: boolean): void { this.latest.set(path, value); }

  reconcile(program: CompiledAutomationProgram): Map<string, { key: ResourceKey; desired: DesiredProfile }> {
    const changes = new Map<string, { key: ResourceKey; desired: DesiredProfile }>();
    for (const binding of [...program.resource_bindings].sort((left, right) => resourceWire(left.resource).localeCompare(resourceWire(right.resource)))) {
      const key: ResourceKey = { kind: binding.resource.kind.toUpperCase() as ResourceKey['kind'], id: binding.resource.id };
      const selectedCase = binding.cases.find((_, index) => required(this.latest.get(`binding:${binding.id}:case:${index}`)));
      const selected = ACTIVE_STATES.has(this.lifecycle)
        ? (selectedCase ? selectedCase.profile_id : binding.default_profile_id)
        : null;
      const wire = resourceKeyString(key); const previous = this.desired.get(wire);
      const forceRestart = this.forcedRestarts.has(wire) && previous?.desired.profile_id !== null && selected !== null;
      if (!previous || previous.desired.profile_id !== selected || forceRestart) {
        const desired = { generation: (previous?.desired.generation ?? 0n) + 1n, profile_id: selected };
        const value = { key, desired }; this.desired.set(wire, value); changes.set(wire, value);
      }
    }
    return changes;
  }

  productionRequests(program: CompiledAutomationProgram, clock: ReducerClock): TimerProductionRequest[] {
    if (this.lifecycle !== 'RUNNING' || this.studyStart === null) return [];
    const deadline = this.studyStart + program.study_duration_seconds * 1000;
    return program.occurrence_automations.flatMap((automation): TimerProductionRequest[] => {
      if (automation.trigger.type !== 'schedule' || (this.counts.get(automation.id) ?? 0) >= automation.maximum_activations) return [];
      const pending = [...this.timers.values()].find((timer) => timer.automation_id === automation.id && !timer.producer_key.startsWith(CONDITION_PREFIX)) ?? null;
      return [{ configuration_sha256: program.configuration_sha256, automation, schedule: automation.trigger.schedule,
        clock, study_start_utc_millis: this.studyStart as number, study_deadline_utc_millis: deadline,
        causal_sequence: this.evaluated, current_generation: this.generations.get(automation.id) ?? 0n,
        session_state: this.lifecycle, pending_timer: pending, materialized: (this.materialized.get(automation.id) ?? []).map((value) => ({ ...value })) }];
    });
  }

  freeze(): AutomationCheckpoint {
    return {
      evaluated_through_sequence: this.evaluated, lifecycle: this.lifecycle, study_start_utc_millis: this.studyStart,
      last_active_elapsed_nanos: this.lastActive, last_calendar_elapsed_nanos: this.lastCalendar,
      latch_values: sortedMap(this.latch), presence_keys: sortedMap(new Map([...this.presence].filter(([, values]) => values.size).map(([key, values]) => [key, new Set([...values].sort())]))),
      held_since_nanos: sortedMap(this.held), prior_condition_values: sortedMap(this.prior),
      windows: sortedMap(new Map([...this.windows].filter(([, values]) => values.length).map(([key, values]) => [key, values.map((value) => ({ ...value }))]))),
      sequences: sortedMap(new Map([...this.sequences].filter(([, values]) => values.length).map(([key, values]) => [key, values.map((value) => ({ ...value }))]))),
      activation_counts: sortedMap(this.counts), cooldown_marks: sortedMap(this.cooldowns), desired_resources: new Map(this.desired),
      timers: sortedMap(this.timers), timer_generations: sortedMap(this.generations), materialized_timers: sortedMap(this.materialized)
    };
  }

  private processSequence(program: CompiledAutomationProgram, automationId: string,
    trigger: Extract<OccurrenceAutomation['trigger'], { type: 'sequence' }>, input: ReducerInput): TriggerMatch[] {
    if (input.type !== 'EVENT') return [];
    const time = eventTime(input.event, trigger.evaluation_clock); const path = `occurrence:${automationId}:trigger:sequence`;
    const retained = this.sequences.get(path) ?? []; const next = []; const found: TriggerMatch[] = [];
    const window = BigInt(trigger.within_seconds) * NANO;
    for (const partial of retained) {
      if (partial.boot_session_id !== time.boot_session_id) continue;
      if (time.elapsed_realtime_nanos < partial.first_time_nanos) throw new Error('automation_sequence_time_backward');
      if (time.elapsed_realtime_nanos - partial.first_time_nanos > window) continue;
      if (matches(program, trigger.steps[partial.next_step], input.event)) {
        if (partial.next_step === trigger.steps.length - 1) found.push({ causal_identity: `range:${partial.first_sequence_number}:${input.event.sequence_number}`,
          logical_time: time, logical_deadline_utc_millis: null, trigger_kind: 'sequence' });
        else next.push({ ...partial, next_step: partial.next_step + 1, last_sequence_number: input.event.sequence_number });
      } else next.push(partial);
    }
    if (matches(program, trigger.steps[0], input.event)) next.push({ next_step: 1, first_sequence_number: input.event.sequence_number,
      last_sequence_number: input.event.sequence_number, first_time_nanos: time.elapsed_realtime_nanos, boot_session_id: time.boot_session_id });
    if (next.length > 4096) throw new Error('automation_sequence_bound'); this.sequences.set(path, next); return found;
  }

  private updateWindow(program: CompiledAutomationProgram, path: string, selector: EventMatcher, seconds: number,
    clock: EvaluationClock, aggregate: Aggregate, comparison: NumericComparison, input: ReducerInput,
    intents: TimerIntent[], automationId: string): boolean {
    const event = input.type === 'EVENT' ? input.event : null; const time = event ? eventTime(event, clock) : input.clock.now;
    const earliest = time.elapsed_realtime_nanos - BigInt(seconds) * NANO;
    const entries = (this.windows.get(path) ?? []).filter((entry) => entry.boot_session_id === time.boot_session_id && entry.time_nanos > earliest);
    if (event) {
      const last = entries.at(-1); if (last && time.elapsed_realtime_nanos < last.time_nanos) throw new Error('automation_window_time_backward');
      if (matches(program, selector, event)) {
        entries.push({ sequence_number: event.sequence_number, time_nanos: time.elapsed_realtime_nanos,
          boot_session_id: time.boot_session_id, numeric_value: aggregate.type === 'count' ? 1n : BigInt(required(event.fields[aggregate.field])) });
        if (entries.length > 4096) throw new Error('automation_window_bound');
      }
    }
    this.windows.set(path, entries);
    if (entries.length) this.ensureConditionTarget(program, automationId, path, { type: 'SAME_BOOT_MONOTONIC',
      boot_session_id: entries[0].boot_session_id, elapsed_realtime_nanos: entries[0].time_nanos + BigInt(seconds) * NANO }, intents);
    else this.retireConditionTimer(path, intents);
    const value = aggregate.type === 'count' ? BigInt(entries.length) : entries.reduce((sum, entry) => sum + entry.numeric_value, 0n);
    return compareBigInt(value, comparison.operator, BigInt(comparison.value));
  }

  private cooldownActive(automation: OccurrenceAutomation, clock: ReducerClock): boolean {
    if (!automation.cooldown) return false; const mark = this.cooldowns.get(automation.id); if (!mark) return false;
    const elapsed = automation.cooldown.clock === 'ACTIVE_RUNNING_TIME'
      ? clock.active_elapsed_nanos - mark.active_elapsed_nanos : clock.calendar_elapsed_nanos - mark.calendar_elapsed_nanos;
    return elapsed < BigInt(automation.cooldown.duration_seconds) * NANO;
  }

  private ensureConditionTimer(program: CompiledAutomationProgram, automation: string, path: string, clock: DurationClock, due: bigint, intents: TimerIntent[]): void {
    if (!ACTIVE_STATES.has(this.lifecycle)) return;
    const target: TimerTarget = clock === 'ACTIVE_RUNNING_TIME' ? { type: 'ACTIVE_ELAPSED', elapsed_nanos: due }
      : { type: 'CALENDAR_UTC', utc_millis: required(this.studyStart) + Number(due / MILLI_NANO) };
    this.ensureConditionTarget(program, automation, path, target, intents);
  }

  private ensureConditionTarget(program: CompiledAutomationProgram, automation: string, path: string, target: TimerTarget, intents: TimerIntent[]): void {
    if (!ACTIVE_STATES.has(this.lifecycle)) return;
    const producer = conditionProducer(path); const id = timerId(program.configuration_sha256, automation, producer);
    const existing = this.timers.get(id); if (existing && targetEquals(existing.target, target)) return;
    if (existing) { this.timers.delete(id); intents.push({ type: 'RETIRE', timer_id: id, generation: existing.generation }); }
    const generation = (this.generations.get(producer) ?? 0n) + 1n;
    const timer: DurableTimer = { id, automation_id: automation, generation, causal_sequence: this.currentSequence,
      producer_key: producer, target, logical_deadline_utc_millis: target.type === 'CALENDAR_UTC' ? target.utc_millis : null,
      expires_at_utc_millis: null };
    this.timers.set(id, timer); this.generations.set(producer, generation); intents.push({ type: 'SCHEDULE', timer });
  }

  private retireConditionTimer(path: string, intents: TimerIntent[]): void {
    const producer = conditionProducer(path); const timer = [...this.timers.values()].find((candidate) => candidate.producer_key === producer);
    if (!timer) return; this.timers.delete(timer.id); intents.push({ type: 'RETIRE', timer_id: timer.id, generation: timer.generation });
  }
}

export function matches(program: CompiledAutomationProgram, matcher: EventMatcher, event: AutomationEvent): boolean {
  if (event.source_id !== matcher.event.source_id || event.schema_version !== matcher.event.schema_version || event.event_type !== matcher.event.event_type) return false;
  const contract = required(program.contracts.get(eventIdentity(event.source_id, event.schema_version, event.event_type)));
  return matcher.predicates.every((predicate) => {
    const raw = event.fields[predicate.field]; if (raw === undefined) return false;
    const field = required(contract.fields[predicate.field]);
    const actual = decodeEventWireFieldValue(field, raw, contract.maximum_encoded_event_bytes);
    if (predicate.operator === 'in') return predicate.values.some((value) =>
      compare(actual, decodePredicateFieldValue(field, value, contract.maximum_encoded_event_bytes), 'eq'));
    return compare(
      actual,
      decodePredicateFieldValue(field, predicate.value, contract.maximum_encoded_event_bytes),
      predicate.operator
    );
  });
}

function compare(left: DecodedEventFieldValue, right: DecodedEventFieldValue, operator: FieldOperator): boolean {
  if (typeof left !== typeof right) throw new Error('automation_field_type');
  const order = left < right ? -1 : left > right ? 1 : 0;
  switch (operator) { case 'eq': return order === 0; case 'ne': return order !== 0; case 'lt': return order < 0;
    case 'lte': return order <= 0; case 'gt': return order > 0; case 'gte': return order >= 0; case 'in': throw new Error('automation_in'); }
}

function compareBigInt(left: bigint, operator: FieldOperator, right: bigint): boolean {
  switch (operator) { case 'eq': return left === right; case 'ne': return left !== right; case 'lt': return left < right;
    case 'lte': return left <= right; case 'gt': return left > right; case 'gte': return left >= right; case 'in': throw new Error('automation_in'); }
}

function durationNanos(clock: DurationClock, input: ReducerClock): bigint {
  return clock === 'ACTIVE_RUNNING_TIME' ? input.active_elapsed_nanos : input.calendar_elapsed_nanos;
}

function eventTime(event: AutomationEvent, clock: EvaluationClock): ResearchTime {
  if (clock === 'OBSERVED_RESEARCH_TIME') return event.observed_time;
  return required(event.primary_source_time);
}

function eventMatch(event: AutomationEvent, clock: EvaluationClock, kind: string): TriggerMatch {
  return { causal_identity: `event:${event.sequence_number}`, logical_time: eventTime(event, clock),
    logical_deadline_utc_millis: null, trigger_kind: kind };
}

function conditionMatch(input: ReducerInput, timer: DurableTimer | null): TriggerMatch {
  if (input.type === 'TIMER_DUE' && timer) return { ...timerMatch(input, timer), trigger_kind: 'condition_rising_edge' };
  return { causal_identity: `event:${input.sequence_number}`, logical_time: input.clock.now,
    logical_deadline_utc_millis: null, trigger_kind: 'condition_rising_edge' };
}

function timerMatch(input: ReducerInput, timer: DurableTimer): TriggerMatch {
  return { causal_identity: `timer:${timer.id}`, logical_time: input.type === 'TIMER_DUE' && timer.logical_deadline_utc_millis !== null ? input.logical_due : input.clock.now,
    logical_deadline_utc_millis: timer.logical_deadline_utc_millis, trigger_kind: 'schedule' };
}

function timerAuditCoordinate(timer: DurableTimer): ResearchTime {
  const target = timer.target;
  if (target.type === 'CALENDAR_UTC') return {
    wall_time_utc_millis: target.utc_millis, elapsed_realtime_nanos: 0n, boot_session_id: 'calendar-time'
  };
  if (target.type === 'ACTIVE_ELAPSED') return {
    wall_time_utc_millis: 0, elapsed_realtime_nanos: target.elapsed_nanos, boot_session_id: 'active-running-time'
  };
  return {
    wall_time_utc_millis: timer.logical_deadline_utc_millis ?? 0,
    elapsed_realtime_nanos: target.elapsed_realtime_nanos,
    boot_session_id: target.boot_session_id
  };
}

function researchTimeEquals(left: ResearchTime, right: ResearchTime): boolean {
  return left.wall_time_utc_millis === right.wall_time_utc_millis &&
    left.elapsed_realtime_nanos === right.elapsed_realtime_nanos &&
    left.boot_session_id === right.boot_session_id;
}

function conditionProducer(path: string): string {
  return CONDITION_PREFIX + deterministicDigest('particeps-condition-timer-key-v1', [path]).slice(0, 40);
}

function allowedDestinations(from: AutomationCheckpoint['lifecycle']): Set<AutomationCheckpoint['lifecycle']> {
  switch (from) {
    case 'READY': return new Set(['ACTIVATING', 'WITHDRAWN']);
    case 'ACTIVATING': return new Set(['RUNNING', 'PAUSING']);
    case 'RUNNING': return new Set(['PAUSING', 'COMPLETED', 'WITHDRAWN']);
    case 'PAUSING': return new Set(['PAUSED', 'COMPLETED', 'WITHDRAWN']);
    case 'PAUSED': return new Set(['ACTIVATING', 'COMPLETED', 'WITHDRAWN']);
    default: return new Set();
  }
}

function isDue(target: TimerTarget, clock: ReducerClock): boolean {
  switch (target.type) {
    case 'CALENDAR_UTC': return clock.now.wall_time_utc_millis >= target.utc_millis;
    case 'ACTIVE_ELAPSED': return clock.active_elapsed_nanos >= target.elapsed_nanos;
    case 'SAME_BOOT_MONOTONIC': return clock.now.boot_session_id === target.boot_session_id && clock.now.elapsed_realtime_nanos >= target.elapsed_realtime_nanos;
  }
}

function targetEquals(left: TimerTarget, right: TimerTarget): boolean { return JSON.stringify(bigintJson(left)) === JSON.stringify(bigintJson(right)); }
function timerEquals(left: DurableTimer, right: DurableTimer): boolean { return JSON.stringify(bigintJson(left)) === JSON.stringify(bigintJson(right)); }
function bigintJson(value: unknown): unknown { return typeof value === 'bigint' ? String(value) : Array.isArray(value) ? value.map(bigintJson) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, bigintJson(item)])) : value; }
function cloneTimer(timer: DurableTimer): DurableTimer { return { ...timer, target: { ...timer.target } }; }
function resourceWire(resource: { kind: string; id: string }): string { return `${resource.kind}\0${resource.id}`; }
function sortedMap<T>(map: Map<string, T>): Map<string, T> { return new Map([...map].sort(([left], [right]) => left.localeCompare(right))); }
function required<T>(value: T | undefined | null): T { if (value === undefined || value === null) throw new Error('automation_required'); return value; }
