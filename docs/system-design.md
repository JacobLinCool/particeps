# System design

Particeps is an offline-first Android research runtime. A researcher authors and signs one bounded,
closed-world configuration; the participant imports and controls it; the device collects and
applies allowed study resources locally; encrypted bundles leave the phone only through participant
export or the signed upload policy. There is no remote command channel or downloaded executable
study code.

The normative wire contract is [Protocol v1](../protocol/v1/README.md). The sole typed
event/profile authority is the
[event-source registry](../protocol/v1/event-source-registry.json). This document explains how the
implementation realizes them.

Protocol v1 is pre-release and replaced destructively in place. Current configuration, storage,
bundle, receipt, scheduler, and event identities have no compatibility reader, migration, alias, or
fallback. Retired bytes fail closed.

## Design invariants

1. **Durable event first.** External observations become authenticated input before they can cause a
   resource change or one-shot action.
2. **One coordinator.** `:core:experiment-runtime` is the only component allowed to advance
   lifecycle, reducer state, timers, actions, resource receipts, epochs, and event admission.
3. **One transaction boundary.** Observation provenance, ordered events, state mutations,
   checkpoint, and successor projection commit in one `EngineCommit`.
4. **Side effects after commit.** Android/collector/native/action effects execute only from durable
   desired state. Results return as new coordinator inputs.
5. **Fail closed.** If required state cannot be proved, collector admission closes synchronously and
   the durable study becomes paused; the runtime does not label later data “possibly valid.”
6. **Typed closed world.** Sources, fields, predicates, clocks, access, privacy, and profile configs
   come from generated registry projections, never plugin self-description or arbitrary JSON logic.
7. **Participant authority.** Pause, complete, withdraw, and safety containment override signed
   automation. Automation cannot reopen admission or auto-resume a safety pause.
8. **Privacy by construction.** Packet, destination, DNS, installed-app inventory, and internal
   treatment state have no participant/export/log field unless the explicit signed/public contract
   defines one.

## Modules and dependency direction

```text
core:model
  ├── core:study-definition
  ├── core:collector-api
  └── core:resource-api
          └── core:automation
                  └── core:experiment-runtime
                          └── core:study-application

collector:* ──> core:collector-api
actuator:traffic-shaping ──> core:resource-api + native:traffic-shaping
app ──> study-application + Android adapters
core:export / core:storage consume core:model contracts
```

- `core:model`: event/clock/coverage, commit, lifecycle, timer/outbox/epoch/checkpoint DTOs and the
  `StudyStore` port.
- `core:study-definition`: signed configuration AST, exact codecs, automation syntax, and generated
  collector-profile types.
- `core:collector-api`: generated event contracts, batch/coverage sink, collector lifecycle,
  durable cursor, and boundary-flush contracts.
- `core:resource-api`: generation-bound prepare/suspend/flush/apply/verify/resume/release receipts
  for every stateful resource.
- `core:automation`: compiler, graph/liveness validator, pure reducer, deterministic IDs, standard
  timers, and device-local random-window producer.
- `core:experiment-runtime`: sole serialized coordinator and event writer.
- `core:study-application`: one-study application service and participant-safe projection.
- `core:storage`: Android Keystore-backed authenticated commit store and encrypted cache snapshots.
- `core:export`: commit-boundary HPKE/AES-GCM bundle writer and streaming verifier.
- `app`: Compose, Android consent/access, WorkManager wakeups, shared foreground host, upload/action
  adapters, and participant-safe notifications.
- `native:traffic-shaping`: pinned Go/gVisor forwarding and token buckets.
- `actuator:traffic-shaping`: Android `VpnService` plus proof and resource adapter.

Platform-independent modules contain no `android.*` references. `app` assembles adapters; it does
not reimplement domain state.

## Event-source registry and code generation

`protocol/v1/event-source-registry.json` discriminates `COLLECTOR` and `SYSTEM` sources. Each event
contract defines the identity tuple `(source_id, schema_version, event_type)`, exact fields and wire
types, allowed automation operators, occurrence clock, delivery/completeness, privacy, trigger
scope, encoded size, and rate bound. Collector sources additionally define access, implementation,
and exact profile configuration.

