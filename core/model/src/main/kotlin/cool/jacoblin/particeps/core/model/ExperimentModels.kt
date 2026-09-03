package cool.jacoblin.particeps.core.model

import java.util.UUID

/** Durable participant lifecycle projection. Ordered lifecycle events are the history. */
enum class ExperimentState {
    IMPORTED,
    CONFIG_VERIFIED,
    CONSENT_PENDING,
    ACCESS_SETUP,
    READY,
    ACTIVATING,
    RUNNING,
    PAUSING,
    PAUSED,
    COMPLETED,
    WITHDRAWN,
}

/** Closed command/result reasons persisted in `study_runtime.v1` events. */
enum class TransitionReason {
    CONFIGURATION_SIGNATURE_VERIFIED,
    CONSENT_REVIEW_OPENED,
    CONSENT_ACCEPTED,
    ACCESS_PREFLIGHT_PASSED,
    PARTICIPANT_STARTED,
    PARTICIPANT_PAUSED,
    PARTICIPANT_RESUMED,
    STUDY_DURATION_ELAPSED,
    PARTICIPANT_WITHDREW,
    REQUIRED_ACCESS_MISSING,
    COLLECTION_HOST_FAILURE,
    WORK_SCHEDULING_FAILURE,
    COLLECTION_TEARDOWN_FAILURE,
    STORAGE_FAILURE,
    DEVICE_REBOOT,
    PROCESS_RECOVERY_UNPROVEN,
    AUTOMATION_ENGINE_FAILURE,
    REQUIRED_RESOURCE_FAILURE,
    TRAFFIC_CONDITION_LOST,
}

enum class SafetyPauseReason(val transitionReason: TransitionReason) {
    REQUIRED_ACCESS_MISSING(TransitionReason.REQUIRED_ACCESS_MISSING),
    COLLECTION_HOST_FAILURE(TransitionReason.COLLECTION_HOST_FAILURE),
    WORK_SCHEDULING_FAILURE(TransitionReason.WORK_SCHEDULING_FAILURE),
    COLLECTION_TEARDOWN_FAILURE(TransitionReason.COLLECTION_TEARDOWN_FAILURE),
    STORAGE_FAILURE(TransitionReason.STORAGE_FAILURE),
    PROCESS_RECOVERY_UNPROVEN(TransitionReason.PROCESS_RECOVERY_UNPROVEN),
    AUTOMATION_ENGINE_FAILURE(TransitionReason.AUTOMATION_ENGINE_FAILURE),
    REQUIRED_RESOURCE_FAILURE(TransitionReason.REQUIRED_RESOURCE_FAILURE),
    TRAFFIC_CONDITION_LOST(TransitionReason.TRAFFIC_CONDITION_LOST),
}

