package cool.jacoblin.particeps.actuator.trafficshaping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnOwnershipMonitorTest {
    @Test
    fun handoverKeepsProofUntilLastOwnedNetworkIsLost() {
        var failures = 0
        val networks = GenerationOwnedNetworkSet<String>(7) { failures += 1 }
        networks.observe("old", true)
        networks.observe("new", true)

        networks.lost("old")
        assertTrue(networks.hasOwnedNetwork())
        assertEquals(0, failures)

        networks.lost("new")
        assertFalse(networks.hasOwnedNetwork())
        assertEquals(1, failures)
    }

    @Test
    fun unownedVpnNeverCountsAsOurProof() {
        var failures = 0
        val networks = GenerationOwnedNetworkSet<String>(8) { failures += 1 }
        networks.observe("other-vpn", false)
        networks.lost("other-vpn")

        assertFalse(networks.hasOwnedNetwork())
        assertEquals(0, failures)
    }

    @Test
    fun closedGenerationIgnoresLateCallbacks() {
        var failures = 0
        val networks = GenerationOwnedNetworkSet<String>(9) { failures += 1 }
        networks.observe("ours", true)
        networks.close()
        networks.lost("ours")
        networks.observe("late", true)

        assertFalse(networks.hasOwnedNetwork())
        assertEquals(0, failures)
    }
}
