package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailure

/**
 * The study lifecycle: setup transitions, start and resume, pause, completion and withdrawal, the
 * signed-deadline completions, and the two-commit safety pause that contains every failure.
 * Extracted from [ExperimentRuntime]; not a concurrent actor. Every member runs on the caller's
 * coroutine while the caller holds the runtime mutex, and suspending members end in `Locked`. It
 * never acquires the runtime mutex, launches or enqueues, and suspends only in store and platform
 * adapter calls, directly or through the collaborators it drives, and in the containment port. It
 * reads runtime state through [RuntimeMemory] at use time and never retains it.
 */
internal class LifecycleCoordinator(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val effectRunner: PostCommitEffectRunner,
    private val resourcePlane: ResourceVectorController,
    private val auditTrail: ResourceAuditTrail,
    private val deadlineTimers: StudyDeadlineTimers,
    private val barrierCoordinator: ResourceBarrierCoordinator,
    private val sourcePrep: SourcePreparation,
    private val clockPolicy: RuntimeClockPolicy,
    private val outbox: ActionOutbox,
    private val discontinuities: ClockDiscontinuityHandler,
    private val containmentPort: RuntimeContainment,
) {
    private val memory = ctx.memory

    suspend fun markReadyLocked(): RuntimeCommandResult {
        val current = memory.requireDocument()
        if (current.state != ExperimentState.ACCESS_SETUP) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            state = ExperimentState.READY,
            epoch = null,
            clock = current.clockCheckpoint,
            checkpoint = memory.automationCheckpoint,
        )
        return RuntimeCommandResult.Success
    }

    suspend fun advanceSetupLocked(
        expected: ExperimentState,
        target: ExperimentState,
    ): RuntimeCommandResult {
        val current = memory.requireDocument()
        if (current.state != expected) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            state = target,
            epoch = null,
            clock = current.clockCheckpoint,
            checkpoint = memory.automationCheckpoint,
        )
        return RuntimeCommandResult.Success
    }

    suspend fun activateLocked(from: ExperimentState, resumed: Boolean): RuntimeCommandResult {
        var current = memory.requireDocument()
        if (current.state != from) return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        if (from == ExperimentState.PAUSED) {
            resourcePlane.finalizePausedResourceCleanupLocked(recovery = true)
            if (!discontinuities.reanchorPausedAcrossBootLocked()) {
                return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
            }
            current = memory.requireDocument()
            if (current.state == ExperimentState.COMPLETED) return RuntimeCommandResult.Success
            outbox.retractInactiveActionsLocked()
        }
        val now = ctx.clocks.now()
        val clock = if (current.clockCheckpoint == null) clockPolicy.initialClock(now) else clockPolicy.advanceClock(current, now)
        val activatingInput = ReducerInput.Lifecycle(
            memory.automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            StudySessionState.ACTIVATING,
        )
        val activating = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(activatingInput))
        val commandId = ctx.commandId(if (resumed) "resume" else "start", current.nextCommitSequence)
        val started = RuntimeEventFactory.lifecycle(
            type = if (resumed) "STUDY_RESUMED" else "STUDY_STARTED",
            commandId = commandId,
            previousState = current.state,
            currentState = ExperimentState.ACTIVATING,
            transitionReason = if (resumed) "PARTICIPANT_RESUME" else "STUDY_START",
            now = now,
        )
        val deadlineUpdate = deadlineTimers.reconcileStudyDeadlineTimer(
            clock,
            activating.checkpoint.evaluatedThroughSequence,
            "CLOCK_REANCHORED",
        )
        val activationEffects = commitLog.appendReductionLocked(
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            reduction = activating,
            eventDrafts = listOf(started) + deadlineUpdate.events,
            state = ExperimentState.ACTIVATING,
            clock = clock,
            extraMutations = deadlineUpdate.mutations,
        )
        effectRunner.performPostCommitEffectsLocked(activationEffects + deadlineUpdate.effects)
        val vector = try {
            resourcePlane.applyDesiredVectorLocked(activating.checkpoint.desiredResources, commandId)
        } catch (_: RequiredResourceFailure) {
            safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        }
        val runningNow = ctx.clocks.now()
        val runningClock = clockPolicy.advanceClock(memory.requireDocument(), runningNow)
        if (ctx.timeline.isElapsed(runningClock)) {
            return try {
                completeActivatingAtDeadlineLocked(vector, runningNow, runningClock)
                RuntimeCommandResult.Success
            } catch (_: RequiredResourceFailure) {
                safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
                RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
            }
        }
        val runningInput = ReducerInput.Lifecycle(
            memory.automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(runningClock),
            StudySessionState.RUNNING,
        )
        val running = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(runningInput))
        check(running.resourceChanges.isEmpty()) { "ACTIVATING to RUNNING changed the resource vector" }
        val epoch = resourcePlane.newEpoch(vector, runningNow)
        val runningEvent = RuntimeEventFactory.lifecycle(
            "STUDY_RUNNING",
            commandId,
            ExperimentState.ACTIVATING,
            ExperimentState.RUNNING,
            "ACTIVATION_CONFIRMED",
            runningNow,
        )
        val resourceAudit = auditTrail.activateResourceAuditsLocked(epoch, vector, runningNow)
        val effects = commitLog.appendReductionLocked(
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
            resourcePlane.resumeAppliedVectorLocked(vector)
        } catch (_: RequiredResourceFailure) {
            ctx.gate.forceClose()
            safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        }
        barrierCoordinator.openAdmission(epoch.id, runningClock)
        try {
            resourcePlane.notifyAdmissionOpenedLocked(vector)
        } catch (_: RequiredResourceFailure) {
            ctx.gate.forceClose()
            safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        }
        commitLog.publishSnapshot()
        effectRunner.performPostCommitEffectsLocked(
            effects + resourceAudit.effects + PostCommitEffects(actionsReady = outbox.pendingActionIdsLocked()),
        )
        return RuntimeCommandResult.Success
    }

    private suspend fun completeActivatingAtDeadlineLocked(
        vector: AppliedResourceVector,
        now: ResearchTime,
        clock: StudyClockCheckpoint,
    ) {
        val timer = requireNotNull(memory.studyDeadlineTimer) { "Activating study has no durable deadline" }
        require(timerIsDue(timer, reducerClock(clock))) { "Activation completed before its deadline" }
        resourcePlane.releaseVectorLocked(vector)
        val reducerClock = reducerClock(clock)
        val reduction = ctx.reducer.reduceBatch(
            ctx.program,
            memory.automationCheckpoint,
            listOf(
                ReducerInput.Lifecycle(
                    memory.automationCheckpoint.evaluatedThroughSequence + 1,
                    reducerClock,
                    StudySessionState.PAUSING,
                ),
                ReducerInput.Lifecycle(
                    memory.automationCheckpoint.evaluatedThroughSequence + 2,
                    reducerClock,
                    StudySessionState.COMPLETED,
                ),
            ),
        )
        val retirement = deadlineTimers.retireStudyDeadlineTimer(now, "FIRED")
        val commandId = ctx.commandId("study-duration-elapsed", memory.requireDocument().nextCommitSequence)
        val effects = commitLog.appendReductionLocked(
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
            extraMutations = retirement.mutations + resourcePlane.inactiveResourceMutations(reduction.checkpoint.desiredResources),
            timerRetirementReason = "LIFECYCLE_ENDED",
        )
        effectRunner.performPostCommitEffectsLocked(
            effects + retirement.effects + PostCommitEffects(actionsInactive = outbox.pendingActionIdsLocked()),
        )
    }

    suspend fun stopSessionLocked(
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
        val current = memory.requireDocument()
        if (current.state == ExperimentState.PAUSED && terminalState != ExperimentState.PAUSED) {
            resourcePlane.finalizePausedResourceCleanupLocked(recovery = true)
            val now = operationNow ?: ctx.clocks.now()
            val clock = clockPolicy.advanceClockForTerminal(current, now)
            val target = terminalState.toSessionState()
            val reduction = ctx.reducer.reduceBatch(
                ctx.program,
                memory.automationCheckpoint,
                listOf(
                    ReducerInput.Lifecycle(
                        memory.automationCheckpoint.evaluatedThroughSequence + 1,
                        reducerClock(clock),
                        target,
                    ),
                ),
            )
            val commandId = ctx.commandId(transitionReason.lowercase(), current.nextCommitSequence)
            val deadlineRetirement = deadlineTimers.retireStudyDeadlineTimer(now, deadlineRetirementReason)
            val effects = commitLog.appendReductionLocked(
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
            effectRunner.performPostCommitEffectsLocked(
                effects + deadlineRetirement.effects +
                    PostCommitEffects(actionsInactive = outbox.pendingActionIdsLocked()),
            )
            return RuntimeCommandResult.Success
        }
        if (current.state != ExperimentState.RUNNING) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val now = operationNow ?: ctx.clocks.now()
        val boundary = collectionBoundary ?: now
        val boundaryClock = clockPolicy.advanceClock(current, now)
        if (ctx.timeline.isElapsed(boundaryClock) && transitionReason != "STUDY_DURATION_ELAPSED") {
            val timer = requireNotNull(memory.studyDeadlineTimer) { "Elapsed running study has no durable deadline" }
            return stopSessionLocked(
                terminalState = ExperimentState.COMPLETED,
                requestEvent = "STUDY_COMPLETE_REQUESTED",
                resultEvent = "STUDY_COMPLETED",
                transitionReason = "STUDY_DURATION_ELAPSED",
                epochReason = "STUDY_COMPLETED",
                operationNow = now,
                collectionBoundary = deadlineTimers.deadlineCollectionBoundary(timer),
                causalEvents = causalEvents + RuntimeEventFactory.timerDue(timer, now),
                deadlineRetirementReason = "FIRED",
                inputKind = EngineInputKind.TIMER_WAKE,
            )
        }
        val commandId = ctx.commandId(transitionReason.lowercase(), current.nextCommitSequence)
        val activeEpoch = requireNotNull(current.activeConditionEpoch)
        val drain = barrierCoordinator.openDrainLocked(
            current.nextObservationSequence,
            current.nextEventSequence,
            activeEpoch.id,
            current.sourceCheckpoints,
            boundary,
            null,
        )
        return try {
            val flushCursors = resourcePlane.suspendAndFlushLocked(boundary)
            val barrier = drain.buffer.snapshot()
            val flush = sourcePrep.prepareSources(
                current,
                barrier.submissions,
                activeEpoch.id,
                current.sourceCheckpoints,
                flushCursors,
            )
            val reducerClock = reducerClock(boundaryClock)
            val reducerInputs = buildList<ReducerInput> {
                flush.events.forEachIndexed { index, event ->
                    add(event.toReducerInput(memory.automationCheckpoint.evaluatedThroughSequence + index + 1L, reducerClock))
                }
                add(
                    ReducerInput.Lifecycle(
                        memory.automationCheckpoint.evaluatedThroughSequence + size + 1L,
                        reducerClock,
                        StudySessionState.PAUSING,
                    ),
                )
            }
            val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, reducerInputs)
            val vector = resourcePlane.currentAppliedVector()
            val deadlineRetirement = if (terminalState in TERMINAL_STATES) {
                deadlineTimers.retireStudyDeadlineTimer(now, deadlineRetirementReason)
            } else {
                DeadlineTimerUpdate.EMPTY
            }
            val resourceAudit = auditTrail.deactivateResourceAuditsLocked(
                activeEpoch,
                vector,
                boundary,
                epochReason.toResourceAuditRemovalReason(),
            )
            val firstEffects = commitLog.appendReductionLocked(
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
            barrierCoordinator.closeDrainLocked(drain.token)
            resourcePlane.releaseAllResourcesLocked()
            val finalNow = ctx.clocks.now()
            val finalClock = clockPolicy.advanceClock(memory.requireDocument(), finalNow)
            val finalReduction = ctx.reducer.reduceBatch(
                ctx.program,
                memory.automationCheckpoint,
                listOf(
                    ReducerInput.Lifecycle(
                        memory.automationCheckpoint.evaluatedThroughSequence + 1,
                        reducerClock(finalClock),
                        terminalState.toSessionState(),
                    ),
                ),
            )
            val finalEffects = commitLog.appendReductionLocked(
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
                extraMutations = resourcePlane.inactiveResourceMutations(finalReduction.checkpoint.desiredResources),
            )
            effectRunner.performPostCommitEffectsLocked(
                firstEffects + resourceAudit.effects + deadlineRetirement.effects + finalEffects +
                    PostCommitEffects(actionsInactive = outbox.pendingActionIdsLocked()),
            )
            RuntimeCommandResult.Success
        } catch (_: Throwable) {
            barrierCoordinator.abortDrainLocked()
            val pending = ctx.store.loadPendingInput()
            if (pending != null) {
                containmentPort.recoverFailClosedLocked(pending)
            } else {
                safetyPauseLocked(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE, null)
            }
            RuntimeCommandResult.FailedClosed(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE)
        }
    }

    suspend fun completePausedAtDeadlineLocked(now: ResearchTime) {
        val current = memory.requireDocument()
        require(current.state == ExperimentState.PAUSED) { "Deadline completion requires PAUSED" }
        require(ctx.timeline.isElapsed(requireNotNull(current.clockCheckpoint))) {
            "Paused study duration has not elapsed"
        }
        val timer = requireNotNull(memory.studyDeadlineTimer) { "Elapsed paused study has no durable deadline" }
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

    suspend fun safetyPauseLocked(
        reason: SafetyPauseReason,
        causeSequence: Long?,
        resourceFailure: ResourceTerminalFailure? = null,
    ) {
        barrierCoordinator.abortDrainLocked()
        val current = memory.requireDocument()
        if (current.state in TERMINAL_STATES || current.state == ExperimentState.PAUSED) return
        val containment = memory.pendingResourceContainment ?: ResourceContainment(emptyMap(), emptyMap())
        require(containment.verifiedInactive.keys.intersect(containment.attempted.keys).isEmpty()) {
            "A resource cannot be both verified inactive and awaiting cleanup"
        }
        val trustedVector = resourcePlane.currentAppliedVector()
        val pausedVector = AppliedResourceVector(
            trustedVector.resources.map { trusted ->
                containment.verifiedInactive[trusted.key]?.let { generation ->
                    inactiveResource(trusted.key, generation)
                } ?: trusted
            },
        )
        val cleanupAttempts = containment.attempted.values.map(::durableCleanup)
        val now = ctx.clocks.now()
        val clock = runCatching { clockPolicy.advanceClock(current, now) }.getOrElse { clockPolicy.recoveryClock(current, now) }
        val inputs = lifecycleInputsToPause(memory.automationCheckpoint, reducerClock(clock))
        val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, inputs)
        val commandId = ctx.commandId("safety-${reason.name.lowercase()}", current.nextCommitSequence)
        val transitionReason = reason.transitionReason.name
        val activeEpoch = current.activeConditionEpoch
        if (activeEpoch != null) runCatching { resourcePlane.suspendAndFlushLocked(now) }
        val resourceAudit = if (activeEpoch == null) {
            ResourceAuditBatch.EMPTY
        } else {
            runCatching {
                auditTrail.deactivateResourceAuditsLocked(
                    activeEpoch,
                    trustedVector,
                    now,
                    resourceFailure.toResourceAuditRemovalReason(reason),
                )
            }.getOrElse { auditTrail.retireResourceAuditTimersLocked(now, "LIFECYCLE_ENDED") }
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
        val effects = commitLog.appendReductionLocked(
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
        memory.pendingResourceContainment = null
        effectRunner.performPostCommitEffectsLocked(
            effects + resourceAudit.effects + PostCommitEffects(actionsInactive = outbox.pendingActionIdsLocked()),
        )
        resourcePlane.finalizePausedResourceCleanupLocked(recovery = false)
    }
}
