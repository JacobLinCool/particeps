package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.withComputedDigest
import cool.jacoblin.particeps.core.resource.ResourceAuditRemovalReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The coordinated resource barrier: it drains admission at one boundary, commits the drained input
 * and the causal change with the epoch they close, applies the next resource vector and reopens
 * admission under a new epoch. Extracted from [ExperimentRuntime]; not a concurrent actor. Every
 * member runs on the caller's coroutine while the caller holds the runtime mutex (for a queued
 * [CoordinatedBarrier], the barrier consumer's), and suspending members end in `Locked`. It never
 * acquires the runtime mutex, launches or enqueues, and suspends only in actuator, audit source,
 * store, wakeup and notifier calls, the containment port, and the per-drain lock of
 * [BarrierInputBuffer]. It reads runtime state through [RuntimeMemory] at use time and never
 * retains it.
 */
internal class ResourceBarrierCoordinator(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val effectRunner: PostCommitEffectRunner,
    private val resourcePlane: ResourceVectorController,
    private val auditTrail: ResourceAuditTrail,
    private val sourcePrep: SourcePreparation,
    private val clockPolicy: RuntimeClockPolicy,
    private val containmentPort: RuntimeContainment,
) {
    private val memory = ctx.memory

    /**
     * Starts draining admission at [boundary] into a new [BarrierInputBuffer] that stages its input
     * at that boundary. The buffer is published before the gate drains, because a submitter the
     * drain wakes re-classifies at once and offers its input to the published buffer.
     */
    fun openDrainLocked(
        firstObservationSequence: Long,
        firstEventSequence: Long,
        epochId: ConditionEpochId,
        sourceCheckpoints: Map<EventSourceId, SourceCheckpoint>,
        boundary: ResearchTime,
        pending: PendingEngineInput?,
    ): Drain {
        val buffer = BarrierInputBuffer(
            firstObservationSequence,
            firstEventSequence,
            epochId,
            sourceCheckpoints,
            boundary,
            pending,
            ctx.gate,
            ctx.store,
        )
        memory.barrierBuffer = buffer
        return Drain(buffer, ctx.gate.beginDrain(boundary))
    }

    /** Ends a drain after the commit that consumed its input; admission stays closed until an epoch opens it. */
    fun closeDrainLocked(token: AdmissionToken) {
        ctx.gate.close(token)
        memory.barrierBuffer = null
    }

    /**
     * Closes admission at once and drops the drain buffer, if any. Whatever the buffer accepted is
     * already in the durable pending slot.
     */
    fun abortDrainLocked() {
        ctx.gate.forceClose()
        memory.barrierBuffer = null
    }

    fun openAdmission(epochId: ConditionEpochId, clock: StudyClockCheckpoint) {
        check(!ctx.timeline.isElapsed(clock)) { "Cannot open admission after the signed study duration" }
        ctx.gate.open(epochId, ctx.timeline.sameBootDeadline(clock))
    }

    suspend fun resourceBarrierLocked(
        inputKind: EngineInputKind,
        causalReducerInput: (Long) -> ReducerInput,
        causalEvents: List<EventDraft>,
        clock: StudyClockCheckpoint,
    ): Boolean {
        val current = memory.requireDocument()
        val epoch = requireNotNull(current.activeConditionEpoch) { "A resource barrier requires an active epoch" }
        val boundary = ctx.clocks.now()
        val drain = openDrainLocked(
            current.nextObservationSequence,
            current.nextEventSequence,
            epoch.id,
            current.sourceCheckpoints,
            boundary,
            null,
        )
        return finishResourceBarrierLocked(
            current = current,
            epoch = epoch,
            boundary = boundary,
            buffer = drain.buffer,
            drainToken = drain.token,
            causalSubmissions = emptyList(),
            inputKind = inputKind,
            causalReducerInput = causalReducerInput,
            causalEvents = causalEvents,
            clock = clock,
        )
    }

    suspend fun completeCoordinatedBarrierLocked(request: CoordinatedBarrier): Boolean {
        if (request is FailClosedBarrier) {
            val durablePending = ctx.store.loadPendingInput()
            request.pending?.let { expected ->
                require(durablePending == expected) { "Fail-closed pending input changed before recovery" }
            }
            if (durablePending != null) {
                containmentPort.recoverFailClosedLocked(durablePending)
            } else {
                containmentPort.safetyPauseLocked(request.reason, null)
            }
            return false
        }
        val current = memory.requireDocument()
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

    suspend fun finishResourceBarrierLocked(
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
            val flushCursors = resourcePlane.suspendAndFlushLocked(boundary)
            val barrier = buffer.snapshot()
            val submissions = causalSubmissions + barrier.submissions
            val durablePending = barrier.pending
            require(
                (submissions.isEmpty() && durablePending == null) ||
                    durablePending?.submissions == submissions.map(SourceSubmission::toPending),
            ) { "Barrier submissions do not match the durable pending slot" }
            val prepared = sourcePrep.prepareSources(
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
                    add(event.toReducerInput(memory.automationCheckpoint.evaluatedThroughSequence + index + 1L, reducerClock))
                }
                causalReducerInput?.let { factory ->
                    add(factory(memory.automationCheckpoint.evaluatedThroughSequence + size + 1L))
                }
            }
            val finalReduction = if (reducerInputs.isEmpty()) {
                emptyReduction(memory.automationCheckpoint)
            } else {
                ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, reducerInputs)
            }
            val oldVector = resourcePlane.currentAppliedVector()
            val deactivationAudit = auditTrail.deactivateResourceAuditsLocked(
                epoch,
                oldVector,
                boundary,
                ResourceAuditRemovalReason.PROFILE_REPLACED,
            )
            val firstEffects = commitLog.appendReductionLocked(
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
            closeDrainLocked(drainToken)
            val vector = resourcePlane.applyDesiredVectorLocked(finalReduction.checkpoint.desiredResources, "barrier-${current.nextCommitSequence}")
            val activatedAt = ctx.clocks.now()
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
                clock = clockPolicy.advanceClock(memory.requireDocument(), activatedAt),
                extraMutations = vector.resources.map(::upsertResource) + activationAudit.mutations,
            )
            resourcePlane.resumeAppliedVectorLocked(vector)
            openAdmission(newEpoch.id, requireNotNull(memory.requireDocument().clockCheckpoint))
            resourcePlane.notifyAdmissionOpenedLocked(vector)
            commitLog.publishSnapshot()
            effectRunner.performPostCommitEffectsLocked(
                firstEffects + deactivationAudit.effects + secondEffects + activationAudit.effects,
            )
            true
        } catch (_: Throwable) {
            abortDrainLocked()
            val stillPending = ctx.store.loadPendingInput()
            if (stillPending != null) containmentPort.recoverFailClosedLocked(stillPending) else {
                containmentPort.safetyPauseLocked(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE, null)
            }
            false
        }
}

