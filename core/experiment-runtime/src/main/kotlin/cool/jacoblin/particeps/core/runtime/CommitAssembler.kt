package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.ReductionResult
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.withComputedDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Assembles and appends every [EngineCommit], then advances [RuntimeMemory] and the published
 * snapshot from it. Extracted from [ExperimentRuntime]; not a concurrent actor. Every member runs
 * on the caller's coroutine while the caller holds the runtime mutex, and suspends only in the
 * store append, which with the memory advance is not cancellable. It reads runtime state through
 * [RuntimeMemory] at use time and never retains it.
 */
internal class CommitAssembler(
    private val ctx: RuntimeContext,
    private val deadlineTimers: StudyDeadlineTimers,
) {
    private val memory = ctx.memory

    suspend fun appendReductionLocked(
        inputKind: EngineInputKind,
        reduction: ReductionResult,
        prepared: PreparedSources = PreparedSources.empty(memory.requireDocument()),
        eventDrafts: List<EventDraft> = emptyList(),
        state: ExperimentState = memory.requireDocument().state,
        epoch: ConditionEpoch? = memory.requireDocument().activeConditionEpoch,
        eventConditionEpochId: ConditionEpochId? = epoch?.id,
        clock: StudyClockCheckpoint = requireNotNull(memory.requireDocument().clockCheckpoint),
        consumedPendingSha256: String? = null,
        consumePending: Boolean = false,
        extraMutations: List<RuntimeMutation> = emptyList(),
        timerRetirementReason: String = "CANCELLED",
        sourceCheckpoints: Map<EventSourceId, SourceCheckpoint> = prepared.sourceCheckpoints,
    ): PostCommitEffects {
        val conditionDigest = reduction.checkpoint.digest()
        val causalSequence = reduction.checkpoint.evaluatedThroughSequence.coerceAtLeast(1)
        val oldTimers = memory.automationCheckpoint.timers
        val timerChanges = committedTimerIntents(oldTimers, reduction.checkpoint.timers, reduction.timerIntents)
        val generatedEvents = buildList {
            reduction.audits.mapNotNullTo(this) {
                RuntimeEventFactory.automationAudit(it, conditionDigest, causalSequence, clock.anchor)
            }
            reduction.actionRequests.forEach {
                add(RuntimeEventFactory.actionRequested(it, conditionDigest, causalSequence, clock.anchor))
            }
            timerChanges.forEach { change ->
                when (change) {
                    is TimerIntent.Schedule -> add(RuntimeEventFactory.timerScheduled(change.timer, clock.anchor))
                    is TimerIntent.Retire -> {
                        val retired = oldTimers.getValue(change.timerId)
                        add(RuntimeEventFactory.timerRetired(retired, timerRetirementReason, clock.anchor))
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
        val timerMutations = timerMutations(oldTimers, reduction.checkpoint.timers)
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
            timerIntents = timerChanges,
            actionsReady = actions.map(DurableActionInvocation::actionId),
            timerProductionRequests = reduction.timerProductionRequests,
        )
    }

    suspend fun appendCommitLocked(
        inputKind: EngineInputKind,
        checkpoint: AutomationCheckpoint,
        prepared: PreparedSources = PreparedSources.empty(memory.requireDocument()),
        eventDrafts: List<EventDraft> = emptyList(),
        state: ExperimentState = memory.requireDocument().state,
        epoch: ConditionEpoch? = memory.requireDocument().activeConditionEpoch,
        eventConditionEpochId: ConditionEpochId? = epoch?.id,
        clock: StudyClockCheckpoint? = memory.requireDocument().clockCheckpoint,
        consumedPendingSha256: String? = null,
        consumePending: Boolean = false,
        extraMutations: List<RuntimeMutation> = emptyList(),
        uploadedThroughCommit: Long = memory.requireDocument().uploadedThroughCommit,
        sourceCheckpoints: Map<EventSourceId, SourceCheckpoint> = prepared.sourceCheckpoints,
    ): PostCommitEffects {
        val current = memory.requireDocument()
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
            committedAt = clock?.anchor ?: ctx.clocks.now(),
            successorProjection = projection,
            resultingCheckpointSha256 = checkpoint.digest(),
            commitSha256 = ZERO_DIGEST,
        ).withComputedDigest()
        val successor = current.advance(commit)
        // The durable append and the in-memory advance are one step. A store append can complete
        // durably and still report cancellation on return (withContext's prompt cancellation), so
        // a cancelled caller waits for the append instead, and memory never trails the store.
        withContext(NonCancellable) {
            if (consumePending) {
                ctx.store.appendCommitConsumingPending(commit, successor)
            } else {
                ctx.store.appendCommit(commit, successor)
            }
            memory.document = successor
            if (successor.state != current.state) memory.stateEntry = StateEntry.of(commit)
            memory.automationCheckpoint = checkpoint
            applyComponentMutations(mutations)
            require(
                memory.resourceCleanupAttempts.isEmpty() ||
                    (successor.state == ExperimentState.PAUSED && successor.activeConditionEpoch == null),
            ) { "Resource cleanup components require a closed paused runtime" }
            publishSnapshot()
        }
        return PostCommitEffects()
    }

    fun publishSnapshot() {
        val current = memory.document ?: return
        ctx.mutableSnapshot.value = RuntimeSnapshot(
            initialized = true,
            state = current.state,
            revision = current.revision,
            conditionEpochId = current.activeConditionEpoch?.id,
            appliedResourceVectorSha256 = current.activeConditionEpoch?.appliedResourceVectorSha256,
            admissionOpen = ctx.gate.isOpen(),
            pendingActionCount = memory.actionInvocations.values.count {
                it.state in PENDING_ACTION_STATES
            },
            lifetimeDataEventCount = current.lifetimeDataEventCount,
            uploadedThroughCommit = current.uploadedThroughCommit,
            retainedFromCommit = current.retainedFromCommit,
            calendarElapsedNanos = current.clockCheckpoint?.calendarElapsedNanos ?: 0,
            activeRunningElapsedNanos = current.clockCheckpoint?.activeRunningElapsedNanos ?: 0,
            clockAnchorWallTimeUtcMillis = current.clockCheckpoint?.anchor?.wallTimeUtcMillis,
            participantInstanceId = current.participantInstanceId,
            startedAtUtcMillis = current.clockCheckpoint?.let {
                Math.subtractExact(it.deadlineUtcMillis, Math.multiplyExact(ctx.study.durationSeconds, MILLIS_PER_SECOND))
            },
            deadlineUtcMillis = current.clockCheckpoint?.deadlineUtcMillis,
            deadlineUtcTrusted = current.clockCheckpoint?.deadlineUtcTrusted == true,
            stateEnteredAtUtcMillis = memory.stateEntry?.wallTimeUtcMillis,
            stateEnteredCalendarElapsedNanos = memory.stateEntry?.calendarElapsedNanos,
        )
    }

    private fun applyComponentMutations(mutations: List<RuntimeMutation>) {
        mutations.forEach { mutation ->
            when (mutation.key.kind) {
                RuntimeComponentKind.ACTION_INVOCATION -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> RuntimeComponentCodec.decodeAction(requireNotNull(mutation.canonicalValue)).also {
                        memory.actionInvocations[it.actionId] = it
                    }
                    RuntimeMutationOperation.REMOVE -> memory.actionInvocations.remove(mutation.key.id)
                }
                RuntimeComponentKind.RESOURCE -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> RuntimeComponentCodec.decodeResource(requireNotNull(mutation.canonicalValue)).also {
                        memory.appliedResources[it.key] = it
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
                        memory.resourceCleanupAttempts[it.key] = it
                    }
                    RuntimeMutationOperation.REMOVE -> memory.resourceCleanupAttempts.remove(
                        cleanupResourceKey(mutation.key.id),
                    )
                }
                RuntimeComponentKind.UPLOAD_ACKNOWLEDGEMENT -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> {
                        memory.latestUploadAcknowledgement = RuntimeComponentCodec.decodeUploadAcknowledgement(
                            requireNotNull(mutation.canonicalValue),
                        )
                    }
                    RuntimeMutationOperation.REMOVE -> memory.latestUploadAcknowledgement = null
                }
                RuntimeComponentKind.RESOURCE_AUDIT_TIMER -> when (mutation.operation) {
                    RuntimeMutationOperation.UPSERT -> RuntimeComponentCodec.decodeTimer(
                        requireNotNull(mutation.canonicalValue),
                    ).takeIf { it.producerKey.startsWith(RESOURCE_AUDIT_PRODUCER_PREFIX) }?.also {
                        memory.resourceAuditTimers[it.id] = it
                    }
                    RuntimeMutationOperation.REMOVE -> memory.resourceAuditTimers.remove(mutation.key.id)
                }
                RuntimeComponentKind.STUDY_DEADLINE_TIMER -> {
                    require(mutation.key.id == STUDY_DEADLINE_COMPONENT_ID) {
                        "Invalid study deadline mutation key"
                    }
                    when (mutation.operation) {
                        RuntimeMutationOperation.UPSERT -> {
                            memory.studyDeadlineTimer = RuntimeComponentCodec.decodeTimer(
                                requireNotNull(mutation.canonicalValue),
                            ).also(deadlineTimers::requireStudyDeadlineTimer)
                        }
                        RuntimeMutationOperation.REMOVE -> memory.studyDeadlineTimer = null
                    }
                }
                else -> Unit
            }
        }
    }
}
