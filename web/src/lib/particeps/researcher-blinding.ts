/** Researcher-facing guard for configurations whose automation can change participant treatment. */

import type { ResourceBindingAutomation, StudyConfiguration } from './types';
import { continuousBindingProfile } from './schema';

/**
 * The authoring UI requires an explicit blinding acknowledgement whenever an automation can
 * invoke an action or change a resource while the study is active. The sole state-changing
 * exception is the generated continuous-collector binding: study active selects one collector
 * profile and study inactive selects null. That is the Protocol expression of ordinary continuous
 * collection, not a participant-varying treatment.
 */
export function requiresBlindingConfirmation(configuration: StudyConfiguration): boolean {
  return configuration.automations.some((automation) =>
    automation.type === 'occurrence' || bindingChangesTreatment(automation)
  );
}

function bindingChangesTreatment(binding: ResourceBindingAutomation): boolean {
  const outcomes = new Set([
    binding.default_profile_id,
    ...binding.cases.map((entry) => entry.profile_id)
  ]);

  if (binding.resource.kind === 'actuator') {
    // A selected actuator profile is itself a participant treatment, even if it is constant.
    return [...outcomes].some((profileId) => profileId !== null);
  }

  // Identical outcomes cannot change a collector's resource state.
  if (outcomes.size <= 1) return false;

  return !isContinuousCollectorBinding(binding);
}

function isContinuousCollectorBinding(binding: ResourceBindingAutomation): boolean {
  return continuousBindingProfile(binding) !== null;
}
