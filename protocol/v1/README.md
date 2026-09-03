# Particeps Protocol v1

This directory is the normative, language-neutral contract for Particeps Protocol v1. Kotlin,
TypeScript, Python, and future Swift implementations are conforming implementations; none of
them is the specification.

Protocol v1 is a destructive pre-1.0 replacement, and this document carries its second and final
identity. `schema_version` stays the JSON number `1`. Every identity string is new: the signed
configuration magic is `PTCCFG01`, the encrypted export magic is `PTCEXP01`, the bundle format is
`particeps-research-bundle-v1`, the join scheme is `particeps://join/v1`, the upload media type is
`application/vnd.particeps.research-bundle`, and the routing headers are `X-Particeps-*`.

Two classes of input are therefore invalid rather than old. Artifacts made by the pre-v1
implementation are invalid, as they always were. Artifacts bearing the retired Android Data
Collector identity — `ADCCFG01`, `ADCEXP01`, `research-bundle-v1`, `adc://join/v1`,
`application/vnd.adc.research-bundle`, or any `X-ADC-*` header — are invalid too. Neither class is
an earlier dialect of this protocol. Readers MUST NOT retain a parser, migration path, dual
interpretation, alias, sniffing heuristic, or fallback for either. They MUST fail closed on both
exactly as on random bytes. The hostile corpus in this directory carries executable coverage for
both. [CHANGELOG.md](../../CHANGELOG.md) records which release retired which spelling, and what
that asks of someone who already installed one.

The companion [`event-source-registry.json`](event-source-registry.json) is the sole closed-world
registry for collector and system sources, event identities, fields, operators, clock semantics,
delivery, completeness, privacy, rate bounds, access, and collector profile schemas. Its exact
meta-schema is [`event-source-registry.schema.json`](event-source-registry.schema.json).
The generated language-neutral registry corpus under
[`conformance/event-source-registry/`](conformance/event-source-registry/) binds the exact current
JCS bytes/digest, semantic-projection digest artifact, and portable hostile mutations.
[`conformance-vectors.json`](conformance-vectors.json) and
[`join-link-vectors.json`](join-link-vectors.json) are the executable valid and hostile corpora.
Start with these files; platform code must not define a second contract.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, and MAY are to be
interpreted as described by RFC 2119 and RFC 8174.

## Common encoding rules

- All binary integers are unsigned, big-endian, and use the exact width stated below.
- JSON is UTF-8 RFC 8785 JSON Canonicalization Scheme (JCS). Duplicate object members,
  noncanonical bytes, malformed UTF-8, non-integral JSON numbers, and trailing bytes are invalid.
- JSON numbers are permitted only where the schema supplies bounded integral minimum and maximum
  values. Sequence numbers, byte counts, Unix times, monotonic times, and client build numbers are
  canonical decimal strings matching `0|[1-9][0-9]*`.
- UUIDs use lowercase RFC 4122 text in JSON and headers, and their 16 network-order bytes in binary
  framing. Producers generate cryptographically random version-4 bundle IDs.
- SHA-256 values use 64 lowercase hexadecimal characters in JSON and in `X-Particeps-*` headers.
- Ed25519 and X25519 keys are raw 32-byte values encoded as unpadded base64url. Signatures are raw
  64-byte Ed25519 signatures encoded the same way in JSON. Tink JSON, protobuf keysets, X.509,
  PKCS#8, padded base64, and standard-base64 wire keys are invalid.
- Every decoder is closed-world. An unknown member, enum, event source, schema version, event type,
  profile field, platform, key context, or framing byte fails the whole artifact.
- Implementations MUST reject values before allocating from a claimed length. A complete
  `PTCEXP01` upload body is limited to 33,554,432 bytes (32 MiB).

## Signed configuration (`PTCCFG01`)

The configuration is an Android-targeted, closed-world JCS object with exactly
`assigned_participant_id`, `automations`, `collectors`, `configuration_id`, `consent`,
`duration_hours`, `expires_at`, `experiment_id`, `export`, `interventions`, `issued_at`,
`minimum_client_version`, `platform`, `purpose`, `researcher`, `schema_version`, `signer`,
`storage`, `surveys`, `title`, `traffic_shaping`, and `upload`. `schema_version` is the JSON number
`1`, `platform` is `"android"`, and `minimum_client_version` is a canonical positive decimal
string. Android and future iOS configurations may share `experiment_id`; they MUST use different
`configuration_id` values and signatures. The Android client rejects every other platform.

All configuration quantities are integral physical units. In particular,
`location.v1.minimum_displacement_millimeters` replaces the former floating-point metre value.
Collector profiles and event-source identities are defined only by
[`event-source-registry.json`](event-source-registry.json).
All signed human-readable text length bounds count UTF-16 code units, matching JVM and ECMAScript
`String.length`; an astral Unicode scalar therefore counts as two units.

