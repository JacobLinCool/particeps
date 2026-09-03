package cool.jacoblin.particeps.core.model

import java.io.IOException
import java.time.ZoneId
import java.util.UUID

data class StudyClockCheckpoint(
    val calendarElapsedNanos: Long,
    val activeRunningElapsedNanos: Long,
    val anchor: ResearchTime,
    val deadlineUtcMillis: Long,
    val deadlineUtcTrusted: Boolean,
    val zoneId: String,
) {
    init {
        require(calendarElapsedNanos >= 0) { "Calendar elapsed time must be non-negative" }
        require(activeRunningElapsedNanos in 0..calendarElapsedNanos) {
            "Active-running time must be within calendar elapsed time"
        }
        require(deadlineUtcMillis >= 0) { "Study deadline must be non-negative" }
        require(zoneId == ZoneId.of(zoneId).id && (zoneId == "UTC" || '/' in zoneId)) {
            "Clock checkpoint requires a canonical IANA zone ID"
        }
    }
}

data class ConditionEpoch(
    val id: ConditionEpochId,
    val configurationSha256: String,
    val appliedResourceVectorSha256: String,
    val activatedAt: ResearchTime,
) {
    init {
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        require(SHA256.matches(appliedResourceVectorSha256)) { "Invalid resource-vector digest" }
    }
}

data class SourceCheckpoint(
    val sourceId: EventSourceId,
    val resourceGeneration: Long,
    val nextProducerOrdinal: Long,
    val coverage: SourceCoverage?,
    val cursor: String?,
) {
    init {
        require(resourceGeneration >= 0) { "Source generation must be non-negative" }
        require(nextProducerOrdinal >= 0) { "Producer ordinal must be non-negative" }
        require(cursor == null || cursor.length <= 4_096) { "Source cursor is too large" }
    }
}

enum class RuntimeComponentKind {
    AUTOMATION_CHECKPOINT,
    TIMER,
    STUDY_DEADLINE_TIMER,
    RESOURCE_AUDIT_TIMER,
    ACTION_INVOCATION,
    UPLOAD_ACKNOWLEDGEMENT,
    RESOURCE,
    RESOURCE_CLEANUP,
}

data class RuntimeComponentKey(
    val kind: RuntimeComponentKind,
    val id: String,
) : Comparable<RuntimeComponentKey> {
    init {
        require(COMPONENT_ID.matches(id)) { "Invalid runtime component ID" }
    }

    override fun compareTo(other: RuntimeComponentKey): Int =
        compareValuesBy(this, other, { it.kind.ordinal }, { it.id })
}

enum class RuntimeMutationOperation {
    UPSERT,
    REMOVE,
}

data class RuntimeMutation(
    val key: RuntimeComponentKey,
    val operation: RuntimeMutationOperation,
    val canonicalValue: String?,
) {
    init {
        when (operation) {
            RuntimeMutationOperation.UPSERT -> require(!canonicalValue.isNullOrBlank()) {
                "Upsert mutation requires a canonical value"
            }
            RuntimeMutationOperation.REMOVE -> require(canonicalValue == null) {
                "Remove mutation cannot carry a value"
            }
        }
        require(canonicalValue == null || canonicalValue.toByteArray().size <= MAX_COMPONENT_BYTES) {
            "Runtime component is too large"
        }
    }
}

enum class EngineInputKind {
    SOURCE_OBSERVATION,
    LIFECYCLE_COMMAND,
    TIMER_WAKE,
    RANDOM_SELECTION,
    ACTION_RESULT,
    UPLOAD_ACKNOWLEDGEMENT,
    RESOURCE_RESULT,
    SAFETY_FAILURE,
    RECOVERY,
}

