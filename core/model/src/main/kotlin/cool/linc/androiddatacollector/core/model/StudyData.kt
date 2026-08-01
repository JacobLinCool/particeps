package cool.linc.androiddatacollector.core.model

import java.util.UUID

data class StudyMetadata(
    val experimentId: String,
    val configurationId: String,
    val state: ExperimentState,
    val transitions: List<ExperimentTransition>,
    val eventCount: Long,
    val nextSequenceNumber: Long,
    val lastEvents: Map<String, RecordedEvent>,
    /**
     * Pseudonymous per-install identifier. A study that uploads has no other way to tell one
     * participant's events from another's, because a manual export carries that information
     * out of band. It never leaves the device except inside an encrypted bundle.
     */
    val participantInstanceId: String,
    /** Highest sequence an endpoint has confirmed receiving; 0 when nothing has been delivered. */
    val uploadedThroughSequence: Long,
    /**
     * Lowest sequence still present on the device; 1 when nothing has been reclaimed.
     *
     * [eventCount] stays the lifetime total, so the readable window is
     * `[retainedFromSequence, eventCount]` while sequence numbers keep counting from the study's
     * start. Reclaiming space must never renumber what was already delivered.
     */
    val retainedFromSequence: Long,
) {
    init {
        require(ID.matches(experimentId)) { "Invalid experiment ID" }
        require(ID.matches(configurationId)) { "Invalid configuration ID" }
        require(INSTANCE_ID.matches(participantInstanceId)) { "Invalid participant instance ID" }
        require(eventCount >= 0) { "Event count must be non-negative" }
        require(nextSequenceNumber == eventCount + 1) { "Next sequence must follow the lifetime event count" }
        require(retainedFromSequence in 1..nextSequenceNumber) { "Invalid retained range start" }
        require(uploadedThroughSequence in 0 until nextSequenceNumber) {
            "Upload watermark must not exceed the lifetime event count"
        }
        // Space is only ever reclaimed from events an endpoint already confirmed.
        require(retainedFromSequence <= uploadedThroughSequence + 1) {
            "Retained range starts above the upload watermark"
        }
        // These are persisted alongside the metadata, so they outlive the events they describe and
        // may point below the retained floor once space has been reclaimed.
        require(lastEvents.values.all { it.sequenceNumber in 1 until nextSequenceNumber }) {
            "Latest collector events must precede the next sequence"
        }
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        private val INSTANCE_ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

        fun initial(
            experimentId: String,
            configurationId: String,
            participantInstanceId: String = UUID.randomUUID().toString(),
        ) = StudyMetadata(
            experimentId,
            configurationId,
            ExperimentState.IMPORTED,
            emptyList(),
            0,
            1,
            emptyMap(),
            participantInstanceId,
            0,
            1,
        )
    }
}

/** How much of a study's local budget its stored data currently occupies. */
data class StorageUsage(val usedBytes: Long, val quotaBytes: Long) {
    init {
        require(usedBytes >= 0) { "Storage usage must be non-negative" }
        require(quotaBytes > 0) { "Storage quota must be positive" }
    }

    val fraction: Double get() = usedBytes.toDouble() / quotaBytes.toDouble()
}

/**
 * Durable study data port. Events are appended in sequence and never rewritten; implementations
 * must never retain the full event history in memory.
 *
 * Whole leading segments may be reclaimed once an endpoint has confirmed them — see
 * [evictThrough] — so the readable window starts at [StudyMetadata.retainedFromSequence] rather
 * than always at 1.
 */
interface StudyStore {
    suspend fun loadMetadata(): StudyMetadata?

    suspend fun initialize(metadata: StudyMetadata)

    suspend fun saveMetadata(metadata: StudyMetadata)

    suspend fun appendEvent(event: RecordedEvent)

    /**
     * Streams `[fromSequenceInclusive, upToSequenceInclusive]`. Implementations must deliver the
     * whole requested range or throw; a short read is never returned, because a caller cannot
     * distinguish it from a study that genuinely collected less.
     */
    suspend fun readEvents(
        fromSequenceInclusive: Long,
        upToSequenceInclusive: Long,
        consume: (RecordedEvent) -> Unit,
    )

    suspend fun storageUsage(): StorageUsage

    /**
     * Reclaims space by deleting whole leading segments, and returns the updated metadata.
     *
     * A segment may go only when every event in it is at or below
     * [StudyMetadata.uploadedThroughSequence] and it is not the newest segment. Nothing an
     * endpoint has not confirmed is ever discarded.
     *
     * Stops once usage is at or below [targetBytes], or as soon as nothing further qualifies.
     * Returns [metadata] unchanged when nothing was reclaimed.
     */
    suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long): StudyMetadata

    suspend fun clear()
}
