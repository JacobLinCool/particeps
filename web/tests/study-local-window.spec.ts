import { describe, expect, it } from 'vitest';
import { studyLocalWindow } from '../src/lib/particeps/automation/timers';

const condition = { type: 'study_local_window', first_day: 3, last_day: 5, start_local_time: '12:00', end_local_time: '17:00' } as const;
const start = Date.parse('2026-09-06T02:15:00Z');
describe('participant-relative local study windows', () => {
  it('opens and closes only on days 3–5 using half-open boundaries', () => {
    const state = (now: string) => studyLocalWindow(condition, start, Date.parse(now), 'Asia/Taipei');
    expect(state('2026-09-07T06:00:00Z')).toEqual({ active: false, nextBoundary: Date.parse('2026-09-08T04:00:00Z') });
    expect(state('2026-09-08T04:00:00Z')).toEqual({ active: true, nextBoundary: Date.parse('2026-09-08T09:00:00Z') });
    expect(state('2026-09-08T09:00:00Z')).toEqual({ active: false, nextBoundary: Date.parse('2026-09-09T04:00:00Z') });
    expect(state('2026-09-10T09:00:00Z')).toEqual({ active: false, nextBoundary: null });
    expect(studyLocalWindow(condition, null, start, 'Asia/Taipei')).toEqual({ active: false, nextBoundary: null });
  });
  it('follows DST and skips nonexistent boundaries', () => {
    const start = Date.parse('2026-03-06T15:00:00Z');
    expect(studyLocalWindow(condition, start, start, 'America/New_York').nextBoundary).toBe(Date.parse('2026-03-08T16:00:00Z'));
    expect(studyLocalWindow({ ...condition, last_day: 3, start_local_time: '02:30', end_local_time: '04:00' }, start, start, 'America/New_York')).toEqual({ active: false, nextBoundary: null });
    const fall = Date.parse('2026-10-30T14:00:00Z');
    expect(studyLocalWindow({ ...condition, last_day: 3, start_local_time: '01:30', end_local_time: '02:30' }, fall, fall, 'America/New_York').nextBoundary).toBe(Date.parse('2026-11-01T05:30:00Z'));
  });
});

import { readFileSync } from 'node:fs';
import { parseConfiguration } from '../src/routes/researcher/parse';
import { validate } from '../src/lib/particeps/schema';
import { compileAutomationProgram } from '../src/lib/particeps/automation/compiler';
import { emptyAutomationCheckpoint } from '../src/lib/particeps/automation/types';
import { reduceAutomationBatch } from '../src/lib/particeps/automation/reducer';
import { canonicalize, canonicalConfigurationBytes } from '../src/lib/particeps/canonical';

it('validates the study and delivers each day once across pause/resume', () => {
  const draft = JSON.parse(readFileSync(new URL('../../researcher-tools/examples/five-day-speed-study.json', import.meta.url), 'utf8'));
  draft.upload = null;
  expect(validate(draft)).toEqual([]);
  const configuration = parseConfiguration(new TextEncoder().encode(canonicalize(JSON.parse(readFileSync(new URL('../../researcher-tools/examples/five-day-speed-study.json', import.meta.url), 'utf8')))));
  expect(validate(configuration)).toEqual([]);
  expect(parseConfiguration(canonicalConfigurationBytes(configuration))).toEqual(configuration);
  const program = compileAutomationProgram(configuration);
  let checkpoint = emptyAutomationCheckpoint();
  let sequence = 0;
  const run = (time: string, state: 'ACTIVATING' | 'RUNNING' | 'PAUSING' | 'PAUSED') => {
    const now = Date.parse(time);
    const clock = { now: { wall_time_utc_millis: now, elapsed_realtime_nanos: BigInt(now - start) * 1_000_000n, boot_session_id: 'study-boot' }, active_elapsed_nanos: 0n, calendar_elapsed_nanos: BigInt(now - start) * 1_000_000n, zone_id: 'Asia/Taipei' };
    const result = reduceAutomationBatch(program, checkpoint, [{ type: 'LIFECYCLE', sequence_number: ++sequence, clock, state }]);
    checkpoint = result.checkpoint;
    return result;
  };
  const profile = () => [...checkpoint.desired_resources.values()].find((value) => value.key.id === 'traffic-shaping.v1')?.desired.profile_id;
  run('2026-09-06T02:15:00Z', 'ACTIVATING');
  expect(run('2026-09-06T02:15:00Z', 'RUNNING').action_requests).toHaveLength(0);
  for (const date of ['08', '09', '10']) {
    run(`2026-09-${date}T04:00:00Z`, 'RUNNING');
    expect(profile()).toBe('limited-500');
    const ended = run(`2026-09-${date}T09:00:00Z`, 'RUNNING');
    expect(ended.action_requests).toHaveLength(1);
    expect(profile()).toBe('baseline');
    run(`2026-09-${date}T09:01:00Z`, 'PAUSING');
    run(`2026-09-${date}T09:01:00Z`, 'PAUSED');
    expect(run(`2026-09-${date}T09:02:00Z`, 'ACTIVATING').action_requests).toHaveLength(0);
    expect(run(`2026-09-${date}T09:02:00Z`, 'RUNNING').action_requests).toHaveLength(0);
  }
});

it('requires explicit all-app scope and rejects empty or misspelled selections', () => {
  const study = JSON.parse(readFileSync(new URL('../../researcher-tools/examples/five-day-speed-study.json', import.meta.url), 'utf8'));
  expect(study.traffic_shaping.target_packages).toBe('all');
  for (const invalid of [[], 'ALL', null, ['all']]) {
    study.traffic_shaping.target_packages = invalid;
    expect(() => parseConfiguration(new TextEncoder().encode(canonicalize(study)))).toThrow();
  }
});
