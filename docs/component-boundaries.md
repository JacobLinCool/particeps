# Component boundaries

The participant app is organized around the eight responsibilities below, not around Android
entry points. Each contract states what a module owns and, where it matters, what it never
sees. The dependency direction in `settings.gradle.kts` and the module build files is the
enforcement mechanism.

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
- `:core:protocol` owns only the signed envelope and trust verification.
- `:core:collector-api` is the only core module a collector feature depends on directly. It
  owns collector lifecycle, health, event admission contracts, capabilities, and shared
  serialized callback delivery. Its `CollectorContext` hands a collector a coroutine scope, an
  `EventSink`, and a clock — no store, no state machine, no scheduler, no exporter.
- `:core:experiment-runtime` owns command serialization, state transitions, admission, and
  collector supervision. It is platform independent.
- `:core:study-application` owns study use cases and coordinates injected storage, access,
  foreground-host, scheduling, export, and upload ports. The `StudyUploader` port takes a
  sequence window and returns a receipt; the HTTP client behind it lives in `:app`.
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
- A bundle carries exactly the window it holds, while later collection may continue. A
  participant export takes the whole retained window with no size budget. An upload asks for
  everything after the endpoint's last confirmation and stops at the first event boundary past
  its plaintext budget, so where it ends is decided while it streams and is reported in the
  receipt. `first_sequence_number` and `last_sequence_number` are written after the `events`
  array for that reason: a bundle never declares a range before it knows it.
- The upload watermark advances only on a confirmed delivery, only as far as the receipt says
  the bundle reached, and never backwards. A confirmed delivery is the only thing that makes
  local data reclaimable. Reclaiming starts only above 80% of the study's quota and takes whole
  leading segments at or below the watermark. Undelivered data is never released to make room.
- Study metadata is self-sufficient: opening a study validates framing and sequence contiguity
  from the plaintext frame headers and reads `lastEvents` from the metadata, so load cost is
  linear in frames rather than in bytes decrypted. Event payloads are authenticated when read.
  Reading a range decrypts only that range and seeks past the frames below it.
- Configuration decoding stays canonical, strict, and compile-time allowlisted. There is no
  legacy or fallback reader.
- One process-scoped study session owns recovery and runtime lifetime. Android receivers,
  workers, services, and UI delegate to it.

Collector-side detail lives in the
[Collector implementation guide](data-collector-implementation-guide.md); the full module map
is in [System design](system-design.md).