/** An open drain: the buffer that collects its input and the gate's barrier-flush token. */
internal class Drain(val buffer: BarrierInputBuffer, val token: AdmissionToken)

/**
 * The input one drain admits after its boundary: pre-drain batches and one boundary flush per
 * retrospective source, each durably appended to the pending slot before it is accepted. A collector
 * thread can call [offer] holding only this buffer's lock and not the runtime mutex, so the buffer
 * touches only the admission gate, the store and pure validation, never [RuntimeMemory].
 */
internal class BarrierInputBuffer(
    private val firstObservationSequence: Long,
    private val firstEventSequence: Long,
    private val conditionEpochId: ConditionEpochId,
    startingSourceCheckpoints: Map<EventSourceId, SourceCheckpoint>,
    private val stagedAt: ResearchTime,
    initialPending: PendingEngineInput?,
    private val gate: EventAdmissionGate,
    private val store: StudyStore,
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
        EmitBatchResult.Accepted(observation, submission.events.size)
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

internal sealed interface CoordinatedBarrier {
    val completion: CompletableDeferred<Unit>
}

internal class StagedSourceBarrier(
    val causal: SourceSubmission,
    val inputKind: EngineInputKind,
    val clock: StudyClockCheckpoint,
    val pending: PendingEngineInput,
    val boundary: ResearchTime,
    val drainToken: AdmissionToken,
    val buffer: BarrierInputBuffer,
    override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
) : CoordinatedBarrier

internal class PostCommitBarrier(
    val inputKind: EngineInputKind,
    val clock: StudyClockCheckpoint,
    val boundary: ResearchTime,
    val drainToken: AdmissionToken,
    val buffer: BarrierInputBuffer,
    override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
) : CoordinatedBarrier

internal class FailClosedBarrier(
    val reason: SafetyPauseReason,
    val pending: PendingEngineInput?,
    override val completion: CompletableDeferred<Unit> = CompletableDeferred(),
) : CoordinatedBarrier
