package cool.jacoblin.particeps.core.export

import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ObservationAdmissionKind
import cool.jacoblin.particeps.core.model.SourceObservation
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceObservationEventOrderTest {
    @Test
    fun ordinaryCommitKeepsManifestAndEventRangeOrderIdentical() {
        requireCanonicalSourceObservationEventOrder(
            listOf(observation(1, 0, 5), observation(2, 1, 6)),
            consumedPendingInputSha256 = null,
        )
    }

    @Test
    fun pendingBarrierKeepsCausalOrdinalFirstAndMovesItsEventRangeLast() {
        requireCanonicalSourceObservationEventOrder(
            listOf(
                observation(1, 0, 6),
                observation(2, 1, 5, ObservationAdmissionKind.BARRIER_FLUSH),
            ),
            consumedPendingInputSha256 = "f".repeat(64),
        )
    }

    @Test
    fun pendingDigestDoesNotAuthorizeAnArbitraryManifestPermutation() {
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalSourceObservationEventOrder(
                listOf(
                    observation(1, 0, 7),
                    observation(2, 1, 6),
                    observation(3, 2, 5, ObservationAdmissionKind.BARRIER_FLUSH),
                ),
                consumedPendingInputSha256 = "f".repeat(64),
            )
        }
    }

    private fun observation(
        sequence: Long,
        ordinal: Long,
        eventSequence: Long,
        kind: ObservationAdmissionKind = ObservationAdmissionKind.NORMAL,
    ) = SourceObservation(
        observationSequence = sequence,
        sourceId = EventSourceId("usage_events.v1"),
        schemaVersion = 1,
        resourceGeneration = 1,
        admissionKind = kind,
        producerOrdinal = ordinal,
        conditionEpochId = EPOCH,
        eventCount = 1,
        firstEventSequence = eventSequence,
        lastEventSequence = eventSequence,
        coverage = null,
        encodedSha256 = "0".repeat(64),
    )

    private companion object {
        val EPOCH = ConditionEpochId("123e4567-e89b-42d3-a456-426614174010")
    }
}
