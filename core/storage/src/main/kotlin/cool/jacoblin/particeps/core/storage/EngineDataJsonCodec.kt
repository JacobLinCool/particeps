package cool.jacoblin.particeps.core.storage

import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.PendingSourceSubmission
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import org.json.JSONArray
import org.json.JSONObject

/** Exact storage codec for layout 3. No older shape is accepted. */
internal object EngineDataJsonCodec {
    fun encodeRuntime(runtime: RuntimeDocument): ByteArray = encodeRuntimeJson(runtime)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decodeRuntime(bytes: ByteArray): RuntimeDocument = decodeRuntimeJson(
        JSONObject(bytes.toString(Charsets.UTF_8)),
    )

    fun encodeCommit(commit: EngineCommit): ByteArray = encodeCommitJson(commit)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decodeCommit(bytes: ByteArray): EngineCommit = decodeCommitJson(
        JSONObject(bytes.toString(Charsets.UTF_8)),
    )

    fun encodePending(input: PendingEngineInput): ByteArray = encodePendingJson(input)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decodePending(bytes: ByteArray): PendingEngineInput = decodePendingJson(
        JSONObject(bytes.toString(Charsets.UTF_8)),
    )

    private fun encodeRuntimeJson(value: RuntimeDocument) = JSONObject()
        .put("layout_version", value.layoutVersion)
        .put("experiment_id", value.experimentId)
        .put("configuration_id", value.configurationId)
        .put("configuration_sha256", value.configurationSha256)
        .put("participant_instance_id", value.participantInstanceId)
        .put("assigned_participant_id", value.assignedParticipantId ?: JSONObject.NULL)
        .put("state", value.state.name)
        .put("revision", value.revision)
        .put("next_commit_sequence", value.nextCommitSequence)
        .put("next_observation_sequence", value.nextObservationSequence)
        .put("next_event_sequence", value.nextEventSequence)
        .put("last_commit_sha256", value.lastCommitSha256)
        .put("source_checkpoints", encodeSourceCheckpoints(value.sourceCheckpoints))
        .put("clock_checkpoint", value.clockCheckpoint?.let(::encodeClock) ?: JSONObject.NULL)
        .put("active_condition_epoch", value.activeConditionEpoch?.let(::encodeEpoch) ?: JSONObject.NULL)
        .put("components", JSONArray().apply {
            value.components.toSortedMap().forEach { (key, canonicalValue) ->
                put(encodeComponent(key, canonicalValue))
            }
        })
        .put("lifetime_data_event_count", value.lifetimeDataEventCount)
        .put("uploaded_through_commit", value.uploadedThroughCommit)
        .put("evaluated_through_commit", value.evaluatedThroughCommit)
        .put("retained_from_commit", value.retainedFromCommit)
        .put("activity_token_key_base64url", value.activityTokenKeyBase64Url)

    private fun decodeRuntimeJson(value: JSONObject): RuntimeDocument {
        value.requireExactKeys(RUNTIME_KEYS)
        val sourceCheckpoints = decodeSourceCheckpoints(value.getJSONObject("source_checkpoints"))
        val components = value.getJSONArray("components").mapObjects(::decodeComponent)
            .associate { it }
        require(components.size == value.getJSONArray("components").length()) {
            "Duplicate runtime component key"
        }
        return RuntimeDocument(
            layoutVersion = value.getInt("layout_version"),
            experimentId = value.getString("experiment_id"),
            configurationId = value.getString("configuration_id"),
            configurationSha256 = value.getString("configuration_sha256"),
            participantInstanceId = value.getString("participant_instance_id"),
            assignedParticipantId = value.nullableString("assigned_participant_id"),
            state = value.enum("state"),
            revision = value.getLong("revision"),
            nextCommitSequence = value.getLong("next_commit_sequence"),
            nextObservationSequence = value.getLong("next_observation_sequence"),
            nextEventSequence = value.getLong("next_event_sequence"),
            lastCommitSha256 = value.getString("last_commit_sha256"),
            sourceCheckpoints = sourceCheckpoints,
            clockCheckpoint = value.nullableObject("clock_checkpoint")?.let(::decodeClock),
            activeConditionEpoch = value.nullableObject("active_condition_epoch")?.let(::decodeEpoch),
            components = components.toSortedMap(),
            lifetimeDataEventCount = value.getLong("lifetime_data_event_count"),
            uploadedThroughCommit = value.getLong("uploaded_through_commit"),
            evaluatedThroughCommit = value.getLong("evaluated_through_commit"),
            retainedFromCommit = value.getLong("retained_from_commit"),
            activityTokenKeyBase64Url = value.getString("activity_token_key_base64url"),
        )
    }

