package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.ReductionResult
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.withComputedDigest
import kotlinx.coroutines.CancellationException

/**
 * Admits one live submission while admission is active: it commits an observation that changes
 * no desired resource, stages one that does and hands it to the resource barrier, and commits a
 * source quality gap. Extracted from [ExperimentRuntime]; not a concurrent actor. Every member
 * runs on the caller's coroutine while the caller holds the runtime mutex, and suspending members
 * end in `Locked`. It never acquires the runtime mutex or launches, enqueues a [CoordinatedBarrier]
 * only through the coordinator's [enqueueBarrier], and suspends only in store and platform adapter
 * calls, directly or through the collaborators it drives. It reads runtime state through
 * [RuntimeMemory] at use time and never retains it.
 */
internal class AdmissionFrontDoor(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val effectRunner: PostCommitEffectRunner,
    private val barrierCoordinator: ResourceBarrierCoordinator,
    private val sourcePrep: SourcePreparation,
    private val clockPolicy: RuntimeClockPolicy,
    private val enqueueBarrier: (CoordinatedBarrier) -> Boolean,
) {
    private val memory = ctx.memory

    suspend fun processActiveSubmissionLocked(
        submission: SourceSubmission,
        epochId: ConditionEpochId,
    ): EmitBatchResult {
        val current = memory.requireDocument()
        if (current.state != ExperimentState.RUNNING || current.activeConditionEpoch?.id != epochId) {
            return EmitBatchResult.RejectedByAdmissionGate
        }
        val now = ctx.clocks.now()
        val clock = try {
            clockPolicy.advanceClock(current, now)
        } catch (_: ClockDiscontinuity) {
            ctx.gate.forceClose()
            enqueueBarrier(FailClosedBarrier(SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN, null))
            return EmitBatchResult.SourceQualityGap(SourceQualityGapReason.CLOCK_DISCONTINUITY)
        }
        val prepared = try {
            sourcePrep.prepareSources(
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
        val inputs = sourcePrep.recordedEventInputs(prepared.events, clock, memory.automationCheckpoint)
        val reduction = if (inputs.isEmpty()) emptyReduction(memory.automationCheckpoint) else {
            ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, inputs)
        }
        val causalObservation = prepared.observations.single()
        if (reduction.resourceChanges.isNotEmpty()) {
            unchangedLeadingEvents(submission, inputs)?.let { leadingEvents ->
                // Record only the events before the first resource change. The caller offers the
                // rest again, so the change is staged from the event that causes it, and the events
                // captured before that one are never reduced after input the barrier drains.
                val leading = sourcePrep.prepareSources(
                    document = current,
                    submissions = listOf(
                        submission.copy(events = submission.events.subList(0, leadingEvents).toList())
                            .withKind(ObservationAdmissionKind.NORMAL),
                    ),
                    conditionEpochId = epochId,
                    startingCheckpoints = current.sourceCheckpoints,
                )
                val leadingReduction = ctx.reducer.reduceBatch(
                    ctx.program,
                    memory.automationCheckpoint,
                    inputs.subList(0, leadingEvents),
                )
                check(leadingReduction.resourceChanges.isEmpty()) {
                    "Events before the first resource change changed a resource"
                }
                return commitObservationLocked(leading, leadingReduction, clock)
            }
            require(submission.events.isNotEmpty()) { "Coverage-only input cannot change a resource" }
            val pending = PendingEngineInput(
                conditionEpochId = epochId,
                submissions = listOf(submission.withKind(ObservationAdmissionKind.NORMAL).toPending()),
                stagedAt = now,
                encodedSha256 = ZERO_DIGEST,
            ).withComputedDigest()
            try {
                ctx.store.stagePendingInput(pending)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                ctx.gate.forceClose()
                val recovered = runCatching { ctx.store.loadPendingInput() }.getOrNull()
                enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, recovered))
                return if (recovered == pending) accepted(causalObservation) else EmitBatchResult.StorageFailure
            }
            try {
                val boundary = now
                val drain = barrierCoordinator.openDrainLocked(
                    prepared.nextObservationSequence,
                    current.nextEventSequence,
                    epochId,
                    prepared.sourceCheckpoints,
                    boundary,
                    pending,
                )
                val request = StagedSourceBarrier(
                    causal = submission,
                    inputKind = EngineInputKind.SOURCE_OBSERVATION,
                    clock = clock,
                    pending = pending,
                    boundary = boundary,
                    drainToken = drain.token,
                    buffer = drain.buffer,
                )
                check(enqueueBarrier(request)) { "The resource barrier coordinator is unavailable" }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                barrierCoordinator.abortDrainLocked()
                enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, pending))
            }
            return accepted(causalObservation)
        }
        return commitObservationLocked(prepared, reduction, clock)
    }

    /** Commits one admitted observation whose reduction changes no desired resource. */
    private suspend fun commitObservationLocked(
        prepared: PreparedSources,
        reduction: ReductionResult,
        clock: StudyClockCheckpoint,
    ): EmitBatchResult {
        val effects = try {
            commitLog.appendReductionLocked(
                    inputKind = EngineInputKind.SOURCE_OBSERVATION,
                    reduction = reduction,
                    prepared = prepared,
                    clock = clock,
                )
        } catch (_: Throwable) {
            ctx.gate.forceClose()
            enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, null))
            return EmitBatchResult.StorageFailure
        }
        try {
            effectRunner.performPostCommitEffectsLocked(effects)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            ctx.gate.forceClose()
            enqueueBarrier(FailClosedBarrier(SafetyPauseReason.STORAGE_FAILURE, null))
        }
        return accepted(prepared.observations.single())
    }

    private suspend fun commitQualityGapLocked(
        sourceId: EventSourceId,
        reason: SourceQualityGapReason,
        clock: StudyClockCheckpoint,
    ) {
        val now = clock.anchor
        val input = ReducerInput.QualityGap(
            memory.automationCheckpoint.evaluatedThroughSequence + 1,
            reducerClock(clock),
            sourceId,
        )
        val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(input))
        if (reduction.resourceChanges.isNotEmpty() && memory.requireDocument().activeConditionEpoch != null) {
            val effects = commitLog.appendReductionLocked(
                inputKind = EngineInputKind.SOURCE_OBSERVATION,
                reduction = reduction,
                eventDrafts = listOf(RuntimeEventFactory.qualityGap(sourceId, reason, now)),
                clock = clock,
                timerRetirementReason = "QUALITY_GAP_RESET",
            )
            effectRunner.performPostCommitEffectsLocked(effects)
            val committed = memory.requireDocument()
            val epoch = requireNotNull(committed.activeConditionEpoch)
            val drain = barrierCoordinator.openDrainLocked(
                committed.nextObservationSequence,
                committed.nextEventSequence,
                epoch.id,
                committed.sourceCheckpoints,
                now,
                null,
            )
            check(enqueueBarrier(
                PostCommitBarrier(
                    inputKind = EngineInputKind.SOURCE_OBSERVATION,
                    clock = clock,
                    boundary = now,
                    drainToken = drain.token,
                    buffer = drain.buffer,
                ),
            )) { "The resource barrier coordinator is unavailable" }
        } else {
            val effects = commitLog.appendReductionLocked(
                inputKind = EngineInputKind.SOURCE_OBSERVATION,
                reduction = reduction,
                eventDrafts = listOf(RuntimeEventFactory.qualityGap(sourceId, reason, now)),
                clock = clock,
                timerRetirementReason = "QUALITY_GAP_RESET",
            )
            effectRunner.performPostCommitEffectsLocked(effects)
        }
    }

    /**
     * How many leading events of a live [submission] to record before the one that first changes
     * a desired resource, or null to handle the submission whole. The reducer reconciles resources
     * once per observation, so staging a merged callback batch whole would reduce the callbacks
     * captured before its trigger after the input the barrier drains, where each alone would have
     * committed first. A batch with coverage is one retrospective claim and is never split. This
     * runs only for an observation that changes a resource.
     */
    private fun unchangedLeadingEvents(submission: SourceSubmission, inputs: List<ReducerInput.Event>): Int? {
        if (submission.coverage != null || inputs.size < 2) return null
        val first = checkNotNull(ctx.reducer.firstResourceChangingInput(ctx.program, memory.automationCheckpoint, inputs)) {
            "A resource-changing observation has no first resource change"
        }
        return first.takeIf { it > 0 }
    }

    private fun accepted(observation: SourceObservation) = EmitBatchResult.Accepted(
        observation.observationSequence,
        observation.eventCount,
    )
}