`collectors` is a sorted array of exact `{id, profiles, required}` objects. `profiles` contains
1–64 exact `{config, id}` objects with unique IDs; `config` MUST match that collector source's
registry-defined closed-world profile schema. `interventions` is a sorted array of reusable exact
`{action, id, required}` one-shot notification or survey actions. An intervention never embeds a
trigger. `traffic_shaping` is either `{}` (disabled) or exact `{profiles, target_packages}`;
`target_packages` contains 1–64 sorted unique Android application IDs and `profiles` contains 1–64
exact `{downlink_kbps, id, uplink_kbps}` objects. A directional cap is `null` or a JSON integer in
1–1,000,000 kbps. `1 kbps` means 1,000 aggregate Layer-3 bits per second at the TUN boundary,
including IP/transport headers and retransmitted packets observed there. A configuration declares
at most 64 stateful resources in total, including the traffic-shaping actuator. `surveys` contains
at most 128 survey definitions.

`automations` is REQUIRED and may be empty only when the configuration declares no collector,
intervention, or traffic-shaping resource. It contains at most 128 sorted unique definitions:

- an occurrence automation is exact `{availability_seconds, cooldown, guard, id,
  intervention_id, maximum_activations, trigger, type}` with `type: "occurrence"`;
- a resource binding is exact `{cases, default_profile_id, id, resource, type}` with
  `type: "resource_binding"`, an exact `{id, kind}` resource key, 1–16 ordered cases, and first-true
  case semantics. `profile_id: null` means inactive. Every stateful resource has exactly one owner.

The closed trigger set is `event_match`, bounded `sequence`, bounded `window_threshold`,
`condition_rising_edge`, and `schedule`. Schedules are `one_time`, `interval`, `daily_local`, or
`random_window`. The closed state-condition set is `study_session_active`, `event_latch`,
`keyed_presence`, `held_for`, `elapsed_at_least`, bounded `window_threshold`, `all`, `any`, and
`not`. Event matchers identify exact `{source_id, schema_version, event_type}` registry entries and
carry only registry-authorized field predicates. `event_match`, sequence, and window evaluation
MUST select `OBSERVED_RESEARCH_TIME` or `PRIMARY_SOURCE_TIME`; absence of a matched field makes
every predicate false, including `ne`. Matcher arrays are OR, and reset/exit wins if the same event
matches both sides. `in.values` has 1–64 typed-canonical, sorted, unique values. Window sums are
exact integer arithmetic and only registry-authorized required integer fields may be summed.
`STUDY_STARTED`, `STUDY_RESUMED`, and `STUDY_RUNNING` are audit-only lifecycle outputs and MUST
NOT appear in an event matcher, sequence step, or window selector. Active-session behavior uses the
durable `study_session_active` state condition; lifecycle output events are never fed back into the
automation reducer.

Conditions are limited to depth 8 and 64 nodes; sequences to 16 steps; windows to seven days and
4,096 retained entries after applying the registry's enforced `E × ceil(W/P)` rate bound (and the
sequence step multiplier); lifetime occurrence activations to 512. A collector used by any other
automation MUST be required and remain non-inactive throughout the active study session.
Self-disabling trigger sources, multiple resource owners, dependency cycles, unbounded sequence or
window sources, unsupported feedback, arbitrary code, SQL, regular expressions, generic JSON
comparison, and remote triggers are invalid before signing.

The envelope is exactly:

```text
offset    size  value
0         8     ASCII "PTCCFG01"
8         2     signer_key_id_length (u16)
10        4     configuration_length (u32)
14        K     signer_key_id UTF-8
14+K      N     configuration_jcs
14+K+N    64    Ed25519 signature
```

`signer_key_id_length` is in `3..64`, and its strict UTF-8 value matches
`[a-z0-9][a-z0-9-]{2,63}`. `configuration_length` is in `2..1,048,576`. The envelope ends after the
signature. The signed message is exactly `configuration_jcs`, without a framing prefix. The key ID
must equal `configuration.signer.key_id`. The verifier obtains the raw Ed25519 public key from
`configuration.signer.public_key`, verifies the fixed 64-byte signature, then applies signer
pinning policy. A valid self-contained signature proves integrity, not publisher identity. What
that trust model is and is not worth — pinning, fingerprint comparison, and publisher
impersonation — is in the [threat model](../../docs/threat-model.md).

The configuration SHA-256 used everywhere below is SHA-256 over `configuration_jcs`, not over the
envelope.

## Immutable join link (`particeps://join/v1`)

A join link is a transport pointer to one immutable `PTCCFG01` artifact. Its exact ASCII form is:

```text
particeps://join/v1?artifact=<percent-encoded-url>&sha256=<64-lowercase-hex>&signer_fingerprint=<32-uppercase-hex>
```

The query order is fixed. RFC 3986 unreserved bytes are literal; every other artifact-URL byte is
percent encoded with uppercase hexadecimal. Duplicate, missing, reordered, or unknown query
members, lowercase escapes, decoded non-ASCII, and links longer than 4,096 bytes are invalid.
`signer_fingerprint` is the first 16 bytes of SHA-256 over the raw Ed25519 public key. It has no
spaces on the wire.

To prevent Java `URI` and WHATWG `URL` from silently accepting different text for one locator, the
decoded artifact URL uses this deliberately narrow canonical HTTPS profile:

- at most 2,048 ASCII bytes and exactly lowercase `https://`;
- a lowercase DNS-style host of labels in `[a-z0-9-]`, each 1–63 bytes, with at least one ASCII
  letter overall; no user information, IP literal, trailing dot, or internationalized host;
- no port, or a canonical decimal port in `1..65535` other than the redundant default `443`;
- one or more non-empty path segments containing only `[A-Za-z0-9._~-]`; `.` and `..` segments,
  repeated slashes, percent escapes, a trailing slash, query, and fragment are invalid.

This profile is intentionally sufficient for an immutable filename or opaque path token, not a
general browser URL. A personalized artifact MUST use at least 128 bits of random opaque path
material; the authoring tools require a final base64url segment of at least 22 characters. It MUST
NOT put an assigned participant ID in the URL or link.

The Android app rejects a join while any active study or pending deletion exists. It performs one
bounded GET with redirects and implicit retries disabled, and stages the response under no-backup
storage. It then checks the complete artifact SHA-256 and executes the ordinary Ed25519
verification and fingerprint / consent flow. The host cannot replace accepted bytes: digest
mismatch fails before signature verification. Staging is cleared on process startup, before each
attempt, and after success or failure. There is no polling, refresh, replacement, background
update, or assigned participant ID in the join URI.

## Encrypted bundle (`PTCEXP01`)

The only cryptographic suite is RFC 9180 base mode (`mode = 0x00`) with:

| Parameter | Value |
| --- | --- |
| KEM | DHKEM(X25519, HKDF-SHA256), `0x0020` |
| KDF | HKDF-SHA256, `0x0001` |
| AEAD | AES-256-GCM, `0x0002` |

Each bundle creates an independent random 32-byte AES-256-GCM content key and a fresh random
12-byte content nonce. HPKE seals the 32-byte content key to the configuration's researcher X25519
public key. With the fixed suite, `enc` is 32 bytes and the sealed content-key ciphertext is 48
bytes including its tag.

The container is exactly; document ciphertext consumes the remainder of the file:

```text
offset     size  value
0          8     ASCII "PTCEXP01"
8          16    bundle_id UUID bytes
24         32    configuration_sha256
56         2     researcher_key_id_length (u16)
58         12    AES-256-GCM content nonce
70         K     researcher_key_id UTF-8
70+K       80    HPKE wrapped content key: enc[32] || sealed_key[48]
150+K      C     encrypted document and 16-byte GCM tag, to end of file
```

`researcher_key_id_length` is in `3..64`; the decoded value matches
`[a-z0-9][a-z0-9-]{2,63}`. `C` is greater than the 16-byte GCM tag. An automatic-upload container is
at most 32 MiB. A manual export has no 32 MiB wire limit and is instead bounded by the signed local
storage quota, so manual-export readers stream it. There is no ciphertext-length field: the file or
HTTP body ends the container, and truncation or appended bytes fail authentication or JCS
validation.

### Cryptographic context

The following exact JCS bytes bind both cryptographic layers (`bundle_id` is lowercase UUID text):

```text
context = UTF8({"bundle_format":"particeps-research-bundle-v1","bundle_id":"<bundle-id>","configuration_sha256":"<lowercase-hex>","researcher_key_id":"<key-id>"})
```

The context is RFC 9180 `info` when sealing the content key; HPKE base-mode `aad` is empty. The
context bytes are separately used as AES-256-GCM associated data for the document. A wrong bundle
format, bundle ID, configuration digest, researcher key ID, HPKE suite, `enc`, sealed key, or
content nonce fails authentication.

The bundle format string is authenticated here, which is what makes the rename to Particeps a wire
change rather than a spelling. The two magics are length-preserving, so every binary offset above
is unchanged. `particeps-research-bundle-v1` is ten bytes longer than the name it replaces, so a
bundle sealed under the retired context fails authentication rather than decoding into anything.
Every deterministic vector and sealed fixture was regenerated rather than edited.

### Authenticated document

The decrypted bytes are one JCS `particeps-research-bundle-v1` object with exactly
`bundle_id`, `bundle_kind`, `configuration`, `configuration_sha256`, `configuration_signature`,
`event_source_registry_sha256`, `experiment`, `exported_at_utc_millis`, `format`, and `producer`.
`format` is exactly `"particeps-research-bundle-v1"`; `bundle_id` and
`configuration_sha256` repeat the authenticated outer identities; `bundle_kind` is
`"manual_export"` or `"automatic_upload"`; `configuration` is byte-for-byte equivalent after JCS
to the signed object; `configuration_signature` is exact `{signature, signer_key_id}`; and
`event_source_registry_sha256` is the lowercase SHA-256 published in
[`generated/event-source-registry.sha256`](generated/event-source-registry.sha256). `producer` is
exact `{client_version, platform}` with a positive decimal client version and the signed platform.

