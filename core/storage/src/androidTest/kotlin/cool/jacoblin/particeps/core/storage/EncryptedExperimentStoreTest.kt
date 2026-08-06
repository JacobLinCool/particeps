package cool.jacoblin.particeps.core.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedExperimentStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val experimentId = "encrypted-store-test"
    private val store = EncryptedExperimentStore(context, experimentId, QUOTA_BYTES)

    @Before
    fun setUp() = runBlocking {
        store.clear()
    }

    @After
    fun tearDown() = runBlocking {
        store.clear()
    }

    @Test
    fun recordRoundTripsWithoutPlaintextOnDisk() = runBlocking {
        val sentinel = "SENSITIVE_LIFECYCLE_SENTINEL"
        val event = event(1, fields = mapOf("activity_class" to sentinel))
        val initial = runningMetadata()
        val expected = initial.copy(
            eventCount = 1,
            nextSequenceNumber = 2,
            lastEvents = mapOf(event.collectorId to event),
        )

        store.initialize(initial)
        store.appendEvent(event)

        val storageFiles = storageFiles()
        assertEquals(2, storageFiles.size)
        assertFalse(storageFiles.any { it.readBytes().toString(Charsets.UTF_8).contains(sentinel) })
        assertEquals(expected, store.loadMetadata())
        assertEquals(listOf(event), readAll(1, 1))
        assertNotNull(store.loadMetadata())
    }

    @Test
    fun eventsRollOverIntoMultipleSegmentsAndScanBackAsOneSequence() = runBlocking {
        store.initialize(runningMetadata())
        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)

        assertTrue("expected rollover", segmentFiles().size >= 3)
        val reloaded = requireNotNull(store.loadMetadata())
        assertEquals(THREE_SEGMENTS_EVENT_COUNT, reloaded.eventCount)
        assertEquals(1L, reloaded.retainedFromSequence)
        assertEquals(
            (1L..THREE_SEGMENTS_EVENT_COUNT).toList(),
            readAll(1, THREE_SEGMENTS_EVENT_COUNT).map { it.sequenceNumber },
        )
    }

    @Test
    fun reclaimingDropsDeliveredSegmentsAndTheStoreReloadsFromTheNewFloor() = runBlocking {
        store.initialize(runningMetadata())
        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)
        val before = requireNotNull(store.loadMetadata())
        val segmentsBefore = segmentFiles().size

        // Everything is confirmed, so only the pin rules limit what can go.
        val delivered = before.copy(uploadedThroughSequence = before.eventCount)
        store.saveMetadata(delivered)
        val after = store.evictThrough(delivered, targetBytes = 0)

        assertTrue("expected segments to be reclaimed", segmentFiles().size < segmentsBefore)
        assertTrue("expected the floor to advance", after.retainedFromSequence > 1)
        // The lifetime counter must never rewind: those sequence numbers are already at the endpoint.
        assertEquals(before.eventCount, after.eventCount)
        assertEquals(before.nextSequenceNumber, after.nextSequenceNumber)

        val reloaded = requireNotNull(store.loadMetadata())
        assertEquals(after.retainedFromSequence, reloaded.retainedFromSequence)
        assertEquals(before.eventCount, reloaded.eventCount)
        assertEquals(
            (after.retainedFromSequence..before.eventCount).toList(),
            readAll(after.retainedFromSequence, before.eventCount).map { it.sequenceNumber },
        )
    }

    @Test
    fun appendingAfterReclaimContinuesTheSequenceWithoutReuse() = runBlocking {
        store.initialize(runningMetadata())
        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)
        val before = requireNotNull(store.loadMetadata())
        store.saveMetadata(before.copy(uploadedThroughSequence = before.eventCount))
        val after = store.evictThrough(
            before.copy(uploadedThroughSequence = before.eventCount),
            targetBytes = 0,
        )

        val next = after.nextSequenceNumber
        store.appendEvent(event(next))

        val reloaded = requireNotNull(store.loadMetadata())
        assertEquals(next, reloaded.eventCount)
        assertEquals(next + 1, reloaded.nextSequenceNumber)
        assertEquals(next, readAll(after.retainedFromSequence, next).last().sequenceNumber)
    }

    @Test
    fun reclaimedEventsCannotBeReadBack() = runBlocking {
        store.initialize(runningMetadata())
        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)
        val before = requireNotNull(store.loadMetadata())
        store.saveMetadata(before.copy(uploadedThroughSequence = before.eventCount))
        val after = store.evictThrough(
            before.copy(uploadedThroughSequence = before.eventCount),
            targetBytes = 0,
        )
        assertTrue("test needs something reclaimed", after.retainedFromSequence > 1)

        // Asking for a reclaimed prefix must fail loudly rather than return a short read that a
        // caller could mistake for a complete history.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readAll(1, before.eventCount) }
        }
        Unit
    }

    @Test
    fun undeliveredEventsAreNeverReclaimed() = runBlocking {
        store.initialize(runningMetadata())
        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)
        val before = requireNotNull(store.loadMetadata())
        val segmentsBefore = segmentFiles().size

        // Nothing confirmed by an endpoint, so nothing may go even with an unreachable target.
        val after = store.evictThrough(before, targetBytes = 0)

        assertEquals(1L, after.retainedFromSequence)
        assertEquals(segmentsBefore, segmentFiles().size)
    }

    @Test
    fun aStoreUnderItsTargetKeepsEverything() = runBlocking {
        store.initialize(runningMetadata())
        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)
        val before = requireNotNull(store.loadMetadata())
        val delivered = before.copy(uploadedThroughSequence = before.eventCount)
        store.saveMetadata(delivered)

        val after = store.evictThrough(delivered, targetBytes = QUOTA_BYTES)

        assertEquals(1L, after.retainedFromSequence)
    }

    @Test
    fun storageUsageTracksTheQuota() = runBlocking {
        store.initialize(runningMetadata())
        val empty = store.storageUsage()
        assertEquals(QUOTA_BYTES, empty.quotaBytes)

        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)

        assertTrue("usage should grow with appends", store.storageUsage().usedBytes > empty.usedBytes)
    }

    @Test
    fun aMissingPrefixThatWasNotReclaimedRefusesToOpen() = runBlocking {
        store.initialize(runningMetadata())
        appendPadded(1L..THREE_SEGMENTS_EVENT_COUNT)
        assertNotNull(store.loadMetadata())

        // Delete the oldest segment behind the store's back. The floor still says 1, so this is
        // indistinguishable from a prefix being tampered away and must fail closed.
        val oldest = segmentFiles().minByOrNull(File::getName)!!
        assertTrue(oldest.delete())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.loadMetadata() }
        }
        Unit
    }

    @Test
    fun corruptAppendJournalFailsClosedAndIsNotDeleted() = runBlocking {
        store.initialize(runningMetadata())
        val metadata = storageFiles().single { it.name.endsWith(".metadata.ptc") }
        val transaction = requireNotNull(metadata.parentFile).resolve(
            metadata.name.replace(".metadata.ptc", ".transaction.ptc"),
        )
        transaction.writeBytes(byteArrayOf(0x01))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.loadMetadata() }
        }
        assertTrue("a corrupt journal must remain available for diagnosis", transaction.exists())
    }

    private fun runningMetadata() = StudyMetadata.initial(experimentId, "encrypted-store-config")
        .copy(state = ExperimentState.RUNNING)

    private fun event(
        sequence: Long,
        fields: Map<String, String> = mapOf("activity_class" to "cool.jacoblin.Demo"),
    ) = RecordedEvent(
        sequenceNumber = sequence,
        collectorId = "app_lifecycle.v1",
        payloadSchemaVersion = 1,
        observedTime = ResearchTime(1_000 + sequence, 2_000 + sequence, "boot-test"),
        payloadType = "ACTIVITY_RESUMED",
        fields = fields,
    )

    /** Each event carries a large field so a handful of them cross the 4 MiB segment boundary. */
    private suspend fun appendPadded(range: LongRange) {
        val padding = "x".repeat(PADDING_BYTES)
        range.forEach { sequence ->
            store.appendEvent(event(sequence, mapOf("activity_class" to padding)))
        }
    }

    private suspend fun readAll(from: Long, to: Long): List<RecordedEvent> =
        mutableListOf<RecordedEvent>().also { collected ->
            store.readEvents(from, to, collected::add)
        }

    private fun storageFiles() =
        File(context.noBackupFilesDir, "experiments").walkTopDown().filter(File::isFile).toList()

    private fun segmentFiles() = storageFiles().filter { it.name.endsWith(".ptcs") }

    private companion object {
        const val QUOTA_BYTES = 64L * 1024 * 1024
        const val PADDING_BYTES = 60 * 1024

        /** Enough 60 KiB events to fill three 4 MiB segments with room to spare. */
        const val THREE_SEGMENTS_EVENT_COUNT = 180L
    }
}