    private fun encodeCommitJson(value: EngineCommit) = JSONObject()
        .put("commit_sequence", value.commitSequence)
        .put("previous_commit_sha256", value.previousCommitSha256)
        .put("input_kind", value.inputKind.name)
        .put("consumed_pending_input_sha256", value.consumedPendingInputSha256 ?: JSONObject.NULL)
        .put("source_observations", JSONArray().apply {
            value.sourceObservations.forEach { put(encodeObservation(it)) }
        })
        .put("events", JSONArray().apply { value.events.forEach { put(encodeEvent(it)) } })
        .put("mutations", JSONArray().apply { value.mutations.forEach { put(encodeMutation(it)) } })
        .put("committed_at", encodeTime(value.committedAt))
        .put("successor_projection", encodeProjection(value.successorProjection))
        .put("resulting_checkpoint_sha256", value.resultingCheckpointSha256)
        .put("commit_sha256", value.commitSha256)

    private fun decodeCommitJson(value: JSONObject): EngineCommit {
        value.requireExactKeys(COMMIT_KEYS)
        return EngineCommit(
            commitSequence = value.getLong("commit_sequence"),
            previousCommitSha256 = value.getString("previous_commit_sha256"),
            inputKind = value.enum("input_kind"),
            consumedPendingInputSha256 = value.nullableString("consumed_pending_input_sha256"),
            sourceObservations = value.getJSONArray("source_observations").mapObjects(::decodeObservation),
            events = value.getJSONArray("events").mapObjects(::decodeEvent),
            mutations = value.getJSONArray("mutations").mapObjects(::decodeMutation),
            committedAt = decodeTime(value.getJSONObject("committed_at")),
            successorProjection = decodeProjection(value.getJSONObject("successor_projection")),
            resultingCheckpointSha256 = value.getString("resulting_checkpoint_sha256"),
            commitSha256 = value.getString("commit_sha256"),
        )
    }

    private fun encodePendingJson(value: PendingEngineInput) = JSONObject()
        .put("condition_epoch_id", value.conditionEpochId.value)
        .put("submissions", JSONArray().apply { value.submissions.forEach { put(encodePendingSubmission(it)) } })
        .put("staged_at", encodeTime(value.stagedAt))
        .put("encoded_sha256", value.encodedSha256)

    private fun decodePendingJson(value: JSONObject): PendingEngineInput {
        value.requireExactKeys(PENDING_KEYS)
        return PendingEngineInput(
            conditionEpochId = ConditionEpochId(value.getString("condition_epoch_id")),
            submissions = value.getJSONArray("submissions").mapObjects(::decodePendingSubmission),
            stagedAt = decodeTime(value.getJSONObject("staged_at")),
            encodedSha256 = value.getString("encoded_sha256"),
        )
    }

    private fun encodePendingSubmission(value: PendingSourceSubmission) = JSONObject()
        .put("source_id", value.sourceId.value)
        .put("schema_version", value.schemaVersion)
        .put("resource_generation", value.resourceGeneration)
        .put("producer_ordinal", value.producerOrdinal)
        .put("admission_kind", value.admissionKind.name)
        .put("events", JSONArray().apply { value.events.forEach { put(encodeDraft(it)) } })
        .put("coverage", value.coverage?.let(::encodeCoverage) ?: JSONObject.NULL)

