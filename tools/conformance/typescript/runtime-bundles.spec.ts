import {
  createCipheriv,
  createHash,
  createHmac,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  randomBytes
} from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
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
import { canonicalize } from '../../../web/src/lib/particeps/canonical';
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
