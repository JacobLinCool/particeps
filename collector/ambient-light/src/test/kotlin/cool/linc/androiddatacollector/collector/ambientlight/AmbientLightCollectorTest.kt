package cool.linc.androiddatacollector.collector.ambientlight

import cool.linc.androiddatacollector.core.collector.LatestValueRateGate
import cool.linc.androiddatacollector.core.collector.ProtocolEventContracts
import cool.linc.androiddatacollector.core.definition.AmbientLightConfiguration
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientLightCollectorTest {
    @Test
    fun changedOnChangeReadingInsideIntervalIsDeferredWithItsCaptureTime() {
        val gate = LatestValueRateGate<AmbientLightSample>(200) { previous, current ->
            sameAmbientLightSample(previous, current, 1_000)
        }
        val first = requireNotNull(ambientLightSample(10f, 1_000_000_000, 3, time(10)))
        val changed = requireNotNull(ambientLightSample(20f, 1_100_000_000, 3, time(20)))

        assertTrue(gate.offer(first, 1_000) is LatestValueRateGate.Decision.Emit)
        val deferred = gate.offer(changed, 1_100)
        assertTrue(deferred is LatestValueRateGate.Decision.Defer)
        val emitted = gate.poll(1_200)

        require(emitted is LatestValueRateGate.Decision.Emit)
        val event = emitted.value.eventDraft()
        assertEquals(time(20), event.observedTime)
        assertEquals("20.0", event.fields["illuminance_lux"])
        assertEquals("1100000000", event.fields["source_elapsed_realtime_nanos"])
        assertEquals(AmbientLightConfiguration.ID, event.collectorId)
        assertTrue(requireNotNull(ProtocolEventContracts[AmbientLightConfiguration.ID]).accepts(event, 1))
    }

    @Test
    fun thresholdUsesIlluminanceAndAccuracyRidesOnEmittedSamples() {
        val previous = requireNotNull(ambientLightSample(10f, 1, 3, time(1)))
        val belowThreshold = requireNotNull(ambientLightSample(10.5f, 2, 3, time(2)))
        val changedAccuracy = belowThreshold.copy(accuracy = 2)

        assertTrue(sameAmbientLightSample(previous, belowThreshold, 1_000))
        assertTrue(sameAmbientLightSample(previous, changedAccuracy, 1_000))
    }

    @Test
    fun rejectsInvalidPhysicalSamples() {
        assertNull(ambientLightSample(Float.NaN, 1, 0, time(1)))
        assertNull(ambientLightSample(-1f, 1, 0, time(1)))
        assertNull(ambientLightSample(1f, -1, 0, time(1)))
    }

    @Test
    fun processRestartRestoresAmbientValueAndSamplingDeadline() {
        val previous = requireNotNull(
            ambientLightSample(10f, 9_000_000_000, 3, ResearchTime(10_000, 10_000_000_000, "boot-a")),
        )
        val draft = previous.eventDraft()
        val recorded = RecordedEvent(
            1,
            draft.collectorId,
            draft.payloadSchemaVersion,
            draft.observedTime,
            draft.payloadType,
            draft.fields,
        )
        val gate = LatestValueRateGate<AmbientLightSample>(200) { old, current ->
            sameAmbientLightSample(old, current, 1_000)
        }
        gate.restoreLastEmission(
            recorded.ambientLightSampleOrNull(),
            10_100,
        )

        assertEquals(LatestValueRateGate.Decision.Suppress, gate.offer(previous, 10_100))
        assertEquals(
            LatestValueRateGate.Decision.Defer(200),
            gate.offer(previous.copy(illuminanceLux = 20f), 10_100),
        )
    }

    private fun time(value: Long) = ResearchTime(value, value, "boot-a")
}
