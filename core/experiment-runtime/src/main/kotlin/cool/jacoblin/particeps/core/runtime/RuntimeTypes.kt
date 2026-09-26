package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerProductionRequest
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.PendingSourceSubmission
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceKey

/**
 * The commit that entered the current state, as its committed wall time and the study's
 * calendar time at that commit. The calendar time is monotonic study time, so a finished
 * study's length does not depend on how far this phone's clock is from network time. It is
 * null for a commit before Start, which has no study clock.
 */
internal data class StateEntry(val wallTimeUtcMillis: Long, val calendarElapsedNanos: Long?) {
    companion object {
        fun of(commit: EngineCommit) = StateEntry(
            wallTimeUtcMillis = commit.committedAt.wallTimeUtcMillis,
            calendarElapsedNanos = commit.successorProjection.clockCheckpoint?.calendarElapsedNanos,
        )
    }
}

internal data class SourceSubmission(
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

internal data class SourceSubmissionIdentity(
    val sourceId: EventSourceId,
    val schemaVersion: Int,
    val resourceGeneration: Long,
    val producerOrdinal: Long,
)

internal data class EventSequenceRange(
    val first: Long?,
    val last: Long?,
)

internal data class PreparedSources(
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

internal data class PostCommitEffects(
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

internal data class ResourceAuditBatch(
    val events: List<EventDraft>,
    val mutations: List<RuntimeMutation>,
    val effects: PostCommitEffects,
) {
    companion object {
        val EMPTY = ResourceAuditBatch(emptyList(), emptyList(), PostCommitEffects())
    }
}

internal data class DeadlineTimerUpdate(
    val events: List<EventDraft>,
    val mutations: List<RuntimeMutation>,
    val effects: PostCommitEffects,
) {
    companion object {
        val EMPTY = DeadlineTimerUpdate(emptyList(), emptyList(), PostCommitEffects())
    }
}

internal data class ResourceContainment(
    val verifiedInactive: Map<ResourceKey, ResourceGeneration>,
    val attempted: Map<ResourceKey, DesiredResourceState>,
)

internal data class BarrierSnapshot(
    val submissions: List<SourceSubmission>,
    val pending: PendingEngineInput?,
)

internal class ContainedActionFailure(val reason: SafetyPauseReason) : RuntimeException()

internal class SourceGap(val reason: SourceQualityGapReason) : IllegalArgumentException()

internal class RequiredResourceFailure : IllegalStateException()

internal class ClockDiscontinuity : IllegalStateException()

internal val RECOVERY_FAIL_CLOSED_STATES = setOf(
    ExperimentState.ACTIVATING,
    ExperimentState.RUNNING,
    ExperimentState.PAUSING,
)
internal val TERMINAL_STATES = setOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN)
internal val ATTEMPTED_CLEANUP_HEALTH_STATES = setOf(
    ResourceHealthStatus.PREPARED,
    ResourceHealthStatus.APPLIED,
    ResourceHealthStatus.SUSPENDED,
    ResourceHealthStatus.FAILED,
)
internal val TRUSTED_APPLIED_HEALTH_STATES = setOf(
    ResourceHealthStatus.APPLIED,
    ResourceHealthStatus.SUSPENDED,
)
internal val TERMINAL_ACTION_STATES = setOf(RuntimeActionState.SUCCEEDED, RuntimeActionState.FAILED)
internal val REQUIRED_DELIVERY_FAILURES = setOf(
    ActionExecutionFailure.DELIVERY_FAILED,
    ActionExecutionFailure.RECONCILIATION_FAILED,
)
internal val PENDING_ACTION_STATES = setOf(
    RuntimeActionState.READY,
    RuntimeActionState.CLAIMED,
    RuntimeActionState.OPENED,
)
internal const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
internal const val MAX_COMPONENT_CHARS = 480 * 1_024
internal const val NANOS_PER_SECOND = 1_000_000_000L
internal const val MILLIS_PER_SECOND = 1_000L
internal const val RESOURCE_AUDIT_PRODUCER_PREFIX = "resource-audit:"
internal const val STUDY_DEADLINE_COMPONENT_ID = "study-duration"
internal const val STUDY_DURATION_AUTOMATION_ID = "study-duration"
internal const val STUDY_DEADLINE_PRODUCER_KEY = "study-deadline"
