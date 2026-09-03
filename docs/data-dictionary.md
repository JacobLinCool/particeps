# Data dictionary

This document explains how to interpret current Particeps Protocol v1 research data. The exact,
machine-readable authority for every source, event, field, type, operator, unit, clock,
completeness rule, privacy class, rate bound, and collector profile is
[`event-source-registry.json`](../protocol/v1/event-source-registry.json). The generated human
projection is [`event-source-registry.md`](generated/event-source-registry.md). Do not infer a
schema from observed rows or maintain a second handwritten field list.

The [Protocol v1 contract](../protocol/v1/README.md) defines framing, signed configuration,
authenticated bundle structure, commit integrity, and validation order. The
[researcher guide](researcher-guide.md) covers study-design and interpretation limits.

## Bundle scope

A decrypted `.partexp` is one canonical `particeps-research-bundle-v1` object. Its root binds:

- the outer random bundle UUID and bundle kind;
- the exact signed configuration, its SHA-256, signer key ID, and Ed25519 signature;
- the exact event-source-registry SHA-256;
- producing platform/client version and export wall time;
- one experiment snapshot containing only complete authenticated commits.

The experiment identity is the tuple `(experiment_id, configuration_id,
participant_instance_id)`. `participant_instance_id` is a random UUID created for each accepted
import. `assigned_participant_id` is an optional researcher-authored opaque code and can link a
personalized study to an external roster; treat it as personal data.

The experiment object records:

| Field | Meaning |
| --- | --- |
| `state` | Durable internal state at the exported boundary. Lifecycle history comes from ordered `study_runtime.v1` events, not a second transition array. |
| `first_commit_sequence`, `last_commit_sequence`, `commit_count` | Exact complete-commit window carried by this bundle. |
| `durable_through_commit` | Device commit head when the snapshot was captured. |
| `next_commit_sequence` | One past the device commit head. |
| `retained_from_commit` | Lowest complete commit still present locally. |
| `uploaded_through_commit` | Highest contiguous commit acknowledged by an exact upload receipt. |
| `evaluated_through_commit` | Highest commit durably consumed by the automation reducer. |
| `event_count` | Sum of event counts in the exported commits. It is not inferred from a contiguous event range because an `EngineCommit` may contain no event. |
| `lifetime_data_event_count` | Collector data events admitted over the study lifetime; system audit events are excluded. |

All counters and identifiers wider than a bounded registry integer are canonical non-negative
decimal strings.

### Manual export and automatic upload

A manual export begins at `retained_from_commit` and ends at the durable boundary captured when
export starts. Automatic upload begins at `uploaded_through_commit + 1` and chooses a boundary
between complete commits near its plaintext budget. One oversized but valid commit can make the
bundle exceed that soft budget; a commit is never split.

An accepted upload receipt advances the contiguous commit watermark only when the UUID, digest,
byte count, configuration digest, first/last commit, commit count, and event count all match the
staged immutable ciphertext. Replays reuse the same UUID and exact bytes.

Join repeated exports on the experiment identity above. Deduplicate `EngineCommit` by
`commit_sequence` and `commit_sha256`; the same sequence with different authenticated content is a
conflict. Within the resulting chain, event identity is `(participant_instance_id,
sequence_number)`. Never use last-write-wins for either conflict.

## `EngineCommit`

`commits[]` is the incremental source of truth. Every commit has exactly:

| Field | Meaning |
| --- | --- |
| `commit_sequence` | Positive contiguous commit number. |
| `previous_commit_sha256` | Digest of the preceding commit, or the fixed genesis digest for commit 1. |
| `commit_sha256` | Digest over the complete canonical binary commit preimage. |
| `input_kind` | The external fact reduced by this transaction: source observation, lifecycle command, timer wake, random selection, action/upload/resource result, safety failure, or recovery. |
| `consumed_pending_input_sha256` | Digest of a staged causal observation consumed by a resource barrier, otherwise null. |
| `committed_at` | Coordinator observation time. |
| `source_observations` | Provenance and coverage for collector batches consumed by this commit. |
| `events` | Ordered collector and system events produced by the transaction. |
| `mutations` | Typed durable timer, action, resource, upload-ack, and reducer-checkpoint changes. A random-selection input materializes through timer and reducer-checkpoint mutations; it is not a component kind. |
| `successor_projection` | Complete scalar runtime state after the transaction. |
| `resulting_checkpoint_sha256` | Digest of the reducer/runtime checkpoint resulting from the transaction. |

The commit footer, chain, checkpoint digest, source-observation digest, and successor projection
are independently recomputed during export verification and offline analysis. A partial frame,
missing commit, divergent digest, or mutation/projection mismatch rejects the dataset.

Encrypted runtime snapshots are caches. They are not exported as provenance and cannot replace a
commit missing from the retained chain. On-device recovery authenticates every complete frame from
`retained_from_commit` through the snapshot's named footer; only prefixes strictly below that floor
may be absent. Range export decrypts and emits one commit at a time and stops at its requested
complete-commit boundary.

## Source observations and coverage