    private fun decodePendingSubmission(value: JSONObject): PendingSourceSubmission {
        value.requireExactKeys(PENDING_SUBMISSION_KEYS)
        return PendingSourceSubmission(
            sourceId = EventSourceId(value.getString("source_id")),
            schemaVersion = value.getInt("schema_version"),
            resourceGeneration = value.getLong("resource_generation"),
            producerOrdinal = value.getLong("producer_ordinal"),
            admissionKind = value.enum("admission_kind"),
            events = value.getJSONArray("events").mapObjects(::decodeDraft),
            coverage = value.nullableObject("coverage")?.let(::decodeCoverage),
        )
    }

    private fun encodeProjection(value: RuntimeProjection) = JSONObject()
        .put("state", value.state.name)
        .put("revision", value.revision)
        .put("next_commit_sequence", value.nextCommitSequence)
        .put("next_observation_sequence", value.nextObservationSequence)
        .put("next_event_sequence", value.nextEventSequence)
        .put("source_checkpoints", encodeSourceCheckpoints(value.sourceCheckpoints))
        .put("clock_checkpoint", value.clockCheckpoint?.let(::encodeClock) ?: JSONObject.NULL)
        .put("active_condition_epoch", value.activeConditionEpoch?.let(::encodeEpoch) ?: JSONObject.NULL)
        .put("lifetime_data_event_count", value.lifetimeDataEventCount)
        .put("uploaded_through_commit", value.uploadedThroughCommit)
        .put("evaluated_through_commit", value.evaluatedThroughCommit)
        .put("retained_from_commit", value.retainedFromCommit)

    private fun decodeProjection(value: JSONObject): RuntimeProjection {
        value.requireExactKeys(PROJECTION_KEYS)
        return RuntimeProjection(
            state = value.enum("state"),
            revision = value.getLong("revision"),
            nextCommitSequence = value.getLong("next_commit_sequence"),
            nextObservationSequence = value.getLong("next_observation_sequence"),
            nextEventSequence = value.getLong("next_event_sequence"),
            sourceCheckpoints = decodeSourceCheckpoints(value.getJSONObject("source_checkpoints")),
            clockCheckpoint = value.nullableObject("clock_checkpoint")?.let(::decodeClock),
            activeConditionEpoch = value.nullableObject("active_condition_epoch")?.let(::decodeEpoch),
            lifetimeDataEventCount = value.getLong("lifetime_data_event_count"),
            uploadedThroughCommit = value.getLong("uploaded_through_commit"),
            evaluatedThroughCommit = value.getLong("evaluated_through_commit"),
            retainedFromCommit = value.getLong("retained_from_commit"),
        )
    }

    private fun encodeObservation(value: SourceObservation) = JSONObject()
        .put("observation_sequence", value.observationSequence)
        .put("source_id", value.sourceId.value)
        .put("schema_version", value.schemaVersion)
        .put("resource_generation", value.resourceGeneration)
        .put("admission_kind", value.admissionKind.name)
        .put("producer_ordinal", value.producerOrdinal)
        .put("condition_epoch_id", value.conditionEpochId.value)
        .put("event_count", value.eventCount)
        .put("first_event_sequence", value.firstEventSequence ?: JSONObject.NULL)
        .put("last_event_sequence", value.lastEventSequence ?: JSONObject.NULL)
        .put("coverage", value.coverage?.let(::encodeCoverage) ?: JSONObject.NULL)
        .put("encoded_sha256", value.encodedSha256)

