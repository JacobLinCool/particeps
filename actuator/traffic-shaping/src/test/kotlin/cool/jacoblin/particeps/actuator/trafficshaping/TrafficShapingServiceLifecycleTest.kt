package cool.jacoblin.particeps.actuator.trafficshaping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficShapingServiceLifecycleTest {
    @Test
    fun destroyIsTerminalOnlyForAnInitializedUnreleasedSession() {
        val beforeStart = TrafficShapingServiceLifecycle()
        assertFalse(beforeStart.isUnexpectedDestroy())

        val active = TrafficShapingServiceLifecycle()
        active.activate()
        assertTrue(active.isUnexpectedDestroy())

        val released = TrafficShapingServiceLifecycle()
        released.activate()
        assertTrue(released.beginRelease())
        assertFalse(released.isUnexpectedDestroy())
        assertFalse(released.beginRelease())
    }

    @Test(expected = IllegalStateException::class)
    fun releasedServiceCannotBeReactivated() {
        val lifecycle = TrafficShapingServiceLifecycle()
        lifecycle.activate()
        lifecycle.beginRelease()
        lifecycle.activate()
    }
}
