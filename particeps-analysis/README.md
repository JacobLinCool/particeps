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
- source coverage continuity and condition-epoch boundaries;
- durable timer generations, action outbox transitions, and causal automation audit events;
- the runtime-owned study deadline identity, target, generation, signed-duration projection, due
  lifecycle, and terminal retirement;
- signed resource profiles, applied resource-vector digests, and condition epoch ordering;
- runtime projection cursors, watermarks, lifecycle, and collector event totals.

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
- derived `source_condition_epoch_id` after source-clock and coverage attribution;
- observed wall, monotonic, and boot-session time;
- source bundle, ciphertext, configuration, commit, and observation provenance;
- analyzer version.

`dataset-manifest.json` binds the dataset to the generated registry digest and complete source
commit ranges. `quality-summary.json` records verified participant heads, identical commit
duplicates, boot sessions, source-clock sampling summaries, and survey lifecycle counts. These
artifacts describe evidence quality; they do not infer missing participant behavior.

## Verification commands

```sh
uv run ruff check src tests
uv run python -m compileall -q src tests
uv run python -m unittest discover -s tests -v
```

Keep the workspace, researcher keys, quarantine, reports, and datasets on encrypted,
researcher-controlled storage. Parquet is the only supported dataset sink in this release.