One generator emits:

- Kotlin runtime event contracts and typed collector-profile codecs;
- TypeScript/Python registry projections;
- registry/source SHA-256 constants;
- human documentation and conformance fixtures.

Collector descriptors reference the generated source contract. They do not declare another event
name list. Runtime validates every batch before admission; export and Python analysis validate the
same identity/field contract independently.

System sources have `disclosure_key = null` and never become participant Data/Access cards. Actuator
capability disclosure is defined by the feature’s signed/public contract rather than generated from
audit events.

## Signed configuration and compiler

Collector declarations are resources with sorted unique named profiles. One-shot interventions
contain notification/survey action text only. The required `automations[]` contains occurrence or
resource-binding definitions. `traffic_shaping` is exactly `{}` or its complete target/profile
object.

The automation compiler resolves registry identities and rejects:

- illegal source/event/field/operator/clock combinations;
- absent required trigger sources or retrospective latency contradictions;
- multiple binding owners, dependency cycles, self-disabling sources, or unreachable wakeup paths;
- unbounded sequences/windows or state above the fixed entry/node/depth/case limits;
- audit/output feedback events;
- noncanonical predicate values, illegal float/integer operations, and impossible schedules.

The reducer operates only on immutable compiled data, checkpoint, and ordered inputs. It reads no
clock, storage, Android API, UUID generator, or CSPRNG. Its output is desired resources, timer
intents, action requests, audit facts, and a canonical successor checkpoint. Kotlin, TypeScript,
and Python compare its digest after each corpus input.

## Observation admission

Collectors receive an `EventSink` that accepts one `SourceEventBatch` of 1–4,096 events from the
same source/schema/resource generation and optional coverage. Polling collectors can emit a
zero-event `CoverageAdvance`. `latestEvent()` and collector-to-collector subscriptions do not
exist.

The sink validates:

1. the runtime is `RUNNING` and the epoch gate is open;
2. source/profile generation equals the applied resource receipt;
3. producer ordinal and durable cursor follow the source checkpoint;
4. every event identity/field/size satisfies the generated contract;
5. coverage is monotonic and belongs to the accepted clock domain;
6. the batch carries the gate’s current condition epoch token.

The coordinator provisionally reduces the complete observation. If desired resources do not
change, it creates one ordinary `EngineCommit`. If they might change, it durably stages the bounded
causal input and enters the global resource barrier.

An in-memory `Flow` publishes participant-safe state after commit for UI refresh. It is never
recovery truth.

## Authenticated `EngineCommit` storage

Each encrypted frame contains:

- one source-observation manifest set and its contiguous events;
- generated system events;
- typed component mutations;
- committed time and input kind;
- successor runtime projection;
- reducer/checkpoint digest;
- previous/current commit digests and authenticated footer.

A commit never crosses a segment. A torn uncommitted final tail is truncated; corruption anywhere
else makes open/read/export fail closed. Export, upload, and eviction boundaries align to complete commits.
Reclamation is bounded by `min(uploaded_through_commit, evaluated_through_commit)` so neither
delivery nor reducer recovery can lose required input.

The one encrypted pending-input slot is bounded to a valid observation batch. Its digest is named by
the commit that consumes it. A crash during barrier containment preserves the cause; recovery
commits it with a quality gap and safety pause rather than discarding or applying an unverified new
resource state.

### Snapshots and cold start

Periodic encrypted snapshots contain the scalar projection and typed component map at one verified
commit digest. They are caches, never provenance. Opening authenticates the newest usable snapshot,
authenticates the complete retained chain from `retained_from_commit` through the named snapshot
footer, and reduces only complete frames after that revision. Missing prefixes are permitted only
strictly below the durable retained floor. A missing retained segment, interior corruption, or a
snapshot that does not name the retained chain's exact boundary fails closed. There is no weaker
metadata reconstruction path.

