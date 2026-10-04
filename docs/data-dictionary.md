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
| `committed_at` | Coordinator observation time: when the runtime admitted the input, not when its events were captured. A windowed gyroscope or accelerometer batch (see [Windowed sensor commits](#windowed-sensor-commits)) commits up to 5 s of awake time after its first sample, and an accelerometer batch can commit later still across a CPU suspend; use each event's observed time for capture time. |
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

If a checkpoint write is interrupted, an incomplete or unauthenticated staging snapshot can be
excluded only when the base snapshot authenticates successfully. Recovery still verifies the full
retained commit chain and pending input before replacing the cache and retiring staging files.
An invalid base, an authenticated malformed or conflicting snapshot, or a missing/corrupt retained
commit fails recovery. This rule applies only to runtime snapshot caches; it does not relax pending
input or safety-record authentication.

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
advance, so coverage can move without inventing a placeholder event; a zero-event barrier flush
also stores the source cursor.

Producer ordinals and coverage must be contiguous for the retained chain. The current source cursor
and next ordinal appear in `successor_projection.source_checkpoints`. Coverage overlap, an event not
covered by exactly one observation, or a checkpoint that diverges from observations fails closed.

The cursor is the retrospective collector's opaque resume token; analysis never interprets it. It
changes only in the commit that carries that source's `BARRIER_FLUSH` observation, and stores the
value the completed flush returned. A barrier flush belongs to a retrospective (`POLL`) source,
carries coverage, appears once per source after every `NORMAL` observation in source-ID order, and
appears only in the commit that closes the epoch. A `RECOVERY` commit records exactly one
`PROCESS_RECOVERY` quality gap; that commit, and one that records a `WALL_CLOCK_CHANGED` gap, drops
every retrospective checkpoint, so the source restarts its coverage and cursor.

Retrospective coverage attributed to an epoch may begin at the epoch's preparation bound, the later
of the previous epoch's deactivation boundary and the latest commit that entered `ACTIVATING`,
because collectors start or resume before the activation commit. In the RC13 exports examined for
this rule, usage coverage started 1–62 ms before activation. Coverage never begins earlier than
that bound and never ends after the deactivation boundary. The interval between the preparation
bound and `activated_at` precedes the confirmed application of the epoch's resources, and nothing
bounds its length. `quality-summary.json` therefore lists, for each participant, every epoch's
`preparation_bound`, `activated_at`, and `deactivated_at`; exclude rows whose source time precedes
`activated_at` when the analysis needs only time under the applied condition.

Coverage never runs backwards. It is empty, `[t, t)`, only in a zero-event barrier flush whose `t`
is the deactivation boundary's wall time: a pause or barrier that lands in the same millisecond as
the collector's last poll. Such a flush adds no time to the epoch.

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

Every event of one commit carries the same envelope epoch: the epoch the commit activates, and
otherwise the epoch active before the commit, or `null` when there is none. The commit that closes
an epoch therefore stamps that epoch on every event it records, including the lifecycle, timer,
and study-deadline events that follow `CONDITION_EPOCH_DEACTIVATED`.

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
explicit quality gap and resets affected state; the windowed-sensor losses below are the documented
exceptions that no gap names. Analysis never guesses, interpolates, or divides an unattributable
interval across conditions.

For a wall-clock gap, every retrospective source cursor is discarded and no crossed backlog is
emitted. Session latches, keyed presence, windows, and sequences reset; a running study rotates its
condition epoch before admitting new data. A paused reboot requires a trusted new-boot anchor before
Resume, while Complete and Withdraw remain available without reopening admission.

The signed duration is enforced independently of collector activity. Its authenticated
`STUDY_DEADLINE_TIMER` same-boot target is an exclusive admission boundary, and the durable due wake
automatically completes the study even when the wakeup adapter runs late.

### Windowed sensor commits

When the signed automation matches no event of `gyroscope.v1` or `accelerometer.v1` and keeps no
sequence or window state, each of those sensors commits its samples in batches through a callback
commit window of up to 5 s of the consumer's awake monotonic time. A sensor that an automation
matches, and both sensors in a study with any `sequence` or `window_threshold` state, commit every
sample without a window. The window changes when a sample commits, never what it records: observed
time and `source_elapsed_realtime_nanos` remain capture time, and condition-epoch attribution is
unchanged. It changes interpretation in three ways:

- **Commit latency.** `committed_at` of a windowed batch can trail its first sample by up to 5 s.
  The accelerometer holds no wake lock, so its window pauses while the CPU is suspended and the
  batch commits after the next wake, which can be hours later.
- **Process death.** Samples captured since the sensor's last recorded event are lost. When the
  durable state was `ACTIVATING`, `RUNNING`, or `PAUSING`, recovery records `SOURCE_QUALITY_GAP`
  with `PROCESS_RECOVERY`. For a windowed sensor the unobserved
  interval begins at that sensor's last recorded event, which can precede the chain's previous
  commit (made by another source, a timer, or a command). Do not bound it by the previous commit or
  by 5 s: for the accelerometer it can include every CPU suspend since that event.
- **Losses that no gap names.** Three admission closures refuse the open batch and record no
  `SOURCE_QUALITY_GAP` for the sensor: a safety pause (up to 5 s of samples before
  `STUDY_SAFETY_PAUSE_REQUESTED`); a TIME_SET or TIMEZONE_CHANGE while the study runs (up to 5 s
  before the change; the only record is the `timer.v1` gap with `WALL_CLOCK_CHANGED`, so treat each
  windowed sensor as unobserved from its last recorded event before that gap until the new epoch's
  activation); and a clock change first observed after the deadline, which completes the study
  without a drain and drops the open batch's samples captured before the deadline.

The [researcher guide](researcher-guide.md#batched-commits-for-the-gyroscope-and-accelerometer)
states the same conditions for study design.

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

A process death or reboot closes the running epoch in the `RECOVERY` commit with
`PROCESS_RECOVERY_UNPROVEN` at the recovery instant, the time of that commit's `PROCESS_RECOVERY`
gap, which may be in the new boot. The commit's `committed_at` can instead keep the previous boot's
clock anchor when the study clock cannot advance across the reboot without trusted UTC; use the
deactivation boundary, not `committed_at`, as the epoch's end. That commit has no traffic-shaping
audit: the recovering process cannot read the counters of a profile the dead process applied, so
the epoch's last traffic evidence is the last periodic snapshot before the death.

A safety pause can also close an epoch without a traffic-shaping audit. When the VPN is revoked or
replaced while the study runs, the native engine stops and the boundary audit cannot read verified
counters, so the `SAFETY_FAILURE` commit records `SAFETY_PAUSED` with no final snapshot or
`TRAFFIC_SHAPING_PROFILE_REMOVED` row. Again the last periodic snapshot is the epoch's last traffic
evidence, and it can be up to 60 seconds old.

An `interventions.v1` event belongs to the epoch in which its `ACTION_REQUESTED` was recorded, and
is bound to that request by `occurrence_id`, `trigger_id`, `intervention_id`, and
`scheduled_for_utc_millis`. A survey requested in the commit that closes an epoch at a barrier is
opened and answered under the next epoch, or while paused, but its `source_condition_epoch_id` is
the request epoch. Its scheduled time is the occurrence's logical time and may lie before that
epoch began, for example when a timer that fell due during a pause fires after Resume.

`condition_epoch_id` is experimental provenance, not proof that Android delivered every possible
source event. Registry completeness and explicit quality gaps still apply.

## Collector sources

The exact event/field table is generated from the registry. These interpretation notes define what
the source is and is not:

| Source | Interpretation boundary |
| --- | --- |
| `app_lifecycle.v1` | Lifecycle of Particeps activities only; not another app’s lifecycle. |
| `accelerometer.v1`, `gyroscope.v1` | Raw platform sensor samples with declared accuracy/source clock; no filtering or activity inference. Unless automation matches the sensor or keeps sequence/window state, samples commit through a window of up to 5 s: see [Windowed sensor commits](#windowed-sensor-commits) for commit latency, where a `PROCESS_RECOVERY` interval begins, and the losses no quality gap names. |
| `ambient_light.v1`, `proximity.v1` | Platform sensor values after the signed collector threshold/cadence; no image or nearby-device data. |
| `battery_state.v1` | Battery percentage, charging source/state, and power-save state; no battery identity. |
| `temporal_context.v1` | Time-zone/offset/context snapshots; do not infer location from them. |
| `network_state.v1` | Default-network transport/capability flags and optional Android bandwidth estimates; no SSID, addresses, carrier, DNS, destination, or achieved-throughput measurement. |
| `vpn_state.v1` | Independent Android VPN callbacks, including other-UID networks. An omitted initial `connected` value means unknown; callbacks do not identify a VPN provider or expose traffic content. |
| `network_throughput.v1` | Device-wide transfer-counter deltas over actual monotonic intervals. Passive accounting, not a capacity test or per-app throughput; VPN interface accounting can overlap. |
| `notification_events.v1` | Published RC9 event contract retained for interpretation; the current Android collector is unavailable and cannot be selected in new configurations. Historical records contain posting package, post/callback times, and an opaque update token; no notification text or proof of a new message, reading, or wake-up cause. |
| `screen_state.v1` | Default-display power, interactive state, and keyguard state, including locked screens. Always-on display and DOZE differ from normal screen use; no attention inference. |
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

A commit that reduces several inputs records its automation-timer schedules and retirements in one
of two renderings. The net rendering, which the current runtime writes, retires only a durable
timer that the commit removes or replaces and schedules only a timer that the commit leaves in the
durable map. The complete rendering, which RC13 writes, records every intent of the batch: a
`TIMER_SCHEDULED` row need not name a timer the durable map ever holds, and a `TIMER_RETIRED` row
can repeat one timer generation. Rows of one commit are ordered by timer ID, retirements first,
not in the order the reducer produced the intents. Every RC13 `RECOVERY` commit of a running pilot
study is such a commit: it schedules condition timers that its own safety pause retires, and it
leaves no condition timer. Timer rows are therefore audit evidence of intents, not a ledger of
durable timers. Do not count `TIMER_SCHEDULED` or `TIMER_RETIRED` rows as timers or reconstruct
pending timers from them; the analyzer verifies the durable timer map from each commit's `TIMER`
components, which the dataset does not publish.

The study deadline (`producer_key` `study-deadline`) is retired by the commit that requests
Complete or Withdraw from `RUNNING`. When that stop does not finish, as after a failed resource
release or a process death while `PAUSING`, the next Resume or recovery re-arms the deadline at
generation 1 under the same timer ID. A `TIMER_SCHEDULED` row can therefore repeat the identity and
generation of an earlier `TIMER_RETIRED` row.

Audit/output-only system events are not automation inputs. This prevents an action’s own audit event
from feeding back into the rule that produced it. In particular, `STUDY_STARTED`, `STUDY_RESUMED`,
and `STUDY_RUNNING` describe lifecycle results but cannot be referenced by `event_match`, sequence,
or window conditions; use `study_session_active` for active-session resource bindings.

## Traffic-shaping counters

`traffic_shaping.v1` is the sole source for aggregate traffic forwarded for the selected apps.
Uplink counters record packets admitted from the TUN to the local forwarding stack after pacing;
they do not count every raw TUN read. Downlink counters record successful paced TUN writes.
Byte counts include IP/transport headers and admitted retransmitted packets; packet counts and
the union of monotonic throttle-wait duration are recorded separately by direction.

Packets dropped before admission, including bounded uplink queue capacity and CoDel drops, are
excluded from these byte and packet counters. The counters measure forwarding under the cap,
not all traffic offered by apps or a packet-loss rate, and do not establish lossless delivery or
payload receipt at the remote endpoint.

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

Registry payload fields become columns under their own names, with two exceptions:

- A payload field named like a provenance or partition column is written as `payload_<name>`. Its
  field metadata `particeps.payload_field` records the registry name. Six fields are renamed:
  `condition_epoch_id` in `study_condition.v1` `CONDITION_EPOCH_ACTIVATED` and
  `CONDITION_EPOCH_DEACTIVATED` and in `traffic_shaping.v1` `TRAFFIC_SHAPING_PROFILE_APPLIED`,
  `TRAFFIC_SHAPING_SNAPSHOT`, and `TRAFFIC_SHAPING_PROFILE_REMOVED`, and `source_id` in
  `study_runtime.v1` `SOURCE_QUALITY_GAP`. The envelope column `condition_epoch_id` and the
  partition column `source_id` keep their provenance meaning.
- A `json_string` column holds the authenticated wire text of the field, exactly as recorded, not a
  re-serialized value. Parse it with a JSON reader to use its members.

The following are dataset-level failures, not warnings to average away: partial/torn commit,
conflicting duplicate, missing source observation, source coverage overlap, illegal producer
ordinal, reducer checkpoint divergence, orphan/overlapping epoch, action without a valid cause,
traffic evidence mismatch, or a source interval that cannot be assigned. No untrusted Parquet or
quality summary is published when any bundle in the selected dataset fails verification.
