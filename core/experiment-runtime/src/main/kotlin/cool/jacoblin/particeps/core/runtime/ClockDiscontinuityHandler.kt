package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyTimelineAdvance
import cool.jacoblin.particeps.core.resource.ResourceAuditRemovalReason

/**
 * Wall-clock and boot discontinuities: a TIME_SET or TIMEZONE_CHANGE input, which rotates a running
 * epoch or re-anchors a paused clock, and the paused re-anchor after a reboot. Extracted from
 * [ExperimentRuntime]; not a concurrent actor. Every member runs on the caller's coroutine while the
 * caller holds the runtime mutex, and suspending members end in `Locked`. It never acquires the
 * runtime mutex, launches or enqueues, and suspends only in store and platform adapter calls,
 * directly or through the collaborators it drives, and in the containment port. It reads runtime
 * state through [RuntimeMemory] at use time and never retains it.
 */
internal class ClockDiscontinuityHandler(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val effectRunner: PostCommitEffectRunner,
    private val resourcePlane: ResourceVectorController,
    private val auditTrail: ResourceAuditTrail,
    private val deadlineTimers: StudyDeadlineTimers,
    private val barrierCoordinator: ResourceBarrierCoordinator,
    private val clockPolicy: RuntimeClockPolicy,
    private val containmentPort: RuntimeContainment,
) {
    private val memory = ctx.memory

    suspend fun onClockDiscontinuityLocked(): RuntimeCommandResult {
        val current = memory.requireDocument()
        if (current.state !in setOf(ExperimentState.RUNNING, ExperimentState.PAUSED)) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val oldClock = current.clockCheckpoint
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        val now = ctx.clocks.now()
        if (
            oldClock.anchor.bootSessionId != now.bootSessionId ||
            now.elapsedRealtimeNanos < oldClock.anchor.elapsedRealtimeNanos
        ) {
            containmentPort.safetyPauseLocked(SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN, null)
            return RuntimeCommandResult.FailedClosed(SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN)
        }
        val advanced = ctx.timeline.advance(
            oldClock.copy(deadlineUtcTrusted = false),
            current.state,
            now,
            ctx.clocks.trustedUtcMillis(),
        ) as? StudyTimelineAdvance.Advanced
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        val clock = advanced.checkpoint.copy(zoneId = canonicalZoneId(ctx.zoneId()))
        if (current.state == ExperimentState.RUNNING) {
            rotateForWallClockGapLocked(current, now, clock)
        } else {
            commitPausedWallClockGapLocked(current, now, clock)
        }
        return RuntimeCommandResult.Success
    }

    private suspend fun commitPausedWallClockGapLocked(
        current: RuntimeDocument,
        now: ResearchTime,
        clock: StudyClockCheckpoint,
    ) {
        val input = ReducerInput.ClockDiscontinuity(
            memory.automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            emptySet(),
        )
        val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(input))
        val deadlineUpdate = if (ctx.timeline.isElapsed(clock)) DeadlineTimerUpdate.EMPTY else {
            deadlineTimers.reconcileStudyDeadlineTimer(clock, reduction.checkpoint.evaluatedThroughSequence, "QUALITY_GAP_RESET")
        }
        val effects = commitLog.appendReductionLocked(
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
        effectRunner.performPostCommitEffectsLocked(effects + deadlineUpdate.effects)
        if (ctx.timeline.isElapsed(clock)) {
            containmentPort.completePausedAtDeadlineLocked(now)
        }
    }

    private suspend fun rotateForWallClockGapLocked(
        current: RuntimeDocument,
        now: ResearchTime,
        clock: StudyClockCheckpoint,
    ) {
        if (ctx.timeline.isElapsed(clock)) {
            completeRunningAtDeadlineAfterWallClockGapLocked(current, now, clock)
            return
        }
        val epoch = requireNotNull(current.activeConditionEpoch)
        val restartKeys = resourcePlane.activeRetrospectiveResourceKeys()
        ctx.gate.forceClose()
        resourcePlane.suspendAppliedResourcesLocked(now)
        val input = ReducerInput.ClockDiscontinuity(
            memory.automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            restartKeys,
        )
        val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(input))
        val oldVector = resourcePlane.currentAppliedVector()
        val deactivationAudit = auditTrail.deactivateResourceAuditsLocked(
            epoch,
            oldVector,
            now,
            ResourceAuditRemovalReason.PROFILE_REPLACED,
        )
        val deadlineUpdate = deadlineTimers.reconcileStudyDeadlineTimer(
            clock,
            reduction.checkpoint.evaluatedThroughSequence,
            "QUALITY_GAP_RESET",
        )
        val firstEffects = commitLog.appendReductionLocked(
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
            resourcePlane.applyDesiredVectorLocked(reduction.checkpoint.desiredResources, "wall-clock-gap-${current.nextCommitSequence}")
        } catch (_: RequiredResourceFailure) {
            containmentPort.safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            return
        }
        val activatedAt = ctx.clocks.now()
        val activatedClock = clockPolicy.advanceClock(memory.requireDocument(), activatedAt)
        val newEpoch = resourcePlane.newEpoch(vector, activatedAt)
        val activationAudit = auditTrail.activateResourceAuditsLocked(newEpoch, vector, activatedAt)
        val secondEffects = commitLog.appendCommitLocked(
            inputKind = EngineInputKind.RESOURCE_RESULT,
            checkpoint = memory.automationCheckpoint,
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
        resourcePlane.resumeAppliedVectorLocked(vector)
        barrierCoordinator.openAdmission(newEpoch.id, activatedClock)
        resourcePlane.notifyAdmissionOpenedLocked(vector)
        effectRunner.performPostCommitEffectsLocked(
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
        val timer = requireNotNull(memory.studyDeadlineTimer) { "Elapsed running study has no durable deadline" }
        val epoch = requireNotNull(current.activeConditionEpoch) { "Running study has no condition epoch" }
        val boundary = deadlineTimers.deadlineCollectionBoundary(timer)
        val restartKeys = resourcePlane.activeRetrospectiveResourceKeys()
        val oldVector = resourcePlane.currentAppliedVector()

        ctx.gate.forceClose()
        resourcePlane.suspendAppliedResourcesLocked(now)

        val reducerClock = reducerClock(clock)
        val reduction = ctx.reducer.reduceBatch(
            ctx.program,
            memory.automationCheckpoint,
            listOf(
                ReducerInput.ClockDiscontinuity(
                    memory.automationCheckpoint.evaluatedThroughSequence + 1,
                    reducerClock,
                    restartKeys,
                ),
                ReducerInput.Lifecycle(
                    memory.automationCheckpoint.evaluatedThroughSequence + 2,
                    reducerClock,
                    StudySessionState.PAUSING,
                ),
            ),
        )
        val commandId = ctx.commandId("study-duration-elapsed", current.nextCommitSequence)
        val retirement = deadlineTimers.retireStudyDeadlineTimer(now, "FIRED")
        val resourceAudit = auditTrail.deactivateResourceAuditsLocked(
            epoch,
            oldVector,
            boundary,
            ResourceAuditRemovalReason.STUDY_COMPLETED,
        )
        val firstEffects = commitLog.appendReductionLocked(
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

        resourcePlane.releaseAllResourcesLocked()
        val completedAt = ctx.clocks.now()
        val completedClock = clockPolicy.advanceClock(memory.requireDocument(), completedAt)
        val completed = ctx.reducer.reduceBatch(
            ctx.program,
            memory.automationCheckpoint,
            listOf(
                ReducerInput.Lifecycle(
                    memory.automationCheckpoint.evaluatedThroughSequence + 1,
                    reducerClock(completedClock),
                    StudySessionState.COMPLETED,
                ),
            ),
        )
        val finalEffects = commitLog.appendReductionLocked(
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
            extraMutations = resourcePlane.inactiveResourceMutations(completed.checkpoint.desiredResources),
        )
        effectRunner.performPostCommitEffectsLocked(
            firstEffects + retirement.effects + resourceAudit.effects + finalEffects,
        )
    }

    /** Reboots never bridge a paused lifetime from ordinary wall time. */
    suspend fun reanchorPausedAcrossBootLocked(): Boolean {
        val current = memory.requireDocument()
        require(current.state == ExperimentState.PAUSED) { "Paused re-anchor requires PAUSED" }
        val oldClock = requireNotNull(current.clockCheckpoint) { "Started study has no clock checkpoint" }
        val now = ctx.clocks.now()
        if (oldClock.anchor.bootSessionId == now.bootSessionId &&
            now.elapsedRealtimeNanos >= oldClock.anchor.elapsedRealtimeNanos
        ) {
            if (ctx.timeline.isElapsed(oldClock)) containmentPort.completePausedAtDeadlineLocked(now)
            return true
        }
        val advanced = ctx.timeline.advance(
            oldClock,
            ExperimentState.PAUSED,
            now,
            ctx.clocks.trustedUtcMillis(),
        ) as? StudyTimelineAdvance.Advanced ?: return false
        val clock = advanced.checkpoint.copy(zoneId = canonicalZoneId(ctx.zoneId()))
        val input = ReducerInput.QualityGap(
            memory.automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            EventSourceId("study_runtime.v1"),
        )
        val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(input))
        val deadlineUpdate = if (ctx.timeline.isElapsed(clock)) {
            DeadlineTimerUpdate.EMPTY
        } else {
            deadlineTimers.reconcileStudyDeadlineTimer(clock, reduction.checkpoint.evaluatedThroughSequence, "QUALITY_GAP_RESET")
        }
        val effects = commitLog.appendReductionLocked(
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
        effectRunner.performPostCommitEffectsLocked(effects + deadlineUpdate.effects)
        if (ctx.timeline.isElapsed(clock)) {
            containmentPort.completePausedAtDeadlineLocked(now)
        }
        return true
    }
}