Cold start never materializes the log: retained frames are authenticated sequentially with at most
one decrypted commit in memory. Export/upload range reads use the same bounded streaming scan and
stop at the requested complete-commit upper boundary instead of decrypting the later suffix.

Old event-segment/metadata layouts are detected and rejected. The app uses its existing generic
recovery/reset surface and never deletes or uploads them automatically.

## Lifecycle

Internal durable states are `IMPORTED`, `CONFIG_VERIFIED`, `CONSENT_PENDING`, `ACCESS_SETUP`,
`READY`, `ACTIVATING`, `RUNNING`, `PAUSING`, `PAUSED`, `COMPLETED`, and `WITHDRAWN`. Ordered
`study_runtime.v1` events are the public lifecycle history; there is no parallel transitions array.
`STUDY_STARTED`, `STUDY_RESUMED`, and `STUDY_RUNNING` are audit-only outputs of that state machine,
not event-trigger inputs. The compiler rejects them in event matches, sequences, and windows;
continuous resource bindings use `study_session_active` instead.

### Start and Resume

1. Verify consent/access and record `STUDY_STARTED` or `STUDY_RESUMED` into `ACTIVATING`.
2. Reduce `study_session_active` and select every desired resource profile.
3. Prepare/apply/verify required resources in key order; stale receipts are rejected by generation.
4. Build the complete applied-resource vector and digest.
5. Commit `CONDITION_EPOCH_ACTIVATED`, resource audit, receipts, and `STUDY_RUNNING`.
6. Open the admission gate immediately before resuming already verified resources.
7. Enter `RUNNING` and notify UI/work adapters.

An activation timeout/failure returns to a fail-closed paused boundary. It never opens admission
with a partial required vector.

### Pause, completion, and withdrawal

1. Close admission synchronously and enter `PAUSING` through the requested lifecycle event.
2. Suspend resources; capture one `ResearchTime` boundary.
3. Flush retrospective collectors through that boundary and persist their cursors/coverage.
4. Commit final resource audit, epoch deactivation, and desired inactivity.
5. Release resources and foreground work.
6. Commit the completed lifecycle state.

Pause does not advance active-running time. Polling sources do not query/backfill the paused or
unverified interval.

Process death/reboot with durable `ACTIVATING`, `RUNNING`, or `PAUSING` becomes `PAUSED` on recovery.
The runtime records a quality gap, closes any epoch, and requires explicit participant Resume.
If the persisted state was already `PAUSED`, a new boot still requires an explicit quality-gap
commit and trusted UTC re-anchor before Resume. That commit removes every retrospective source
cursor and replaces the same-boot deadline generation; it never queries the reboot interval.
Without trustworthy UTC the study stays `PAUSED`, while Complete and Withdraw remain available
because they do not open admission.

TIME_SET and TIMEZONE_CHANGE are durable discard barriers rather than ordinary timer wakeups. The
runtime closes admission, suspends resources, resets latch/presence/window/sequence state, removes
all retrospective cursors without a flush, restarts active retrospective resource generations,
and rotates the condition epoch only after the new vector verifies. If the discontinuity crosses
the signed duration, it completes the study instead of opening a replacement epoch.

## Global resource barrier

For a resource-vector change during `RUNNING`:

1. Stage the complete causal batch in the encrypted pending slot.
2. Begin gate drain and suspend all applied resources in sorted resource-key order.
3. Capture the common boundary and flush retrospective sources in source-ID order.
4. Keep `SourceObservation` manifests in admitted producer order (causal, pre-drain, exact flush),
   while sequencing their event ranges and reducer inputs as pre-drain/flush then causal; commit
   those inputs, reducer/audit changes, final resource counters, and old epoch deactivation.
5. Close the drain token and apply only the newest desired generation.
6. Verify every required receipt and reconstruct the canonical full vector.
7. Commit resource applied audit, new epoch, vector digest, and new resource components.
8. Open the new epoch gate and resume resources.

No unbounded apply queue exists. A newer desired generation supersedes an older unexecuted one.
Unchanged resources remain applied and do not reset native state merely because another resource
caused an epoch rotation.

