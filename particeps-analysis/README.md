# Particeps analysis

`particeps-analysis` is the offline, fail-closed Protocol v1 verifier and Parquet
materializer. It accepts only the current durable event-driven wire format. Old flat-event
bundles, old collector configuration shapes, alternate field names, incomplete commit chains,
and unknown event contracts are rejected.

The normative inputs are:

- `../protocol/v1/README.md` for the signed configuration and encrypted bundle protocol.
- `../protocol/v1/event-source-registry.json` for every COLLECTOR and SYSTEM source, event,
  field, operator, clock, delivery, privacy, rate, and profile contract.
- `src/particeps_analysis/generated/event_source_registry.py` for the generated Python registry
  embedded in this package.
- `../protocol/v1/conformance-vectors.json` for shared valid and hostile protocol examples.

The generated registry digest is compiled into the analyzer. A bundle carries that digest and
must match it exactly; there is no command-line registry override.

## Verification model

The pipeline performs these steps in order:

```text
immutable ciphertext inventory
  -> container framing, HPKE, AES-GCM, and canonical JSON verification
  -> signed current configuration and generated registry contract validation
  -> EngineCommit and SourceObservation integrity verification
  -> complete per-participant commit-chain replay from genesis
  -> typed event spill store
  -> atomic, create-only Parquet publication
```

An authenticated `EngineCommit` is the atomic unit. Analysis independently verifies:

- contiguous commit, event, observation, producer-ordinal, and manifest ranges;
- commit hashes, observation hashes, reducer checkpoint hashes, and predecessor linkage;
- exact event contracts and canonical typed field values from the generated registry;
- the reducer cursor, which counts reducer inputs and is 0 exactly in the setup states whose
  commits carry the empty automation checkpoint;
- source coverage continuity, barrier flushes, opaque collector cursors that change only with
  their source's flush, and condition-epoch boundaries from each epoch's preparation bound;
- one envelope epoch per commit, and each intervention event's binding to its `ACTION_REQUESTED`;
- durable timer generations, the complete or net rendering of each batch's timer intents,
  retirement reasons fixed by the commit, action outbox transitions, and causal automation audit
  events;
- the runtime-owned study deadline identity, target, generation, signed-duration projection, due
  lifecycle, and retirement, including by a Complete or Withdraw request from `RUNNING` and its
  re-arm when that stop does not finish;
- lifecycle transitions, where a safety pause of a study already `PAUSING` reduces only `PAUSED`;
- recovery containment, which closes the epoch at the recovery instant without a traffic audit and
  keeps every resource receipt unchanged, and the safety pause whose boundary audit could not read
  the traffic counters;
- coverage that never runs backwards and is empty only as the barrier flush at the close;
- signed resource profiles, applied resource-vector digests, and condition epoch ordering;
- runtime projection cursors, watermarks, lifecycle, and collector event totals.

`../protocol/v1/README.md` is normative for every one of these rules; the analyzer applies one
rule set to every export and does not branch on the producer's client version.

Every participant chain must be present from commit 1 through its authenticated durable head.
Missing commits, partial observation batches, orphan or overlapping epochs, cross-epoch coverage,
stale timers, action events without durable requests, resource digest divergence, or checkpoint
divergence stop publication. If any inventoried bundle fails verification, the whole requested
dataset is not published; the ciphertext is quarantined and a validation report is written.

Clock-discontinuity replay reconstructs the exact reset of latches, keyed presence, windows, and
sequences, verifies that retrospective source checkpoints were discarded, and requires an epoch
rotation before later data. A deadline crossed by that gap may complete the study but cannot
materialize a retrospective flush. Paused reboot recovery is accepted only with an explicit quality
gap and trustworthy new-boot anchor; the analyzer never attributes or backfills the intervening
interval.

Ciphertext routing metadata and object paths are untrusted until the encrypted bundle verifies.
Decrypted bytes are staged only in owner-private workspace files and are removed on every handled
success or failure. The analyzer never contacts participants or changes a study.

## Install and run

From this directory:

```sh
uv sync --locked
uv run particeps-analysis inventory \
  --workspace /secure/particeps-work \
  --local /path/to/manual-exports /path/to/downloaded-receiver-objects
```

R2 is read through its S3-compatible API and boto3 credential chain:

```sh
uv run particeps-analysis inventory \
  --workspace /secure/particeps-work \
  --s3-bucket particeps-ciphertext \
  --s3-endpoint-url https://ACCOUNT_ID.r2.cloudflarestorage.com \
  --s3-region auto \
  --s3-prefix uploads/
```

Local and S3 sources may be combined in one inventory invocation. Inventory is an explicit
snapshot: a subsequent invocation replaces the manifest with exactly the supplied objects while
retaining the immutable, content-addressed ciphertext cache.

Materialization needs a local mode-0600 key file. Each value is an unpadded base64url raw X25519
private key indexed by the signed researcher key ID:

```json
{"format":"particeps-analysis-keys-v1","keys":{"researcher-key-id":"RAW_PRIVATE_KEY_BASE64URL"}}
```

