package cool.jacoblin.particeps.collector.networkthroughput

import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.model.*
import org.junit.Assert.*
import org.junit.Test

class NetworkThroughputCollectorTest {
    @Test fun preservesActualIntervalAndZeroTrafficWithoutInventingCapacity() {
        val fields = requireNotNull(throughputFields(NetworkCounters(1, 20, 30), NetworkCounters(2_000_000_001, 125020, 30)))
        assertEquals("125000", fields["rx_bytes"])
        assertEquals("0", fields["tx_bytes"])
        assertEquals("2000000001", fields["interval_end_elapsed_nanos"])
        val event = EventDraft(EventTypeKey(EventSourceId("network_throughput.v1"), 1, "NETWORK_THROUGHPUT"), ResearchTime(10, 20, "boot-a"), fields)
        assertTrue(requireNotNull(ProtocolEventSourceRegistry["network_throughput.v1"]).accepts(event, 1, null))
    }
    @Test fun rejectsUnsupportedCountersResetsAndNonIncreasingClocks() {
        val start = NetworkCounters(100, 20, 30)
        assertNull(throughputFields(start, NetworkCounters(100, 20, 30)))
        assertNull(throughputFields(start, NetworkCounters(200, 19, 30)))
        assertNull(throughputFields(start, NetworkCounters(200, 20, -1)))
        assertNull(throughputFields(NetworkCounters(100, -1, 0), NetworkCounters(200, 20, 30)))
    }
}
