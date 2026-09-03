package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.AutomationEvent
import cool.jacoblin.particeps.core.automation.AutomationReducer
import cool.jacoblin.particeps.core.automation.CompiledAutomationProgram
import cool.jacoblin.particeps.core.automation.DesiredProfile
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.ReducerClock
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.ReductionResult
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerProductionRequest
import cool.jacoblin.particeps.core.automation.TimerProductionResult
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.RegistryClockBasis
import cool.jacoblin.particeps.core.collector.RegistryEmissionAuthority
import cool.jacoblin.particeps.core.collector.RegistrySourceKind
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.collector.protocolEncodedBytes
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.MAX_OBSERVATION_ENCODED_BYTES
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.PendingSourceSubmission
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.StudyTimeline
import cool.jacoblin.particeps.core.model.StudyTimelineAdvance
import cool.jacoblin.particeps.core.model.withComputedDigest
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.PeriodicResourceAuditSource
import cool.jacoblin.particeps.core.resource.ResourceAuditEvidence
import cool.jacoblin.particeps.core.resource.ResourceAuditRemovalReason
import cool.jacoblin.particeps.core.resource.ResourceAuditRequest
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailure
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.requireAppliedMatches
import cool.jacoblin.particeps.core.resource.requireCleanupReleased
import cool.jacoblin.particeps.core.resource.requireInactiveMatches
import cool.jacoblin.particeps.core.resource.requireMatches
import cool.jacoblin.particeps.core.resource.requireReleased
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only mutable coordinator for a signed study. Every durable fact is an [EngineCommit]; flows,
 * callbacks, wakeups and resource health are merely adapters around that commit chain.
 */
