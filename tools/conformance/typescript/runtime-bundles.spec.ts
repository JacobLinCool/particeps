import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  randomBytes
} from 'node:crypto';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  bundleContext,
  calculateCommitDigest,
  calculateObservationDigest,
  configurationDigest,
  openBundle,
  type EngineCommit,
  type ResearchExperiment
} from '../../../web/src/lib/particeps/bundle';
import { canonicalBytes, canonicalize } from '../../../web/src/lib/particeps/canonical';
import type { StudyConfiguration } from '../../../web/src/lib/particeps/types';
import { parseConfiguration } from '../../../web/src/routes/researcher/parse';

/**
 * Every bundle a real runtime exported, RC13 and later, opens with the browser reader.
 *
 * The fixtures and their manifest are the ones `particeps-analysis` materializes and the Kotlin
 * `ResearchBundleVerifier` verifies. Each is encrypted to the public INSECURE demo HPKE key and
 * signed by the demo signer. Like the Kotlin fixture test, each bundle is also opened as a
 * retained partial range that starts at every recovery and at sampled resource barriers and
 * condition-epoch transitions, where a reader without history must still prove each rule.
 */

type ManifestEntry = {
  file: string;
  sha256: string;
  bundle_id: string;
  commit_count: number;
  event_count: number;
  state: string;
};

const repository = new URL('../../../', import.meta.url);
const fixtures = new URL('particeps-analysis/tests/fixtures/runtime-bundles/', repository);
const configurations = new URL(
  'researcher-tools/src/test/resources/runtime-bundle-configurations/',
  repository
);
const manifest = JSON.parse(readFileSync(new URL('manifest.json', fixtures), 'utf8')) as {
  format: string;
  fixtures: ManifestEntry[];
};
const privateKey = readFileSync(
  new URL('researcher-tools/examples/INSECURE-demo-hpke-private.key', repository),
  'utf8'
).trim();
const MAXIMUM_BOUNDARY_STARTS = 24;

const hex = (value: Uint8Array) => Buffer.from(value).toString('hex');
const sha256Hex = (value: Uint8Array) => createHash('sha256').update(value).digest('hex');

const configurationsByDigest = new Map<string, StudyConfiguration>(
  readdirSync(configurations)
    .filter((name) => name.endsWith('.json'))
    .map((name) => parseConfiguration(readFileSync(new URL(name, configurations))))
    .map((configuration) => [hex(configurationDigest(configuration)), configuration])
);

describe('real runtime bundle fixtures', () => {
  it('lists exactly the checked-in exports', () => {
    expect(manifest.format).toBe('particeps-runtime-bundle-fixtures-v1');
    const listed = manifest.fixtures.map((entry) => entry.file).sort();
    const present = readdirSync(fixtures).filter((name) => name.endsWith('.partexp')).sort();
    expect(listed).toEqual(present);
    expect(listed.length).toBeGreaterThan(0);
  });

  for (const entry of manifest.fixtures) {
    it(`opens ${entry.file} and each retained range`, async () => {
      const container = new Uint8Array(readFileSync(new URL(entry.file, fixtures)));
      expect(sha256Hex(container)).toBe(entry.sha256);
      const configuration = configurationsByDigest.get(hex(container.subarray(24, 56)));
      expect(configuration, 'configuration for the bundle header digest').toBeDefined();
      if (!configuration) return;

      const opened = await openBundle(container, configuration, privateKey);
      expect(opened).toMatchObject({ ok: true });
      if (!opened.ok) return;
      expect(opened.bundle.document.bundle_id).toBe(entry.bundle_id);
      const experiment = opened.bundle.document.experiment;
      expect(experiment).toMatchObject({
        commit_count: String(entry.commit_count),
        event_count: String(entry.event_count),
        first_commit_sequence: '1',
        state: entry.state
      });
      const document = JSON.parse(opened.bundle.text) as { experiment: ResearchExperiment };
      expect(canonicalize(document)).toBe(opened.bundle.text);

      for (const start of retainedRangeStarts(experiment)) {
        const partial = retainedRange(document, start);
        const reopened = await openBundle(
          reseal(container, partial, configuration),
          configuration,
          privateKey
        );
        expect(reopened, `retained range from commit ${start + 1}`).toMatchObject({ ok: true });
      }
    }, 120_000);
  }
});

