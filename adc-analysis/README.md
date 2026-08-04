# ADC analysis

`adc-analysis` is the offline, one-way Protocol v1 pipeline. It inventories encrypted `.adcexp`
objects, authenticates an entire bundle, deterministically reassembles events, and atomically
publishes typed Parquet. It never changes a study, contacts a participant, or adds a receiver-side
decrypt path.

Start with the repository's normative contract:

- `../protocol/v1/README.md` defines the wire and cryptographic protocol.
- `../protocol/v1/collector-catalog.json` defines every accepted collector, payload, field, unit,
  and type.
- `../protocol/v1/conformance-vectors.json` is the shared valid and hostile corpus.
- `src/adc_analysis/` contains the implementation; each pipeline stage has one correspondingly
  named module.
- `tests/` covers the shared corpus, source inventory, conflict/gap taxonomy, configuration, and
  Parquet round trips.

| Concern | Source | Primary tests |
| --- | --- | --- |
| Local/R2 reads, source-specific bounds, immutable cache | `sources.py`, `inventory.py`, `limits.py` | `test_inventory.py` |
| JCS, keys, HPKE, configuration, complete streaming bundle verification | `jcs.py`, `streaming_json.py`, `encoding.py`, `crypto.py`, `configuration.py`, `bundle.py` | `test_conformance.py`, `test_configuration_catalog.py`, `test_streaming_filesystem.py` |
| Duplicate/conflict/gap decisions and bounded spill storage | `reassembly.py`, `event_store.py` | `test_reassembly.py` |
| Arrow schema, Parquet, manifest, quality summary | `sink.py`, `summary.py` | `test_pipeline.py`, `test_sink.py` |
| One-way orchestration and CLI | `pipeline.py`, `cli.py` | `test_pipeline.py`, `test_cli.py` |
| Owner-only staging and create-only publication | `filesystem.py` | `test_streaming_filesystem.py` |

## Pipeline and trust boundary

```text
LocalBundleSource / S3BundleSource
              -> content-addressed ciphertext cache + inventory.json
              -> framing, HPKE, AEAD, JCS, signature, config, range, catalog validation
              -> deterministic event reassembly
              -> typed Parquet + dataset manifest + quality summary
```

R2 metadata and paths are untrusted routing claims. Participant/event identity exists only after
the complete authenticated document verifies. Automatic receiver objects retain the 32 MiB wire
bound. A local manual export may reach its signed local-storage quota (at most 8 GiB), so GCM
decryption, JCS checking, event validation, reassembly, and Parquet row groups are streamed or
spilled instead of loading the document into memory. AEAD authentication completes before JSON is
accepted. Every plaintext staging/spill artifact is inside a tightened mode-0700 directory with
owner-only files and is removed on every handled success or failure. One invalid bundle is
quarantined whole and
emits no rows. A conflicting authenticated event identity stops dataset publication; there is no
last-write-wins behavior or unknown-schema fallback.

## Install and run

From this directory:

```sh
uv sync --locked
uv run adc-analysis inventory \
  --workspace /secure/adc-work \
  --local /path/to/manual-exports /path/to/downloaded-r2-objects
```

R2 uses its S3-compatible endpoint and boto3's normal credential chain:

```sh
uv run adc-analysis inventory \
  --workspace /secure/adc-work \
  --s3-bucket adc-ciphertext \
  --s3-endpoint-url https://ACCOUNT_ID.r2.cloudflarestorage.com \
  --s3-region auto \
  --s3-prefix uploads/
```

Both source types may be inventoried into one manifest in a single command by supplying
`--local ...` and `--s3-bucket ...` together. Inventory is an explicit snapshot: rerunning the
command replaces the manifest with exactly the objects supplied during that invocation while
retaining the immutable content-addressed ciphertext cache.

Materialization needs a local mode-0600 key file. Keys are unpadded base64url raw X25519 private
keys, keyed by the signed configuration's researcher key ID:

```json
{"format":"adc-analysis-keys-v1","keys":{"researcher-key-id":"RAW_PRIVATE_KEY_BASE64URL"}}
```

```sh
chmod 600 /secure/researcher-keys.json
uv run adc-analysis materialize \
  --workspace /secure/adc-work \
  --keys /secure/researcher-keys.json \
  --catalog ../protocol/v1/collector-catalog.json \
  --output /secure/datasets/study-2026-08
```

The output path must not already exist or be a symbolic link. Publication uses an OS-level atomic,
create-only rename of a complete sibling staging directory, so a concurrently appearing empty
directory is never replaced. Hive-style partitions are
`experiment_id/configuration_id/collector_id/payload_schema_version/payload_type`; files contain
explicit Arrow schemas, exact 64-bit clocks/sequences, and source ciphertext provenance.

Run all checks with:

```sh
uv run ruff check src tests
uv run python -m unittest discover -s tests -v
```

## Operational notes

- Keep the workspace, key file, and dataset on encrypted researcher-controlled storage.
- Inventory downloads ciphertext before any key is used. The receiver remains R2-only and has no
  list/decrypt/admin API.
- `reports/validation-report.json` records quarantine and conflict outcomes even when publication
  stops. `quality-summary.json` distinguishes overlaps, identical duplicates, conflicts, interior
  gaps, undelivered suffixes, reclaimed prefixes, and achieved mean sampling rates (rounded to the
  nearest millihertz, with the exact interval count and duration retained); it does not infer
  participant behavior. Sensor rates use the catalog-declared hardware/source
  `source_elapsed_realtime_nanos`, not callback-envelope time, so Android FIFO batching does not
  collapse the measured duration.
- Potentially large quality collections use the stable
  `{"count":"…","examples":[…],"examples_truncated":true|false}` shape. Counts remain exact;
  at most 100 deterministic examples are retained. Nested details such as gap ranges and boot
  session IDs use the same shape, so consumers must not treat `examples` as the complete set when
  `examples_truncated` is true.
- An identical duplicate has the same authenticated event identity and bytes. A conflict has the
  same identity but different bytes and stops publication. An interior gap is absent below the
  highest arrived sequence; an undelivered suffix is absent after it but at or below the latest
  authenticated durable boundary; a reclaimed prefix is absent below the latest authenticated
  retained boundary. These labels describe evidence availability, not why a participant did or did
  not produce an observation.
- Database connectors are intentionally out of scope. `DatasetSink` is the narrow extension
  contract; Parquet is the only implementation in this release.