The `experiment` object has exactly `assigned_participant_id`, `commit_count`, `commits`,
`configuration_id`, `durable_through_commit`, `evaluated_through_commit`, `event_count`,
`experiment_id`, `first_commit_sequence`, `last_commit_sequence`, `lifetime_data_event_count`,
`next_commit_sequence`, `participant_instance_id`, `retained_from_commit`, `state`, and
`uploaded_through_commit`. All counts, watermarks, and sequences here are canonical decimal
strings. `commits` contains a non-empty complete range;
`commit_count = last_commit_sequence - first_commit_sequence + 1 = commits.length`, and the first
and last entries carry those sequence values. `event_count` is the sum of the events in those
commits; it may be zero. Export and retention MUST split only at commit boundaries.

`state` is exactly one of `IMPORTED`, `CONFIG_VERIFIED`, `CONSENT_PENDING`, `ACCESS_SETUP`,
`READY`, `ACTIVATING`, `RUNNING`, `PAUSING`, `PAUSED`, `COMPLETED`, or `WITHDRAWN`. Lifecycle
history is represented only by typed `study_runtime.v1` events in the commit chain. The scalar
state is a replay checkpoint, not a second history.

Each EngineCommit has exactly `commit_sequence`, `commit_sha256`, `committed_at`,
`consumed_pending_input_sha256`, `events`, `input_kind`, `mutations`, `previous_commit_sha256`,
`resulting_checkpoint_sha256`, `source_observations`, and `successor_projection`.
`commit_sequence` is positive and contiguous across the exported range; commit 1 uses 64 zeroes as
its predecessor, and every later commit names the preceding `commit_sha256`. `input_kind` is one of
`SOURCE_OBSERVATION`, `LIFECYCLE_COMMAND`, `TIMER_WAKE`, `RANDOM_SELECTION`, `ACTION_RESULT`,
`UPLOAD_ACKNOWLEDGEMENT`, `RESOURCE_RESULT`, `SAFETY_FAILURE`, or `RECOVERY`.

The reducer batch reconstructed from one SourceObservation or EngineCommit is an indivisible
semantic boundary. It first consumes every ordered input into condition state, then evaluates the
complete resource-binding vector and allocates desired generations exactly once. A transport or
analysis replay may partition a stream only between these boundaries; it MUST NOT split one
observation/commit or reconcile resources after an intermediate event. Consequently, a delayed
foreground-enter/exit pair that ends in the previously applied profile creates neither a resource
change nor an unapplied desired-generation increment.

Reducer order is `RecordedEvent.sequence_number`, not manifest order, producer ordinal, or source
time. In an ordinary commit, non-empty observation ranges follow `observation_sequence` order. In a
pending-consuming resource barrier, manifests preserve admission and producer continuity as the
eventful causal `NORMAL` observation first, followed by pre-drain `NORMAL` observations and then
`BARRIER_FLUSH` observations. Their event ranges use the one intentional rotation: every eventful
pre-drain/flush range first, in manifest order, and the causal range last. Zero-event coverage does
not enter the reducer order. A reader MUST reject every other range permutation and MUST reject
this rotation when `consumed_pending_input_sha256` is null.

Reducer-input reconstruction is closed by `input_kind` (runtime/audit events not listed here are
side-effect evidence, not implicit inputs):

`study_runtime.v1` lifecycle output events—including `STUDY_STARTED`, `STUDY_RESUMED`, and
`STUDY_RUNNING`—record the result of lifecycle reduction. They do not themselves reconstruct an
event input and cannot create a second transition or automation match.

- `SOURCE_OBSERVATION` carries an ordered collector-event batch, a single quality-gap input with
  no collector event, or an empty post-commit barrier/coverage batch;
- `LIFECYCLE_COMMAND` is side-effect-only and empty, or carries an optional collector-event prefix
  followed by exactly one lifecycle input;
- `TIMER_WAKE` is side-effect-only and empty, or carries an optional collector-event prefix
  followed by exactly one timer-due or clock-discontinuity input. The runtime-owned signed-duration
  deadline instead carries one or two lifecycle inputs; when the deadline is first observed across
  a wall-clock discontinuity, one clock-discontinuity input precedes the first lifecycle input;
- `RANDOM_SELECTION` carries exactly one timer-materialized input;
- `ACTION_RESULT` and `UPLOAD_ACKNOWLEDGEMENT` carry no reducer input;
- `RESOURCE_RESULT` carries no reducer input or exactly one lifecycle input, without collector
  events;
- `SAFETY_FAILURE` carries one or two lifecycle inputs, without collector events; and
- `RECOVERY` carries an optional collector-event prefix, then exactly one quality-gap input,
  followed only by lifecycle inputs.

A reader MUST reject a commit whose authenticated kind and reconstructed input shape differ, even
when its supplied checkpoint and digest are mutually self-consistent.

