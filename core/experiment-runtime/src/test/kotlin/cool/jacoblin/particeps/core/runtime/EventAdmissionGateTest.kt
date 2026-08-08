package cool.jacoblin.particeps.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAdmissionGateTest {
    @Test
    fun drainingAcceptsOnlyCapturedEventsBeforeBoundary() {
        val gate = EventAdmissionGate()
        val token = gate.open()

        assertTrue(gate.accepts(token, 99))
        gate.beginDrain(100)
        assertNull(gate.capture())
        assertTrue(gate.accepts(token, 99))
        assertFalse(gate.accepts(token, 100))
        assertFalse(gate.accepts(token, 101))

        gate.close(token)
        assertFalse(gate.accepts(token, 99))
    }

    @Test
    fun aTokenFromAnEarlierRunningWindowIsAlwaysRejected() {
        val gate = EventAdmissionGate()
        val first = gate.open()
        gate.beginDrain(10)
        gate.close(first)

        val second = gate.open()

        assertFalse(gate.accepts(first, 1))
        assertTrue(gate.accepts(second, 11))
    }

    @Test
    fun aTokenIsBoundToTheGateThatIssuedIt() {
        val first = EventAdmissionGate()
        val second = EventAdmissionGate()
        val firstToken = first.open()
        val secondToken = second.open()

        assertTrue(first.accepts(firstToken, 1))
        assertTrue(second.accepts(secondToken, 1))
        assertFalse(first.accepts(secondToken, 1))
        assertFalse(second.accepts(firstToken, 1))
    }
}