data class EngineCommit(
    val commitSequence: Long,
    val previousCommitSha256: String,
    val inputKind: EngineInputKind,
    val consumedPendingInputSha256: String?,
    val sourceObservations: List<SourceObservation>,
    val events: List<RecordedEvent>,
    val mutations: List<RuntimeMutation>,
    val committedAt: ResearchTime,
    val successorProjection: RuntimeProjection,
    val resultingCheckpointSha256: String,
    val commitSha256: String,
) {
    init {
        require(commitSequence > 0) { "Commit sequence must be positive" }
        require(previousCommitSha256 == GENESIS_DIGEST || SHA256.matches(previousCommitSha256)) {
            "Invalid previous commit digest"
        }
        require(consumedPendingInputSha256 == null || SHA256.matches(consumedPendingInputSha256)) {
            "Invalid consumed pending-input digest"
        }
        require(SHA256.matches(resultingCheckpointSha256)) { "Invalid checkpoint digest" }
        require(SHA256.matches(commitSha256)) { "Invalid commit digest" }
        require(successorProjection.revision == commitSequence) {
            "Successor projection must advance to the committed revision"
        }
        require(successorProjection.nextCommitSequence == commitSequence + 1) {
            "Successor projection has an invalid next commit sequence"
        }
        require(sourceObservations.zipWithNext().all { (left, right) ->
            left.observationSequence < right.observationSequence
        }) { "Source observations must be strictly ordered" }
        require(events.zipWithNext().all { (left, right) ->
            left.sequenceNumber + 1 == right.sequenceNumber
        }) { "Commit events must be contiguous" }
        require(mutations.map(RuntimeMutation::key).distinct().size == mutations.size) {
            "A commit cannot mutate one runtime component twice"
        }
    }
}

/**
 * The complete scalar successor carried by every authenticated commit. Runtime components are
 * advanced by the commit's typed mutations. Together they make the commit chain independently
 * replayable after the most recent encrypted snapshot without treating the snapshot as truth.
 */
data class RuntimeProjection(
    val state: ExperimentState,
    val revision: Long,
    val nextCommitSequence: Long,
    val nextObservationSequence: Long,
    val nextEventSequence: Long,
    val sourceCheckpoints: Map<EventSourceId, SourceCheckpoint>,
    val clockCheckpoint: StudyClockCheckpoint?,
    val activeConditionEpoch: ConditionEpoch?,
    val lifetimeDataEventCount: Long,
    val uploadedThroughCommit: Long,
    val evaluatedThroughCommit: Long,
    val retainedFromCommit: Long,
) {
    init {
        require(revision >= 0) { "Revision must be non-negative" }
        require(nextCommitSequence == revision + 1) { "Next commit must follow revision" }
        require(nextObservationSequence > 0 && nextEventSequence > 0) { "Invalid next sequence" }
        require(sourceCheckpoints.all { (key, value) -> key == value.sourceId }) {
            "Source checkpoint key mismatch"
        }
        require(lifetimeDataEventCount >= 0) { "Event count must be non-negative" }
        require(uploadedThroughCommit in 0..revision) { "Invalid upload watermark" }
        require(evaluatedThroughCommit in 0..revision) { "Invalid reducer watermark" }
        require(retainedFromCommit in 1..nextCommitSequence) { "Invalid retained commit floor" }
        require(retainedFromCommit <= minOf(uploadedThroughCommit, evaluatedThroughCommit) + 1) {
            "Retained floor exceeds the safe reclaim watermark"
        }
    }
}

data class PendingSourceSubmission(
    val sourceId: EventSourceId,
    val schemaVersion: Int,
    val resourceGeneration: Long,
    val producerOrdinal: Long,
    val admissionKind: ObservationAdmissionKind,
    val events: List<EventDraft>,
    val coverage: SourceCoverage?,
) {
    init {
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(resourceGeneration > 0) { "Resource generation must be positive" }
        require(producerOrdinal >= 0) { "Producer ordinal must be non-negative" }
        require(events.size <= MAX_OBSERVATION_EVENTS) { "Pending submission event count is out of range" }
        require(events.isNotEmpty() || coverage != null) { "Empty pending submission needs coverage" }
        require(events.all { it.type.sourceId == sourceId && it.type.schemaVersion == schemaVersion }) {
            "Pending submission events do not share one source contract"
        }
    }
}

data class PendingEngineInput(
    val conditionEpochId: ConditionEpochId,
    val submissions: List<PendingSourceSubmission>,
    val stagedAt: ResearchTime,
    val encodedSha256: String,
) {
    init {
        require(submissions.size in 1..MAX_PENDING_SUBMISSIONS) { "Pending submission count is out of range" }
        require(SHA256.matches(encodedSha256)) { "Invalid pending input digest" }
    }

    private companion object {
        const val MAX_PENDING_SUBMISSIONS = 4_096
    }
}