A `SourceObservation` describes one admitted collector batch without repeating batch metadata in
every event. It binds:

- `source_id` and `schema_version`;
- applied resource generation and producer ordinal;
- the active `condition_epoch_id`;
- optional half-open coverage with an explicit clock basis;
- first/last event sequence, event count, and an exact encoded SHA-256;
- observation sequence and admission kind.

One batch contains 1–4,096 events from exactly one source/schema/generation. A successful
retrospective poll or boundary flush that emits no event is represented by a zero-event coverage
advance, so source cursor and coverage can move without inventing a placeholder event.

Producer ordinals and coverage must be contiguous for the retained chain. The current source cursor
and next ordinal appear in `successor_projection.source_checkpoints`. Coverage overlap, an event not
covered by exactly one observation, or a checkpoint that diverges from observations fails closed.

## Event envelope

Every collector and system event uses the same exact envelope:

```json
{
  "condition_epoch_id": "00000000-0000-4000-8000-000000000001",
  "event_type": "BATTERY_STATE",
  "fields": {
    "percentage": "82"
  },
  "observed_time": {
    "boot_session_id": "boot-session",
    "elapsed_realtime_nanos": "12345678901234",
    "wall_time_utc_millis": "1767225600000"
  },
  "schema_version": 1,
  "sequence_number": "42",
  "source_id": "battery_state.v1"
}
```

| Field | Meaning |
| --- | --- |
| `sequence_number` | Positive, study-wide event order shared by all sources. |
| `source_id`, `schema_version`, `event_type` | Closed identity tuple resolved only through the registry. Event names are not globally unique without source and schema. |
| `condition_epoch_id` | UUID of the fully verified applied-resource vector under which collector data was admitted. Nullable only where the system event contract permits it. |
| `observed_time` | Runtime observation using wall, same-boot elapsed-realtime, and boot-session clocks. |
| `fields` | Exact string-to-string payload validated by the selected registry event contract. |

An unknown source, event, schema version, member, field, enum, or invalid typed wire value rejects the
whole bundle. There is no generic-event fallback.

### Typed wire field strings

Wire field values remain strings even when their registry type is boolean, integer, finite float,
UUID, digest, enum, or embedded JSON. Generated typed decoders enforce each declared grammar and
physical bound. Boolean and integer spellings are canonical; floats use the finite Protocol decimal
grammar; embedded JSON must be syntactically valid and have no duplicate object member names, but
need not use JCS whitespace or member order. Missing and nullable are different: a field can
be absent only when its contract says it is not required; JSON null is never substituted for an
absent event field.

For automation predicates, an absent field makes every operator false, including `ne`. Values for
`in` are typed-canonical, sorted, unique, and bounded. A signed float literal uses exact Java
`Double.toString` spelling; an event float may use any declared decimal wire spelling, and the
reducer compares their finite binary64 values. Window sums are exact integer arithmetic over fields
the registry explicitly marks as summable.

## Time and attribution

`ResearchTime` carries three values:

| Field | Basis | Use |
| --- | --- | --- |
| `wall_time_utc_millis` | Android wall clock | Calendar display and UTC deadlines. It can jump and is not trusted as monotonic. |
| `elapsed_realtime_nanos` | Android elapsed realtime | Ordering and duration within one boot; includes deep sleep. |
| `boot_session_id` | Random per-boot identity | Prevents elapsed values from different boots being compared. |

The registry specifies the occurrence clock for each event. Sensor source elapsed times use the
same Android elapsed-realtime basis. Keyboard uptime excludes deep sleep. Usage and network-usage
polls are retrospective: their source/coverage timestamps, not batch observation time, determine
attribution.

Wall-clock discontinuity, reboot, or an interval that cannot be assigned safely produces an
explicit quality gap and resets affected state. Analysis never guesses, interpolates, or divides an
unattributable interval across conditions.

For a wall-clock gap, every retrospective source cursor is discarded and no crossed backlog is
emitted. Session latches, keyed presence, windows, and sequences reset; a running study rotates its
condition epoch before admitting new data. A paused reboot requires a trusted new-boot anchor before
Resume, while Complete and Withdraw remain available without reopening admission.

The signed duration is enforced independently of collector activity. Its authenticated
`STUDY_DEADLINE_TIMER` same-boot target is an exclusive admission boundary, and the durable due wake
automatically completes the study even when the wakeup adapter runs late.

## Condition epochs

A condition epoch begins only after the complete desired resource vector has applied and verified.
`study_condition.v1/CONDITION_EPOCH_ACTIVATED` records its UUID, signed-configuration digest,
canonical applied vector, vector digest, reason, and boundary time. Deactivation records the same
identity/vector plus the exact reason and boundary.

Every collector event and observation admitted while `RUNNING` belongs to exactly one active epoch.
Epochs do not overlap. A resource change closes admission, flushes retrospective sources at a common
boundary, ends the old epoch, applies and verifies the complete new vector, then activates the new
epoch before reopening admission. An orphan/missing/overlapping epoch, mixed coverage, or vector
digest divergence makes the dataset unpublishable.

