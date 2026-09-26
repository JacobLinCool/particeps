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
causal input and enters the global resource barrier. A live batch, one without coverage, that would
change a desired resource only after its first event is recorded only up to that event, as an
ordinary commit: `EmitBatchResult.Accepted.recordedEvents` counts the leading events it holds, and
the collector offers the rest under the next producer ordinal. The staged causal observation
therefore starts with the event that changes a resource, as it did when each callback was offered
alone. A batch with coverage is one retrospective claim and is always handled whole.

`SerializedCallbackCollector` submits a callback source's queue in capture order. When its single
consumer wakes, it merges the callbacks that are already queued into one batch. Unless the collector
passes a commit window (see [Commit windows for continuous
sensors](#commit-windows-for-continuous-sensors)), it never waits for another callback, so merging
adds no timer, wake lock, or delivery latency. A batch ends at a barrier or stop message (a barrier
completes only after every earlier event has been handled), at an admission token that is not equal
to the batch's, at an observed-time regression or boot-session change, and at min(4,096, the
registry's per-batch rate bound, ⌊1 MiB ÷ the source's largest `maximum_encoded_event_bytes`⌋)
events; an oversized single callback splits the same way. `captureAll` queues one platform delivery,
such as a batched `LocationResult`, as one message under one token, and each fix keeps its own
observed time. Normal tokens of one gate generation compare equal, because the gate classifies a
normal token only by its owner, generation, and kind; the drain's barrier-flush token keeps identity
equality.

The sink admits or refuses each offer as one observation. Admission under one token bounds observed
time from above and narrows over time, with one exception at the study deadline: once it has passed,
an open epoch refuses every batch, but a drain that begins afterwards, such as the deadline stop's,
admits the events observed at or before its boundary and before the deadline. A refused batch
ordered by observed time can therefore have an admitted prefix. The collector halves an offer the
gate refuses, or one that violates the event contract, until a prefix is accepted, advances the
producer ordinal only on acceptance, and after every acceptance offers the whole remainder again, so
a drain that begins during the search still admits what it covers. Each part accepted this way holds
at least half of what is still admissible, so a batch takes O(log n) accepted observations, which
bounds the pending-slot rewrites of a barrier drain, and O(log² n) offers. A refused one-event offer
ends the batch. After a gate refusal, that event and every later event of the batch are dropped; a
windowed collector first holds them for one more offer, as described below. A
contract violation fails the collector at the offending event: the valid events before it are
recorded, as they were when each callback was offered alone, and the later events of the batch are
dropped. A storage failure or quality gap fails the collector at once. Parts of the batch accepted
before a failure stay recorded.

Merging stays within Protocol v1 but changes the granularity of resource reconciliation. The
reducer consumes every merged event in capture order but reconciles desired resources once per
observation. A condition that sets and resets inside one merged observation therefore neither
changes a resource nor rotates the epoch. Callbacks merged ahead of a trigger commit before it is
staged, exactly as when each was submitted alone. The trigger and the callbacks merged behind it
form the causal observation, which the barrier reduces after its pre-drain input. Per-callback
submission instead staged the trigger alone and reduced the callbacks queued behind it first, as
pre-drain input, so a reset queued behind its trigger was reduced before the trigger and the
resource changed anyway. A callback queued only after its trigger was submitted still takes that
pre-drain path. Recorded events, their observed times, and their condition-epoch attribution are
unchanged; inside a merged causal observation, event sequence numbers follow capture order.

### Commit windows for continuous sensors

A collector may also pass a `CallbackCommitWindow`, at most `MAXIMUM_CALLBACK_COMMIT_WINDOW`
(5 s). Only `gyroscope.v1` and `accelerometer.v1` do, with 5 s. Every other callback source,
including ambient light, proximity, screen, network, keyboard, location, app lifecycle, and battery,
passes none and keeps the merge-only consumer above.

The window applies only when `CollectorContext.referencedByAutomation` is false.
`EventDrivenRuntimeAssemblyFactory` sets that flag for each collector from the compiled program:
`CompiledAutomationProgram.referencesSource` is true when any matcher names an event of the source.
A matcher can be an event-match or sequence trigger, a window selector, or an event-latch or
keyed-presence condition, in a trigger, a guard, or a resource-binding case. The compiler records
the event of every matcher it validates (`AutomationCompiler.kt:382`), and a program exists only
when all of them validate. The compiler does not forbid references to the two sensors. Their events
are `RESEARCHER`-scoped with `event_match`, `sequence_step`, `window_count`, and `window_sum`.
Sequences and windows over them are rejected as `UNBOUNDED_SOURCE` (`AutomationCompiler.kt:482`),
because their `PLATFORM_ONLY` rate gets no enforced bound (`GeneratedEventContractRegistry.kt:109`).
Event-match triggers and event latches over them compile. Such a source must then be required and
continuously active (`AutomationCompiler.kt:582`, `:586`), and its collector commits every sample
without a window. The flag is process-local assembly state; it is never signed, stored, or exported.

The factory also sets the flag for every collector when
`CompiledAutomationProgram.retainsEventTimeOrderedState` is true: the program has a sequence or
window-threshold trigger, or a window-threshold condition anywhere in a trigger, a guard, or a
resource-binding case. Sequence partials and window entries retain the time of the event that made
them, and the reducer requires every later event, from any source and whether or not a matcher
names it, to be no older than the newest one (`AutomationReducer.kt:880`, `:932`). A windowed
sample commits up to one window after events that other sources observed later and committed at
once, so it would fail that check. The reducer would throw, the collector would fail with
`STORAGE_WRITE_FAILED` and lose the batch, and its terminal failure would safety-pause the study; a
barrier that drained such a batch would fail closed the same way. So a study with any sequence or
window commits every gyroscope and accelerometer sample without a window, as before. The reducer
check itself is unchanged.

With a window, the consumer keeps a batch open after taking its first callback, until 5 s of the
consumer dispatcher's monotonic time have elapsed since that callback was captured. It then offers
the batch, together with whatever is already queued. A barrier or stop message offers the open batch
at once and is then handled as before, so pause, completion, withdrawal, resource barriers, and the
deadline stop never wait for a window. Every merge rule still ends a batch at once: an unequal
admission token, an observed-time regression or boot change, and the per-source event bound (512
for both sensors, ⌊1 MiB ÷ 2,048 bytes⌋). The callback that ends a batch opens the next one, whose
window starts at that callback's capture. The window is a `select` with `onTimeout` on the consumer
dispatcher; it adds no WorkManager work, alarm, `Handler` callback, or wake lock. On Android that
dispatcher measures awake monotonic time. If the CPU suspends while a batch is open, which the
accelerometer allows because it holds no wake lock, the window pauses with it. The batch then
commits after the next wake, once the rest of the window has elapsed, or earlier at a barrier, stop,
or merge rule. The gyroscope keeps its partial wake lock, so its window elapses in real time.

Samples keep their capture-time observed time and the admission token captured in their callback.
The window therefore changes when a sample commits, never what it records or which epoch it
belongs to: samples captured before a drain are admitted as pre-drain input of the old epoch, and
none is admitted after a force-close. When the gate refuses a windowed batch, the consumer holds the
refused events, together with same-token callbacks that were already queued. It offers them once
more before its next barrier, stop, or callback under another token, and a refusal of that final
offer drops them. For example, after the study deadline an open epoch refuses every batch until the
deadline stop's drain begins. A window that closes in that interval is held and drained by the
deadline stop, which admits what was observed before the deadline, as it would have admitted the
same samples committed one at a time. Once the gate refuses a token it issues no equal token again,
so a held batch is at most one batch plus the queue. Anything more fails the collector with
`CALLBACK_QUEUE_FULL`. While a batch is open the consumer keeps moving callbacks from the queue into
it. The 2,048-message queue therefore still fails with `CALLBACK_QUEUE_FULL` only when the consumer
falls that far behind, for example behind a slow commit, and memory stays bounded by one open batch
of at most 512 events plus the queue.

Until its batch is accepted, a windowed sample exists only in process memory. Acceptance still
means a durable commit or a durable pending-slot write. Process death therefore loses the samples
captured since the source's last recorded event: at most one window (5 s of awake time, longer
across a CPU suspend for the accelerometer), plus a batch whose offer was in progress. Death from
durable `ACTIVATING`, `RUNNING`, or `PAUSING` makes the next initialization commit one `RECOVERY`
commit with `SOURCE_QUALITY_GAP` (`PROCESS_RECOVERY`) that also closes the epoch the samples
belonged to, so the loss is never silent. That event carries only its own time. For a windowed
source, the interval it marks begins after that source's last recorded event. When the chain's last
commit came from another source, a timer, or a command, that event can precede the commit by up to
5 s of awake time. For the accelerometer, whose window pauses while the CPU is suspended, it can
precede the commit by that much awake time plus every suspend in between, which can be hours.
Analyses must treat a windowed source as unobserved from its last recorded event, not from the
previous commit, and must not bound that interval by 5 s.

