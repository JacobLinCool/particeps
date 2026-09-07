package cool.jacoblin.particeps.collector.notificationevents

import org.junit.Assert.*
import org.junit.Test

class NotificationObservationBridgeTest {
    @Test fun neverBackfillsAndPreservesUpdatesOnlyWhileOwnedAndConnected() {
        val bridge = NotificationObservationBridge
        val owner = Any()
        val seen = mutableListOf<Long>()
        var failures = 0
        bridge.disconnected()
        assertThrows(IllegalStateException::class.java) { bridge.install(owner, { _, _, _ -> }, {}) }
        bridge.connected()
        bridge.posted("app.one", "key", 1)
        bridge.install(owner, { _, _, time -> seen += time }, { failures++ })
        try {
            assertThrows(IllegalStateException::class.java) { bridge.install(Any(), { _, _, _ -> }, {}) }
            bridge.posted("app.one", "same-key", 2)
            bridge.posted("app.one", "same-key", 3)
            assertThrows(IllegalStateException::class.java) { bridge.uninstall(Any()) }
            bridge.disconnected()
            bridge.posted("app.one", "key", 4)
            assertEquals(1, failures)
            assertThrows(IllegalStateException::class.java) { bridge.requireReady(owner) }
        } finally {
            bridge.uninstall(owner)
        }
        bridge.connected()
        bridge.posted("app.one", "key", 5)
        bridge.install(owner, { _, _, time -> seen += time }, {})
        try { bridge.posted("app.one", "key", 6) } finally { bridge.uninstall(owner); bridge.disconnected() }
        assertEquals(listOf(2L, 3L, 6L), seen)
    }
}