`condition_epoch_id` is experimental provenance, not proof that Android delivered every possible
source event. Registry completeness and explicit quality gaps still apply.

## Collector sources

The exact event/field table is generated from the registry. These interpretation notes define what
the source is and is not:

| Source | Interpretation boundary |
| --- | --- |
| `app_lifecycle.v1` | Lifecycle of Particeps activities only; not another app’s lifecycle. |
| `accelerometer.v1`, `gyroscope.v1` | Raw platform sensor samples with declared accuracy/source clock; no filtering or activity inference. |
| `ambient_light.v1`, `proximity.v1` | Platform sensor values after the signed collector threshold/cadence; no image or nearby-device data. |
| `battery_state.v1` | Battery percentage, charging source/state, and power-save state; no battery identity. |
| `temporal_context.v1` | Time-zone/offset/context snapshots; do not infer location from them. |
| `network_state.v1` | Default-network transport/capability flags and optional Android bandwidth estimates; no SSID, addresses, carrier, DNS, destination, or achieved-throughput measurement. |
| `network_usage.v1` | Device-wide Android accounting by configured Wi-Fi/mobile transport over a split coverage window. It is contextual, coarse, and can lag; it is not the shaped apps’ total. |
| `usage_events.v1` | Android usage-history lifecycle/screen/keyguard/boot events. Delivery is retrospective and can be delayed or incomplete. Package is present when Android supplies it. Activity lifecycle records add a study-scoped opaque component token; Particeps never persists the class name. |
| `location.v1` | Fused Android location fixes with platform accuracy/mock/source-time metadata; indoor/urban errors and platform batching remain real. |
| `keyboard_touch.v1` | Timing/geometry on the optional Particeps keyboard only; no key identity, text, clipboard, suggestions, or protected-field touches. |

Collector configuration now uses named profiles. `network_usage.v1` and `usage_events.v1` use
`poll_interval_seconds`; a `usage_events.v1` source referenced by an automation is fixed at 15
seconds. This changes observation latency, not Android’s completeness guarantee.

## System sources

System events are emitted only by the runtime authority and cannot be configured as fake collectors.

| Source | Purpose |
| --- | --- |
| `study_runtime.v1` | Requested and completed lifecycle changes plus typed source-quality gaps. Ordered lifecycle events replace the former metadata transition array. |
| `timer.v1` | Durable schedule, due, and retirement audit for only the deadlines the signed study requires. There is no periodic minute-tick stream. |
| `automation_runtime.v1` | Match/suppression and durable action request/result/failure causality. External side effects are retried with one deterministic invocation ID; the log does not claim the outside world is exactly-once. |
| `interventions.v1` | Notification/survey occurrence lifecycle and one final validated survey submission. Posted is not seen; opened is not submitted. |
| `study_condition.v1` | Generic applied-resource condition epoch lifecycle. |
| `traffic_shaping.v1` | Verified traffic profile application/removal and 60-second/final aggregate counter snapshots for shaping studies only. |

Audit/output-only system events are not automation inputs. This prevents an action’s own audit event
from feeding back into the rule that produced it. In particular, `STUDY_STARTED`, `STUDY_RESUMED`,
and `STUDY_RUNNING` describe lifecycle results but cannot be referenced by `event_match`, sequence,
or window conditions; use `study_session_active` for active-session resource bindings.

## Traffic-shaping counters

`traffic_shaping.v1` is the sole source for aggregate traffic forwarded for the selected apps.
TUN read is uplink and TUN write is downlink. Byte counts include the Layer-3 IP header; packet
counts and the union of monotonic throttle-wait duration are recorded separately by direction.

The applied event binds the signed configuration, selected profile, resource/VPN generation,
package-list digest, optional directional caps, and native applied-profile digest. Periodic
snapshots occur at a logical 60-second cadence and final snapshots occur at epoch boundaries.
Counters are aggregate across all selected apps; they do not identify a package, destination, DNS
name, or payload. A null directional cap means unlimited forwarding through the same local VPN
path, not bypass.

Existing `network_usage.v1` remains a device-wide contextual total and must never be relabelled as
the shaped apps’ traffic. Analysis refuses to publish a dataset with missing/mismatched traffic
profile, counter, epoch, or resource-generation evidence.

## Quality and publication

Particeps analysis validates the signed configuration and registry digest, authenticates complete
commit chains, replays reducer semantics independently, checks timer/action causality, reconciles
coverage, and verifies condition/resource digests before writing Parquet. Partition keys are
`experiment_id/configuration_id/source_id/schema_version/event_type`. Each row carries
`condition_epoch_id` and derived `source_condition_epoch_id`.

The following are dataset-level failures, not warnings to average away: partial/torn commit,
conflicting duplicate, missing source observation, source coverage overlap, illegal producer
ordinal, reducer checkpoint divergence, orphan/overlapping epoch, action without a valid cause,
traffic evidence mismatch, or a source interval that cannot be assigned. No untrusted Parquet or
quality summary is published when any bundle in the selected dataset fails verification.
