import type { ResearchExperiment } from '$lib/particeps/bundle';
import { isCollectorId } from '$lib/particeps/types';

/** Event ordinals count both system and collector events; commit cursors describe file coverage. */
export function bundleSummary(experiment: ResearchExperiment) {
  const collectorEvents = experiment.commits.reduce((total, commit) => total + commit.events.filter(event => isCollectorId(event.source_id)).length, 0);
  const first = BigInt(experiment.first_commit_sequence);
  const last = BigInt(experiment.last_commit_sequence);
  const head = BigInt(experiment.durable_through_commit);
  const count = BigInt(experiment.commit_count);
  return {
    collectorEvents,
    commitRange: count === 0n ? '—' : `${first}–${last}`,
    completeThroughHead: count === 0n ? head === 0n : first === 1n && last === head && count === head
  };
}
