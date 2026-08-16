package cool.jacoblin.particeps.core.storage

import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.ExperimentTransition
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import org.json.JSONArray
import org.json.JSONObject

internal data class DecodedStudyMetadata(
    val metadata: StudyMetadata,
    val migratedFromV1: Boolean,
)

internal object StudyDataJsonCodec {
    private val V1_METADATA_KEYS = setOf(
        "experiment_id",
        "configuration_id",
        "state",
        "next_sequence_number",
        "transitions",
        "participant_instance_id",
        "assigned_participant_id",
        "occurrences",
        "uploaded_through_sequence",
        "retained_from_sequence",
        "last_events",
    )
    private val METADATA_KEYS = V1_METADATA_KEYS + setOf("layout_version", "clock_checkpoint")
    private val TRANSITION_KEYS = setOf("from", "to", "reason", "time")
    private val TIME_KEYS = setOf("wall_time_utc_millis", "elapsed_realtime_nanos", "boot_session_id")
    private val CLOCK_CHECKPOINT_KEYS = setOf(
        "study_elapsed_nanos",
        "active_collection_elapsed_nanos",
        "anchor",
        "deadline_utc_millis",
        "deadline_utc_trusted",
    )
    private val EVENT_KEYS = setOf(
        "sequence_number",
        "collector_id",
        "payload_schema_version",
        "observed_time",
        "payload_type",
        "fields",
    )
    private val OCCURRENCE_KEYS = setOf(
        "occurrence_id",
        "intervention_id",
        "trigger_id",
        "schedule_key",
        "scheduled_for",
        "expires_at_utc_millis",
        "state",
        "opened_at",
        "submitted_at",
        "submission_sequence",
    )

