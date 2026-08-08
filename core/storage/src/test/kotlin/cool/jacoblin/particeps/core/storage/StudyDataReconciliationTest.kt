package cool.jacoblin.particeps.core.storage

import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyDataReconciliationTest {
    @Test
    fun interruptedEvictionFloorIsAdoptedBeforeTheNextAppend() {
        val first = event(1)
        val stored = StudyMetadata.initial("reconcile-test", "reconcile-config")
            .withEvent(first)
            .copy(
                state = ExperimentState.RUNNING,
                uploadedThroughSequence = 1,
                retainedFromSequence = 2,
            )

        val reconciled = StudyDataJsonCodec.reconcileMetadata(stored, 1, 1)
        assertEquals(1L, reconciled.retainedFromSequence)

        val second = event(2)
        val successor = reconciled.withEvent(second)
        val failClosed = ExperimentStateMachine().transition(
            successor,
            ExperimentState.PAUSED,
            TransitionReason.STORAGE_FAILURE,
            ResearchTime(10_000, 10_000, "boot-test"),
        )
        val recovered = AppendTransactionRecovery.recover(reconciled, failClosed, 2, second)
        assertEquals(failClosed, recovered.metadata)
        assertTrue(recovered.rewriteMetadata)
    }

    @Test
    fun missingPrefixBelowThePersistedFloorFailsClosed() {
        val stored = StudyMetadata.initial("reconcile-test", "reconcile-config")
            .withEvent(event(1))
            .withEvent(event(2))

        assertThrows(IllegalArgumentException::class.java) {
            StudyDataJsonCodec.reconcileMetadata(stored, 2, 2)
        }
    }

    private fun StudyMetadata.withEvent(event: RecordedEvent) = copy(
        eventCount = event.sequenceNumber,
        nextSequenceNumber = event.sequenceNumber + 1,
        lastEvents = lastEvents + (event.collectorId to event),
    )

    private fun event(sequence: Long) = RecordedEvent(
        sequenceNumber = sequence,
        collectorId = "battery_state.v1",
        payloadSchemaVersion = 1,
        observedTime = ResearchTime(sequence, sequence, "boot-test"),
        payloadType = "BATTERY_STATE",
        fields = emptyMap(),
    )
}