class ExperimentRuntime(
    val study: RuntimeStudyIdentity,
    private val store: StudyStore,
    private val program: CompiledAutomationProgram,
    surveyInterventionIds: Set<String>,
    resourceHosts: List<RuntimeResourceHost>,
    private val clocks: ResearchClocks,
    private val scope: CoroutineScope,
    private val zoneId: () -> String,
    private val timerProducer: RuntimeTimerProducer,
    private val timerWakeups: TimerWakeupAdapter = NoOpTimerWakeupAdapter,
    private val actionNotifier: ActionOutboxNotifier = NoOpActionOutboxNotifier,
    private val entropy: RuntimeEntropySource = SecureRuntimeEntropySource(),
    private val reducer: AutomationReducer = AutomationReducer(),
) : EventSink {
    private val initialZoneId = canonicalZoneId(zoneId())
    private val timeline = StudyTimeline(Math.multiplyExact(study.durationSeconds, MILLIS_PER_SECOND))
    private val hosts = resourceHosts.associateBy(RuntimeResourceHost::key).toSortedMap()
    private val interventionRequiredById = program.input.interventions
        .associate { it.id to it.required }
        .toSortedMap()
    private val surveyInterventionIds = surveyInterventionIds.toSortedSet().also { ids ->
        require(ids.all(interventionRequiredById::containsKey)) {
            "Survey intervention identity is not declared by the signed automation program"
        }
    }
    private val mutex = Mutex()
    private val gate = EventAdmissionGate(clocks::now)
    private val initialized = AtomicBoolean(false)
    private val terminalFailures = Channel<ResourceTerminalFailure>(Channel.UNLIMITED)
    private val coordinatedBarriers = Channel<CoordinatedBarrier>(capacity = 1)
    private var terminalJob: Job? = null
    private var barrierJob: Job? = null
    private var document: RuntimeDocument? = null
    private var automationCheckpoint = AutomationCheckpoint()
    private var appliedResources = sortedMapOf<ResourceKey, AppliedResourceState>()
    private var resourceCleanupAttempts = sortedMapOf<ResourceKey, DurableResourceCleanup>()
    private var pendingResourceContainment: ResourceContainment? = null
    private var resourceAuditTimers = sortedMapOf<String, DurableTimer>()
    private var studyDeadlineTimer: DurableTimer? = null
    private var actionInvocations = sortedMapOf<String, DurableActionInvocation>()
    private var latestUploadAcknowledgement: DurableUploadAcknowledgement? = null
    @Volatile private var barrierBuffer: BarrierInputBuffer? = null
    @Volatile private var activeBarrier: CoordinatedBarrier? = null
    private val mutableSnapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = mutableSnapshot.asStateFlow()

    init {
        require(program.input.configurationSha256 == study.configurationSha256) {
            "Compiled automation configuration digest mismatch"
        }
        require(program.input.studyDurationSeconds == study.durationSeconds) {
            "Compiled automation study duration mismatch"
        }
        require(hosts.keys == program.input.resources.map { it.key }.toSet()) {
            "Runtime resource hosts must exactly match the compiled resource set"
        }
        require(interventionRequiredById.size == program.input.interventions.size) {
            "Runtime interventions must have unique identities"
        }
        program.input.resources.forEach { declared ->
            val host = hosts.getValue(declared.key)
            require(host.required == declared.required) { "Resource requiredness mismatch: ${declared.key}" }
            require(host.profiles.mapValues { it.value.expectedSha256.value } == declared.profileDigests) {
                "Resource signed profile digest mismatch: ${declared.key}"
            }
        }
        hosts.values.filter { it.auditSource != null }.forEach { host ->
            val source = requireNotNull(host.auditSource)
            val registry = requireNotNull(ProtocolEventSourceRegistry[source.sourceId.value]) {
                "Unknown resource audit source: ${source.sourceId.value}"
            }
            require(registry.sourceKind == RegistrySourceKind.SYSTEM) { "Resource audit source must be SYSTEM" }
            require(registry.emissionAuthority == RegistryEmissionAuthority.RUNTIME_ONLY) {
                "Resource audit source must be runtime-only"
            }
            require(registry.schemaVersion == source.schemaVersion) { "Resource audit schema mismatch" }
            require(program.resourceBindings.single { it.resource == host.key }.id.length <= 64) {
                "Resource audit timer owner is invalid"
            }
        }
    }

    suspend fun initialize(): RuntimeInitializationResult {
        if (!initialized.compareAndSet(false, true)) {
            return RuntimeInitializationResult.Ready(false, snapshot.value)
        }
        return try {
            mutex.withLock {
                var loaded = store.loadRuntime()
                if (loaded == null) {
                    loaded = RuntimeDocument.initial(
                        experimentId = study.experimentId,
                        configurationId = study.configurationId,
                        configurationSha256 = study.configurationSha256,
                        activityTokenKeyBase64Url = entropy.next(RuntimeEntropyKind.ACTIVITY_TOKEN_KEY),
                        assignedParticipantId = study.assignedParticipantId,
                        participantInstanceId = entropy.next(RuntimeEntropyKind.PARTICIPANT_INSTANCE_UUID),
                    )
                    store.initialize(loaded)
                }
                validateIdentity(loaded)
                document = loaded
                restoreComponents(loaded)
                bindTerminalListeners()
                val pending = store.loadPendingInput()
                val recover = pending != null || loaded.state in RECOVERY_FAIL_CLOSED_STATES
                if (recover) {
                    recoverFailClosedLocked(pending)
                    check(resourceAuditTimers.isEmpty()) { "Recovery retained a resource audit timer" }
                } else {
                    if (loaded.state == ExperimentState.PAUSED) {
                        finalizePausedResourceCleanupLocked(recovery = true)
                        reanchorPausedAcrossBootLocked()
                    }
                    require(resourceAuditTimers.isEmpty()) { "Inactive runtime retains a resource audit timer" }
                }
                publishSnapshot()
                retractInactiveActionsLocked()
                startTerminalConsumer()
                startBarrierConsumer()
                RuntimeInitializationResult.Ready(recover, snapshot.value)
            }
        } catch (failure: Throwable) {
            gate.forceClose()
            RuntimeInitializationResult.Failed(SafetyPauseReason.STORAGE_FAILURE, failure)
        }
    }

    suspend fun markConfigurationVerified(): RuntimeCommandResult = advanceSetup(
        expected = ExperimentState.IMPORTED,
        target = ExperimentState.CONFIG_VERIFIED,
    )

    suspend fun beginConsentReview(): RuntimeCommandResult = advanceSetup(
        expected = ExperimentState.CONFIG_VERIFIED,
        target = ExperimentState.CONSENT_PENDING,
    )

    suspend fun acceptConsent(): RuntimeCommandResult = advanceSetup(
        expected = ExperimentState.CONSENT_PENDING,
        target = ExperimentState.ACCESS_SETUP,
    )

    /** Enrollment/setup owns platform preconditions. This is the only bridge to runtime READY. */
    suspend fun markReady(): RuntimeCommandResult = command {
        val current = requireDocument()
        if (current.state != ExperimentState.ACCESS_SETUP) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        appendCommitLocked(
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            state = ExperimentState.READY,
            epoch = null,
            clock = current.clockCheckpoint,
            checkpoint = automationCheckpoint,
        )
        RuntimeCommandResult.Success
    }

    private suspend fun advanceSetup(
        expected: ExperimentState,
        target: ExperimentState,
    ): RuntimeCommandResult = command {
        val current = requireDocument()
        if (current.state != expected) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        appendCommitLocked(
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            state = target,
            epoch = null,
            clock = current.clockCheckpoint,
            checkpoint = automationCheckpoint,
        )
        RuntimeCommandResult.Success
    }

    suspend fun start(): RuntimeCommandResult = activate(from = ExperimentState.READY, resumed = false)

    suspend fun resume(): RuntimeCommandResult = activate(from = ExperimentState.PAUSED, resumed = true)

    suspend fun pause(): RuntimeCommandResult = stopSession(
        terminalState = ExperimentState.PAUSED,
        requestEvent = "STUDY_PAUSE_REQUESTED",
        resultEvent = "STUDY_PAUSED",
        transitionReason = "PARTICIPANT_PAUSE",
        epochReason = "PARTICIPANT_PAUSED",
    )

    suspend fun complete(): RuntimeCommandResult = stopSession(
        terminalState = ExperimentState.COMPLETED,
        requestEvent = "STUDY_COMPLETE_REQUESTED",
        resultEvent = "STUDY_COMPLETED",
        transitionReason = "PARTICIPANT_COMPLETE",
        epochReason = "STUDY_COMPLETED",
    )

    suspend fun withdraw(): RuntimeCommandResult = stopSession(
        terminalState = ExperimentState.WITHDRAWN,
        requestEvent = "STUDY_WITHDRAW_REQUESTED",
        resultEvent = "STUDY_WITHDRAWN",
        transitionReason = "PARTICIPANT_WITHDRAW",
        epochReason = "STUDY_WITHDRAWN",
    )

    suspend fun safetyPause(reason: SafetyPauseReason): RuntimeCommandResult = command {
        safetyPauseLocked(reason, causeSequence = null)
        RuntimeCommandResult.FailedClosed(reason)
    }

    override fun captureToken(): AdmissionToken? = gate.capture()

    override fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken? =
        gate.captureBarrierFlush(boundary)

    override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult =
        submit(token, SourceSubmission.from(batch))

    override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult =
        submit(token, SourceSubmission.from(advance))

    suspend fun onTimerDue(
        timerId: String,
        generation: ULong,
    ): RuntimeCommandResult = command {
        val current = requireDocument()
        val deadlineTimer = studyDeadlineTimer
        if (deadlineTimer?.id == timerId) {
            if (deadlineTimer.generation != generation) {
                return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION)
            }
            if (current.state !in setOf(ExperimentState.RUNNING, ExperimentState.PAUSED)) {
                return@command if (current.state in TERMINAL_STATES) {
                    RuntimeCommandResult.Success
                } else {
                    RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
                }
            }
            val now = clocks.now()
            val clock = advanceClock(current, now)
            if (!timerIsDue(deadlineTimer, reducerClock(clock)) || !timeline.isElapsed(clock)) {
                return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_DUE)
            }
            return@command stopSessionLocked(
                terminalState = ExperimentState.COMPLETED,
                requestEvent = "STUDY_COMPLETE_REQUESTED",
                resultEvent = "STUDY_COMPLETED",
                transitionReason = "STUDY_DURATION_ELAPSED",
                epochReason = "STUDY_COMPLETED",
                operationNow = now,
                collectionBoundary = deadlineCollectionBoundary(deadlineTimer),
                causalEvents = listOf(RuntimeEventFactory.timerDue(deadlineTimer, now)),
                deadlineRetirementReason = "FIRED",
                inputKind = EngineInputKind.TIMER_WAKE,
            )
        }
        val resourceAuditTimer = resourceAuditTimers[timerId]
        val automationTimer = automationCheckpoint.timers[timerId]
        if (current.state != ExperimentState.RUNNING) {
            if (resourceAuditTimer == null && automationTimer == null) return@command RuntimeCommandResult.Success
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        resourceAuditTimer?.let { auditTimer ->
            return@command onResourceAuditTimerDueLocked(auditTimer, generation)
        }
        val timer = automationTimer ?: return@command RuntimeCommandResult.Success
        if (timer.generation != generation) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION)
        }
        val now = clocks.now()
        val clock = advanceClock(current, now)
        val reducerClock = reducerClock(clock)
        if (!timerIsDue(timer, reducerClock)) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_DUE)
        }
        val logicalDue = RuntimeEventFactory.timerLogicalTarget(timer)
        val input = ReducerInput.TimerDue(
            sequenceNumber = automationCheckpoint.evaluatedThroughSequence + 1,
            clock = reducerClock,
            timerId = timer.id,
            automationId = timer.automationId,
            generation = timer.generation,
            causalSequence = timer.causalSequence,
            target = timer.target,
            logicalDue = logicalDue,
        )
        val reduction = reducer.reduceBatch(program, automationCheckpoint, listOf(input))
        val dueEvent = RuntimeEventFactory.timerDue(timer, now)
        if (reduction.resourceChanges.isNotEmpty()) {
            resourceBarrierLocked(
                inputKind = EngineInputKind.TIMER_WAKE,
                causalReducerInput = { sequence -> input.copy(sequenceNumber = sequence) },
                causalEvents = listOf(dueEvent),
                clock = clock,
            )
        } else {
            val effects = appendReductionLocked(
                inputKind = EngineInputKind.TIMER_WAKE,
                reduction = reduction,
                eventDrafts = listOf(dueEvent),
                clock = clock,
                timerRetirementReason = "FIRED",
            )
            performPostCommitEffectsLocked(effects)
        }
        RuntimeCommandResult.Success
    }

    /** Durable TIME_SET/TIMEZONE_CHANGE input. Crossed retrospective wall intervals are discarded. */
    suspend fun onClockDiscontinuity(): RuntimeCommandResult = command {
        val current = requireDocument()
        if (current.state !in setOf(ExperimentState.RUNNING, ExperimentState.PAUSED)) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val oldClock = current.clockCheckpoint
            ?: return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        val now = clocks.now()
        if (
            oldClock.anchor.bootSessionId != now.bootSessionId ||
            now.elapsedRealtimeNanos < oldClock.anchor.elapsedRealtimeNanos
        ) {
            safetyPauseLocked(SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN, null)
            return@command RuntimeCommandResult.FailedClosed(SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN)
        }
        val advanced = timeline.advance(
            oldClock.copy(deadlineUtcTrusted = false),
            current.state,
            now,
            clocks.trustedUtcMillis(),
        ) as? StudyTimelineAdvance.Advanced
            ?: return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        val clock = advanced.checkpoint.copy(zoneId = canonicalZoneId(zoneId()))
        if (current.state == ExperimentState.RUNNING) {
            rotateForWallClockGapLocked(current, now, clock)
        } else {
            commitPausedWallClockGapLocked(current, now, clock)
        }
        RuntimeCommandResult.Success
    }

    private suspend fun commitPausedWallClockGapLocked(
        current: RuntimeDocument,
        now: ResearchTime,
        clock: StudyClockCheckpoint,
    ) {
        val input = ReducerInput.ClockDiscontinuity(
            automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            emptySet(),
        )
        val reduction = reducer.reduceBatch(program, automationCheckpoint, listOf(input))
        val deadlineUpdate = if (timeline.isElapsed(clock)) DeadlineTimerUpdate.EMPTY else {
            reconcileStudyDeadlineTimer(clock, reduction.checkpoint.evaluatedThroughSequence, "QUALITY_GAP_RESET")
        }
        val effects = appendReductionLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            reduction = reduction,
            eventDrafts = listOf(
                RuntimeEventFactory.qualityGap(
                    EventSourceId("timer.v1"),
                    SourceQualityGapReason.WALL_CLOCK_CHANGED,
                    now,
                ),
            ) + deadlineUpdate.events,
            state = ExperimentState.PAUSED,
            epoch = null,
            clock = clock,
            extraMutations = deadlineUpdate.mutations,
            timerRetirementReason = "QUALITY_GAP_RESET",
            sourceCheckpoints = dropRetrospectiveSourceCheckpoints(current.sourceCheckpoints),
        )
        performPostCommitEffectsLocked(effects + deadlineUpdate.effects)
        if (timeline.isElapsed(clock)) {
            completePausedAtDeadlineLocked(now)
        }
    }

    private suspend fun rotateForWallClockGapLocked(
        current: RuntimeDocument,
        now: ResearchTime,
        clock: StudyClockCheckpoint,
    ) {
        if (timeline.isElapsed(clock)) {
            completeRunningAtDeadlineAfterWallClockGapLocked(current, now, clock)
            return
        }
        val epoch = requireNotNull(current.activeConditionEpoch)
        val restartKeys = activeRetrospectiveResourceKeys()
        gate.forceClose()
        suspendAppliedResourcesLocked(now)
        val input = ReducerInput.ClockDiscontinuity(
            automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            restartKeys,
        )
        val reduction = reducer.reduceBatch(program, automationCheckpoint, listOf(input))
        val oldVector = currentAppliedVector()
        val deactivationAudit = deactivateResourceAuditsLocked(
            epoch,
            oldVector,
            now,
            ResourceAuditRemovalReason.PROFILE_REPLACED,
        )
        val deadlineUpdate = reconcileStudyDeadlineTimer(
            clock,
            reduction.checkpoint.evaluatedThroughSequence,
            "QUALITY_GAP_RESET",
        )
        val firstEffects = appendReductionLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            reduction = reduction,
            eventDrafts = listOf(
                RuntimeEventFactory.qualityGap(
                    EventSourceId("timer.v1"),
                    SourceQualityGapReason.WALL_CLOCK_CHANGED,
                    now,
                ),
            ) + deadlineUpdate.events + deactivationAudit.events + listOf(
                RuntimeEventFactory.epochDeactivated(
                    epoch,
                    oldVector,
                    "RESOURCE_VECTOR_CHANGED",
                    now,
                ),
            ),
            state = ExperimentState.RUNNING,
            epoch = null,
            eventConditionEpochId = epoch.id,
            clock = clock,
            extraMutations = deadlineUpdate.mutations + deactivationAudit.mutations,
            timerRetirementReason = "QUALITY_GAP_RESET",
            sourceCheckpoints = dropRetrospectiveSourceCheckpoints(current.sourceCheckpoints),
        )
        val vector = try {
            applyDesiredVectorLocked(reduction.checkpoint.desiredResources, "wall-clock-gap-${current.nextCommitSequence}")
        } catch (_: RequiredResourceFailure) {
            safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return
        }
        val activatedAt = clocks.now()
        val activatedClock = advanceClock(requireDocument(), activatedAt)
        val newEpoch = newEpoch(vector, activatedAt)
        val activationAudit = activateResourceAuditsLocked(newEpoch, vector, activatedAt)
        val secondEffects = appendCommitLocked(
            inputKind = EngineInputKind.RESOURCE_RESULT,
            checkpoint = automationCheckpoint,
            eventDrafts = listOf(
                RuntimeEventFactory.epochActivated(
                    newEpoch,
                    vector,
                    "RESOURCE_VECTOR_CHANGED",
                    activatedAt,
                ),
            ) + activationAudit.events,
            state = ExperimentState.RUNNING,
            epoch = newEpoch,
            clock = activatedClock,
            extraMutations = vector.resources.map(::upsertResource) + activationAudit.mutations,
        )
        resumeAppliedVectorLocked(vector)
        openAdmission(newEpoch.id, activatedClock)
        notifyAdmissionOpenedLocked(vector)
        performPostCommitEffectsLocked(
            firstEffects + deadlineUpdate.effects + deactivationAudit.effects + secondEffects + activationAudit.effects,
        )
    }

    /**
     * A discontinuity that is first observed after the signed deadline cannot be retrospectively
     * split at that deadline. Close admission synchronously, discard every retrospective cursor,
     * and complete from durable reducer inputs without asking collectors to manufacture a flush.
     */
    private suspend fun completeRunningAtDeadlineAfterWallClockGapLocked(
        current: RuntimeDocument,
        now: ResearchTime,
        clock: StudyClockCheckpoint,
    ) {
        val timer = requireNotNull(studyDeadlineTimer) { "Elapsed running study has no durable deadline" }
        val epoch = requireNotNull(current.activeConditionEpoch) { "Running study has no condition epoch" }
        val boundary = deadlineCollectionBoundary(timer)
        val restartKeys = activeRetrospectiveResourceKeys()
        val oldVector = currentAppliedVector()

        gate.forceClose()
        suspendAppliedResourcesLocked(now)

        val reducerClock = reducerClock(clock)
        val reduction = reducer.reduceBatch(
            program,
            automationCheckpoint,
            listOf(
                ReducerInput.ClockDiscontinuity(
                    automationCheckpoint.evaluatedThroughSequence + 1,
                    reducerClock,
                    restartKeys,
                ),
                ReducerInput.Lifecycle(
                    automationCheckpoint.evaluatedThroughSequence + 2,
                    reducerClock,
                    StudySessionState.PAUSING,
                ),
            ),
        )
        val commandId = commandId("study-duration-elapsed", current.nextCommitSequence)
        val retirement = retireStudyDeadlineTimer(now, "FIRED")
        val resourceAudit = deactivateResourceAuditsLocked(
            epoch,
            oldVector,
            boundary,
            ResourceAuditRemovalReason.STUDY_COMPLETED,
        )
        val firstEffects = appendReductionLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            reduction = reduction,
            eventDrafts = listOf(
                RuntimeEventFactory.qualityGap(
                    EventSourceId("timer.v1"),
                    SourceQualityGapReason.WALL_CLOCK_CHANGED,
                    now,
                ),
                RuntimeEventFactory.timerDue(timer, now),
            ) + retirement.events + listOf(
                RuntimeEventFactory.lifecycle(
                    "STUDY_COMPLETE_REQUESTED",
                    commandId,
                    ExperimentState.RUNNING,
                    ExperimentState.PAUSING,
                    "STUDY_DURATION_ELAPSED",
                    now,
                ),
            ) + resourceAudit.events + listOf(
                RuntimeEventFactory.epochDeactivated(
                    epoch,
                    oldVector,
                    "STUDY_COMPLETED",
                    boundary,
                ),
            ),
            state = ExperimentState.PAUSING,
            epoch = null,
            eventConditionEpochId = epoch.id,
            clock = clock,
            extraMutations = retirement.mutations + resourceAudit.mutations,
            timerRetirementReason = "LIFECYCLE_ENDED",
            sourceCheckpoints = dropRetrospectiveSourceCheckpoints(current.sourceCheckpoints),
        )

        releaseAllResourcesLocked()
        val completedAt = clocks.now()
        val completedClock = advanceClock(requireDocument(), completedAt)
        val completed = reducer.reduceBatch(
            program,
            automationCheckpoint,
            listOf(
                ReducerInput.Lifecycle(
                    automationCheckpoint.evaluatedThroughSequence + 1,
                    reducerClock(completedClock),
                    StudySessionState.COMPLETED,
                ),
            ),
        )
        val finalEffects = appendReductionLocked(
            inputKind = EngineInputKind.RESOURCE_RESULT,
            reduction = completed,
            eventDrafts = listOf(
                RuntimeEventFactory.lifecycle(
                    "STUDY_COMPLETED",
                    commandId,
                    ExperimentState.PAUSING,
                    ExperimentState.COMPLETED,
                    "STUDY_DURATION_ELAPSED",
                    completedAt,
                ),
            ),
            state = ExperimentState.COMPLETED,
            epoch = null,
            clock = completedClock,
            extraMutations = inactiveResourceMutations(completed.checkpoint.desiredResources),
        )
        performPostCommitEffectsLocked(
            firstEffects + retirement.effects + resourceAudit.effects + finalEffects,
        )
    }

    private suspend fun suspendAppliedResourcesLocked(boundary: ResearchTime) {
        currentAppliedVector().resources.filter { it.status == AppliedResourceStatus.APPLIED }
            .sortedBy(AppliedResourceState::key)
            .forEach { applied ->
                val desired = desiredState(applied)
                requireNotNull(hosts.getValue(applied.key).actuator)
                    .suspendAt(desired, boundary)
                    .requireMatches(desired, boundary)
            }
    }

    private fun activeRetrospectiveResourceKeys(): Set<ResourceKey> = appliedResources.values
        .asSequence()
        .filter { it.status == AppliedResourceStatus.APPLIED && it.key.kind == ResourceKind.COLLECTOR }
        .map(AppliedResourceState::key)
        .filter { key -> ProtocolEventSourceRegistry[key.id]?.isRetrospective == true }
        .toSortedSet()

    suspend fun pendingActions(): List<DurableActionInvocation> = mutex.withLock {
        checkInitialized()
        actionInvocations.values.filter { it.state in PENDING_ACTION_STATES }
    }

    /** Replays only the platform adapter work implied by durable action components and state. */
    suspend fun reconcileActions(): RuntimeCommandResult = command {
        if (requireDocument().state == ExperimentState.RUNNING) {
            performPostCommitEffectsLocked(PostCommitEffects(actionsReady = pendingActionIdsLocked()))
        } else {
            retractInactiveActionsLocked()
        }
        RuntimeCommandResult.Success
    }

    /** Durable timer truth for process recovery/re-arm; wakeup adapters never own timer state. */
    suspend fun pendingTimers(): List<DurableTimer> = mutex.withLock {
        checkInitialized()
        val bootId = clocks.now().bootSessionId
        val wakeableDeadline = studyDeadlineTimer?.takeIf { timer ->
            (timer.target as? TimerTarget.SameBootMonotonic)?.bootSessionId == bootId
        }
        (automationCheckpoint.timers.values + resourceAuditTimers.values + listOfNotNull(wakeableDeadline)).sortedWith(
            compareBy<DurableTimer>({ it.automationId }, { it.id }),
        )
    }

    suspend fun claimAction(actionId: String): DurableActionInvocation? = mutex.withLock {
        checkInitialized()
        val currentDocument = requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) return@withLock null
        val current = actionInvocations[actionId] ?: return@withLock null
        if (current.state == RuntimeActionState.SUCCEEDED || current.state == RuntimeActionState.FAILED) {
            return@withLock null
        }
        val now = clocks.now()
        if (now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            expireActionLocked(current, now)
            return@withLock null
        }
        if (current.state == RuntimeActionState.CLAIMED || current.state == RuntimeActionState.OPENED) {
            return@withLock current
        }
        val claimed = current.copy(state = RuntimeActionState.CLAIMED)
        appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = automationCheckpoint,
            extraMutations = listOf(upsertAction(claimed)),
        )
        claimed
    }

    suspend fun recordActionResult(
        actionId: String,
        succeeded: Boolean,
        failure: ActionExecutionFailure? = null,
    ): RuntimeCommandResult = command {
        val current = actionInvocations[actionId]
            ?: return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.state == RuntimeActionState.SUCCEEDED || current.state == RuntimeActionState.FAILED) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        require(succeeded == (failure == null)) { "Failed action results require one typed reason" }
        val now = clocks.now()
        if (!succeeded && failure == ActionExecutionFailure.EXPIRED) {
            if (now.wallTimeUtcMillis < current.expiresAtUtcMillis) {
                return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
            }
            expireActionLocked(current, now)
        } else {
            recordActionResultLocked(current, succeeded, failure, now)
        }
    }

    private suspend fun recordActionResultLocked(
        current: DurableActionInvocation,
        succeeded: Boolean,
        reportedFailure: ActionExecutionFailure?,
        now: ResearchTime,
    ): RuntimeCommandResult {
        require(succeeded == (reportedFailure == null)) { "Failed action results require one typed reason" }
        require(reportedFailure != ActionExecutionFailure.EXPIRED) {
            "Availability expiry must use the centralized expiry transition"
        }
        val requiredDeliveryFailure = !succeeded &&
            reportedFailure in REQUIRED_DELIVERY_FAILURES &&
            interventionRequiredById.getValue(current.interventionId)
        val durableFailure = if (requiredDeliveryFailure) {
            ActionExecutionFailure.REQUIRED_ACTION_FAILED
        } else {
            reportedFailure
        }
        val updated = current.copy(
            state = if (succeeded) RuntimeActionState.SUCCEEDED else RuntimeActionState.FAILED,
            failureReason = durableFailure?.name,
        )
        appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = automationCheckpoint,
            eventDrafts = listOf(RuntimeEventFactory.actionResult(current, succeeded, durableFailure, now)),
            extraMutations = listOf(upsertAction(updated)),
            clock = advanceClock(requireDocument(), now),
        )
        if (!requiredDeliveryFailure) return RuntimeCommandResult.Success

        safetyPauseLocked(SafetyPauseReason.WORK_SCHEDULING_FAILURE, current.causalSequence)
        return RuntimeCommandResult.FailedClosed(SafetyPauseReason.WORK_SCHEDULING_FAILURE)
    }

    suspend fun openSurvey(actionId: String, interventionId: String): RuntimeCommandResult = command {
        val currentDocument = requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val current = actionInvocations[actionId]
            ?: return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state == RuntimeActionState.OPENED) return@command RuntimeCommandResult.Success
        if (current.state in TERMINAL_ACTION_STATES) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        val now = clocks.now()
        if (now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            expireActionLocked(current, now)
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_EXPIRED)
        }
        val updated = current.copy(state = RuntimeActionState.OPENED, openedAt = now)
        appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = automationCheckpoint,
            eventDrafts = listOf(RuntimeEventFactory.surveyOpened(updated, now)),
            extraMutations = listOf(upsertAction(updated)),
            clock = advanceClock(currentDocument, now),
        )
        RuntimeCommandResult.Success
    }

    suspend fun submitSurvey(
        actionId: String,
        interventionId: String,
        surveyId: String,
        answersJson: String,
    ): RuntimeCommandResult = command {
        val currentDocument = requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val current = actionInvocations[actionId]
            ?: return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state in TERMINAL_ACTION_STATES) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        if (current.state != RuntimeActionState.OPENED) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_NOT_OPEN)
        }
        val now = clocks.now()
        if (now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            expireActionLocked(current, now)
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_EXPIRED)
        }
        val updated = current.copy(state = RuntimeActionState.SUCCEEDED, failureReason = null)
        appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = automationCheckpoint,
            eventDrafts = listOf(
                RuntimeEventFactory.surveySubmitted(current, surveyId, answersJson, now),
                RuntimeEventFactory.actionResult(current, succeeded = true, failure = null, now = now),
            ),
            extraMutations = listOf(upsertAction(updated)),
            clock = advanceClock(currentDocument, now),
        )
        RuntimeCommandResult.Success
    }

    suspend fun dismissSurvey(actionId: String, interventionId: String): RuntimeCommandResult = command {
        val current = actionInvocations[actionId]
            ?: return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state != RuntimeActionState.OPENED) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_NOT_OPEN)
        }
        // A dismissal is intentionally non-terminal. The signed availability window remains the
        // durable authority and the same action ID may reconcile/reopen without a second event.
        RuntimeCommandResult.Success
    }

    suspend fun expireSurvey(actionId: String, interventionId: String): RuntimeCommandResult = command {
        val currentDocument = requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val current = actionInvocations[actionId]
            ?: return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.interventionId !in surveyInterventionIds) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state in TERMINAL_ACTION_STATES) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        val now = clocks.now()
        if (now.wallTimeUtcMillis < current.expiresAtUtcMillis) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        expireActionLocked(current, now)
    }

    private suspend fun expireActionLocked(
        current: DurableActionInvocation,
        now: ResearchTime,
    ): RuntimeCommandResult {
        require(now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            "Availability expiry cannot precede its durable deadline"
        }
        require(current.state !in TERMINAL_ACTION_STATES) { "Terminal action cannot expire again" }
        val updated = current.copy(
            state = RuntimeActionState.FAILED,
            failureReason = ActionExecutionFailure.EXPIRED.name,
        )
        val eventDrafts = buildList {
            if (current.interventionId in surveyInterventionIds) {
                add(RuntimeEventFactory.surveyExpired(current, now))
            }
            add(
                RuntimeEventFactory.actionResult(
                    current,
                    succeeded = false,
                    failure = ActionExecutionFailure.EXPIRED,
                    now = now,
                ),
            )
        }
        val currentDocument = requireDocument()
        appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = automationCheckpoint,
            eventDrafts = eventDrafts,
            extraMutations = listOf(upsertAction(updated)),
            clock = currentDocument.clockCheckpoint?.let { advanceClock(currentDocument, now) },
        )
        return RuntimeCommandResult.Success
    }

    suspend fun acknowledgeUpload(
        bundleId: String,
        firstCommit: Long,
        throughCommit: Long,
        bundleSha256: String,
    ): RuntimeCommandResult = command {
        val current = requireDocument()
        val prior = latestUploadAcknowledgement
        if (
            throughCommit == current.uploadedThroughCommit &&
            prior?.bundleId == bundleId &&
            prior.firstCommit == firstCommit &&
            prior.throughCommit == throughCommit &&
            prior.bundleSha256 == bundleSha256
        ) {
            return@command RuntimeCommandResult.Success
        }
        if (
            firstCommit != current.uploadedThroughCommit + 1 ||
            throughCommit !in firstCommit..current.revision
        ) {
            return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.UPLOAD_RECEIPT_MISMATCH)
        }
        val now = clocks.now()
        val acknowledgement = runCatching {
            DurableUploadAcknowledgement(bundleId, firstCommit, throughCommit, bundleSha256, now)
        }.getOrNull() ?: return@command RuntimeCommandResult.Rejected(
            RuntimeCommandRejection.UPLOAD_RECEIPT_MISMATCH,
        )
        appendCommitLocked(
            inputKind = EngineInputKind.UPLOAD_ACKNOWLEDGEMENT,
            checkpoint = automationCheckpoint,
            clock = current.clockCheckpoint?.let { advanceClock(current, now) },
            uploadedThroughCommit = throughCommit,
            extraMutations = listOf(upsertUploadAcknowledgement(acknowledgement)),
        )
        RuntimeCommandResult.Success
    }

    fun close() {
        gate.forceClose()
        terminalJob?.cancel()
        barrierJob?.cancel()
        terminalFailures.close()
        coordinatedBarriers.close()
        val abandonedBarrier = activeBarrier
        activeBarrier = null
        abandonedBarrier?.completion?.complete(Unit)
        hosts.values.forEach { it.actuator?.setTerminalFailureListener(null) }
    }

    private suspend fun submit(token: AdmissionToken, submission: SourceSubmission): EmitBatchResult {
        val observedTimes = submission.events.map(EventDraft::observedTime)
        while (true) {
            when (val decision = gate.classify(token, observedTimes)) {
                AdmissionDecision.Rejected -> return EmitBatchResult.RejectedByAdmissionGate
                is AdmissionDecision.PreDrain,
                is AdmissionDecision.BoundaryFlush,
                -> return barrierBuffer?.offer(token, submission) ?: EmitBatchResult.RejectedByAdmissionGate
                is AdmissionDecision.Active -> {
                    val lockOwner = Any()
                    var acquired = false
                    while (!decision.drainSignal.isCompleted) {
                        if (mutex.tryLock(lockOwner)) {
                            acquired = true
                            break
                        }
                        delay(ADMISSION_LOCK_RETRY_MILLIS)
                    }
                    if (!acquired) continue
                    try {
                        when (val lockedDecision = gate.classify(token, observedTimes)) {
                            AdmissionDecision.Rejected -> return EmitBatchResult.RejectedByAdmissionGate
                            is AdmissionDecision.PreDrain,
                            is AdmissionDecision.BoundaryFlush,
                            -> return barrierBuffer?.offer(token, submission)
                                ?: EmitBatchResult.RejectedByAdmissionGate
                            is AdmissionDecision.Active -> return processActiveSubmissionLocked(
                                submission,
                                lockedDecision.conditionEpochId,
                            )
                        }
                    } finally {
                        mutex.unlock(lockOwner)
                    }
                }
            }
        }
    }

    private suspend fun processActiveSubmissionLocked(
        submission: SourceSubmission,
        epochId: ConditionEpochId,
    ): EmitBatchResult {
        val current = requireDocument()
        if (current.state != ExperimentState.RUNNING || current.activeConditionEpoch?.id != epochId) {
            return EmitBatchResult.RejectedByAdmissionGate
        }
        val now = clocks.now()
        val clock = try {
            advanceClock(current, now)
        } catch (_: ClockDiscontinuity) {
            gate.forceClose()
            enqueueBarrier(FailClosedBarrier(SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN, null))
            return EmitBatchResult.SourceQualityGap(SourceQualityGapReason.CLOCK_DISCONTINUITY)
        }
        val prepared = try {
            prepareSources(
                document = current,
                submissions = listOf(submission.withKind(ObservationAdmissionKind.NORMAL)),
                conditionEpochId = epochId,
                startingCheckpoints = current.sourceCheckpoints,
            )
        } catch (gap: SourceGap) {
            commitQualityGapLocked(submission.sourceId, gap.reason, clock)
            return EmitBatchResult.SourceQualityGap(gap.reason)
        } catch (_: IllegalArgumentException) {
            return EmitBatchResult.ContractViolation
        }
        val reduction = reduceRecordedEvents(prepared.events, clock, automationCheckpoint)
        val causalObservation = prepared.observations.single()
        if (reduction.resourceChanges.isNotEmpty()) {
            require(submission.events.isNotEmpty()) { "Coverage-only input cannot change a resource" }
            val pending = PendingEngineInput(
                conditionEpochId = epochId,
                submissions = listOf(submission.withKind(ObservationAdmissionKind.NORMAL).toPending()),
                stagedAt = now,
                encodedSha256 = ZERO_DIGEST,
            ).withComputedDigest()
            try {
                store.stagePendingInput(pending)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                gate.forceClose()
                val recovered = runCatching { store.loadPendingInput() }.getOrNull()
                enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, recovered))
                return if (recovered == pending) accepted(causalObservation) else EmitBatchResult.StorageFailure
            }
            try {
                val boundary = now
                val buffer = BarrierInputBuffer(
                    prepared.nextObservationSequence,
                    current.nextEventSequence,
                    epochId,
                    prepared.sourceCheckpoints,
                    now,
                    pending,
                )
                barrierBuffer = buffer
                val drainToken = gate.beginDrain(boundary)
                val request = StagedSourceBarrier(
                    causal = submission,
                    inputKind = EngineInputKind.SOURCE_OBSERVATION,
                    clock = clock,
                    pending = pending,
                    boundary = boundary,
                    drainToken = drainToken,
                    buffer = buffer,
                )
                check(enqueueBarrier(request)) { "The resource barrier coordinator is unavailable" }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                gate.forceClose()
                barrierBuffer = null
                enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, pending))
            }
            return accepted(causalObservation)
        }

        val effects = try {
            appendReductionLocked(
                    inputKind = EngineInputKind.SOURCE_OBSERVATION,
                    reduction = reduction,
                    prepared = prepared,
                    clock = clock,
                )
        } catch (_: Throwable) {
            gate.forceClose()
            enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, null))
            return EmitBatchResult.StorageFailure
        }
        try {
            performPostCommitEffectsLocked(effects)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            gate.forceClose()
            enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, null))
        }
        return accepted(causalObservation)
    }

    private suspend fun activate(from: ExperimentState, resumed: Boolean): RuntimeCommandResult = command {
        var current = requireDocument()
        if (current.state != from) return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        if (from == ExperimentState.PAUSED) {
            finalizePausedResourceCleanupLocked(recovery = true)
            if (!reanchorPausedAcrossBootLocked()) {
                return@command RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
            }
            current = requireDocument()
            if (current.state == ExperimentState.COMPLETED) return@command RuntimeCommandResult.Success
            retractInactiveActionsLocked()
        }
        val now = clocks.now()
        val clock = if (current.clockCheckpoint == null) initialClock(now) else advanceClock(current, now)
        val activatingInput = ReducerInput.Lifecycle(
            automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            StudySessionState.ACTIVATING,
        )
        val activating = reducer.reduceBatch(program, automationCheckpoint, listOf(activatingInput))
        val commandId = commandId(if (resumed) "resume" else "start", current.nextCommitSequence)
        val started = RuntimeEventFactory.lifecycle(
            type = if (resumed) "STUDY_RESUMED" else "STUDY_STARTED",
            commandId = commandId,
            previousState = current.state,
            currentState = ExperimentState.ACTIVATING,
            transitionReason = if (resumed) "PARTICIPANT_RESUME" else "STUDY_START",
            now = now,
        )
        val deadlineUpdate = reconcileStudyDeadlineTimer(
            clock,
            activating.checkpoint.evaluatedThroughSequence,
            "CLOCK_REANCHORED",
        )
        val activationEffects = appendReductionLocked(
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            reduction = activating,
            eventDrafts = listOf(started) + deadlineUpdate.events,
            state = ExperimentState.ACTIVATING,
            clock = clock,
            extraMutations = deadlineUpdate.mutations,
        )
        performPostCommitEffectsLocked(activationEffects + deadlineUpdate.effects)
        val vector = try {
            applyDesiredVectorLocked(activating.checkpoint.desiredResources, commandId)
        } catch (_: RequiredResourceFailure) {
            safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return@command RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        }
        val runningNow = clocks.now()
        val runningClock = advanceClock(requireDocument(), runningNow)
        if (timeline.isElapsed(runningClock)) {
            return@command try {
                completeActivatingAtDeadlineLocked(vector, runningNow, runningClock)
                RuntimeCommandResult.Success
            } catch (_: RequiredResourceFailure) {
                safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
                RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
            }
        }
        val runningInput = ReducerInput.Lifecycle(
            automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(runningClock),
            StudySessionState.RUNNING,
        )
        val running = reducer.reduceBatch(program, automationCheckpoint, listOf(runningInput))
        check(running.resourceChanges.isEmpty()) { "ACTIVATING to RUNNING changed the resource vector" }
        val epoch = newEpoch(vector, runningNow)
        val runningEvent = RuntimeEventFactory.lifecycle(
            "STUDY_RUNNING",
            commandId,
            ExperimentState.ACTIVATING,
            ExperimentState.RUNNING,
            "ACTIVATION_CONFIRMED",
            runningNow,
        )
        val resourceAudit = activateResourceAuditsLocked(epoch, vector, runningNow)
        val effects = appendReductionLocked(
            inputKind = EngineInputKind.RESOURCE_RESULT,
            reduction = running,
            eventDrafts = listOf(
                RuntimeEventFactory.epochActivated(
                    epoch,
                    vector,
                    if (resumed) "PARTICIPANT_RESUME" else "INITIAL_START",
                    runningNow,
                ),
            ) + resourceAudit.events + listOf(
                runningEvent,
            ),
            state = ExperimentState.RUNNING,
            epoch = epoch,
            clock = runningClock,
            extraMutations = vector.resources.map(::upsertResource) + resourceAudit.mutations,
        )
        try {
            resumeAppliedVectorLocked(vector)
        } catch (_: RequiredResourceFailure) {
            gate.forceClose()
            safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return@command RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        }
        openAdmission(epoch.id, runningClock)
        try {
            notifyAdmissionOpenedLocked(vector)
        } catch (_: RequiredResourceFailure) {
            gate.forceClose()
            safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return@command RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        }
        publishSnapshot()
        performPostCommitEffectsLocked(
            effects + resourceAudit.effects + PostCommitEffects(actionsReady = pendingActionIdsLocked()),
        )
        RuntimeCommandResult.Success
    }

    private suspend fun completeActivatingAtDeadlineLocked(
        vector: AppliedResourceVector,
        now: ResearchTime,
        clock: StudyClockCheckpoint,
    ) {
        val timer = requireNotNull(studyDeadlineTimer) { "Activating study has no durable deadline" }
        require(timerIsDue(timer, reducerClock(clock))) { "Activation completed before its deadline" }
        releaseVectorLocked(vector)
        val reducerClock = reducerClock(clock)
        val reduction = reducer.reduceBatch(
            program,
            automationCheckpoint,
            listOf(
                ReducerInput.Lifecycle(
                    automationCheckpoint.evaluatedThroughSequence + 1,
                    reducerClock,
                    StudySessionState.PAUSING,
                ),
                ReducerInput.Lifecycle(
                    automationCheckpoint.evaluatedThroughSequence + 2,
                    reducerClock,
                    StudySessionState.COMPLETED,
                ),
            ),
        )
        val retirement = retireStudyDeadlineTimer(now, "FIRED")
        val commandId = commandId("study-duration-elapsed", requireDocument().nextCommitSequence)
        val effects = appendReductionLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            reduction = reduction,
            eventDrafts = listOf(RuntimeEventFactory.timerDue(timer, now)) + retirement.events + listOf(
                RuntimeEventFactory.lifecycle(
                    "STUDY_COMPLETE_REQUESTED",
                    commandId,
                    ExperimentState.ACTIVATING,
                    ExperimentState.PAUSING,
                    "STUDY_DURATION_ELAPSED",
                    now,
                ),
                RuntimeEventFactory.lifecycle(
                    "STUDY_COMPLETED",
                    commandId,
                    ExperimentState.PAUSING,
                    ExperimentState.COMPLETED,
                    "STUDY_DURATION_ELAPSED",
                    now,
                ),
            ),
            state = ExperimentState.COMPLETED,
            epoch = null,
            clock = clock,
            extraMutations = retirement.mutations + inactiveResourceMutations(reduction.checkpoint.desiredResources),
            timerRetirementReason = "LIFECYCLE_ENDED",
        )
        performPostCommitEffectsLocked(
            effects + retirement.effects + PostCommitEffects(actionsInactive = pendingActionIdsLocked()),
        )
    }

    private suspend fun stopSession(
        terminalState: ExperimentState,
        requestEvent: String,
        resultEvent: String,
        transitionReason: String,
        epochReason: String,
    ): RuntimeCommandResult = command {
        stopSessionLocked(terminalState, requestEvent, resultEvent, transitionReason, epochReason)
    }

    private suspend fun stopSessionLocked(
        terminalState: ExperimentState,
        requestEvent: String,
        resultEvent: String,
        transitionReason: String,
        epochReason: String,
        operationNow: ResearchTime? = null,
        collectionBoundary: ResearchTime? = null,
        causalEvents: List<EventDraft> = emptyList(),
        deadlineRetirementReason: String = "LIFECYCLE_ENDED",
        inputKind: EngineInputKind = EngineInputKind.LIFECYCLE_COMMAND,
    ): RuntimeCommandResult {
        val current = requireDocument()
        if (current.state == ExperimentState.PAUSED && terminalState != ExperimentState.PAUSED) {
            finalizePausedResourceCleanupLocked(recovery = true)
            val now = operationNow ?: clocks.now()
            val clock = advanceClockForTerminal(current, now)
            val target = terminalState.toSessionState()
            val reduction = reducer.reduceBatch(
                program,
                automationCheckpoint,
                listOf(
                    ReducerInput.Lifecycle(
                        automationCheckpoint.evaluatedThroughSequence + 1,
                        reducerClock(clock),
                        target,
                    ),
                ),
            )
            val commandId = commandId(transitionReason.lowercase(), current.nextCommitSequence)
            val deadlineRetirement = retireStudyDeadlineTimer(now, deadlineRetirementReason)
            val effects = appendReductionLocked(
                inputKind = inputKind,
                reduction = reduction,
                eventDrafts = causalEvents + deadlineRetirement.events + listOf(
                    RuntimeEventFactory.lifecycle(
                        requestEvent,
                        commandId,
                        current.state,
                        terminalState,
                        transitionReason,
                        now,
                    ),
                    RuntimeEventFactory.lifecycle(
                        resultEvent,
                        commandId,
                        current.state,
                        terminalState,
                        transitionReason,
                        now,
                    ),
                ),
                state = terminalState,
                clock = clock,
                extraMutations = deadlineRetirement.mutations,
            )
            performPostCommitEffectsLocked(
                effects + deadlineRetirement.effects +
                    PostCommitEffects(actionsInactive = pendingActionIdsLocked()),
            )
            return RuntimeCommandResult.Success
        }
        if (current.state != ExperimentState.RUNNING) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val now = operationNow ?: clocks.now()
        val boundary = collectionBoundary ?: now
        val boundaryClock = advanceClock(current, now)
        if (timeline.isElapsed(boundaryClock) && transitionReason != "STUDY_DURATION_ELAPSED") {
            val timer = requireNotNull(studyDeadlineTimer) { "Elapsed running study has no durable deadline" }
            return stopSessionLocked(
                terminalState = ExperimentState.COMPLETED,
                requestEvent = "STUDY_COMPLETE_REQUESTED",
                resultEvent = "STUDY_COMPLETED",
                transitionReason = "STUDY_DURATION_ELAPSED",
                epochReason = "STUDY_COMPLETED",
                operationNow = now,
                collectionBoundary = deadlineCollectionBoundary(timer),
                causalEvents = causalEvents + RuntimeEventFactory.timerDue(timer, now),
                deadlineRetirementReason = "FIRED",
                inputKind = EngineInputKind.TIMER_WAKE,
            )
        }
        val commandId = commandId(transitionReason.lowercase(), current.nextCommitSequence)
        val activeEpoch = requireNotNull(current.activeConditionEpoch)
        val buffer = BarrierInputBuffer(
            current.nextObservationSequence,
            current.nextEventSequence,
            activeEpoch.id,
            current.sourceCheckpoints,
            boundary,
            null,
        )
        barrierBuffer = buffer
        val drainToken = gate.beginDrain(boundary)
        return try {
            val flushCursors = suspendAndFlushLocked(boundary)
            val barrier = buffer.snapshot()
            val flush = prepareSources(
                current,
                barrier.submissions,
                activeEpoch.id,
                current.sourceCheckpoints,
                flushCursors,
            )
            val reducerClock = reducerClock(boundaryClock)
            val reducerInputs = buildList<ReducerInput> {
                flush.events.forEachIndexed { index, event ->
                    add(event.toReducerInput(automationCheckpoint.evaluatedThroughSequence + index + 1L, reducerClock))
                }
                add(
                    ReducerInput.Lifecycle(
                        automationCheckpoint.evaluatedThroughSequence + size + 1L,
                        reducerClock,
                        StudySessionState.PAUSING,
                    ),
                )
            }
            val reduction = reducer.reduceBatch(program, automationCheckpoint, reducerInputs)
            val vector = currentAppliedVector()
            val deadlineRetirement = if (terminalState in TERMINAL_STATES) {
                retireStudyDeadlineTimer(now, deadlineRetirementReason)
            } else {
                DeadlineTimerUpdate.EMPTY
            }
            val resourceAudit = deactivateResourceAuditsLocked(
                activeEpoch,
                vector,
                boundary,
                epochReason.toResourceAuditRemovalReason(),
            )
            val firstEffects = appendReductionLocked(
                inputKind = inputKind,
                reduction = reduction,
                prepared = flush,
                eventDrafts = causalEvents + deadlineRetirement.events + listOf(
                    RuntimeEventFactory.lifecycle(
                        requestEvent,
                        commandId,
                        current.state,
                        ExperimentState.PAUSING,
                        transitionReason,
                        now,
                    ),
                ) + resourceAudit.events + listOf(
                    RuntimeEventFactory.epochDeactivated(
                        activeEpoch,
                        vector,
                        epochReason,
                        boundary,
                    ),
                ),
                state = ExperimentState.PAUSING,
                epoch = null,
                eventConditionEpochId = activeEpoch.id,
                clock = boundaryClock,
                consumedPendingSha256 = barrier.pending?.encodedSha256,
                consumePending = barrier.pending != null,
                timerRetirementReason = "LIFECYCLE_ENDED",
                extraMutations = resourceAudit.mutations + deadlineRetirement.mutations,
            )
            gate.close(drainToken)
            barrierBuffer = null
            releaseAllResourcesLocked()
            val finalNow = clocks.now()
            val finalClock = advanceClock(requireDocument(), finalNow)
            val finalReduction = reducer.reduceBatch(
                program,
                automationCheckpoint,
                listOf(
                    ReducerInput.Lifecycle(
                        automationCheckpoint.evaluatedThroughSequence + 1,
                        reducerClock(finalClock),
                        terminalState.toSessionState(),
                    ),
                ),
            )
            val finalEffects = appendReductionLocked(
                inputKind = EngineInputKind.RESOURCE_RESULT,
                reduction = finalReduction,
                eventDrafts = listOf(
                    RuntimeEventFactory.lifecycle(
                        resultEvent,
                        commandId,
                        ExperimentState.PAUSING,
                        terminalState,
                        transitionReason,
                        finalNow,
                    ),
                ),
                state = terminalState,
                epoch = null,
                clock = finalClock,
                extraMutations = inactiveResourceMutations(finalReduction.checkpoint.desiredResources),
            )
            performPostCommitEffectsLocked(
                firstEffects + resourceAudit.effects + deadlineRetirement.effects + finalEffects +
                    PostCommitEffects(actionsInactive = pendingActionIdsLocked()),
            )
            RuntimeCommandResult.Success
        } catch (_: Throwable) {
            gate.forceClose()
            barrierBuffer = null
            val pending = store.loadPendingInput()
            if (pending != null) {
                recoverFailClosedLocked(pending)
            } else {
                safetyPauseLocked(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE, null)
            }
            RuntimeCommandResult.FailedClosed(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE)
        }
    }

    private suspend fun resourceBarrierLocked(
        inputKind: EngineInputKind,
        causalReducerInput: (Long) -> ReducerInput,
        causalEvents: List<EventDraft>,
        clock: StudyClockCheckpoint,
    ): Boolean {
        val current = requireDocument()
        val epoch = requireNotNull(current.activeConditionEpoch) { "A resource barrier requires an active epoch" }
        val boundary = clocks.now()
        val buffer = BarrierInputBuffer(
            current.nextObservationSequence,
            current.nextEventSequence,
            epoch.id,
            current.sourceCheckpoints,
            boundary,
            null,
        )
        barrierBuffer = buffer
        val drainToken = gate.beginDrain(boundary)
        return finishResourceBarrierLocked(
            current = current,
            epoch = epoch,
            boundary = boundary,
            buffer = buffer,
            drainToken = drainToken,
            causalSubmissions = emptyList(),
            inputKind = inputKind,
            causalReducerInput = causalReducerInput,
            causalEvents = causalEvents,
            clock = clock,
        )
    }

    private suspend fun completeCoordinatedBarrierLocked(request: CoordinatedBarrier): Boolean {
        if (request is FailClosedBarrier) {
            val durablePending = store.loadPendingInput()
            request.pending?.let { expected ->
                require(durablePending == expected) { "Fail-closed pending input changed before recovery" }
            }
            if (durablePending != null) {
                recoverFailClosedLocked(durablePending)
            } else {
                safetyPauseLocked(request.reason, null)
            }
            return false
        }
        val current = requireDocument()
        val epoch = requireNotNull(current.activeConditionEpoch) { "A coordinated barrier requires an active epoch" }
        return when (request) {
            is StagedSourceBarrier -> {
                require(epoch.id == request.pending.conditionEpochId) { "Staged barrier epoch is stale" }
                finishResourceBarrierLocked(
                    current = current,
                    epoch = epoch,
                    boundary = request.boundary,
                    buffer = request.buffer,
                    drainToken = request.drainToken,
                    causalSubmissions = listOf(request.causal.withKind(ObservationAdmissionKind.NORMAL)),
                    inputKind = request.inputKind,
                    causalReducerInput = null,
                    causalEvents = emptyList(),
                    clock = request.clock,
                )
            }
            is PostCommitBarrier -> finishResourceBarrierLocked(
                current = current,
                epoch = epoch,
                boundary = request.boundary,
                buffer = request.buffer,
                drainToken = request.drainToken,
                causalSubmissions = emptyList(),
                inputKind = request.inputKind,
                causalReducerInput = null,
                causalEvents = emptyList(),
                clock = request.clock,
            )
        }
    }

    private suspend fun finishResourceBarrierLocked(
        current: RuntimeDocument,
        epoch: ConditionEpoch,
        boundary: ResearchTime,
        buffer: BarrierInputBuffer,
        drainToken: AdmissionToken,
        causalSubmissions: List<SourceSubmission>,
        inputKind: EngineInputKind,
        causalReducerInput: ((Long) -> ReducerInput)?,
        causalEvents: List<EventDraft>,
        clock: StudyClockCheckpoint,
    ): Boolean = try {
            val flushCursors = suspendAndFlushLocked(boundary)
            val barrier = buffer.snapshot()
            val submissions = causalSubmissions + barrier.submissions
            val durablePending = barrier.pending
            require(
                (submissions.isEmpty() && durablePending == null) ||
                    durablePending?.submissions == submissions.map(SourceSubmission::toPending),
            ) { "Barrier submissions do not match the durable pending slot" }
            val prepared = prepareSources(
                document = current,
                submissions = submissions,
                conditionEpochId = epoch.id,
                startingCheckpoints = current.sourceCheckpoints,
                flushCursors = flushCursors,
                semanticEventOrder = barrier.submissions + causalSubmissions,
            )
            val reducerClock = reducerClock(clock)
            val reducerInputs = buildList {
                prepared.events.forEachIndexed { index, event ->
                    add(event.toReducerInput(automationCheckpoint.evaluatedThroughSequence + index + 1L, reducerClock))
                }
                causalReducerInput?.let { factory ->
                    add(factory(automationCheckpoint.evaluatedThroughSequence + size + 1L))
                }
            }
            val finalReduction = if (reducerInputs.isEmpty()) {
                emptyReduction(automationCheckpoint)
            } else {
                reducer.reduceBatch(program, automationCheckpoint, reducerInputs)
            }
            val oldVector = currentAppliedVector()
            val deactivationAudit = deactivateResourceAuditsLocked(
                epoch,
                oldVector,
                boundary,
                ResourceAuditRemovalReason.PROFILE_REPLACED,
            )
            val firstEffects = appendReductionLocked(
                inputKind = inputKind,
                reduction = finalReduction,
                prepared = prepared,
                eventDrafts = causalEvents + deactivationAudit.events + listOf(
                    RuntimeEventFactory.epochDeactivated(
                        epoch,
                        oldVector,
                        "RESOURCE_VECTOR_CHANGED",
                        boundary,
                    ),
                ),
                state = ExperimentState.RUNNING,
                epoch = null,
                eventConditionEpochId = epoch.id,
                clock = clock,
                consumedPendingSha256 = durablePending?.encodedSha256,
                consumePending = durablePending != null,
                extraMutations = deactivationAudit.mutations,
            )
            gate.close(drainToken)
            barrierBuffer = null
            val vector = applyDesiredVectorLocked(finalReduction.checkpoint.desiredResources, "barrier-${current.nextCommitSequence}")
            val activatedAt = clocks.now()
            val newEpoch = newEpoch(vector, activatedAt)
            val activationAudit = activateResourceAuditsLocked(newEpoch, vector, activatedAt)
            val secondEffects = appendCommitLocked(
                inputKind = EngineInputKind.RESOURCE_RESULT,
                checkpoint = automationCheckpoint,
                eventDrafts = listOf(
                    RuntimeEventFactory.epochActivated(
                        newEpoch,
                        vector,
                        "RESOURCE_VECTOR_CHANGED",
                        activatedAt,
                    ),
                ) + activationAudit.events,
                state = ExperimentState.RUNNING,
                epoch = newEpoch,
                clock = advanceClock(requireDocument(), activatedAt),
                extraMutations = vector.resources.map(::upsertResource) + activationAudit.mutations,
            )
            resumeAppliedVectorLocked(vector)
            openAdmission(newEpoch.id, requireNotNull(requireDocument().clockCheckpoint))
            notifyAdmissionOpenedLocked(vector)
            publishSnapshot()
            performPostCommitEffectsLocked(
                firstEffects + deactivationAudit.effects + secondEffects + activationAudit.effects,
            )
            true
        } catch (_: Throwable) {
            gate.forceClose()
            barrierBuffer = null
            val stillPending = store.loadPendingInput()
            if (stillPending != null) recoverFailClosedLocked(stillPending) else {
                safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            }
            false
        }

    private suspend fun suspendAndFlushLocked(boundary: ResearchTime): Map<EventSourceId, String?> {
        val appliedByKey = currentAppliedVector().resources.associateBy(AppliedResourceState::key)
        appliedByKey.values.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
            val actuator = requireNotNull(hosts.getValue(applied.key).actuator)
            val desired = desiredState(applied)
            actuator.suspendAt(desired, boundary).requireMatches(desired, boundary)
        }
        val retrospectiveCursors = sortedMapOf<EventSourceId, String?>()
        hosts.values.sortedBy(RuntimeResourceHost::key).forEach { host ->
            val actuator = host.actuator ?: return@forEach
            val applied = appliedByKey.getValue(host.key)
            if (applied.status != AppliedResourceStatus.APPLIED) return@forEach
            val desired = desiredState(applied)
            val sourceId = if (host.key.kind == ResourceKind.COLLECTOR) EventSourceId(host.key.id) else null
            val cursor = if (sourceId != null) {
                requireDocument().sourceCheckpoints[sourceId]?.cursor
            } else {
                null
            }
            val receipt = actuator.flushThrough(desired, boundary, cursor)
            receipt.requireMatches(desired, boundary)
            if (
                sourceId != null &&
                requireNotNull(ProtocolEventSourceRegistry[sourceId.value]).isRetrospective
            ) {
                retrospectiveCursors[sourceId] = receipt.cursor
            }
        }
        return retrospectiveCursors
    }

    private suspend fun applyDesiredVectorLocked(
        desiredProfiles: Map<ResourceKey, DesiredProfile>,
        requestId: String,
    ): AppliedResourceVector {
        check(pendingResourceContainment == null) { "A prior resource containment plan is unresolved" }
        val applied = mutableListOf<AppliedResourceState>()
        val verifiedApplied = mutableListOf<Pair<StatefulResourceActuator, DesiredResourceState>>()
        val verifiedInactive = sortedMapOf<ResourceKey, ResourceGeneration>()
        val attempted = sortedMapOf<ResourceKey, DesiredResourceState>()
        try {
            hosts.values.sortedBy(RuntimeResourceHost::key).forEach { host ->
                val desiredProfile = requireNotNull(desiredProfiles[host.key]) { "Missing desired resource state" }
                val profile = desiredProfile.profileId?.let { host.profiles.getValue(it) }
                val desired = DesiredResourceState(host.key, desiredProfile.generation, host.required, profile)
                val actuator = host.actuator
                val trustedDesired = appliedResources[host.key]
                    ?.takeIf { it.status == AppliedResourceStatus.APPLIED }
                    ?.let(::desiredState)
                if (profile == null) {
                    val prior = appliedResources[host.key]
                    if (actuator != null && prior?.status == AppliedResourceStatus.APPLIED) {
                        val priorDesired = desiredState(prior)
                        actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        verifiedInactive[host.key] = desired.generation
                    }
                    applied += AppliedResourceState(
                        host.key,
                        desired.generation,
                        null,
                        null,
                        AppliedResourceStatus.INACTIVE,
                        null,
                    )
                    return@forEach
                }
                if (actuator == null) {
                    if (host.required) throw RequiredResourceFailure()
                    applied += optionalFailure(host.key, desired, "RESOURCE_NOT_COMPILED")
                    return@forEach
                }
                try {
                    // Platform work can start before prepare returns, so persist this identity if
                    // containment becomes necessary at any later point in the call.
                    attempted[host.key] = desired
                    actuator.prepare(desired, requestId).requireMatches(desired, requestId)
                    actuator.apply(desired).requireMatches(desired)
                    actuator.verify(desired).requireMatches(desired)
                    attempted.remove(host.key)
                    verifiedApplied += actuator to desired
                    applied += AppliedResourceState(
                        host.key,
                        desired.generation,
                        profile.id,
                        profile.expectedSha256,
                        AppliedResourceStatus.APPLIED,
                        null,
                    )
                } catch (failure: Throwable) {
                    try {
                        val release = actuator.release(desired)
                        if (trustedDesired?.sameIdentity(desired) == true) {
                            release.requireReleased(desired, actuator.health())
                        } else {
                            release.requireCleanupReleased(desired, actuator.health())
                        }
                        attempted.remove(host.key)
                        verifiedInactive[host.key] = desired.generation
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                        attempted[host.key] = desired
                        throw RequiredResourceFailure()
                    }
                    if (failure is CancellationException) throw failure
                    if (host.required) throw RequiredResourceFailure()
                    applied += optionalFailure(host.key, desired, "RESOURCE_APPLY_FAILED")
                }
            }
        } catch (failure: Throwable) {
            var cleanupFailed = false
            verifiedApplied.asReversed().forEach { (actuator, desired) ->
                try {
                    actuator.release(desired).requireReleased(desired, actuator.health())
                    attempted.remove(desired.key)
                    verifiedInactive[desired.key] = desired.generation
                } catch (cleanupFailure: Throwable) {
                    cleanupFailed = true
                    attempted[desired.key] = desired
                    failure.addSuppressed(cleanupFailure)
                }
            }
            pendingResourceContainment = ResourceContainment(verifiedInactive, attempted)
            if (failure is CancellationException && !cleanupFailed) throw failure
            throw RequiredResourceFailure()
        }
        pendingResourceContainment = null
        return AppliedResourceVector(applied.sortedBy(AppliedResourceState::key))
    }

    private suspend fun resumeAppliedVectorLocked(vector: AppliedResourceVector) {
        try {
            vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
                val actuator = requireNotNull(hosts.getValue(applied.key).actuator)
                val desired = desiredState(applied)
                actuator.resume(desired).requireMatches(desired)
                actuator.health().requireAppliedMatches(desired)
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw RequiredResourceFailure()
        }
    }

    private suspend fun notifyAdmissionOpenedLocked(vector: AppliedResourceVector) {
        try {
            vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
                val actuator = requireNotNull(hosts.getValue(applied.key).actuator)
                val desired = desiredState(applied)
                actuator.onAdmissionOpened(desired).requireAppliedMatches(desired)
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw RequiredResourceFailure()
        }
    }

    private suspend fun releaseAllResourcesLocked() {
        releaseVectorLocked(currentAppliedVector())
    }

    private suspend fun releaseVectorLocked(vector: AppliedResourceVector) {
        vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }
            .sortedByDescending(AppliedResourceState::key)
            .forEach { applied ->
            val actuator = requireNotNull(hosts.getValue(applied.key).actuator)
            val desired = desiredState(applied)
            try {
                actuator.release(desired).requireReleased(desired, actuator.health())
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                throw RequiredResourceFailure()
            }
        }
    }

    private suspend fun finalizePausedResourceCleanupLocked(recovery: Boolean) {
        val current = requireDocument()
        require(current.state == ExperimentState.PAUSED && current.activeConditionEpoch == null) {
            "Resource cleanup finalization requires a closed paused study"
        }
        val trusted = currentAppliedVector().resources.associateBy(AppliedResourceState::key)
        val failures = mutableListOf<Throwable>()
        hosts.values.sortedByDescending(RuntimeResourceHost::key).forEach { host ->
            val actuator = host.actuator
            val prior = trusted.getValue(host.key)
            val attempted = resourceCleanupAttempts[host.key]?.let(::cleanupDesiredState)
            val priorDesired = prior.takeIf { it.status == AppliedResourceStatus.APPLIED }?.let(::desiredState)
            val attemptedWasTrusted = attempted != null && priorDesired != null &&
                attempted.sameIdentity(priorDesired)
            if (actuator == null) {
                if (attempted != null || priorDesired != null) {
                    failures += RequiredResourceFailure()
                }
                return@forEach
            }
            try {
                if (recovery) {
                    val health = actuator.health()
                    if (runCatching { health.requireInactiveMatches(host.key) }.isSuccess) {
                        return@forEach
                    }
                    when {
                        attemptedWasTrusted && health.matchesTrustedApplied(requireNotNull(priorDesired)) ->
                            actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        priorDesired != null && health.matchesTrustedApplied(priorDesired) ->
                            actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        attempted != null && !attemptedWasTrusted && health.matchesCleanupAttempt(attempted) ->
                            actuator.release(attempted).requireCleanupReleased(attempted, actuator.health())
                        else -> throw RequiredResourceFailure()
                    }
                } else {
                    when {
                        attemptedWasTrusted -> {
                            val trustedDesired = requireNotNull(priorDesired)
                            actuator.release(trustedDesired).requireReleased(trustedDesired, actuator.health())
                        }
                        attempted != null ->
                            actuator.release(attempted).requireCleanupReleased(attempted, actuator.health())
                        priorDesired != null ->
                            actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        else -> Unit
                    }
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failures += failure
            }
        }
        if (failures.isNotEmpty()) {
            throw RequiredResourceFailure().also { aggregate -> failures.forEach(aggregate::addSuppressed) }
        }

        val resourceMutations = inactiveResourceMutations(automationCheckpoint.desiredResources)
        val cleanupMutations = resourceCleanupAttempts.keys.map(::removeResourceCleanup)
        val alreadyFinal = appliedResources.values.all { it.status == AppliedResourceStatus.INACTIVE } &&
            resourceCleanupAttempts.isEmpty()
        if (!alreadyFinal) {
            appendCommitLocked(
                inputKind = EngineInputKind.RESOURCE_RESULT,
                checkpoint = automationCheckpoint,
                state = ExperimentState.PAUSED,
                epoch = null,
                clock = current.clockCheckpoint,
                extraMutations = resourceMutations + cleanupMutations,
            )
        }
    }

    private fun desiredState(applied: AppliedResourceState): DesiredResourceState {
        require(applied.status == AppliedResourceStatus.APPLIED) { "Desired state requires an applied resource" }
        val host = hosts.getValue(applied.key)
        val profileId = requireNotNull(applied.profileId) { "Applied resource has no profile" }
        val profile = host.profiles.getValue(profileId)
        require(profile.expectedSha256 == applied.appliedProfileSha256) { "Applied resource digest mismatch" }
        return DesiredResourceState(applied.key, applied.desiredGeneration, host.required, profile)
    }

    private fun cleanupDesiredState(cleanup: DurableResourceCleanup): DesiredResourceState {
        val host = hosts.getValue(cleanup.key)
        val profile = host.profiles.getValue(cleanup.profileId)
        require(profile.expectedSha256 == cleanup.expectedProfileSha256) {
            "Durable cleanup profile digest mismatch"
        }
        return DesiredResourceState(cleanup.key, cleanup.generation, host.required, profile)
    }

    private fun durableCleanup(desired: DesiredResourceState): DurableResourceCleanup {
        val profile = requireNotNull(desired.profile) { "Inactive desired state cannot require cleanup" }
        return DurableResourceCleanup(desired.key, desired.generation, profile.id, profile.expectedSha256)
    }

    private fun deriveRecoveryCleanupAttempts(
        trustedVector: AppliedResourceVector,
        desiredProfiles: Map<ResourceKey, DesiredProfile>,
    ): Map<ResourceKey, DurableResourceCleanup> {
        val trusted = trustedVector.resources.associateBy(AppliedResourceState::key)
        return desiredProfiles.mapNotNull { (key, desiredProfile) ->
            val profileId = desiredProfile.profileId ?: return@mapNotNull null
            val host = hosts.getValue(key)
            if (host.actuator == null) return@mapNotNull null
            val profile = host.profiles.getValue(profileId)
            val prior = trusted.getValue(key)
            val alreadyTrusted = prior.status == AppliedResourceStatus.APPLIED &&
                prior.desiredGeneration == desiredProfile.generation &&
                prior.profileId == profileId &&
                prior.appliedProfileSha256 == profile.expectedSha256
            if (alreadyTrusted) null else key to DurableResourceCleanup(
                key,
                desiredProfile.generation,
                profileId,
                profile.expectedSha256,
            )
        }.toMap()
    }

    private fun cool.jacoblin.particeps.core.resource.ResourceHealth.matchesCleanupAttempt(
        desired: DesiredResourceState,
    ): Boolean =
        key == desired.key &&
            generation == desired.generation &&
            profileId == desired.profile?.id &&
            expectedProfileSha256 == desired.profile?.expectedSha256 &&
            status in ATTEMPTED_CLEANUP_HEALTH_STATES

    private fun cool.jacoblin.particeps.core.resource.ResourceHealth.matchesTrustedApplied(
        desired: DesiredResourceState,
    ): Boolean =
        key == desired.key &&
            generation == desired.generation &&
            profileId == desired.profile?.id &&
            expectedProfileSha256 == desired.profile?.expectedSha256 &&
            appliedProfileSha256 == desired.profile?.expectedSha256 &&
            status in TRUSTED_APPLIED_HEALTH_STATES

    private fun DesiredResourceState.sameIdentity(other: DesiredResourceState): Boolean =
        key == other.key &&
            generation == other.generation &&
            profile?.id == other.profile?.id &&
            profile?.expectedSha256 == other.profile?.expectedSha256

    private suspend fun activateResourceAuditsLocked(
        epoch: ConditionEpoch,
        vector: AppliedResourceVector,
        now: ResearchTime,
    ): ResourceAuditBatch {
        val events = mutableListOf<EventDraft>()
        val mutations = mutableListOf<RuntimeMutation>()
        val intents = mutableListOf<TimerIntent>()
        vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
            val source = hosts.getValue(applied.key).auditSource ?: return@forEach
            val evidence = applied.auditEvidence()
            val receipt = source.audit(
                ResourceAuditRequest.EpochActivated(
                    evidence = evidence,
                    conditionEpochId = epoch.id,
                    observedAt = now,
                    activatedAt = epoch.activatedAt,
                    signedConfigurationSha256 = Sha256Digest(study.configurationSha256),
                ),
            )
            require(receipt.evidence == evidence) { "Resource audit activation evidence mismatch" }
            events += RuntimeEventFactory.validateResourceAudit(source, receipt, epoch, now)
            val timer = resourceAuditTimer(source, evidence, epoch, now)
            require(timer.id !in resourceAuditTimers) { "Duplicate resource audit timer" }
            events += RuntimeEventFactory.timerScheduled(timer, now)
            mutations += upsertResourceAuditTimer(timer)
            intents += TimerIntent.Schedule(timer)
        }
        return ResourceAuditBatch(events, mutations, PostCommitEffects(timerIntents = intents))
    }

    private suspend fun deactivateResourceAuditsLocked(
        epoch: ConditionEpoch,
        vector: AppliedResourceVector,
        boundary: ResearchTime,
        reason: ResourceAuditRemovalReason,
    ): ResourceAuditBatch {
        val events = mutableListOf<EventDraft>()
        vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
            val source = hosts.getValue(applied.key).auditSource ?: return@forEach
            val evidence = applied.auditEvidence()
            val timer = resourceAuditTimers.values.singleOrNull {
                it.producerKey == resourceAuditProducerKey(applied.key)
            } ?: error("Applied auditable resource has no durable audit timer")
            require(timer.generation == evidence.generation.value) { "Stale resource audit timer generation" }
            require(timer.id == resourceAuditTimerId(source, evidence, epoch, timer.causalSequence, timer.target)) {
                "Resource audit timer is not bound to its epoch evidence"
            }
            val receipt = source.audit(
                ResourceAuditRequest.EpochBoundary(
                    evidence = evidence,
                    conditionEpochId = epoch.id,
                    observedAt = boundary,
                    boundary = boundary,
                    reason = reason,
                ),
            )
            require(receipt.evidence == evidence) { "Resource audit boundary evidence mismatch" }
            events += RuntimeEventFactory.validateResourceAudit(source, receipt, epoch, boundary)
        }
        val retirement = retireResourceAuditTimersLocked(boundary, "LIFECYCLE_ENDED")
        return ResourceAuditBatch(
            events = events + retirement.events,
            mutations = retirement.mutations,
            effects = retirement.effects,
        )
    }

    private fun retireResourceAuditTimersLocked(
        now: ResearchTime,
        reason: String,
    ): ResourceAuditBatch {
        val timers = resourceAuditTimers.values.sortedBy(DurableTimer::id)
        return ResourceAuditBatch(
            events = timers.map { RuntimeEventFactory.timerRetired(it, reason, now) },
            mutations = timers.map { removeResourceAuditTimer(it.id) },
            effects = PostCommitEffects(
                timerIntents = timers.map { TimerIntent.Retire(it.id, it.generation) },
            ),
        )
    }

    private suspend fun onResourceAuditTimerDueLocked(
        timer: DurableTimer,
        generation: ULong,
    ): RuntimeCommandResult {
        if (timer.generation != generation) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION)
        }
        val current = requireDocument()
        val epoch = requireNotNull(current.activeConditionEpoch) { "Resource audit timer requires an active epoch" }
        val owner = hosts.values.singleOrNull { resourceAuditProducerKey(it.key) == timer.producerKey }
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_FOUND)
        val source = owner.auditSource
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_FOUND)
        val applied = appliedResources[owner.key]
            ?.takeIf { it.status == AppliedResourceStatus.APPLIED }
            ?: return retireStaleResourceAuditTimerLocked(timer, current, "GENERATION_REPLACED")
        val evidence = applied.auditEvidence()
        if (
            timer.generation != evidence.generation.value ||
            timer.id != resourceAuditTimerId(source, evidence, epoch, timer.causalSequence, timer.target)
        ) {
            return retireStaleResourceAuditTimerLocked(timer, current, "GENERATION_REPLACED")
        }
        timer.target as? TimerTarget.SameBootMonotonic
            ?: error("Resource audit timer must use same-boot monotonic time")
        val now = clocks.now()
        val clock = advanceClock(current, now)
        if (!timerIsDue(timer, reducerClock(clock))) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_DUE)
        }
        val logicalDue = RuntimeEventFactory.timerLogicalTarget(timer)
        val receipt = source.audit(
            ResourceAuditRequest.Periodic(
                evidence = evidence,
                conditionEpochId = epoch.id,
                observedAt = now,
                logicalDeadline = logicalDue,
            ),
        )
        require(receipt.evidence == evidence) { "Periodic resource audit evidence mismatch" }
        val auditEvents = RuntimeEventFactory.validateResourceAudit(source, receipt, epoch, now)
        val successor = resourceAuditTimer(source, evidence, epoch, now)
        val effects = appendCommitLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            checkpoint = automationCheckpoint,
            eventDrafts = listOf(RuntimeEventFactory.timerDue(timer, now)) +
                auditEvents +
                listOf(
                    RuntimeEventFactory.timerRetired(timer, "FIRED", now),
                    RuntimeEventFactory.timerScheduled(successor, now),
                ),
            epoch = epoch,
            clock = clock,
            extraMutations = listOf(
                removeResourceAuditTimer(timer.id),
                upsertResourceAuditTimer(successor),
            ),
        )
        performPostCommitEffectsLocked(
            effects + PostCommitEffects(
                timerIntents = listOf(
                    TimerIntent.Retire(timer.id, timer.generation),
                    TimerIntent.Schedule(successor),
                ),
            ),
        )
        return RuntimeCommandResult.Success
    }

    private suspend fun retireStaleResourceAuditTimerLocked(
        timer: DurableTimer,
        current: RuntimeDocument,
        reason: String,
    ): RuntimeCommandResult {
        val now = clocks.now()
        val effects = appendCommitLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            checkpoint = automationCheckpoint,
            eventDrafts = listOf(RuntimeEventFactory.timerRetired(timer, reason, now)),
            clock = advanceClock(current, now),
            extraMutations = listOf(removeResourceAuditTimer(timer.id)),
        )
        performPostCommitEffectsLocked(
            effects + PostCommitEffects(timerIntents = listOf(TimerIntent.Retire(timer.id, timer.generation))),
        )
        return RuntimeCommandResult.Success
    }

    private fun resourceAuditTimer(
        source: PeriodicResourceAuditSource,
        evidence: ResourceAuditEvidence,
        epoch: ConditionEpoch,
        now: ResearchTime,
    ): DurableTimer {
        val intervalNanos = Math.multiplyExact(source.intervalSeconds, NANOS_PER_SECOND)
        val target = TimerTarget.SameBootMonotonic(
            now.bootSessionId,
            Math.addExact(now.elapsedRealtimeNanos, intervalNanos),
        )
        val causalSequence = automationCheckpoint.evaluatedThroughSequence.coerceAtLeast(1)
        return DurableTimer(
            id = resourceAuditTimerId(source, evidence, epoch, causalSequence, target),
            automationId = program.resourceBindings.single { it.resource == evidence.key }.id,
            generation = evidence.generation.value,
            causalSequence = causalSequence,
            producerKey = resourceAuditProducerKey(evidence.key),
            target = target,
            logicalDeadlineUtcMillis = Math.addExact(now.wallTimeUtcMillis, source.intervalSeconds * 1_000L),
            expiresAtUtcMillis = null,
        )
    }

    private fun resourceAuditTimerId(
        source: PeriodicResourceAuditSource,
        evidence: ResourceAuditEvidence,
        epoch: ConditionEpoch,
        causalSequence: Long,
        target: TimerTarget,
    ): String {
        val monotonic = target as? TimerTarget.SameBootMonotonic
            ?: error("Resource audit timer must use same-boot monotonic time")
        return digest(
            "particeps-resource-audit-timer-v1",
            study.configurationSha256,
            source.sourceId.value,
            evidence.key.kind.name,
            evidence.key.id,
            evidence.generation.toString(),
            evidence.profileId,
            evidence.appliedProfileSha256.value,
            epoch.id.value,
            causalSequence.toString(),
            monotonic.bootSessionId,
            monotonic.elapsedRealtimeNanos.toString(),
        )
    }

    private fun resourceAuditProducerKey(key: ResourceKey): String =
        "$RESOURCE_AUDIT_PRODUCER_PREFIX${key.kind.name.lowercase()}:${key.id}"

    private fun reconcileStudyDeadlineTimer(
        clock: StudyClockCheckpoint,
        causalSequence: Long,
        replacementReason: String,
    ): DeadlineTimerUpdate {
        require(!timeline.isElapsed(clock)) { "Elapsed studies cannot schedule a deadline timer" }
        val target = timeline.sameBootDeadline(clock)
        val current = studyDeadlineTimer
        if (current != null &&
            current.target == TimerTarget.SameBootMonotonic(target.bootSessionId, target.elapsedRealtimeNanos) &&
            current.logicalDeadlineUtcMillis == target.wallTimeUtcMillis
        ) {
            return DeadlineTimerUpdate.EMPTY
        }
        val timer = DurableTimer(
            id = studyDeadlineTimerId(),
            automationId = STUDY_DURATION_AUTOMATION_ID,
            generation = (current?.generation ?: 0uL) + 1uL,
            causalSequence = causalSequence.coerceAtLeast(1L),
            producerKey = STUDY_DEADLINE_PRODUCER_KEY,
            target = TimerTarget.SameBootMonotonic(target.bootSessionId, target.elapsedRealtimeNanos),
            logicalDeadlineUtcMillis = target.wallTimeUtcMillis,
            expiresAtUtcMillis = null,
        )
        return DeadlineTimerUpdate(
            events = buildList {
                current?.let { add(RuntimeEventFactory.timerRetired(it, replacementReason, clock.anchor)) }
                add(RuntimeEventFactory.timerScheduled(timer, clock.anchor))
            },
            mutations = listOf(upsertStudyDeadlineTimer(timer)),
            effects = PostCommitEffects(
                timerIntents = buildList {
                    current?.let { add(TimerIntent.Retire(it.id, it.generation)) }
                    add(TimerIntent.Schedule(timer))
                },
            ),
        )
    }

    private fun retireStudyDeadlineTimer(now: ResearchTime, reason: String): DeadlineTimerUpdate {
        val timer = studyDeadlineTimer ?: return DeadlineTimerUpdate.EMPTY
        return DeadlineTimerUpdate(
            events = listOf(RuntimeEventFactory.timerRetired(timer, reason, now)),
            mutations = listOf(removeStudyDeadlineTimer()),
            effects = PostCommitEffects(timerIntents = listOf(TimerIntent.Retire(timer.id, timer.generation))),
        )
    }

    private fun requireStudyDeadlineTimer(timer: DurableTimer) {
        require(timer.id == studyDeadlineTimerId()) { "Study deadline timer ID mismatch" }
        require(timer.automationId == STUDY_DURATION_AUTOMATION_ID) { "Study deadline timer owner mismatch" }
        require(timer.producerKey == STUDY_DEADLINE_PRODUCER_KEY) { "Study deadline producer mismatch" }
        require(timer.target is TimerTarget.SameBootMonotonic) { "Study deadline must use a same-boot target" }
        require(timer.logicalDeadlineUtcMillis != null && timer.expiresAtUtcMillis == null) {
            "Study deadline timer has invalid wall-time evidence"
        }
    }

    private fun studyDeadlineTimerId(): String = digest(
        "particeps-study-deadline-timer-v1",
        study.configurationSha256,
        STUDY_DURATION_AUTOMATION_ID,
        STUDY_DEADLINE_PRODUCER_KEY,
    )

    private fun AppliedResourceState.auditEvidence() = ResourceAuditEvidence(
        key = key,
        generation = desiredGeneration,
        profileId = requireNotNull(profileId),
        appliedProfileSha256 = requireNotNull(appliedProfileSha256),
    )

    private suspend fun safetyPauseLocked(
        reason: SafetyPauseReason,
        causeSequence: Long?,
        resourceFailure: ResourceTerminalFailure? = null,
    ) {
        gate.forceClose()
        barrierBuffer = null
        val current = requireDocument()
        if (current.state in TERMINAL_STATES || current.state == ExperimentState.PAUSED) return
        val containment = pendingResourceContainment ?: ResourceContainment(emptyMap(), emptyMap())
        require(containment.verifiedInactive.keys.intersect(containment.attempted.keys).isEmpty()) {
            "A resource cannot be both verified inactive and awaiting cleanup"
        }
        val trustedVector = currentAppliedVector()
        val pausedVector = AppliedResourceVector(
            trustedVector.resources.map { trusted ->
                containment.verifiedInactive[trusted.key]?.let { generation ->
                    inactiveResource(trusted.key, generation)
                } ?: trusted
            },
        )
        val cleanupAttempts = containment.attempted.values.map(::durableCleanup)
        val now = clocks.now()
        val clock = runCatching { advanceClock(current, now) }.getOrElse { recoveryClock(current, now) }
        val inputs = lifecycleInputsToPause(automationCheckpoint, reducerClock(clock))
        val reduction = reducer.reduceBatch(program, automationCheckpoint, inputs)
        val commandId = commandId("safety-${reason.name.lowercase()}", current.nextCommitSequence)
        val transitionReason = reason.transitionReason.name
        val activeEpoch = current.activeConditionEpoch
        if (activeEpoch != null) runCatching { suspendAndFlushLocked(now) }
        val resourceAudit = if (activeEpoch == null) {
            ResourceAuditBatch.EMPTY
        } else {
            runCatching {
                deactivateResourceAuditsLocked(
                    activeEpoch,
                    trustedVector,
                    now,
                    resourceFailure.toResourceAuditRemovalReason(reason),
                )
            }.getOrElse { retireResourceAuditTimersLocked(now, "LIFECYCLE_ENDED") }
        }
        val events = buildList {
            add(
                RuntimeEventFactory.lifecycle(
                    "STUDY_SAFETY_PAUSE_REQUESTED",
                    commandId,
                    current.state,
                    ExperimentState.PAUSING,
                    transitionReason,
                    now,
                    causeSequence,
                ),
            )
            addAll(resourceAudit.events)
            activeEpoch?.let { epoch ->
                add(
                    RuntimeEventFactory.epochDeactivated(
                        epoch,
                        trustedVector,
                        if (reason == SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN) {
                            "PROCESS_RECOVERY_UNPROVEN"
                        } else {
                            "SAFETY_PAUSED"
                        },
                        now,
                    ),
                )
            }
            add(
                RuntimeEventFactory.lifecycle(
                    "STUDY_SAFETY_PAUSED",
                    commandId,
                    ExperimentState.PAUSING,
                    ExperimentState.PAUSED,
                    transitionReason,
                    now,
                    causeSequence,
                ),
            )
        }
        val effects = appendReductionLocked(
            inputKind = EngineInputKind.SAFETY_FAILURE,
            reduction = reduction,
            eventDrafts = events,
            state = ExperimentState.PAUSED,
            epoch = null,
            eventConditionEpochId = activeEpoch?.id,
            clock = clock,
            extraMutations = pausedVector.resources.map(::upsertResource) +
                cleanupAttempts.map(::upsertResourceCleanup) +
                resourceAudit.mutations,
            timerRetirementReason = "LIFECYCLE_ENDED",
        )
        pendingResourceContainment = null
        performPostCommitEffectsLocked(
            effects + resourceAudit.effects + PostCommitEffects(actionsInactive = pendingActionIdsLocked()),
        )
        finalizePausedResourceCleanupLocked(recovery = false)
    }

    private suspend fun recoverFailClosedLocked(pending: PendingEngineInput?) {
        gate.forceClose()
        val current = requireDocument()
        val trustedVector = currentAppliedVector()
        val cleanupAttempts = (
            deriveRecoveryCleanupAttempts(trustedVector, automationCheckpoint.desiredResources) +
                resourceCleanupAttempts.values.associateBy(DurableResourceCleanup::key)
            ).toSortedMap()
        val now = clocks.now()
        val clock = recoveryClock(current, now)
        val prepared = pending?.let { preparePending(current, it) } ?: PreparedSources.empty(current)
        val checkpoint = automationCheckpoint
        val inputs = mutableListOf<ReducerInput>()
        val reducerClock = reducerClock(clock)
        prepared.events.forEach { event ->
            inputs += event.toReducerInput(checkpoint.evaluatedThroughSequence + inputs.size + 1, reducerClock)
        }
        inputs += ReducerInput.QualityGap(
            checkpoint.evaluatedThroughSequence + inputs.size + 1,
            reducerClock,
            pending?.submissions?.firstOrNull()?.sourceId ?: EventSourceId("study_runtime.v1"),
        )
        when (checkpoint.lifecycle) {
            StudySessionState.ACTIVATING, StudySessionState.RUNNING -> {
                inputs += ReducerInput.Lifecycle(
                    checkpoint.evaluatedThroughSequence + inputs.size + 1,
                    reducerClock,
                    StudySessionState.PAUSING,
                )
                inputs += ReducerInput.Lifecycle(
                    checkpoint.evaluatedThroughSequence + inputs.size + 1,
                    reducerClock,
                    StudySessionState.PAUSED,
                )
            }
            StudySessionState.PAUSING -> inputs += ReducerInput.Lifecycle(
                checkpoint.evaluatedThroughSequence + inputs.size + 1,
                reducerClock,
                StudySessionState.PAUSED,
            )
            StudySessionState.PAUSED -> Unit
            StudySessionState.READY -> inputs += ReducerInput.Lifecycle(
                checkpoint.evaluatedThroughSequence + inputs.size + 1,
                reducerClock,
                StudySessionState.WITHDRAWN,
            )
            StudySessionState.COMPLETED, StudySessionState.WITHDRAWN -> Unit
        }
        val reduction = reducer.reduceBatch(program, checkpoint, inputs)
        val commandId = commandId("process-recovery", current.nextCommitSequence)
        val vector = trustedVector
        val resourceAudit = retireResourceAuditTimersLocked(now, "QUALITY_GAP_RESET")
        val deadlineUpdate = if (timeline.isElapsed(clock)) {
            DeadlineTimerUpdate.EMPTY
        } else {
            reconcileStudyDeadlineTimer(
                clock,
                reduction.checkpoint.evaluatedThroughSequence,
                "QUALITY_GAP_RESET",
            )
        }
        val events = buildList {
            add(
                RuntimeEventFactory.qualityGap(
                    pending?.submissions?.firstOrNull()?.sourceId ?: EventSourceId("study_runtime.v1"),
                    SourceQualityGapReason.PROCESS_RECOVERY,
                    now,
                ),
            )
            add(
                RuntimeEventFactory.lifecycle(
                    "STUDY_SAFETY_PAUSE_REQUESTED",
                    commandId,
                    current.state,
                    ExperimentState.PAUSING,
                    "REQUIRED_RESOURCE_FAILURE",
                    now,
                ),
            )
            addAll(resourceAudit.events)
            current.activeConditionEpoch?.let {
                add(RuntimeEventFactory.epochDeactivated(it, vector, "PROCESS_RECOVERY_UNPROVEN", now))
            }
            add(
                RuntimeEventFactory.lifecycle(
                    "STUDY_SAFETY_PAUSED",
                    commandId,
                    ExperimentState.PAUSING,
                    ExperimentState.PAUSED,
                    "REQUIRED_RESOURCE_FAILURE",
                    now,
                ),
            )
            addAll(deadlineUpdate.events)
        }
        val effects = appendReductionLocked(
            inputKind = EngineInputKind.RECOVERY,
            reduction = reduction,
            prepared = prepared,
            eventDrafts = events,
            state = ExperimentState.PAUSED,
            epoch = null,
            eventConditionEpochId = current.activeConditionEpoch?.id,
            clock = clock,
            consumedPendingSha256 = pending?.encodedSha256,
            consumePending = pending != null,
            extraMutations = trustedVector.resources.map(::upsertResource) +
                cleanupAttempts.values.map(::upsertResourceCleanup) +
                resourceAudit.mutations + deadlineUpdate.mutations,
            timerRetirementReason = "QUALITY_GAP_RESET",
            sourceCheckpoints = dropRetrospectiveSourceCheckpoints(prepared.sourceCheckpoints),
        )
        performPostCommitEffectsLocked(
            effects + resourceAudit.effects + deadlineUpdate.effects +
                PostCommitEffects(actionsInactive = pendingActionIdsLocked()),
        )
        finalizePausedResourceCleanupLocked(recovery = true)
        if (requireDocument().state == ExperimentState.PAUSED && timeline.isElapsed(clock)) {
            completePausedAtDeadlineLocked(now)
        }
    }

    private suspend fun commitQualityGapLocked(
        sourceId: EventSourceId,
        reason: SourceQualityGapReason,
        clock: StudyClockCheckpoint,
    ) {
        val now = clock.anchor
        val input = ReducerInput.QualityGap(
            automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            sourceId,
        )
        val reduction = reducer.reduceBatch(program, automationCheckpoint, listOf(input))
        if (reduction.resourceChanges.isNotEmpty() && requireDocument().activeConditionEpoch != null) {
            val effects = appendReductionLocked(
                inputKind = EngineInputKind.SOURCE_OBSERVATION,
                reduction = reduction,
                eventDrafts = listOf(RuntimeEventFactory.qualityGap(sourceId, reason, now)),
                clock = clock,
                timerRetirementReason = "QUALITY_GAP_RESET",
            )
            performPostCommitEffectsLocked(effects)
            val committed = requireDocument()
            val epoch = requireNotNull(committed.activeConditionEpoch)
            val buffer = BarrierInputBuffer(
                committed.nextObservationSequence,
                committed.nextEventSequence,
                epoch.id,
                committed.sourceCheckpoints,
                now,
                null,
            )
            barrierBuffer = buffer
            val drainToken = gate.beginDrain(now)
            check(enqueueBarrier(
                PostCommitBarrier(
                    inputKind = EngineInputKind.SOURCE_OBSERVATION,
                    clock = clock,
                    boundary = now,
                    drainToken = drainToken,
                    buffer = buffer,
                ),
            )) { "The resource barrier coordinator is unavailable" }
        } else {
            val effects = appendReductionLocked(
                inputKind = EngineInputKind.SOURCE_OBSERVATION,
                reduction = reduction,
                eventDrafts = listOf(RuntimeEventFactory.qualityGap(sourceId, reason, now)),
                clock = clock,
                timerRetirementReason = "QUALITY_GAP_RESET",
            )
            performPostCommitEffectsLocked(effects)
        }
    }

    private fun reduceRecordedEvents(
        events: List<RecordedEvent>,
        clock: StudyClockCheckpoint,
        base: AutomationCheckpoint,
    ): ReductionResult {
        if (events.isEmpty()) return emptyReduction(base)
        val reducerClock = reducerClock(clock)
        val inputs = events.mapIndexed { index, event ->
            event.toReducerInput(base.evaluatedThroughSequence + index + 1L, reducerClock)
        }
        return reducer.reduceBatch(program, base, inputs)
    }

    private fun RecordedEvent.toReducerInput(sequence: Long, clock: ReducerClock): ReducerInput.Event {
        val registry = ProtocolEventSourceRegistry[type.sourceId.value]!!
        val contract = registry.events.getValue(type.eventType)
        val primary = contract.primarySourceTimeField?.let(fields::get)?.let { encoded ->
            when (contract.primarySourceBasis) {
                RegistryClockBasis.UTC_WALL -> encoded.toLongOrNull()?.let { ResearchTime(it, observedTime.elapsedRealtimeNanos, observedTime.bootSessionId) }
                RegistryClockBasis.CONTINUOUS_MONOTONIC_SINCE_BOOT,
                RegistryClockBasis.BOOT_SESSION_MONOTONIC,
                -> encoded.toLongOrNull()?.let { ResearchTime(observedTime.wallTimeUtcMillis, it, observedTime.bootSessionId) }
                else -> null
            }
        }
        val event = AutomationEvent(sequence, type, observedTime, primary, fields)
        return ReducerInput.Event(sequence, clock, event)
    }

    private suspend fun appendReductionLocked(
        inputKind: EngineInputKind,
        reduction: ReductionResult,
        prepared: PreparedSources = PreparedSources.empty(requireDocument()),
        eventDrafts: List<EventDraft> = emptyList(),
        state: ExperimentState = requireDocument().state,
        epoch: ConditionEpoch? = requireDocument().activeConditionEpoch,
        eventConditionEpochId: ConditionEpochId? = epoch?.id,
        clock: StudyClockCheckpoint = requireNotNull(requireDocument().clockCheckpoint),
        consumedPendingSha256: String? = null,
        consumePending: Boolean = false,
        extraMutations: List<RuntimeMutation> = emptyList(),
        timerRetirementReason: String = "CANCELLED",
        sourceCheckpoints: Map<EventSourceId, SourceCheckpoint> = prepared.sourceCheckpoints,
    ): PostCommitEffects {
        val conditionDigest = reduction.checkpoint.digest()
        val causalSequence = reduction.checkpoint.evaluatedThroughSequence.coerceAtLeast(1)
        val generatedEvents = buildList {
            reduction.audits.mapNotNullTo(this) {
                RuntimeEventFactory.automationAudit(it, conditionDigest, causalSequence, clock.anchor)
            }
            reduction.actionRequests.forEach {
                add(RuntimeEventFactory.actionRequested(it, conditionDigest, causalSequence, clock.anchor))
            }
            val oldTimers = automationCheckpoint.timers
            reduction.timerIntents.forEach { intent ->
                when (intent) {
                    is TimerIntent.Schedule -> add(RuntimeEventFactory.timerScheduled(intent.timer, clock.anchor))
                    is TimerIntent.Retire -> oldTimers[intent.timerId]?.let { timer ->
                        add(RuntimeEventFactory.timerRetired(timer, timerRetirementReason, clock.anchor))
                    }
                }
            }
        }
        val actions = reduction.actionRequests.map { request ->
            DurableActionInvocation(
                actionId = request.actionId,
                automationId = request.automationId,
                interventionId = request.interventionId,
                causalSequence = causalSequence,
                logicalDeadlineUtcMillis = request.logicalDeadlineUtcMillis,
                expiresAtUtcMillis = request.expiresAtUtcMillis,
                conditionSha256 = conditionDigest,
                generation = 1uL,
                requestedAt = clock.anchor,
                openedAt = null,
                state = RuntimeActionState.READY,
                failureReason = null,
            )
        }
        val timerMutations = timerMutations(automationCheckpoint.timers, reduction.checkpoint.timers)
        val effects = appendCommitLocked(
            inputKind = inputKind,
            checkpoint = reduction.checkpoint,
            prepared = prepared,
            eventDrafts = eventDrafts + generatedEvents,
            state = state,
            epoch = epoch,
            eventConditionEpochId = eventConditionEpochId,
            clock = clock,
            consumedPendingSha256 = consumedPendingSha256,
            consumePending = consumePending,
            extraMutations = extraMutations + timerMutations + actions.map(::upsertAction),
            sourceCheckpoints = sourceCheckpoints,
        )
        return effects.copy(
            timerIntents = reduction.timerIntents,
            actionsReady = actions.map(DurableActionInvocation::actionId),
            timerProductionRequests = reduction.timerProductionRequests,
        )
    }

    private suspend fun appendCommitLocked(
        inputKind: EngineInputKind,
        checkpoint: AutomationCheckpoint,
        prepared: PreparedSources = PreparedSources.empty(requireDocument()),
        eventDrafts: List<EventDraft> = emptyList(),
        state: ExperimentState = requireDocument().state,
        epoch: ConditionEpoch? = requireDocument().activeConditionEpoch,
        eventConditionEpochId: ConditionEpochId? = epoch?.id,
        clock: StudyClockCheckpoint? = requireDocument().clockCheckpoint,
        consumedPendingSha256: String? = null,
        consumePending: Boolean = false,
        extraMutations: List<RuntimeMutation> = emptyList(),
        uploadedThroughCommit: Long = requireDocument().uploadedThroughCommit,
        sourceCheckpoints: Map<EventSourceId, SourceCheckpoint> = prepared.sourceCheckpoints,
    ): PostCommitEffects {
        val current = requireDocument()
        var nextSequence = if (prepared.events.isEmpty()) current.nextEventSequence else prepared.nextEventSequence
        val generated = eventDrafts.map { draft ->
            RecordedEvent(nextSequence++, draft.type, draft.observedTime, eventConditionEpochId, draft.fields)
        }
        val events = prepared.events + generated
        val mutations = buildMap<RuntimeComponentKey, RuntimeMutation> {
            checkpointMutations(current, checkpoint).forEach { checkpointMutation ->
                put(checkpointMutation.key, checkpointMutation)
            }
            extraMutations.forEach { mutation -> put(mutation.key, mutation) }
        }.values.sortedBy(RuntimeMutation::key)
        val commitSequence = current.nextCommitSequence
        val projection = RuntimeProjection(
            state = state,
            revision = commitSequence,
            nextCommitSequence = commitSequence + 1,
            nextObservationSequence = prepared.nextObservationSequence,
            nextEventSequence = nextSequence,
            sourceCheckpoints = sourceCheckpoints,
            clockCheckpoint = clock,
            activeConditionEpoch = epoch,
            lifetimeDataEventCount = current.lifetimeDataEventCount + prepared.events.size,
            uploadedThroughCommit = uploadedThroughCommit,
            evaluatedThroughCommit = commitSequence,
            retainedFromCommit = current.retainedFromCommit,
        )
        val commit = EngineCommit(
            commitSequence = commitSequence,
            previousCommitSha256 = current.lastCommitSha256,
            inputKind = inputKind,
            consumedPendingInputSha256 = consumedPendingSha256,
            sourceObservations = prepared.observations,
            events = events,
            mutations = mutations,
            committedAt = clock?.anchor ?: clocks.now(),
            successorProjection = projection,
            resultingCheckpointSha256 = checkpoint.digest(),
            commitSha256 = ZERO_DIGEST,
        ).withComputedDigest()
        val successor = current.advance(commit)
        if (consumePending) {
            store.appendCommitConsumingPending(commit, successor)
        } else {
            store.appendCommit(commit, successor)
        }
        document = successor
        automationCheckpoint = checkpoint
        applyComponentMutations(mutations)
        require(
            resourceCleanupAttempts.isEmpty() ||
                (successor.state == ExperimentState.PAUSED && successor.activeConditionEpoch == null),
        ) { "Resource cleanup components require a closed paused runtime" }
        publishSnapshot()
        return PostCommitEffects()
    }

    private suspend fun materializeTimerRequestsLocked(requests: List<TimerProductionRequest>) {
        requests.forEach { request ->
            when (val produced = timerProducer.produce(request)) {
                is TimerProductionResult.Materialized -> {
                    if (automationCheckpoint.timers[produced.timer.id] != null) return@forEach
                    val current = requireDocument()
                    val clock = requireNotNull(current.clockCheckpoint)
                    val input = ReducerInput.TimerMaterialized(
                        automationCheckpoint.evaluatedThroughSequence + 1,
                        reducerClock(clock),
                        produced.timer,
                    )
                    val reduction = reducer.reduceBatch(program, automationCheckpoint, listOf(input))
                    val effects = appendReductionLocked(
                        EngineInputKind.RANDOM_SELECTION,
                        reduction,
                        clock = clock,
                    )
                    performPostCommitEffectsLocked(effects)
                }
                TimerProductionResult.Deferred, TimerProductionResult.Exhausted -> Unit
            }
        }
    }

    private suspend fun performPostCommitEffectsLocked(effects: PostCommitEffects) {
        effects.timerIntents.forEach { intent ->
            when (intent) {
                is TimerIntent.Schedule -> timerWakeups.schedule(intent.timer)
                is TimerIntent.Retire -> timerWakeups.retire(intent.timerId, intent.generation)
            }
        }
        if (requireDocument().state == ExperimentState.RUNNING) {
            effects.actionsReady.distinct().sorted().forEach { actionId ->
                val action = actionInvocations[actionId]
                    ?.takeIf { it.state in PENDING_ACTION_STATES }
                    ?: return@forEach
                val now = clocks.now()
                if (now.wallTimeUtcMillis >= action.expiresAtUtcMillis) {
                    expireActionLocked(action, now)
                    return@forEach
                }
                try {
                    actionNotifier.onActionReady(actionId)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    val result = recordActionResultLocked(
                        action,
                        succeeded = false,
                        reportedFailure = ActionExecutionFailure.RECONCILIATION_FAILED,
                        now = clocks.now(),
                    )
                    if (result is RuntimeCommandResult.FailedClosed) {
                        throw ContainedActionFailure(result.reason)
                    }
                }
            }
        }
        effects.actionsInactive.distinct().sorted().takeIf { it.isNotEmpty() }?.let { actionIds ->
            actionNotifier.onActionsInactive(actionIds)
        }
        if (effects.timerProductionRequests.isNotEmpty()) {
            materializeTimerRequestsLocked(effects.timerProductionRequests)
        }
    }

    private fun pendingActionIdsLocked(): List<String> = actionInvocations.values
        .filter { it.state in PENDING_ACTION_STATES }
        .map(DurableActionInvocation::actionId)

    private suspend fun retractInactiveActionsLocked() {
        check(requireDocument().state != ExperimentState.RUNNING) {
            "RUNNING actions must be reconciled, not retracted"
        }
        pendingActionIdsLocked().takeIf { it.isNotEmpty() }?.let { actionIds ->
            actionNotifier.onActionsInactive(actionIds)
        }
    }

    private fun prepareSources(
        document: RuntimeDocument,
        submissions: List<SourceSubmission>,
        conditionEpochId: ConditionEpochId,
        startingCheckpoints: Map<EventSourceId, SourceCheckpoint>,
        flushCursors: Map<EventSourceId, String?> = emptyMap(),
        semanticEventOrder: List<SourceSubmission> = submissions,
    ): PreparedSources {
        val submissionsByIdentity = submissions.uniqueByIdentity("Submission admission order")
        val semanticSubmissionsByIdentity = semanticEventOrder.uniqueByIdentity("Submission semantic event order")
        require(submissionsByIdentity == semanticSubmissionsByIdentity) {
            "Submission semantic event order is not an exact admission-order permutation"
        }
        if (submissions.isEmpty()) return PreparedSources.empty(document)
        val checkpoints = startingCheckpoints.toMutableMap()
        val observations = mutableListOf<SourceObservation>()
        val events = mutableListOf<RecordedEvent>()
        val eventRanges = mutableMapOf<SourceSubmissionIdentity, EventSequenceRange>()
        var observationSequence = document.nextObservationSequence
        var eventSequence = document.nextEventSequence
        semanticEventOrder.forEach { submission ->
            validateSubmission(submission, eventSequence, conditionEpochId)
            val first = eventSequence.takeIf { submission.events.isNotEmpty() }
            submission.events.forEach { event ->
                events += RecordedEvent(eventSequence++, event.type, event.observedTime, conditionEpochId, event.fields)
            }
            val last = (eventSequence - 1).takeIf { submission.events.isNotEmpty() }
            check(eventRanges.put(submission.identity(), EventSequenceRange(first, last)) == null) {
                "Submission event range was assigned twice"
            }
        }
        submissions.forEach { submission ->
            val resource = appliedResources[ResourceKey(ResourceKind.COLLECTOR, submission.sourceId.value)]
                ?: throw IllegalArgumentException("Collector source has no applied resource")
            require(resource.status == AppliedResourceStatus.APPLIED) { "Collector resource is inactive" }
            require(resource.desiredGeneration.value.toLong() == submission.resourceGeneration) {
                "Collector batch generation is stale"
            }
            val prior = checkpoints[submission.sourceId]
            val expectedOrdinal = if (prior == null || prior.resourceGeneration != submission.resourceGeneration) {
                0L
            } else {
                prior.nextProducerOrdinal
            }
            require(submission.producerOrdinal == expectedOrdinal) { "Collector producer ordinal is not contiguous" }
            if (prior != null && prior.resourceGeneration == submission.resourceGeneration) {
                val oldCoverage = prior.coverage
                val newCoverage = submission.coverage
                if (oldCoverage != null && newCoverage != null &&
                    (oldCoverage.clockBasis != newCoverage.clockBasis || oldCoverage.endExclusive != newCoverage.startInclusive)
                ) {
                    throw SourceGap(SourceQualityGapReason.RETROSPECTIVE_COVERAGE_GAP)
                }
            }
            val eventRange = checkNotNull(eventRanges[submission.identity()]) {
                "Submission has no semantic event range"
            }
            observations += SourceObservation(
                observationSequence = observationSequence++,
                sourceId = submission.sourceId,
                schemaVersion = submission.schemaVersion,
                resourceGeneration = submission.resourceGeneration,
                admissionKind = submission.admissionKind,
                producerOrdinal = submission.producerOrdinal,
                conditionEpochId = conditionEpochId,
                eventCount = submission.events.size,
                firstEventSequence = eventRange.first,
                lastEventSequence = eventRange.last,
                coverage = submission.coverage,
                encodedSha256 = submissionDigest(submission, conditionEpochId),
            )
            checkpoints[submission.sourceId] = SourceCheckpoint(
                sourceId = submission.sourceId,
                resourceGeneration = submission.resourceGeneration,
                nextProducerOrdinal = submission.producerOrdinal + 1,
                coverage = submission.coverage ?: prior?.coverage,
                cursor = if (flushCursors.containsKey(submission.sourceId)) {
                    flushCursors[submission.sourceId]
                } else {
                    prior?.cursor
                },
            )
        }
        require(flushCursors.keys.all { sourceId ->
            observations.count {
                it.sourceId == sourceId && it.admissionKind == ObservationAdmissionKind.BARRIER_FLUSH
            } == 1
        }) { "A retrospective flush cursor requires a committed coverage observation" }
        return PreparedSources(observations, events, checkpoints.toSortedMap(), observationSequence, eventSequence)
    }

    private fun preparePending(current: RuntimeDocument, pending: PendingEngineInput): PreparedSources {
        val submissions = pending.submissions.map(SourceSubmission::from)
        val semanticEventOrder = if (
            submissions.size > 1 && submissions.first().admissionKind == ObservationAdmissionKind.NORMAL
        ) {
            submissions.drop(1) + submissions.first()
        } else {
            submissions
        }
        return prepareSources(
            document = current,
            submissions = submissions,
            conditionEpochId = pending.conditionEpochId,
            startingCheckpoints = current.sourceCheckpoints,
            semanticEventOrder = semanticEventOrder,
        )
    }

    private fun validateSubmission(
        submission: SourceSubmission,
        firstSequence: Long,
        epochId: ConditionEpochId,
    ) {
        val source = requireNotNull(ProtocolEventSourceRegistry[submission.sourceId.value]) { "Unknown source" }
        require(source.sourceKind == RegistrySourceKind.COLLECTOR) { "Event sink only admits collector sources" }
        require(source.emissionAuthority == RegistryEmissionAuthority.SOURCE_PLUGIN_ONLY) { "Invalid source authority" }
        require(source.schemaVersion == submission.schemaVersion) { "Source schema mismatch" }
        require(submission.events.isNotEmpty() || submission.coverage != null) { "Empty source input needs coverage" }
        if (source.isRetrospective) requireNotNull(submission.coverage) { "Retrospective source needs coverage" }
        var bytes = 0L
        submission.events.forEachIndexed { index, event ->
            val sequence = firstSequence + index
            require(source.accepts(event, sequence, epochId)) { "Collector event contract violation" }
            bytes += event.protocolEncodedBytes(sequence, epochId)
        }
        require(bytes <= MAX_OBSERVATION_ENCODED_BYTES) { "Collector batch exceeds encoded-size bound" }
        submission.events.zipWithNext().forEach { (left, right) ->
            require(
                left.observedTime.bootSessionId == right.observedTime.bootSessionId &&
                    left.observedTime.elapsedRealtimeNanos <= right.observedTime.elapsedRealtimeNanos
            ) { "Collector batch source time is not ordered" }
        }
    }

    private fun advanceClock(current: RuntimeDocument, now: ResearchTime): StudyClockCheckpoint {
        val old = current.clockCheckpoint ?: return initialClock(now)
        return when (val advanced = timeline.advance(old, current.state, now, clocks.trustedUtcMillis())) {
            is StudyTimelineAdvance.Advanced -> advanced.checkpoint
            StudyTimelineAdvance.TrustedUtcRequired -> throw ClockDiscontinuity()
        }
    }

    private fun initialClock(now: ResearchTime): StudyClockCheckpoint =
        timeline.startedAt(now, clocks.trustedUtcMillis(), initialZoneId)

    /**
     * Advances only the calendar lifetime during fail-closed recovery. The interval after the last
     * authenticated checkpoint was never verified RUNNING time, so it cannot advance active-time
     * automations. An untrusted cross-boot interval has no defensible coordinate at all: retain the
     * last reliable anchor so Resume still requires an explicit trusted re-anchor.
     */
    private fun recoveryClock(current: RuntimeDocument, now: ResearchTime): StudyClockCheckpoint {
        val checkpoint = current.clockCheckpoint
            ?: return initialClock(now).copy(deadlineUtcTrusted = false)
        return when (
            val advanced = timeline.advance(
                checkpoint,
                ExperimentState.PAUSED,
                now,
                clocks.trustedUtcMillis(),
            )
        ) {
            is StudyTimelineAdvance.Advanced -> advanced.checkpoint
            StudyTimelineAdvance.TrustedUtcRequired -> checkpoint
        }
    }

    /** Reboots never bridge a paused lifetime from ordinary wall time. */
    private suspend fun reanchorPausedAcrossBootLocked(): Boolean {
        val current = requireDocument()
        require(current.state == ExperimentState.PAUSED) { "Paused re-anchor requires PAUSED" }
        val oldClock = requireNotNull(current.clockCheckpoint) { "Started study has no clock checkpoint" }
        val now = clocks.now()
        if (oldClock.anchor.bootSessionId == now.bootSessionId &&
            now.elapsedRealtimeNanos >= oldClock.anchor.elapsedRealtimeNanos
        ) {
            if (timeline.isElapsed(oldClock)) completePausedAtDeadlineLocked(now)
            return true
        }
        val advanced = timeline.advance(
            oldClock,
            ExperimentState.PAUSED,
            now,
            clocks.trustedUtcMillis(),
        ) as? StudyTimelineAdvance.Advanced ?: return false
        val clock = advanced.checkpoint.copy(zoneId = canonicalZoneId(zoneId()))
        val input = ReducerInput.QualityGap(
            automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            EventSourceId("study_runtime.v1"),
        )
        val reduction = reducer.reduceBatch(program, automationCheckpoint, listOf(input))
        val deadlineUpdate = if (timeline.isElapsed(clock)) {
            DeadlineTimerUpdate.EMPTY
        } else {
            reconcileStudyDeadlineTimer(clock, reduction.checkpoint.evaluatedThroughSequence, "QUALITY_GAP_RESET")
        }
        val effects = appendReductionLocked(
            inputKind = EngineInputKind.RECOVERY,
            reduction = reduction,
            eventDrafts = listOf(
                RuntimeEventFactory.qualityGap(
                    EventSourceId("study_runtime.v1"),
                    SourceQualityGapReason.PROCESS_RECOVERY,
                    now,
                ),
            ) + deadlineUpdate.events,
            state = ExperimentState.PAUSED,
            epoch = null,
            clock = clock,
            extraMutations = deadlineUpdate.mutations,
            timerRetirementReason = "QUALITY_GAP_RESET",
            sourceCheckpoints = dropRetrospectiveSourceCheckpoints(current.sourceCheckpoints),
        )
        performPostCommitEffectsLocked(effects + deadlineUpdate.effects)
        if (timeline.isElapsed(clock)) {
            completePausedAtDeadlineLocked(now)
        }
        return true
    }

    private suspend fun completePausedAtDeadlineLocked(now: ResearchTime) {
        val current = requireDocument()
        require(current.state == ExperimentState.PAUSED) { "Deadline completion requires PAUSED" }
        require(timeline.isElapsed(requireNotNull(current.clockCheckpoint))) {
            "Paused study duration has not elapsed"
        }
        val timer = requireNotNull(studyDeadlineTimer) { "Elapsed paused study has no durable deadline" }
        stopSessionLocked(
            terminalState = ExperimentState.COMPLETED,
            requestEvent = "STUDY_COMPLETE_REQUESTED",
            resultEvent = "STUDY_COMPLETED",
            transitionReason = "STUDY_DURATION_ELAPSED",
            epochReason = "STUDY_COMPLETED",
            operationNow = now,
            causalEvents = listOf(RuntimeEventFactory.timerDue(timer, now)),
            deadlineRetirementReason = "FIRED",
            inputKind = EngineInputKind.TIMER_WAKE,
        )
    }

    private fun advanceClockForTerminal(current: RuntimeDocument, now: ResearchTime): StudyClockCheckpoint =
        runCatching { advanceClock(current, now) }.getOrElse { recoveryClock(current, now) }

    private fun deadlineCollectionBoundary(timer: DurableTimer): ResearchTime {
        requireStudyDeadlineTimer(timer)
        val target = timer.target as TimerTarget.SameBootMonotonic
        require(target.elapsedRealtimeNanos > 0) { "Study deadline cannot precede the monotonic epoch" }
        return ResearchTime(
            wallTimeUtcMillis = requireNotNull(timer.logicalDeadlineUtcMillis),
            elapsedRealtimeNanos = target.elapsedRealtimeNanos - 1,
            bootSessionId = target.bootSessionId,
        )
    }

    private fun dropRetrospectiveSourceCheckpoints(
        checkpoints: Map<EventSourceId, SourceCheckpoint>,
    ): Map<EventSourceId, SourceCheckpoint> = checkpoints.filterKeys { sourceId ->
        ProtocolEventSourceRegistry[sourceId.value]?.isRetrospective != true
    }

    private fun openAdmission(epochId: ConditionEpochId, clock: StudyClockCheckpoint) {
        check(!timeline.isElapsed(clock)) { "Cannot open admission after the signed study duration" }
        gate.open(epochId, timeline.sameBootDeadline(clock))
    }

    private fun reducerClock(clock: StudyClockCheckpoint): ReducerClock = ReducerClock(
        now = clock.anchor,
        activeElapsedNanos = clock.activeRunningElapsedNanos,
        calendarElapsedNanos = clock.calendarElapsedNanos,
        zoneId = clock.zoneId,
    )

    private fun canonicalZoneId(value: String): String = ZoneId.of(value).id.also { canonical ->
        require(canonical == value && (canonical == "UTC" || '/' in canonical)) {
            "Runtime requires a canonical IANA zone ID"
        }
    }

    private fun lifecycleInputsToPause(
        checkpoint: AutomationCheckpoint,
        clock: ReducerClock,
    ): List<ReducerInput.Lifecycle> {
        var sequence = checkpoint.evaluatedThroughSequence
        return when (checkpoint.lifecycle) {
            StudySessionState.READY -> listOf(
                ReducerInput.Lifecycle(++sequence, clock, StudySessionState.WITHDRAWN),
            )
            StudySessionState.ACTIVATING, StudySessionState.RUNNING -> listOf(
                ReducerInput.Lifecycle(++sequence, clock, StudySessionState.PAUSING),
                ReducerInput.Lifecycle(++sequence, clock, StudySessionState.PAUSED),
            )
            StudySessionState.PAUSING -> listOf(
                ReducerInput.Lifecycle(++sequence, clock, StudySessionState.PAUSED),
            )
            StudySessionState.PAUSED -> error("Study is already paused")
            StudySessionState.COMPLETED, StudySessionState.WITHDRAWN -> error("Terminal study cannot pause")
        }
    }

    private fun restoreComponents(runtime: RuntimeDocument) {
        val checkpointParts = runtime.components
            .filterKeys { it.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT && it.id.startsWith("main") }
            .toSortedMap()
            .values
        automationCheckpoint = if (checkpointParts.isEmpty()) {
            AutomationCheckpoint()
        } else {
            RuntimeComponentCodec.decodeCheckpoint(checkpointParts.joinToString(separator = ""))
        }
        actionInvocations = runtime.components.filterKeys { it.kind == RuntimeComponentKind.ACTION_INVOCATION }
            .values.map(RuntimeComponentCodec::decodeAction)
            .associateByTo(sortedMapOf(), DurableActionInvocation::actionId)
        latestUploadAcknowledgement = runtime.components.entries
            .singleOrNull { it.key.kind == RuntimeComponentKind.UPLOAD_ACKNOWLEDGEMENT }
            ?.value
            ?.let(RuntimeComponentCodec::decodeUploadAcknowledgement)
        appliedResources = runtime.components.filterKeys { it.kind == RuntimeComponentKind.RESOURCE }
            .values.map(RuntimeComponentCodec::decodeResource)
            .associateByTo(sortedMapOf(), AppliedResourceState::key)
        val cleanupEntries = runtime.components.filterKeys {
            it.kind == RuntimeComponentKind.RESOURCE_CLEANUP
        }.map { (componentKey, encoded) ->
            RuntimeComponentCodec.decodeResourceCleanup(encoded).also { cleanup ->
                require(componentKey == resourceCleanupComponentKey(cleanup.key)) {
                    "Resource cleanup component key mismatch"
                }
            }
        }
        require(cleanupEntries.map(DurableResourceCleanup::key).distinct().size == cleanupEntries.size) {
            "Duplicate resource cleanup components"
        }
        resourceCleanupAttempts = cleanupEntries.associateByTo(sortedMapOf(), DurableResourceCleanup::key)
        require(
            resourceCleanupAttempts.isEmpty() ||
                (runtime.state == ExperimentState.PAUSED && runtime.activeConditionEpoch == null),
        ) { "Resource cleanup components require a closed paused runtime" }
        resourceAuditTimers = runtime.components.filterKeys {
            it.kind == RuntimeComponentKind.RESOURCE_AUDIT_TIMER
        }
            .values.map(RuntimeComponentCodec::decodeTimer)
            .filter { it.producerKey.startsWith(RESOURCE_AUDIT_PRODUCER_PREFIX) }
            .associateByTo(sortedMapOf(), DurableTimer::id)
        studyDeadlineTimer = runtime.components.entries
            .singleOrNull { it.key.kind == RuntimeComponentKind.STUDY_DEADLINE_TIMER }
            ?.also { require(it.key.id == STUDY_DEADLINE_COMPONENT_ID) { "Invalid study deadline component key" } }
            ?.value
            ?.let(RuntimeComponentCodec::decodeTimer)
            ?.also(::requireStudyDeadlineTimer)
    }

    private fun applyComponentMutations(mutations: List<RuntimeMutation>) {
        mutations.forEach { mutation ->
            when (mutation.key.kind) {
                RuntimeComponentKind.ACTION_INVOCATION -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> RuntimeComponentCodec.decodeAction(requireNotNull(mutation.canonicalValue)).also {
                        actionInvocations[it.actionId] = it
                    }
                    RuntimeMutationOperation.REMOVE -> actionInvocations.remove(mutation.key.id)
                }
                RuntimeComponentKind.RESOURCE -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> RuntimeComponentCodec.decodeResource(requireNotNull(mutation.canonicalValue)).also {
                        appliedResources[it.key] = it
                    }
                    RuntimeMutationOperation.REMOVE -> Unit
                }
                RuntimeComponentKind.RESOURCE_CLEANUP -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> RuntimeComponentCodec.decodeResourceCleanup(
                        requireNotNull(mutation.canonicalValue),
                    ).also {
                        require(mutation.key == resourceCleanupComponentKey(it.key)) {
                            "Resource cleanup mutation key mismatch"
                        }
                        resourceCleanupAttempts[it.key] = it
                    }
                    RuntimeMutationOperation.REMOVE -> resourceCleanupAttempts.remove(
                        cleanupResourceKey(mutation.key.id),
                    )
                }
                RuntimeComponentKind.UPLOAD_ACKNOWLEDGEMENT -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> {
                        latestUploadAcknowledgement = RuntimeComponentCodec.decodeUploadAcknowledgement(
                            requireNotNull(mutation.canonicalValue),
                        )
                    }
                    RuntimeMutationOperation.REMOVE -> latestUploadAcknowledgement = null
                }
                RuntimeComponentKind.RESOURCE_AUDIT_TIMER -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> RuntimeComponentCodec.decodeTimer(
                        requireNotNull(mutation.canonicalValue),
                    ).takeIf { it.producerKey.startsWith(RESOURCE_AUDIT_PRODUCER_PREFIX) }?.also {
                        resourceAuditTimers[it.id] = it
                    }
                    RuntimeMutationOperation.REMOVE -> resourceAuditTimers.remove(mutation.key.id)
                }
                RuntimeComponentKind.STUDY_DEADLINE_TIMER -> {
                    require(mutation.key.id == STUDY_DEADLINE_COMPONENT_ID) {
                        "Invalid study deadline mutation key"
                    }
                    when (mutation.operation) {
                        RuntimeMutationOperation.UPSERT -> {
                            studyDeadlineTimer = RuntimeComponentCodec.decodeTimer(
                                requireNotNull(mutation.canonicalValue),
                            ).also(::requireStudyDeadlineTimer)
                        }
                        RuntimeMutationOperation.REMOVE -> studyDeadlineTimer = null
                    }
                }
                else -> Unit
            }
        }
    }

    private fun bindTerminalListeners() {
        hosts.values.forEach { host ->
            host.actuator?.setTerminalFailureListener { failure ->
                gate.forceClose()
                terminalFailures.trySend(failure)
            }
        }
    }

    private fun startTerminalConsumer() {
        if (terminalJob != null) return
        terminalJob = scope.launch {
            for (failure in terminalFailures) {
                command {
                    val reason = if (failure.key.id == "traffic-shaping.v1") {
                        SafetyPauseReason.TRAFFIC_CONDITION_LOST
                    } else {
                        SafetyPauseReason.REQUIRED_RESOURCE_FAILURE
                    }
                    safetyPauseLocked(reason, null, failure)
                    RuntimeCommandResult.FailedClosed(reason)
                }
            }
        }
    }

    private fun startBarrierConsumer() {
        if (barrierJob != null) return
        barrierJob = scope.launch {
            for (request in coordinatedBarriers) {
                try {
                    mutex.withLock {
                        check(activeBarrier === request) { "Barrier coordinator lost ownership" }
                        completeCoordinatedBarrierLocked(request)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ContainedActionFailure) {
                    // The ACTION_FAILED commit and WORK_SCHEDULING_FAILURE safety pause are
                    // already durable. Do not relabel that contained failure as storage damage.
                } catch (_: Throwable) {
                    gate.forceClose()
                    runCatching {
                        mutex.withLock {
                            val pending = store.loadPendingInput()
                            if (pending != null) {
                                recoverFailClosedLocked(pending)
                            } else {
                                safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
                            }
                        }
                    }
                } finally {
                    if (activeBarrier === request) activeBarrier = null
                    request.completion.complete(Unit)
                }
            }
        }
    }

    private fun enqueueBarrier(request: CoordinatedBarrier): Boolean {
        if (activeBarrier != null) return false
        activeBarrier = request
        if (coordinatedBarriers.trySend(request).isSuccess) return true
        if (activeBarrier === request) {
            activeBarrier = null
        }
        request.completion.complete(Unit)
        return false
    }

    private suspend fun <T : RuntimeCommandResult> command(block: suspend () -> T): RuntimeCommandResult {
        if (!initialized.get()) return RuntimeCommandResult.Rejected(RuntimeCommandRejection.NOT_INITIALIZED)
        return try {
            while (true) {
                activeBarrier?.completion?.await()
                mutex.lock()
                val barrier = activeBarrier
                if (barrier == null) break
                mutex.unlock()
                barrier.completion.await()
            }
            try {
                block()
            } finally {
                mutex.unlock()
            }
        } catch (contained: ContainedActionFailure) {
            RuntimeCommandResult.FailedClosed(contained.reason)
        } catch (_: Throwable) {
            gate.forceClose()
            runCatching {
                mutex.withLock {
                    document?.takeIf { it.state !in TERMINAL_STATES && it.state != ExperimentState.PAUSED }
                        ?.let { safetyPauseLocked(SafetyPauseReason.STORAGE_FAILURE, null) }
                }
            }
            RuntimeCommandResult.FailedClosed(SafetyPauseReason.STORAGE_FAILURE)
        }
    }

    private fun validateIdentity(runtime: RuntimeDocument) {
        require(runtime.experimentId == study.experimentId) { "Experiment ID mismatch" }
        require(runtime.configurationId == study.configurationId) { "Configuration ID mismatch" }
        require(runtime.configurationSha256 == study.configurationSha256) { "Configuration digest mismatch" }
        require(runtime.assignedParticipantId == study.assignedParticipantId) { "Participant assignment mismatch" }
    }

    private fun currentAppliedVector(): AppliedResourceVector = AppliedResourceVector(
        hosts.keys.map { key ->
            appliedResources[key] ?: inactiveResource(key, automationCheckpoint.desiredResources[key]?.generation ?: ResourceGeneration(1uL))
        }.sortedBy(AppliedResourceState::key),
    )

    private fun newEpoch(vector: AppliedResourceVector, now: ResearchTime): ConditionEpoch = ConditionEpoch(
        id = ConditionEpochId(entropy.next(RuntimeEntropyKind.CONDITION_EPOCH_UUID)),
        configurationSha256 = study.configurationSha256,
        appliedResourceVectorSha256 = vector.conditionDigest.value,
        activatedAt = now,
    )

    private fun inactiveResourceMutations(desired: Map<ResourceKey, DesiredProfile>): List<RuntimeMutation> =
        hosts.keys.map { key ->
            val generation = desired[key]?.generation
                ?: appliedResources[key]?.desiredGeneration
                ?: ResourceGeneration(1uL)
            upsertResource(inactiveResource(key, generation))
        }

    private fun inactiveResource(key: ResourceKey, generation: ResourceGeneration) = AppliedResourceState(
        key,
        generation,
        null,
        null,
        AppliedResourceStatus.INACTIVE,
        null,
    )

    private fun optionalFailure(
        key: ResourceKey,
        desired: DesiredResourceState,
        reason: String,
    ) = AppliedResourceState(
        key,
        desired.generation,
        desired.profile!!.id,
        null,
        AppliedResourceStatus.OPTIONAL_FAILED,
        reason,
    )

    private fun upsertResource(resource: AppliedResourceState) = RuntimeMutation(
        resourceComponentKey(resource.key),
        RuntimeMutationOperation.UPSERT,
        RuntimeComponentCodec.encodeResource(resource),
    )

    private fun upsertResourceCleanup(cleanup: DurableResourceCleanup) = RuntimeMutation(
        resourceCleanupComponentKey(cleanup.key),
        RuntimeMutationOperation.UPSERT,
        RuntimeComponentCodec.encodeResourceCleanup(cleanup),
    )

    private fun removeResourceCleanup(key: ResourceKey) = RuntimeMutation(
        resourceCleanupComponentKey(key),
        RuntimeMutationOperation.REMOVE,
        null,
    )

    private fun upsertAction(action: DurableActionInvocation) = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.ACTION_INVOCATION, action.actionId),
        RuntimeMutationOperation.UPSERT,
        RuntimeComponentCodec.encodeAction(action),
    )

    private fun upsertUploadAcknowledgement(acknowledgement: DurableUploadAcknowledgement) = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.UPLOAD_ACKNOWLEDGEMENT, "latest"),
        RuntimeMutationOperation.UPSERT,
        RuntimeComponentCodec.encodeUploadAcknowledgement(acknowledgement),
    )

    private fun upsertResourceAuditTimer(timer: DurableTimer) = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.RESOURCE_AUDIT_TIMER, timer.id),
        RuntimeMutationOperation.UPSERT,
        RuntimeComponentCodec.encodeTimer(timer),
    )

    private fun removeResourceAuditTimer(timerId: String) = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.RESOURCE_AUDIT_TIMER, timerId),
        RuntimeMutationOperation.REMOVE,
        null,
    )

    private fun upsertStudyDeadlineTimer(timer: DurableTimer) = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.STUDY_DEADLINE_TIMER, STUDY_DEADLINE_COMPONENT_ID),
        RuntimeMutationOperation.UPSERT,
        RuntimeComponentCodec.encodeTimer(timer.also(::requireStudyDeadlineTimer)),
    )

    private fun removeStudyDeadlineTimer() = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.STUDY_DEADLINE_TIMER, STUDY_DEADLINE_COMPONENT_ID),
        RuntimeMutationOperation.REMOVE,
        null,
    )

    private fun timerMutations(
        before: Map<String, DurableTimer>,
        after: Map<String, DurableTimer>,
    ): List<RuntimeMutation> = buildList {
        (before.keys - after.keys).sorted().forEach { timerId ->
            add(RuntimeMutation(RuntimeComponentKey(RuntimeComponentKind.TIMER, timerId), RuntimeMutationOperation.REMOVE, null))
        }
        after.toSortedMap().forEach { (timerId, timer) ->
            if (before[timerId] != timer) {
                add(
                    RuntimeMutation(
                        RuntimeComponentKey(RuntimeComponentKind.TIMER, timerId),
                        RuntimeMutationOperation.UPSERT,
                        RuntimeComponentCodec.encodeTimer(timer),
                    ),
                )
            }
        }
    }

    private fun checkpointMutations(
        current: RuntimeDocument,
        checkpoint: AutomationCheckpoint,
    ): List<RuntimeMutation> {
        val encoded = RuntimeComponentCodec.encodeCheckpoint(checkpoint)
        val parts = encoded.chunked(MAX_COMPONENT_CHARS)
        val desiredKeys = parts.indices.map { index ->
            RuntimeComponentKey(
                RuntimeComponentKind.AUTOMATION_CHECKPOINT,
                if (index == 0) "main" else "main/${index.toString().padStart(4, '0')}",
            )
        }
        val existingKeys = current.components.keys.filter {
            it.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT && it.id.startsWith("main")
        }
        return buildList {
            parts.forEachIndexed { index, part ->
                add(RuntimeMutation(desiredKeys[index], RuntimeMutationOperation.UPSERT, part))
            }
            (existingKeys - desiredKeys.toSet()).sorted().forEach { stale ->
                add(RuntimeMutation(stale, RuntimeMutationOperation.REMOVE, null))
            }
        }
    }

    private fun resourceComponentKey(key: ResourceKey) = RuntimeComponentKey(
        RuntimeComponentKind.RESOURCE,
        "${key.kind.name.lowercase()}:${key.id}",
    )

    private fun resourceCleanupComponentKey(key: ResourceKey) = RuntimeComponentKey(
        RuntimeComponentKind.RESOURCE_CLEANUP,
        "${key.kind.name.lowercase()}:${key.id}",
    )

    private fun cleanupResourceKey(id: String): ResourceKey {
        val separator = id.indexOf(':')
        require(separator > 0 && separator < id.lastIndex) { "Invalid cleanup resource component ID" }
        return ResourceKey(
            enumValueOf<ResourceKind>(id.substring(0, separator).uppercase()),
            id.substring(separator + 1),
        )
    }

    private fun accepted(observation: SourceObservation) = EmitBatchResult.Accepted(
        observation.observationSequence,
    )

    private fun commandId(kind: String, sequence: Long): String = digest(
        "particeps-runtime-command-v1",
        study.configurationSha256,
        kind,
        sequence.toString(),
    )

    private fun submissionDigest(submission: SourceSubmission, epochId: ConditionEpochId): String {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeCanonicalString("particeps-source-observation-v1")
                output.writeCanonicalString(submission.sourceId.value)
                output.writeInt(submission.schemaVersion)
                output.writeLong(submission.resourceGeneration)
                output.writeLong(submission.producerOrdinal)
                output.writeCanonicalString(epochId.value)
                output.writeBoolean(submission.coverage != null)
                submission.coverage?.let { coverage ->
                    output.writeCanonicalString(coverage.clockBasis.name)
                    output.writeCanonicalString(coverage.startInclusive)
                    output.writeCanonicalString(coverage.endExclusive)
                }
                output.writeInt(submission.events.size)
                submission.events.forEach { event ->
                    output.writeCanonicalString(event.type.eventType)
                    output.writeLong(event.observedTime.wallTimeUtcMillis)
                    output.writeLong(event.observedTime.elapsedRealtimeNanos)
                    output.writeCanonicalString(event.observedTime.bootSessionId)
                    val fields = event.fields.toSortedMap()
                    output.writeInt(fields.size)
                    fields.forEach { (key, value) ->
                        output.writeCanonicalString(key)
                        output.writeCanonicalString(value)
                    }
                }
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun digest(vararg components: String): String {
        val output = components.joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(output).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun timerIsDue(timer: DurableTimer, clock: ReducerClock): Boolean = when (val target = timer.target) {
        is TimerTarget.CalendarUtc -> clock.now.wallTimeUtcMillis >= target.utcMillis
        is TimerTarget.ActiveElapsed -> clock.activeElapsedNanos >= target.elapsedNanos
        is TimerTarget.SameBootMonotonic ->
            clock.now.bootSessionId == target.bootSessionId && clock.now.elapsedRealtimeNanos >= target.elapsedRealtimeNanos
    }

    private fun emptyReduction(checkpoint: AutomationCheckpoint) = ReductionResult(
        checkpoint,
        emptyList(),
        emptyList(),
        emptyList(),
        emptyMap(),
        emptyList(),
    )

    private fun requireDocument(): RuntimeDocument = checkNotNull(document) { "Runtime is not initialized" }
    private fun checkInitialized() = check(initialized.get() && document != null) { "Runtime is not initialized" }

    private fun publishSnapshot() {
        val current = document ?: return
        mutableSnapshot.value = RuntimeSnapshot(
            initialized = true,
            state = current.state,
            revision = current.revision,
            conditionEpochId = current.activeConditionEpoch?.id,
            appliedResourceVectorSha256 = current.activeConditionEpoch?.appliedResourceVectorSha256,
            admissionOpen = gate.isOpen(),
            pendingActionCount = actionInvocations.values.count {
                it.state in PENDING_ACTION_STATES
            },
            lifetimeDataEventCount = current.lifetimeDataEventCount,
            uploadedThroughCommit = current.uploadedThroughCommit,
            retainedFromCommit = current.retainedFromCommit,
            calendarElapsedNanos = current.clockCheckpoint?.calendarElapsedNanos ?: 0,
            activeRunningElapsedNanos = current.clockCheckpoint?.activeRunningElapsedNanos ?: 0,
            clockAnchorWallTimeUtcMillis = current.clockCheckpoint?.anchor?.wallTimeUtcMillis,
        )
    }

    private inner class BarrierInputBuffer(
        private val firstObservationSequence: Long,
        private val firstEventSequence: Long,
        private val conditionEpochId: ConditionEpochId,
        startingSourceCheckpoints: Map<EventSourceId, SourceCheckpoint>,
        private val stagedAt: ResearchTime,
        initialPending: PendingEngineInput?,
    ) {
        private val lock = Mutex()
        private val normalSubmissions = mutableListOf<SourceSubmission>()
        private val flushSubmissions = mutableListOf<SourceSubmission>()
        private val sourceCheckpoints = startingSourceCheckpoints.toMutableMap()
        private var eventCount = 0L
        private var pendingInput = initialPending

        init {
            require(initialPending == null || initialPending.conditionEpochId == conditionEpochId) {
                "Initial pending input epoch mismatch"
            }
        }

        suspend fun offer(token: AdmissionToken, submission: SourceSubmission): EmitBatchResult = lock.withLock {
            val decision = gate.classify(token, submission.events.map(EventDraft::observedTime))
            if (decision is AdmissionDecision.Active || decision == AdmissionDecision.Rejected) {
                return@withLock EmitBatchResult.RejectedByAdmissionGate
            }
            val admitted = try {
                validateSubmission(submission, firstEventSequence + eventCount, conditionEpochId)
                val nextCheckpoint = provisionalCheckpoint(submission)
                val acceptedSubmission = when (decision) {
                    is AdmissionDecision.PreDrain -> {
                        require(decision.conditionEpochId == conditionEpochId) { "Pre-drain epoch mismatch" }
                        require(flushSubmissions.isEmpty()) { "A normal input cannot follow boundary flushes" }
                        require(submission.coverage.doesNotEndAfter(decision.boundary)) {
                            "A pre-drain input crossed the common boundary"
                        }
                        submission.withKind(ObservationAdmissionKind.NORMAL)
                    }
                    is AdmissionDecision.BoundaryFlush -> {
                        require(decision.conditionEpochId == conditionEpochId) { "Boundary-flush epoch mismatch" }
                        val sourceContract = requireNotNull(ProtocolEventSourceRegistry[submission.sourceId.value]) {
                            "Unknown barrier source"
                        }
                        require(sourceContract.isRetrospective) {
                            "Only retrospective sources may emit a boundary flush"
                        }
                        require(submission.coverage.endsAt(decision.boundary)) {
                            "A barrier flush must prove the exact common boundary"
                        }
                        require(flushSubmissions.none { it.sourceId == submission.sourceId }) {
                            "A retrospective source emitted more than one boundary flush"
                        }
                        val prior = flushSubmissions.lastOrNull()
                        require(prior == null || prior.sourceId < submission.sourceId) {
                            "Barrier flush sources must be emitted once in source order"
                        }
                        submission.withKind(ObservationAdmissionKind.BARRIER_FLUSH)
                    }
                    is AdmissionDecision.Active, AdmissionDecision.Rejected -> error("Unreachable admission decision")
                }
                acceptedSubmission to nextCheckpoint
            } catch (_: IllegalArgumentException) {
                return@withLock EmitBatchResult.ContractViolation
            }
            val (acceptedSubmission, nextCheckpoint) = admitted
            val priorPending = pendingInput
            val nextPending = if (priorPending == null) {
                PendingEngineInput(
                    conditionEpochId = conditionEpochId,
                    submissions = listOf(acceptedSubmission.toPending()),
                    stagedAt = stagedAt,
                    encodedSha256 = ZERO_DIGEST,
                )
            } else {
                priorPending.copy(
                    submissions = priorPending.submissions + acceptedSubmission.toPending(),
                    encodedSha256 = ZERO_DIGEST,
                )
            }.withComputedDigest()
            try {
                if (priorPending == null) {
                    store.stagePendingInput(nextPending)
                } else {
                    store.replacePendingInput(priorPending.encodedSha256, nextPending)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                return@withLock EmitBatchResult.StorageFailure
            }
            pendingInput = nextPending
            when (acceptedSubmission.admissionKind) {
                ObservationAdmissionKind.NORMAL -> normalSubmissions += acceptedSubmission
                ObservationAdmissionKind.BARRIER_FLUSH -> flushSubmissions += acceptedSubmission
            }
            val observation = firstObservationSequence + normalSubmissions.size + flushSubmissions.size - 1L
            eventCount += submission.events.size
            sourceCheckpoints[submission.sourceId] = nextCheckpoint
            EmitBatchResult.Accepted(observation)
        }

        suspend fun snapshot(): BarrierSnapshot = lock.withLock {
            val persisted = pendingInput
            require(store.loadPendingInput() == persisted) { "Barrier pending input lost durable ownership" }
            BarrierSnapshot(normalSubmissions.toList() + flushSubmissions.toList(), persisted)
        }

        private fun provisionalCheckpoint(submission: SourceSubmission): SourceCheckpoint {
            val prior = sourceCheckpoints[submission.sourceId]
            val expectedOrdinal = if (prior == null || prior.resourceGeneration != submission.resourceGeneration) {
                0L
            } else {
                prior.nextProducerOrdinal
            }
            require(submission.producerOrdinal == expectedOrdinal) {
                "Buffered collector producer ordinal is not contiguous"
            }
            if (prior != null && prior.resourceGeneration == submission.resourceGeneration) {
                val oldCoverage = prior.coverage
                val newCoverage = submission.coverage
                if (
                    oldCoverage != null && newCoverage != null &&
                    (oldCoverage.clockBasis != newCoverage.clockBasis ||
                        oldCoverage.endExclusive != newCoverage.startInclusive)
                ) {
                    throw SourceGap(SourceQualityGapReason.RETROSPECTIVE_COVERAGE_GAP)
                }
            }
            return SourceCheckpoint(
                sourceId = submission.sourceId,
                resourceGeneration = submission.resourceGeneration,
                nextProducerOrdinal = Math.addExact(submission.producerOrdinal, 1L),
                coverage = submission.coverage ?: prior?.coverage,
                cursor = prior?.cursor,
            )
        }
    }

    private fun SourceCoverage?.doesNotEndAfter(boundary: ResearchTime): Boolean {
        val coverage = this ?: return true
        val end = coverage.endExclusive.toLongOrNull() ?: return false
        return when (coverage.clockBasis) {
            cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_WALL_TIME ->
                end <= boundary.wallTimeUtcMillis
            cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_MONOTONIC_TIME ->
                end <= boundary.elapsedRealtimeNanos
            cool.jacoblin.particeps.core.model.SourceClockBasis.OBSERVED_RESEARCH_TIME -> false
        }
    }

    private sealed interface CoordinatedBarrier {
        val completion: CompletableDeferred<Unit>
    }

    private inner class StagedSourceBarrier(
        val causal: SourceSubmission,
        val inputKind: EngineInputKind,
        val clock: StudyClockCheckpoint,
        val pending: PendingEngineInput,
        val boundary: ResearchTime,
        val drainToken: AdmissionToken,
        val buffer: BarrierInputBuffer,
        override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : CoordinatedBarrier

    private inner class PostCommitBarrier(
        val inputKind: EngineInputKind,
        val clock: StudyClockCheckpoint,
        val boundary: ResearchTime,
        val drainToken: AdmissionToken,
        val buffer: BarrierInputBuffer,
        override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : CoordinatedBarrier

    private inner class FailClosedBarrier(
        val reason: SafetyPauseReason,
        val pending: PendingEngineInput?,
        override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : CoordinatedBarrier

    private fun SourceCoverage?.endsAt(boundary: ResearchTime): Boolean {
        val coverage = this ?: return false
        return when (coverage.clockBasis) {
            cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_WALL_TIME ->
                coverage.endExclusive == boundary.wallTimeUtcMillis.toString()
            cool.jacoblin.particeps.core.model.SourceClockBasis.SOURCE_MONOTONIC_TIME ->
                coverage.endExclusive == boundary.elapsedRealtimeNanos.toString()
            cool.jacoblin.particeps.core.model.SourceClockBasis.OBSERVED_RESEARCH_TIME -> false
        }
    }

    private data class SourceSubmission(
        val sourceId: EventSourceId,
        val schemaVersion: Int,
        val resourceGeneration: Long,
        val producerOrdinal: Long,
        val events: List<EventDraft>,
        val coverage: SourceCoverage?,
        val admissionKind: ObservationAdmissionKind,
    ) {
        fun identity() = SourceSubmissionIdentity(
            sourceId,
            schemaVersion,
            resourceGeneration,
            producerOrdinal,
        )

        fun withKind(kind: ObservationAdmissionKind) = copy(admissionKind = kind)

        fun toPending() = PendingSourceSubmission(
            sourceId,
            schemaVersion,
            resourceGeneration,
            producerOrdinal,
            admissionKind,
            events,
            coverage,
        )

        companion object {
            fun from(batch: SourceEventBatch) = SourceSubmission(
                batch.sourceId,
                batch.schemaVersion,
                batch.resourceGeneration,
                batch.producerOrdinal,
                batch.events,
                batch.coverage,
                ObservationAdmissionKind.NORMAL,
            )

            fun from(advance: CoverageAdvance) = SourceSubmission(
                advance.sourceId,
                advance.schemaVersion,
                advance.resourceGeneration,
                advance.producerOrdinal,
                emptyList(),
                advance.coverage,
                ObservationAdmissionKind.NORMAL,
            )

            fun from(pending: PendingSourceSubmission) = SourceSubmission(
                pending.sourceId,
                pending.schemaVersion,
                pending.resourceGeneration,
                pending.producerOrdinal,
                pending.events,
                pending.coverage,
                pending.admissionKind,
            )
        }
    }

    private data class SourceSubmissionIdentity(
        val sourceId: EventSourceId,
        val schemaVersion: Int,
        val resourceGeneration: Long,
        val producerOrdinal: Long,
    )

    private data class EventSequenceRange(
        val first: Long?,
        val last: Long?,
    )

    private fun List<SourceSubmission>.uniqueByIdentity(label: String): Map<SourceSubmissionIdentity, SourceSubmission> {
        val indexed = associateBy(SourceSubmission::identity)
        require(indexed.size == size) { "$label contains a duplicate producer identity" }
        return indexed
    }

    private data class PreparedSources(
        val observations: List<SourceObservation>,
        val events: List<RecordedEvent>,
        val sourceCheckpoints: Map<EventSourceId, SourceCheckpoint>,
        val nextObservationSequence: Long,
        val nextEventSequence: Long,
    ) {
        companion object {
            fun empty(document: RuntimeDocument) = PreparedSources(
                emptyList(),
                emptyList(),
                document.sourceCheckpoints,
                document.nextObservationSequence,
                document.nextEventSequence,
            )
        }
    }

    private data class PostCommitEffects(
        val timerIntents: List<TimerIntent> = emptyList(),
        val actionsReady: List<String> = emptyList(),
        val actionsInactive: List<String> = emptyList(),
        val timerProductionRequests: List<TimerProductionRequest> = emptyList(),
    ) {
        operator fun plus(other: PostCommitEffects) = PostCommitEffects(
            timerIntents = timerIntents + other.timerIntents,
            actionsReady = actionsReady + other.actionsReady,
            actionsInactive = actionsInactive + other.actionsInactive,
            timerProductionRequests = timerProductionRequests + other.timerProductionRequests,
        )
    }

    private class ContainedActionFailure(val reason: SafetyPauseReason) : RuntimeException()

    private data class ResourceAuditBatch(
        val events: List<EventDraft>,
        val mutations: List<RuntimeMutation>,
        val effects: PostCommitEffects,
    ) {
        companion object {
            val EMPTY = ResourceAuditBatch(emptyList(), emptyList(), PostCommitEffects())
        }
    }

    private data class DeadlineTimerUpdate(
        val events: List<EventDraft>,
        val mutations: List<RuntimeMutation>,
        val effects: PostCommitEffects,
    ) {
        companion object {
            val EMPTY = DeadlineTimerUpdate(emptyList(), emptyList(), PostCommitEffects())
        }
    }

    private data class ResourceContainment(
        val verifiedInactive: Map<ResourceKey, ResourceGeneration>,
        val attempted: Map<ResourceKey, DesiredResourceState>,
    )

    private data class BarrierSnapshot(
        val submissions: List<SourceSubmission>,
        val pending: PendingEngineInput?,
    )

    private class SourceGap(val reason: SourceQualityGapReason) : IllegalArgumentException()
    private class RequiredResourceFailure : IllegalStateException()
    private class ClockDiscontinuity : IllegalStateException()

    private companion object {
        val RECOVERY_FAIL_CLOSED_STATES = setOf(
            ExperimentState.ACTIVATING,
            ExperimentState.RUNNING,
            ExperimentState.PAUSING,
        )
        val TERMINAL_STATES = setOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN)
        val ATTEMPTED_CLEANUP_HEALTH_STATES = setOf(
            ResourceHealthStatus.PREPARED,
            ResourceHealthStatus.APPLIED,
            ResourceHealthStatus.SUSPENDED,
            ResourceHealthStatus.FAILED,
        )
        val TRUSTED_APPLIED_HEALTH_STATES = setOf(
            ResourceHealthStatus.APPLIED,
            ResourceHealthStatus.SUSPENDED,
        )
        val TERMINAL_ACTION_STATES = setOf(RuntimeActionState.SUCCEEDED, RuntimeActionState.FAILED)
        val REQUIRED_DELIVERY_FAILURES = setOf(
            ActionExecutionFailure.DELIVERY_FAILED,
            ActionExecutionFailure.RECONCILIATION_FAILED,
        )
        val PENDING_ACTION_STATES = setOf(
            RuntimeActionState.READY,
            RuntimeActionState.CLAIMED,
            RuntimeActionState.OPENED,
        )
        const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        const val MAX_COMPONENT_CHARS = 480 * 1_024
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val RESOURCE_AUDIT_PRODUCER_PREFIX = "resource-audit:"
        const val STUDY_DEADLINE_COMPONENT_ID = "study-duration"
        const val STUDY_DURATION_AUTOMATION_ID = "study-duration"
        const val STUDY_DEADLINE_PRODUCER_KEY = "study-deadline"
        const val ADMISSION_LOCK_RETRY_MILLIS = 1L
    }
}

private fun String.toResourceAuditRemovalReason(): ResourceAuditRemovalReason = when (this) {
    "PARTICIPANT_PAUSED" -> ResourceAuditRemovalReason.PARTICIPANT_PAUSED
    "STUDY_COMPLETED" -> ResourceAuditRemovalReason.STUDY_COMPLETED
    "STUDY_WITHDRAWN" -> ResourceAuditRemovalReason.STUDY_WITHDRAWN
    else -> error("Unsupported resource audit lifecycle reason: $this")
}

private fun ResourceTerminalFailure?.toResourceAuditRemovalReason(
    safetyReason: SafetyPauseReason,
): ResourceAuditRemovalReason = when (this?.reason) {
    "ACTIVATION_TIMEOUT" -> ResourceAuditRemovalReason.ACTIVATION_TIMEOUT
    "NATIVE_ENGINE_FAILED" -> ResourceAuditRemovalReason.FORWARDER_FAILURE
    "OWNED_VPN_LOST", "OWNED_VPN_NOT_CONFIRMED" -> ResourceAuditRemovalReason.OWNED_VPN_NETWORK_LOST
    "PROFILE_MISMATCH" -> ResourceAuditRemovalReason.PROFILE_MISMATCH
    "SOCKET_PROTECTOR_MISSING" -> ResourceAuditRemovalReason.SOCKET_PROTECT_FAILURE
    "TARGET_PACKAGE_CHANGED", "TARGET_PACKAGE_INVALID" -> ResourceAuditRemovalReason.TARGET_PACKAGE_CHANGED
    "TUN_CLOSED" -> ResourceAuditRemovalReason.TUN_IO_FAILURE
    "TUN_ESTABLISH_FAILED" -> ResourceAuditRemovalReason.TUN_ESTABLISH_FAILURE
    "VPN_CONSENT_REQUIRED", "VPN_REVOKED", "LOCAL_NETWORK_PERMISSION_REQUIRED" ->
        ResourceAuditRemovalReason.VPN_PERMISSION_REVOKED
    "FOREGROUND_SERVICE_FAILED" -> ResourceAuditRemovalReason.VPN_SERVICE_START_FAILURE
    else -> when (safetyReason) {
        SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN ->
            ResourceAuditRemovalReason.RECOVERY_WITHOUT_CONFIRMED_VPN
        SafetyPauseReason.REQUIRED_ACCESS_MISSING -> ResourceAuditRemovalReason.VPN_PERMISSION_REVOKED
        SafetyPauseReason.TRAFFIC_CONDITION_LOST -> ResourceAuditRemovalReason.OWNED_VPN_NETWORK_LOST
        SafetyPauseReason.COLLECTION_HOST_FAILURE,
        SafetyPauseReason.WORK_SCHEDULING_FAILURE,
        SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
        SafetyPauseReason.STORAGE_FAILURE,
        SafetyPauseReason.AUTOMATION_ENGINE_FAILURE,
        SafetyPauseReason.REQUIRED_RESOURCE_FAILURE,
        -> ResourceAuditRemovalReason.SYSTEM_SAFETY_PAUSE
    }
}

private fun DataOutputStream.writeCanonicalString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun ExperimentState.toSessionState(): StudySessionState = when (this) {
    ExperimentState.READY -> StudySessionState.READY
    ExperimentState.ACTIVATING -> StudySessionState.ACTIVATING
    ExperimentState.RUNNING -> StudySessionState.RUNNING
    ExperimentState.PAUSING -> StudySessionState.PAUSING
    ExperimentState.PAUSED -> StudySessionState.PAUSED
    ExperimentState.COMPLETED -> StudySessionState.COMPLETED
    ExperimentState.WITHDRAWN -> StudySessionState.WITHDRAWN
    else -> throw IllegalArgumentException("Enrollment state has no automation lifecycle projection")
}
