package cool.linc.particeps.collector.proximity

import cool.linc.particeps.core.collector.LatestValueRateGate
import cool.linc.particeps.core.collector.ProtocolEventContracts
import cool.linc.particeps.core.definition.ProximityConfiguration
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityCollectorTest {
    @Test
    fun mapsRawDistanceAndKeepsItsCaptureTime() {
        val observed = time(10)
        val near = proximitySample(1f, 5f, 123, observed)
        val far = proximitySample(5f, 5f, 124, time(20))

        requireNotNull(near)
        requireNotNull(far)
        val event = near.eventDraft()
        assertTrue(near.near)
        assertFalse(far.near)
        assertEquals(observed, event.observedTime)
        assertEquals("1.0", event.fields["distance_centimeters"])
        assertEquals("5.0", event.fields["maximum_range_centimeters"])
        assertEquals("true", event.fields["near"])
        assertEquals(ProximityConfiguration.ID, event.collectorId)
        assertTrue(requireNotNull(ProtocolEventContracts[ProximityConfiguration.ID]).accepts(event, 1))
    }

    @Test
    fun changeGateIgnoresCaptureMetadataButNotPhysicalState() {
        val previous = requireNotNull(proximitySample(1f, 5f, 100, time(10)))
        val belowThreshold = requireNotNull(proximitySample(1.09f, 5f, 200, time(20)))
        val atThreshold = requireNotNull(proximitySample(1.1f, 5f, 200, time(20)))

        assertTrue(sameProximitySample(previous, belowThreshold, 1))
        assertFalse(sameProximitySample(previous, atThreshold, 1))
        assertFalse(sameProximitySample(previous, previous.copy(maximumRangeCentimeters = 6f), 1))
    }

    @Test
    fun rejectsInvalidPhysicalSamples() {
        assertNull(proximitySample(Float.NaN, 5f, 1, time(1)))
        assertNull(proximitySample(1f, Float.POSITIVE_INFINITY, 1, time(1)))
        assertNull(proximitySample(-1f, 5f, 1, time(1)))
        assertNull(proximitySample(1f, 5f, -1, time(1)))
    }

    @Test
    fun processRestartRestoresProximityValueAndMinimumInterval() {
        val previous = requireNotNull(
            proximitySample(1f, 5f, 9_000_000_000, ResearchTime(10_000, 10_000_000_000, "boot-a")),
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
        val gate = LatestValueRateGate<ProximitySample>(100) { old, current ->
            sameProximitySample(old, current, 1)
        }
        gate.restoreLastEmission(
            recorded.proximitySampleOrNull(),
            10_050,
        )

        assertEquals(LatestValueRateGate.Decision.Suppress, gate.offer(previous, 10_050))
        assertEquals(
            LatestValueRateGate.Decision.Defer(100),
            gate.offer(previous.copy(distanceCentimeters = 2f), 10_050),
        )
    }

    private fun time(value: Long) = ResearchTime(value, value, "boot-a")
}
