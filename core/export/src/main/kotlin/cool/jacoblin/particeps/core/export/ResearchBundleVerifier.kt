package cool.jacoblin.particeps.core.export

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import cool.jacoblin.particeps.core.automation.AutomationCheckpointCodec
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.RegistrySourceKind
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineCommitIntegrity
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.GENESIS_DIGEST
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.protocol.ConfigurationVerificationPurpose
import cool.jacoblin.particeps.core.protocol.ConfigurationVerifier
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import cool.jacoblin.particeps.core.protocol.SignedConfigurationEnvelope
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Typed result published only after the complete authenticated document passes Protocol v1. */
data class VerifiedResearchBundle(
    val header: AuthenticatedBundleHeader,
    val kind: BundleKind,
    val configuration: VerifiedConfiguration,
    val registrySha256: String,
    val producer: BundleProducer,
    val exportedAtUtcMillis: Long,
    val experiment: VerifiedExperimentSnapshot,
)

data class VerifiedExperimentSnapshot(
    val experimentId: String,
    val configurationId: String,
    val participantInstanceId: String,
    val assignedParticipantId: String?,
    val state: ExperimentState,
    val nextCommitSequence: Long,
    val retainedFromCommit: Long,
    val durableThroughCommit: Long,
    val uploadedThroughCommit: Long,
    val evaluatedThroughCommit: Long,
    val firstCommitSequence: Long,
    val lastCommitSequence: Long,
    val commitCount: Long,
    val eventCount: Long,
    val lifetimeDataEventCount: Long,
    val firstPreviousCommitSha256: String?,
    val lastCommitSha256: String?,
)

/**
 * Sole closed-world streaming reader for authenticated `particeps-research-bundle-v1` plaintext.
 * It validates each bounded commit before releasing the aggregate result and never builds a
 * document-sized DOM.
 */
