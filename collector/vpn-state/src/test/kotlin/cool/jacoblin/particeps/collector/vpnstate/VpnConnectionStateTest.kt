package cool.jacoblin.particeps.collector.vpnstate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectionStateTest {
    @Test
    fun absenceOfInitialCallbacksRemainsUnknown() {
        val state = VpnConnectionState<Int>()
        assertNull(state.connected)
    }

    @Test
    fun losingOneVpnDoesNotDisconnectAnotherObservedVpn() {
        val state = VpnConnectionState<Int>()
        assertTrue(state.available(10))
        assertTrue(state.available(20))
        assertTrue(state.lost(10))
        assertTrue(requireNotNull(state.connected))
        assertFalse(state.lost(20))
        assertFalse(requireNotNull(state.connected))
    }

    @Test
    fun repeatedAvailabilityDoesNotLeaveAPhantomConnection() {
        val state = VpnConnectionState<Int>()
        state.available(10)
        state.available(10)
        assertFalse(state.lost(10))
    }

    @Test
    fun restartingObservationDoesNotReusePreviousConnectionEvidence() {
        val state = VpnConnectionState<Int>()
        state.available(10)
        state.clear()
        assertNull(state.connected)
        state.available(20)
        assertFalse(state.lost(20))
    }
}
