import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { canonicalize, canonicalConfigurationBytes } from '../src/lib/particeps/canonical';
import { compileAutomationProgram } from '../src/lib/particeps/automation/compiler';
import { reduceAutomationBatch } from '../src/lib/particeps/automation/reducer';
import { emptyAutomationCheckpoint, type ReducerClock } from '../src/lib/particeps/automation/types';
import { validate } from '../src/lib/particeps/schema';
import { parseConfiguration } from '../src/routes/researcher/parse';

function example() {
  return parseConfiguration(new TextEncoder().encode(canonicalize(JSON.parse(readFileSync(
    new URL('../../researcher-tools/examples/five-day-windowed-gyro-study.json', import.meta.url), 'utf8'
  )))));
}

describe('independent scheduled collector profiles', () => {
  it('round-trips the required windowed gyro example and preserves required actuator liveness', () => {
    const configuration = example();
    expect(validate(configuration)).toEqual([]);
    expect(parseConfiguration(canonicalConfigurationBytes(configuration))).toEqual(configuration);
    expect(configuration.collectors.find((collector) => collector.id === 'gyroscope.v1')?.required).toBe(true);
    expect(configuration.collectors).toHaveLength(7);
    expect(configuration.collectors.find((collector) => collector.id === 'vpn_state.v1')).toEqual({
      id: 'vpn_state.v1', required: true, profiles: [{ id: 'continuous', config: {} }]
    });
    const vpn = configuration.automations.find((automation) => automation.id === 'bind-traffic-shaping');
    if (!vpn || vpn.type !== 'resource_binding') throw new Error('missing VPN binding');
    vpn.default_profile_id = null;
    expect(validate(configuration).some((issue) => issue.code === 'trigger_source_liveness')).toBe(true);
  });

  it('uses durable boundaries to stop and restart only the scheduled collector on successive days', () => {
    const program = compileAutomationProgram(example());
    let checkpoint = emptyAutomationCheckpoint();
    let sequence = 0;
    const started = Date.parse('2026-09-07T03:59:00Z');
    const clock = (now: number): ReducerClock => ({
      now: { wall_time_utc_millis: now, elapsed_realtime_nanos: BigInt(now - started) * 1_000_000n, boot_session_id: 'schedule-boot' },
      active_elapsed_nanos: BigInt(now - started) * 1_000_000n,
      calendar_elapsed_nanos: BigInt(now - started) * 1_000_000n, zone_id: 'Asia/Taipei'
    });
    for (const state of ['ACTIVATING', 'RUNNING'] as const) {
      checkpoint = reduceAutomationBatch(program, checkpoint, [{
        type: 'LIFECYCLE', sequence_number: ++sequence, clock: clock(started), state
      }]).checkpoint;
    }
    const profile = (id: string) => [...checkpoint.desired_resources.values()].find((resource) => resource.key.id === id)?.desired.profile_id;
    expect(profile('gyroscope.v1')).toBeNull();
    for (const [time, desired] of [
      ['2026-09-07T04:00:00Z', 'continuous'], ['2026-09-07T09:00:00Z', null],
      ['2026-09-08T04:00:00Z', 'continuous'], ['2026-09-08T09:00:00Z', null]
    ] as const) {
      const timer = [...checkpoint.timers.values()].find((value) => value.automation_id === 'bind-gyroscope');
      if (!timer || timer.target.type !== 'CALENDAR_UTC') throw new Error('missing collector boundary');
      const now = clock(Date.parse(time));
      expect(timer.target.utc_millis).toBe(now.now.wall_time_utc_millis);
      checkpoint = reduceAutomationBatch(program, checkpoint, [{
        type: 'TIMER_DUE', sequence_number: ++sequence, clock: now, timer_id: timer.id,
        automation_id: timer.automation_id, generation: timer.generation,
        causal_sequence: timer.causal_sequence, target: timer.target,
        logical_due: { wall_time_utc_millis: timer.target.utc_millis, elapsed_realtime_nanos: 0n, boot_session_id: 'calendar-time' }
      }]).checkpoint;
      expect(profile('gyroscope.v1')).toBe(desired);
      expect(profile('screen_state.v1')).toBe('continuous');
      expect(profile('network_throughput.v1')).toBe('continuous');
      expect(profile('vpn_state.v1')).toBe('continuous');
    }
  });
});
