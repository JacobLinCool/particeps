package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationReducer
import cool.jacoblin.particeps.core.automation.CompiledAutomationProgram
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.RegistryEmissionAuthority
import cool.jacoblin.particeps.core.collector.RegistrySourceKind
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.StudyTimeline
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailure
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only mutable coordinator for a signed study. Every durable fact is an [EngineCommit]; flows,
 * callbacks, wakeups and resource health are merely adapters around that commit chain.
 *
 * This class alone holds the runtime mutex, the coordinated-barrier and terminal-failure channels
 * with their one consumer each, the active barrier and the coroutine scope. Its public members admit
 * input or enter a command under that mutex; the work itself runs in internal collaborators on the
 * caller's coroutine while the mutex is held. None of them is a concurrent actor: none takes the
 * mutex or launches, and they call back up only through [RuntimeContainment] and, for the admission
 * front door, the barrier enqueue.
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

    private val memory = RuntimeMemory()
    private val ctx = RuntimeContext(
        study,
        store,
        program,
        hosts,
        interventionRequiredById,
        this.surveyInterventionIds,
        clocks,
        zoneId,
        initialZoneId,
        timeline,
        timerProducer,
        timerWakeups,
        actionNotifier,
        entropy,
        reducer,
        gate,
        mutableSnapshot,
        memory,
    )
    private val containmentPort = ContainmentPort()
    private val clockPolicy = RuntimeClockPolicy(ctx)
    private val deadlineTimers = StudyDeadlineTimers(ctx)
    private val sourcePrep = SourcePreparation(ctx)
    private val commitLog = CommitAssembler(ctx, deadlineTimers)
    private val resourcePlane = ResourceVectorController(ctx, commitLog)
    private val auditTrail = ResourceAuditTrail(ctx)
    private val outbox = ActionOutbox(ctx, commitLog, clockPolicy, containmentPort)
    private val effectRunner = PostCommitEffectRunner(ctx, commitLog, outbox)
    private val barrierCoordinator = ResourceBarrierCoordinator(
        ctx,
        commitLog,
        effectRunner,
        resourcePlane,
        auditTrail,
        sourcePrep,
        clockPolicy,
        containmentPort,
    )
    private val frontDoor = AdmissionFrontDoor(
        ctx,
        commitLog,
        effectRunner,
        barrierCoordinator,
        sourcePrep,
        clockPolicy,
        ::enqueueBarrier,
    )
    private val discontinuities = ClockDiscontinuityHandler(
        ctx,
        commitLog,
        effectRunner,
        resourcePlane,
        auditTrail,
        deadlineTimers,
        barrierCoordinator,
        clockPolicy,
        containmentPort,
    )
    private val lifecycleCoordinator = LifecycleCoordinator(
        ctx,
        commitLog,
        effectRunner,
        resourcePlane,
        auditTrail,
        deadlineTimers,
        barrierCoordinator,
        sourcePrep,
        clockPolicy,
        outbox,
        discontinuities,
        containmentPort,
    )
    private val recoveryCoordinator = RuntimeRecovery(
        ctx,
        commitLog,
        effectRunner,
        resourcePlane,
        auditTrail,
        deadlineTimers,
        sourcePrep,
        clockPolicy,
        outbox,
        lifecycleCoordinator,
        discontinuities,
    )
    private val timerCoordinator = TimerCoordinator(
        ctx,
        commitLog,
        effectRunner,
        auditTrail,
        clockPolicy,
        lifecycleCoordinator,
        barrierCoordinator,
        deadlineTimers,
    )

    suspend fun initialize(): RuntimeInitializationResult {
        if (!initialized.compareAndSet(false, true)) {
            return RuntimeInitializationResult.Ready(false, snapshot.value)
        }
        return try {
            mutex.withLock {
                val loaded = recoveryCoordinator.loadLocked()
                bindTerminalListeners()
                val recover = recoveryCoordinator.recoverOrReanchorLocked(loaded)
                startTerminalConsumer()
                startBarrierConsumer()
                RuntimeInitializationResult.Ready(recover, snapshot.value)
            }
        } catch (failure: Throwable) {
            gate.forceClose()
            RuntimeInitializationResult.Failed(SafetyPauseReason.STORAGE_FAILURE, failure)
        }
    }

    /**
     * The runtime document of the last acknowledged commit: the one [initialize] recovered, advanced
     * only after each durable append. It never reads storage, so a caller that needs the document
     * after initialization does not authenticate the retained log a second time. It is null until
     * initialization has loaded or created the document.
     */
    suspend fun committedDocument(): RuntimeDocument? = mutex.withLock { memory.document }

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
        lifecycleCoordinator.markReadyLocked()
    }

    private suspend fun advanceSetup(
        expected: ExperimentState,
        target: ExperimentState,
    ): RuntimeCommandResult = command {
        lifecycleCoordinator.advanceSetupLocked(expected, target)
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
        lifecycleCoordinator.safetyPauseLocked(reason, causeSequence = null)
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
        timerCoordinator.onTimerDueLocked(timerId, generation)
    }

    /**
     * Durable TIME_SET/TIMEZONE_CHANGE input. Crossed retrospective wall intervals are discarded.
     * While running, admission closes before collectors pause, so live callbacks not yet committed,
     * including an open [cool.jacoblin.particeps.core.collector.CallbackCommitWindow] batch, are
     * refused rather than drained.
     */
    suspend fun onClockDiscontinuity(): RuntimeCommandResult = command {
        discontinuities.onClockDiscontinuityLocked()
    }

    suspend fun pendingActions(): List<DurableActionInvocation> = mutex.withLock {
        checkInitialized()
        outbox.pendingActionsLocked()
    }

    /** Replays only the platform adapter work implied by durable action components and state. */
    suspend fun reconcileActions(): RuntimeCommandResult = command {
        if (memory.requireDocument().state == ExperimentState.RUNNING) {
            effectRunner.performPostCommitEffectsLocked(PostCommitEffects(actionsReady = outbox.pendingActionIdsLocked()))
        } else {
            outbox.retractInactiveActionsLocked()
        }
        RuntimeCommandResult.Success
    }

    /** Durable timer truth for process recovery/re-arm; wakeup adapters never own timer state. */
    suspend fun pendingTimers(): List<DurableTimer> = mutex.withLock {
        checkInitialized()
        timerCoordinator.pendingTimersLocked()
    }

    /**
     * Claims a READY action for display, or returns the invocation already claimed or opened. Like
     * every command, a claim waits for an active coordinated barrier and fails closed when it fails
     * while holding the runtime mutex; it returns null when the action cannot be claimed or
     * containment ran. Unlike a command, a cancelled claim is never contained. A cancellation
     * during the wait propagates having committed nothing. One that arrives after the claim holds
     * the mutex cannot interrupt a claim commit already being appended: the append and the memory
     * advance finish together, so memory never trails the store, and then the claim either returns
     * or propagates the cancellation.
     */
    suspend fun claimAction(actionId: String): DurableActionInvocation? {
        if (!initialized.get()) return null
        // Waiting holds nothing and has committed nothing, so a cancelled wait propagates without containment.
        lockOutsideCoordinatedBarrier()
        return try {
            try {
                outbox.claimActionLocked(actionId)
            } finally {
                mutex.unlock()
            }
        } catch (cancelled: CancellationException) {
            // A stopped caller is not a storage failure, so admission and the lifecycle stay untouched.
            throw cancelled
        } catch (_: ContainedActionFailure) {
            null
        } catch (_: Throwable) {
            failClosedAfterCommandFailure()
            null
        }
    }

    suspend fun recordActionResult(
        actionId: String,
        succeeded: Boolean,
        failure: ActionExecutionFailure? = null,
    ): RuntimeCommandResult = command {
        outbox.recordActionResultLocked(actionId, succeeded, failure)
    }

    suspend fun openSurvey(actionId: String, interventionId: String): RuntimeCommandResult = command {
        outbox.openSurveyLocked(actionId, interventionId)
    }

    suspend fun submitSurvey(
        actionId: String,
        interventionId: String,
        surveyId: String,
        answersJson: String,
    ): RuntimeCommandResult = command {
        outbox.submitSurveyLocked(actionId, interventionId, surveyId, answersJson)
    }

    suspend fun dismissSurvey(actionId: String, interventionId: String): RuntimeCommandResult = command {
        outbox.dismissSurveyLocked(actionId, interventionId)
    }

    suspend fun expireSurvey(actionId: String, interventionId: String): RuntimeCommandResult = command {
        outbox.expireSurveyLocked(actionId, interventionId)
    }

    suspend fun acknowledgeUpload(
        bundleId: String,
        firstCommit: Long,
        throughCommit: Long,
        bundleSha256: String,
    ): RuntimeCommandResult = command {
        val current = memory.requireDocument()
        val prior = memory.latestUploadAcknowledgement
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
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.UPLOAD_ACKNOWLEDGEMENT,
            checkpoint = memory.automationCheckpoint,
            clock = current.clockCheckpoint?.let { clockPolicy.advanceClock(current, now) },
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
                -> return memory.barrierBuffer?.offer(token, submission) ?: EmitBatchResult.RejectedByAdmissionGate
                is AdmissionDecision.Active -> {
                    val result = mutex.withLockUntilDrain(decision.drainSignal) {
                        when (val lockedDecision = gate.classify(token, observedTimes)) {
                            AdmissionDecision.Rejected -> EmitBatchResult.RejectedByAdmissionGate
                            is AdmissionDecision.PreDrain,
                            is AdmissionDecision.BoundaryFlush,
                            -> memory.barrierBuffer?.offer(token, submission)
                                ?: EmitBatchResult.RejectedByAdmissionGate
                            is AdmissionDecision.Active -> frontDoor.processActiveSubmissionLocked(
                                submission,
                                lockedDecision.conditionEpochId,
                            )
                        }
                    }
                    if (result != null) return result
                }
            }
        }
    }

    private suspend fun activate(from: ExperimentState, resumed: Boolean): RuntimeCommandResult = command {
        lifecycleCoordinator.activateLocked(from, resumed)
    }

    private suspend fun stopSession(
        terminalState: ExperimentState,
        requestEvent: String,
        resultEvent: String,
        transitionReason: String,
        epochReason: String,
    ): RuntimeCommandResult = command {
        lifecycleCoordinator.stopSessionLocked(terminalState, requestEvent, resultEvent, transitionReason, epochReason)
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
                    lifecycleCoordinator.safetyPauseLocked(reason, null, failure)
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
                        barrierCoordinator.completeCoordinatedBarrierLocked(request)
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
                                recoveryCoordinator.recoverFailClosedLocked(pending)
                            } else {
                                lifecycleCoordinator.safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
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
            lockOutsideCoordinatedBarrier()
            try {
                block()
            } finally {
                mutex.unlock()
            }
        } catch (contained: ContainedActionFailure) {
            RuntimeCommandResult.FailedClosed(contained.reason)
        } catch (_: Throwable) {
            failClosedAfterCommandFailure()
            RuntimeCommandResult.FailedClosed(SafetyPauseReason.STORAGE_FAILURE)
        }
    }

    /**
     * Returns holding [mutex] while no coordinated barrier is active. It throws only when
     * cancelled, and then holds nothing.
     */
    private suspend fun lockOutsideCoordinatedBarrier() {
        while (true) {
            activeBarrier?.completion?.await()
            mutex.lock()
            val barrier = activeBarrier
            if (barrier == null) return
            mutex.unlock()
            barrier.completion.await()
        }
    }

    /** Closes admission and, unless the study is already paused or terminal, safety-pauses it as a storage failure. */
    private suspend fun failClosedAfterCommandFailure() {
        gate.forceClose()
        runCatching {
            mutex.withLock {
                memory.document?.takeIf { it.state !in TERMINAL_STATES && it.state != ExperimentState.PAUSED }
                    ?.let { lifecycleCoordinator.safetyPauseLocked(SafetyPauseReason.STORAGE_FAILURE, null) }
            }
        }
    }

    /** The runtime's containment, as the one port extracted code calls back through under the caller's lock. */
    private inner class ContainmentPort : RuntimeContainment {
        override suspend fun safetyPauseLocked(
            reason: SafetyPauseReason,
            causeSequence: Long?,
            resourceFailure: ResourceTerminalFailure?,
        ) = lifecycleCoordinator.safetyPauseLocked(reason, causeSequence, resourceFailure)

        override suspend fun recoverFailClosedLocked(pending: PendingEngineInput?) =
            recoveryCoordinator.recoverFailClosedLocked(pending)

        override suspend fun completePausedAtDeadlineLocked(now: ResearchTime) =
            lifecycleCoordinator.completePausedAtDeadlineLocked(now)
    }

    private fun checkInitialized() = check(initialized.get() && memory.document != null) { "Runtime is not initialized" }
}