describe('real runtime bundle shapes stay strict', () => {
  type Document = { experiment: ResearchExperiment };
  const load = async (file: string) => {
    const container = new Uint8Array(readFileSync(new URL(file, fixtures)));
    const configuration = configurationsByDigest.get(hex(container.subarray(24, 56)))!;
    const opened = await openBundle(container, configuration, privateKey);
    if (!opened.ok) throw new Error(`${file} did not open`);
    return { container, configuration, document: JSON.parse(opened.bundle.text) as Document };
  };
  // Opens commits 1..end+1 as a manual export after changing commit [end] and resealing it. The
  // unchanged prefix must open, so a rejection belongs to the change.
  const openPrefix = async (
    file: string,
    select: (commits: EngineCommit[]) => number,
    change: (commit: EngineCommit) => void
  ) => {
    const { container, configuration, document } = await load(file);
    const end = select(document.experiment.commits);
    expect(end, 'selected commit').toBeGreaterThan(0);
    const open = async (mutate: boolean) => {
      const commits = structuredClone(document.experiment.commits.slice(0, end + 1));
      if (mutate) {
        change(commits[end]);
        commits[end].commit_sha256 = calculateCommitDigest(commits[end]);
      }
      const plaintext = new TextEncoder().encode(canonicalize({
        ...document,
        experiment: {
          ...document.experiment,
          commit_count: String(commits.length),
          commits,
          event_count: String(commits.reduce((sum, commit) => sum + commit.events.length, 0)),
          last_commit_sequence: String(end + 1)
        }
      }));
      return openBundle(reseal(container, plaintext, configuration), configuration, privateKey);
    };
    expect(await open(false), 'unchanged prefix').toMatchObject({ ok: true });
    return open(true);
  };
  const flushes = (commit: EngineCommit) =>
    commit.source_observations.some((item) => item.admission_kind === 'BARRIER_FLUSH');
  const unreadable = { ok: false, failure: 'unreadable' };

  it('treats the cursor stored with a completed flush as opaque', async () => {
    await expect(openPrefix(
      'rc13-emulator-complete-from-running.partexp',
      (commits) => commits.findIndex(flushes),
      (commit) => {
        commit.successor_projection.source_checkpoints['usage_events.v1'].cursor = 'opaque-token';
      }
    )).resolves.toMatchObject({ ok: true });
  });

  it('rejects a cursor that changes without its source flush', async () => {
    await expect(openPrefix(
      'rc13-jvm-pilot-process-restart.partexp',
      (commits) => commits.findIndex((commit) =>
        !flushes(commit) &&
        Object.hasOwn(commit.successor_projection.source_checkpoints, 'usage_events.v1')
      ),
      (commit) => {
        const checkpoint = commit.successor_projection.source_checkpoints['usage_events.v1'];
        checkpoint.cursor = checkpoint.cursor === 'moved' ? 'moved-again' : 'moved';
      }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a barrier flush outside the commit that closes its epoch', async () => {
    await expect(openPrefix(
      'rc13-jvm-pilot-process-restart.partexp',
      (commits) => commits.findIndex((commit) =>
        commit.source_observations.some((item) =>
          item.source_id === 'usage_events.v1' && item.admission_kind === 'NORMAL') &&
        !commit.events.some((event) => event.event_type === 'CONDITION_EPOCH_DEACTIVATED')
      ),
      (commit) => {
        for (const item of commit.source_observations) {
          if (item.source_id === 'usage_events.v1') item.admission_kind = 'BARRIER_FLUSH';
        }
      }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a recovery whose quality gap is not a process recovery', async () => {
    await expect(openPrefix(
      'rc13-jvm-pilot-process-restart.partexp',
      (commits) => commits.findIndex((commit) => commit.input_kind === 'RECOVERY'),
      (commit) => {
        const gap = commit.events.find((event) => event.event_type === 'SOURCE_QUALITY_GAP')!;
        gap.fields.reason = 'WALL_CLOCK_CHANGED';
      }
    )).resolves.toEqual(unreadable);
  });

  const deactivation = (commit: EngineCommit) =>
    commit.events.find((event) => event.event_type === 'CONDITION_EPOCH_DEACTIVATED')!;
  const closesEpoch = (commit: EngineCommit) =>
    commit.events.some((event) => event.event_type === 'CONDITION_EPOCH_DEACTIVATED');
  // Moves a condition event's boundary and observed time together, as a forger would.
  const moveEventTime = (
    event: EngineCommit['events'][number],
    boot: string,
    elapsed: string,
    wall: string
  ) => {
    event.fields.boundary_research_time = canonicalize({
      boot_session_id: boot, monotonic_time_nanos: elapsed, wall_time_utc_millis: wall
    });
    event.observed_time = {
      boot_session_id: boot, elapsed_realtime_nanos: elapsed, wall_time_utc_millis: wall
    };
  };

  it('opens an empty flush at the close and a safety pause with no traffic audit', async () => {
    const { document } = await load('rc13-jvm-empty-flush-and-vpn-revoked.partexp');
    const commits = document.experiment.commits;
    expect(commits.some((commit) => commit.source_observations.some((item) =>
      item.admission_kind === 'BARRIER_FLUSH' &&
      item.coverage?.start_inclusive === item.coverage?.end_exclusive
    ))).toBe(true);
    expect(commits.some((commit) =>
      commit.input_kind === 'SAFETY_FAILURE' && closesEpoch(commit) &&
      !commit.events.some((event) => event.source_id === 'traffic_shaping.v1')
    )).toBe(true);
  });

  it('rejects empty coverage outside the barrier flush at the close', async () => {
    await expect(openPrefix(
      'rc13-jvm-pilot-process-restart.partexp',
      (commits) => commits.findIndex((commit) =>
        commit.input_kind === 'SOURCE_OBSERVATION' &&
        commit.source_observations.some((item) => item.source_id === 'usage_events.v1')
      ),
      (commit) => {
        const observation = commit.source_observations.find((item) =>
          item.source_id === 'usage_events.v1'
        )!;
        observation.coverage!.end_exclusive = observation.coverage!.start_inclusive;
        const covered = commit.events.filter((event) =>
          observation.first_event_sequence !== null &&
          BigInt(event.sequence_number) >= BigInt(observation.first_event_sequence) &&
          BigInt(event.sequence_number) <= BigInt(observation.last_event_sequence!)
        );
        observation.encoded_sha256 = calculateObservationDigest(observation, covered);
        commit.successor_projection.source_checkpoints['usage_events.v1'].coverage = {
          ...observation.coverage!
        };
      }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a condition boundary that is not its event time', async () => {
    await expect(openPrefix(
      'rc13-jvm-pilot-process-restart.partexp',
      (commits) => commits.findIndex(closesEpoch),
      (commit) => {
        const event = deactivation(commit);
        const boundary = JSON.parse(event.fields.boundary_research_time) as Record<string, string>;
        boundary.wall_time_utc_millis = String(BigInt(boundary.wall_time_utc_millis) - 1n);
        event.fields.boundary_research_time = canonicalize(boundary);
      }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a participant pause closed in another boot', async () => {
    await expect(openPrefix(
      'rc13-jvm-pilot-process-restart.partexp',
      (commits) => commits.findIndex((commit) =>
        closesEpoch(commit) &&
        deactivation(commit).fields.deactivation_reason === 'PARTICIPANT_PAUSED'
      ),
      (commit) => {
        const time = deactivation(commit).observed_time;
        moveEventTime(
          deactivation(commit), 'another-boot-session', time.elapsed_realtime_nanos,
          time.wall_time_utc_millis
        );
      }
    )).resolves.toEqual(unreadable);
  });

  it('opens a recovery close at the recovery instant without a trusted clock', async () => {
    const { document } = await load('rc13-jvm-reboot-without-trusted-time.partexp');
    const recovery = document.experiment.commits
      .find((commit) => commit.input_kind === 'RECOVERY')!;
    expect(deactivation(recovery).observed_time.boot_session_id)
      .not.toBe(recovery.committed_at.boot_session_id);
  });

  for (const file of [
    'rc13-emulator-reboot-while-running.partexp',
    'rc13-jvm-reboot-without-trusted-time.partexp'
  ]) {
    it(`rejects a recovery close away from the recovery instant in ${file}`, async () => {
      await expect(openPrefix(
        file,
        (commits) => commits.findIndex((commit) => commit.input_kind === 'RECOVERY'),
        (commit) => {
          const wall = BigInt(deactivation(commit).observed_time.wall_time_utc_millis);
          moveEventTime(deactivation(commit), 'forged-boot-session', '1', String(wall + 1n));
        }
      )).resolves.toEqual(unreadable);
    });
  }

  it('rejects a recovery close with another reason', async () => {
    await expect(openPrefix(
      'rc13-emulator-reboot-while-running.partexp',
      (commits) => commits.findIndex((commit) => commit.input_kind === 'RECOVERY'),
      (commit) => {
        deactivation(commit).fields.deactivation_reason = 'SAFETY_PAUSED';
      }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a recovery that records a traffic audit', async () => {
    const { document } = await load('rc13-emulator-reboot-while-running.partexp');
    const snapshot = document.experiment.commits
      .flatMap((commit) => commit.events)
      .find((event) => event.event_type === 'TRAFFIC_SHAPING_SNAPSHOT')!;
    await expect(openPrefix(
      'rc13-emulator-reboot-while-running.partexp',
      (commits) => commits.findIndex((commit) => commit.input_kind === 'RECOVERY'),
      (commit) => {
        const projection = commit.successor_projection;
        commit.events.push({
          ...structuredClone(snapshot), sequence_number: projection.next_event_sequence
        });
        projection.next_event_sequence = String(BigInt(projection.next_event_sequence) + 1n);
      }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a commit that removes an unknown automation checkpoint part', async () => {
    await expect(openPrefix(
      'rc13-jvm-pilot-process-restart.partexp',
      (commits) => commits.findIndex((commit) => commit.input_kind === 'ACTION_RESULT'),
      (commit) => {
        const position = commit.mutations.findIndex((item) =>
          item.component_kind === 'AUTOMATION_CHECKPOINT' && item.component_id === 'main'
        );
        commit.mutations.splice(position + 1, 0, {
          canonical_value: null,
          component_id: 'main/0001',
          component_kind: 'AUTOMATION_CHECKPOINT',
          operation: 'REMOVE'
        });
      }
    )).resolves.toEqual(unreadable);
  });

  it('rejects an event of a closing commit outside the closed epoch', async () => {
    await expect(openPrefix(
      'rc13-emulator-complete-from-running.partexp',
      (commits) => commits.findIndex((commit) =>
        commit.events.some((event) => event.event_type === 'CONDITION_EPOCH_DEACTIVATED')
      ),
      (commit) => {
        commit.events.at(-1)!.condition_epoch_id = null;
      }
    )).resolves.toEqual(unreadable);
  });
});

/**
 * A schema 2 study exported by the real runtime after participant choices: a collector turned off
 * while running and back on while paused, a requested collector turned off, and automatic upload
 * stopped after withdrawal. The checked-in snapshot is `RealRuntimeBundleInteropTest` output; when
 * PARTICEPS_REAL_RUNTIME_INTEROP_DIR names a written run, every bundle there opens too. Each bundle
 * carries its signed configuration, which is read through the configuration parser first.
 */
type InteropManifest = {
  format: string;
  researcher_key_id: string;
  bundles: Array<{
    file: string; sha256: string; byte_count: number; commit_count: number; event_count: number;
    participant_instance_id: string; state: string;
  }>;
};
const choiceFixtures = new URL('fixtures/real-runtime/', import.meta.url);
const interopDirectory = process.env.PARTICEPS_REAL_RUNTIME_INTEROP_DIR;

function interopSources(): Array<{ label: string; root: URL }> {
  const sources = [{ label: 'checked-in snapshot', root: choiceFixtures }];
  if (interopDirectory) {
    const root = new URL(`file://${interopDirectory.replace(/\/?$/, '/')}`);
    // Release CI sets the variable for the whole job and runs this suite before the Kotlin test
    // writes the directory, so a directory without its manifest is not yet a run to check.
    if (existsSync(new URL('expected.json', root))) {
      sources.push({ label: 'PARTICEPS_REAL_RUNTIME_INTEROP_DIR', root });
    }
  }
  return sources;
}

async function openInterop(root: URL, file: string) {
  const key = readFileSync(new URL('researcher-private-key.base64url', root), 'utf8').trim();
  const container = new Uint8Array(readFileSync(new URL(file, root)));
  const plaintext = decryptContainer(container, key);
  const embedded = (JSON.parse(new TextDecoder().decode(plaintext)) as { configuration: unknown })
    .configuration;
  const configuration = parseConfiguration(canonicalBytes(embedded));
  return { container, configuration, key, opened: await openBundle(container, configuration, key) };
}

for (const { label, root } of interopSources()) {
  describe(`real runtime interop bundles (${label})`, () => {
    const expected = JSON.parse(readFileSync(new URL('expected.json', root), 'utf8')) as InteropManifest;

    it('includes the participant-choices scenario', () => {
      expect(expected.format).toBe('particeps-real-runtime-interop-v1');
      expect(expected.bundles.map((bundle) => bundle.file)).toContain('participant-choices.partexp');
    });

    for (const bundle of expected.bundles) {
      it(`opens ${bundle.file} and each retained range`, async () => {
        const { container, configuration, key, opened } = await openInterop(root, bundle.file);
        expect(sha256Hex(container)).toBe(bundle.sha256);
        expect(opened).toMatchObject({ ok: true });
        if (!opened.ok) return;
        const experiment = opened.bundle.document.experiment;
        expect(experiment).toMatchObject({
          participant_instance_id: bundle.participant_instance_id,
          commit_count: String(bundle.commit_count),
          event_count: String(bundle.event_count),
          state: bundle.state
        });
        const document = JSON.parse(opened.bundle.text) as { experiment: ResearchExperiment };
        for (const start of retainedRangeStarts(experiment)) {
          const reopened = await openBundle(
            reseal(container, retainedRange(document, start), configuration), configuration, key
          );
          expect(reopened, `retained range from commit ${start + 1}`).toMatchObject({ ok: true });
        }
      }, 120_000);
    }
  });
}

describe('real runtime participant choices stay strict', () => {
  type Document = { experiment: ResearchExperiment };
  const file = 'participant-choices.partexp';
  const unreadable = { ok: false, failure: 'unreadable' };
  const isChoice = (commit: EngineCommit) => commit.input_kind === 'PARTICIPANT_CHOICE';
  const choiceOf = (commit: EngineCommit) =>
    commit.mutations.find((mutation) => mutation.component_kind === 'PARTICIPANT_CHOICE')!;
  const encodeChoice = (version: number, name: string, trailing: number[] = []) => {
    const text = new TextEncoder().encode(name);
    const payload = Buffer.alloc(8 + text.length + trailing.length);
    payload.writeInt32BE(version, 0);
    payload.writeInt32BE(text.length, 4);
    payload.set(text, 8);
    payload.set(trailing, 8 + text.length);
    return `participant-choice-v1:${payload.toString('base64url')}`;
  };

  // Opens commits 1..end+1 after changing commit [end] and resealing; the unchanged prefix must
  // open, so a rejection belongs to the change.
  const openPrefix = async (
    select: (commits: EngineCommit[]) => number,
    change: (commit: EngineCommit) => void
  ) => {
    const { container, configuration, key, opened } = await openInterop(choiceFixtures, file);
    if (!opened.ok) throw new Error(`${file} did not open`);
    const document = JSON.parse(opened.bundle.text) as Document;
    const end = select(document.experiment.commits);
    expect(end, 'selected commit').toBeGreaterThan(0);
    const open = async (mutate: boolean) => {
      const commits = structuredClone(document.experiment.commits.slice(0, end + 1));
      if (mutate) {
        change(commits[end]);
        commits[end].commit_sha256 = calculateCommitDigest(commits[end]);
      }
      const plaintext = new TextEncoder().encode(canonicalize({
        ...document,
        experiment: {
          ...document.experiment,
          commit_count: String(commits.length),
          commits,
          event_count: String(commits.reduce((sum, commit) => sum + commit.events.length, 0)),
          last_commit_sequence: String(end + 1)
        }
      }));
      return openBundle(reseal(container, plaintext, configuration), configuration, key);
    };
    expect(await open(false), 'unchanged prefix').toMatchObject({ ok: true });
    return open(true);
  };

  it('records every participant-choice shape it is meant to exercise', async () => {
    const { configuration, opened } = await openInterop(choiceFixtures, file);
    expect(configuration.schema_version).toBe(2);
    expect(configuration.participant_disclosure).toEqual({
      approximate_frequency: true, collection_timeline: true
    });
    expect(configuration.collectors.map((collector) => collector.tier)).toContain('requested');
    if (!opened.ok) throw new Error(`${file} did not open`);
    const choices = opened.bundle.document.experiment.commits.filter(isChoice);
    const states = choices.map((commit) => [
      commit.successor_projection.state, choiceOf(commit).component_id, choiceOf(commit).operation
    ]);
    expect(states).toContainEqual(['RUNNING', 'collector:gyroscope.v1', 'UPSERT']);
    expect(states).toContainEqual(['PAUSED', 'collector:gyroscope.v1', 'REMOVE']);
    expect(states).toContainEqual(['PAUSED', 'collector:network_throughput.v1', 'UPSERT']);
    expect(states).toContainEqual(['WITHDRAWN', 'upload:automatic', 'UPSERT']);
    expect(opened.bundle.document.experiment.commits.some((commit) => commit.events.some((event) =>
      event.fields.resource_vector_json?.includes('"failure_reason":"PARTICIPANT_DISABLED"')
    ))).toBe(true);
  });

  it('rejects a participant choice that turns off a core collector', async () => {
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && commit.successor_projection.state === 'RUNNING'),
      (commit) => { choiceOf(commit).component_id = 'collector:network_state.v1'; }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a participant choice for a collector the study does not sign', async () => {
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && commit.successor_projection.state === 'RUNNING'),
      (commit) => { choiceOf(commit).component_id = 'collector:location.v1'; }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a participant-choice component outside a participant-choice commit', async () => {
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && commit.successor_projection.state === 'PAUSED' &&
        choiceOf(commit).operation === 'UPSERT'),
      (commit) => { commit.input_kind = 'RESOURCE_RESULT'; }
    )).resolves.toEqual(unreadable);
  });

  it('rejects a choice value with a wrong name, version, or trailing bytes', async () => {
    for (const value of [
      encodeChoice(1, 'AUTOMATIC_UPLOAD_STOPPED'),
      encodeChoice(2, 'COLLECTOR_DISABLED'),
      encodeChoice(1, 'COLLECTOR_DISABLED', [0]),
      `${encodeChoice(1, 'COLLECTOR_DISABLED')}=`
    ]) {
      await expect(openPrefix(
        (commits) => commits.findIndex((commit) =>
          isChoice(commit) && commit.successor_projection.state === 'PAUSED' &&
          choiceOf(commit).operation === 'UPSERT'),
        (commit) => { choiceOf(commit).canonical_value = value; }
      ), value).resolves.toEqual(unreadable);
    }
  });

  it('accepts the canonical encoding of the recorded choice', async () => {
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && commit.successor_projection.state === 'PAUSED' &&
        choiceOf(commit).operation === 'UPSERT'),
      (commit) => {
        expect(choiceOf(commit).canonical_value).toBe(encodeChoice(1, 'COLLECTOR_DISABLED'));
      }
    )).resolves.toMatchObject({ ok: true });
  });

  it('rejects re-enabling a collector that was never turned off', async () => {
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && choiceOf(commit).operation === 'REMOVE'),
      (commit) => { choiceOf(commit).component_id = 'collector:usage_events.v1'; }
    )).resolves.toEqual(unreadable);
  });

  it('rejects stopping automatic upload as a removal or before the study ended', async () => {
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && choiceOf(commit).component_id === 'upload:automatic'),
      (commit) => Object.assign(choiceOf(commit), { operation: 'REMOVE', canonical_value: null })
    )).resolves.toEqual(unreadable);
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && commit.successor_projection.state === 'PAUSED' &&
        choiceOf(commit).operation === 'UPSERT'),
      (commit) => Object.assign(choiceOf(commit), {
        component_id: 'upload:automatic', canonical_value: encodeChoice(1, 'AUTOMATIC_UPLOAD_STOPPED')
      })
    )).resolves.toEqual(unreadable);
  });

  it('rejects a running choice that leaves the condition epoch open', async () => {
    await expect(openPrefix(
      (commits) => commits.findIndex((commit) =>
        isChoice(commit) && commit.successor_projection.state === 'RUNNING'),
      (commit) => { commit.successor_projection.state = 'PAUSED'; }
    )).resolves.toEqual(unreadable);
  });

  // The activation after a choice must show the collector off, and only as the participant's.
  const rewriteActivationVector = (
    commit: EngineCommit,
    rewrite: (resources: Array<Record<string, string | null>>) => void
  ) => {
    const event = commit.events.find((item) => item.event_type === 'CONDITION_EPOCH_ACTIVATED')!;
    const vector = JSON.parse(event.fields.resource_vector_json) as {
      resources: Array<Record<string, string | null>>;
    };
    rewrite(vector.resources);
    const text = canonicalize(vector);
    const digest = createHash('sha256').update(text).digest('hex');
    event.fields.resource_vector_json = text;
    event.fields.applied_resource_vector_sha256 = digest;
    commit.successor_projection.active_condition_epoch!.applied_resource_vector_sha256 = digest;
  };
  const firstActivationAfterChoice = (commits: EngineCommit[]) => {
    const choice = commits.findIndex((commit) =>
      isChoice(commit) && commit.successor_projection.state === 'RUNNING');
    return commits.findIndex((commit, index) => index > choice &&
      commit.events.some((event) => event.event_type === 'CONDITION_EPOCH_ACTIVATED'));
  };

  it('rejects an epoch that applies a collector the participant turned off', async () => {
    await expect(openPrefix(firstActivationAfterChoice, (commit) => {
      rewriteActivationVector(commit, (resources) => {
        const item = resources.find((resource) => resource.failure_reason === 'PARTICIPANT_DISABLED')!;
        Object.assign(item, { status: 'APPLIED', failure_reason: null, applied_profile_sha256: 'a'.repeat(64) });
      });
    })).resolves.toEqual(unreadable);
  });

  it('rejects a collector marked turned off without a participant choice', async () => {
    await expect(openPrefix(firstActivationAfterChoice, (commit) => {
      rewriteActivationVector(commit, (resources) => {
        const item = resources.find((resource) => resource.id === 'usage_events.v1')!;
        Object.assign(item, {
          status: 'OPTIONAL_FAILED', failure_reason: 'PARTICIPANT_DISABLED', applied_profile_sha256: null
        });
      });
    })).resolves.toEqual(unreadable);
  });
});

