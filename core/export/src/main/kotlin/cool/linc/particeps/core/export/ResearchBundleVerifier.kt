package cool.linc.particeps.core.export

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import cool.linc.particeps.core.collector.ProtocolEventContracts
import cool.linc.particeps.core.definition.ProtocolBase64Url
import cool.linc.particeps.core.definition.StudyConfiguration
import cool.linc.particeps.core.definition.StudyConfigurationCodec
import cool.linc.particeps.core.model.EventDraft
import cool.linc.particeps.core.model.ExperimentState
import cool.linc.particeps.core.model.ExperimentStateMachine
import cool.linc.particeps.core.model.ResearchTime
import cool.linc.particeps.core.model.TransitionReason
import cool.linc.particeps.core.protocol.ConfigurationVerifier
import cool.linc.particeps.core.protocol.SignedConfigurationCodec
import cool.linc.particeps.core.protocol.SignedConfigurationEnvelope
import cool.linc.particeps.core.protocol.VerifiedConfiguration
import java.io.ByteArrayInputStream
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
    val nextSequenceNumber: Long,
    val retainedFromSequence: Long,
    val durableThroughSequence: Long,
    val uploadedThroughSequence: Long,
    val firstSequenceNumber: Long,
    val lastSequenceNumber: Long,
    val eventCount: Long,
    val transitionCount: Long,
)

