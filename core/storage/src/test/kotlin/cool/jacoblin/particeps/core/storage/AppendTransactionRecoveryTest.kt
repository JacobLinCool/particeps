package cool.jacoblin.particeps.core.storage

import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppendTransactionRecoveryTest {
    @Test
    fun crashAfterEventFsyncRecoversTheEventAndAtomicOccurrenceSideEffect() {
        val main = initial()
        val event = event(1, "battery_state.v1")
        val occurrence = occurrence()
        val successor = main.withEvent(event).copy(
            occurrences = mapOf(occurrence.occurrenceId to occurrence),
        )
        val transaction = successor.failClosed()

        val result = AppendTransactionRecovery.recover(main, transaction, 1, event)

        assertEquals(transaction, result.metadata)
        assertTrue(result.rewriteMetadata)
    }

    @Test
    fun crashAfterJournalWriteFailsClosedWithoutInventingAnEvent() {
        val main = initial()
        val transaction = main.withEvent(event(1, "battery_state.v1")).failClosed()

        val result = AppendTransactionRecovery.recover(main, transaction, 0, null)

        assertEquals(ExperimentState.PAUSED, result.metadata.state)
        assertEquals(TransitionReason.STORAGE_FAILURE, result.metadata.transitions.last().reason)
        assertEquals(0, result.metadata.eventCount)
        assertTrue(result.rewriteMetadata)
    }

    @Test
    fun crashAfterMainCommitButBeforeJournalRetirementStillFailsClosed() {
        val committed = initial().withEvent(event(1, "battery_state.v1"))
        val transaction = committed.failClosed()

        val result = AppendTransactionRecovery.recover(
            committed,
            transaction,
            committed.eventCount,
            committed.lastEvents.values.single(),
        )

        assertEquals(transaction, result.metadata)
        assertTrue(result.rewriteMetadata)
    }

    @Test
    fun laterDurableMetadataProvesAnUnretiredJournalIsOnlyCleanupResidue() {
        val committed = initial().withEvent(event(1, "battery_state.v1"))
        val transaction = committed.failClosed()
        val occurrence = occurrence()
        val newerMain = committed.copy(occurrences = mapOf(occurrence.occurrenceId to occurrence))

        val result = AppendTransactionRecovery.recover(
            newerMain,
            transaction,
            newerMain.eventCount,
            committed.lastEvents.values.single(),
        )

        assertEquals(newerMain, result.metadata)
        assertFalse(result.rewriteMetadata)
    }

    @Test
    fun sameBoundaryJournalMustStillBelongToTheSameStudyIdentity() {
        val main = initial().withEvent(event(1, "battery_state.v1"))
        val wrongConfiguration = main.failClosed().copy(configurationId = "another-config")

        assertThrows(IllegalArgumentException::class.java) {
            AppendTransactionRecovery.recover(main, wrongConfiguration, main.eventCount, null)
        }
    }

    @Test
    fun refusesTailWithoutJournalBecauseMetadataSideEffectsCannotBeReconstructed() {
        assertThrows(IllegalArgumentException::class.java) {
            AppendTransactionRecovery.recover(initial(), null, 1, null)
        }
    }

    @Test
    fun refusesStaleOrMismatchedLatestEventMaps() {
        val main = initial().withEvent(event(1, "battery_state.v1"))
        val appended = event(2, "temporal_context.v1")
        val staleTransaction = main.copy(
            eventCount = 2,
            nextSequenceNumber = 3,
            lastEvents = main.lastEvents + ("temporal_context.v1" to appended.copy(sequenceNumber = 1)),
        ).failClosed()
        assertThrows(IllegalArgumentException::class.java) {
            AppendTransactionRecovery.recover(main, staleTransaction, 2, appended)
        }

        val wrongTail = appended.copy(payloadType = "OTHER")
        assertThrows(IllegalArgumentException::class.java) {
            AppendTransactionRecovery.recover(main, main.withEvent(appended).failClosed(), 2, wrongTail)
        }
    }

    private fun initial() = StudyMetadata.initial("recovery-test", "recovery-config")
        .copy(state = ExperimentState.RUNNING)

    private fun StudyMetadata.failClosed() = ExperimentStateMachine().transition(
        this,
        ExperimentState.PAUSED,
        TransitionReason.STORAGE_FAILURE,
        ResearchTime(10_000, 10_000, "boot-test"),
    )

    private fun StudyMetadata.withEvent(event: RecordedEvent) = copy(
        eventCount = event.sequenceNumber,
        nextSequenceNumber = event.sequenceNumber + 1,
        lastEvents = lastEvents + (event.collectorId to event),
    )

    private fun event(sequence: Long, collectorId: String) = RecordedEvent(
        sequenceNumber = sequence,
        collectorId = collectorId,
        payloadSchemaVersion = 1,
        observedTime = ResearchTime(sequence, sequence, "boot-test"),
        payloadType = "TEST_EVENT",
        fields = emptyMap(),
    )

    private fun occurrence() = InterventionOccurrence(
        occurrenceId = "a".repeat(64),
        interventionId = "daily-ema",
        triggerId = "random-window",
        scheduleKey = "random:2026-08-04:morning",
        scheduledFor = ResearchTime(1_000, 1_000, "boot-test"),
        expiresAtUtcMillis = 2_000,
        state = OccurrenceState.SCHEDULED,
    )
}
