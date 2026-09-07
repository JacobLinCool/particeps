package cool.jacoblin.particeps.collector.networkthroughput

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cool.jacoblin.particeps.core.collector.*
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.NetworkThroughputV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NetworkThroughputCollectorPlugin : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id = NetworkThroughputV1ProfileConfiguration.SOURCE_ID,
        displayName = "Observed network throughput",
        accessKinds = emptySet(),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[NetworkThroughputV1ProfileConfiguration.SOURCE_ID]),
    )
    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
        require(configuration is NetworkThroughputV1ProfileConfiguration)
        return NetworkThroughputCollector(configuration, context)
    }
}

private class NetworkThroughputCollector(
    private val configuration: NetworkThroughputV1ProfileConfiguration,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, 64) {
    private val handler = Handler(Looper.getMainLooper())
    private var previous: NetworkCounters? = null
    private val poll = object : Runnable {
        override fun run() {
            val current = counters()
            val start = previous ?: return
            val fields = throughputFields(start, current)
            if (fields == null) {
                fail("NETWORK_COUNTER_DISCONTINUITY")
                return
            }
            capture {
                EventDraft(
                    EventTypeKey(EventSourceId(NetworkThroughputV1ProfileConfiguration.SOURCE_ID), 1, "NETWORK_THROUGHPUT"),
                    context.clocks.now(), fields,
                )
            }
            previous = current
            handler.postDelayed(this, configuration.pollIntervalSeconds * 1_000)
        }
    }
    override suspend fun registerSource(): SourceRegistrationResult = SourceRegistrationResult.Registered
    override suspend fun onSourceAdmitted() = withContext(Dispatchers.Main.immediate) {
        handler.removeCallbacks(poll)
        val current = counters()
        check(current.rxBytes >= 0 && current.txBytes >= 0) { "Network counters unavailable" }
        previous = current
        handler.postDelayed(poll, configuration.pollIntervalSeconds * 1_000)
        Unit
    }
    override suspend fun unregisterSource(): SourceTeardownResult = withContext(Dispatchers.Main.immediate) {
        handler.removeCallbacks(poll)
        previous = null
        SourceTeardownResult.Released
    }
    private fun counters() = NetworkCounters(SystemClock.elapsedRealtimeNanos(), TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes())
}

internal data class NetworkCounters(val elapsedNanos: Long, val rxBytes: Long, val txBytes: Long)

internal fun throughputFields(start: NetworkCounters, end: NetworkCounters): Map<String, String>? {
    if (start.elapsedNanos < 0 || end.elapsedNanos <= start.elapsedNanos ||
        start.rxBytes < 0 || start.txBytes < 0 || end.rxBytes < start.rxBytes || end.txBytes < start.txBytes) return null
    return mapOf(
        "interval_start_elapsed_nanos" to start.elapsedNanos.toString(),
        "interval_end_elapsed_nanos" to end.elapsedNanos.toString(),
        "rx_bytes" to (end.rxBytes - start.rxBytes).toString(),
        "tx_bytes" to (end.txBytes - start.txBytes).toString(),
    )
}