/**
 * RFC 9180 base-mode open of a PTCEXP01 container, without a configuration: the header names the
 * configuration digest the context binds, so the embedded configuration can be read first.
 */
function decryptContainer(container: Uint8Array, privateKey: string): Uint8Array {
  const keyIdLength = new DataView(container.buffer, container.byteOffset).getUint16(56);
  const keyIdEnd = 70 + keyIdLength;
  const keyId = new TextDecoder().decode(container.subarray(70, keyIdEnd));
  const context = Buffer.from(bundleContext(
    formatUuid(container.subarray(8, 24)), hex(container.subarray(24, 56)), keyId
  ));
  // PKCS#8 wrapping of a raw X25519 private key (RFC 8410).
  const secretKey = createPrivateKey({
    format: 'der', type: 'pkcs8',
    key: Buffer.concat([
      Buffer.from('302e020100300506032b656e04220420', 'hex'), Buffer.from(privateKey, 'base64url')
    ])
  });
  const recipient = Buffer.from(createPublicKey(secretKey).export({ format: 'jwk' }).x!, 'base64url');
  const wrapped = Buffer.from(container.subarray(keyIdEnd, keyIdEnd + 80));
  const enc = wrapped.subarray(0, 32);
  const dh = diffieHellman({
    privateKey: secretKey,
    publicKey: createPublicKey({ format: 'jwk', key: { crv: 'X25519', kty: 'OKP', x: enc.toString('base64url') } })
  });
  const kem = Buffer.concat([Buffer.from('KEM'), i2osp2(0x0020)]);
  const suite = Buffer.concat([Buffer.from('HPKE'), i2osp2(0x0020), i2osp2(0x0001), i2osp2(0x0002)]);
  const eaePrk = labeledExtract(kem, Buffer.alloc(0), 'eae_prk', dh);
  const shared = labeledExpand(kem, eaePrk, 'shared_secret', Buffer.concat([enc, recipient]), 32);
  const schedule = Buffer.concat([
    Buffer.of(0),
    labeledExtract(suite, Buffer.alloc(0), 'psk_id_hash', Buffer.alloc(0)),
    labeledExtract(suite, Buffer.alloc(0), 'info_hash', context)
  ]);
  const secret = labeledExtract(suite, shared, 'secret', Buffer.alloc(0));
  const contentKey = aesGcmOpen(
    labeledExpand(suite, secret, 'key', schedule, 32),
    labeledExpand(suite, secret, 'base_nonce', schedule, 12),
    Buffer.alloc(0),
    wrapped.subarray(32)
  );
  return aesGcmOpen(
    contentKey, Buffer.from(container.subarray(58, 70)), context,
    Buffer.from(container.subarray(keyIdEnd + 80))
  );
}

