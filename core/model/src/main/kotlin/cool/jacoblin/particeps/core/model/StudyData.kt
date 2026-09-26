package cool.jacoblin.particeps.core.model

import java.io.IOException
import java.time.ZoneId
import java.util.TreeMap
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
        require(configurationSha256.isLowercaseSha256()) { "Invalid configuration digest" }
        require(appliedResourceVectorSha256.isLowercaseSha256()) { "Invalid resource-vector digest" }
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
        require(id.isRuntimeComponentId()) { "Invalid runtime component ID" }
    }

    override fun compareTo(other: RuntimeComponentKey): Int {
        val byKind = kind.ordinal.compareTo(other.kind.ordinal)
        return if (byKind != 0) byKind else id.compareTo(other.id)
    }
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
        require(canonicalValue == null || canonicalValue.utf8LengthAtMost(MAX_COMPONENT_BYTES)) {
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
        require(previousCommitSha256 == GENESIS_DIGEST || previousCommitSha256.isLowercaseSha256()) {
            "Invalid previous commit digest"
        }
        require(consumedPendingInputSha256 == null || consumedPendingInputSha256.isLowercaseSha256()) {
            "Invalid consumed pending-input digest"
        }
        require(resultingCheckpointSha256.isLowercaseSha256()) { "Invalid checkpoint digest" }
        require(commitSha256.isLowercaseSha256()) { "Invalid commit digest" }
        require(successorProjection.revision == commitSequence) {
            "Successor projection must advance to the committed revision"
        }
        require(successorProjection.nextCommitSequence == commitSequence + 1) {
            "Successor projection has an invalid next commit sequence"
        }
        require(sourceObservations.adjacentPairsAll { left, right ->
            left.observationSequence < right.observationSequence
        }) { "Source observations must be strictly ordered" }
        require(events.adjacentPairsAll { left, right ->
            left.sequenceNumber + 1 == right.sequenceNumber
        }) { "Commit events must be contiguous" }
        require(mutations.mapTo(HashSet(mutations.size * 2)) { it.key }.size == mutations.size) {
            "A commit cannot mutate one runtime component twice"
        }
    }

    /**
     * [EngineCommitIntegrity.calculate] of this value, kept once computed. The preimage excludes
     * [commitSha256], so [withComputedDigest] hands the digest it just calculated to its copy, and
     * the store's verification of that copy compares instead of hashing the commit again. The
     * commit is a value: its lists are never mutated after construction. Not a constructor
     * property, so equality, hashing and `copy` are unchanged, and every other copy starts empty.
     */
    @Volatile
    internal var contentSha256: String? = null
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
        require(encodedSha256.isLowercaseSha256()) { "Invalid pending input digest" }
    }

    /** [EngineCommitIntegrity.calculate] of this value, kept as [EngineCommit.contentSha256] is. */
    @Volatile
    internal var contentSha256: String? = null

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
        require(experimentId.isStudyId() && configurationId.isStudyId()) { "Invalid study ID" }
        require(configurationSha256.isLowercaseSha256()) { "Invalid configuration digest" }
        require(participantInstanceId.isLowercaseUuid()) { "Invalid participant instance ID" }
        assignedParticipantId?.let {
            require(it.isAssignedParticipantId() && it.toByteArray().size <= 64) {
                "Invalid assigned participant ID"
            }
        }
        require(revision >= 0) { "Revision must be non-negative" }
        require(nextCommitSequence == revision + 1) { "Next commit must follow revision" }
        require(nextObservationSequence > 0 && nextEventSequence > 0) { "Invalid next sequence" }
        require(lastCommitSha256 == GENESIS_DIGEST || lastCommitSha256.isLowercaseSha256()) {
            "Invalid last commit digest"
        }
        require(sourceCheckpoints.all { (key, value) -> key == value.sourceId }) {
            "Source checkpoint key mismatch"
        }
        require(components.values.all { it.utf8LengthAtMost(MAX_COMPONENT_BYTES) }) {
            "Runtime component is too large"
        }
        require(lifetimeDataEventCount >= 0) { "Event count must be non-negative" }
        require(uploadedThroughCommit in 0..revision) { "Invalid upload watermark" }
        require(evaluatedThroughCommit in 0..revision) { "Invalid reducer watermark" }
        require(retainedFromCommit in 1..nextCommitSequence) { "Invalid retained commit floor" }
        require(retainedFromCommit <= minOf(uploadedThroughCommit, evaluatedThroughCommit) + 1) {
            "Retained floor exceeds the safe reclaim watermark"
        }
        require(activityTokenKeyBase64Url.isActivityTokenKey()) { "Invalid activity-token key" }
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
        // Copying a sorted component map is linear; only the commit's few mutations are sorted in.
        val nextComponents = TreeMap(components)
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
            components = nextComponents,
            lifetimeDataEventCount = projection.lifetimeDataEventCount,
            uploadedThroughCommit = projection.uploadedThroughCommit,
            evaluatedThroughCommit = projection.evaluatedThroughCommit,
            retainedFromCommit = projection.retainedFromCommit,
        )
    }

    /**
     * Exactly `successor == advance(commit)`, decided without building that document again: a store
     * checks every append this way after the runtime has advanced once. It compares each field with
     * this document's identity or the commit's successor projection, and the components with these
     * components under the commit's mutations, and rejects a commit that does not follow this runtime
     * as [advance] does.
     */
    fun advancesTo(commit: EngineCommit, successor: RuntimeDocument): Boolean {
        require(commit.commitSequence == nextCommitSequence) { "Commit sequence does not follow runtime" }
        require(commit.previousCommitSha256 == lastCommitSha256) { "Commit chain does not follow runtime" }
        val projection = commit.successorProjection
        return successor.layoutVersion == layoutVersion &&
            successor.experimentId == experimentId &&
            successor.configurationId == configurationId &&
            successor.configurationSha256 == configurationSha256 &&
            successor.participantInstanceId == participantInstanceId &&
            successor.assignedParticipantId == assignedParticipantId &&
            successor.state == projection.state &&
            successor.revision == projection.revision &&
            successor.nextCommitSequence == projection.nextCommitSequence &&
            successor.nextObservationSequence == projection.nextObservationSequence &&
            successor.nextEventSequence == projection.nextEventSequence &&
            successor.lastCommitSha256 == commit.commitSha256 &&
            successor.sourceCheckpoints == projection.sourceCheckpoints &&
            successor.clockCheckpoint == projection.clockCheckpoint &&
            successor.activeConditionEpoch == projection.activeConditionEpoch &&
            successor.lifetimeDataEventCount == projection.lifetimeDataEventCount &&
            successor.uploadedThroughCommit == projection.uploadedThroughCommit &&
            successor.evaluatedThroughCommit == projection.evaluatedThroughCommit &&
            successor.retainedFromCommit == projection.retainedFromCommit &&
            successor.activityTokenKeyBase64Url == activityTokenKeyBase64Url &&
            componentsAdvanceTo(commit.mutations, successor.components)
    }

    /**
     * Whether [candidate] equals, as a map, these components with [mutations] applied: every mutated
     * key holds its new value or is gone, every other component is carried unchanged, and nothing
     * else is present. A commit's mutation keys are distinct, so the expected size is exact.
     */
    private fun componentsAdvanceTo(
        mutations: List<RuntimeMutation>,
        candidate: Map<RuntimeComponentKey, String>,
    ): Boolean {
        var expectedSize = components.size
        mutations.forEach { mutation ->
            val present = components.containsKey(mutation.key)
            when (mutation.operation) {
                RuntimeMutationOperation.UPSERT -> {
                    if (!present) expectedSize++
                    if (candidate[mutation.key] != mutation.canonicalValue) return false
                }
                RuntimeMutationOperation.REMOVE -> {
                    if (present) expectedSize--
                    if (candidate.containsKey(mutation.key)) return false
                }
            }
        }
        if (candidate.size != expectedSize) return false
        val mutated = mutations.mapTo(HashSet(mutations.size * 2)) { it.key }
        return components.all { (key, value) -> key in mutated || candidate[key] == value }
    }
}

