package cool.jacoblin.particeps.core.model

enum class ExperimentState {
    IMPORTED,
    CONFIG_VERIFIED,
    CONSENT_PENDING,
    ACCESS_SETUP,
    READY,
    RUNNING,
    PAUSED,
    COMPLETED,
    WITHDRAWN,
}

enum class TransitionReason(
    val destination: ExperimentState,
) {
    CONFIGURATION_SIGNATURE_VERIFIED(ExperimentState.CONFIG_VERIFIED),
    CONSENT_REVIEW_OPENED(ExperimentState.CONSENT_PENDING),
    CONSENT_ACCEPTED(ExperimentState.ACCESS_SETUP),
    ACCESS_PREFLIGHT_PASSED(ExperimentState.READY),
    PARTICIPANT_STARTED(ExperimentState.RUNNING),
    PARTICIPANT_PAUSED(ExperimentState.PAUSED),
    PARTICIPANT_RESUMED(ExperimentState.RUNNING),
    STUDY_DURATION_ELAPSED(ExperimentState.COMPLETED),
    PARTICIPANT_WITHDREW(ExperimentState.WITHDRAWN),
    REQUIRED_ACCESS_MISSING(ExperimentState.PAUSED),
    COLLECTION_HOST_FAILURE(ExperimentState.PAUSED),
    WORK_SCHEDULING_FAILURE(ExperimentState.PAUSED),
    COLLECTION_TEARDOWN_FAILURE(ExperimentState.PAUSED),
    STORAGE_FAILURE(ExperimentState.PAUSED),
}

/** Closed reasons that force collection admission to remain closed until an explicit resume. */
enum class SafetyPauseReason(
    val transitionReason: TransitionReason,
) {
    REQUIRED_ACCESS_MISSING(TransitionReason.REQUIRED_ACCESS_MISSING),
    COLLECTION_HOST_FAILURE(TransitionReason.COLLECTION_HOST_FAILURE),
    WORK_SCHEDULING_FAILURE(TransitionReason.WORK_SCHEDULING_FAILURE),
    COLLECTION_TEARDOWN_FAILURE(TransitionReason.COLLECTION_TEARDOWN_FAILURE),
    STORAGE_FAILURE(TransitionReason.STORAGE_FAILURE),
}

data class ResearchTime(
    val wallTimeUtcMillis: Long,
    val elapsedRealtimeNanos: Long,
    val bootSessionId: String,
) {
    init {
        require(wallTimeUtcMillis >= 0) { "Wall time must be non-negative" }
        require(elapsedRealtimeNanos >= 0) { "Elapsed time must be non-negative" }
        require(bootSessionId.isNotBlank()) { "Boot session ID must not be blank" }
    }
}

data class ExperimentTransition(
    val from: ExperimentState,
    val to: ExperimentState,
    val reason: TransitionReason,
    val time: ResearchTime,
)

data class EventDraft(
    val collectorId: String,
    val payloadSchemaVersion: Int,
    val observedTime: ResearchTime,
    val payloadType: String,
    val fields: Map<String, String>,
) {
    init {
        require(COLLECTOR_ID.matches(collectorId)) { "Invalid collector ID" }
        require(payloadSchemaVersion > 0) { "Payload schema version must be positive" }
        require(PAYLOAD_TYPE.matches(payloadType)) { "Invalid payload type" }
        require(fields.size <= MAX_FIELDS) { "Payload has too many fields" }
        fields.forEach { (key, value) ->
            require(FIELD_KEY.matches(key)) { "Invalid payload field key" }
            require(value.length <= MAX_FIELD_VALUE_LENGTH) { "Payload field value is too long" }
        }
    }

    companion object {
        private val COLLECTOR_ID = Regex("[a-z][a-z0-9_.-]{2,63}")
        private val PAYLOAD_TYPE = Regex("[A-Z][A-Z0-9_]{1,63}")
        private val FIELD_KEY = Regex("[a-z][a-z0-9_]{0,63}")
        private const val MAX_FIELDS = 32
        // Survey submissions are one bounded, immutable JSON value so they cannot be partially
        // committed. The encrypted event frame remains capped at 64 KiB by storage.
        private const val MAX_FIELD_VALUE_LENGTH = 60 * 1_024
    }
}

data class RecordedEvent(
    val sequenceNumber: Long,
    val collectorId: String,
    val payloadSchemaVersion: Int,
    val observedTime: ResearchTime,
    val payloadType: String,
    val fields: Map<String, String>,
) {
    init {
        require(sequenceNumber > 0) { "Sequence number must be positive" }
    }
}