Three admission closures drop an open window instead of committing it. Each closes admission
before it suspends collectors, so the samples in the window are refused and dropped, as queued
callbacks always were, and no `SOURCE_QUALITY_GAP` names the sensor:

- A safety pause, including one for storage or required-resource failure. Up to 5 s of samples
  before `STUDY_SAFETY_PAUSE_REQUESTED` can be missing.
- A running wall-clock discontinuity (TIME_SET or TIMEZONE_CHANGE), which is a discard barrier
  (below). Up to 5 s of samples captured before the change can be missing, although the study stays
  `RUNNING` in a new epoch. The only record is `SOURCE_QUALITY_GAP` with `source_id` `timer.v1`
  and `WALL_CLOCK_CHANGED`. Analyses must treat each windowed source as unobserved from its last
  recorded event before that gap to the new epoch's activation.
- A wall-clock discontinuity first observed after the signed deadline, which completes the study
  from durable input without a drain. The samples captured before the deadline in an open window
  are dropped, where the deadline stop's drain would have admitted them.

Before the window, each of these lost only callbacks still queued or in flight. The discard barrier
does not drain live sources: a drain would admit these samples to the old epoch, but it would add
pre-drain observations to the discard commit. Cancelling a collector's scope drops its open batch
as process death does; the application scope ends only with the process.

