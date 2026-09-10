import { canonicalize } from '$lib/particeps/canonical';
import { continuousBinding, defaultCollector, validate } from '$lib/particeps/schema';
import { trafficShapingEnabled, type EventMatcher, type ResourceBindingAutomation, type StudyConfiguration } from '$lib/particeps/types';

export interface TrafficExampleSnapshot {
  collectors: StudyConfiguration['collectors'];
  automations: StudyConfiguration['automations'];
}
export interface TrafficExampleReview {
  before: TrafficExampleSnapshot;
  after: TrafficExampleSnapshot;
  /** The complete configuration fingerprint prevents applying an out-of-date preview. */
  basis: string;
  changedBefore: unknown;
  changedAfter: unknown;
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value));
const usageOwner = (configuration: Pick<StudyConfiguration, 'automations'>) => configuration.automations.find((automation): automation is ResourceBindingAutomation => automation.type === 'resource_binding' && automation.resource.kind === 'collector' && automation.resource.id === 'usage_events.v1');
const trafficOwner = (configuration: Pick<StudyConfiguration, 'automations'>) => configuration.automations.find((automation): automation is ResourceBindingAutomation => automation.type === 'resource_binding' && automation.resource.kind === 'actuator' && automation.resource.id === 'traffic-shaping.v1');
function changedParts(configuration: Pick<StudyConfiguration, 'collectors' | 'automations'>) {
  return {
    usage_collector: configuration.collectors.find((collector) => collector.id === 'usage_events.v1') ?? null,
    usage_rule: usageOwner(configuration) ?? null,
    traffic_rule: trafficOwner(configuration) ?? null
  };
}
function matcher(event_type: string, packageName: string): EventMatcher {
  return { event: { source_id: 'usage_events.v1', schema_version: 1, event_type }, predicates: [{ field: 'package_name', operator: 'eq', value: packageName }] };
}
/** Build a reviewable replacement. No live draft data is changed while preparing the review. */
export function reviewTrafficExample(configuration: StudyConfiguration): TrafficExampleReview {
  if (!trafficShapingEnabled(configuration.traffic_shaping) || configuration.traffic_shaping.target_packages === 'all' || !configuration.traffic_shaping.target_packages[0]) throw new Error('example_requires_package');
  const candidate = copy(configuration);
  const shaping = configuration.traffic_shaping;
  if (!shaping.profiles.some((profile) => profile.uplink_kbps !== null || profile.downlink_kbps !== null)) throw new Error('example_requires_capped_profile');
  const owner = trafficOwner(candidate);
  if (!owner) throw new Error('example_requires_traffic_rule');
  const baseline = shaping.profiles.find((profile) => profile.id === owner.default_profile_id);
  if (!baseline) throw new Error('example_requires_default_profile');
  const slowerOrEqual = (next: number | null, previous: number | null) => previous === null || (next !== null && next <= previous);
  const strictlySlower = (next: number | null, previous: number | null) => next !== null && (previous === null || next < previous);
  const slow = shaping.profiles.find((profile) => profile.id !== baseline.id &&
    slowerOrEqual(profile.uplink_kbps, baseline.uplink_kbps) && slowerOrEqual(profile.downlink_kbps, baseline.downlink_kbps) &&
    (strictlySlower(profile.uplink_kbps, baseline.uplink_kbps) || strictlySlower(profile.downlink_kbps, baseline.downlink_kbps)));
  if (!slow) throw new Error('example_requires_slower_profile');
  let usage = candidate.collectors.find((collector) => collector.id === 'usage_events.v1');
  if (!usage) {
    candidate.collectors.push(defaultCollector('usage_events.v1'));
    usage = candidate.collectors.find((collector) => collector.id === 'usage_events.v1');
    if (!usage) throw new Error('example_requires_usage_rule');
    candidate.collectors.sort((left, right) => left.id.localeCompare(right.id));
    const binding = continuousBinding(usage);
    const used = new Set(candidate.automations.map((automation) => automation.id));
    let ordinal = 1;
    while (used.has(binding.id)) binding.id = `bind-usage-example-${ordinal++}`;
    candidate.automations.push(binding);
  }
  usage.required = true;
  for (const profile of usage.profiles) profile.config.poll_interval_seconds = 15;
  const usageBinding = usageOwner(candidate);
  if (!usageBinding || !usage.profiles[0]) throw new Error('example_requires_usage_rule');
  usageBinding.default_profile_id = usage.profiles[0].id;
  for (const entry of usageBinding.cases) entry.profile_id ??= usage.profiles[0].id;
  owner.cases = [{ condition: {
    type: 'held_for', duration_seconds: 180, clock: 'ACTIVE_RUNNING_TIME', condition: {
      type: 'keyed_presence', key_field: 'activity_component_token',
      enter_when: [matcher('ACTIVITY_RESUMED', shaping.target_packages[0])],
      exit_when: [matcher('ACTIVITY_PAUSED', shaping.target_packages[0]), matcher('ACTIVITY_STOPPED', shaping.target_packages[0])]
    }
  }, profile_id: slow.id }, { condition: { type: 'study_session_active' }, profile_id: baseline.id }];
  candidate.automations.sort((left, right) => left.id.localeCompare(right.id));
  const structuralIssues = validate(candidate).filter((issue) => /^(automations|collectors|traffic_shaping)(\.|$)/.test(issue.path));
  if (structuralIssues.length) throw new Error(`example_invalid: ${structuralIssues.map((issue) => `${issue.path}: ${issue.code}`).join('; ')}`);
  return {
    basis: canonicalize(configuration),
    before: copy({ collectors: configuration.collectors, automations: configuration.automations }),
    after: { collectors: candidate.collectors, automations: candidate.automations },
    changedBefore: changedParts(configuration), changedAfter: changedParts(candidate)
  };
}
export function applyTrafficExample(configuration: StudyConfiguration, review: TrafficExampleReview): string {
  if (canonicalize(configuration) !== review.basis) throw new Error('example_preview_stale');
  configuration.collectors = copy(review.after.collectors);
  configuration.automations = copy(review.after.automations);
  return canonicalize({ collectors: configuration.collectors, automations: configuration.automations });
}
export function undoTrafficExample(configuration: StudyConfiguration, review: TrafficExampleReview, applied: string): void {
  if (canonicalize({ collectors: configuration.collectors, automations: configuration.automations }) !== applied) throw new Error('example_undo_stale');
  configuration.collectors = copy(review.before.collectors);
  configuration.automations = copy(review.before.automations);
}