function aesGcmOpen(key: Buffer, nonce: Buffer, aad: Buffer, sealed: Buffer): Buffer {
  const decipher = createDecipheriv('aes-256-gcm', key, nonce);
  decipher.setAAD(aad);
  decipher.setAuthTag(sealed.subarray(sealed.length - 16));
  return Buffer.concat([decipher.update(sealed.subarray(0, sealed.length - 16)), decipher.final()]);
}

function retainedRangeStarts(experiment: ResearchExperiment): number[] {
  const commits = experiment.commits;
  const recoveries = commits.flatMap((commit, index) =>
    index > 0 && commit.input_kind === 'RECOVERY' ? [index] : []
  );
  const boundaries = commits.flatMap((commit, index) =>
    index > 0 && commit.input_kind !== 'RECOVERY' && (
      commit.source_observations.some((item) => item.admission_kind === 'BARRIER_FLUSH') ||
      commit.events.some((event) => event.source_id === 'study_condition.v1')
    ) ? [index] : []
  );
  // Every recovery start is kept; the more frequent boundaries are sampled.
  const stride = Math.floor(boundaries.length / MAXIMUM_BOUNDARY_STARTS) + 1;
  return [...recoveries, ...boundaries.filter((_, position) => position % stride === 0)]
    .sort((left, right) => left - right);
}