The terminal callback contract is deliberately narrow: close admission synchronously and wake the
coordinator, then return. A collector/actuator cannot append its own system event, transition state,
or call the resource recursively.

## Timers and actions

Timer state stores one stable clock-domain target: calendar UTC, accumulated active-running
elapsed, or same-boot monotonic. `TIMER_SCHEDULED` commits before WorkManager is asked to wake.
WorkManager carries only timer ID and generation and calls `onTimerDue`; the runtime resolves the
authenticated target from its durable timer component and never accepts a deadline from worker
input or rebuilds a schedule from configuration. Timer audit events use the same immutable
clock-domain coordinate for schedule, due, and retirement: calendar targets are
`{wall_time_utc_millis = target UTC, elapsed_realtime_nanos = 0, boot_session_id =
"calendar-time"}`; active-running targets are `{wall_time_utc_millis = 0,
elapsed_realtime_nanos = target active elapsed, boot_session_id = "active-running-time"}`; and
same-boot targets carry the recorded wall deadline, target elapsed-realtime nanos, and boot-session
ID. Pause/resume may re-arm a wakeup estimate but cannot change this committed logical target.

The signed duration is represented by exactly one runtime-owned `STUDY_DEADLINE_TIMER` component
for every started nonterminal study with time remaining. Its same-boot target is also the admission
gate's exclusive upper bound: collector observations at or after that nanosecond are rejected even
when WorkManager is late. A verified due wake retires the component and drives automatic
`STUDY_DURATION_ELAPSED` completion, so expiration cannot depend on a later collector event.

Random-window CSPRNG selection is a coordinator input. The current proven selection algorithm and
constraints remain device-local; the selected instant is committed before scheduling. Replay
validates recorded eligibility/uniqueness but never redraws.

One-shot action ID derives from configuration digest, automation ID, and causal sequence/deadline.
The outbox commits the request and successor state before Android notification/survey work. A claim,
retry, success, or failure uses the same ID. Internal invocation is idempotent; the system does not
claim arbitrary external notification effects are exactly-once.

Only `RUNNING` studies may claim or display an invocation. Pause and every terminal transition
serialize visible-notification retraction, then issue non-blocking idempotent cancellation of the
delivery/expiry work while retaining the durable outbox component. Resume re-arms pending actions
from that component. Availability is a half-open interval: `now >= expires_at_utc_millis` is
expired. A survey reaches expiry through one runtime transition that emits `SURVEY_EXPIRED` followed
by `ACTION_FAILED(EXPIRED)`, even when it expires while paused and is discovered on resume.

Android workers report neutral delivery or reconciliation failure. The runtime alone reads signed
intervention requiredness: optional failure remains neutral, while required failure first commits
`ACTION_FAILED(REQUIRED_ACTION_FAILED)` and then safety-pauses with
`WORK_SCHEDULING_FAILURE`. The display/retraction lease never encloses runtime result reporting, so
a fail-closed transition does not await cancellation of its own worker.

System audit/output events are not reducer trigger inputs, preventing feedback loops.

## Collectors as resources

`CollectorResourceActuator` adapts the collector lifecycle to the same resource API as actuators.
Profile changes call exact stop/flush/start boundaries rather than mutating a running collector
behind the runtime’s evidence.

Retrospective collectors implement `flushThrough(boundary, cursor)`:

- `network_usage.v1` splits device-wide accounting coverage at the boundary;
- `usage_events.v1` advances an exact query cursor and emits batches ordered by source time;
- an empty result advances coverage without an event.

`usage_events.v1` profiles use seconds. When referenced by automation, the compiler requires 15
seconds. Activity lifecycle events carry a study-scoped HMAC token derived from the activity
component; the class name is not persisted. Delayed entry+exit batches update historical state but
cannot apply a no-longer-current presence resource profile.

A reference-counted foreground-host decorator acquires the acknowledged neutral Android research
service before the first continuous collector starts and releases it after the last collector
stops. Foreground hosting is process containment, not a signed resource and not part of the applied
research vector.