object ResearchBundleVerifier {
    fun verify(
        plaintext: InputStream,
        header: AuthenticatedBundleHeader,
        expectedConfiguration: StudyConfiguration,
    ): VerifiedResearchBundle {
        val source = DigestingCountingInputStream(plaintext)
        val canonical = DigestingCountingOutputStream()
        val reader = JsonReader(
            InputStreamReader(
                source,
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT),
            ),
        ).apply { strictness = Strictness.STRICT }
        val writer = CanonicalJsonWriter(canonical)
        val parsed = Parser(reader, writer, header, expectedConfiguration).parse()
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing bundle JSON content" }
        writer.close()
        require(source.count == canonical.count && source.digest().contentEquals(canonical.digest())) {
            "Bundle JSON is not canonical"
        }
        return parsed
    }

    fun verify(
        plaintext: ByteArray,
        header: AuthenticatedBundleHeader,
        expectedConfiguration: StudyConfiguration,
    ): VerifiedResearchBundle = verify(ByteArrayInputStream(plaintext), header, expectedConfiguration)

    private class Parser(
        private val reader: JsonReader,
        private val writer: CanonicalJsonWriter,
        private val header: AuthenticatedBundleHeader,
        private val expectedConfiguration: StudyConfiguration,
    ) {
        private val expectedConfigurationBytes = StudyConfigurationCodec.encode(expectedConfiguration)
        private val configuredCollectors = expectedConfiguration.collectors.mapTo(hashSetOf()) { it.id }
        private val shapingEnabled = expectedConfiguration.trafficShaping is TrafficShapingConfiguration.Enabled

        fun parse(): VerifiedResearchBundle {
            require(expectedConfigurationBytes.sha256Hex() == header.configurationSha256) {
                "Outer configuration digest mismatch"
            }
            beginObject()
            member("bundle_id")
            val bundleId = canonicalUuid(string("bundle_id"), "bundle ID")
            require(bundleId == header.bundleId) { "Inner bundle ID mismatch" }
            member("bundle_kind")
            val kind = when (string("bundle_kind")) {
                BundleKind.MANUAL_EXPORT.wireValue -> BundleKind.MANUAL_EXPORT
                BundleKind.AUTOMATIC_UPLOAD.wireValue -> BundleKind.AUTOMATIC_UPLOAD
                else -> throw IllegalArgumentException("Unknown bundle kind")
            }
            member("configuration")
            compareExpectedConfiguration()
            member("configuration_sha256")
            require(string("configuration_sha256") == header.configurationSha256) {
                "Inner configuration digest mismatch"
            }
            member("configuration_signature")
            val provenance = configurationSignature()
            member("event_source_registry_sha256")
            val registrySha256 = string("event_source_registry_sha256")
            require(registrySha256 == ProtocolEventSourceRegistry.REGISTRY_SHA256) {
                "Event-source registry digest mismatch"
            }
            member("experiment")
            val experiment = experiment(kind)
            member("exported_at_utc_millis")
            val exportedAt = decimalLong("exported_at_utc_millis")
            member("format")
            require(string("format") == ResearchExport.BUNDLE_FORMAT) { "Unknown bundle format" }
            member("producer")
            val producer = producer()
            endObject()

            val verified = ConfigurationVerifier(
                trustedSigningKeys = emptyMap(),
                clientVersion = producer.clientVersion.toLong(),
                now = { expectedConfiguration.issuedAt },
            ).verify(
                SignedConfigurationCodec.encode(
                    SignedConfigurationEnvelope(
                        signerKeyId = provenance.signerKeyId,
                        configurationBytes = expectedConfigurationBytes,
                        signature = provenance.signature,
                    ),
                ),
                ConfigurationVerificationPurpose.ACCEPTED_ACTIVE_STUDY_RECOVERY,
            )
            require(verified.configuration == expectedConfiguration) { "Embedded configuration mismatch" }
            require(producer.platform == expectedConfiguration.platform) { "Producer platform mismatch" }
            return VerifiedResearchBundle(
                header,
                kind,
                verified,
                registrySha256,
                producer,
                exportedAt,
                experiment,
            )
        }

        private fun experiment(kind: BundleKind): VerifiedExperimentSnapshot {
            beginObject()
            member("assigned_participant_id")
            val assignedParticipantId = nullableString("assigned_participant_id")
            require(assignedParticipantId == expectedConfiguration.assignedParticipantId) {
                "Assigned participant ID mismatch"
            }
            member("commit_count")
            val declaredCommitCount = decimalLong("commit_count")
            member("commits")
            val commits = commits(declaredCommitCount)
            member("configuration_id")
            val configurationId = string("configuration_id")
            require(configurationId == expectedConfiguration.configurationId) { "Configuration ID mismatch" }
            member("durable_through_commit")
            val durableThrough = decimalLong("durable_through_commit")
            member("evaluated_through_commit")
            val evaluatedThrough = decimalLong("evaluated_through_commit")
            member("event_count")
            val declaredEventCount = decimalLong("event_count")
            member("experiment_id")
            val experimentId = string("experiment_id")
            require(experimentId == expectedConfiguration.experimentId) { "Experiment ID mismatch" }
            member("first_commit_sequence")
            val firstCommit = decimalLong("first_commit_sequence")
            require(firstCommit > 0) { "First commit sequence must be positive" }
            member("last_commit_sequence")
            val lastCommit = decimalLong("last_commit_sequence")
            member("lifetime_data_event_count")
            val lifetimeDataEventCount = decimalLong("lifetime_data_event_count")
            member("next_commit_sequence")
            val nextCommit = decimalLong("next_commit_sequence")
            member("participant_instance_id")
            val participantInstanceId = string("participant_instance_id")
            require(PARTICIPANT_INSTANCE_ID.matches(participantInstanceId)) {
                "Invalid participant instance ID"
            }
            member("retained_from_commit")
            val retainedFrom = decimalLong("retained_from_commit")
            member("state")
            val state = enumValue<ExperimentState>(string("state"), "experiment state")
            member("uploaded_through_commit")
            val uploadedThrough = decimalLong("uploaded_through_commit")
            endObject()

            require(nextCommit > 0 && durableThrough == nextCommit - 1) { "Durable commit boundary mismatch" }
            require(evaluatedThrough in 0..durableThrough) { "Reducer watermark is invalid" }
            require(uploadedThrough in 0..durableThrough) { "Upload watermark is invalid" }
            require(retainedFrom in 1..nextCommit) { "Retained commit floor is invalid" }
            require(retainedFrom <= minOf(uploadedThrough, evaluatedThrough) + 1) {
                "Retained range exceeds the safe reclaim watermark"
            }
            require(firstCommit in retainedFrom..nextCommit) { "Bundle starts outside retained commits" }
            require(lastCommit <= durableThrough) { "Bundle range exceeds durable commits" }
            val expectedLast = if (declaredCommitCount == 0L) {
                firstCommit - 1
            } else {
                require(firstCommit <= Long.MAX_VALUE - declaredCommitCount + 1) { "Bundle range overflows" }
                firstCommit + declaredCommitCount - 1
            }
            require(lastCommit == expectedLast) { "Bundle commit range/count mismatch" }
            require(commits.count == declaredCommitCount) { "Bundle commit count mismatch" }
            require(commits.eventCount == declaredEventCount) { "Bundle event count mismatch" }
            if (declaredCommitCount > 0) {
                require(commits.firstSequence == firstCommit && commits.lastSequence == lastCommit) {
                    "Bundle commits do not cover the declared range"
                }
            }
            commits.lastProjection?.let { projection ->
                if (lastCommit == durableThrough) {
                    require(projection.state == state) { "Runtime state diverges from the final commit" }
                    require(projection.nextCommitSequence == nextCommit) {
                        "Runtime next commit diverges from the final commit"
                    }
                    require(projection.lifetimeDataEventCount == lifetimeDataEventCount) {
                        "Lifetime data count diverges from the final commit"
                    }
                    require(projection.uploadedThroughCommit == uploadedThrough) {
                        "Upload watermark diverges from the final commit"
                    }
                    require(projection.evaluatedThroughCommit == evaluatedThrough) {
                        "Reducer watermark diverges from the final commit"
                    }
                    require(projection.retainedFromCommit == retainedFrom) {
                        "Retained floor diverges from the final commit"
                    }
                }
            }
            require(lifetimeDataEventCount >= commits.collectorEventCount) {
                "Lifetime data count is smaller than exported collector data"
            }
            if (kind == BundleKind.AUTOMATIC_UPLOAD) {
                require(declaredCommitCount > 0) { "Automatic upload cannot be empty" }
                require(firstCommit == uploadedThrough + 1) {
                    "Automatic upload does not start after its durable watermark"
                }
            }
            return VerifiedExperimentSnapshot(
                experimentId = experimentId,
                configurationId = configurationId,
                participantInstanceId = participantInstanceId,
                assignedParticipantId = assignedParticipantId,
                state = state,
                nextCommitSequence = nextCommit,
                retainedFromCommit = retainedFrom,
                durableThroughCommit = durableThrough,
                uploadedThroughCommit = uploadedThrough,
                evaluatedThroughCommit = evaluatedThrough,
                firstCommitSequence = firstCommit,
                lastCommitSequence = lastCommit,
                commitCount = declaredCommitCount,
                eventCount = declaredEventCount,
                lifetimeDataEventCount = lifetimeDataEventCount,
                firstPreviousCommitSha256 = commits.firstPreviousSha256,
                lastCommitSha256 = commits.lastSha256,
            )
        }

        private fun commits(declaredCount: Long): CommitSummary {
            beginArray()
            var count = 0L
            var eventCount = 0L
            var collectorEventCount = 0L
            var firstSequence: Long? = null
            var lastSequence: Long? = null
            var firstPreviousSha256: String? = null
            var lastSha256: String? = null
            var priorProjection: RuntimeProjection? = null
            while (reader.hasNext()) {
                require(count < declaredCount) { "Bundle contains more commits than declared" }
                val commit = commit()
                if (firstSequence == null) {
                    firstSequence = commit.commitSequence
                    firstPreviousSha256 = commit.previousCommitSha256
                    if (commit.commitSequence == 1L) {
                        require(commit.previousCommitSha256 == GENESIS_DIGEST) { "Genesis commit has a predecessor" }
                    }
                } else {
                    require(commit.commitSequence == requireNotNull(lastSequence) + 1) {
                        "Non-contiguous commit sequence"
                    }
                    require(commit.previousCommitSha256 == lastSha256) { "Broken commit digest chain" }
                    verifyProjectionContinuity(requireNotNull(priorProjection), commit)
                }
                val semantics = verifyCommitSemantics(commit, priorProjection)
                count++
                eventCount = Math.addExact(eventCount, commit.events.size.toLong())
                collectorEventCount = Math.addExact(collectorEventCount, semantics.collectorEventCount)
                lastSequence = commit.commitSequence
                lastSha256 = commit.commitSha256
                priorProjection = commit.successorProjection
            }
            endArray()
            return CommitSummary(
                count,
                eventCount,
                collectorEventCount,
                firstSequence,
                lastSequence,
                firstPreviousSha256,
                lastSha256,
                priorProjection,
            )
        }

        private fun commit(): EngineCommit {
            beginObject()
            member("commit_sequence")
            val commitSequence = decimalLong("commit_sequence")
            member("commit_sha256")
            val commitSha256 = digest("commit_sha256")
            member("committed_at")
            val committedAt = time()
            member("consumed_pending_input_sha256")
            val consumedPending = nullableDigest("consumed_pending_input_sha256")
            member("events")
            val events = events()
            member("input_kind")
            val inputKind = enumValue<EngineInputKind>(string("input_kind"), "engine input kind")
            member("mutations")
            val mutations = mutations()
            member("previous_commit_sha256")
            val previousSha256 = digest("previous_commit_sha256")
            member("resulting_checkpoint_sha256")
            val resultingCheckpoint = digest("resulting_checkpoint_sha256")
            member("source_observations")
            val observations = observations()
            member("successor_projection")
            val projection = projection()
            endObject()
            val commit = EngineCommit(
                commitSequence = commitSequence,
                previousCommitSha256 = previousSha256,
                inputKind = inputKind,
                consumedPendingInputSha256 = consumedPending,
                sourceObservations = observations,
                events = events,
                mutations = mutations,
                committedAt = committedAt,
                successorProjection = projection,
                resultingCheckpointSha256 = resultingCheckpoint,
                commitSha256 = commitSha256,
            )
            EngineCommitIntegrity.verify(commit)
            require(commit.successorProjection.evaluatedThroughCommit == commit.commitSequence) {
                "Commit was not durably reducer-evaluated"
            }
            require(commit.mutations.zipWithNext().all { (left, right) -> left.key < right.key }) {
                "Runtime mutations are not canonically ordered"
            }
            verifyAutomationCheckpoint(commit)
            return commit
        }

        private fun verifyAutomationCheckpoint(commit: EngineCommit) {
            val mutations = commit.mutations.filter { it.key.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT }
            require(mutations.isNotEmpty()) { "Commit has no automation checkpoint mutation" }
            require(mutations.all { CHECKPOINT_COMPONENT_ID.matches(it.key.id) }) {
                "Unknown automation checkpoint component ID"
            }
            val parts = mutations.filter { it.operation == RuntimeMutationOperation.UPSERT }
            require(parts.isNotEmpty()) { "Commit does not carry its successor automation checkpoint" }
            parts.forEachIndexed { index, mutation ->
                val expectedId = if (index == 0) "main" else "main/${index.toString().padStart(4, '0')}"
                require(mutation.key.id == expectedId) { "Automation checkpoint parts are not contiguous" }
            }
            val encoded = parts.joinToString(separator = "") { requireNotNull(it.canonicalValue) }
            val checkpoint = AutomationCheckpointCodec.decode(encoded)
            require(checkpoint.evaluatedThroughSequence == commit.commitSequence) {
                "Automation checkpoint reducer cursor diverges from commit"
            }
            require(checkpoint.digest() == commit.resultingCheckpointSha256) {
                "Automation checkpoint digest diverges from commit"
            }
        }

        private fun events(): List<RecordedEvent> {
            beginArray()
            val events = ArrayList<RecordedEvent>()
            while (reader.hasNext()) {
                require(events.size < MAX_EVENTS_PER_COMMIT) { "Commit contains too many events" }
                events += event()
            }
            endArray()
            return events
        }

        private fun event(): RecordedEvent {
            beginObject()
            member("condition_epoch_id")
            val epochId = nullableString("condition_epoch_id")?.let(::ConditionEpochId)
            member("event_type")
            val eventType = string("event_type")
            member("fields")
            val fields = fields()
            member("observed_time")
            val observedTime = time()
            member("schema_version")
            val schemaVersion = integer("schema_version")
            member("sequence_number")
            val sequenceNumber = decimalLong("sequence_number")
            member("source_id")
            val sourceId = EventSourceId(string("source_id"))
            endObject()

            val event = RecordedEvent(
                sequenceNumber,
                EventTypeKey(sourceId, schemaVersion, eventType),
                observedTime,
                epochId,
                fields,
            )
            val sourceContract = requireNotNull(ProtocolEventSourceRegistry[sourceId.value]) {
                "Unknown event source: $sourceId"
            }
            require(sourceContract.schemaVersion == schemaVersion) { "Event schema does not match registry" }
            val eventContract = requireNotNull(sourceContract.events[eventType]) { "Unknown event type" }
            require(eventContract.exported) { "Non-exportable event appears in research bundle" }
            require(sourceContract.accepts(EventDraft(event.type, observedTime, fields), sequenceNumber, epochId)) {
                "Event violates its generated registry contract"
            }
            when (sourceContract.sourceKind) {
                RegistrySourceKind.COLLECTOR -> {
                    require(sourceId.value in configuredCollectors) { "Collector was not signed into the study" }
                    require(epochId != null) { "Collector data event has no condition epoch" }
                }
                RegistrySourceKind.SYSTEM -> if (sourceId.value == TRAFFIC_SOURCE_ID) {
                    require(shapingEnabled) { "Traffic-shaping event appears in an unshaped study" }
                }
            }
            return event
        }

        private fun observations(): List<SourceObservation> {
            beginArray()
            val observations = ArrayList<SourceObservation>()
            while (reader.hasNext()) {
                require(observations.size < MAX_OBSERVATIONS_PER_COMMIT) {
                    "Commit contains too many source observations"
                }
                observations += observation()
            }
            endArray()
            return observations
        }

        private fun observation(): SourceObservation {
            beginObject()
            member("admission_kind")
            val admission = enumValue<ObservationAdmissionKind>(string("admission_kind"), "admission kind")
            member("condition_epoch_id")
            val conditionEpochId = ConditionEpochId(string("condition_epoch_id"))
            member("coverage")
            val coverage = nullableObject(::coverage)
            member("encoded_sha256")
            val encodedSha256 = digest("encoded_sha256")
            member("event_count")
            val eventCount = integer("event_count")
            member("first_event_sequence")
            val firstEvent = nullableDecimalLong("first_event_sequence")
            member("last_event_sequence")
            val lastEvent = nullableDecimalLong("last_event_sequence")
            member("observation_sequence")
            val observationSequence = decimalLong("observation_sequence")
            member("producer_ordinal")
            val producerOrdinal = decimalLong("producer_ordinal")
            member("resource_generation")
            val resourceGeneration = decimalLong("resource_generation")
            member("schema_version")
            val schemaVersion = integer("schema_version")
            member("source_id")
            val sourceId = EventSourceId(string("source_id"))
            endObject()
            val contract = requireNotNull(ProtocolEventSourceRegistry[sourceId.value]) {
                "Unknown observation source: $sourceId"
            }
            require(contract.sourceKind == RegistrySourceKind.COLLECTOR) {
                "Only collector plugins may produce source observations"
            }
            require(sourceId.value in configuredCollectors) { "Observation source was not signed into the study" }
            require(contract.schemaVersion == schemaVersion) { "Observation schema does not match registry" }
            require(!contract.isRetrospective || coverage != null) {
                "Observation coverage does not match source delivery semantics"
            }
            return SourceObservation(
                observationSequence,
                sourceId,
                schemaVersion,
                resourceGeneration,
                admission,
                producerOrdinal,
                conditionEpochId,
                eventCount,
                firstEvent,
                lastEvent,
                coverage,
                encodedSha256,
            )
        }

        private fun mutations(): List<RuntimeMutation> {
            beginArray()
            val mutations = ArrayList<RuntimeMutation>()
            while (reader.hasNext()) {
                require(mutations.size < MAX_MUTATIONS_PER_COMMIT) { "Commit contains too many mutations" }
                mutations += mutation()
            }
            endArray()
            return mutations
        }

        private fun mutation(): RuntimeMutation {
            beginObject()
            member("canonical_value")
            val canonicalValue = nullableString("canonical_value")
            member("component_id")
            val componentId = string("component_id")
            member("component_kind")
            val componentKind = enumValue<RuntimeComponentKind>(string("component_kind"), "component kind")
            member("operation")
            val operation = enumValue<RuntimeMutationOperation>(string("operation"), "mutation operation")
            endObject()
            return RuntimeMutation(RuntimeComponentKey(componentKind, componentId), operation, canonicalValue)
        }

        private fun projection(): RuntimeProjection {
            beginObject()
            member("active_condition_epoch")
            val activeEpoch = nullableObject(::epoch)
            activeEpoch?.let {
                require(it.configurationSha256 == header.configurationSha256) {
                    "Condition epoch is bound to another configuration"
                }
            }
            member("clock_checkpoint")
            val clock = nullableObject(::clock)
            member("evaluated_through_commit")
            val evaluatedThrough = decimalLong("evaluated_through_commit")
            member("lifetime_data_event_count")
            val lifetimeCount = decimalLong("lifetime_data_event_count")
            member("next_commit_sequence")
            val nextCommit = decimalLong("next_commit_sequence")
            member("next_event_sequence")
            val nextEvent = decimalLong("next_event_sequence")
            member("next_observation_sequence")
            val nextObservation = decimalLong("next_observation_sequence")
            member("retained_from_commit")
            val retainedFrom = decimalLong("retained_from_commit")
            member("revision")
            val revision = decimalLong("revision")
            member("source_checkpoints")
            val checkpoints = sourceCheckpoints()
            member("state")
            val state = enumValue<ExperimentState>(string("state"), "experiment state")
            member("uploaded_through_commit")
            val uploadedThrough = decimalLong("uploaded_through_commit")
            endObject()
            return RuntimeProjection(
                state,
                revision,
                nextCommit,
                nextObservation,
                nextEvent,
                checkpoints,
                clock,
                activeEpoch,
                lifetimeCount,
                uploadedThrough,
                evaluatedThrough,
                retainedFrom,
            )
        }

        private fun sourceCheckpoints(): Map<EventSourceId, SourceCheckpoint> {
            beginObject()
            val checkpoints = sortedMapOf<EventSourceId, SourceCheckpoint>()
            var previousName: String? = null
            while (reader.hasNext()) {
                require(checkpoints.size < MAX_SOURCE_CHECKPOINTS) { "Too many source checkpoints" }
                val name = reader.nextName()
                require(previousName == null || requireNotNull(previousName) < name) {
                    "Source checkpoints are not canonically ordered"
                }
                writer.name(name)
                val id = EventSourceId(name)
                val checkpoint = sourceCheckpoint()
                require(checkpoint.sourceId == id) { "Source checkpoint key mismatch" }
                require(checkpoints.put(id, checkpoint) == null) { "Duplicate source checkpoint" }
                previousName = name
            }
            endObject()
            return checkpoints
        }

        private fun sourceCheckpoint(): SourceCheckpoint {
            beginObject()
            member("coverage")
            val coverage = nullableObject(::coverage)
            member("cursor")
            val cursor = nullableString("cursor")
            member("next_producer_ordinal")
            val nextOrdinal = decimalLong("next_producer_ordinal")
            member("resource_generation")
            val generation = decimalLong("resource_generation")
            member("source_id")
            val sourceId = EventSourceId(string("source_id"))
            endObject()
            require(sourceId.value in configuredCollectors) { "Checkpoint source was not signed into the study" }
            return SourceCheckpoint(sourceId, generation, nextOrdinal, coverage, cursor)
        }

        private fun clock(): StudyClockCheckpoint {
            beginObject()
            member("active_running_elapsed_nanos")
            val active = decimalLong("active_running_elapsed_nanos")
            member("anchor")
            val anchor = time()
            member("calendar_elapsed_nanos")
            val calendar = decimalLong("calendar_elapsed_nanos")
            member("deadline_utc_millis")
            val deadline = decimalLong("deadline_utc_millis")
            member("deadline_utc_trusted")
            val trusted = boolean("deadline_utc_trusted")
            member("zone_id")
            val zoneId = string("zone_id")
            endObject()
            return StudyClockCheckpoint(calendar, active, anchor, deadline, trusted, zoneId)
        }

        private fun epoch(): ConditionEpoch {
            beginObject()
            member("activated_at")
            val activatedAt = time()
            member("applied_resource_vector_sha256")
            val appliedDigest = digest("applied_resource_vector_sha256")
            member("configuration_sha256")
            val configurationDigest = digest("configuration_sha256")
            member("id")
            val id = ConditionEpochId(string("id"))
            endObject()
            return ConditionEpoch(id, configurationDigest, appliedDigest, activatedAt)
        }

        private fun coverage(): SourceCoverage {
            beginObject()
            member("clock_basis")
            val basis = enumValue<SourceClockBasis>(string("clock_basis"), "coverage clock basis")
            member("end_exclusive")
            val end = string("end_exclusive")
            member("start_inclusive")
            val start = string("start_inclusive")
            endObject()
            return SourceCoverage(basis, start, end)
        }

        private fun time(): ResearchTime {
            beginObject()
            member("boot_session_id")
            val bootSessionId = string("boot_session_id")
            member("elapsed_realtime_nanos")
            val elapsed = decimalLong("elapsed_realtime_nanos")
            member("wall_time_utc_millis")
            val wall = decimalLong("wall_time_utc_millis")
            endObject()
            return ResearchTime(wall, elapsed, bootSessionId)
        }

        private fun verifyProjectionContinuity(previous: RuntimeProjection, commit: EngineCommit) {
            require(previous.nextCommitSequence == commit.commitSequence) {
                "Commit does not follow the preceding projection"
            }
            val firstEvent = commit.events.firstOrNull()?.sequenceNumber
            require(firstEvent == null || firstEvent == previous.nextEventSequence) {
                "Event range does not follow the preceding projection"
            }
            val firstObservation = commit.sourceObservations.firstOrNull()?.observationSequence
            require(firstObservation == null || firstObservation == previous.nextObservationSequence) {
                "Observation range does not follow the preceding projection"
            }
            require(commit.successorProjection.lifetimeDataEventCount >= previous.lifetimeDataEventCount) {
                "Lifetime data count moved backwards"
            }
            require(commit.successorProjection.uploadedThroughCommit >= previous.uploadedThroughCommit) {
                "Upload watermark moved backwards"
            }
            require(commit.successorProjection.retainedFromCommit >= previous.retainedFromCommit) {
                "Retained floor moved backwards"
            }
        }

        private fun verifyCommitSemantics(
            commit: EngineCommit,
            previous: RuntimeProjection?,
        ): CommitSemantics {
            if (commit.events.isNotEmpty()) {
                require(commit.successorProjection.nextEventSequence == commit.events.last().sequenceNumber + 1) {
                    "Successor event sequence does not cover the commit"
                }
                if (commit.commitSequence == 1L) {
                    require(commit.events.first().sequenceNumber == 1L) { "Genesis event range does not start at one" }
                }
            } else if (previous != null) {
                require(commit.successorProjection.nextEventSequence == previous.nextEventSequence) {
                    "Empty commit advanced the event sequence"
                }
            }
            if (commit.sourceObservations.isNotEmpty()) {
                require(commit.sourceObservations.zipWithNext().all { (left, right) ->
                    left.observationSequence + 1 == right.observationSequence
                }) { "Observation sequence is not contiguous" }
                require(
                    commit.successorProjection.nextObservationSequence ==
                        commit.sourceObservations.last().observationSequence + 1,
                ) { "Successor observation sequence does not cover the commit" }
                if (commit.commitSequence == 1L) {
                    require(commit.sourceObservations.first().observationSequence == 1L) {
                        "Genesis observation range does not start at one"
                    }
                }
            } else if (previous != null) {
                require(commit.successorProjection.nextObservationSequence == previous.nextObservationSequence) {
                    "Commit without observations advanced the observation sequence"
                }
            }

            val eventsBySequence = commit.events.associateBy(RecordedEvent::sequenceNumber)
            require(eventsBySequence.size == commit.events.size) { "Duplicate event sequence" }
            val observedCollectorEvents = hashSetOf<Long>()
            commit.sourceObservations.forEach { observation ->
                val observationEvents = if (observation.eventCount == 0) {
                    emptyList()
                } else {
                    val first = requireNotNull(observation.firstEventSequence)
                    val last = requireNotNull(observation.lastEventSequence)
                    (first..last).map { sequence ->
                        val event = requireNotNull(eventsBySequence[sequence]) {
                            "Observation points outside its commit event range"
                        }
                        require(event.type.sourceId == observation.sourceId) { "Observation source/event mismatch" }
                        require(event.type.schemaVersion == observation.schemaVersion) {
                            "Observation schema/event mismatch"
                        }
                        require(event.conditionEpochId == observation.conditionEpochId) {
                            "Observation condition epoch/event mismatch"
                        }
                        require(observedCollectorEvents.add(sequence)) {
                            "Collector event belongs to multiple observations"
                        }
                        event
                    }
                }
                require(observation.encodedSha256 == SourceObservationIntegrity.calculate(observation, observationEvents)) {
                    "Source-observation digest mismatch"
                }
            }
            val collectorEvents = commit.events.filter { event ->
                ProtocolEventSourceRegistry[event.type.sourceId.value]?.sourceKind == RegistrySourceKind.COLLECTOR
            }
            require(collectorEvents.all { it.sequenceNumber in observedCollectorEvents }) {
                "Collector event has no source-observation provenance"
            }
            require(observedCollectorEvents.size == collectorEvents.size) {
                "Source observation covers a non-collector event"
            }
            requireCanonicalSourceObservationEventOrder(
                commit.sourceObservations,
                commit.consumedPendingInputSha256,
            )

            var activeEpochId = previous?.activeConditionEpoch?.id
            var activeAppliedDigest = previous?.activeConditionEpoch?.appliedResourceVectorSha256
            var epochKnown = previous != null || commit.commitSequence == 1L
            commit.sourceObservations.forEach { observation ->
                if (epochKnown) {
                    require(observation.conditionEpochId == activeEpochId) {
                        "Source observation is outside its active condition epoch"
                    }
                } else {
                    activeEpochId = observation.conditionEpochId
                    epochKnown = true
                }
            }
            commit.events.forEach { event ->
                event.fields["signed_configuration_sha256"]?.let { digest ->
                    require(digest == header.configurationSha256) {
                        "Runtime audit event is bound to another signed configuration"
                    }
                }
                if (ProtocolEventSourceRegistry[event.type.sourceId.value]?.sourceKind == RegistrySourceKind.COLLECTOR) {
                    val eventEpoch = requireNotNull(event.conditionEpochId)
                    if (epochKnown) {
                        require(activeEpochId == eventEpoch) { "Collector event is outside its active condition epoch" }
                    } else {
                        activeEpochId = eventEpoch
                        epochKnown = true
                    }
                }
                if (event.type.sourceId.value == CONDITION_SOURCE_ID) {
                    when (event.type.eventType) {
                        CONDITION_ACTIVATED -> {
                            val id = ConditionEpochId(requireNotNull(event.fields["condition_epoch_id"]))
                            val digest = requireNotNull(event.fields["applied_resource_vector_sha256"])
                            require(activeEpochId == null) { "Condition epochs overlap" }
                            activeEpochId = id
                            activeAppliedDigest = digest
                            epochKnown = true
                        }
                        CONDITION_DEACTIVATED -> {
                            val id = ConditionEpochId(requireNotNull(event.fields["condition_epoch_id"]))
                            val digest = requireNotNull(event.fields["applied_resource_vector_sha256"])
                            if (epochKnown) {
                                require(activeEpochId == id && activeAppliedDigest == digest) {
                                    "Condition epoch deactivation does not match the active epoch"
                                }
                            }
                            activeEpochId = null
                            activeAppliedDigest = null
                            epochKnown = true
                        }
                    }
                }
            }
            val successorEpoch = commit.successorProjection.activeConditionEpoch
            if (epochKnown) {
                require(successorEpoch?.id == activeEpochId) { "Condition epoch projection diverges from events" }
                if (successorEpoch != null && activeAppliedDigest != null) {
                    require(successorEpoch.appliedResourceVectorSha256 == activeAppliedDigest) {
                        "Condition resource-vector digest diverges from activation"
                    }
                }
            }
            successorEpoch?.let {
                require(it.configurationSha256 == header.configurationSha256) {
                    "Condition epoch configuration digest diverges"
                }
            }

            previous?.let {
                require(
                    commit.successorProjection.lifetimeDataEventCount ==
                        it.lifetimeDataEventCount + collectorEvents.size,
                ) { "Lifetime collector-data count does not match source observations" }
            }
            verifySourceCheckpoints(commit, previous)
            return CommitSemantics(collectorEvents.size.toLong())
        }

        private fun verifySourceCheckpoints(commit: EngineCommit, previous: RuntimeProjection?) {
            val expected = previous?.sourceCheckpoints?.toMutableMap() ?: mutableMapOf()
            val historyKnown = previous != null || commit.commitSequence == 1L
            val firstUnanchoredObservation = hashSetOf<EventSourceId>()
            commit.sourceObservations.forEach { observation ->
                val prior = expected[observation.sourceId]
                val hasUnknownPredecessor = !historyKnown && prior == null &&
                    firstUnanchoredObservation.add(observation.sourceId)
                if (!hasUnknownPredecessor) {
                    val expectedOrdinal = if (prior == null || prior.resourceGeneration != observation.resourceGeneration) {
                        0L
                    } else {
                        prior.nextProducerOrdinal
                    }
                    require(observation.producerOrdinal == expectedOrdinal) {
                        "Source producer ordinal is not contiguous"
                    }
                }
                val priorCoverage = prior?.coverage
                val observationCoverage = observation.coverage
                if (prior != null && prior.resourceGeneration == observation.resourceGeneration &&
                    priorCoverage != null && observationCoverage != null
                ) {
                    require(priorCoverage.clockBasis == observationCoverage.clockBasis &&
                        priorCoverage.endExclusive == observationCoverage.startInclusive
                    ) { "Retrospective source coverage is not contiguous" }
                }
                expected[observation.sourceId] = SourceCheckpoint(
                    sourceId = observation.sourceId,
                    resourceGeneration = observation.resourceGeneration,
                    nextProducerOrdinal = observation.producerOrdinal + 1,
                    coverage = observation.coverage ?: prior?.coverage,
                    cursor = prior?.cursor,
                )
            }
            if (historyKnown) {
                expected.forEach { (sourceId, checkpoint) ->
                    require(commit.successorProjection.sourceCheckpoints[sourceId] == checkpoint) {
                        "Successor source checkpoint diverges from observation provenance"
                    }
                }
                require(commit.successorProjection.sourceCheckpoints.keys == expected.keys) {
                    "Successor projection introduced an unproven source checkpoint"
                }
            } else {
                commit.sourceObservations.groupBy(SourceObservation::sourceId).forEach { (sourceId, observations) ->
                    val last = observations.last()
                    val checkpoint = requireNotNull(commit.successorProjection.sourceCheckpoints[sourceId]) {
                        "Source observation has no successor checkpoint"
                    }
                    require(checkpoint.resourceGeneration == last.resourceGeneration &&
                        checkpoint.nextProducerOrdinal == last.producerOrdinal + 1
                    ) { "Successor source checkpoint diverges from observation provenance" }
                }
            }
        }

        private fun fields(): Map<String, String> {
            beginObject()
            val fields = linkedMapOf<String, String>()
            var previous: String? = null
            while (reader.hasNext()) {
                require(fields.size < MAX_EVENT_FIELDS) { "Event has too many fields" }
                val name = reader.nextName()
                require(previous == null || requireNotNull(previous) < name) {
                    "Event fields are not canonically ordered"
                }
                writer.name(name)
                require(fields.put(name, string(name)) == null) { "Duplicate event field" }
                previous = name
            }
            endObject()
            return fields
        }

        private fun compareExpectedConfiguration() {
            val expected = JsonReader(
                InputStreamReader(ByteArrayInputStream(expectedConfigurationBytes), Charsets.UTF_8),
            ).apply { strictness = Strictness.STRICT }
            compareValue(reader, expected)
            require(expected.peek() == JsonToken.END_DOCUMENT) { "Expected configuration comparison is incomplete" }
            writer.rawCanonicalJson(expectedConfigurationBytes)
        }

        private fun compareValue(actual: JsonReader, expected: JsonReader) {
            require(actual.peek() == expected.peek()) { "Embedded configuration value type mismatch" }
            when (expected.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    actual.beginObject()
                    expected.beginObject()
                    while (expected.hasNext()) {
                        require(actual.hasNext()) { "Embedded configuration member is missing" }
                        require(actual.nextName() == expected.nextName()) { "Embedded configuration member mismatch" }
                        compareValue(actual, expected)
                    }
                    require(!actual.hasNext()) { "Embedded configuration has an unknown member" }
                    actual.endObject()
                    expected.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    actual.beginArray()
                    expected.beginArray()
                    while (expected.hasNext()) {
                        require(actual.hasNext()) { "Embedded configuration array entry is missing" }
                        compareValue(actual, expected)
                    }
                    require(!actual.hasNext()) { "Embedded configuration has an extra array entry" }
                    actual.endArray()
                    expected.endArray()
                }
                JsonToken.STRING, JsonToken.NUMBER -> require(actual.nextString() == expected.nextString()) {
                    "Embedded configuration value mismatch"
                }
                JsonToken.BOOLEAN -> require(actual.nextBoolean() == expected.nextBoolean()) {
                    "Embedded configuration value mismatch"
                }
                JsonToken.NULL -> {
                    actual.nextNull()
                    expected.nextNull()
                }
                else -> throw IllegalArgumentException("Invalid embedded configuration token")
            }
        }

        private fun configurationSignature(): SignatureProvenance {
            beginObject()
            member("signature")
            val signature = ProtocolBase64Url.decodeExact(string("signature"), SIGNATURE_BYTES, "Ed25519 signature")
            member("signer_key_id")
            val signerKeyId = string("signer_key_id")
            require(signerKeyId == expectedConfiguration.signer.keyId) { "Configuration signer key ID mismatch" }
            endObject()
            return SignatureProvenance(signerKeyId, signature)
        }

        private fun producer(): BundleProducer {
            beginObject()
            member("client_version")
            val clientVersion = decimalLongText("client_version")
            require(clientVersion.toLong() > 0) { "Producer client version must be positive" }
            member("platform")
            val platform = string("platform")
            endObject()
            return BundleProducer(platform, clientVersion)
        }

        private fun beginObject() {
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected JSON object" }
            reader.beginObject()
            writer.beginObject()
        }

        private fun endObject() {
            require(!reader.hasNext()) { "JSON object has an unknown member" }
            reader.endObject()
            writer.endObject()
        }

        private fun beginArray() {
            require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Expected JSON array" }
            reader.beginArray()
            writer.beginArray()
        }

        private fun endArray() {
            reader.endArray()
            writer.endArray()
        }

        private fun member(expected: String) {
            require(reader.hasNext() && reader.nextName() == expected) { "Expected JSON member $expected" }
            writer.name(expected)
        }

        private fun string(label: String): String {
            require(reader.peek() == JsonToken.STRING) { "$label must be a string" }
            return reader.nextString().also(writer::value)
        }

        private fun boolean(label: String): Boolean {
            require(reader.peek() == JsonToken.BOOLEAN) { "$label must be a boolean" }
            return reader.nextBoolean().also(writer::value)
        }

        private fun nullableString(label: String): String? = if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            writer.nullValue()
            null
        } else {
            string(label)
        }

        private fun integer(label: String): Int {
            require(reader.peek() == JsonToken.NUMBER) { "$label must be an integer" }
            val raw = reader.nextString()
            writer.valueCanonicalInteger(raw)
            return raw.toIntOrNull() ?: throw IllegalArgumentException("$label is outside Int range")
        }

        private fun decimalLongText(label: String): String {
            val value = string(label)
            require(UNSIGNED_DECIMAL.matches(value) && value.toLongOrNull() != null) {
                "$label must be a bounded canonical decimal string"
            }
            return value
        }

        private fun decimalLong(label: String): Long = decimalLongText(label).toLong()

        private fun nullableDecimalLong(label: String): Long? = if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            writer.nullValue()
            null
        } else {
            decimalLong(label)
        }

        private fun digest(label: String): String = string(label).also { value ->
            require(SHA256.matches(value)) {
                "$label must be a lowercase SHA-256 digest"
            }
        }

        private fun nullableDigest(label: String): String? = nullableString(label)?.also { value ->
            require(SHA256.matches(value)) { "$label must be a lowercase SHA-256 digest" }
        }

        private fun <T> nullableObject(parse: () -> T): T? = if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            writer.nullValue()
            null
        } else {
            parse()
        }

        private data class SignatureProvenance(val signerKeyId: String, val signature: ByteArray)
        private data class CommitSemantics(val collectorEventCount: Long)
        private data class CommitSummary(
            val count: Long,
            val eventCount: Long,
            val collectorEventCount: Long,
            val firstSequence: Long?,
            val lastSequence: Long?,
            val firstPreviousSha256: String?,
            val lastSha256: String?,
            val lastProjection: RuntimeProjection?,
        )
    }

    private class DigestingCountingInputStream(private val source: InputStream) : InputStream() {
        private val messageDigest = MessageDigest.getInstance("SHA-256")
        var count = 0L
            private set

        override fun read(): Int = source.read().also { value ->
            if (value >= 0) {
                messageDigest.update(value.toByte())
                count++
            }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            source.read(bytes, offset, length).also { read ->
                if (read > 0) {
                    messageDigest.update(bytes, offset, read)
                    count += read
                }
            }

        fun digest(): ByteArray = messageDigest.digest()
    }

    private class DigestingCountingOutputStream : OutputStream() {
        private val messageDigest = MessageDigest.getInstance("SHA-256")
        var count = 0L
            private set

        override fun write(value: Int) {
            messageDigest.update(value.toByte())
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            messageDigest.update(bytes, offset, length)
            count += length
        }

        fun digest(): ByteArray = messageDigest.digest()
    }

    private fun canonicalUuid(value: String, label: String): UUID = runCatching { UUID.fromString(value) }
        .getOrElse { throw IllegalArgumentException("Invalid $label", it) }
        .also { uuid ->
            require(uuid.toString() == value && uuid.version() == 4 && uuid.variant() == 2) { "Invalid $label" }
        }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
        runCatching { enumValueOf<T>(value) }
            .getOrElse { throw IllegalArgumentException("Unknown $label", it) }

    private const val SIGNATURE_BYTES = 64
    private const val MAX_EVENTS_PER_COMMIT = 262_144
    private const val MAX_OBSERVATIONS_PER_COMMIT = 4_096
    private const val MAX_MUTATIONS_PER_COMMIT = 4_096
    private const val MAX_SOURCE_CHECKPOINTS = 128
    private const val MAX_EVENT_FIELDS = 32
    private const val CONDITION_SOURCE_ID = "study_condition.v1"
    private const val CONDITION_ACTIVATED = "CONDITION_EPOCH_ACTIVATED"
    private const val CONDITION_DEACTIVATED = "CONDITION_EPOCH_DEACTIVATED"
    private const val TRAFFIC_SOURCE_ID = "traffic_shaping.v1"
    private val CHECKPOINT_COMPONENT_ID = Regex("main(?:/[0-9]{4})?")
    private val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")
    private val PARTICIPANT_INSTANCE_ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
}

