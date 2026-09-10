import { describe, expect, it } from 'vitest';
import type { ResearchExperiment } from '../src/lib/particeps/bundle';
import { bundleSummary } from '../src/routes/researcher/bundle-summary';

function experiment(overrides: Partial<ResearchExperiment> = {}): ResearchExperiment {
  return { first_commit_sequence: '1', last_commit_sequence: '3', commit_count: '3', durable_through_commit: '3', lifetime_data_event_count: '1', event_count: '100', commits: [], ...overrides } as ResearchExperiment;
}
describe('bundle coverage summary', () => {
  it('uses commit coverage independently of lifetime collector totals and system events', () => {
    expect(bundleSummary(experiment()).completeThroughHead).toBe(true);
    expect(bundleSummary(experiment({first_commit_sequence:'2',commit_count:'2'})).completeThroughHead).toBe(false);
    expect(bundleSummary(experiment({last_commit_sequence:'2',commit_count:'2'})).completeThroughHead).toBe(false);
  });
  it('does not invent a reversed range for an empty file', () => {
    expect(bundleSummary(experiment({first_commit_sequence:'1',last_commit_sequence:'0',commit_count:'0',durable_through_commit:'0'}))).toMatchObject({commitRange:'—',completeThroughHead:true});
    expect(bundleSummary(experiment({first_commit_sequence:'4',last_commit_sequence:'3',commit_count:'0'})).completeThroughHead).toBe(false);
  });
  it('counts collector events separately from the all-event ordinal', () => {
    const value = experiment({ commits: [{ events: [{source_id:'accelerometer.v1'},{source_id:'engine.lifecycle.v1'},{source_id:'screen_state.v1'}] }] as ResearchExperiment['commits'] });
    expect(bundleSummary(value).collectorEvents).toBe(2);
  });
});