## Traffic-shaping resource

### Android service

`TrafficShapingVpnService` is exported false, requires `BIND_VPN_SERVICE`, declares the VPN intent
filter and `systemExempted` foreground-service type, opts out of always-on, and never calls
`allowBypass()`. It establishes IPv4/IPv6 all routes, MTU 1500, fixed private TUN addresses, no
public DNS override, and inherits underlying metered state.

Only 1–64 signed packages are added with `addAllowedApplication`; Particeps itself and unselected
apps remain on the ordinary network. Package validation uses `QUERY_ALL_PACKAGES` solely for the
signed names and shared-UID peers. Package add/remove/replace revalidates the complete snapshot; no
inventory is persisted or exported.

Android 17 local-network permission is required before forwarding selected apps’ LAN connections.
Refusal/revocation is a terminal resource failure. The app does not scan/discover local devices.

### Ownership proof

Activation succeeds within ten seconds only when all are true:

1. a generation-scoped `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` request observes VPN networks
   using cleared capabilities, `TRANSPORT_VPN`, and other-UID networks;
2. a fresh post-establish `Network` absent from the baseline has `ownerUid == Process.myUid()` and
   remains in the generation’s owned set;
3. the detached TUN remains open in native ownership;
4. native forwarder/limiter health is positive;
5. protected outbound socket creation has not failed;
6. installed package/UID evidence is unchanged;
7. the native applied profile reconstructed by Kotlin hashes to the signed expected digest.

`VpnService.prepare() == null`, default-network VPN transport, a non-null TUN, or always-on state is
insufficient alone. Android redacts other VPN ownership, so Particeps reports only ours/not ours.

`onRevoke`, unexpected `VpnService.onDestroy`, loss of all owned networks, TUN I/O/EOF, native
terminal failure, profile mismatch, package identity change, permission loss, protect failure, or
timeout synchronously closes admission and wakes fail-closed safety pause. Intentional actuator
release is linearized before service cleanup, so its subsequent `onDestroy` is not misreported as a
failure; unexpected destruction delivers the terminal callback before clearing TUN/native evidence.

### Native forwarder and limiter

The module pins Go 1.26.3, NDK 30.0.14904198,
`github.com/xjasonlyu/tun2socks/v2 v2.7.0`, and the exact `golang.org/x/mobile` pseudo-version and
sums in the build. CI uses the Go proxy and checksum database with no direct fallback. gomobile
builds four ABIs into the Gradle build directory; no AAR/`.so` is committed.

Particeps composes the unmodified tun2socks/gVisor stack with its own thin direct proxy and TUN
wrapper. Every TCP/UDP socket synchronously calls `VpnService.protect(fd)` before use. The detached
TUN FD transfers exactly once to native and closes exactly once on every success/failure path.
Upstream logging is disabled before network activity; raw tunnel errors, source/destination, DNS,
and payload never enter logs/events.

TUN read/write defines aggregate Layer-3 accounting, shaping, and the resource-barrier boundary.
The two shared directional buckets consume credit before the packet crosses that boundary, so the
traffic reported by audit counters is exactly the traffic subject to the cap. `1 kbps` is 1,000
aggregate Layer-3 bits per second, including IP/transport headers and retransmitted packets seen at
the TUN. Capacity is `max(MTU, floor(rate_bytes_per_second × 2 s))`; this absorbs timer jitter while
a fresh saturated 60-second interval remains below the protocol's 105% upper bound. A profile
change atomically resets both buckets and fractional credit and wakes waiters to recalculate. The
synchronous TUN call may hold one packet while waiting, but Particeps adds no packet queue. The
direct proxy remains limited to opening and protecting raw sockets; the upstream tun2socks/gVisor
stack is not modified or tuned. Unlimited directions still traverse the same VPN path.

Native exposes generation-bound profile receipt/health and saturating 64-bit aggregate
bytes/packets/throttled-interval counters. Runtime writes applied/removed audit and 60-second plus
epoch-boundary snapshots. Counter overflow is terminal, never wraparound.