/** Exact storage-layout document. Ordered events, not this projection, are lifecycle history. */
data class RuntimeDocument(
    val layoutVersion: Int,
    val experimentId: String,
    val configurationId: String,
    val configurationSha256: String,
    val participantInstanceId: String,
    val assignedParticipantId: String?,
    val state: ExperimentState,
    val revision: Long,
    val nextCommitSequence: Long,
    val nextObservationSequence: Long,
    val nextEventSequence: Long,
    val lastCommitSha256: String,
    val sourceCheckpoints: Map<EventSourceId, SourceCheckpoint>,
    val clockCheckpoint: StudyClockCheckpoint?,
    val activeConditionEpoch: ConditionEpoch?,
    val components: Map<RuntimeComponentKey, String>,
    val lifetimeDataEventCount: Long,
    val uploadedThroughCommit: Long,
    val evaluatedThroughCommit: Long,
    val retainedFromCommit: Long,
    val activityTokenKeyBase64Url: String,
) {
    init {
        require(layoutVersion == LAYOUT_VERSION) { "Unsupported runtime storage layout" }
        require(ID.matches(experimentId) && ID.matches(configurationId)) { "Invalid study ID" }
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        require(UUID_PATTERN.matches(participantInstanceId)) { "Invalid participant instance ID" }
        assignedParticipantId?.let {
            require(ASSIGNED_ID.matches(it) && it.toByteArray().size <= 64) {
                "Invalid assigned participant ID"
            }
        }
        require(revision >= 0) { "Revision must be non-negative" }
        require(nextCommitSequence == revision + 1) { "Next commit must follow revision" }
        require(nextObservationSequence > 0 && nextEventSequence > 0) { "Invalid next sequence" }
        require(lastCommitSha256 == GENESIS_DIGEST || SHA256.matches(lastCommitSha256)) {
            "Invalid last commit digest"
        }
        require(sourceCheckpoints.all { (key, value) -> key == value.sourceId }) {
            "Source checkpoint key mismatch"
        }
        require(components.values.all { it.toByteArray().size <= MAX_COMPONENT_BYTES }) {
            "Runtime component is too large"
        }
        require(lifetimeDataEventCount >= 0) { "Event count must be non-negative" }
        require(uploadedThroughCommit in 0..revision) { "Invalid upload watermark" }
        require(evaluatedThroughCommit in 0..revision) { "Invalid reducer watermark" }
        require(retainedFromCommit in 1..nextCommitSequence) { "Invalid retained commit floor" }
        require(retainedFromCommit <= minOf(uploadedThroughCommit, evaluatedThroughCommit) + 1) {
            "Retained floor exceeds the safe reclaim watermark"
        }
        require(ACTIVITY_KEY.matches(activityTokenKeyBase64Url)) { "Invalid activity-token key" }
    }

    companion object {
        const val LAYOUT_VERSION = 3

        fun initial(
            experimentId: String,
            configurationId: String,
            configurationSha256: String,
            activityTokenKeyBase64Url: String,
            assignedParticipantId: String? = null,
            participantInstanceId: String = UUID.randomUUID().toString(),
        ): RuntimeDocument = RuntimeDocument(
            layoutVersion = LAYOUT_VERSION,
            experimentId = experimentId,
            configurationId = configurationId,
            configurationSha256 = configurationSha256,
            participantInstanceId = participantInstanceId,
            assignedParticipantId = assignedParticipantId,
            state = ExperimentState.IMPORTED,
            revision = 0,
            nextCommitSequence = 1,
            nextObservationSequence = 1,
            nextEventSequence = 1,
            lastCommitSha256 = GENESIS_DIGEST,
            sourceCheckpoints = emptyMap(),
            clockCheckpoint = null,
            activeConditionEpoch = null,
            components = emptyMap(),
            lifetimeDataEventCount = 0,
            uploadedThroughCommit = 0,
            evaluatedThroughCommit = 0,
            retainedFromCommit = 1,
            activityTokenKeyBase64Url = activityTokenKeyBase64Url,
        )
    }

    fun projection(): RuntimeProjection = RuntimeProjection(
        state = state,
        revision = revision,
        nextCommitSequence = nextCommitSequence,
        nextObservationSequence = nextObservationSequence,
        nextEventSequence = nextEventSequence,
        sourceCheckpoints = sourceCheckpoints,
        clockCheckpoint = clockCheckpoint,
        activeConditionEpoch = activeConditionEpoch,
        lifetimeDataEventCount = lifetimeDataEventCount,
        uploadedThroughCommit = uploadedThroughCommit,
        evaluatedThroughCommit = evaluatedThroughCommit,
        retainedFromCommit = retainedFromCommit,
    )

    fun advance(commit: EngineCommit): RuntimeDocument {
        require(commit.commitSequence == nextCommitSequence) { "Commit sequence does not follow runtime" }
        require(commit.previousCommitSha256 == lastCommitSha256) { "Commit chain does not follow runtime" }
        val nextComponents = components.toMutableMap()
        commit.mutations.forEach { mutation ->
            when (mutation.operation) {
                RuntimeMutationOperation.UPSERT ->
                    nextComponents[mutation.key] = requireNotNull(mutation.canonicalValue)
                RuntimeMutationOperation.REMOVE -> nextComponents.remove(mutation.key)
            }
        }
        val projection = commit.successorProjection
        return copy(
            state = projection.state,
            revision = projection.revision,
            nextCommitSequence = projection.nextCommitSequence,
            nextObservationSequence = projection.nextObservationSequence,
            nextEventSequence = projection.nextEventSequence,
            lastCommitSha256 = commit.commitSha256,
            sourceCheckpoints = projection.sourceCheckpoints,
            clockCheckpoint = projection.clockCheckpoint,
            activeConditionEpoch = projection.activeConditionEpoch,
            components = nextComponents.toSortedMap(),
            lifetimeDataEventCount = projection.lifetimeDataEventCount,
            uploadedThroughCommit = projection.uploadedThroughCommit,
            evaluatedThroughCommit = projection.evaluatedThroughCommit,
            retainedFromCommit = projection.retainedFromCommit,
        )
    }
}

