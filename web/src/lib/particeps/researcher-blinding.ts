/** Researcher-facing guard for configurations whose automation can change participant treatment. */

import { participantDisclosure, type ResourceBindingAutomation, type StudyConfiguration } from './types';
import { bindingAlwaysActive, continuousBindingProfile } from './schema';

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
  ) || disclosureMayRevealSchedule(configuration);
}

/**
 * A participant disclosure view (approximate frequency or a collection timeline) shows when and how
 * much each category collected. That can reveal a scheduled or conditional window whenever the
 * study has an occurrence automation, or a collector that is not active for the whole session —
 * so either switch on such a study needs the same researcher confirmation as a treatment.
 */
export function disclosureMayRevealSchedule(configuration: StudyConfiguration): boolean {
  const disclosure = participantDisclosure(configuration);
  if (!disclosure.approximate_frequency && !disclosure.collection_timeline) return false;
  if (configuration.automations.some((automation) => automation.type === 'occurrence')) return true;
  return configuration.collectors.some((collector) => {
    const owner = configuration.automations.find((automation): automation is ResourceBindingAutomation =>
      automation.type === 'resource_binding' && automation.resource.kind === 'collector' &&
      automation.resource.id === collector.id);
    return !owner || !bindingAlwaysActive(owner);
  });
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
