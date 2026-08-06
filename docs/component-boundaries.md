# Component boundaries

The participant app is organized around the eight responsibilities below, not around Android
entry points. Each contract states what a module owns and, where it matters, what it never
sees. The dependency direction in `settings.gradle.kts` and the module build files is the
enforcement mechanism.

New contributors should read this with the [normative Protocol v1 contract](../protocol/v1/README.md),
the [collector catalog](../protocol/v1/collector-catalog.json), the
[P0–P2 implementation contract](p0-p2-implementation-contract.md), and
[`assurance`](../assurance/README.md). The concrete upload seam is deliberately short:
[StudyUploader](../core/study-application/src/main/kotlin/cool/jacoblin/particeps/core/application/StudyApplication.kt),
[FileUploadOutbox](../app/src/main/kotlin/cool/jacoblin/particeps/platform/FileUploadOutbox.kt),
[OkHttpStudyUploader](../app/src/main/kotlin/cool/jacoblin/particeps/platform/OkHttpStudyUploader.kt),
and their neighbouring tests.

```text
participant UI -> study application -> study domain
                                |-> collector runtime -> collector API <- collector features
                                |-> study store port <- encrypted Android storage
Android host/access/work/recovery -> study application
researcher tools -> study definition + signed protocol + export format
```

## Contracts

- `:core:model` owns finite study metadata, events, state transitions, and the `StudyStore`
  port. It never owns an unbounded event list.
- `:core:study-definition` owns the strict, closed-world study schema and its canonical codec.
- `:core:protocol` owns the signed envelope, immutable join-URI parser, and trust verification.
- `:core:collector-api` is the only runtime-facing core API a collector feature depends on; a
  collector also depends on `:core:study-definition` for its closed typed configuration. Hardware
  sensor modules may additionally use the narrow `:collector:sensor-common` lifecycle helper.
  `:core:collector-api` owns collector lifecycle, health, event admission contracts, capabilities,
  and serialized callback delivery. Its `CollectorContext` hands a collector a coroutine scope, an
  `EventSink`, and a clock — no store, no state machine, no scheduler, no exporter.
- `:core:experiment-runtime` owns command serialization, state transitions, admission, and
  collector supervision. It is platform independent.
- `:core:study-application` owns study use cases and coordinates injected storage, access,
  foreground-host, scheduling, export, and upload ports. The `StudyUploader` port takes a
  sequence window and returns a verified receipt; durable staging and HTTP live in `:app`.
- Android entry points and adapters live in `:app`; they do not duplicate recovery or study
  policy.
- `:core:storage`, `:core:export`, and `:core:crypto` implement the encrypted data boundary
  without exposing cryptographic internals transitively.

## Invariants

- Events are appended and never rewritten, and the retained window is sequence-contiguous.
  `StudyMetadata` requires `nextSequenceNumber == eventCount + 1` on the lifetime count, and
  `retainedFromSequence` marks the lowest sequence still on disk. A read rejects a gap between
  surviving segments, a segment index mismatch, or a non-contiguous sequence number rather
  than skipping past it. Survivors need not start at index 1 or at sequence 1: reclaiming
  removes whole leading segments and never reuses an index or a sequence number. Runtime
  snapshots carry bounded metadata — counters, the transition history, and the last event per
  collector — never the event log.
- A bundle's authenticated JCS document declares exactly the contiguous window it holds. A
  participant export takes the retained window and may scale to the local quota. Automatic upload
  selects a bounded exact window, then durably stages one complete ciphertext bundle plus its
  manifest before HTTP. At most one entry exists; reboot, process death, retry, and response loss
  reuse its bundle ID, digest, range, length, and bytes.
- The upload watermark advances only on `201 Created` or exact-replay `200 OK` with a canonical
  seven-field receipt matching the outbox manifest, and never backwards. Redirects, `202`, other
  statuses, and malformed or mismatched receipts cannot commit. A confirmed delivery is the only thing that makes
  local data reclaimable. Reclaiming starts only above 80% of the study's quota and takes whole
  leading segments at or below the watermark. Undelivered data is never released to make room.
- Study metadata is self-sufficient: opening a study validates framing and sequence contiguity
  from the plaintext frame headers and reads `lastEvents` from the metadata, so load cost is
  linear in frames rather than in bytes decrypted. Event payloads are authenticated when read;
  the one exception is exact append-journal recovery, which authenticates only the durable tail.
  Reading a range decrypts only that range and seeks past the frames below it.
- Configuration decoding stays canonical, strict, and compile-time allowlisted. There is no
  legacy or fallback reader.
- The collector catalog is the shared schema source, but not a runtime plugin mechanism. Runtime
  validates payload schema and `maximumEncodedEventBytes`; the Collector capability policy
  constrains source, bytecode, and dependencies in CI.
- One process-scoped study session owns recovery and runtime lifetime. Android receivers,
  workers, services, and UI delegate to it.

Collector-side detail lives in the
[Collector implementation guide](data-collector-implementation-guide.md); the full module map
is in [System design](system-design.md).