Excluding referenced sources is exact for event matching, but a windowed source's events remain
reducer inputs reduced at their commit's clock. Each commit therefore also evaluates time-based
conditions such as `study_local_window`, `held_for`, and `elapsed_at_least`. Their durable timers
remain the authority. When a WorkManager wake is late, the first commit after a
boundary surfaces the transition instead, and a windowed source's commits surface it up to one
window later than per-sample commits would. The old epoch, including samples of this source
captured after the boundary, then lasts until that commit, another source's commit, or the late
timer, whichever comes first.

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

Before writing a frame, the store verifies the commit digest and requires the runtime document it is
handed to equal the previous document advanced by the commit's mutations and successor projection.
It compares field by field and component by component rather than building that successor a second
time. Each derived digest or encoding of an immutable value is computed once per value: a commit or
pending input sealed by `withComputedDigest` keeps the digest it was sealed with, so the store's
verification of that same value is a comparison, while any other value, such as a decoded frame or
an edited copy, is hashed in full. A reducer checkpoint builds its digest preimage and its component
encoding once, and each durable timer keeps its preimage component while it is carried unchanged.
The compiled automation program resolves its static configuration once: state keys, condition-timer
identities, local times, predicate literals, the study zone, and each study-local window's dates
for the current study start. `CommitDerivationGuardTest` runs a multi-commit study and checks every
commit, pending input, checkpoint, and successor against a plain re-derivation of the same bytes.

A commit never crosses a segment. A torn uncommitted final tail is truncated; corruption anywhere
else makes open/read/export fail closed. Export, upload, and eviction boundaries align to complete commits.
Reclamation is bounded by `min(uploaded_through_commit, evaluated_through_commit)` so neither
delivery nor reducer recovery can lose required input.

The one encrypted pending-input slot is bounded to a valid observation batch. Its digest is named by
the commit that consumes it. A crash during barrier containment preserves the cause; recovery
commits it with a quality gap and safety pause rather than discarding or applying an unverified new
resource state.

### Snapshots and cold start

Runtime observers consume one immutable committed-revision projection, including study clock and
participant identity fields; ordinary UI updates never reopen storage or replay the historical log.
The application serializes projection updates against session replacement. Admission contenders
suspend on mutex acquisition or the gate's drain signal, rather than polling a lock every millisecond.

Commit frames retain synchronous durable acknowledgement. Snapshot checkpoints follow a budget of
64 acknowledged commits or 1 MiB of appended frames, with additional lifecycle, safety, recovery,
epoch and pending-input boundaries. A failed required checkpoint remains due until acknowledged.
Quota accounting reads validated file sizes, not encrypted candidate contents. These policies add
no timer wakeups and do not alter observation frequency or commit durability.

Periodic encrypted snapshots contain the scalar projection and typed component map at one verified
commit digest. They are caches, never provenance. Opening authenticates the newest usable snapshot,
authenticates the complete retained chain from `retained_from_commit` through the named snapshot
footer, and reduces only complete frames after that revision. Missing prefixes are permitted only
strictly below the durable retained floor. A missing retained segment, interior corruption, or a
snapshot that does not name the retained chain's exact boundary fails closed. There is no weaker
metadata reconstruction path.

