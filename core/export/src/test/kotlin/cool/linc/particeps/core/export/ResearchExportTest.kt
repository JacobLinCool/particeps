package cool.linc.particeps.core.export

import com.google.gson.JsonParser
import cool.linc.particeps.core.crypto.HpkeCrypto
import cool.linc.particeps.core.definition.AppLifecycleConfiguration
import cool.linc.particeps.core.definition.ExportConfiguration
import cool.linc.particeps.core.definition.ProtocolBase64Url
import cool.linc.particeps.core.definition.SignerIdentity
import cool.linc.particeps.core.definition.StudyConfiguration
import cool.linc.particeps.core.definition.StudyConfigurationCodec
import cool.linc.particeps.core.model.ExperimentState
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
import cool.linc.particeps.core.model.StorageUsage
import cool.linc.particeps.core.model.StudyMetadata
import cool.linc.particeps.core.model.StudyStore
import cool.linc.particeps.core.protocol.VerifiedConfiguration
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchExportTest {
    @Test
    fun partExpFrameAndAuthenticatedDocumentCarryExactProvenance() = runBlocking {
        val fixture = fixture(assignedParticipantId = "arm-a-017")
        val events = events(3)
        val metadata = metadata(events, assignedParticipantId = "arm-a-017")
        val bundleId = UUID.fromString("00000000-0000-4000-8000-000000000099")
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(
            snapshot(fixture.verified, metadata, bundleId = bundleId),
            SnapshotStore(metadata, events),
            destination,
        )
        val encrypted = destination.toByteArray()
        val header = ByteBuffer.wrap(encrypted)
        assertEquals("PTCEXP01", ByteArray(8).also(header::get).toString(Charsets.US_ASCII))
        assertEquals(bundleId.mostSignificantBits, header.long)
        assertEquals(bundleId.leastSignificantBits, header.long)
        assertEquals(fixture.verified.configurationSha256, ByteArray(32).also(header::get).toHex())
        assertEquals("export-key".length, header.short.toInt())
        header.position(header.position() + 12)
        assertEquals("export-key", ByteArray("export-key".length).also(header::get).toString(Charsets.UTF_8))
        header.position(header.position() + 80)
        assertTrue(header.hasRemaining())

        val plaintext = ResearchExport.decrypt(encrypted, fixture.hpke.privateKey, fixture.configuration)
        val text = plaintext.toString(Charsets.UTF_8)
        val root = JsonParser.parseString(text).asJsonObject
        val experiment = root.getAsJsonObject("experiment")
        assertTrue(text.startsWith("{\"bundle_id\":\"$bundleId\",\"bundle_kind\":\"manual_export\""))
        assertEquals(BUNDLE_KEYS, root.keySet())
        assertEquals(EXPERIMENT_KEYS, experiment.keySet())
        assertEquals(fixture.verified.configurationSha256, root.get("configuration_sha256").asString)
        assertEquals(ProtocolBase64Url.encode(fixture.verified.signature),
            root.getAsJsonObject("configuration_signature").get("signature").asString)
        assertEquals("3", experiment.get("event_count").asString)
        assertEquals("3", experiment.get("last_sequence_number").asString)
        assertEquals("1", experiment.getAsJsonArray("events")[0].asJsonObject.get("sequence_number").asString)
        assertEquals("2000", experiment.getAsJsonArray("events")[0].asJsonObject
            .getAsJsonObject("observed_time").get("monotonic_time_nanos").asString)
        assertEquals(bundleId, receipt.bundleId)
        assertEquals(3L, receipt.eventCount)
        assertEquals(encrypted.size.toLong(), receipt.byteCount)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("arm-a-017"))
    }

    @Test
    fun rangedAndBudgetedBundlesDeclareOnlyRowsActuallyWritten() = runBlocking {
        val fixture = fixture()
        val events = (1..2_000).map {
            event(it.toLong(), fields = mapOf("value" to "x".repeat(200)))
        }
        val metadata = metadata(events)
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(
            snapshot(
                fixture.verified,
                metadata,
                fromSequence = 501,
                toSequence = 2_000,
                maximumPlaintextBytes = 64 * 1_024,
                kind = BundleKind.AUTOMATIC_UPLOAD,
            ),
            SnapshotStore(metadata, events),
            destination,
        )

        assertEquals(501L, receipt.firstSequence)
        assertTrue(receipt.lastSequence in 501..<2_000)
        assertEquals(receipt.lastSequence - 500, receipt.eventCount)
        val text = ResearchExport.decrypt(destination.toByteArray(), fixture.hpke.privateKey, fixture.configuration)
            .toString(Charsets.UTF_8)
        assertTrue(text.contains("\"bundle_kind\":\"automatic_upload\""))
        assertTrue(text.contains("\"last_sequence_number\":\"${receipt.lastSequence}\""))
        assertTrue(text.contains("\"sequence_number\":\"${receipt.lastSequence}\""))
        assertFalse(text.contains("\"sequence_number\":\"${receipt.lastSequence + 1}\""))
    }

    @Test
    fun manualEmptyBundleIsValidButAutomaticUploadIsNot() = runBlocking {
        val fixture = fixture()
        val metadata = StudyMetadata.initial("export-test", "export-config")
        val destination = ByteArrayOutputStream()
        val receipt = ResearchExport.encrypt(
            snapshot(fixture.verified, metadata),
            SnapshotStore(metadata, emptyList()),
            destination,
        )

        assertEquals(1L, receipt.firstSequence)
        assertEquals(0L, receipt.lastSequence)
        assertEquals(0L, receipt.eventCount)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ResearchExport.encrypt(
                    snapshot(fixture.verified, metadata, kind = BundleKind.AUTOMATIC_UPLOAD),
                    SnapshotStore(metadata, emptyList()),
                    ByteArrayOutputStream(),
                )
            }
        }
        Unit
    }

    @Test
    fun wrongContextTamperingAndOldV1FramingFailClosed() = runBlocking {
        val fixture = fixture()
        val stored = events(1)
        val metadata = metadata(stored)
        val destination = ByteArrayOutputStream()
        ResearchExport.encrypt(snapshot(fixture.verified, metadata), SnapshotStore(metadata, stored), destination)
        val encoded = destination.toByteArray()

        assertThrows(Exception::class.java) {
            ResearchExport.decrypt(encoded, HpkeCrypto.generateKeyPair().privateKey, fixture.configuration)
        }
        assertThrows(Exception::class.java) {
            ResearchExport.decrypt(encoded.copyOf().also { it[it.lastIndex]++ }, fixture.hpke.privateKey, fixture.configuration)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchExport.decrypt(encoded.copyOf().also { it[24]++ }, fixture.hpke.privateKey, fixture.configuration)
        }
        val oldV1 = "PTCEXP01".toByteArray() + ByteArray(128)
        assertThrows(IllegalArgumentException::class.java) {
            ResearchExport.decrypt(oldV1, fixture.hpke.privateKey, fixture.configuration)
        }
        Unit
    }

    @Test
    fun uploadReceiptCodecIsExactCanonicalAndSelfConsistent() {
        val receipt = ExportReceipt(
            UUID.fromString("00000000-0000-4000-8000-000000000099"),
            "11".repeat(32),
            501,
            750,
            250,
            "22".repeat(32),
            16_777_216,
        )
        val encoded = UploadReceiptCodec.encode(receipt)

        assertEquals(receipt, UploadReceiptCodec.decode(encoded))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"byte_count\":\"16777216\""))
        assertThrows(IllegalArgumentException::class.java) {
            UploadReceiptCodec.decode((" " + encoded.toString(Charsets.UTF_8)).toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UploadReceiptCodec.decode(encoded.toString(Charsets.UTF_8)
                .replace("\"501\"", "\"0501\"").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UploadReceiptCodec.decode(encoded.toString(Charsets.UTF_8)
                .replace("\"event_count\":\"250\"", "\"event_count\":250").toByteArray())
        }
    }

    @Test
    fun snapshotRejectsConfigurationProvenanceDrift() {
        val fixture = fixture()
        val metadata = StudyMetadata.initial("export-test", "export-config")
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(
                fixture.verified.copy(configurationSha256 = "00".repeat(32)),
                metadata,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(
                fixture.verified.copy(canonicalConfigurationBytes = fixture.verified.canonicalConfigurationBytes + 0),
                metadata,
            )
        }
    }

    private fun snapshot(
        verified: VerifiedConfiguration,
        metadata: StudyMetadata,
        bundleId: UUID = UUID.fromString("00000000-0000-4000-8000-000000000099"),
        fromSequence: Long = metadata.retainedFromSequence,
        toSequence: Long? = null,
        maximumPlaintextBytes: Long? = null,
        kind: BundleKind = BundleKind.MANUAL_EXPORT,
    ) = ExportSnapshot(
        verified,
        metadata,
        BundleProducer("android", "42"),
        kind,
        10_000,
        bundleId,
        fromSequence,
        toSequence,
        maximumPlaintextBytes,
    )

    private fun fixture(assignedParticipantId: String? = null): Fixture {
        val hpke = HpkeCrypto.generateKeyPair()
        val configuration = configuration(hpke.publicKey, assignedParticipantId)
        val bytes = StudyConfigurationCodec.encode(configuration)
        return Fixture(
            hpke,
            configuration,
            VerifiedConfiguration(
                configuration,
                bytes,
                configuration.signer.keyId,
                ByteArray(64) { it.toByte() },
                bytes.sha256Hex(),
                false,
            ),
        )
    }

    private fun configuration(publicKey: ByteArray, assignedParticipantId: String?) = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "export-test",
        configurationId = "export-config",
        assignedParticipantId = assignedParticipantId,
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = "android",
        minimumClientVersion = 1,
        title = "Export test",
        researcherName = "Export researcher",
        researcherContact = "export@example.invalid",
        purpose = "Test Protocol v1 export encryption.",
        durationHours = 1,
        consentDocumentVersion = "v1",
        consentSummary = "Export test consent.",
        collectors = listOf(AppLifecycleConfiguration(true)),
        surveys = emptyList(),
        interventions = emptyList(),
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", ProtocolBase64Url.encode(ByteArray(32) { 3 })),
        export = ExportConfiguration("export-key", ProtocolBase64Url.encode(publicKey)),
        upload = null,
    )

    private fun events(count: Int) = (1..count).map { event(it.toLong()) }

    private fun event(sequence: Long, fields: Map<String, String> = emptyMap()) = RecordedEvent(
        sequence,
        "app_lifecycle.v1",
        1,
        ResearchTime(1_000, 2_000, "boot-test"),
        "EVENT",
        fields,
    )

    private fun metadata(events: List<RecordedEvent>, assignedParticipantId: String? = null) =
        StudyMetadata.initial(
            "export-test",
            "export-config",
            assignedParticipantId,
            "00000000-0000-4000-8000-000000000017",
        ).copy(
            state = ExperimentState.RUNNING,
            eventCount = events.size.toLong(),
            nextSequenceNumber = events.size + 1L,
            lastEvents = events.lastOrNull()?.let { mapOf(it.collectorId to it) } ?: emptyMap(),
        )

    private data class Fixture(
        val hpke: cool.linc.particeps.core.crypto.HpkeKeyPair,
        val configuration: StudyConfiguration,
        val verified: VerifiedConfiguration,
    )

    private class SnapshotStore(
        private var metadata: StudyMetadata,
        private val events: List<RecordedEvent>,
    ) : StudyStore {
        override suspend fun loadMetadata() = metadata
        override suspend fun initialize(metadata: StudyMetadata) { this.metadata = metadata }
        override suspend fun saveMetadata(metadata: StudyMetadata) { this.metadata = metadata }
        override suspend fun appendEvent(event: RecordedEvent) = error("Not supported")
        override suspend fun appendEventAtomically(event: RecordedEvent, metadata: StudyMetadata) = error("Not supported")
        override suspend fun readEvents(fromSequenceInclusive: Long, upToSequenceInclusive: Long,
            consume: (RecordedEvent) -> Unit) {
            events.filter { it.sequenceNumber in fromSequenceInclusive..upToSequenceInclusive }.forEach(consume)
        }
        override suspend fun storageUsage() = StorageUsage(events.size.toLong(), 16_777_216)
        override suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long) = metadata
        override suspend fun clear() = Unit
    }

    private companion object {
        val BUNDLE_KEYS = setOf(
            "bundle_id", "bundle_kind", "configuration", "configuration_sha256",
            "configuration_signature", "experiment", "exported_at_utc_millis", "format", "producer",
        )
        val EXPERIMENT_KEYS = setOf(
            "assigned_participant_id", "configuration_id", "durable_through_sequence", "event_count",
            "events", "experiment_id", "first_sequence_number", "last_sequence_number",
            "next_sequence_number", "participant_instance_id", "retained_from_sequence", "state",
            "transitions", "uploaded_through_sequence",
        )
    }
}