    private fun decodeObservation(value: JSONObject): SourceObservation {
        value.requireExactKeys(OBSERVATION_KEYS)
        return SourceObservation(
            observationSequence = value.getLong("observation_sequence"),
            sourceId = EventSourceId(value.getString("source_id")),
            schemaVersion = value.getInt("schema_version"),
            resourceGeneration = value.getLong("resource_generation"),
            admissionKind = value.enum("admission_kind"),
            producerOrdinal = value.getLong("producer_ordinal"),
            conditionEpochId = ConditionEpochId(value.getString("condition_epoch_id")),
            eventCount = value.getInt("event_count"),
            firstEventSequence = value.nullableLong("first_event_sequence"),
            lastEventSequence = value.nullableLong("last_event_sequence"),
            coverage = value.nullableObject("coverage")?.let(::decodeCoverage),
            encodedSha256 = value.getString("encoded_sha256"),
        )
    }

    private fun encodeEvent(value: RecordedEvent) = JSONObject()
        .put("sequence_number", value.sequenceNumber)
        .put("source_id", value.type.sourceId.value)
        .put("schema_version", value.type.schemaVersion)
        .put("event_type", value.type.eventType)
        .put("observed_time", encodeTime(value.observedTime))
        .put("condition_epoch_id", value.conditionEpochId?.value ?: JSONObject.NULL)
        .put("fields", encodeFields(value.fields))

    private fun decodeEvent(value: JSONObject): RecordedEvent {
        value.requireExactKeys(EVENT_KEYS)
        return RecordedEvent(
            sequenceNumber = value.getLong("sequence_number"),
            type = EventTypeKey(
                EventSourceId(value.getString("source_id")),
                value.getInt("schema_version"),
                value.getString("event_type"),
            ),
            observedTime = decodeTime(value.getJSONObject("observed_time")),
            conditionEpochId = value.nullableString("condition_epoch_id")?.let(::ConditionEpochId),
            fields = decodeFields(value.getJSONObject("fields")),
        )
    }

    private fun encodeDraft(value: EventDraft) = JSONObject()
        .put("source_id", value.type.sourceId.value)
        .put("schema_version", value.type.schemaVersion)
        .put("event_type", value.type.eventType)
        .put("observed_time", encodeTime(value.observedTime))
        .put("fields", encodeFields(value.fields))

    private fun decodeDraft(value: JSONObject): EventDraft {
        value.requireExactKeys(DRAFT_KEYS)
        return EventDraft(
            type = EventTypeKey(
                EventSourceId(value.getString("source_id")),
                value.getInt("schema_version"),
                value.getString("event_type"),
            ),
            observedTime = decodeTime(value.getJSONObject("observed_time")),
            fields = decodeFields(value.getJSONObject("fields")),
        )
    }

    private fun encodeMutation(value: RuntimeMutation) = JSONObject()
        .put("component_kind", value.key.kind.name)
        .put("component_id", value.key.id)
        .put("operation", value.operation.name)
        .put("canonical_value", value.canonicalValue ?: JSONObject.NULL)

    private fun decodeMutation(value: JSONObject): RuntimeMutation {
        value.requireExactKeys(MUTATION_KEYS)
        return RuntimeMutation(
            key = RuntimeComponentKey(
                kind = value.enum("component_kind"),
                id = value.getString("component_id"),
            ),
            operation = value.enum("operation"),
            canonicalValue = value.nullableString("canonical_value"),
        )
    }

    private fun encodeSourceCheckpoints(values: Map<EventSourceId, SourceCheckpoint>) = JSONObject().apply {
        values.toSortedMap().forEach { (sourceId, checkpoint) ->
            put(sourceId.value, encodeSourceCheckpoint(checkpoint))
        }
    }

    private fun decodeSourceCheckpoints(value: JSONObject): Map<EventSourceId, SourceCheckpoint> =
        value.keys().asSequence().sorted().associate { encodedId ->
            val id = EventSourceId(encodedId)
            val checkpoint = decodeSourceCheckpoint(value.getJSONObject(encodedId))
            require(checkpoint.sourceId == id) { "Source checkpoint key mismatch" }
            id to checkpoint
        }

