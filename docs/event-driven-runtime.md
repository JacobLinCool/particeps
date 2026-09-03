# Durable event-driven study runtime

Particeps Protocol v1 uses one signed automation program and one durable, ordered runtime log.
Collectors and actuators expose profile-controlled resources; the experiment runtime alone admits
events, reduces conditions, persists decisions, and reconciles side effects. Collector plugins do
not subscribe to one another and no component owns a parallel scheduler or condition epoch.

## Module boundaries

- `core:model` owns wire-neutral event, observation, commit, lifecycle, clock, timer, action,
  resource, and condition-epoch value objects.
- `core:study-definition` owns the exact signed configuration AST and generated collector-profile
  codecs.
- `core:collector-api` owns generated event-source contracts and source batch/flush ports.
- `core:resource-api` owns profile application, verification, suspension, release, and terminal
  failure contracts.
- `core:automation` owns configuration compilation and a pure deterministic reducer.
- `core:experiment-runtime` is the only coordinator of admission, storage commits, resources,
  timers, action outbox delivery, and safety containment.
- `core:storage` persists authenticated `EngineCommit` frames and encrypted snapshots.
- `actuator:traffic-shaping` implements the generic resource contract; its Go data plane lives in
  `native:traffic-shaping`.

## Runtime transaction

Every accepted input is reduced without I/O. The resulting source-observation manifest, contiguous
events, typed state mutations, and successor digest are written in one authenticated commit before
any action or profile side effect. Empty retrospective coverage is represented by a zero-event
coverage record. Ephemeral flows may wake UI or workers but are never replay state.

Lifecycle state enters the reducer only through the coordinator's typed lifecycle input.
`STUDY_STARTED`, `STUDY_RESUMED`, and `STUDY_RUNNING` are resulting audit events, so the generated
registry exposes no condition kinds for them. `study_session_active` is the sole active-session
condition used by continuous resource bindings.

A resource-changing source batch is first placed in the single encrypted pending-input slot. The
coordinator closes admission, suspends applied resources, flushes retrospective sources, and then
commits one boundary batch. `SourceObservation` manifests remain in admitted producer order—causal,
pre-drain, then exact flush—while their referenced `RecordedEvent` ranges and reducer inputs are
sequenced pre-drain/flush first and causal last. Only after that commit may the coordinator apply
and verify the new vector, activate a new condition epoch, resume resources, and reopen admission.

## Action outbox reconciliation

An action invocation is claimable and displayable only while the durable study state is `RUNNING`.
Pause, safety pause, completion, and withdrawal synchronously retract its visible notification and
issue idempotent cancellation for its WorkManager delivery and expiry jobs, without deleting the
durable invocation. Resume asks the runtime to re-arm every still-pending invocation; Android never
reconstructs outbox truth independently.

The availability interval is half-open: an invocation is expired when runtime wall time is greater
than or equal to `expires_at_utc_millis`. Survey expiry always commits `SURVEY_EXPIRED` immediately
before its terminal `ACTION_FAILED(EXPIRED)`, including an invocation that expires while paused and
is first reconciled after resume. Notification expiry commits the terminal action event only.

Workers report only neutral `DELIVERY_FAILED` or `RECONCILIATION_FAILED` results. The runtime reads
the signed intervention's `required` flag: an optional failure remains neutral and the study keeps
running, while a required failure is durably normalized to `REQUIRED_ACTION_FAILED` before the
runtime safety-pauses with `WORK_SCHEDULING_FAILURE`. Side-effect serialization covers only display
and retraction; a worker reports its result outside that lease, so fail-closed cancellation never
waits for the worker that caused it.

## Duration and clock gaps

The signed study duration has one runtime-owned `STUDY_DEADLINE_TIMER`, separate from automation
timers. The runtime commits its same-boot monotonic target before scheduling WorkManager and installs
the same target as the admission gate's exclusive upper bound. A batch observed at the target or
later is therefore rejected even when the wakeup is delayed. The eventual due wake retires the
component and automatically completes the study with `STUDY_DURATION_ELAPSED`; completion never
depends on another collector event.

A wall-clock discontinuity while `RUNNING` is a global discard barrier. Admission closes before
resource suspension, every retrospective cursor is removed without `flushThrough`, condition
latches, keyed presence, windows, and sequences reset, and active retrospective resource
generations restart. A replacement epoch opens only after the complete vector verifies. If the
discontinuity first reveals that the signed duration has elapsed, the same barrier completes the
study without opening an epoch or backfilling the crossed interval.

A reboot first observed while `PAUSED` records a quality gap and discards retrospective cursors.
Trusted UTC may then establish a new boot anchor and a replacement deadline generation. Without
that proof, Resume remains unavailable; Complete and Withdraw remain available because neither
command opens admission. Paused and pre-anchor intervals are never queried or backfilled.

## Safety and participant boundary

Participant pause, terminal lifecycle, access loss, storage failure, and resource failure override
automation. Recovery from an unproven active or transitional state always ends in `PAUSED` and
requires explicit participant resume. The participant UI receives a whitelist-only projection and
never receives automation, profile, cap, package, timer, epoch, digest, or typed diagnostic state.
Researcher authoring and encrypted analysis retain the complete technical record.