/**
 * The sole closed-world reader for authenticated `particeps-research-bundle-v1` plaintext.
 *
 * The caller must keep [plaintext] private and unpublished until this method returns. Validation is
 * streaming: even a manual export near the local-storage quota does not become one in-memory DOM.
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
        private val permittedCollectors = buildSet {
            expectedConfiguration.collectors.forEach { add(it.id) }
            if (expectedConfiguration.interventions.isNotEmpty()) add(INTERVENTION_COLLECTOR_ID)
        }

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
                // Historical analysis verifies the issuance-time contract, not whether it is still
                // enrollable today. StudyConfiguration already proves this instant precedes expiry.
                now = { expectedConfiguration.issuedAt },
            ).verify(
                SignedConfigurationCodec.encode(
                    SignedConfigurationEnvelope(
                        signerKeyId = provenance.signerKeyId,
                        configurationBytes = expectedConfigurationBytes,
                        signature = provenance.signature,
                    ),
                ),
            )
            require(verified.configuration == expectedConfiguration) { "Embedded configuration mismatch" }
            require(producer.platform == expectedConfiguration.platform) { "Producer platform mismatch" }
            return VerifiedResearchBundle(header, kind, verified, producer, exportedAt, experiment)
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

        private fun experiment(kind: BundleKind): VerifiedExperimentSnapshot {
            beginObject()
            member("assigned_participant_id")
            val assignedParticipantId = nullableString("assigned_participant_id")
            require(assignedParticipantId == expectedConfiguration.assignedParticipantId) {
                "Assigned participant ID mismatch"
            }
            member("configuration_id")
            val configurationId = string("configuration_id")
            require(configurationId == expectedConfiguration.configurationId) { "Configuration ID mismatch" }
            member("durable_through_sequence")
            val durableThrough = decimalLong("durable_through_sequence")
            member("event_count")
            val declaredEventCount = decimalLong("event_count")
            member("events")
            val events = events(declaredEventCount)
            member("experiment_id")
            val experimentId = string("experiment_id")
            require(experimentId == expectedConfiguration.experimentId) { "Experiment ID mismatch" }
            member("first_sequence_number")
            val firstSequence = decimalLong("first_sequence_number")
            require(firstSequence > 0) { "First sequence must be positive" }
            member("last_sequence_number")
            val lastSequence = decimalLong("last_sequence_number")
            member("next_sequence_number")
            val nextSequence = decimalLong("next_sequence_number")
            require(nextSequence > 0 && durableThrough == nextSequence - 1) { "Durable sequence boundary mismatch" }
            member("participant_instance_id")
            val participantInstanceId = string("participant_instance_id")
            require(PARTICIPANT_INSTANCE_ID.matches(participantInstanceId)) { "Invalid participant instance ID" }
            member("retained_from_sequence")
            val retainedFrom = decimalLong("retained_from_sequence")
            member("state")
            val state = enumValue<ExperimentState>(string("state"), "experiment state")
            member("transitions")
            val transitions = transitions(state)
            member("uploaded_through_sequence")
            val uploadedThrough = decimalLong("uploaded_through_sequence")
            endObject()

            require(retainedFrom in 1..nextSequence) { "Retained range start is invalid" }
            require(uploadedThrough in 0..durableThrough) { "Upload watermark is invalid" }
            require(retainedFrom <= uploadedThrough + 1) { "Retained range exceeds the upload watermark" }
            require(firstSequence in retainedFrom..nextSequence) { "Bundle starts outside retained data" }
            require(lastSequence <= durableThrough) { "Bundle range exceeds durable data" }
            val expectedLast = if (declaredEventCount == 0L) {
                firstSequence - 1
            } else {
                require(firstSequence <= Long.MAX_VALUE - declaredEventCount + 1) { "Bundle range overflows" }
                firstSequence + declaredEventCount - 1
            }
            require(lastSequence == expectedLast) { "Bundle range/count mismatch" }
            require(events.count == declaredEventCount) { "Bundle event count mismatch" }
            if (declaredEventCount > 0) {
                require(events.firstSequence == firstSequence && events.lastSequence == lastSequence) {
                    "Bundle events do not cover the declared range"
                }
            }
            if (kind == BundleKind.AUTOMATIC_UPLOAD) {
                require(declaredEventCount > 0) { "Automatic upload cannot be empty" }
                require(firstSequence == uploadedThrough + 1) { "Automatic upload does not start after its watermark" }
            }
            return VerifiedExperimentSnapshot(
                experimentId,
                configurationId,
                participantInstanceId,
                assignedParticipantId,
                state,
                nextSequence,
                retainedFrom,
                durableThrough,
                uploadedThrough,
                firstSequence,
                lastSequence,
                declaredEventCount,
                transitions,
            )
        }

        private fun events(declaredCount: Long): EventSummary {
            beginArray()
            var count = 0L
            var first: Long? = null
            var previous: Long? = null
            while (reader.hasNext()) {
                require(count < declaredCount) { "Bundle contains more events than declared" }
                val sequence = event()
                previous?.let { require(it < Long.MAX_VALUE && sequence == it + 1) { "Non-contiguous event sequence" } }
                if (first == null) first = sequence
                previous = sequence
                count++
            }
            endArray()
            return EventSummary(count, first, previous)
        }

        private fun event(): Long {
            beginObject()
            member("collector_id")
            val collectorId = string("collector_id")
            require(collectorId in permittedCollectors) { "Collector was not enabled by the configuration" }
            val contract = requireNotNull(ProtocolEventContracts[collectorId]) { "Unknown collector" }
            member("fields")
            val fields = fields(contract.payloads.values.maxOf { it.fields.size })
            member("observed_time")
            val observedTime = time()
            member("payload_schema_version")
            val schemaVersion = integer("payload_schema_version")
            member("payload_type")
            val payloadType = string("payload_type")
            member("sequence_number")
            val sequenceNumber = decimalLong("sequence_number")
            require(sequenceNumber > 0) { "Event sequence must be positive" }
            endObject()

            val draft = EventDraft(collectorId, schemaVersion, observedTime, payloadType, fields)
            require(contract.accepts(draft, sequenceNumber)) { "Event violates its catalog contract" }
            return sequenceNumber
        }

        private fun fields(maximumFieldCount: Int): Map<String, String> {
            beginObject()
            val fields = linkedMapOf<String, String>()
            var previous: String? = null
            while (reader.hasNext()) {
                require(fields.size < maximumFieldCount) { "Event has too many fields for its catalog contract" }
                val name = reader.nextName()
                require(previous == null || previous < name) { "Event fields are not in canonical order" }
                writer.name(name)
                require(fields.put(name, string(name)) == null) { "Duplicate event field" }
                previous = name
            }
            endObject()
            return fields
        }

        private fun transitions(finalState: ExperimentState): Long {
            beginArray()
            val stateMachine = ExperimentStateMachine()
            var state = ExperimentState.IMPORTED
            var count = 0L
            while (reader.hasNext()) {
                beginObject()
                member("from")
                val from = enumValue<ExperimentState>(string("from"), "transition source")
                member("reason")
                val reason = enumValue<TransitionReason>(string("reason"), "transition reason")
                member("time")
                time()
                member("to")
                val to = enumValue<ExperimentState>(string("to"), "transition destination")
                endObject()
                require(from == state && reason.destination == to && stateMachine.canTransition(from, to)) {
                    "Invalid experiment transition"
                }
                state = to
                require(count < Long.MAX_VALUE) { "Transition count overflows" }
                count++
            }
            endArray()
            require((count == 0L && finalState == ExperimentState.IMPORTED) || (count > 0 && state == finalState)) {
                "Experiment state does not match its transition history"
            }
            return count
        }

        private fun time(): ResearchTime {
            beginObject()
            member("boot_session_id")
            val bootSessionId = string("boot_session_id")
            require(bootSessionId.toByteArray(Charsets.UTF_8).size in 1..MAXIMUM_BOOT_SESSION_ID_BYTES) {
                "Invalid boot session ID"
            }
            member("monotonic_time_nanos")
            val monotonic = decimalLong("monotonic_time_nanos")
            member("wall_time_utc_millis")
            val wall = decimalLong("wall_time_utc_millis")
            endObject()
            return ResearchTime(wall, monotonic, bootSessionId)
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

        private data class SignatureProvenance(val signerKeyId: String, val signature: ByteArray)
        private data class EventSummary(val count: Long, val firstSequence: Long?, val lastSequence: Long?)
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
    private const val MAXIMUM_BOOT_SESSION_ID_BYTES = 128
    private const val INTERVENTION_COLLECTOR_ID = "interventions.v1"
    private val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")
    private val PARTICIPANT_INSTANCE_ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
}
