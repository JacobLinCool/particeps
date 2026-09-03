import { MutableAutomationState, type DueResolution } from './reducer-state';
import type {
  AutomationAudit,
  AutomationCheckpoint,
  CompiledAutomationProgram,
  ReducerInput,
  ReductionResult,
  TimerIntent
} from './types';

/** Pure deterministic reducer. Clocks, UUIDs, durable random selections, Android and storage are inputs. */
export function reduceAutomationBatch(
  program: CompiledAutomationProgram,
  checkpoint: AutomationCheckpoint,
  inputs: readonly ReducerInput[]
): ReductionResult {
  if (inputs.length === 0) throw new Error('automation_empty_batch');
  const mutable = new MutableAutomationState(checkpoint);
  const actions: ReductionResult['action_requests'] = [];
  const audits: AutomationAudit[] = [];
  const intents: TimerIntent[] = [];

  inputs.forEach((input, index) => {
    const expected = checkpoint.evaluated_through_sequence + index + 1;
    if (input.sequence_number !== expected) throw new Error('automation_noncontiguous_input');
    mutable.begin(input);
    let due: DueResolution = { type: 'NONE' };
    switch (input.type) {
      case 'EVENT': if (input.event.sequence_number !== input.sequence_number) throw new Error('automation_nested_sequence'); break;
      case 'LIFECYCLE': mutable.applyLifecycle(input.state, input.clock, intents); break;
      case 'TIMER_DUE': due = mutable.acceptDue(input, intents); break;
      case 'TIMER_MATERIALIZED': mutable.materialize(program, input, intents); break;
      case 'QUALITY_GAP': mutable.resetSession(intents); break;
      case 'CLOCK_DISCONTINUITY':
        mutable.resetSession(intents);
        mutable.resetCalendar(intents);
        mutable.restartResources(program, input.restart_resources);
        break;
    }
    const dueTimer = due.type === 'ACCEPTED' ? due.timer : null;
    if (due.type === 'STALE' && input.type === 'TIMER_DUE') {
      const automation = program.occurrence_automations.find((candidate) =>
        candidate.id === input.automation_id && candidate.trigger.type === 'schedule');
      if (automation) audits.push({ automation_id: automation.id, matched: false, suppression_reason: 'STALE_TIMER',
        causal_identity: `timer:${input.timer_id}` });
    }
    for (const automation of program.occurrence_automations) {
      const root = `occurrence:${automation.id}`;
      const guard = automation.guard ? mutable.condition(program, automation.guard, `${root}:guard`, input, intents, automation.id) : true;
      const matches = mutable.lifecycle === 'RUNNING' && input.type !== 'QUALITY_GAP' && input.type !== 'CLOCK_DISCONTINUITY'
        ? mutable.evaluateTrigger(program, automation, root, input, dueTimer, intents) : [];
      for (const match of matches) {
        const outcome = mutable.requestAction(program, automation, match, guard, input.clock);
        audits.push(outcome.audit); if (outcome.request) actions.push(outcome.request);
      }
    }
    for (const binding of program.resource_bindings) {
      binding.cases.forEach((entry, index) => {
        const path = `binding:${binding.id}:case:${index}`;
        mutable.remember(path, mutable.condition(program, entry.condition, path, input, intents, binding.id));
      });
    }
    mutable.finish(input);
  });

  // A durable SourceObservation/EngineCommit is indivisible. Evaluate all of its inputs before
  // allocating one final desired generation, so delayed enter+exit data has no transient effect.
  const resourceChanges = mutable.reconcile(program);
  const production = mutable.productionRequests(program, inputs.at(-1)!.clock);
  return {
    checkpoint: mutable.freeze(), action_requests: actions,
    timer_intents: dedupeAndSortIntents(intents), timer_production_requests: production,
    resource_changes: resourceChanges, audits
  };
}

function dedupeAndSortIntents(intents: readonly TimerIntent[]): TimerIntent[] {
  const unique = new Map<string, TimerIntent>();
  for (const intent of intents) {
    const timer = intent.type === 'RETIRE' ? `${intent.timer_id}:${intent.generation}`
      : `${intent.timer.id}:${intent.timer.generation}:${JSON.stringify(intent.timer, (_, value) => typeof value === 'bigint' ? String(value) : value)}`;
    unique.set(`${intent.type}:${timer}`, intent);
  }
  return [...unique.values()].sort((left, right) => {
    const leftId = left.type === 'RETIRE' ? left.timer_id : left.timer.id;
    const rightId = right.type === 'RETIRE' ? right.timer_id : right.timer.id;
    return leftId.localeCompare(rightId) || (left.type === right.type ? 0 : left.type === 'RETIRE' ? -1 : 1);
  });
}