/**
 * Producer manifests stay in admission/ordinal order. Collector event ranges normally follow that
 * order; the sole exception is a pending-consuming resource barrier, whose first manifest is the
 * causal NORMAL submission and whose remaining pre-drain/flush ranges precede it for reduction.
 */
internal fun requireCanonicalSourceObservationEventOrder(
    observations: List<SourceObservation>,
    consumedPendingInputSha256: String?,
) {
    val eventful = observations.filter { it.eventCount > 0 }
    if (eventful.size <= 1) return
    val semanticOrder = eventful.sortedBy { requireNotNull(it.firstEventSequence) }
    semanticOrder.zipWithNext().forEach { (left, right) ->
        require(Math.addExact(requireNotNull(left.lastEventSequence), 1L) == right.firstEventSequence) {
            "Source observation event ranges are not contiguous"
        }
    }
    if (semanticOrder == eventful) return

    require(consumedPendingInputSha256 != null) {
        "Source observation event ranges diverge outside a pending-consuming barrier"
    }
    val causal = observations.first()
    require(causal.admissionKind == ObservationAdmissionKind.NORMAL && causal.eventCount > 0) {
        "Pending-consuming barrier does not start with an eventful causal observation"
    }
    var flushStarted = false
    observations.drop(1).forEach { observation ->
        when (observation.admissionKind) {
            ObservationAdmissionKind.NORMAL -> require(!flushStarted) {
                "A normal pre-drain observation follows a boundary flush"
            }
            ObservationAdmissionKind.BARRIER_FLUSH -> flushStarted = true
        }
    }
    val expectedSemanticOrder = observations.drop(1).filter { it.eventCount > 0 } + causal
    require(semanticOrder == expectedSemanticOrder) {
        "Pending-consuming barrier event ranges are not pre-drain/flush then causal"
    }
}

