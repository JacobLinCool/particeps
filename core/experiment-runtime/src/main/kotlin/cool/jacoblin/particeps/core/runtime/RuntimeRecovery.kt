package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.resource.AppliedResourceState

/**
 * Cold start and fail-closed recovery: it loads and authenticates the retained runtime, restores
 * [RuntimeMemory] from its components, and pauses a runtime that stopped with staged input or in
 * an active state, consuming that input. Extracted from [ExperimentRuntime]; not a concurrent
 * actor. Every member runs on the caller's coroutine while the caller holds the runtime mutex, and
 * suspending members end in `Locked`. It never acquires the runtime mutex, launches or enqueues,
 * and suspends only in store and platform adapter calls, directly or through the collaborators it
 * drives. It reads runtime state through [RuntimeMemory] at use time and never retains it.
 */
internal class RuntimeRecovery(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val effectRunner: PostCommitEffectRunner,
    private val resourcePlane: ResourceVectorController,
    private val auditTrail: ResourceAuditTrail,
    private val deadlineTimers: StudyDeadlineTimers,
    private val sourcePrep: SourcePreparation,
    private val clockPolicy: RuntimeClockPolicy,
    private val outbox: ActionOutbox,
    private val lifecycleCoordinator: LifecycleCoordinator,
    private val discontinuities: ClockDiscontinuityHandler,
) {
    private val memory = ctx.memory

    /**
     * Loads the retained runtime, or creates and stores the initial one, checks that it belongs to
     * this study, and restores [RuntimeMemory] from it.
     */
    suspend fun loadLocked(): RuntimeDocument {
        val retainedTransition = RetainedStateTransition()
        var loaded = ctx.store.loadRuntime(retainedTransition::observe)
        if (loaded == null) {
            loaded = RuntimeDocument.initial(
                experimentId = ctx.study.experimentId,
                configurationId = ctx.study.configurationId,
                configurationSha256 = ctx.study.configurationSha256,
                activityTokenKeyBase64Url = ctx.entropy.next(RuntimeEntropyKind.ACTIVITY_TOKEN_KEY),
                assignedParticipantId = ctx.study.assignedParticipantId,
                participantInstanceId = ctx.entropy.next(RuntimeEntropyKind.PARTICIPANT_INSTANCE_UUID),
            )
            ctx.store.initialize(loaded)
        }
        validateIdentity(loaded)
        memory.document = loaded
        memory.stateEntry = retainedTransition.entry(loaded)
        restoreComponents(loaded)
        return loaded
    }

    /**
     * Recovers fail-closed from a staged input or an interrupted active state, or finishes a paused
     * runtime's cleanup and re-anchors its clock, then publishes the snapshot and retracts inactive
     * actions. Returns whether it recovered.
     */
    suspend fun recoverOrReanchorLocked(loaded: RuntimeDocument): Boolean {
        val pending = ctx.store.loadPendingInput()
        val recover = pending != null || loaded.state in RECOVERY_FAIL_CLOSED_STATES
        if (recover) {
            recoverFailClosedLocked(pending)
            check(memory.resourceAuditTimers.isEmpty()) { "Recovery retained a resource audit timer" }
        } else {
            if (loaded.state == ExperimentState.PAUSED) {
                resourcePlane.finalizePausedResourceCleanupLocked(recovery = true)
                discontinuities.reanchorPausedAcrossBootLocked()
            }
            require(memory.resourceAuditTimers.isEmpty()) { "Inactive runtime retains a resource audit timer" }
        }
        commitLog.publishSnapshot()
        outbox.retractInactiveActionsLocked()
        return recover
    }

    suspend fun recoverFailClosedLocked(pending: PendingEngineInput?) {
        ctx.gate.forceClose()
        val current = memory.requireDocument()
        val trustedVector = resourcePlane.currentAppliedVector()
        val cleanupAttempts = (
            resourcePlane.deriveRecoveryCleanupAttempts(trustedVector, memory.automationCheckpoint.desiredResources) +
                memory.resourceCleanupAttempts.values.associateBy(DurableResourceCleanup::key)
            ).toSortedMap()
        val now = ctx.clocks.now()
        val clock = clockPolicy.recoveryClock(current, now)
        val prepared = pending?.let { sourcePrep.preparePending(current, it) } ?: PreparedSources.empty(current)
        val checkpoint = memory.automationCheckpoint
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
        val reduction = ctx.reducer.reduceBatch(ctx.program, checkpoint, inputs)
        val commandId = ctx.commandId("process-recovery", current.nextCommitSequence)
        val vector = trustedVector
        val resourceAudit = auditTrail.retireResourceAuditTimersLocked(now, "QUALITY_GAP_RESET")
        val deadlineUpdate = if (ctx.timeline.isElapsed(clock)) {
            DeadlineTimerUpdate.EMPTY
        } else {
            deadlineTimers.reconcileStudyDeadlineTimer(
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
        val effects = commitLog.appendReductionLocked(
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
        effectRunner.performPostCommitEffectsLocked(
            effects + resourceAudit.effects + deadlineUpdate.effects +
                PostCommitEffects(actionsInactive = outbox.pendingActionIdsLocked()),
        )
        resourcePlane.finalizePausedResourceCleanupLocked(recovery = true)
        if (memory.requireDocument().state == ExperimentState.PAUSED && ctx.timeline.isElapsed(clock)) {
            lifecycleCoordinator.completePausedAtDeadlineLocked(now)
        }
    }

    private fun restoreComponents(runtime: RuntimeDocument) {
        val checkpointParts = runtime.components
            .filterKeys { it.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT && it.id.startsWith("main") }
            .toSortedMap()
            .values
        memory.automationCheckpoint = if (checkpointParts.isEmpty()) {
            AutomationCheckpoint()
        } else {
            RuntimeComponentCodec.decodeCheckpoint(checkpointParts.joinToString(separator = ""))
        }
        memory.actionInvocations = runtime.components.filterKeys { it.kind == RuntimeComponentKind.ACTION_INVOCATION }
            .values.map(RuntimeComponentCodec::decodeAction)
            .associateByTo(sortedMapOf(), DurableActionInvocation::actionId)
        memory.latestUploadAcknowledgement = runtime.components.entries
            .singleOrNull { it.key.kind == RuntimeComponentKind.UPLOAD_ACKNOWLEDGEMENT }
            ?.value
            ?.let(RuntimeComponentCodec::decodeUploadAcknowledgement)
        memory.appliedResources = runtime.components.filterKeys { it.kind == RuntimeComponentKind.RESOURCE }
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
        memory.resourceCleanupAttempts = cleanupEntries.associateByTo(sortedMapOf(), DurableResourceCleanup::key)
        require(
            memory.resourceCleanupAttempts.isEmpty() ||
                (runtime.state == ExperimentState.PAUSED && runtime.activeConditionEpoch == null),
        ) { "Resource cleanup components require a closed paused runtime" }
        memory.resourceAuditTimers = runtime.components.filterKeys {
            it.kind == RuntimeComponentKind.RESOURCE_AUDIT_TIMER
        }
            .values.map(RuntimeComponentCodec::decodeTimer)
            .filter { it.producerKey.startsWith(RESOURCE_AUDIT_PRODUCER_PREFIX) }
            .associateByTo(sortedMapOf(), DurableTimer::id)
        memory.studyDeadlineTimer = runtime.components.entries
            .singleOrNull { it.key.kind == RuntimeComponentKind.STUDY_DEADLINE_TIMER }
            ?.also { require(it.key.id == STUDY_DEADLINE_COMPONENT_ID) { "Invalid study deadline component key" } }
            ?.value
            ?.let(RuntimeComponentCodec::decodeTimer)
            ?.also(deadlineTimers::requireStudyDeadlineTimer)
    }

    private fun validateIdentity(runtime: RuntimeDocument) {
        require(runtime.experimentId == ctx.study.experimentId) { "Experiment ID mismatch" }
        require(runtime.configurationId == ctx.study.configurationId) { "Configuration ID mismatch" }
        require(runtime.configurationSha256 == ctx.study.configurationSha256) { "Configuration digest mismatch" }
        require(runtime.assignedParticipantId == ctx.study.assignedParticipantId) { "Participant assignment mismatch" }
    }
}

/**
 * Finds, in the one authenticated pass of cold-start recovery, the commit that entered the
 * recovered state. Later commits in the same state (upload acknowledgements, paused clock
 * re-anchors, action results) advance the clock anchor but are not that transition. A commit is
 * a transition only when its retained predecessor is known to be in another state, so a
 * transition below the retained floor stays unknown rather than being guessed.
 */
private class RetainedStateTransition {
    private var lastState: ExperimentState? = null
    private var lastSequence: Long? = null
    private var entered: StateEntry? = null

    fun observe(commit: EngineCommit) {
        val state = commit.successorProjection.state
        if (lastState != null && state != lastState) entered = StateEntry.of(commit)
        lastState = state
        lastSequence = commit.commitSequence
    }

    fun entry(recovered: RuntimeDocument): StateEntry? {
        if (lastSequence == null) return null
        check(lastSequence == recovered.revision && lastState == recovered.state) {
            "Recovery observed a retained log that does not end at the recovered runtime"
        }
        return entered
    }
}
