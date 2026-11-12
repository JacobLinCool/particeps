# Commit and recovery verification

The first verification boundary is `EncryptedExperimentStore`: acknowledged commits, replayed
runtime projections and the durable pending-input slot. The finite model and generated histories
check this contract independently; neither is a proof of the entire Android application.

## Contract and model

`verification/storage/CommitRecovery.tla` separates kernel-visible `log` from fsynced `stable`
bytes. A successful write may be followed by a sync exception. Process death preserves visible
bytes; power loss may retain any complete prefix between the stable and visible lengths. A torn
final frame abstracts to no additional complete commit. A checkpoint can succeed or remain stale,
and pending-slot cleanup can fail after acknowledgement.

The checked invariants are:

| Invariant | Requirement |
| --- | --- |
| `AcknowledgedDurable` | Acknowledged commits are within the durable prefix. |
| `SnapshotBackedByDurableLog` | A published recovery cache never advances past that prefix. |
| `PendingAccountedFor` | Every staged input remains in the slot or has a durable consumer. |
| `ConsumedAtMostOnce` | An input has at most one consuming commit. |
| `RecoveredProjection` | An open store's projection matches the durable prefix. |
| `TypeOK` | States, indices and visible/durable prefix relationships remain well formed. |

The PR configuration exhausts up to three commits, two distinct pending inputs and two process
deaths/power losses, including subsequent recovery. Larger histories are exercised by the property
suite. This is a bounded safety check, not an unbounded proof or a liveness/latency claim. The
finished model currently has 1,576 distinct reachable states. Two deliberately incorrect model
variants must produce the expected invariant counterexamples; a parser error, process failure,
timeout or unfinished search cannot count as either success or an expected counterexample.

| Model operation | Implementation boundary |
| --- | --- |
| `Stage` | Acknowledged `stagePendingInput` |
| `Write`, `Sync`, `Ack` | `appendValidated`, its durable appender, then advancing runtime memory |
| `AppendFailure`, `Readback` | Exception reconciliation through authenticated `scanFrames` |
| `Checkpoint`, `Retire`, `CleanupFailure` | Optional snapshot and pending-slot retirement after acknowledgement |
| `Recover` | `loadRuntime`, `replayAfter`, pending-consumer resolution and snapshot publication |

The model assumes a single writer, valid authenticated frame identities, and the acknowledged
atomic-file contract for pending inputs and snapshots. It does not model every rename/witness
candidate, encryption internals, arbitrary media corruption, Android scheduling or the runtime's
later safety-pause/resource cleanup. Existing atomic-file fault tests, Android corruption/Keystore
tests and runtime recovery tests retain responsibility for those boundaries. Successful fsync is
assumed to provide the platform's durability guarantee; a device that violates it is outside this
model.

## Defect found and fixed

The previous exception path accepted a complete authenticated readback without syncing it. The
minimal counterexample is write → sync error → successful readback → acknowledgement → power loss:
the acknowledged frame could disappear. Cold recovery could similarly see an unsynced frame left
by a dead process and persist a snapshot ahead of the durable log.

Recovery scans now sync each authenticated segment before accepting reconciliation or publishing
the recovered snapshot. A sync failure propagates as failure. Tail repair uses the same boundary.
Normal successful appends retain their existing one-sync acknowledgement, and ordinary read
snapshots do not sync. This adds one sync per recovered segment, not per frame; it changes no keys,
aliases, encryption format or stored layout.

The original implementation failed the actual-store regression and generated-history tests. The
generator reduced its counterexample to `[VISIBLE_FAILURE]`, followed by the fixture's final power
loss/reopen. The corrected implementation passes. This defect does not by itself establish the
cause of the reported Samsung startup delay.

## Implementation properties

`CommitRecoveryPropertyTest` uses the real store, AES-GCM, JSON codec and replay implementation.
Only key access and filesystem acknowledgement are supplied through explicit ports. The host key
provider and JSON dependency are test-only; production always constructs the Android Keystore
adapter. Android instrumentation still exercises that adapter and the platform JSON implementation.

The default seed is `5782993918480303440`, with 48 histories of up to 24 operations. Commands mix
append, full visible-but-unsynced writes, post-sync errors, torn tails, pending staging/consumption,
checkpoint/cleanup failures, reopen and power loss. Histories include component upserts/removals
and multiple log segments. An independent projection calculation does not call production
`RuntimeDocument.advance`. Every reopen checks the exact ordered commit history, projection,
pending identity, unique consumption, monotonic progress and one sync per segment. Kotest shrinks
failing command lists and prints a reproducible seed.

The filesystem fixture distinguishes visible and stable log bytes, restoring only the latter on
simulated power loss. Namespace mutations are reliable in this fixture; actual device power loss
and the atomic-file protocol are not simulated by copying files.

```sh
python3 tools/check_storage_model.py
./gradlew :core:storage:testDebugUnitTest --tests '*CommitRecoveryPropertyTest'
# Deeper/manual run or reproduce a printed seed:
./gradlew :core:storage:testDebugUnitTest --tests '*CommitRecoveryPropertyTest' \
  -PstoragePropertyIterations=500 -PstoragePropertySeed=5782993918480303440
```

## Cost and CI policy

The ordinary Gradle test invocation discovers the property suite; CI adds no second Gradle build,
emulator or separate property-test framework runner. TLC uses a SHA-256-pinned 2.2 MiB upstream
v1.7.4 JAR, one worker and a 256 MiB heap. Each search has a 15-second timeout and the complete CI
model step a one-minute limit; timeout is a failure, never a partial proof. Default exploration
bounds are fixed. Larger searches are manual and should not silently expand the PR gate.

The model runner writes setup/download time and each JVM/search duration to
`build/reports/storage-model/timing.json`. CI publishes these plus the property's JUnit durations in
its step summary and `storage-verification-<sha>` artifact. JUnit timings exclude compilation and
worker startup and can come from restored test results; the Gradle log identifies cached tasks.
They must not be described as a controlled whole-workflow before/after measurement. The two tiny
Python result-validation tests run in the existing tool suite.

Local paired measurement on 2026-10-27 used the same compiled code and warm Gradle daemon, disabled
the build cache and cleaned the test results before every run. Three interleaved baseline/full
runs gave median wall times of 0.672 s for the existing 15 storage tests and 3.579 s for all 19:
an increment of 2.907 s. Median JUnit execution was 0.028 s versus 2.949 s. TLC plus both negative
checks took 2.489 s with the JAR already downloaded. Combined local steady-state verification cost
was about 5.4 s; first-time dependency download/compilation and GitHub runner cost are separate.

Primary tool references: [TLC](https://docs.tlapl.us/using%3Atlc%3Astart),
[Kotest shrinking](https://kotest.io/docs/proptest/property-test-shrinking.html).