data class StorageUsage(val usedBytes: Long, val quotaBytes: Long) {
    init {
        require(usedBytes >= 0) { "Storage usage must be non-negative" }
        require(quotaBytes > 0) { "Storage quota must be positive" }
    }

    val fraction: Double get() = usedBytes.toDouble() / quotaBytes.toDouble()
}

interface StudyStore {
    suspend fun loadRuntime(): RuntimeDocument?
    suspend fun initialize(runtime: RuntimeDocument)
    suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument)
    suspend fun stagePendingInput(input: PendingEngineInput)
    suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput)
    suspend fun loadPendingInput(): PendingEngineInput?
    suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument)
    suspend fun readCommits(
        fromCommitInclusive: Long,
        throughCommitInclusive: Long,
        consume: (EngineCommit) -> Unit,
    )
    suspend fun storageUsage(): StorageUsage
    suspend fun evictThrough(runtime: RuntimeDocument, targetBytes: Long): RuntimeDocument
    suspend fun clear()
}

enum class StudyStoreRecoveryFailure {
    KEY_UNAVAILABLE,
    SNAPSHOT_INVALID,
    COMMIT_LOG_INVALID,
    PENDING_INPUT_INVALID,
    UNSUPPORTED_LAYOUT,
}

class StudyStoreRecoveryException(
    val failure: StudyStoreRecoveryFailure,
    cause: Throwable? = null,
) : IOException("Study-store recovery failed: ${failure.name}", cause)

data class StudyResetMarker(val retainedEnvelopeBytes: ByteArray?)

interface StudyResetStore {
    suspend fun load(): StudyResetMarker?
    suspend fun mark(retainedEnvelopeBytes: ByteArray?)
    suspend fun clear()
}

fun interface StudyStorageResetter {
    suspend fun clearAll()
}

private const val MAX_COMPONENT_BYTES = 512 * 1_024
const val GENESIS_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
private val ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
private val ASSIGNED_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val COMPONENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,191}")
private val UUID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private val ACTIVITY_KEY = Regex("[A-Za-z0-9_-]{43}")
private val SHA256 = Regex("[0-9a-f]{64}")
