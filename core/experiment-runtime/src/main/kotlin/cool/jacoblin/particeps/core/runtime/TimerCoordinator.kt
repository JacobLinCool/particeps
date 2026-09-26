package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.ResourceAuditRequest

/**
 * Durable timers that come due: the signed study deadline, periodic resource audits and
 * automation timers, and the durable set that wakeup adapters re-arm. Extracted from
 * [ExperimentRuntime]; not a concurrent actor. Every member runs on the caller's coroutine while
 * the caller holds the runtime mutex, and suspending members end in `Locked`. It never acquires
 * the runtime mutex, launches or enqueues, and suspends only in store and platform adapter calls,
 * directly or through the collaborators it drives. It reads runtime state through [RuntimeMemory]
 * at use time and never retains it.
 */
internal class TimerCoordinator(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val effectRunner: PostCommitEffectRunner,
    private val auditTrail: ResourceAuditTrail,
    private val clockPolicy: RuntimeClockPolicy,
    private val lifecycleCoordinator: LifecycleCoordinator,
    private val barrierCoordinator: ResourceBarrierCoordinator,
    private val deadlineTimers: StudyDeadlineTimers,
) {
    private val memory = ctx.memory

    suspend fun onTimerDueLocked(
        timerId: String,
        generation: ULong,
    ): RuntimeCommandResult {
        val current = memory.requireDocument()
        val deadlineTimer = memory.studyDeadlineTimer
        if (deadlineTimer?.id == timerId) {
            if (deadlineTimer.generation != generation) {
                return RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION)
            }
            if (current.state !in setOf(ExperimentState.RUNNING, ExperimentState.PAUSED)) {
                return if (current.state in TERMINAL_STATES) {
                    RuntimeCommandResult.Success
                } else {
                    RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
                }
            }
            val now = ctx.clocks.now()
            val clock = clockPolicy.advanceClock(current, now)
            if (!timerIsDue(deadlineTimer, reducerClock(clock)) || !ctx.timeline.isElapsed(clock)) {
                return RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_DUE)
            }
            return lifecycleCoordinator.stopSessionLocked(
                terminalState = ExperimentState.COMPLETED,
                requestEvent = "STUDY_COMPLETE_REQUESTED",
                resultEvent = "STUDY_COMPLETED",
                transitionReason = "STUDY_DURATION_ELAPSED",
                epochReason = "STUDY_COMPLETED",
                operationNow = now,
                collectionBoundary = deadlineTimers.deadlineCollectionBoundary(deadlineTimer),
                causalEvents = listOf(RuntimeEventFactory.timerDue(deadlineTimer, now)),
                deadlineRetirementReason = "FIRED",
                inputKind = EngineInputKind.TIMER_WAKE,
            )
        }
        val resourceAuditTimer = memory.resourceAuditTimers[timerId]
        val automationTimer = memory.automationCheckpoint.timers[timerId]
        if (current.state != ExperimentState.RUNNING) {
            if (resourceAuditTimer == null && automationTimer == null) return RuntimeCommandResult.Success
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        resourceAuditTimer?.let { auditTimer ->
            return onResourceAuditTimerDueLocked(auditTimer, generation)
        }
        val timer = automationTimer ?: return RuntimeCommandResult.Success
        if (timer.generation != generation) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION)
        }
        val now = ctx.clocks.now()
        val clock = clockPolicy.advanceClock(current, now)
        val reducerClock = reducerClock(clock)
        if (!timerIsDue(timer, reducerClock)) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_DUE)
        }
        val logicalDue = RuntimeEventFactory.timerLogicalTarget(timer)
        val input = ReducerInput.TimerDue(
            sequenceNumber = memory.automationCheckpoint.evaluatedThroughSequence + 1,
            clock = reducerClock,
            timerId = timer.id,
            automationId = timer.automationId,
            generation = timer.generation,
            causalSequence = timer.causalSequence,
            target = timer.target,
            logicalDue = logicalDue,
        )
        val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(input))
        val dueEvent = RuntimeEventFactory.timerDue(timer, now)
        if (reduction.resourceChanges.isNotEmpty()) {
            val applied = barrierCoordinator.resourceBarrierLocked(
                inputKind = EngineInputKind.TIMER_WAKE,
                causalReducerInput = { sequence -> input.copy(sequenceNumber = sequence) },
                causalEvents = listOf(dueEvent),
                clock = clock,
            )
            if (!applied) return RuntimeCommandResult.FailedClosed(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        } else {
            val effects = commitLog.appendReductionLocked(
                inputKind = EngineInputKind.TIMER_WAKE,
                reduction = reduction,
                eventDrafts = listOf(dueEvent),
                clock = clock,
                timerRetirementReason = "FIRED",
            )
            effectRunner.performPostCommitEffectsLocked(effects)
        }
        return RuntimeCommandResult.Success
    }

    fun pendingTimersLocked(): List<DurableTimer> {
        val bootId = ctx.clocks.now().bootSessionId
        val wakeableDeadline = memory.studyDeadlineTimer?.takeIf { timer ->
            (timer.target as? TimerTarget.SameBootMonotonic)?.bootSessionId == bootId
        }
        return (memory.automationCheckpoint.timers.values + memory.resourceAuditTimers.values + listOfNotNull(wakeableDeadline)).sortedWith(
            compareBy<DurableTimer>({ it.automationId }, { it.id }),
        )
    }

    private suspend fun onResourceAuditTimerDueLocked(
        timer: DurableTimer,
        generation: ULong,
    ): RuntimeCommandResult {
        if (timer.generation != generation) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION)
        }
        val current = memory.requireDocument()
        val epoch = requireNotNull(current.activeConditionEpoch) { "Resource audit timer requires an active epoch" }
        val owner = ctx.hosts.values.singleOrNull { auditTrail.resourceAuditProducerKey(it.key) == timer.producerKey }
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_FOUND)
        val source = owner.auditSource
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.TIMER_NOT_FOUND)
        val applied = memory.appliedResources[owner.key]
            ?.takeIf { it.status == AppliedResourceStatus.APPLIED }
            ?: return retireStaleResourceAuditTimerLocked(timer, current, "GENERATION_REPLACED")
        val evidence = applied.auditEvidence()
        if (
            timer.generation != evidence.generation.value ||
            timer.id != auditTrail.resourceAuditTimerId(source, evidence, epoch, timer.causalSequence, timer.target)
        ) {
            return retireStaleResourceAuditTimerLocked(timer, current, "GENERATION_REPLACED")
        }
        timer.target as? TimerTarget.SameBootMonotonic
            ?: error("Resource audit timer must use same-boot monotonic time")
        val now = ctx.clocks.now()
        val clock = clockPolicy.advanceClock(current, now)
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
        val successor = auditTrail.resourceAuditTimer(source, evidence, epoch, now)
        val effects = commitLog.appendCommitLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            checkpoint = memory.automationCheckpoint,
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
        effectRunner.performPostCommitEffectsLocked(
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
        val now = ctx.clocks.now()
        val effects = commitLog.appendCommitLocked(
            inputKind = EngineInputKind.TIMER_WAKE,
            checkpoint = memory.automationCheckpoint,
            eventDrafts = listOf(RuntimeEventFactory.timerRetired(timer, reason, now)),
            clock = clockPolicy.advanceClock(current, now),
            extraMutations = listOf(removeResourceAuditTimer(timer.id)),
        )
        effectRunner.performPostCommitEffectsLocked(
            effects + PostCommitEffects(timerIntents = listOf(TimerIntent.Retire(timer.id, timer.generation))),
        )
        return RuntimeCommandResult.Success
    }
}
