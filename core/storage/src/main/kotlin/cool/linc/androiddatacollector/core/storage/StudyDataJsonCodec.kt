package cool.linc.androiddatacollector.core.storage

import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.ExperimentTransition
import cool.linc.androiddatacollector.core.model.InterventionOccurrence
import cool.linc.androiddatacollector.core.model.OccurrenceState
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.TransitionReason
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

    /**
     * Reconciles the persisted metadata against what is actually on disk.
     *
     * [scanFirstSequence] and [scanLastSequence] describe the surviving events, and are both 0 when
     * no segment exists. The stored counter is authoritative for how far the study has counted,
     * because reclaimed events are gone from disk but their sequence numbers must never be reissued.
     * The scan is authoritative for the tail, because an event is fsynced before the metadata naming
     * it and a crash in between leaves the counter one behind the durable truth.
     */
    fun decodeMetadata(
        bytes: ByteArray,
        scanFirstSequence: Long,
        scanLastSequence: Long,
    ): StudyMetadata {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        root.requireExactKeys(METADATA_KEYS)
        val transitionsJson = root.getJSONArray("transitions")
        val storedNextSequence = root.getLong("next_sequence_number")
        val storedRetainedFrom = root.getLong("retained_from_sequence")
        val uploadedThrough = root.getLong("uploaded_through_sequence")

        val nextSequenceNumber: Long
        val retainedFrom: Long
        if (scanFirstSequence == 0L) {
            // Reclaiming never removes the newest segment, so an empty store means no event was
            // ever written. A populated counter here would mean the log was lost, not reclaimed.
            require(storedNextSequence == 1L) {
                "Experiment metadata references events that are not durable"
            }
            nextSequenceNumber = 1L
            retainedFrom = 1L
        } else {
            // The floor is persisted before the segments below it are unlinked, so finding more on
            // disk than the floor claims just means an interrupted reclaim; the next pass finishes
            // it. Finding *less* means a prefix disappeared without being reclaimed, which is
            // indistinguishable from tampering and must not be opened.
            require(scanFirstSequence <= storedRetainedFrom) {
                "Event segments below the retained floor are missing"
            }
            require(storedNextSequence <= scanLastSequence + 1) {
                "Experiment metadata references an event that is not durable"
            }
            nextSequenceNumber = maxOf(storedNextSequence, scanLastSequence + 1)
            retainedFrom = scanFirstSequence
        }
        // Claiming an endpoint received something that was never durable would let it be reclaimed
        // before it was ever sent.
        require(uploadedThrough in 0 until nextSequenceNumber) {
            "Experiment metadata claims an upload beyond the lifetime event count"
        }

        return StudyMetadata(
            experimentId = root.getString("experiment_id"),
            configurationId = root.getString("configuration_id"),
            state = enumValueOf<ExperimentState>(root.getString("state")),
            transitions = List(transitionsJson.length()) { index ->
                decodeTransition(transitionsJson.getJSONObject(index))
            },
            eventCount = nextSequenceNumber - 1,
            nextSequenceNumber = nextSequenceNumber,
            lastEvents = root.getJSONObject("last_events").let { events ->
                events.keys().asSequence().associateWith { collectorId ->
                    decodeEvent(events.getJSONObject(collectorId).toString().toByteArray(Charsets.UTF_8))
                }
            },
            participantInstanceId = root.getString("participant_instance_id"),
            assignedParticipantId = if (root.isNull("assigned_participant_id")) null else root.getString("assigned_participant_id"),
            occurrences = root.getJSONObject("occurrences").let { occurrences ->
                occurrences.keys().asSequence().associateWith { id -> decodeOccurrence(occurrences.getJSONObject(id)) }
            },
            uploadedThroughSequence = uploadedThrough,
            retainedFromSequence = retainedFrom,
        )
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