/** The canonical document as a manual export of the retained range starting at [start]. */
function retainedRange(document: { experiment: ResearchExperiment }, start: number): Uint8Array {
  const commits = document.experiment.commits.slice(start);
  const events = commits.reduce((sum, commit) => sum + commit.events.length, 0);
  return new TextEncoder().encode(canonicalize({
    ...document,
    experiment: {
      ...document.experiment,
      commit_count: String(commits.length),
      commits,
      event_count: String(events),
      first_commit_sequence: String(start + 1)
    }
  }));
}

/**
 * Re-encrypts [plaintext] under the container's outer identities: a fresh content key and nonce,
 * wrapped with RFC 9180 base-mode DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM.
 */
function reseal(
  container: Uint8Array,
  plaintext: Uint8Array,
  configuration: StudyConfiguration
): Uint8Array {
  const keyIdLength = new DataView(container.buffer, container.byteOffset).getUint16(56);
  const keyId = new TextDecoder().decode(container.subarray(70, 70 + keyIdLength));
  const bundleId = formatUuid(container.subarray(8, 24));
  const context = bundleContext(bundleId, hex(container.subarray(24, 56)), keyId);
  const recipient = Buffer.from(configuration.export.hpke_public_key, 'base64url');
  const contentKey = randomBytes(32);
  const nonce = randomBytes(12);

  const ephemeral = generateKeyPairSync('x25519');
  const enc = Buffer.from(ephemeral.publicKey.export({ format: 'jwk' }).x!, 'base64url');
  const dh = diffieHellman({
    privateKey: ephemeral.privateKey,
    publicKey: createPublicKey({
      format: 'jwk',
      key: { crv: 'X25519', kty: 'OKP', x: recipient.toString('base64url') }
    })
  });
  const kem = Buffer.concat([Buffer.from('KEM'), i2osp2(0x0020)]);
  const suite = Buffer.concat([Buffer.from('HPKE'), i2osp2(0x0020), i2osp2(0x0001), i2osp2(0x0002)]);
  const eaePrk = labeledExtract(kem, Buffer.alloc(0), 'eae_prk', dh);
  const shared = labeledExpand(kem, eaePrk, 'shared_secret', Buffer.concat([enc, recipient]), 32);
  const schedule = Buffer.concat([
    Buffer.of(0),
    labeledExtract(suite, Buffer.alloc(0), 'psk_id_hash', Buffer.alloc(0)),
    labeledExtract(suite, Buffer.alloc(0), 'info_hash', Buffer.from(context))
  ]);
  const secret = labeledExtract(suite, shared, 'secret', Buffer.alloc(0));
  const wrapped = Buffer.concat([
    enc,
    aesGcm(
      labeledExpand(suite, secret, 'key', schedule, 32),
      labeledExpand(suite, secret, 'base_nonce', schedule, 12),
      Buffer.alloc(0),
      contentKey
    )
  ]);
  const header = Buffer.from(container.subarray(0, 70 + keyIdLength));
  nonce.copy(header, 58);
  return new Uint8Array(Buffer.concat([
    header,
    wrapped,
    aesGcm(contentKey, nonce, Buffer.from(context), Buffer.from(plaintext))
  ]));
}

const VERSION = Buffer.from('HPKE-v1');
const i2osp2 = (value: number) => Buffer.of((value >> 8) & 0xff, value & 0xff);

function labeledExtract(suite: Buffer, salt: Buffer, label: string, ikm: Buffer): Buffer {
  return createHmac('sha256', salt.length === 0 ? Buffer.alloc(32) : salt)
    .update(Buffer.concat([VERSION, suite, Buffer.from(label), ikm]))
    .digest();
}

function labeledExpand(suite: Buffer, prk: Buffer, label: string, info: Buffer, length: number) {
  // One HKDF-Expand block suffices for every output here (at most 32 bytes).
  return createHmac('sha256', prk)
    .update(Buffer.concat([i2osp2(length), VERSION, suite, Buffer.from(label), info, Buffer.of(1)]))
    .digest()
    .subarray(0, length);
}

function aesGcm(key: Buffer, nonce: Buffer, aad: Buffer, plaintext: Buffer): Buffer {
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(aad);
  return Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
}

function formatUuid(value: Uint8Array): string {
  const text = hex(value);
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-` +
    `${text.slice(16, 20)}-${text.slice(20)}`;
}