```sh
chmod 600 /secure/researcher-keys.json
uv run particeps-analysis materialize \
  --workspace /secure/particeps-work \
  --keys /secure/researcher-keys.json \
  --output /secure/datasets/study-2026-08
```

The output path must not exist and must not be a symbolic link. Publication uses an atomic,
create-only rename of a complete sibling staging directory.

## Dataset contract

Parquet files use Hive-style partitions:

```text
experiment_id=<id>/configuration_id=<id>/source_id=<id>/schema_version=<n>/event_type=<type>/part-00000.parquet
```

Each row contains typed registry fields plus:

- participant identity and global event sequence;
- `condition_epoch_id` from the admitted event envelope;
- derived `source_condition_epoch_id`: the observation's epoch for collector events, the request
  epoch for intervention events, and otherwise the epoch from source-clock attribution;
- observed wall, monotonic, and boot-session time;
- source bundle, ciphertext, configuration, commit, and observation provenance;
- analyzer version.

A registry field named like one of these provenance columns or a partition key is written as
`payload_<name>`, and its column metadata `particeps.payload_field` names the registry field. Six
fields are renamed: `condition_epoch_id` in the two `study_condition.v1` epoch events and the three
`traffic_shaping.v1` events, and `source_id` in `study_runtime.v1` `SOURCE_QUALITY_GAP`. A
`json_string` field's column holds the authenticated wire text exactly as recorded. A schema or
row that still does not fit its Arrow schema stops publication with a validation error.

`dataset-manifest.json` binds the dataset to the generated registry digest and complete source
commit ranges. `quality-summary.json` records verified participant heads, identical commit
duplicates, boot sessions, source-clock sampling summaries, and survey lifecycle counts. For each
participant it also lists every condition epoch's `preparation_bound`, `activated_at`, and
`deactivated_at`: retrospective rows may begin at the preparation bound, before the epoch's
resources were confirmed, so rows whose source time precedes `activated_at` can be excluded. These
artifacts describe evidence quality; they do not infer missing participant behavior.

## Real runtime fixtures

`tests/fixtures/runtime-bundles/` holds encrypted exports written by the v1.0.0-rc.13 pilot build
and by the current runtime, encrypted to the public INSECURE demonstration key; `PROVENANCE.md`
there describes each run. `test_runtime_bundle_fixtures.py` inventories and materializes each one
through the CLI and checks the published partitions, row counts, epoch columns, and payload
columns against `manifest.json`; a replay of each chain checks which timer rendering its recovery
commit records. `test_runtime_bundle_mutations.py` changes one accepted shape at a time in those
chains and requires the owning rule to reject it.

Most RC13 fixtures come from normal use. Five come from the RC13 runtime on the JVM with scripted
platform doubles that inject the failures RC13 contains, so the rules for those shapes are tested
against runtime output rather than hand-built chains: a pause in the same millisecond as a
usage-events poll, whose flush covers the empty interval `[t, t)`; a VPN revoked while running,
whose safety pause cannot read the traffic counters; a failed resource release after Complete;
process death while `PAUSING`; and a reboot without trusted UTC. `PROVENANCE.md` there lists
which run produced each file. The three pilot-scenario runs, two from RC13 and one from this
branch, share one participant instance ID, so materialize them one at a time.

`test_real_runtime_interop.py` consumes exports that the current runtime writes during the build.
`RealRuntimeBundleInteropTest` in `core/study-application` runs the production session manager and
runtime on deterministic platform doubles, with the five-day pilot configuration re-signed with
test-only keys. The run covers setup and Start, collector events and usage-events barrier flushes,
the noon and 17:00 window barriers, and pauses. It also covers a survey requested at the 17:00
barrier, process death and a reboot with their recoveries, a clock change while running and
another while paused, and the deadline stop, plus Complete from `RUNNING` on a second phone. The
Kotlin test verifies every export with `ResearchBundleVerifier`. With
`PARTICEPS_REAL_RUNTIME_INTEROP_DIR` set, it also writes the exports, the test-only key, and
`expected.json` to that directory. The Python test then inventories and materializes them through
the CLI and checks each export's counts, columns, and shapes. It skips when the variable is unset.
CI runs both. These exports come from this tree's runtime; the RC13 evidence is the frozen fixtures
above. From the repository root:

```sh
export PARTICEPS_REAL_RUNTIME_INTEROP_DIR="$PWD/build/protocol-interop/real-runtime"
./gradlew :core:study-application:test --tests cool.jacoblin.particeps.core.application.RealRuntimeBundleInteropTest
(cd particeps-analysis && uv run python -m unittest discover -s tests -p test_real_runtime_interop.py -v)
```

## Verification commands

```sh
uv run ruff check src tests
uv run python -m compileall -q src tests
uv run python -m unittest discover -s tests -v
```

Keep the workspace, researcher keys, quarantine, reports, and datasets on encrypted,
researcher-controlled storage. Parquet is the only supported dataset sink in this release.