    private fun encodeSourceCheckpoint(value: SourceCheckpoint) = JSONObject()
        .put("source_id", value.sourceId.value)
        .put("resource_generation", value.resourceGeneration)
        .put("next_producer_ordinal", value.nextProducerOrdinal)
        .put("coverage", value.coverage?.let(::encodeCoverage) ?: JSONObject.NULL)
        .put("cursor", value.cursor ?: JSONObject.NULL)

    private fun decodeSourceCheckpoint(value: JSONObject): SourceCheckpoint {
        value.requireExactKeys(SOURCE_CHECKPOINT_KEYS)
        return SourceCheckpoint(
            sourceId = EventSourceId(value.getString("source_id")),
            resourceGeneration = value.getLong("resource_generation"),
            nextProducerOrdinal = value.getLong("next_producer_ordinal"),
            coverage = value.nullableObject("coverage")?.let(::decodeCoverage),
            cursor = value.nullableString("cursor"),
        )
    }

    private fun encodeClock(value: StudyClockCheckpoint) = JSONObject()
        .put("calendar_elapsed_nanos", value.calendarElapsedNanos)
        .put("active_running_elapsed_nanos", value.activeRunningElapsedNanos)
        .put("anchor", encodeTime(value.anchor))
        .put("deadline_utc_millis", value.deadlineUtcMillis)
        .put("deadline_utc_trusted", value.deadlineUtcTrusted)
        .put("zone_id", value.zoneId)

    private fun decodeClock(value: JSONObject): StudyClockCheckpoint {
        value.requireExactKeys(CLOCK_KEYS)
        return StudyClockCheckpoint(
            calendarElapsedNanos = value.getLong("calendar_elapsed_nanos"),
            activeRunningElapsedNanos = value.getLong("active_running_elapsed_nanos"),
            anchor = decodeTime(value.getJSONObject("anchor")),
            deadlineUtcMillis = value.getLong("deadline_utc_millis"),
            deadlineUtcTrusted = value.getBoolean("deadline_utc_trusted"),
            zoneId = value.getString("zone_id"),
        )
    }

    private fun encodeEpoch(value: ConditionEpoch) = JSONObject()
        .put("id", value.id.value)
        .put("configuration_sha256", value.configurationSha256)
        .put("applied_resource_vector_sha256", value.appliedResourceVectorSha256)
        .put("activated_at", encodeTime(value.activatedAt))

    private fun decodeEpoch(value: JSONObject): ConditionEpoch {
        value.requireExactKeys(EPOCH_KEYS)
        return ConditionEpoch(
            id = ConditionEpochId(value.getString("id")),
            configurationSha256 = value.getString("configuration_sha256"),
            appliedResourceVectorSha256 = value.getString("applied_resource_vector_sha256"),
            activatedAt = decodeTime(value.getJSONObject("activated_at")),
        )
    }

    private fun encodeCoverage(value: SourceCoverage) = JSONObject()
        .put("clock_basis", value.clockBasis.name)
        .put("start_inclusive", value.startInclusive)
        .put("end_exclusive", value.endExclusive)

    private fun decodeCoverage(value: JSONObject): SourceCoverage {
        value.requireExactKeys(COVERAGE_KEYS)
        return SourceCoverage(
            clockBasis = value.enum("clock_basis"),
            startInclusive = value.getString("start_inclusive"),
            endExclusive = value.getString("end_exclusive"),
        )
    }

    private fun encodeTime(value: ResearchTime) = JSONObject()
        .put("wall_time_utc_millis", value.wallTimeUtcMillis)
        .put("elapsed_realtime_nanos", value.elapsedRealtimeNanos)
        .put("boot_session_id", value.bootSessionId)

    private fun decodeTime(value: JSONObject): ResearchTime {
        value.requireExactKeys(TIME_KEYS)
        return ResearchTime(
            wallTimeUtcMillis = value.getLong("wall_time_utc_millis"),
            elapsedRealtimeNanos = value.getLong("elapsed_realtime_nanos"),
            bootSessionId = value.getString("boot_session_id"),
        )
    }

