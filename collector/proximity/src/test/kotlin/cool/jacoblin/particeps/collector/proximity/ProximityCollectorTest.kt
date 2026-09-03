package cool.jacoblin.particeps.collector.proximity

import cool.jacoblin.particeps.core.collector.LatestValueRateGate
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.definition.ProximityV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.ResearchTime
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
        assertEquals(ProximityV1ProfileConfiguration.SOURCE_ID, event.type.sourceId.value)
        assertTrue(requireNotNull(ProtocolEventSourceRegistry[ProximityV1ProfileConfiguration.SOURCE_ID]).accepts(event, 1, null))
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

    private fun time(value: Long) = ResearchTime(value, value, "boot-a")
}