## Condition epochs and analysis provenance

`study_condition.v1` is the only generic epoch truth. An epoch binds configuration SHA-256, UUID,
activation time, complete canonical applied-resource vector, and its SHA-256. Collector observations
must carry the active UUID. System resource audit carries the same UUID in its typed fields.

Traffic audit does not own a parallel epoch. `traffic_shaping.v1` binds profile/VPN/resource
generation, package-list digest, caps, native digest, and counters to the generic epoch.

Kotlin export verification and Python analysis check activation/deactivation order, no overlap,
event/observation attribution, vector/profile receipts, source coverage, reducer causality, and
checkpoint digests. A divergence rejects publication rather than inferring assignment.

## Android participant boundary

Compose receives `ParticipantStudyUiModel`, a whitelist projection. It contains high-level study
identity/consent, profile-independent data categories, ordinary access status, participant controls,
safe state/count/time/export summaries, and one shaping-disclosure flag. It cannot carry target
packages, resource profiles, caps, automation, timers, epochs, digests, owner UID, health, or typed
failure reasons. Reflection/accessibility snapshot tests enforce this boundary.

The existing five setup steps and normal running screen remain. Shaping adds only the fixed inline
paragraph next to the existing Access completion control; Done/Resume sequences Android 17 local
network permission and system VPN consent. No VPN card/screen/status/history or second ongoing
notification is added. `CollectionService` and the VPN service share one neutral notification
identity while either foreground service remains active.

Particeps-generated/derived UI never reveals treatment control. Researcher-authored study title,
purpose, researcher name/contact, consent, notification, and survey strings remain verbatim. These
signed free-text fields are the explicit exception to the generated-UI blinding boundary; Web
requires a blinding/ethics acknowledgement before signing because Android runtime cannot
semantically police them.

Release logs and participant messages are generic. Debug builds may retain bounded non-sensitive
diagnostics for development, never packet/destination/DNS or collected payload values.

## Export, upload, receiver, and analysis

`core:export` captures one `RuntimeDocument` boundary and streams only complete retained commits to
canonical JSON, encrypts with a fresh AES-256-GCM content key/nonce, and wraps the key with fixed
RFC 9180 HPKE. The decrypted document repeats configuration/signature/registry digest and full
commit data. Its verifier publishes nothing before all framing, AEAD, JCS, signature, registry,
chain, observation, mutation, checkpoint, epoch, and range checks pass.

Automatic upload stages immutable ciphertext before HTTP. Headers and receipts name complete commit
ranges and aggregate event count; participant identity stays encrypted. Exact replay reuses bytes.
The receiver validates bounds/digest/identity/range and stores ciphertext atomically without keys.

Python inventory copies ciphertext into a content-addressed workspace. Materialization verifies each
bundle, reassembles the complete chain, runs an independent current Protocol v1 reducer, and only
then writes typed Parquet partitioned by event identity with epoch provenance. One invalid bundle
prevents publishing that dataset.

## Build and release gates

CI runs registry generation/checks; Kotlin/TypeScript/Python conformance; JVM, Compose,
instrumentation, Web, analysis, receiver, and native tests; and release verification.

Blocking Android lanes are API 34 x86_64 and API 37 `google_apis_ps16k` x86_64 revision 5 or newer,
with the latter asserting a 16 KiB page size. Host orchestration is used for process kill, reboot,
competing VPN, permission/package change, and multi-APK cases that cannot stay inside one
instrumentation process.

The release verifier requires:

- exact four native ABIs and 16 KiB ELF alignment;
- VPN service/foreground/always-on flags and declared permissions;
- Go/tool/module checksum provenance with no tracked native artifact;
- event-registry digest asset;
- complete statically linked dependency SBOM and licenses;
- no sensitive packet/destination/DNS logging.

Google Play VpnService and all-packages declarations are distribution gates outside the repository’s
actual store-submission scope. GitHub branch protection/rulesets are likewise operational policy,
not changed by this implementation.
