package cool.jacoblin.particeps.core.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStoreMutationFailedClosed
import cool.jacoblin.particeps.core.model.TransitionReason
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
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
    fun partialEvictionCleanupCannotRollBackTheCommittedFloorOrCreateAGap() = runBlocking {
        val partialExperimentId = "$experimentId-partial-eviction"
        var deleteAttempts = 0
        val partialStore = EncryptedExperimentStore(
            context = context,
            experimentId = partialExperimentId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = { file ->
                deleteAttempts += 1
                deleteAttempts != 2 && file.delete()
            },
        )
        try {
            partialStore.clear()
            partialStore.initialize(
                StudyMetadata.initial(partialExperimentId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            val padding = "x".repeat(PADDING_BYTES)
            (1L..THREE_SEGMENTS_EVENT_COUNT).forEach { sequence ->
                partialStore.appendEvent(event(sequence, mapOf("activity_class" to padding)))
            }
            val before = requireNotNull(partialStore.loadMetadata())
            val delivered = before.copy(uploadedThroughSequence = before.eventCount)
            partialStore.saveMetadata(delivered)

            val committed = partialStore.evictThrough(delivered, targetBytes = 0)

            assertTrue("test requires a partial unlink", deleteAttempts >= 2)
            assertTrue("the logical floor must commit before cleanup", committed.retainedFromSequence > 1)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { partialStore.saveMetadata(delivered) }
            }

            // The failed unlink stops later deletion, so reload sees a contiguous suffix and may
            // conservatively lower the logical floor to the first segment that still exists.
            val reloaded = requireNotNull(partialStore.loadMetadata())
            assertTrue(reloaded.retainedFromSequence in 2..committed.retainedFromSequence)
            val events = mutableListOf<RecordedEvent>()
            partialStore.readEvents(
                reloaded.retainedFromSequence,
                reloaded.eventCount,
                events::add,
            )
            assertEquals(
                (reloaded.retainedFromSequence..reloaded.eventCount).toList(),
                events.map(RecordedEvent::sequenceNumber),
            )
        } finally {
            partialStore.clear()
        }
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

    @Test
    fun metadataRenameFailureDoesNotAdvanceAuthoritativeInMemoryMetadata() = runBlocking {
        val faultId = "$experimentId-metadata-fault"
        val operations = TargetedFaultFileSystem()
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            fileSystem = operations,
        )
        try {
            faultStore.clear()
            val initial = StudyMetadata.initial(faultId, "encrypted-store-config")
                .copy(state = ExperimentState.RUNNING)
            faultStore.initialize(initial)
            operations.renameFailureSuffix = ".metadata.ptc"

            assertThrows(IOException::class.java) {
                runBlocking {
                    faultStore.saveMetadata(initial.copy(state = ExperimentState.PAUSED))
                }
            }

            operations.renameFailureSuffix = null
            val reopened = EncryptedExperimentStore(
                context = context,
                experimentId = faultId,
                maximumLocalBytes = QUOTA_BYTES,
                deleteSegment = File::delete,
                fileSystem = operations,
            )
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }

            // An explicit new store mutation is the only operation allowed to retire the pending
            // evidence. It must use the old authoritative in-memory metadata, not the failed copy.
            faultStore.appendEvent(event(1))
            assertEquals(
                ExperimentState.RUNNING,
                requireNotNull(faultStore.loadMetadata()).state,
            )
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
    }

    @Test
    fun journalRenameFailureDoesNotAdvanceTheSequenceBoundary() = runBlocking {
        val faultId = "$experimentId-journal-fault"
        val operations = TargetedFaultFileSystem()
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            fileSystem = operations,
        )
        try {
            faultStore.clear()
            faultStore.initialize(
                StudyMetadata.initial(faultId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            operations.renameFailureSuffix = ".transaction.ptc"

            assertThrows(IOException::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }

            operations.renameFailureSuffix = null
            val reopened = EncryptedExperimentStore(
                context = context,
                experimentId = faultId,
                maximumLocalBytes = QUOTA_BYTES,
                deleteSegment = File::delete,
                fileSystem = operations,
            )
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
        Unit
    }

    @Test
    fun rc5FrameworkAtomicFileMetadataResidueBlocksRecovery() = runBlocking {
        val legacyId = "$experimentId-rc5-metadata"
        val legacyStore = EncryptedExperimentStore(context, legacyId, QUOTA_BYTES)
        try {
            legacyStore.clear()
            legacyStore.initialize(
                StudyMetadata.initial(legacyId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            val metadata = experimentFile(legacyId, ".metadata.ptc")
            requireNotNull(metadata.parentFile)
                .resolve("${metadata.name}.new")
                .writeText("unresolved rc.5 metadata")

            val reopened = EncryptedExperimentStore(context, legacyId, QUOTA_BYTES)
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }
        } finally {
            legacyStore.clear()
        }
        Unit
    }

    @Test
    fun rc5FrameworkAtomicFileJournalResidueBlocksRecovery() = runBlocking {
        val legacyId = "$experimentId-rc5-journal"
        val legacyStore = EncryptedExperimentStore(context, legacyId, QUOTA_BYTES)
        try {
            legacyStore.clear()
            legacyStore.initialize(
                StudyMetadata.initial(legacyId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            val metadata = experimentFile(legacyId, ".metadata.ptc")
            requireNotNull(metadata.parentFile)
                .resolve(metadata.name.replace(".metadata.ptc", ".transaction.ptc.new"))
                .writeText("unresolved rc.5 journal")

            val reopened = EncryptedExperimentStore(context, legacyId, QUOTA_BYTES)
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }
        } finally {
            legacyStore.clear()
        }
        Unit
    }

    @Test
    fun metadataPostRenameDirectorySyncFailureCannotReopenRunning() = runBlocking {
        val faultId = "$experimentId-metadata-commit-sync"
        val operations = TargetedFaultFileSystem()
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            fileSystem = operations,
        )
        try {
            faultStore.clear()
            val ready = StudyMetadata.initial(faultId, "encrypted-store-config")
                .copy(state = ExperimentState.READY)
            faultStore.initialize(ready)
            operations.failRootDirectorySyncAt = operations.rootDirectorySyncAttempts + 2

            assertThrows(IOException::class.java) {
                runBlocking { faultStore.saveMetadata(ready.copy(state = ExperimentState.RUNNING)) }
            }

            operations.failRootDirectorySyncAt = null
            val reopened = EncryptedExperimentStore(
                context = context,
                experimentId = faultId,
                maximumLocalBytes = QUOTA_BYTES,
                deleteSegment = File::delete,
                fileSystem = operations,
            )
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
        Unit
    }

    @Test
    fun fullVisibleEventAfterSyncFailureRecoversDurablyPaused() = runBlocking {
        verifyUnacknowledgedEventRecovery(writeCompleteFrame = true, resumeAndAppend = false)
    }

    @Test
    fun partialEventAfterWriteFailureRecoversDurablyPausedWithoutInventingTheEvent() = runBlocking {
        verifyUnacknowledgedEventRecovery(writeCompleteFrame = false, resumeAndAppend = false)
    }

    @Test
    fun sameProcessFullEventFailureCanResumeWithoutReusingItsSequence() = runBlocking {
        verifyUnacknowledgedEventRecovery(writeCompleteFrame = true, resumeAndAppend = true)
    }

    @Test
    fun sameProcessPartialEventFailureCanResumeWithoutReusingItsSequence() = runBlocking {
        verifyUnacknowledgedEventRecovery(writeCompleteFrame = false, resumeAndAppend = true)
    }

    @Test
    fun retainedAppendJournalPreservesFirstSafetyReasonAcrossTwoProcessDeaths() = runBlocking {
        val faultId = "$experimentId-double-reopen-first-reason"
        val initial = StudyMetadata.initial(faultId, "encrypted-store-config")
            .copy(state = ExperimentState.RUNNING)
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            appendFrame = { file, frame ->
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(output.length())
                    output.write(frame)
                }
                throw IOException("injected event acknowledgement failure")
            },
        )
        try {
            faultStore.clear()
            faultStore.initialize(initial)
            assertThrows(StudyStoreMutationFailedClosed::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }

            val firstProcess = EncryptedExperimentStore(context, faultId, QUOTA_BYTES)
            assertEquals(TransitionReason.STORAGE_FAILURE, firstProcess.loadMetadata()?.transitions?.last()?.reason)
            val secondProcess = EncryptedExperimentStore(context, faultId, QUOTA_BYTES)
            assertEquals(TransitionReason.STORAGE_FAILURE, secondProcess.loadMetadata()?.transitions?.last()?.reason)
            val resolved = requireNotNull(
                secondProcess.resolvePendingAppendFailure(TransitionReason.REQUIRED_ACCESS_MISSING),
            )
            assertEquals(TransitionReason.REQUIRED_ACCESS_MISSING, resolved.transitions.last().reason)

            val finalProcess = EncryptedExperimentStore(context, faultId, QUOTA_BYTES)
            assertEquals(
                TransitionReason.REQUIRED_ACCESS_MISSING,
                requireNotNull(finalProcess.loadMetadata()).transitions.last().reason,
            )
        } finally {
            faultStore.clear()
        }
        Unit
    }

    @Test
    fun failedInlineRecoveryKeepsEveryLaterMutationClosed() = runBlocking {
        val faultId = "$experimentId-double-append-failure"
        val initial = StudyMetadata.initial(faultId, "encrypted-store-config")
            .copy(state = ExperimentState.RUNNING)
        val operations = TargetedFaultFileSystem()
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            fileSystem = operations,
            appendFrame = { file, frame ->
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(output.length())
                    output.write(frame.copyOf(frame.size / 2))
                }
                operations.failRootDirectorySyncAt = operations.rootDirectorySyncAttempts + 1
                throw IOException("injected event write failure")
            },
        )
        try {
            faultStore.clear()
            faultStore.initialize(initial)
            assertThrows(IOException::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }
            operations.clearFailures()

            val requestedPause = ExperimentStateMachine().transition(
                initial,
                ExperimentState.PAUSED,
                TransitionReason.STORAGE_FAILURE,
                ResearchTime(9_000, 9_000, "boot-test"),
            )
            val blockedSave = assertThrows(IllegalStateException::class.java) {
                runBlocking { faultStore.saveMetadata(requestedPause) }
            }
            assertTrue(blockedSave.message.orEmpty().contains("requires fail-closed recovery"))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }

            val reopened = EncryptedExperimentStore(
                context = context,
                experimentId = faultId,
                maximumLocalBytes = QUOTA_BYTES,
                deleteSegment = File::delete,
                fileSystem = operations,
            )
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
        Unit
    }

    @Test
    fun evictionMetadataFailureCannotStartSegmentUnlink() = runBlocking {
        val faultId = "$experimentId-eviction-fault"
        val operations = TargetedFaultFileSystem()
        var deleteAttempts = 0
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = { file ->
                deleteAttempts += 1
                file.delete()
            },
            fileSystem = operations,
        )
        try {
            faultStore.clear()
            faultStore.initialize(
                StudyMetadata.initial(faultId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            val padding = "x".repeat(PADDING_BYTES)
            (1L..THREE_SEGMENTS_EVENT_COUNT).forEach { sequence ->
                faultStore.appendEvent(event(sequence, mapOf("activity_class" to padding)))
            }
            val before = requireNotNull(faultStore.loadMetadata())
            val delivered = before.copy(uploadedThroughSequence = before.eventCount)
            faultStore.saveMetadata(delivered)
            operations.renameFailureSuffix = ".metadata.ptc"

            assertThrows(IOException::class.java) {
                runBlocking { faultStore.evictThrough(delivered, targetBytes = 0) }
            }

            assertEquals(0, deleteAttempts)
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
        Unit
    }

    @Test
    fun newEventSegmentRequiresDirectoryFsyncBeforeAppendCanSucceed() = runBlocking {
        val faultId = "$experimentId-segment-directory-fault"
        val operations = TargetedFaultFileSystem()
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            fileSystem = operations,
        )
        try {
            faultStore.clear()
            faultStore.initialize(
                StudyMetadata.initial(faultId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            operations.failEventDirectorySync = true

            assertThrows(IOException::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }

            assertTrue(operations.eventDirectorySyncAttempts > 0)
            operations.failEventDirectorySync = false
            val reopened = EncryptedExperimentStore(
                context = context,
                experimentId = faultId,
                maximumLocalBytes = QUOTA_BYTES,
                deleteSegment = File::delete,
                fileSystem = operations,
            )
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
        Unit
    }

    @Test
    fun incompleteSegmentRenameEvidenceBlocksProcessDeathRecovery() = runBlocking {
        val faultId = "$experimentId-segment-rename-fault"
        val operations = TargetedFaultFileSystem()
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            fileSystem = operations,
        )
        try {
            faultStore.clear()
            faultStore.initialize(
                StudyMetadata.initial(faultId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            operations.renameFailureSuffix = ".ptcs"

            assertThrows(IOException::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }

            operations.renameFailureSuffix = null
            val reopened = EncryptedExperimentStore(
                context = context,
                experimentId = faultId,
                maximumLocalBytes = QUOTA_BYTES,
                deleteSegment = File::delete,
                fileSystem = operations,
            )
            assertThrows(IncompleteAtomicWrite::class.java) {
                runBlocking { reopened.loadMetadata() }
            }
            Unit
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
    }

    @Test
    fun unlistableEventDirectoryBlocksFreshRecovery() = runBlocking {
        val faultId = "$experimentId-listing-fault"
        val operations = TargetedFaultFileSystem()
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            fileSystem = operations,
        )
        try {
            faultStore.clear()
            faultStore.initialize(
                StudyMetadata.initial(faultId, "encrypted-store-config")
                    .copy(state = ExperimentState.RUNNING),
            )
            faultStore.appendEvent(event(1))
            operations.failEventDirectoryListing = true

            val reopened = EncryptedExperimentStore(
                context = context,
                experimentId = faultId,
                maximumLocalBytes = QUOTA_BYTES,
                deleteSegment = File::delete,
                fileSystem = operations,
            )
            assertThrows(IllegalStateException::class.java) {
                runBlocking { reopened.loadMetadata() }
            }
            Unit
        } finally {
            operations.clearFailures()
            faultStore.clear()
        }
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

    private fun experimentFile(experimentId: String, suffix: String): File {
        val opaqueId = MessageDigest.getInstance("SHA-256")
            .digest(experimentId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return context.noBackupFilesDir.resolve("experiments/$opaqueId$suffix")
    }

    private suspend fun verifyUnacknowledgedEventRecovery(
        writeCompleteFrame: Boolean,
        resumeAndAppend: Boolean,
    ) {
        val suffix = when {
            resumeAndAppend && writeCompleteFrame -> "same-process-full-resume"
            resumeAndAppend -> "same-process-partial-resume"
            writeCompleteFrame -> "full-event-sync"
            else -> "partial-event-write"
        }
        val faultId = "$experimentId-$suffix"
        val initial = StudyMetadata.initial(faultId, "encrypted-store-config")
            .copy(state = ExperimentState.RUNNING)
        var failNextAppend = true
        val faultStore = EncryptedExperimentStore(
            context = context,
            experimentId = faultId,
            maximumLocalBytes = QUOTA_BYTES,
            deleteSegment = File::delete,
            appendFrame = { file, frame ->
                if (failNextAppend) {
                    failNextAppend = false
                    RandomAccessFile(file, "rw").use { output ->
                        output.seek(output.length())
                        output.write(if (writeCompleteFrame) frame else frame.copyOf(frame.size / 2))
                    }
                    throw IOException("injected event acknowledgement failure")
                }
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(output.length())
                    output.write(frame)
                    output.fd.sync()
                }
            },
        )
        try {
            faultStore.clear()
            faultStore.initialize(initial)
            assertThrows(StudyStoreMutationFailedClosed::class.java) {
                runBlocking { faultStore.appendEvent(event(1)) }
            }

            if (resumeAndAppend) {
                val failClosed = requireNotNull(
                    faultStore.resolvePendingAppendFailure(TransitionReason.STORAGE_FAILURE),
                )
                assertEquals(ExperimentState.PAUSED, failClosed.state)
                assertEquals(TransitionReason.STORAGE_FAILURE, failClosed.transitions.last().reason)
                assertEquals(if (writeCompleteFrame) 1L else 0L, failClosed.eventCount)
                val resumed = ExperimentStateMachine().transition(
                    failClosed,
                    ExperimentState.RUNNING,
                    TransitionReason.PARTICIPANT_RESUMED,
                    ResearchTime(10_000, 10_000, "boot-test"),
                )
                faultStore.saveMetadata(resumed)
                faultStore.appendEvent(event(resumed.nextSequenceNumber))
            }

            val reopened = EncryptedExperimentStore(context, faultId, QUOTA_BYTES)
            val recovered = requireNotNull(reopened.loadMetadata())
            val firstBoundary = if (writeCompleteFrame) 1L else 0L
            val expectedBoundary = firstBoundary + if (resumeAndAppend) 1L else 0L
            assertEquals(if (resumeAndAppend) ExperimentState.RUNNING else ExperimentState.PAUSED, recovered.state)
            assertEquals(expectedBoundary, recovered.eventCount)
            if (!resumeAndAppend) {
                assertEquals(TransitionReason.STORAGE_FAILURE, recovered.transitions.last().reason)
            }
            if (expectedBoundary > 0) {
                val events = mutableListOf<RecordedEvent>()
                reopened.readEvents(1, expectedBoundary, events::add)
                assertEquals((1L..expectedBoundary).toList(), events.map(RecordedEvent::sequenceNumber))
            }
        } finally {
            faultStore.clear()
        }
    }

    private fun segmentFiles() = storageFiles().filter { it.name.endsWith(".ptcs") }

    private class TargetedFaultFileSystem : AcknowledgedFileSystem {
        var renameFailureSuffix: String? = null
        var failEventDirectorySync = false
        var failEventDirectoryListing = false
        var eventDirectorySyncAttempts = 0
        var rootDirectorySyncAttempts = 0
        var failRootDirectorySyncAt: Int? = null

        fun clearFailures() {
            renameFailureSuffix = null
            failEventDirectorySync = false
            failEventDirectoryListing = false
            failRootDirectorySyncAt = null
        }

        override fun exists(file: File): Boolean = AndroidAcknowledgedFileSystem.exists(file)

        override fun isDirectory(file: File): Boolean =
            AndroidAcknowledgedFileSystem.isDirectory(file)

        override fun listFiles(directory: File): Array<File>? =
            if (failEventDirectoryListing && directory.name.endsWith(".events")) {
                null
            } else {
                AndroidAcknowledgedFileSystem.listFiles(directory)
            }

        override fun ensureDirectory(directory: File) =
            AndroidAcknowledgedFileSystem.ensureDirectory(directory)

        override fun openOutput(file: File): FileOutputStream =
            AndroidAcknowledgedFileSystem.openOutput(file)

        override fun syncFile(output: FileOutputStream) =
            AndroidAcknowledgedFileSystem.syncFile(output)

        override fun closeFile(output: FileOutputStream) =
            AndroidAcknowledgedFileSystem.closeFile(output)

        override fun atomicReplace(source: File, target: File) {
            if (renameFailureSuffix?.let(target.name::endsWith) == true) {
                throw IOException("injected rename failure for ${target.name}")
            }
            AndroidAcknowledgedFileSystem.atomicReplace(source, target)
        }

        override fun readFully(file: File): ByteArray =
            AndroidAcknowledgedFileSystem.readFully(file)

        override fun deleteIfExists(file: File) =
            AndroidAcknowledgedFileSystem.deleteIfExists(file)

        override fun syncDirectory(directory: File) {
            if (directory.name == "experiments") {
                rootDirectorySyncAttempts += 1
                if (rootDirectorySyncAttempts == failRootDirectorySyncAt) {
                    throw IOException("injected root-directory fsync failure")
                }
            }
            if (directory.name.endsWith(".events")) {
                eventDirectorySyncAttempts += 1
                if (failEventDirectorySync) {
                    throw IOException("injected event-directory fsync failure")
                }
            }
            AndroidAcknowledgedFileSystem.syncDirectory(directory)
        }
    }

    private companion object {
        const val QUOTA_BYTES = 64L * 1024 * 1024
        const val PADDING_BYTES = 60 * 1024

        /** Enough 60 KiB events to fill three 4 MiB segments with room to spare. */
        const val THREE_SEGMENTS_EVENT_COUNT = 180L
    }
}