An event has exactly `condition_epoch_id`, `event_type`, `fields`, `observed_time`,
`schema_version`, `sequence_number`, and `source_id`. `schema_version` is a JSON integer; sequence
is a positive decimal string; `condition_epoch_id` is an explicit UUIDv4 string or `null`; and
`fields` is a sorted string-to-string object. `observed_time` is exact
`{boot_session_id, elapsed_realtime_nanos, wall_time_utc_millis}`. The identity
`(source_id, schema_version, event_type)`, exact field set, valid typed wire values, encoded bound,
exportability, and emission authority MUST match the event-source registry. Integer and boolean
fields therefore remain strings on the event wire. Decimal floats MUST match
`[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)`, parse as finite binary32 or
binary64 as declared, and obey registry bounds. Missing fields, extra fields, `NaN`, infinities,
hexadecimal values, and unknown events fail the bundle rather than becoming generic rows. A
`json_string` field accepts strict JSON without requiring JCS whitespace/member order, but duplicate
object member names at any nesting level are invalid.

Signed automation predicate literals are stricter than the float event wire: a float literal MUST
use the exact Java `Double.toString` spelling of its finite binary64 value. Reducers parse an
admitted event value with the decimal wire grammar above, parse the signed literal canonically,
and compare the resulting numeric values. This preserves a unique signed configuration without
rejecting another declared decimal spelling from an event producer.

Each collector submission has one SourceObservation with exactly `admission_kind`,
`condition_epoch_id`, `coverage`, `encoded_sha256`, `event_count`, `first_event_sequence`,
`last_event_sequence`, `observation_sequence`, `producer_ordinal`, `resource_generation`,
`schema_version`, and `source_id`. `event_count` is a JSON integer in 0–4,096; schema version is a
JSON integer; generation, ordinal, observation sequence, and nullable event-range endpoints are
decimal strings. A non-empty observation owns exactly one contiguous range of collector events in
the same commit, all with the observation's source, schema, and epoch, and no collector event may
be unowned or owned twice. A zero-event coverage advance has null endpoints and non-null exact
`{clock_basis, end_exclusive, start_inclusive}` half-open coverage. Retrospective sources always
carry coverage. Producer ordinals are contiguous within a resource generation; coverage is
contiguous within one generation and clock basis. Manifest sequence and owned event-range order
are identical except for the exact pending-barrier rotation defined above; contiguous ownership is
still complete and unique in either case.

`mutations` contains sorted exact `{canonical_value, component_id, component_kind, operation}`
objects. `operation` is `UPSERT` with a non-null canonical component value or `REMOVE` with null.
The closed component kinds, in canonical mutation order, are `AUTOMATION_CHECKPOINT`, `TIMER`,
`STUDY_DEADLINE_TIMER`, `RESOURCE_AUDIT_TIMER`, `ACTION_INVOCATION`,
`UPLOAD_ACKNOWLEDGEMENT`, `RESOURCE`, and `RESOURCE_CLEANUP`. `RANDOM_SELECTION` is an
`EngineInputKind`, never a component kind: its durable materialization updates the `TIMER` and
`AUTOMATION_CHECKPOINT` components in the same commit. `TIMER` contains only automation-reducer
timers and MUST equal the timer map in the automation checkpoint.
`STUDY_DEADLINE_TIMER` and `RESOURCE_AUDIT_TIMER` are distinct runtime-owned timer classes and
MUST NOT be inserted into that checkpoint map. The canonical values are durable reducer
inputs/state, not UI state; a reader that cannot decode the current component codec MUST reject
instead of skipping it.

`STUDY_DEADLINE_TIMER` has the single component ID `study-duration` and the
`durable-timer-v1:` codec. Its timer ID is the lowercase SHA-256 of the NUL-separated sequence
`particeps-study-deadline-timer-v1`, configuration SHA-256, `study-duration`, and
`study-deadline`. Its owner and producer are respectively `study-duration` and `study-deadline`;
it has a `SAME_BOOT_MONOTONIC` target, the signed-duration UTC deadline as logical wall evidence,
and no expiry. The initial generation is 1 and each trustworthy boot or wall-clock re-anchor
increments it. A started nonterminal study with remaining signed duration MUST retain exactly one
such component; terminal state MUST retain none.

`RESOURCE_CLEANUP` is the bounded ledger for an applied or attempted resource state that the
runtime could not yet prove inactive during fail-closed containment. Its component ID is the
lowercase `<kind>:<source_id>` resource key. Its canonical `resource-cleanup-v1:` payload is the
version-1 binary sequence `int(1)`, uppercase resource kind, source ID, canonical unsigned-decimal
desired generation, signed profile ID, and signed expected-profile SHA-256, with every string
encoded by the Protocol length-prefixed UTF-8 primitive. It is attempted identity, not an applied
receipt: the same key MUST retain a `RESOURCE` component as the last trusted state.

A cleanup component exists only while the successor is `PAUSED` with no active condition epoch.
The first containment commit is `SAFETY_FAILURE` or `RECOVERY`; it preserves any pre-existing
same-key `RESOURCE` bytes exactly. Initial activation failure may instead first materialize that
key as `INACTIVE`. Pending cleanup entries and their trusted resources remain immutable across
ancillary paused commits. Cleanup success is one `RESOURCE_RESULT` commit that removes every
pending `RESOURCE_CLEANUP` and upserts every signed resource key as `INACTIVE`, using the reducer's
current desired generation. It cannot open admission, activate an epoch, or transition to
`ACTIVATING`, `RUNNING`, or a terminal state. A complete replay ending in `PAUSED`, `COMPLETED`, or
`WITHDRAWN` is publishable only when no cleanup entry remains and the complete signed resource
vector is inactive.