    private fun encodeComponent(key: RuntimeComponentKey, value: String) = JSONObject()
        .put("kind", key.kind.name)
        .put("id", key.id)
        .put("canonical_value", value)

    private fun decodeComponent(value: JSONObject): Pair<RuntimeComponentKey, String> {
        value.requireExactKeys(COMPONENT_KEYS)
        return RuntimeComponentKey(
            kind = value.enum("kind"),
            id = value.getString("id"),
        ) to value.getString("canonical_value")
    }

    private fun encodeFields(values: Map<String, String>) = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    private fun decodeFields(value: JSONObject): Map<String, String> =
        value.keys().asSequence().sorted().associateWith(value::getString)

    private fun JSONObject.requireExactKeys(expected: Set<String>) {
        val actual = keys().asSequence().toSet()
        require(actual == expected) { "Unexpected JSON fields: expected=$expected actual=$actual" }
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.nullableObject(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)
    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T = enumValueOf(getString(key))

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }

    private val RUNTIME_KEYS = setOf(
        "layout_version", "experiment_id", "configuration_id", "configuration_sha256",
        "participant_instance_id", "assigned_participant_id", "state", "revision",
        "next_commit_sequence", "next_observation_sequence", "next_event_sequence",
        "last_commit_sha256", "source_checkpoints", "clock_checkpoint", "active_condition_epoch",
        "components", "lifetime_data_event_count", "uploaded_through_commit",
        "evaluated_through_commit", "retained_from_commit", "activity_token_key_base64url",
    )
    private val COMMIT_KEYS = setOf(
        "commit_sequence", "previous_commit_sha256", "input_kind", "consumed_pending_input_sha256", "source_observations",
        "events", "mutations", "committed_at", "successor_projection",
        "resulting_checkpoint_sha256", "commit_sha256",
    )
    private val PENDING_KEYS = setOf(
        "condition_epoch_id", "submissions", "staged_at", "encoded_sha256",
    )
    private val PENDING_SUBMISSION_KEYS = setOf(
        "source_id", "schema_version", "resource_generation", "producer_ordinal",
        "admission_kind", "events", "coverage",
    )
    private val PROJECTION_KEYS = setOf(
        "state", "revision", "next_commit_sequence", "next_observation_sequence",
        "next_event_sequence", "source_checkpoints", "clock_checkpoint",
        "active_condition_epoch", "lifetime_data_event_count", "uploaded_through_commit",
        "evaluated_through_commit", "retained_from_commit",
    )
    private val OBSERVATION_KEYS = setOf(
        "observation_sequence", "source_id", "schema_version", "resource_generation",
        "admission_kind", "producer_ordinal", "condition_epoch_id", "event_count",
        "first_event_sequence", "last_event_sequence", "coverage", "encoded_sha256",
    )
    private val EVENT_KEYS = setOf(
        "sequence_number", "source_id", "schema_version", "event_type", "observed_time",
        "condition_epoch_id", "fields",
    )
    private val DRAFT_KEYS = setOf("source_id", "schema_version", "event_type", "observed_time", "fields")
    private val MUTATION_KEYS = setOf("component_kind", "component_id", "operation", "canonical_value")
    private val SOURCE_CHECKPOINT_KEYS = setOf(
        "source_id", "resource_generation", "next_producer_ordinal", "coverage", "cursor",
    )
    private val CLOCK_KEYS = setOf(
        "calendar_elapsed_nanos", "active_running_elapsed_nanos", "anchor",
        "deadline_utc_millis", "deadline_utc_trusted", "zone_id",
    )
    private val EPOCH_KEYS = setOf(
        "id", "configuration_sha256", "applied_resource_vector_sha256", "activated_at",
    )
    private val COVERAGE_KEYS = setOf("clock_basis", "start_inclusive", "end_exclusive")
    private val TIME_KEYS = setOf("wall_time_utc_millis", "elapsed_realtime_nanos", "boot_session_id")
    private val COMPONENT_KEYS = setOf("kind", "id", "canonical_value")
}