data class StorageUsage(val usedBytes: Long, val quotaBytes: Long) {
    init {
        require(usedBytes >= 0) { "Storage usage must be non-negative" }
        require(quotaBytes > 0) { "Storage quota must be positive" }
    }

    val fraction: Double get() = usedBytes.toDouble() / quotaBytes.toDouble()
}

/** A retained, acknowledged commit boundary, valid only inside [StudyStore.withReadSnapshot]. */
interface StudyReadSnapshot {
    val runtime: RuntimeDocument

    /** Visits complete commits in order; returning false stops before reading the next commit. */
    suspend fun readCommits(
        fromCommitInclusive: Long,
        throughCommitInclusive: Long,
        consume: (EngineCommit) -> Boolean,
    )
}

interface StudyStore {
    /**
     * Recovers the durable runtime. Recovery authenticates every retained commit, and
     * [observeRetained] sees each of them in commit order during that same pass: a caller learns
     * what the scalar successor does not carry without reading the log a second time. A commit it
     * has seen is not yet recovered truth; only a returned runtime is.
     */
    suspend fun loadRuntime(observeRetained: (EngineCommit) -> Unit = {}): RuntimeDocument?
    /** Captures initialized durable state without recovery; pins the range while allowing appends. */
    suspend fun <T> withReadSnapshot(block: suspend (StudyReadSnapshot) -> T): T
    suspend fun initialize(runtime: RuntimeDocument)
    suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument)
    suspend fun stagePendingInput(input: PendingEngineInput)
    suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput)
    suspend fun loadPendingInput(): PendingEngineInput?
    suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument)
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

/** `zipWithNext().all { (left, right) -> ... }` without allocating the pairs. */
private inline fun <T> List<T>.adjacentPairsAll(predicate: (T, T) -> Boolean): Boolean {
    for (index in 1 until size) {
        if (!predicate(this[index - 1], this[index])) return false
    }
    return true
}

/**
 * Whether `toByteArray().size <= limit`, without encoding. A UTF-16 unit encodes to at most three
 * bytes, so short values need no scan; longer ones are counted exactly as the platform encoder
 * does, including one replacement byte for an unpaired surrogate.
 */
private fun String.utf8LengthAtMost(limit: Int): Boolean {
    if (length.toLong() * 3 <= limit) return true
    var bytes = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        bytes += when {
            character.code < 0x80 -> 1
            character.code < 0x800 -> 2
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            character.isSurrogate() -> 1
            else -> 3
        }
        if (bytes > limit) return false
        index++
    }
    return true
}
const val GENESIS_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
