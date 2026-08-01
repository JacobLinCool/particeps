package cool.linc.androiddatacollector.core.export

import cool.linc.androiddatacollector.core.crypto.HpkeCrypto
import cool.linc.androiddatacollector.core.model.StorageUsage
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StudyStore
import cool.linc.androiddatacollector.core.definition.AppLifecycleConfiguration
import cool.linc.androiddatacollector.core.definition.ExportConfiguration
import cool.linc.androiddatacollector.core.definition.SignerIdentity
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchExportTest {
    @Test
    fun repeatedExportsAreIndependentDecryptableSnapshotsWithoutAStateTransition() = runBlocking {
        val keys = HpkeCrypto.generateKeyset()
        val configuration = configuration(keys.publicKeysetJson)
        val time = ResearchTime(1_000, 2_000, "boot-test")
        val event = RecordedEvent(1, "app_lifecycle.v1", 1, time, "ACTIVITY_RESUMED", mapOf("source" to "test"))
        val metadata = StudyMetadata.initial("export-test", "export-config").copy(
            state = ExperimentState.RUNNING,
            eventCount = 1,
            nextSequenceNumber = 2,
            lastEvents = mapOf(event.collectorId to event),
        )
        val snapshot = ExportSnapshot(configuration, metadata, 10_000)
        val events = SnapshotStore(metadata, listOf(event))

        val firstBytes = ByteArrayOutputStream()
        val first = ResearchExport.encrypt(snapshot, events, firstBytes)
        val secondBytes = ByteArrayOutputStream()
        val second = ResearchExport.encrypt(snapshot.copy(exportedAtUtcMillis = 11_000), events, secondBytes)

        assertNotEquals(first.sha256, second.sha256)
        assertTrue(ResearchExport.decrypt(firstBytes.toByteArray(), keys.privateKeysetJson, configuration).toString(Charsets.UTF_8)
            .contains("\"state\":\"RUNNING\""))
        assertTrue(ResearchExport.decrypt(secondBytes.toByteArray(), keys.privateKeysetJson, configuration).toString(Charsets.UTF_8)
            .contains("\"sequence_number\":1"))
        assertEquals(ExperimentState.RUNNING, metadata.state)
        assertFalse(ExperimentState.entries.any { it.name == "EXPORTED" })
    }

    @Test
    fun exportReadsOnlyTheMetadataBoundaryWhenEventsAppendDuringStreaming() = runBlocking {
        val keys = HpkeCrypto.generateKeyset()
        val configuration = configuration(keys.publicKeysetJson)
        val time = ResearchTime(1_000, 2_000, "boot-test")
        val first = RecordedEvent(1, "app_lifecycle.v1", 1, time, "FIRST", emptyMap())
        val second = RecordedEvent(2, "app_lifecycle.v1", 1, time, "SECOND", emptyMap())
        val metadata = StudyMetadata.initial("export-test", "export-config").copy(
            state = ExperimentState.RUNNING,
            eventCount = 1,
            nextSequenceNumber = 2,
            lastEvents = mapOf(first.collectorId to first),
        )
        val destination = ByteArrayOutputStream()

        val receipt = ResearchExport.encrypt(
            ExportSnapshot(configuration, metadata, 10_000),
            SnapshotStore(metadata, listOf(first), appendDuringRead = second),
            destination,
        )

        val plaintext = ResearchExport.decrypt(
            destination.toByteArray(),
            keys.privateKeysetJson,
            configuration,
        ).toString(Charsets.UTF_8)
        assertEquals(1L, receipt.sequenceBoundary)
        assertTrue(plaintext.contains("\"payload_type\":\"FIRST\""))
        assertFalse(plaintext.contains("\"payload_type\":\"SECOND\""))
    }

    @Test
    fun rangedBundleCarriesOnlyItsWindowAndDeclaresIt() = runBlocking {
        val keys = HpkeCrypto.generateKeyset()
        val configuration = configuration(keys.publicKeysetJson)
        val time = ResearchTime(1_000, 2_000, "boot-test")
        val events = (1..5).map {
            RecordedEvent(it.toLong(), "app_lifecycle.v1", 1, time, "EVENT_$it", emptyMap())
        }
        val metadata = StudyMetadata.initial("export-test", "export-config").copy(
            state = ExperimentState.RUNNING,
            eventCount = 5,
            nextSequenceNumber = 6,
            lastEvents = mapOf(events.last().collectorId to events.last()),
        )
        val destination = ByteArrayOutputStream()

        val receipt = ResearchExport.encrypt(
            ExportSnapshot(configuration, metadata, 10_000, fromSequence = 3, toSequence = 4),
            SnapshotStore(metadata, events),
            destination,
        )

        val plaintext = ResearchExport.decrypt(
            destination.toByteArray(),
            keys.privateKeysetJson,
            configuration,
        ).toString(Charsets.UTF_8)

        assertEquals(3L, receipt.firstSequence)
        assertEquals(4L, receipt.sequenceBoundary)
        assertEquals(2L, receipt.eventCount)
        assertTrue(plaintext.contains("\"format\":\"research-bundle-v1\""))
        assertTrue(plaintext.contains("\"first_sequence_number\":3"))
        assertTrue(plaintext.contains("\"last_sequence_number\":4"))
        // A chunk must identify which install it came from, or a study that uploads cannot tell
        // one participant's events from another's.
        assertTrue(plaintext.contains("\"participant_instance_id\":\"${metadata.participantInstanceId}\""))
        assertTrue(plaintext.contains("\"payload_type\":\"EVENT_3\""))
        assertTrue(plaintext.contains("\"payload_type\":\"EVENT_4\""))
        assertFalse(plaintext.contains("\"payload_type\":\"EVENT_2\""))
        assertFalse(plaintext.contains("\"payload_type\":\"EVENT_5\""))
    }

    @Test
    fun rangedBundleRejectsAWindowBeyondTheDurableEventCount() = runBlocking {
        val keys = HpkeCrypto.generateKeyset()
        val configuration = configuration(keys.publicKeysetJson)
        val time = ResearchTime(1_000, 2_000, "boot-test")
        val event = RecordedEvent(1, "app_lifecycle.v1", 1, time, "ONLY", emptyMap())
        val metadata = StudyMetadata.initial("export-test", "export-config").copy(
            state = ExperimentState.RUNNING,
            eventCount = 1,
            nextSequenceNumber = 2,
            lastEvents = mapOf(event.collectorId to event),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ResearchExport.encrypt(
                    ExportSnapshot(configuration, metadata, 10_000, fromSequence = 1, toSequence = 9),
                    SnapshotStore(metadata, listOf(event)),
                    ByteArrayOutputStream(),
                )
            }
        }
        Unit
    }

    @Test
    fun aBudgetStopsAtAnEventBoundaryAndTheReceiptSaysWhere() = runBlocking {
        val keys = HpkeCrypto.generateKeyset()
        val configuration = configuration(keys.publicKeysetJson)
        val time = ResearchTime(1_000, 2_000, "boot-test")
        val events = (1..5_000).map {
            RecordedEvent(it.toLong(), "app_lifecycle.v1", 1, time, "EVENT", mapOf("activity_class" to "x".repeat(200)))
        }
        val metadata = StudyMetadata.initial("export-test", "export-config").copy(
            state = ExperimentState.RUNNING,
            eventCount = 5_000,
            nextSequenceNumber = 5_001,
            lastEvents = mapOf(events.last().collectorId to events.last()),
        )
        val destination = ByteArrayOutputStream()

        // Ask for everything, with a budget far below what everything would need.
        val receipt = ResearchExport.encrypt(
            ExportSnapshot(configuration, metadata, 10_000, maximumPlaintextBytes = 64 * 1024),
            SnapshotStore(metadata, events),
            destination,
        )

        assertTrue("expected the budget to stop it short", receipt.sequenceBoundary < 5_000)
        assertTrue("expected real progress", receipt.sequenceBoundary > 0)
        // Stopping short must still produce a complete, decryptable bundle rather than a truncated
        // one, and it must declare the window it actually holds.
        val plaintext = ResearchExport.decrypt(destination.toByteArray(), keys.privateKeysetJson, configuration)
            .toString(Charsets.UTF_8)
        assertTrue(plaintext.contains("\"last_sequence_number\":${receipt.sequenceBoundary}"))
        assertTrue(plaintext.contains("\"sequence_number\":${receipt.sequenceBoundary}"))
        assertFalse(plaintext.contains("\"sequence_number\":${receipt.sequenceBoundary + 1}"))
    }

    @Test
    fun noBudgetSendsEverythingAsked() = runBlocking {
        val keys = HpkeCrypto.generateKeyset()
        val configuration = configuration(keys.publicKeysetJson)
        val time = ResearchTime(1_000, 2_000, "boot-test")
        val events = (1..2_000).map {
            RecordedEvent(it.toLong(), "app_lifecycle.v1", 1, time, "EVENT", mapOf("activity_class" to "x".repeat(200)))
        }
        val metadata = StudyMetadata.initial("export-test", "export-config").copy(
            state = ExperimentState.RUNNING,
            eventCount = 2_000,
            nextSequenceNumber = 2_001,
            lastEvents = mapOf(events.last().collectorId to events.last()),
        )

        // A participant export passes no budget, so their copy is complete however large it gets.
        val receipt = ResearchExport.encrypt(
            ExportSnapshot(configuration, metadata, 10_000),
            SnapshotStore(metadata, events),
            ByteArrayOutputStream(),
        )

        assertEquals(2_000L, receipt.sequenceBoundary)
        assertEquals(2_000L, receipt.eventCount)
    }

    @Test
    fun streamingDecryptStillRefusesATamperedBundle() = runBlocking {
        val keys = HpkeCrypto.generateKeyset()
        val configuration = configuration(keys.publicKeysetJson)
        val time = ResearchTime(1_000, 2_000, "boot-test")
        val event = RecordedEvent(1, "app_lifecycle.v1", 1, time, "ONLY", emptyMap())
        val metadata = StudyMetadata.initial("export-test", "export-config").copy(
            state = ExperimentState.RUNNING,
            eventCount = 1,
            nextSequenceNumber = 2,
            lastEvents = mapOf(event.collectorId to event),
        )
        val destination = ByteArrayOutputStream()
        ResearchExport.encrypt(
            ExportSnapshot(configuration, metadata, 10_000),
            SnapshotStore(metadata, listOf(event)),
            destination,
        )
        // Reading in chunks must not turn an authentication failure into a silently short file.
        val tampered = destination.toByteArray().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            ResearchExport.decrypt(tampered, keys.privateKeysetJson, configuration)
        }
        Unit
    }

    private class SnapshotStore(
        private var metadata: StudyMetadata,
        events: List<RecordedEvent>,
        private val appendDuringRead: RecordedEvent? = null,
    ) : StudyStore {
        private val storedEvents = events.toMutableList()

        override suspend fun loadMetadata(): StudyMetadata = metadata
        override suspend fun initialize(metadata: StudyMetadata) { this.metadata = metadata }
        override suspend fun saveMetadata(metadata: StudyMetadata) { this.metadata = metadata }
        override suspend fun appendEvent(event: RecordedEvent) { storedEvents += event }
        override suspend fun readEvents(
            fromSequenceInclusive: Long,
            upToSequenceInclusive: Long,
            consume: (RecordedEvent) -> Unit,
        ) {
            storedEvents.takeWhile { it.sequenceNumber <= upToSequenceInclusive }
                .filter { it.sequenceNumber >= fromSequenceInclusive }
                .forEachIndexed { index, event ->
                    consume(event)
                    if (index == 0) appendDuringRead?.let(storedEvents::add)
                }
        }
        override suspend fun storageUsage() = StorageUsage(storedEvents.size.toLong(), 16_777_216)
        override suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long) = metadata
        override suspend fun clear() { storedEvents.clear() }
    }

    private fun configuration(publicKeyset: String) = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = "export-test",
        configurationId = "export-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        minimumAppVersion = 1,
        title = "Export test",
        researcherName = "Export researcher",
        researcherContact = "export@example.invalid",
        purpose = "Test export encryption.",
        durationHours = 1,
        consentDocumentVersion = "v1",
        consentSummary = "Export test consent.",
        collectors = listOf(AppLifecycleConfiguration(true)),
        prompts = emptyList(),
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", TEST_SIGNER_PUBLIC_KEY),
        export = ExportConfiguration("export-key", publicKeyset),
        upload = null,
    )
}

private const val TEST_SIGNER_PUBLIC_KEY =
    "MCowBQYDK2VwAyEAsRSaTpZmTSBL7eN6nS/HBsNmLM8n1hdRmIt1vtLZsC0="
