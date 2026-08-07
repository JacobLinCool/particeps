package cool.jacoblin.particeps.collector.gyroscope

import cool.jacoblin.particeps.core.collector.ProtocolEventContracts
import cool.jacoblin.particeps.core.definition.GyroscopeConfiguration
import cool.jacoblin.particeps.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroscopeCollectorTest {
    @Test
    fun emitsRawAxesWithHardwareAndCaptureTimes() {
        val observed = ResearchTime(10, 20, "boot-a")
        val event = gyroscopeEvent(floatArrayOf(1.25f, -2.5f, 0.0f), 123, 3, observed)

        requireNotNull(event)
        assertEquals(observed, event.observedTime)
        assertEquals("123", event.fields["source_elapsed_realtime_nanos"])
        assertEquals("1.25", event.fields["x_radians_per_second"])
        assertEquals("-2.5", event.fields["y_radians_per_second"])
        assertEquals("0.0", event.fields["z_radians_per_second"])
        assertEquals("3", event.fields["accuracy"])
        assertEquals(GyroscopeConfiguration.ID, event.collectorId)
        assertTrue(requireNotNull(ProtocolEventContracts[GyroscopeConfiguration.ID]).accepts(event, 1))
    }

    @Test
    fun rejectsIncompleteNonFiniteOrNegativeTimestampSamples() {
        val observed = ResearchTime(10, 20, "boot-a")

        assertNull(gyroscopeEvent(floatArrayOf(1f, 2f), 1, 0, observed))
        assertNull(gyroscopeEvent(floatArrayOf(1f, Float.NaN, 3f), 1, 0, observed))
        assertNull(gyroscopeEvent(floatArrayOf(1f, 2f, 3f), -1, 0, observed))
    }
}