data class ResearchTime(
    val wallTimeUtcMillis: Long,
    val elapsedRealtimeNanos: Long,
    val bootSessionId: String,
) {
    init {
        require(wallTimeUtcMillis >= 0) { "Wall time must be non-negative" }
        require(elapsedRealtimeNanos >= 0) { "Elapsed time must be non-negative" }
        require(BOOT_SESSION_ID.matches(bootSessionId)) { "Invalid boot session ID" }
    }

    companion object {
        private val BOOT_SESSION_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

@JvmInline
value class EventSourceId(val value: String) : Comparable<EventSourceId> {
    init {
        require(SOURCE_ID.matches(value)) { "Invalid event source ID" }
    }

    override fun compareTo(other: EventSourceId): Int = value.compareTo(other.value)
    override fun toString(): String = value

    private companion object {
        val SOURCE_ID = Regex("[a-z][a-z0-9_.-]{2,63}")
    }
}

data class EventTypeKey(
    val sourceId: EventSourceId,
    val schemaVersion: Int,
    val eventType: String,
) : Comparable<EventTypeKey> {
    init {
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(EVENT_TYPE.matches(eventType)) { "Invalid event type" }
    }

    override fun compareTo(other: EventTypeKey): Int =
        compareValuesBy(this, other, { it.sourceId }, { it.schemaVersion }, { it.eventType })

    private companion object {
        val EVENT_TYPE = Regex("[A-Z][A-Z0-9_]{1,63}")
    }
}

@JvmInline
value class ConditionEpochId(val value: String) {
    init {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull()
        require(parsed != null && parsed.version() == 4 && parsed.variant() == 2) {
            "Condition epoch ID must be an RFC 4122 UUIDv4"
        }
    }

    override fun toString(): String = value
}

/** Producer-owned input. The runtime injects sequence and condition-epoch identity. */
data class EventDraft(
    val type: EventTypeKey,
    val observedTime: ResearchTime,
    val fields: Map<String, String>,
) {
    init {
        validateFields(fields)
    }
}

data class RecordedEvent(
    val sequenceNumber: Long,
    val type: EventTypeKey,
    val observedTime: ResearchTime,
    val conditionEpochId: ConditionEpochId?,
    val fields: Map<String, String>,
) {
    init {
        require(sequenceNumber > 0) { "Sequence number must be positive" }
        validateFields(fields)
    }
}

enum class ObservationAdmissionKind {
    NORMAL,
    BARRIER_FLUSH,
}

enum class SourceClockBasis {
    OBSERVED_RESEARCH_TIME,
    SOURCE_WALL_TIME,
    SOURCE_MONOTONIC_TIME,
}

/** A half-open source interval proven by one retrospective observation. */
data class SourceCoverage(
    val clockBasis: SourceClockBasis,
    val startInclusive: String,
    val endExclusive: String,
) {
    init {
        require(startInclusive.isNotBlank()) { "Coverage start must not be blank" }
        require(endExclusive.isNotBlank()) { "Coverage end must not be blank" }
        require(startInclusive.length <= 160 && endExclusive.length <= 160) {
            "Coverage coordinates are too long"
        }
    }
}

/**
 * Public provenance for one source submission. Event batches contain 1..4,096 events; an empty
 * successful retrospective query is represented by `eventCount == 0` plus non-null coverage.
 */
data class SourceObservation(
    val observationSequence: Long,
    val sourceId: EventSourceId,
    val schemaVersion: Int,
    val resourceGeneration: Long,
    val admissionKind: ObservationAdmissionKind,
    val producerOrdinal: Long,
    val conditionEpochId: ConditionEpochId,
    val eventCount: Int,
    val firstEventSequence: Long?,
    val lastEventSequence: Long?,
    val coverage: SourceCoverage?,
    val encodedSha256: String,
) {
    init {
        require(observationSequence > 0) { "Observation sequence must be positive" }
        require(schemaVersion > 0) { "Schema version must be positive" }
        require(resourceGeneration > 0) { "Resource generation must be positive" }
        require(producerOrdinal >= 0) { "Producer ordinal must be non-negative" }
        require(eventCount in 0..MAX_OBSERVATION_EVENTS) { "Observation event count is out of range" }
        require(SHA256.matches(encodedSha256)) { "Invalid observation digest" }
        if (eventCount == 0) {
            require(firstEventSequence == null && lastEventSequence == null && coverage != null) {
                "Empty observations require coverage and no event range"
            }
        } else {
            val first = requireNotNull(firstEventSequence) { "Missing first event sequence" }
            val last = requireNotNull(lastEventSequence) { "Missing last event sequence" }
            require(first > 0 && last >= first && last - first + 1 == eventCount.toLong()) {
                "Observation event range is not contiguous"
            }
        }
    }
}

private val FIELD_KEY = Regex("[a-z][a-z0-9_]{0,63}")
private val SHA256 = Regex("[0-9a-f]{64}")
private const val MAX_FIELDS = 32
private const val MAX_FIELD_VALUE_LENGTH = 60 * 1_024
const val MAX_OBSERVATION_EVENTS = 4_096
const val MAX_OBSERVATION_ENCODED_BYTES = 8 * 1_024 * 1_024

private fun validateFields(fields: Map<String, String>) {
    require(fields.size <= MAX_FIELDS) { "Event has too many fields" }
    fields.forEach { (key, value) ->
        require(FIELD_KEY.matches(key)) { "Invalid event field key" }
        require(value.length <= MAX_FIELD_VALUE_LENGTH) { "Event field value is too long" }
    }
}