    fun encodeMetadata(metadata: StudyMetadata): ByteArray {
        validateMetadata(metadata, requireV2Clock = true)
        return JSONObject()
        .put("layout_version", METADATA_LAYOUT_VERSION)
        .put("experiment_id", metadata.experimentId)
        .put("configuration_id", metadata.configurationId)
        .put("state", metadata.state.name)
        .put("next_sequence_number", metadata.nextSequenceNumber)
        .put("transitions", JSONArray().apply {
            metadata.transitions.forEach { put(encodeTransition(it)) }
        })
        .put("participant_instance_id", metadata.participantInstanceId)
        .put("assigned_participant_id", metadata.assignedParticipantId ?: JSONObject.NULL)
        .put("occurrences", JSONObject().apply {
            metadata.occurrences.toSortedMap().forEach { (id, occurrence) -> put(id, encodeOccurrence(occurrence)) }
        })
        .put("uploaded_through_sequence", metadata.uploadedThroughSequence)
        .put("retained_from_sequence", metadata.retainedFromSequence)
        .put("clock_checkpoint", metadata.clockCheckpoint?.let(::encodeClockCheckpoint) ?: JSONObject.NULL)
        // Durable rather than rebuilt by scanning, so opening a study never has to decrypt its
        // whole log. Polling collectors resume their query window from these.
        .put("last_events", JSONObject().apply {
            metadata.lastEvents.toSortedMap().forEach { (collectorId, event) ->
                put(collectorId, JSONObject(encodeEvent(event).toString(Charsets.UTF_8)))
            }
        })
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    /** Decodes exactly the persisted boundary; append recovery reconciles it with the event log. */
    fun decodeMetadata(bytes: ByteArray): StudyMetadata = decodeMetadataDocument(bytes).metadata

    /** Accepts only the exact currently deployed v1 layout for its one-time v2 migration. */
    fun decodeMetadataDocument(bytes: ByteArray): DecodedStudyMetadata {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val actualKeys = root.keys().asSequence().toSet()
        val migratedFromV1 = when (actualKeys) {
            V1_METADATA_KEYS -> true
            METADATA_KEYS -> false
            else -> throw IllegalArgumentException(
                "Unexpected JSON fields: expected current v1 or v2 metadata layout; actual=$actualKeys",
            )
        }
        if (!migratedFromV1) {
            require(root.getInt("layout_version") == METADATA_LAYOUT_VERSION) {
                "Unsupported metadata layout version"
            }
        }
        val transitionsJson = root.getJSONArray("transitions")
        val storedNextSequence = root.getLong("next_sequence_number")
        val storedRetainedFrom = root.getLong("retained_from_sequence")
        val uploadedThrough = root.getLong("uploaded_through_sequence")
        require(storedNextSequence > 0) { "Invalid persisted event boundary" }
        // Claiming an endpoint received something that was never durable would let it be reclaimed
        // before it was ever sent.
        require(uploadedThrough in 0 until storedNextSequence) {
            "Experiment metadata claims an upload beyond the lifetime event count"
        }

        val metadata = StudyMetadata(
            experimentId = root.getString("experiment_id"),
            configurationId = root.getString("configuration_id"),
            state = enumValueOf<ExperimentState>(root.getString("state")),
            transitions = List(transitionsJson.length()) { index ->
                decodeTransition(transitionsJson.getJSONObject(index))
            },
            eventCount = storedNextSequence - 1,
            nextSequenceNumber = storedNextSequence,
            lastEvents = root.getJSONObject("last_events").let { events ->
                events.keys().asSequence().associateWith { collectorId ->
                    decodeEvent(events.getJSONObject(collectorId).toString().toByteArray(Charsets.UTF_8)).also {
                        require(it.collectorId == collectorId) { "Latest-event collector key mismatch" }
                    }
                }
            },
            participantInstanceId = root.getString("participant_instance_id"),
            assignedParticipantId = if (root.isNull("assigned_participant_id")) null else root.getString("assigned_participant_id"),
            occurrences = root.getJSONObject("occurrences").let { occurrences ->
                occurrences.keys().asSequence().associateWith { id -> decodeOccurrence(occurrences.getJSONObject(id)) }
            },
            uploadedThroughSequence = uploadedThrough,
            retainedFromSequence = storedRetainedFrom,
            clockCheckpoint = if (migratedFromV1 || root.isNull("clock_checkpoint")) {
                null
            } else {
                decodeClockCheckpoint(root.getJSONObject("clock_checkpoint"))
            },
        )
        validateMetadata(metadata, requireV2Clock = !migratedFromV1)
        return DecodedStudyMetadata(metadata, migratedFromV1)
    }

    /** Validates the recovered metadata boundary and reconciles an interrupted prefix eviction. */
    fun reconcileMetadata(
        metadata: StudyMetadata,
        scanFirstSequence: Long,
        scanLastSequence: Long,
    ): StudyMetadata {
        if (scanFirstSequence == 0L) {
            require(scanLastSequence == 0L && metadata.eventCount == 0L) {
                "Experiment metadata references events that are not durable"
            }
            return metadata
        }
        require(scanFirstSequence in 1..scanLastSequence) { "Invalid durable event range" }
        require(metadata.eventCount == scanLastSequence) { "Metadata does not name the durable event tail" }
        // The floor is persisted before old segments are unlinked. Extra prefix segments therefore
        // mean eviction was interrupted; a missing segment below the stored floor is corruption.
        require(scanFirstSequence <= metadata.retainedFromSequence) {
            "Event segments below the retained floor are missing"
        }
        return metadata.copy(retainedFromSequence = scanFirstSequence)
    }

    fun encodeEvent(event: RecordedEvent): ByteArray = JSONObject()
        .put("sequence_number", event.sequenceNumber)
        .put("collector_id", event.collectorId)
        .put("payload_schema_version", event.payloadSchemaVersion)
        .put("observed_time", encodeTime(event.observedTime))
        .put("payload_type", event.payloadType)
        .put("fields", JSONObject().apply {
            event.fields.toSortedMap().forEach { (key, value) -> put(key, value) }
        })
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decodeEvent(bytes: ByteArray): RecordedEvent {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        json.requireExactKeys(EVENT_KEYS)
        val fieldsJson = json.getJSONObject("fields")
        val fields = fieldsJson.keys().asSequence().sorted().associateWith(fieldsJson::getString)
        return RecordedEvent(
            sequenceNumber = json.getLong("sequence_number"),
            collectorId = json.getString("collector_id"),
            payloadSchemaVersion = json.getInt("payload_schema_version"),
            observedTime = decodeTime(json.getJSONObject("observed_time")),
            payloadType = json.getString("payload_type"),
            fields = fields,
        )
    }

    private fun encodeTransition(transition: ExperimentTransition): JSONObject = JSONObject()
        .put("from", transition.from.name)
        .put("to", transition.to.name)
        .put("reason", transition.reason.name)
        .put("time", encodeTime(transition.time))

    private fun decodeTransition(json: JSONObject): ExperimentTransition {
        json.requireExactKeys(TRANSITION_KEYS)
        return ExperimentTransition(
            from = enumValueOf(json.getString("from")),
            to = enumValueOf(json.getString("to")),
            reason = enumValueOf<TransitionReason>(json.getString("reason")),
            time = decodeTime(json.getJSONObject("time")),
        )
    }

    private fun encodeOccurrence(occurrence: InterventionOccurrence): JSONObject = JSONObject()
        .put("occurrence_id", occurrence.occurrenceId)
        .put("intervention_id", occurrence.interventionId)
        .put("trigger_id", occurrence.triggerId)
        .put("schedule_key", occurrence.scheduleKey)
        .put("scheduled_for", encodeTime(occurrence.scheduledFor))
        .put("expires_at_utc_millis", occurrence.expiresAtUtcMillis)
        .put("state", occurrence.state.name)
        .put("opened_at", occurrence.openedAt?.let(::encodeTime) ?: JSONObject.NULL)
        .put("submitted_at", occurrence.submittedAt?.let(::encodeTime) ?: JSONObject.NULL)
        .put("submission_sequence", occurrence.submissionSequence ?: JSONObject.NULL)

    private fun decodeOccurrence(json: JSONObject): InterventionOccurrence {
        json.requireExactKeys(OCCURRENCE_KEYS)
        return InterventionOccurrence(
            occurrenceId = json.getString("occurrence_id"),
            interventionId = json.getString("intervention_id"),
            triggerId = json.getString("trigger_id"),
            scheduleKey = json.getString("schedule_key"),
            scheduledFor = decodeTime(json.getJSONObject("scheduled_for")),
            expiresAtUtcMillis = json.getLong("expires_at_utc_millis"),
            state = enumValueOf<OccurrenceState>(json.getString("state")),
            openedAt = if (json.isNull("opened_at")) null else decodeTime(json.getJSONObject("opened_at")),
            submittedAt = if (json.isNull("submitted_at")) null else decodeTime(json.getJSONObject("submitted_at")),
            submissionSequence = if (json.isNull("submission_sequence")) null else json.getLong("submission_sequence"),
        )
    }

    private fun encodeTime(time: ResearchTime): JSONObject = JSONObject()
        .put("wall_time_utc_millis", time.wallTimeUtcMillis)
        .put("elapsed_realtime_nanos", time.elapsedRealtimeNanos)
        .put("boot_session_id", time.bootSessionId)

    private fun encodeClockCheckpoint(checkpoint: StudyClockCheckpoint): JSONObject = JSONObject()
        .put("study_elapsed_nanos", checkpoint.studyElapsedNanos)
        .put("active_collection_elapsed_nanos", checkpoint.activeCollectionElapsedNanos)
        .put("anchor", encodeTime(checkpoint.anchor))
        .put("deadline_utc_millis", checkpoint.deadlineUtcMillis)
        .put("deadline_utc_trusted", checkpoint.deadlineUtcTrusted)

    private fun decodeClockCheckpoint(json: JSONObject): StudyClockCheckpoint {
        json.requireExactKeys(CLOCK_CHECKPOINT_KEYS)
        return StudyClockCheckpoint(
            studyElapsedNanos = json.getLong("study_elapsed_nanos"),
            activeCollectionElapsedNanos = json.getLong("active_collection_elapsed_nanos"),
            anchor = decodeTime(json.getJSONObject("anchor")),
            deadlineUtcMillis = json.getLong("deadline_utc_millis"),
            deadlineUtcTrusted = json.getBoolean("deadline_utc_trusted"),
        )
    }

    private fun validateMetadata(metadata: StudyMetadata, requireV2Clock: Boolean) {
        val stateMachine = ExperimentStateMachine()
        var state = ExperimentState.IMPORTED
        var previousTime: ResearchTime? = null
        metadata.transitions.forEach { transition ->
            require(transition.from == state) { "Experiment transition history is discontinuous" }
            require(
                transition.reason.destination == transition.to &&
                    stateMachine.canTransition(transition.from, transition.to)
            ) { "Experiment transition is invalid" }
            require(transition.from in TRANSITION_SOURCES.getValue(transition.reason)) {
                "Experiment transition reason has an invalid source"
            }
            previousTime?.takeIf { it.bootSessionId == transition.time.bootSessionId }?.let { previous ->
                require(transition.time.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos) {
                    "Experiment transition monotonic time moved backwards"
                }
            }
            previousTime = transition.time
            state = transition.to
        }
        require(state == metadata.state) { "Experiment state does not match transition history" }
        val started = metadata.transitions.count { it.reason == TransitionReason.PARTICIPANT_STARTED }
        require(started in 0..1) { "Participant start transition must be unique" }
        if (requireV2Clock) {
            require((metadata.clockCheckpoint != null) == (started == 1)) {
                "Version-2 clock checkpoint does not match participant-start history"
            }
        } else {
            require(metadata.clockCheckpoint == null) { "Version-1 metadata cannot contain a clock checkpoint" }
        }
    }

    private fun decodeTime(json: JSONObject): ResearchTime {
        json.requireExactKeys(TIME_KEYS)
        return ResearchTime(
            wallTimeUtcMillis = json.getLong("wall_time_utc_millis"),
            elapsedRealtimeNanos = json.getLong("elapsed_realtime_nanos"),
            bootSessionId = json.getString("boot_session_id"),
        )
    }

    private fun JSONObject.requireExactKeys(expected: Set<String>) {
        val actual = keys().asSequence().toSet()
        require(actual == expected) { "Unexpected JSON fields: expected=$expected actual=$actual" }
    }

    private const val METADATA_LAYOUT_VERSION = 2

    private val TRANSITION_SOURCES = mapOf(
        TransitionReason.CONFIGURATION_SIGNATURE_VERIFIED to setOf(ExperimentState.IMPORTED),
        TransitionReason.CONSENT_REVIEW_OPENED to setOf(ExperimentState.CONFIG_VERIFIED),
        TransitionReason.CONSENT_ACCEPTED to setOf(ExperimentState.CONSENT_PENDING),
        TransitionReason.ACCESS_PREFLIGHT_PASSED to setOf(ExperimentState.ACCESS_SETUP),
        TransitionReason.PARTICIPANT_STARTED to setOf(ExperimentState.READY),
        TransitionReason.PARTICIPANT_PAUSED to setOf(ExperimentState.RUNNING),
        TransitionReason.PARTICIPANT_RESUMED to setOf(ExperimentState.PAUSED),
        TransitionReason.STUDY_DURATION_ELAPSED to setOf(ExperimentState.RUNNING, ExperimentState.PAUSED),
        TransitionReason.PARTICIPANT_WITHDREW to ExperimentState.entries
            .filterNot { it == ExperimentState.WITHDRAWN }
            .toSet(),
        TransitionReason.REQUIRED_ACCESS_MISSING to setOf(ExperimentState.RUNNING),
        TransitionReason.COLLECTION_HOST_FAILURE to setOf(ExperimentState.RUNNING),
        TransitionReason.WORK_SCHEDULING_FAILURE to setOf(ExperimentState.RUNNING),
        TransitionReason.COLLECTION_TEARDOWN_FAILURE to setOf(ExperimentState.RUNNING),
        TransitionReason.STORAGE_FAILURE to setOf(ExperimentState.RUNNING),
        TransitionReason.DEVICE_REBOOT to setOf(ExperimentState.RUNNING),
        TransitionReason.AUTOMATIC_RECOVERY to setOf(ExperimentState.PAUSED),
    )
}