`successor_projection` has exactly `active_condition_epoch`, `clock_checkpoint`,
`evaluated_through_commit`, `lifetime_data_event_count`, `next_commit_sequence`,
`next_event_sequence`, `next_observation_sequence`, `retained_from_commit`, `revision`,
`source_checkpoints`, `state`, and `uploaded_through_commit`. `revision` equals the containing
commit sequence, `next_commit_sequence = revision + 1`, and the next event/observation sequences
cover the commit's complete ranges. `source_checkpoints` is an object keyed by source ID; each
value is exact `{coverage, cursor, next_producer_ordinal, resource_generation, source_id}` and the
key MUST equal the embedded source ID. A non-null `clock_checkpoint` is exact
`{active_running_elapsed_nanos, anchor, calendar_elapsed_nanos, deadline_utc_millis,
deadline_utc_trusted, zone_id}`. `zone_id` is the canonical IANA `ZoneId` authenticated at that
commit; a system time-zone change is represented by a durable clock-discontinuity reducer input,
never inferred from a timer selection. A non-null `active_condition_epoch` is exact
`{activated_at, applied_resource_vector_sha256, configuration_sha256, id}`.

`TIMER_SCHEDULED`, `TIMER_DUE`, and `TIMER_RETIRED` all repeat the timer's immutable clock-domain
target, not the time at which a worker happened to run. `CALENDAR_TIME` is encoded as
`ResearchTime(target_utc_millis, 0, "calendar-time")`; `ACTIVE_RUNNING_TIME` as
`ResearchTime(0, target_active_elapsed_nanos, "active-running-time")`; and
`SAME_BOOT_MONOTONIC` as the recorded logical wall deadline, target elapsed-realtime nanos, and
target boot-session ID. WorkManager carries only timer ID and generation; after waking, the runtime
resolves and verifies the durable timer target before producing `TIMER_DUE`.

The admission gate independently enforces the `STUDY_DEADLINE_TIMER` target as an exclusive
same-boot boundary: an observation at or after that elapsed-realtime nanosecond is rejected even
when WorkManager runs late. A due wake atomically retires the deadline and drives
`STUDY_COMPLETE_REQUESTED` / `STUDY_COMPLETED` with `STUDY_DURATION_ELAPSED`; no later collector
event is needed. If a wall-clock discontinuity crosses the deadline, the runtime closes admission,
discards all retrospective cursors and backlog, resets session condition state, closes the epoch,
and completes without a retrospective flush. A paused reboot may replace the timer only after a
trusted UTC re-anchor and records a quality gap; without trusted UTC it stays fail-closed `PAUSED`.

### Commit and observation digests

Both digests use a language-neutral binary preimage. `int` is four-byte big-endian, `long` is
eight-byte big-endian, `boolean` is one byte (`00` or `01`), and `string` is an `int` UTF-8 byte
length followed by those bytes. `nullable(x)` is a presence boolean then `x` when present;
`list(x)` is an `int` count followed by each entry. Enums are their uppercase names as strings.
Maps are sorted by key.

`commit_sha256` is SHA-256 over the following values, excluding `commit_sha256` itself:

```text
string("particeps-engine-commit-v1")
long(commit_sequence)
string(previous_commit_sha256)
string(input_kind)
nullable(string(consumed_pending_input_sha256))
list(source_observations, observation)
list(events, event)
list(mutations, mutation)
time(committed_at)
projection(successor_projection)
string(resulting_checkpoint_sha256)
```

The nested encodings are exact and ordered:

- `observation`: long observation sequence, string source ID, int schema version, long generation,
  string admission kind, long producer ordinal, string epoch UUID, int event count, nullable long
  first/last event sequences, nullable coverage, string encoded digest;
- `event`: long event sequence, string source ID, int schema version, string event type, time,
  nullable string epoch UUID, sorted fields count followed by each string key/value;
- `mutation`: string component kind, string component ID, string operation, nullable canonical value;
- `time`: long wall UTC millis, long elapsed-realtime nanos, string boot-session ID;
- `coverage`: string clock basis, string inclusive start, string exclusive end;
- `projection`: string state; long revision and next commit/observation/event sequences; sorted source
  checkpoint map count, each string map key then string embedded source ID, long generation, long
  next ordinal, nullable coverage and cursor; nullable clock checkpoint; nullable condition epoch;
  then long lifetime-data count, uploaded watermark, evaluated watermark, and retained floor;
- `clock checkpoint`: long calendar elapsed, long active-running elapsed, time anchor, long UTC
  deadline, boolean trust flag, then canonical IANA zone-ID string;
- `condition epoch`: string UUID, string configuration digest, string applied-resource-vector digest,
  then activation time.

`SourceObservation.encoded_sha256` independently binds the producer submission before sequence
assignment. It is SHA-256 over:

```text
string("particeps-source-observation-v1")
string(source_id)
int(schema_version)
long(resource_generation)
long(producer_ordinal)
string(condition_epoch_id)
boolean(coverage_present)
[coverage when present]
int(event_count)
for each event in order:
  string(event_type)
  long(wall_time_utc_millis)
  long(elapsed_realtime_nanos)
  string(boot_session_id)
  int(sorted_field_count)
  each string(field_name), string(field_value)
```

Generic condition epochs are opened and closed only by `study_condition.v1` activation and
deactivation events. Both event envelopes carry the same UUID as their `condition_epoch_id` field;
the deactivation event is the last event bound to the old epoch, not a null-epoch event. Epochs MUST
NOT overlap. Every collector event and SourceObservation belongs to the currently active epoch;
its projection ID, signed-configuration digest, and complete applied-resource-vector digest MUST
agree with the audit events. Missing, orphan, overlapping, or digest-divergent epochs fail closed.

For the Protocol v1 traffic actuator, the runtime maintains exactly one
`RESOURCE_AUDIT_TIMER` while `actuator:traffic-shaping.v1` is applied in an active epoch. Its
canonical timer value uses the `durable-timer-v1:` codec, a `SAME_BOOT_MONOTONIC` target exactly 60
seconds after its committed schedule observation, a matching wall deadline, no expiry, and the
producer key `resource-audit:actuator:traffic-shaping.v1`. Its lowercase SHA-256 identity is the
NUL-separated digest of, in order:

```text
"particeps-resource-audit-timer-v1"
configuration_sha256
"traffic_shaping.v1"
"ACTUATOR"
"traffic-shaping.v1"
resource_generation
profile_id
applied_profile_sha256
condition_epoch_id
causal_sequence
boot_session_id
target_elapsed_realtime_nanos
```

Activation ordering is `CONDITION_EPOCH_ACTIVATED`,
`TRAFFIC_SHAPING_PROFILE_APPLIED`, `TIMER_SCHEDULED`. A due commit orders `TIMER_DUE`, one
`TRAFFIC_SHAPING_SNAPSHOT` with reason `PERIODIC`, `TIMER_RETIRED` with reason `FIRED`, then the
successor `TIMER_SCHEDULED`. An epoch boundary orders the exact counter snapshot with reason
`EPOCH_BOUNDARY`, `TRAFFIC_SHAPING_PROFILE_REMOVED`, `TIMER_RETIRED`, then
`CONDITION_EPOCH_DEACTIVATED`. The snapshot and removal carry the same resource generation,
profile, VPN generation, counters, and epoch evidence. Recovery may retire a resource-audit timer
but MUST NOT recreate one or open an epoch; a participant must resume explicitly.

The reader verifies, in order: framing and bounds; HPKE; content AEAD; JCS bytes; repeated outer
identities; embedded configuration digest and Ed25519 signature; registry digest;
platform/client requirements; complete commit range; every commit digest and predecessor;
registry event contracts; observation range, digest, ordinal, generation, and coverage; mutation
and projection continuity; deterministic automation causality and checkpoint digest; and generic
condition epochs. It publishes no plaintext-derived record before every check succeeds.

## Automatic upload request

The receiver exposes one endpoint chosen at deployment. The request is `POST`; redirects are
forbidden. It has a fixed `Content-Length`, no `Transfer-Encoding`, and an immutable `PTCEXP01`
body staged before HTTP starts. The exact request headers are:

| Header | Value |
| --- | --- |
| `Content-Type` | `application/vnd.particeps.research-bundle` |
| `Content-Length` | canonical decimal body byte count, at most 33,554,432 |
| `Content-Digest` | RFC 9530 `sha-256=:<padded standard-base64 digest>:` |
| `X-Particeps-Bundle-Format` | `particeps-research-bundle-v1` |
| `X-Particeps-Bundle-Id` | lowercase bundle UUID |
| `X-Particeps-Commit-Count` | canonical positive decimal count |
| `X-Particeps-Commit-From` | canonical decimal exact first commit |
| `X-Particeps-Commit-To` | canonical decimal exact last commit |
| `X-Particeps-Configuration-SHA256` | 64 lowercase hex characters |
| `X-Particeps-Event-Count` | canonical decimal count |
| `X-Particeps-Researcher-Key-Id` | researcher key ID |

This vocabulary is the media type, the bundle format, and the eight routing header names. It is
the one part of Protocol v1 whose producer is in one language and whose only reader is in another.
It is therefore also in `conformance-vectors.json` as `valid.upload_request`, and both sides assert
against that fixture rather than against their own constants. Asserting against your own constant
proves only that you are self-consistent, which is exactly what a half-applied rename is.

The routing headers are untrusted claims. The receiver checks their syntax, internal range/count
arithmetic, body length/digest, and equality to the parseable outer bundle ID, configuration
digest, and researcher key ID. It cannot authenticate the encrypted participant or commit claims
and MUST NOT describe them as authenticated.

The body and every header are fixed for all attempts of one staged bundle. Clients disable
automatic redirects and transport-library request replay. Only I/O failure, 408, 425, 429, and 5xx
are retryable. `202 Accepted`, redirects, every other 4xx, malformed receipts, and receipt mismatch
are terminal delivery failures; they do not stop collection.

