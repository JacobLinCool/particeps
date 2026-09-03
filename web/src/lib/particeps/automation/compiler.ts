import { sha256 } from '@noble/hashes/sha2.js';
import { canonicalConfigurationBytes } from '../canonical';
import { eventContract } from '../registry';
import { validate, type Issue } from '../schema';
import type { AutomationDefinition, EventMatcher, StateCondition, StudyConfiguration } from '../types';
import type { CompiledAutomationProgram } from './types';

export class AutomationCompilationError extends Error {
  constructor(readonly issues: readonly Issue[]) {
    super(`automation_compilation_failed:${issues.map((issue) => `${issue.path}:${issue.code}`).join(',')}`);
  }
}

/**
 * Compile an already signature-verified Protocol v1 configuration into the pure reducer program.
 * This is deliberately independent of the authoring preview. No unknown event, predicate, resource,
 * or automation shape survives `validate` into the returned closed-world program.
 */
export function compileAutomationProgram(
  configuration: StudyConfiguration,
  configurationSha256?: string
): CompiledAutomationProgram {
  const issues = validate(configuration);
  if (issues.length > 0) throw new AutomationCompilationError(issues);
  const digest = hex(sha256(canonicalConfigurationBytes(configuration)));
  if (configurationSha256 !== undefined && digest !== configurationSha256) {
    throw new Error('automation_configuration_digest_mismatch');
  }
  const contracts = new Map();
  for (const automation of configuration.automations) {
    for (const matcher of automationMatchers(automation)) {
      const contract = eventContract(matcher.event);
      if (!contract) throw new Error('automation_compiler_registry_divergence');
      contracts.set(identity(matcher), contract.event);
    }
  }
  return {
    configuration,
    configuration_sha256: digest,
    study_duration_seconds: configuration.duration_hours * 3_600,
    occurrence_automations: configuration.automations
      .filter((automation) => automation.type === 'occurrence')
      .sort((left, right) => left.id.localeCompare(right.id)),
    resource_bindings: configuration.automations
      .filter((automation) => automation.type === 'resource_binding')
      .sort((left, right) => left.id.localeCompare(right.id)),
    contracts,
    automations: configuration.automations
  };
}

export function eventIdentity(sourceId: string, schemaVersion: number, eventType: string): string {
  return `${sourceId}\0${schemaVersion}\0${eventType}`;
}

function identity(matcher: EventMatcher): string {
  return eventIdentity(matcher.event.source_id, matcher.event.schema_version, matcher.event.event_type);
}

function automationMatchers(automation: AutomationDefinition): EventMatcher[] {
  if (automation.type === 'resource_binding') return automation.cases.flatMap((entry) => conditionMatchers(entry.condition));
  const trigger = automation.trigger;
  const matchers = trigger.type === 'event_match' ? [trigger.selector]
    : trigger.type === 'sequence' ? trigger.steps
      : trigger.type === 'window_threshold' ? [trigger.selector]
        : trigger.type === 'condition_rising_edge' ? conditionMatchers(trigger.condition)
          : [];
  return automation.guard ? [...matchers, ...conditionMatchers(automation.guard)] : matchers;
}

function conditionMatchers(condition: StateCondition): EventMatcher[] {
  switch (condition.type) {
    case 'study_session_active': case 'elapsed_at_least': return [];
    case 'event_latch': return [...condition.set_when, ...condition.reset_when];
    case 'keyed_presence': return [...condition.enter_when, ...condition.exit_when];
    case 'held_for': case 'not': return conditionMatchers(condition.condition);
    case 'window_threshold': return [condition.selector];
    case 'all': case 'any': return condition.conditions.flatMap(conditionMatchers);
  }
}

function hex(bytes: Uint8Array): string {
  return [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
}
