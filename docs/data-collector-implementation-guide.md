# Collector implementation guide

This guide describes the v1 collector API as it exists in this repository, and how to add
a new collector without breaking the pause, privacy, and storage invariants that the rest
of the system depends on.

Read [System design](system-design.md) first for the module map, and
[Component boundaries](component-boundaries.md) for the responsibility split. This document
covers only the collector side of that boundary.

Everything below is current source. Where a field or a rule exists but nothing enforces it,
this guide says so — see [Known gaps](#13-known-gaps).

## 1. What a collector is

A collector is three separate things with three separate lifetimes.

| Part | Type | Lives in | Lifetime |
| --- | --- | --- | --- |
| Typed parameters | a `CollectorConfiguration` subtype | `:core:study-definition` | decoded once from the signed study configuration, immutable |
| Registration | `CollectorPlugin` | `:collector:<name>` | one instance per process, built in the composition root |
| Running instance | `Collector` | `:collector:<name>` | created when the runtime initializes, driven start → pause → resume → stop |

The configuration type lives in `:core:study-definition` and not in the collector module,
because the strict codec must be able to decode every collector's parameters without
depending on any collector. That is what makes the set of collectors a closed world: the
codec's `when` over collector IDs is the allowlist.

## 2. The contract

Every type below is declared in one file:
[`core/collector-api/.../CollectorContracts.kt`](../core/collector-api/src/main/kotlin/cool/linc/androiddatacollector/core/collector/CollectorContracts.kt).
The only other file in `:core:collector-api` is the shared base class covered in
[section 6](#6-lifecycle).

### Plugin and instance

```kotlin
interface CollectorPlugin {
    val descriptor: CollectorDescriptor

    fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement>

    fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector
}

interface Collector {
    val health: StateFlow<CollectorHealth>

    suspend fun start()

    suspend fun pause()

    suspend fun resume()

    suspend fun stop()
}
```

`accessRequirements` and `create` both receive the base `CollectorConfiguration` interface
and must narrow it themselves. Every existing plugin rejects a mismatch with
`IllegalArgumentException` rather than substituting a default.

### Descriptor

```kotlin
data class CollectorDescriptor(
    val id: String,
    val payloadSchemaVersion: Int,
    val displayName: String,
    val privacyClass: PrivacyClass,
    val maximumEncodedEventBytes: Int,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid collector ID" }
        require(payloadSchemaVersion > 0) { "Payload schema version must be positive" }
        require(displayName.isNotBlank()) { "Collector display name must not be blank" }
        require(maximumEncodedEventBytes in 128..65_536) { "Invalid maximum event size" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9_.-]{2,63}")
    }
}

enum class PrivacyClass {
    SENSITIVE,
    RESTRICTED,
}
```

### Context and clocks

```kotlin
data class CollectorContext(
    val scope: CoroutineScope,
    val eventSink: EventSink,
    val clocks: ResearchClocks,
)

interface ResearchClocks {
    fun now(): ResearchTime
}
```

Three handles. A coroutine scope, an event sink, and a clock. There is no store, no state
machine, no scheduler, no exporter, and no `Activity`. That narrowness is the boundary —
see [section 3](#3-invariants-you-must-not-break).

`ResearchTime` carries three readings taken together
([`core/model/.../ExperimentModels.kt`](../core/model/src/main/kotlin/cool/linc/androiddatacollector/core/model/ExperimentModels.kt)):

```kotlin
data class ResearchTime(
    val wallTimeUtcMillis: Long,
    val elapsedRealtimeNanos: Long,
    val bootSessionId: String,
)
```

Wall time can jump backwards when the participant or the network changes the device clock.
`elapsedRealtimeNanos` is monotonic within a boot, and `bootSessionId` tells an analyst
which boot an elapsed reading belongs to. The admission gate compares only
`elapsedRealtimeNanos`, so ordering decisions never depend on wall time.

### Event sink

```kotlin
/** Opaque runtime-issued admission capability; collector features cannot construct a valid token. */
interface AdmissionToken

sealed interface EmitResult {
    data class Accepted(val sequenceNumber: Long) : EmitResult

    data object RejectedByAdmissionGate : EmitResult

    data object StorageFailure : EmitResult
}

interface EventSink {
    fun captureToken(): AdmissionToken?

    suspend fun emit(token: AdmissionToken, event: EventDraft): EmitResult

    suspend fun latestEvent(collectorId: String): RecordedEvent?
}
```

`AdmissionToken` is a marker interface with no members. The only implementation is
`EventAdmissionGate.EpochToken`, which is `private` inside
[`core/experiment-runtime/.../EventAdmissionGate.kt`](../core/experiment-runtime/src/main/kotlin/cool/linc/androiddatacollector/core/runtime/EventAdmissionGate.kt).
A collector can write `object : AdmissionToken {}`, but the gate's `epoch()` extension maps
any foreign implementation to `Long.MIN_VALUE`, which never equals a live epoch, so the
event is rejected. Forging a token is possible; forging an accepted token is not.

`latestEvent` returns the last event this collector persisted, from bounded metadata that
survives process death. Polling collectors use it to resume a coverage window instead of
re-querying an interval they already recorded. It exposes only this collector's own last
event, not the event history and not another collector's data. Reclaiming local space does
not take it away: these are stored in the study metadata rather than recovered by scanning the
event log, so a collector that has been quiet for a long time still finds the timestamp it
resumes from even after the segment holding that event is gone.

### Access requirements

```kotlin
enum class AccessKind {
    FINE_LOCATION,
    BACKGROUND_LOCATION,
    NOTIFICATIONS,
    USAGE_ACCESS,
    RESEARCH_KEYBOARD_ENABLED,
    RESEARCH_KEYBOARD_SELECTED,
    ACCELEROMETER_HARDWARE,
}

data class AccessRequirement(
    val kind: AccessKind,
    val required: Boolean,
)
```

`AccessKind` deliberately mixes Android runtime permissions, special access grants, an
input-method selection state, and a hardware capability. They are all preconditions the
participant can see and, except for hardware, revoke. `required` is not a property of the
collector; it is copied from the `required` flag the researcher set on that collector in the
study configuration.

| `required` | Missing access at preflight | Missing access at start |
| --- | --- | --- |
| `true` | `completeAccessSetup` rejects the command; the study cannot reach `READY` | collector health becomes `BLOCKED_ACCESS` / `ACCESS_UNAVAILABLE` |
| `false` | preflight passes | collector health becomes `BLOCKED_ACCESS` / `ACCESS_UNAVAILABLE`, the rest of the study runs |

A blocked collector produces no events. It never produces substitute, degraded, or
placeholder events.

## 3. Invariants a collector must hold

These are structural rules of the current design. Breaking one does not produce a bug to fix
later; it invalidates what the participant guide and the deployed consent texts describe, which
is a coordination problem with live studies rather than a code change. If you have a reason to
change one of these, raise it as a design discussion first.

| A collector does not | Why | Boundary that enforces it |
| --- | --- | --- |
| Write files, databases, or preferences | Every research byte must go through the encrypted store so that sequence numbers stay contiguous and export, upload, and reclaiming can all reason about one window. A side file is invisible to export, to the storage quota, and to deletion. | The only project dependencies in a `:collector:*` build file are `:core:collector-api` and `:core:study-definition`. `:core:storage` is not on the classpath, and `CollectorContext` carries no `StudyStore`. |
| Change study state | `IMPORTED` → … → `WITHDRAWN` is the participant's control surface. A collector that could move it could un-pause a study the participant paused. | `ExperimentStateMachine.transition` is called only from `ExperimentRuntime` in `:core:experiment-runtime`, which no collector module depends on. |
| Start an `Activity` or drive UI | The app must never interrupt the participant on a collector's schedule. | Collector modules do not depend on `:app`. Plugins are constructed with `context.applicationContext`. |
| Schedule interventions or notifications | Intervention timing and occurrence identity come from the signed configuration and are reconciled by the session manager. | The `StudyWorkScheduler` port is declared in `:core:study-application`; the WorkManager adapter is in `:app`. Neither is on a collector's classpath. |
| Export, encrypt, or package data | Export is a participant-initiated act over a bounded sequence window, encrypted to a researcher HPKE key. | `:core:export` and `:core:crypto` are not on any collector's classpath. |
| Open a socket or upload | Network transport lives in the study application layer, where the `StudyUploader` port sends only the encrypted bundle, only to the endpoint the signed configuration names, and only after the consent screen has disclosed it. A collector reaching the network would bypass all three, and the participant guide and deployed consent texts describe a study's transmission as coming from that one place. | The app declares `android.permission.INTERNET` for the upload worker, so the permission is present in the process and no manifest check will catch a collector using it. `:core:export`, `:core:crypto`, and the uploader are off a collector's classpath, and `CollectorContext` exposes no network client. Review is what enforces the rest. |
| Record text, characters, or content typed on the research keyboard | The consent text tells participants the keyboard never sees what they write. Touch dynamics research does not need the characters, so the characters are never carried across the boundary. | `ResearchKeyboardView.onTouchEvent` passes `key.category.name` to `ImeObservationBridge.publish`, never `key.text`. `ImeTouchObservation` has no field that could hold a character. The committed text goes to `InputConnection` and stops there. |
| Log payload values, paths, package names, or exception messages | A logcat line is readable by anyone with adb access and is not covered by the encrypted store. | `CollectorHealth.reasonCode` is constrained to `[A-Z][A-Z0-9_]{2,63}`, which cannot hold free text. There is no other diagnostic channel in the API. |

### What is not enforced

None of the above is a sandbox. A collector module is Kotlin compiled into the same process
with the app's permissions; `java.io.File` and `Context.startActivity` are reachable from
any of them. The Gradle dependency graph is the real enforcement point for most of these
rules, and there is currently **no automated architecture test** asserting it. A collector
that broke these rules would compile and pass CI. Review is the backstop.

Verify the graph yourself:

```bash
./gradlew :collector:accelerometer:dependencies --configuration debugCompileClasspath
```

Verify the permission set of a built APK, which is stronger than reading a single manifest:

```bash
aapt dump permissions app/build/outputs/apk/release/app-release.apk
```

Expect exactly the permissions the collectors and the upload worker need — and nothing a
collector added on its own:

```text
ACCESS_BACKGROUND_LOCATION   ACCESS_COARSE_LOCATION   ACCESS_FINE_LOCATION
ACCESS_NETWORK_STATE         FOREGROUND_SERVICE       FOREGROUND_SERVICE_LOCATION
FOREGROUND_SERVICE_SPECIAL_USE  INTERNET              PACKAGE_USAGE_STATS
POST_NOTIFICATIONS           RECEIVE_BOOT_COMPLETED   WAKE_LOCK
```

plus the signature-level `cool.linc.androiddatacollector.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
that AndroidX contributes. `INTERNET` belongs to the study application layer's upload worker;
its presence no longer tells you whether any given study transmits, and it means a collector's
own network call would not show up here either.

## 4. Module layout and dependency direction

Each data source is one Gradle module:

```text
:collector:<name>
    -> :core:collector-api   (which re-exports :core:model and :core:study-definition as api dependencies)
    -> :core:study-definition
```

Because `:core:collector-api` declares `api(project(":core:model"))`, model types such as
`EventDraft`, `RecordedEvent`, `StudyStore`, and `ExperimentState` are on a collector's
compile classpath. Being able to *name* `StudyStore` is not the same as holding one: nothing
in `CollectorContext` gives a collector an instance, and no implementation module is
reachable. Do not treat visibility as permission.

`:app` builds the plugin list explicitly and hands it to `CollectorRegistry`. A study
configuration selects stable IDs from that list. There is no reflective class loading, no
DEX download, and no script interpreter anywhere in the path:

```kotlin
fun pluginFor(configuration: CollectorConfiguration): CollectorPlugin =
    plugins.singleOrNull { it.descriptor.id == configuration.id }
        ?: throw IllegalArgumentException("Collector is not compiled into this app: ${configuration.id}")
```

`CollectorRegistry`'s constructor also rejects duplicate descriptor IDs.

## 5. Descriptor and configuration contract

### Identity and versioning

- The ID must match `[a-z][a-z0-9_.-]{2,63}`. Every existing collector uses `<name>.v<major>`.
- The ID is immutable once a study has shipped. Changing what an existing payload type
  means, changing units, or removing a field is a new major ID and a new configuration type.
- `payloadSchemaVersion` versions the payload independently of the configuration schema and
  independently of the ID. It is stamped on every `EventDraft` and travels into the export.
- `maximumEncodedEventBytes` must be in `128..65_536`. Declare a bound you can actually
  justify from the field list. Note that **nothing reads this field today** — see
  [Known gaps](#13-known-gaps). The limits the code does enforce are `EventDraft`'s: at most
  32 fields, each value at most 1,024 characters.
- `privacyClass` is `RESTRICTED` for `keyboard_touch.v1` and `SENSITIVE` for the other six.
  Nothing reads it today either; it documents the author's own classification.

### Strict configuration decoding

Configuration decoding lives in
[`StudyConfigurationCodec`](../core/study-definition/src/main/kotlin/cool/linc/androiddatacollector/core/definition/StudyConfigurationCodec.kt).
It is strict in a specific, checkable way:

- `requireExactKeys` demands the exact key set. Unknown keys, missing keys, and renamed keys
  are all rejected. There are no optional fields with defaults.
- Integers must match `-?(0|[1-9][0-9]*)` as a literal, so `1.0`, `1e3`, and `"1"` are all
  rejected for an integer field.
- `decode` re-encodes what it parsed and requires the bytes to be identical
  (`require(encode(decoded).contentEquals(bytes))`). Whitespace, key order, and number
  formatting are therefore all fixed. Signatures are taken over these canonical bytes.
- An unknown collector ID throws. There is no fallback reader and no legacy path.

Range checks live in the configuration type's `init` block, not in the codec, so they apply
equally to a configuration built in a test.

## 6. Lifecycle

The base class for callback-driven collectors is
[`SerializedCallbackCollector`](../core/collector-api/src/main/kotlin/cool/linc/androiddatacollector/core/collector/SerializedCallbackCollector.kt).
It marks all four lifecycle methods `final` and leaves you two:

```kotlin
protected abstract suspend fun registerSource()
protected abstract suspend fun unregisterSource()
```

Use it unless your source is a periodic query. What the base class does with each call:

### `start()`

1. Rejects a second start (`check(consumerJob == null)`) and rejects starting from any state
   other than `STOPPED` or `FAILED`.
2. Launches the single consumer coroutine on `Dispatchers.Default` in the runtime's scope.
3. Calls `registerSource()` inside a `sourceRegistered` guard that makes double registration
   a failure rather than a silent second listener.
4. On success sets `ACTIVE`. On failure it drains the consumer, clears the job, sets
   `FAILED` / `SOURCE_REGISTRATION_FAILED`, and rethrows so the runtime can record
   `COLLECTOR_START_FAILED`. The collector can be started again afterwards.

### `pause()`

1. `unregisterSource()` first, so the Android source stops producing before anything else.
2. `flush()` sends a barrier through the queue and awaits it, so everything already queued
   reaches `emit` before `pause` returns.
3. Sets `PAUSED` unless health is already `FAILED`. A failure is never cleared by pausing.

Around this, the runtime has already put the admission gate into `DRAINING` with a boundary
taken from `clocks.now().elapsedRealtimeNanos`. During the drain, only events from the same
epoch whose `observedTime.elapsedRealtimeNanos` is strictly before the boundary are accepted.
Anything observed after the participant pressed pause is dropped, even if it is still sitting
in the queue.

### `resume()`

1. Requires an existing consumer job and a `PAUSED` or `FAILED` status.
2. Calls `registerSource()` again. It does not launch a second consumer.
3. Sets `ACTIVE`.

The runtime calls `admissionGate.open()` on resume, which increments the epoch. Tokens
captured before the pause are dead. A retrospective-query collector must start a new coverage
window at resume time and must not backfill the paused interval — see `network_usage.v1` and
`usage_events.v1`, both of which reset their query start to the resume wall time.

### `stop()`

1. Returns immediately if never started.
2. `unregisterSource()` (capturing but not yet throwing any failure), `flush()`, then a stop
   sentinel, then joins the consumer.
3. Releases the `HandlerThread`, listener, or bridge in `unregisterSource()`.
4. Sets `STOPPED`, or `FAILED` / `SOURCE_UNREGISTRATION_FAILED` if unregistration threw.

Wrong call order must fail loudly. Every base-class method begins with a `check`. If you
write a collector without the base class, keep that property: never let a second `start()`
quietly create a second listener.

## 7. Event admission and time

### The capture pattern

```kotlin
protected fun capture(draft: () -> EventDraft) {
    val token = context.eventSink.captureToken() ?: return
    if (!messages.trySend(Message.Event(token, draft())).isSuccess) {
        fail("CALLBACK_QUEUE_FULL")
    }
}
```

The token is taken *before* the draft is built, on the source thread. Two consequences you
should rely on:

- If the study is not running, the lambda never executes. No observation is even constructed
  from a `MotionEvent` or `SensorEvent` outside a running window. `SerializedCallbackCollectorTest.rejectedAdmissionDoesNotConstructAnObservation`
  asserts exactly this.
- The token pins the epoch at observation time, not at write time. An event queued before a
  pause carries the pre-pause epoch and is judged against the drain boundary.

A collector subclass supplies only the draft:

```kotlin
capture {
    EventDraft(
        collectorId = descriptor.id,
        payloadSchemaVersion = descriptor.payloadSchemaVersion,
        observedTime = context.clocks.now(),
        payloadType = "STABLE_TYPE",
        fields = fields,
    )
}
```

### What `EventDraft` allows

From [`core/model/.../ExperimentModels.kt`](../core/model/src/main/kotlin/cool/linc/androiddatacollector/core/model/ExperimentModels.kt):

| Constraint | Value |
| --- | --- |
| `collectorId` | `[a-z][a-z0-9_.-]{2,63}` |
| `payloadType` | `[A-Z][A-Z0-9_]{1,63}` |
| field key | `[a-z][a-z0-9_]{0,63}` |
| field count | at most 32 |
| field value length | at most 1,024 characters |
| field value type | `String` only — encode numbers with `toString()` |

The runtime sorts fields with `toSortedMap()` when it builds the `RecordedEvent`, so field
order in your map does not affect the stored bytes.

### Source time versus write time

`observedTime` is when the collector observed the event, which is not when Android produced
it. When the platform gives you its own timestamp, record it as a payload field as well:

- `accelerometer.v1` stores `SensorEvent.timestamp` as `source_elapsed_realtime_nanos`.
- `location.v1` stores both `Location.elapsedRealtimeNanos` and `Location.time`.
- `usage_events.v1` stores `UsageEvents.Event.timeStamp` as `source_time_utc_millis`.

Never overwrite a source time with a write time. A batched sensor delivery can hand you
samples that were taken seconds earlier, and an analyst who cannot tell the difference will
draw a wrong conclusion about timing.

### Emit results

| Result | Meaning | What the collector must do |
| --- | --- | --- |
| `Accepted(sequenceNumber)` | durably appended | nothing |
| `RejectedByAdmissionGate` | outside a valid running window | drop it silently; this is normal at every pause and stop |
| `StorageFailure` | the append failed | set `FAILED` with a fixed reason code |

`StorageFailure` is not recoverable by retrying. On the runtime side, `emit` force-closes the
admission gate, records the `STORAGE_WRITE_FAILED` incident, and launches a fail-closed
transition to `PAUSED` with reason `STORAGE_FAILURE`. The design choice is deliberate:
when the system can no longer prove it is recording completely, it stops recording rather
than producing a log with invisible holes.

## 8. Concurrency and backpressure

High-frequency callbacks arrive on Binder, main, or sensor threads. None of them may do disk
I/O. `SerializedCallbackCollector` owns the whole concurrency story: a bounded `Channel`, one
consumer coroutine, barrier messages, the stop sentinel, health, and the
registered/unregistered invariant. Feature modules supply source registration and payload
encoding only.

| Collector | Base | Queue capacity |
| --- | --- | --- |
| `app_lifecycle.v1` | `SerializedCallbackCollector` | 128 |
| `network_state.v1` | `SerializedCallbackCollector` | 256 |
| `location.v1` | `SerializedCallbackCollector` | 512 |
| `accelerometer.v1` | `SerializedCallbackCollector` | 2,048 |
| `keyboard_touch.v1` | `SerializedCallbackCollector` | 2,048 |
| `network_usage.v1` | `Collector` directly (polling) | none |
| `usage_events.v1` | `Collector` directly (polling) | none |

Rules:

- Send with `trySend`. The callback thread never blocks and never suspends.
- A full queue is a failure, not a silent drop. The base class sets
  `FAILED` / `CALLBACK_QUEUE_FULL`. Losing events without saying so would make the event log
  quietly incomplete, which is worse for a study than stopping.
- Only the single consumer coroutine calls `EventSink.emit`, so sequence numbers are assigned
  in a well-defined order.
- Callbacks that Android delivers to a `Looper` get a dedicated `HandlerThread` owned by the
  collector, quit in `unregisterSource()` (`accelerometer.v1`, `location.v1`).
- Polling queries run on `Dispatchers.IO` inside `withContext`, and `CancellationException`
  must be rethrown, never swallowed by a broad `catch`.
- No unbounded queues, no `GlobalScope`, no unsupervised threads. Use
  `context.scope`, which the runtime cancels on shutdown.

## 9. Health and reason codes

`CollectorHealth` has five states and a reason code that is required for exactly two of them:

| Status | Reason code |
| --- | --- |
| `STOPPED` | must be absent |
| `ACTIVE` | must be absent |
| `PAUSED` | must be absent |
| `BLOCKED_ACCESS` | must be present |
| `FAILED` | must be present |

The constructor enforces both halves of that rule and the format `[A-Z][A-Z0-9_]{2,63}`.
A reason code must never carry an exception message, a file path, a location, a package
name, input content, or any other research data. The regex makes most of those impossible to
express, which is the point: the diagnostic channel is deliberately too narrow to leak.

Codes currently in the source:

| Code | Set by | Meaning |
| --- | --- | --- |
| `SOURCE_REGISTRATION_FAILED` | `SerializedCallbackCollector` | `registerSource()` threw during start or resume |
| `SOURCE_UNREGISTRATION_FAILED` | `SerializedCallbackCollector` | `unregisterSource()` threw during pause or stop |
| `CALLBACK_QUEUE_FULL` | `SerializedCallbackCollector` | the bounded queue rejected an event |
| `STORAGE_WRITE_FAILED` | `SerializedCallbackCollector`, `network_usage.v1`, `usage_events.v1` | `emit` returned `StorageFailure` |
| `WALL_CLOCK_NOT_FORWARD` | `network_usage.v1`, `usage_events.v1` | the wall clock did not advance past the coverage start |
| `USAGE_ACCESS_REVOKED` | `network_usage.v1`, `usage_events.v1` | the platform query threw `SecurityException` |
| `NETWORK_STATS_QUERY_FAILED` | `network_usage.v1` | `NetworkStatsManager` threw a `RuntimeException` |
| `USAGE_EVENTS_QUERY_FAILED` | `usage_events.v1` | `UsageStatsManager` threw a `RuntimeException` |
| `ACCESS_UNAVAILABLE` | `ExperimentRuntime` | required access was missing when collectors were activated (`BLOCKED_ACCESS`) |
| `COLLECTOR_START_FAILED` | `ExperimentRuntime` | `start()` or `resume()` threw |
| `COLLECTOR_PAUSE_FAILED` | `ExperimentRuntime` | `pause()` threw |
| `COLLECTOR_STOP_FAILED` | `ExperimentRuntime` | `stop()` threw |

`RuntimeSnapshot.collectorHealth` is written from two places: the runtime's own
`updateCollectorHealth`, and a job collecting each collector's `health` flow. The map holds
whichever wrote last. Runtime-level incidents (`COMMAND_REJECTED`, `RUNTIME_FAILURE`,
`STORAGE_WRITE_FAILED`, `PAUSE_PERSISTENCE_FAILED`) are a separate field,
`RuntimeSnapshot.incidentCode`, and are not collector health.

## 10. The seven built-in collectors

Each entry states what the collector records and, as importantly, what its data cannot be
used to claim.

### `app_lifecycle.v1`

| | |
| --- | --- |
| Display name | `Own-app lifecycle` |
| Privacy class | `SENSITIVE` |
| Payload schema | 1 |
| Declared max bytes | 2,048 |
| Config | `{}` — empty object, exact |
| Access | none |
| Queue | 128 |

Payload types: `ACTIVITY_CREATED`, `ACTIVITY_STARTED`, `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`,
`ACTIVITY_STOPPED`, `ACTIVITY_INSTANCE_STATE_SAVED`, `ACTIVITY_DESTROYED`.

Field: `activity_class`. Registration goes through `Application.registerActivityLifecycleCallbacks`
on `Dispatchers.Main.immediate`, so it observes only this app's own activities.

This is not app usage. It says when the participant looked at the collector app itself.

### `accelerometer.v1`

| | |
| --- | --- |
| Display name | `Accelerometer` |
| Privacy class | `SENSITIVE` |
| Declared max bytes | 2,048 |
| Access | `ACCELEROMETER_HARDWARE` (device capability, no Android permission) |
| Queue | 2,048 |

```json
{
  "sampling_period_us": 100000,
  "maximum_report_latency_us": 1000000
}
```

- `sampling_period_us`: 5,000–1,000,000 (so at most 200 Hz requested).
- `maximum_report_latency_us`: 0–60,000,000.

Both are hints. Android may deliver at a different rate, and batching means a delivery can
contain samples taken up to the report latency earlier.

`ACCELEROMETER_SAMPLE` fields: `source_elapsed_realtime_nanos`,
`x_meters_per_second_squared`, `y_meters_per_second_squared`,
`z_meters_per_second_squared`, `accuracy`.

The listener runs on a dedicated `HandlerThread` named `adc-accelerometer`. Axes and units
are Android's, unmodified. The collector performs no filtering, no gravity removal, and no
inference. It does not produce step counts, postures, or activity labels; those are the
analyst's claims to make and defend.

### `network_state.v1`

| | |
| --- | --- |
| Display name | `Network connection state` |
| Privacy class | `SENSITIVE` |
| Declared max bytes | 4,096 |
| Access | none (`ACCESS_NETWORK_STATE` is a normal manifest permission) |
| Queue | 256 |

```json
{ "include_bandwidth_estimates": true }
```

Uses `ConnectivityManager.registerDefaultNetworkCallback`. Every `registerSource()` — so at
both start and resume — also writes one `NETWORK_SNAPSHOT` describing the current state, so a
segment never begins with an unknown connection state.

Payload types: `NETWORK_AVAILABLE`, `NETWORK_LOST`, `NETWORK_CAPABILITIES`,
`NETWORK_SNAPSHOT`.

Fields: `wifi`, `mobile`, `ethernet`, `vpn`, `validated`, `metered`, `roaming`; plus
`connected` on `NETWORK_SNAPSHOT` only; plus `downstream_kbps` and `upstream_kbps` when
`include_bandwidth_estimates` is true. `NETWORK_AVAILABLE` and `NETWORK_LOST` carry no
fields.

`metered` and `roaming` are stored as the negation of Android's `NOT_METERED` and
`NOT_ROAMING` capabilities. The bandwidth values are the platform's link estimates, not
measurements.

Do not add SSID, BSSID, IP address, DNS server, URL, packet content, active socket probing, or
any network identifier without raising it as a design discussion first. Those turn a
connection-state record into something materially different, with consequences for consent text
and ethics review that go well beyond the code.

### `network_usage.v1`

| | |
| --- | --- |
| Display name | `Aggregate network usage` |
| Privacy class | `SENSITIVE` |
| Declared max bytes | 2,048 |
| Access | `USAGE_ACCESS` |
| Base | plain `Collector`, polling |

```json
{
  "transports": ["mobile", "wifi"],
  "poll_interval_minutes": 5
}
```

- `poll_interval_minutes`: 1–1,440. The floor is a minute so a pilot does not have to wait for
  a result; note that a shorter window buys finer sampling of the platform's counters, not finer
  truth, since `NetworkStatsManager`'s own accounting granularity is coarser than that.
- `transports`: non-empty set of `mobile` and/or `wifi`; canonical encoding sorts them by
  enum name and lowercases them.

Queries `NetworkStatsManager.querySummaryForDevice(networkType, null, start, end)` on
`Dispatchers.IO`, once per configured transport per tick.

`NETWORK_USAGE_AGGREGATE` fields: `transport`, `coverage_start_utc_millis`,
`coverage_end_utc_millis`, `rx_bytes`, `tx_bytes`, `rx_packets`, `tx_packets`.

Coverage window handling:

- On `start()`, coverage resumes from the last persisted `coverage_end_utc_millis` read back
  through `EventSink.latestEvent`, so a process restart does not lose or duplicate an
  interval.
- On `resume()`, coverage starts at the resume wall time. The paused interval is deliberately
  not backfilled; recording usage that accrued while the participant had the study paused
  would break the pause guarantee.
- If wall time has not advanced past the coverage start, the collector sets
  `WALL_CLOCK_NOT_FORWARD` rather than emitting a window it cannot justify.

This is Android's coarse, device-total, possibly delayed counter. It is not packet
inspection, not per-app attribution, not instantaneous throughput, and not evidence of when
within the window the traffic occurred.

### `usage_events.v1`

| | |
| --- | --- |
| Display name | `App and screen usage events` |
| Privacy class | `SENSITIVE` |
| Declared max bytes | 4,096 |
| Access | `USAGE_ACCESS` |
| Base | plain `Collector`, polling |

```json
{ "poll_interval_minutes": 15 }
```

`poll_interval_minutes`: 1–1,440. The floor is a minute so a pilot does not have to wait for a
result. A study shorter than one poll interval collects nothing from this collector, because
there is no flush on stop.

Payload types are Android's own event names, preserved rather than reinterpreted:
`ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`, `SCREEN_INTERACTIVE`,
`SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, `KEYGUARD_HIDDEN`, `DEVICE_STARTUP`,
`DEVICE_SHUTDOWN`. Any other event type Android reports is discarded.

Fields: `source_time_utc_millis`, and `package_name` when the platform supplies a non-blank
one.

On `start()` the query resumes from the last persisted `source_time_utc_millis + 1`; on
`resume()` it starts at the resume wall time.

The app does not reconstruct sessions. Analysis must handle gaps, duplicates, multi-window
events, and unpaired start/stop events. A `package_name` identifies an app the participant
used and is sensitive; a data set containing it is not anonymous.

### `location.v1`

| | |
| --- | --- |
| Display name | `Location` |
| Privacy class | `SENSITIVE` |
| Declared max bytes | 4,096 |
| Access | `FINE_LOCATION` and `BACKGROUND_LOCATION` |
| Queue | 512 |

Configuration fields, all required and exact:

| Field | Range |
| --- | --- |
| `interval_millis` | 1,000–3,600,000 |
| `minimum_interval_millis` | 500 to `interval_millis` |
| `maximum_batch_delay_millis` | 0–86,400,000 |
| `minimum_displacement_meters` | 0–10,000 |
| `priority` | `BALANCED` or `HIGH_ACCURACY` |

Uses Google Play services `FusedLocationProviderClient`. There is no platform
`LocationManager` fallback: on a device without Play services this collector fails to
register rather than silently switching to a different, undocumented source.
`registerSource()` re-checks `ACCESS_FINE_LOCATION` itself and throws if it was revoked
after the study started.

`LOCATION_FIX` fields: `source_elapsed_realtime_nanos`, `source_time_utc_millis`,
`latitude_degrees`, `longitude_degrees`, `horizontal_accuracy_meters`, `mock`; plus
`altitude_meters`, `vertical_accuracy_meters`, `speed_meters_per_second`,
`speed_accuracy_meters_per_second`, `bearing_degrees`, and `bearing_accuracy_degrees` when
the platform reports each as present.

The callback runs on a dedicated `HandlerThread` named `adc-location`; pause and stop remove
updates and flush.

A fix is a fused estimate with a stated accuracy radius, not ground truth. The `mock` field
records whether Android flagged the fix as mocked; treat it as information, not a guarantee.

### `keyboard_touch.v1`

| | |
| --- | --- |
| Display name | `Research keyboard touch` |
| Privacy class | `RESTRICTED` |
| Declared max bytes | 4,096 |
| Access | `RESEARCH_KEYBOARD_ENABLED` and `RESEARCH_KEYBOARD_SELECTED` |
| Queue | 2,048 |

```json
{ "trajectory_sampling_hz": 60 }
```

`trajectory_sampling_hz`: 1–120. It throttles `ACTION_MOVE` only, to a minimum interval of
`1000 / hz` milliseconds (floored to at least 1 ms). `DOWN`, `UP`, and `CANCEL` are never
sampled out.

`KEYBOARD_TOUCH` fields: `action` (`DOWN`/`MOVE`/`UP`/`CANCEL`), `event_uptime_millis`,
`down_uptime_millis`, `pointer_id`, `relative_x`, `relative_y` (both clamped to 0–1 within
the key's own bounds), `pressure`, `size`, `orientation_radians`, `tool_type`,
`key_category` (`LETTER`, `SPACE`, `BACKSPACE`, `ENTER`), and `geometry_version`
(currently the constant `qwerty-v1`).

Three separate gates must all be open before a single touch is recorded:

1. The participant has enabled the research keyboard and selected it as the current input
   method. Android will not run the IME otherwise.
2. The study is `RUNNING`, so `captureToken()` returns a token. `ImeObservationBridge` is
   installed only while the collector is registered.
3. `ResearchInputMethodService` set `collectionAllowed`. It is set from
   `SensitiveFieldPolicy.isSensitive(EditorInfo)` on every `onStartInput` and cleared on
   `onFinishInput`.

`SensitiveFieldPolicy` fails closed for password input types
(`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`,
`TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`) and for any field with
`IME_FLAG_NO_PERSONALIZED_LEARNING`. Typing still works in those fields; only the research
capture is suppressed, and the keyboard says so on screen.

The keyboard never records key identity or text. `ResearchKeyboardView.onTouchEvent` passes
`key.category.name` into the bridge and nothing else about the key. The character travels a
separate path — `commitKey` writes it to `InputConnection` — and never enters an
`ImeTouchObservation`, which has no field capable of holding it. The consent text makes this
promise to participants; it is kept by the shape of the data type, not by discipline at the
call site.

`pressure` and `size` are device-specific normalized values. They are not calibrated
newtons or square millimetres and are not comparable across device models.

## 11. Worked example: adding `ambient_light.v1`

This collector is **not in the repository**. It is written out in full so every file you must
touch appears exactly once. It follows the `accelerometer.v1` pattern, which is the shortest
correct path for a callback source.

### Step 1 — module

`settings.gradle.kts`, keeping the include list sorted:

```kotlin
include(
    ":app",
    ":collector:accelerometer",
    ":collector:ambient-light",
    ":collector:app-lifecycle",
    // …
)
```

`collector/ambient-light/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cool.linc.androiddatacollector.collector.ambientlight"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":core:collector-api"))
    implementation(project(":core:study-definition"))
    implementation(libs.coroutines.android)
}
```

Do not add a dependency that is not on this list without understanding which invariant in
[section 3](#3-invariants-you-must-not-break) it weakens. Add a module
`src/main/AndroidManifest.xml` only if you need a permission or an Android component; remember
that anything you declare there is merged into the app manifest.

### Step 2 — typed configuration

In `core/study-definition/src/main/kotlin/.../StudyConfiguration.kt`, add a member of the
sealed `CollectorConfiguration` interface. Range checks belong here, not in the codec:

```kotlin
data class AmbientLightConfiguration(
    override val required: Boolean,
    val samplingPeriodUs: Int,
) : CollectorConfiguration {
    override val id: String = ID

    init {
        require(samplingPeriodUs in 200_000..10_000_000) { "Invalid ambient-light sampling period" }
    }

    companion object { const val ID = "ambient_light.v1" }
}
```

### Step 3 — strict codec

Two edits in `StudyConfigurationCodec.kt`. Decode:

```kotlin
AmbientLightConfiguration.ID -> {
    config.requireExactKeys(setOf("sampling_period_us"))
    AmbientLightConfiguration(required, config.requireInt("sampling_period_us"))
}
```

Encode — the `when` in `encodeCollector` is exhaustive over the sealed interface, so the
compiler will not let you forget this half:

```kotlin
is AmbientLightConfiguration -> writer.name("sampling_period_us").value(collector.samplingPeriodUs)
```

Both halves must agree exactly, because `decode` re-encodes and compares bytes.

### Step 4 — access kind

In `CollectorContracts.kt`:

```kotlin
enum class AccessKind {
    // …
    ACCELEROMETER_HARDWARE,
    AMBIENT_LIGHT_HARDWARE,
}
```

Every `when` over `AccessKind` is exhaustive and every module builds with
`allWarningsAsErrors = true`, so adding a value breaks the build in exactly four places until
you handle it:

| File | What to add |
| --- | --- |
| `core/access/.../AccessManager.kt` → `isGranted` | `getDefaultSensor(Sensor.TYPE_LIGHT) != null` |
| `core/access/.../AccessManager.kt` → `settingsIntent` | `null` — there is no settings screen for hardware |
| `app/.../MainActivity.kt` → `requestAccess` | `Unit` — hardware cannot be requested |
| `app/.../CollectorDashboard.kt` → `AccessKind.displayName` | a participant-readable label |

This is the one part of collector registration the compiler enforces for you. Use it.

### Step 5 — the collector

`collector/ambient-light/src/main/kotlin/cool/linc/androiddatacollector/collector/ambientlight/AmbientLightCollector.kt`:

```kotlin
package cool.linc.androiddatacollector.collector.ambientlight

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.SerializedCallbackCollector
import cool.linc.androiddatacollector.core.definition.AmbientLightConfiguration
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.model.EventDraft

class AmbientLightCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = AmbientLightConfiguration.ID,
        payloadSchemaVersion = 1,
        displayName = "Ambient light",
        privacyClass = PrivacyClass.SENSITIVE,
        maximumEncodedEventBytes = 1_024,
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? AmbientLightConfiguration
            ?: throw IllegalArgumentException("Invalid ambient-light configuration")
        return setOf(AccessRequirement(AccessKind.AMBIENT_LIGHT_HARDWARE, typed.required))
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector = AmbientLightCollector(
        applicationContext,
        configuration as? AmbientLightConfiguration
            ?: throw IllegalArgumentException("Invalid ambient-light configuration"),
        context,
    )
}

private class AmbientLightCollector(
    androidContext: Context,
    private val configuration: AmbientLightConfiguration,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY),
    SensorEventListener {
    private val sensorManager = androidContext.getSystemService(SensorManager::class.java)
    private val sensor by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?: throw IllegalStateException("Ambient light hardware is unavailable")
    }
    private var handlerThread: HandlerThread? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT || event.values.isEmpty()) return
        capture {
            EventDraft(
                collectorId = AmbientLightConfiguration.ID,
                payloadSchemaVersion = 1,
                observedTime = context.clocks.now(),
                payloadType = "AMBIENT_LIGHT_SAMPLE",
                fields = mapOf(
                    "source_elapsed_realtime_nanos" to event.timestamp.toString(),
                    "illuminance_lux" to event.values[0].toString(),
                    "accuracy" to event.accuracy.toString(),
                ),
            )
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    override suspend fun registerSource() {
        val thread = HandlerThread("adc-ambient-light").also { it.start() }
        try {
            check(
                sensorManager.registerListener(
                    this,
                    sensor,
                    configuration.samplingPeriodUs,
                    Handler(thread.looper),
                ),
            ) { "Android rejected the ambient light listener" }
            handlerThread = thread
        } catch (failure: Throwable) {
            thread.quitSafely()
            throw failure
        }
    }

    override suspend fun unregisterSource() {
        sensorManager.unregisterListener(this, sensor)
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private companion object {
        const val CHANNEL_CAPACITY = 256
    }
}
```

Points worth copying, in order of how often they are got wrong:

- The `HandlerThread` is started before registration and quit on the failure path, so a
  rejected registration does not leak a thread.
- `registerSource()` throws instead of setting health; the base class turns that into
  `SOURCE_REGISTRATION_FAILED` and the runtime into `COLLECTOR_START_FAILED`.
- Units are in the field name (`illuminance_lux`). An analyst reading the export should not
  have to consult this document to know what a number means.
- Android's own timestamp is preserved alongside `observedTime`.
- No filtering, no smoothing, no derived "is the participant indoors" field.

### Step 6 — register in the app

`app/build.gradle.kts`:

```kotlin
implementation(project(":collector:ambient-light"))
```

`app/src/main/kotlin/cool/linc/androiddatacollector/CollectorApplication.kt`:

```kotlin
val registry = CollectorRegistry(
    listOf(
        AppLifecycleCollectorPlugin(this),
        AccelerometerCollectorPlugin(this),
        AmbientLightCollectorPlugin(this),
        // …
    ),
)
```

This list is the whole allowlist. A study configuration can name only IDs that appear here,
and `CollectorRegistry` throws for anything else. Adding a collector to the codec without
adding it here produces a configuration that verifies and then fails to run — which is the
correct failure direction, but check both.

### Step 7 — tests, example, and disclosure

- Configuration tests in `core/study-definition/src/test/...` covering nominal values, both
  range boundaries, an unknown key, a wrong JSON type, and canonical round-trip. Follow
  [`NetworkUsageConfigurationTest`](../core/study-definition/src/test/kotlin/cool/linc/androiddatacollector/core/definition/NetworkUsageConfigurationTest.kt).
- Collector tests using the fake sink pattern in
  [`SerializedCallbackCollectorTest`](../core/collector-api/src/test/kotlin/cool/linc/androiddatacollector/core/collector/SerializedCallbackCollectorTest.kt):
  admission refusal, storage failure, failed registration, failed unregistration.
- Add the collector to
  [`researcher-tools/examples/demo-study.json`](../researcher-tools/examples/demo-study.json)
  if it should be part of the demo study, then re-canonicalise and re-sign that file into
  `app/src/debug/res/raw/demo_study_envelope.txt`. That envelope is a debug-only resource; the
  release variant ships no demonstration study.
- Update the participant guide, the researcher guide's capability table, and this document.
  A collector whose data is not described to participants must not ship.

## 12. Definition of done

- [ ] Strict configuration tests pass for nominal values, both boundaries, out-of-range
      values, unknown keys, missing keys, wrong types, and canonical round-trip.
- [ ] `required` and optional access both behave as documented, and a blocked collector
      produces no events rather than substitutes.
- [ ] Start, pause, resume, stop, repeated pause cycles, process restart, and mid-study
      permission revocation are all exercised.
- [ ] No event is recorded after the pause boundary, and a token from a previous epoch is
      rejected.
- [ ] Queue full, disk full, and AEAD or key failure all fail closed, and no payload value
      reaches logcat.
- [ ] Every payload field's unit, clock, precision, platform limitation, and sensitivity is
      documented.
- [ ] The release manifest gained no permission and no component beyond what the collector
      genuinely needs. Check the merged manifest, not only your module's.
- [ ] `./gradlew test testDebugUnitTest lintDebug assembleDebug assembleRelease` passes —
      the same command CI runs.
- [ ] The collector has been run on a physical device, not only an emulator. Sensor
      batching, doze, and IME selection behave differently there.

## 13. Known gaps

Stated here rather than discovered later.

- **No architecture test enforces the module boundary.** The rules in
  [section 3](#3-invariants-you-must-not-break) are enforced by the Gradle dependency graph
  and by review. A collector that wrote a file or started an `Activity` would compile and
  pass CI.
- **`maximumEncodedEventBytes` is declared but unread.** No code compares an encoded event
  against it. The enforced limits are `EventDraft`'s 32 fields and 1,024 characters per value.
- **`privacyClass` is declared but unread.** It documents the author's classification and
  drives no behaviour.
- **`displayName` is not shown to participants.** The dashboard lists collectors by ID.
- **A collector module can widen the app's permissions.** Manifest merging means a
  `<uses-permission>` in a collector module lands in the app manifest, and nothing in the
  build fails when it does. Reviewing the merged manifest is the only check. Note that
  `INTERNET` is now declared by the app itself for the upload worker, so a collector that
  used the network would leave no trace in the permission set at all.
- **No collector module has its own tests.** Coverage for collector behaviour currently comes
  from `SerializedCallbackCollectorTest` and `ExperimentRuntimeTest` in the core modules.