## Receiver write and receipt

The receiver streams the bounded body directly into a new R2 object whose key is the lowercase
bundle UUID. It verifies SHA-256 during the write, uses a create-only conditional write, and returns
success only after R2 durability is confirmed.

- New immutable object: `201 Created`.
- Existing object with identical byte count, content digest, configuration digest, key ID, and
  claimed range/count metadata: `200 OK`, with the original receipt bytes.
- Existing bundle ID with any mismatch: `409 Conflict`; it is never overwritten.

A success body is JCS JSON with `Content-Type: application/json` and exactly the following members
(shown expanded for readability; response bytes are compact JCS):

```json
{
  "bundle_id": "550e8400-e29b-41d4-a716-446655440000",
  "byte_count": "1234",
  "commit_count": "1",
  "configuration_sha256": "64 lowercase hex characters",
  "event_count": "1",
  "first_commit_sequence": "1",
  "last_commit_sequence": "1",
  "sha256": "64 lowercase hex characters"
}
```

Both `201 Created` and exact-replay `200 OK` return this same canonical eight-member receipt:
`bundle_id`, `byte_count`, `commit_count`, `configuration_sha256`, `event_count`,
`first_commit_sequence`, `last_commit_sequence`, and `sha256`. There are no other members.

Those two responses are also the only ones that may advance the client's uploaded-through
watermark, and only when every receipt value matches its durable outbox manifest exactly. `202
Accepted`, any other `2xx`, a redirect, `409 Conflict`, any other `4xx`, a malformed receipt, and a
receipt that mismatches the manifest never advance it. The watermark never moves backwards.

Receive time, researcher key ID, and claimed commit range may additionally be retained as untrusted R2
custom metadata; they are not added to the receipt JSON.

The receiver has no list, download, delete, administration, decryption, private-key, D1, Queue, KV,
Durable Object, dashboard, or runtime-configuration path. Deployment-time allowlists, WAF/rate
limits, R2 lifecycle rules, and minimal S3 read credentials are operational controls, not protocol
extensions.

## Conformance

Every implementation must consume the shared valid and hostile corpus in this directory. The
corpus must cover Unicode JCS ordering, integral bounds, raw-key encodings, signature input, HPKE
labels and wrong contexts, malformed lengths, wrong outer/inner identities, body tampering,
registry binding, complete commit ranges, commit/checkpoint/predecessor digests, event contracts,
SourceObservation ranges/digests/ordinals, generic condition epochs, and trailing bytes. It must
also cover the valid causal-first-manifest/barrier-event rotation, rejection of arbitrary range
permutations, rejection of flat-event documents, pre-v1 encodings, and the retired Android Data
Collector identity: the `ADCCFG01` and `ADCEXP01` magics, the `research-bundle-v1` bundle format,
and the `adc://join/v1` scheme. The two legacy classes are named separately because an
implementation can reject one while accepting the other. Absence of a vector is not permission to
accept an unspecified encoding.

The join-link corpus is consumed by Kotlin and TypeScript, the two implementations that create or
open join links. Python analysis has no join-link entrypoint and never parses a join link.
`tools/validate_protocol_vectors.py` does read the corpus, but only to check the fixtures
themselves: closed-world shape, corpus identity, digest and fingerprint spelling, ASCII, and the
4,096-byte limit. That is not an implementation of the join-link grammar, and it proves nothing
about the profile rules above.

Validate the checked-in sources with:

```sh
python3 tools/event_source_registry.py check
python3 -m unittest tools.tests.test_event_source_registry
python3 tools/validate_protocol_vectors.py
python3 tools/retired_identity_audit.py
```

The last of those is what keeps the retirement above from decaying into a convention. It searches
every tracked file for the retired spellings and fails on any that is not in its reviewed
allow-list. A hostile fixture therefore cannot quietly become a live constant, and a new one cannot
be added without saying in writing why it must carry an old name. It also pins the Android
`applicationId`, the single value that decides whether an install is the same application.
[CHANGELOG.md](../../CHANGELOG.md) records the moves of that value, and what each one asks of
someone who already installed a release.

After deliberately changing a wire rule, regenerate the deterministic corpus with
`node tools/generate_protocol_vectors.mjs` and make every language consumer pass the new bytes in
the same change.

## Implementation map

For the join path, Web authoring is in `web/src/lib/particeps/join.ts` and
`web/src/routes/researcher/JoinLinkPanel.svelte`; the shared parser is
`core/protocol/.../JoinLink.kt`; Android staging is
`app/.../platform/JoinArtifactDownloader.kt`; the `particeps://join/v1` intent enters through
`app/.../MainActivity.kt`; and digest → signature → fingerprint binding is enforced by
`core/study-application/.../StudyApplication.kt`. The adjacent tests and shared
`join-link-vectors.json` are the executable map. Automatic upload instead follows the outbox and
HTTP adapter named in the repository README; receiver and offline analysis each have their own
README code map.