Cold start never materializes the log: retained frames are authenticated sequentially with at most
one decrypted commit in memory. `StudyStore.loadRuntime` hands each authenticated retained commit to
the caller during that same pass. The runtime keeps only the commit that entered the current state,
as its committed wall time (the participant's pause or end time) and the study calendar time in
its successor clock checkpoint (a finished study's length). Later same-state commits (upload
acknowledgements, paused re-anchors, action results) move the clock anchor and keep advancing
calendar time, but change neither value. Both live in process memory, are re-derived on every cold
start, and stay unknown when the transition lies below the retained floor. Live export/upload uses `StudyStore.withReadSnapshot` to capture
acknowledged runtime and fixed encrypted segment lengths without running recovery again. The scoped
snapshot pins retained files, opens one segment at a time, and checks cancellation between frames.
Its consumer runs outside the store mutex, so appends and participant lifecycle commands can proceed
while a destination is slow. Reclamation waits for the snapshot to close; the existing storage quota
still applies while files are pinned. Reads stop at the requested boundary or when the consumer
declines the next commit. Locating a range still scans earlier frame headers in retained segments.

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
the signed duration, it completes the study instead of opening a replacement epoch. Because
admission closes before suspension, a live source's callbacks that have not yet committed are
refused. For the gyroscope and accelerometer that includes an open commit window, up to 5 s of
samples; see [Commit windows for continuous sensors](#commit-windows-for-continuous-sensors).

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
A commit records the net change of the reducer's timer map rather than every timer intent: each
prior timer the reduction removed or replaced gets one `TIMER_RETIRED` with its own generation, each
resulting timer that is new or replaced gets one `TIMER_SCHEDULED`, ordered by timer ID with the
retirement first, and only those changes reach WorkManager. A multi-input reduction, such as a
merged callback batch that slides a window once per event or a barrier's combined input, therefore
never records or wakes a generation that it armed and replaced within itself. For a single input
the net change is exactly the reducer's intents, and Python replay verifies the same rule.
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
failure reasons. A reflection test pins the field names of the participant projection types and
every type reachable from them. A Compose semantics sentinel test renders a configuration fixture
carrying sentinel values in every non-displayed field through the real projection and checks that
neither the running screen nor *Study and my data* exposes them; notification sentinel tests cover
the shared foreground notification and the lock-screen public version of intervention notifications.

Participant disclosure is a researcher decision with blinding as the default. The platform floor
(study identity, purpose, contact, consent, data categories and what each records, access, upload
terms, the fixed intervention-existence disclosure, and participant rights) is always available.
Intervention targets, timing, strength, assignment, self-view charts, and debrief are reserved for a
future signed participant disclosure policy that must be explicit, cannot go below the floor, and
pairs every hidden dimension with a debrief. Protocol v1 does not carry it yet, so the App applies
only the default. Automation/profile identifiers, timers, epochs, digests, owner UID, and typed
failure reasons are never shown under any policy.

The existing five setup steps and normal running screen remain the primary surface. One labeled
*Study and my data* entry, identical in every study arm, opens a secondary screen that restates the
floor information and coarse participation facts; it adds no lifecycle control. It is a Compose
state with a back handler inside `MainActivity`, so `app_lifecycle.v1` records no extra Activity.
The entry is shown once the study has started: on the collection panel, and on the access panel
that replaces it while required access is repaired in `RUNNING` or `PAUSED`. It is absent during
setup and while recovery requires action.
Its facts come from `ParticipantParticipationSummary`, built only from the monotonic study clock:
study length is calendar elapsed time, collecting time is active-running time, and paused time is
their difference. While the study is under way, each total is extended live by the phone's wall
time since the clock anchor (collecting time only while `RUNNING`), never by a difference between
the phone's clock and the network-time start or deadline, so a skewed or changed phone clock
neither invents nor hides paused time. An ended study's length is the calendar time at the commit
that ended it. Every total stops at the signed duration, which also covers a deadline processed
late after the phone was off. The study day is 24-hour periods of that study length, ending at the
planned end; the planned end is shown only while the deadline is trusted. The screen also shows the
last export size and local storage, which `StudySessionManager` measures only when the screen
opens. The signed `configuration_id` is not part of the projection: it differs between study arms
and, from the Web tool, carries a digest of the whole configuration. Each state offers
only the exit its command accepts: setup states offer "Decline and remove this study" (the local
deletion path, since Withdraw is a runtime command only from `RUNNING` or `PAUSED`), started studies
offer Withdraw, and ended studies offer Delete local data. Shaping adds only
the fixed inline paragraph next to the existing Access completion control; Done/Resume sequences
Android 17 local network permission and system VPN consent. No VPN card/status/history or second
ongoing notification is added. `CollectionService` and the VPN service share one neutral
notification identity while either foreground service remains active.

`StudyViewModel` builds the projection only while `MainActivity` is started. It stops five seconds
after the screen stops, which is long enough that a configuration change does not restart it, and
it rebuilds the model only when the session snapshot changes, so export progress and busy or message
changes reuse the model. The live clocks on the running screen and *Study and my data* tick only
while the Activity is started. Checks made outside composition read `StudySessionManager.snapshot`
rather than the last projection: the traffic-shaping safety pause on resume, and whether Access
completion, Start, and Resume need the VPN and local-network prerequisites first.

Under the default policy, Particeps-generated/derived UI never reveals treatment control. Researcher-authored study title,
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

Manual export derives commit count from the captured range and accumulates event count while
streaming that range once. Budgeted upload performs a bounded selection pass, then verifies the
selected counts and final chain digest during output. Character buffering and complete string-span
writes preserve canonical JSON and strict surrogate checks. Neither path stores plaintext on disk.

The participant export operation has its own state and cancellation job, separate from lifecycle
commands. Preparation is indeterminate, encryption reports completed batches in the captured range,
and finalization remains indeterminate until the destination closes successfully. Cancellation is
checked between commits; a blocking document provider may delay cancellation, but does not hold the
store or session mutex. The app closes every accepted destination on IO, then attempts to delete
incomplete files on cancellation or failure. Failed removal is explicitly shown. Successful export
metadata is published only after close succeeds and cancellation has been checked.

The `ParticepsExport` log tag records fixed phase names, elapsed times, outcome and successful
aggregate commit/event/byte counts. It excludes study identities, destination URIs, exception text
and event contents. Phase timings separate opening, preparation, selection, encryption, finalization
and cleanup; preparation includes lock waits and the scoped snapshot capture.

Automatic upload stages immutable ciphertext before HTTP. Headers and receipts name complete commit
ranges and aggregate event count; participant identity stays encrypted. Exact replay reuses bytes.
Nothing is staged or scheduled before Start: `StudySessionManager` arms the upload chain when Start
leaves setup (including a start that fails closed to `PAUSED`) and on each process start of a
started study, and refuses to stage while the runtime is in a setup state. A participant who
declines during setup has therefore sent nothing; for one who starts, the first bundle begins at
commit 1 and carries the setup commits with the first collection.
The receiver validates bounds/digest/identity/range and stores ciphertext atomically without keys.

Python inventory copies ciphertext into a content-addressed workspace. Materialization verifies each
bundle, reassembles the complete chain, runs an independent current Protocol v1 reducer, and only
then writes typed Parquet partitioned by event identity with epoch provenance. One invalid bundle
prevents publishing that dataset.

## Build and release gates

CI runs registry generation/checks; Kotlin/TypeScript/Python conformance; JVM, Compose,
instrumentation, Web, analysis, receiver, and native tests; and release verification.

API 34 x86_64 is the complete blocking functional lane, including host-orchestrated process kill,
reboot, competing VPN, permission/package change, TCP/UDP/DNS, throughput, and multi-APK cases. API
37 `google_apis_ps16k` x86_64 revision 5 or newer is a blocking compatibility lane for compilation,
installation, 16 KiB runtime page size, manifest/permission contracts, native loading, and
instrumentation that does not invoke system task snapshots. The release verifier independently
blocks on all four packaged ABIs and 16 KiB ELF alignment.

The complete API 37 host harness is temporarily quarantined only when evidence exactly matches the
revision 5 `mapper.ranchu.so` / `SurfaceFlinger` readback assertion and contains no Particeps App,
VPN, native, or instrumentation/test assertion failure. It must also end in a recognized emulator
transport failure; a coincident platform log cannot mask a failed scenario. Any other failure
remains blocking. CI does not modify the preview system image or its services. This limitation is
tracked in [#33](https://github.com/JacobLinCool/particeps/issues/33), and a quarantined run is not
represented as a complete API 37 host-harness pass. API 37 orchestration observes the package and
activity services required by its non-UI compatibility checks because revision 5 can publish
`sys.boot_completed=1` before those services are usable. An exact-signature service restart during the blocking
compatibility test permits a bounded full-test retry, but never substitutes for a successful
installation and native-loading result.
Once those blocking checks pass, the full-harness watcher may stop only on the exact assertion plus
a failed live package-manager probe. Host-scenario failures are retained before cleanup, so a later
matching platform crash cannot convert them into a quarantine.

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