/** Canonical producer-batch digest copied from the public runtime observation contract. */
internal object SourceObservationIntegrity {
    private const val FORMAT = "particeps-source-observation-v1"

    fun calculate(observation: SourceObservation, events: List<RecordedEvent>): String {
        require(events.size == observation.eventCount) { "Observation event count mismatch" }
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeCanonicalString(FORMAT)
                output.writeCanonicalString(observation.sourceId.value)
                output.writeInt(observation.schemaVersion)
                output.writeLong(observation.resourceGeneration)
                output.writeLong(observation.producerOrdinal)
                output.writeCanonicalString(observation.conditionEpochId.value)
                output.writeBoolean(observation.coverage != null)
                observation.coverage?.let { coverage ->
                    output.writeCanonicalString(coverage.clockBasis.name)
                    output.writeCanonicalString(coverage.startInclusive)
                    output.writeCanonicalString(coverage.endExclusive)
                }
                output.writeInt(events.size)
                events.forEach { event ->
                    require(event.type.sourceId == observation.sourceId &&
                        event.type.schemaVersion == observation.schemaVersion
                    ) { "Observation event identity mismatch" }
                    output.writeCanonicalString(event.type.eventType)
                    output.writeLong(event.observedTime.wallTimeUtcMillis)
                    output.writeLong(event.observedTime.elapsedRealtimeNanos)
                    output.writeCanonicalString(event.observedTime.bootSessionId)
                    val fields = event.fields.toSortedMap()
                    output.writeInt(fields.size)
                    fields.forEach { (key, value) ->
                        output.writeCanonicalString(key)
                        output.writeCanonicalString(value)
                    }
                }
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(payload).toHex()
    }

    private fun DataOutputStream.writeCanonicalString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
