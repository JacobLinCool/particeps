package cool.jacoblin.particeps.core.storage

import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentTransition
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import org.json.JSONArray
import org.json.JSONObject

internal object StudyDataJsonCodec {
    private val METADATA_KEYS = setOf(
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
    private val TRANSITION_KEYS = setOf("from", "to", "reason", "time")
    private val TIME_KEYS = setOf("wall_time_utc_millis", "elapsed_realtime_nanos", "boot_session_id")
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

    fun encodeMetadata(metadata: StudyMetadata): ByteArray = JSONObject()
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
        // Durable rather than rebuilt by scanning, so opening a study never has to decrypt its
        // whole log. Polling collectors resume their query window from these.
        .put("last_events", JSONObject().apply {
            metadata.lastEvents.toSortedMap().forEach { (collectorId, event) ->
                put(collectorId, JSONObject(encodeEvent(event).toString(Charsets.UTF_8)))
            }
        })
        .toString()
        .toByteArray(Charsets.UTF_8)

    /** Decodes exactly the persisted boundary; append recovery reconciles it with the event log. */
    fun decodeMetadata(bytes: ByteArray): StudyMetadata {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        root.requireExactKeys(METADATA_KEYS)
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

        return StudyMetadata(
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
        )
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
}
