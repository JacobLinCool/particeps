package cool.jacoblin.particeps.collector.screenstate

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStateCollectorTest {
    @Test fun doesNotCollapseDozeAndOffIntoInteractiveState() {
        assertEquals("OFF", displayStateName(1))
        assertEquals("ON", displayStateName(2))
        assertEquals("DOZE", displayStateName(3))
        assertEquals("DOZE_SUSPEND", displayStateName(4))
        assertEquals("UNKNOWN", displayStateName(99))
    }
}
