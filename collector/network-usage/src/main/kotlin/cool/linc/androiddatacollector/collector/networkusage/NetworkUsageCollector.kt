package cool.linc.androiddatacollector.collector.networkusage

import android.app.usage.NetworkStatsManager
import android.content.Context
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.NetworkTransport
import cool.linc.androiddatacollector.core.definition.NetworkUsageConfiguration
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorHealth
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.CollectorStatus
import cool.linc.androiddatacollector.core.collector.EmitResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkUsageCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = NetworkUsageConfiguration.ID,
        payloadSchemaVersion = 1,
        displayName = "Aggregate network usage",
        privacyClass = PrivacyClass.SENSITIVE,
        maximumEncodedEventBytes = 2_048,
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? NetworkUsageConfiguration
            ?: throw IllegalArgumentException("Invalid network-usage configuration")
        return setOf(AccessRequirement(AccessKind.USAGE_ACCESS, typed.required))
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector = NetworkUsageCollector(
        applicationContext,
        configuration as? NetworkUsageConfiguration
            ?: throw IllegalArgumentException("Invalid network-usage configuration"),
        context,
    )
}

private class NetworkUsageCollector(
    context: Context,
    private val configuration: NetworkUsageConfiguration,
    private val collectorContext: CollectorContext,
) : Collector {
    private val statsManager = context.getSystemService(NetworkStatsManager::class.java)
    private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
    override val health: StateFlow<CollectorHealth> = mutableHealth.asStateFlow()
    private var pollingJob: Job? = null
    private var coverageStartUtcMillis = 0L

    override suspend fun start() {
        check(pollingJob == null) { "Network-usage collector is already started" }
        coverageStartUtcMillis = collectorContext.eventSink
            .latestEvent(NetworkUsageConfiguration.ID)
            ?.fields
            ?.get("coverage_end_utc_millis")
            ?.toLongOrNull()
            ?: collectorContext.clocks.now().wallTimeUtcMillis
        startPolling()
    }

    override suspend fun pause() {
        checkNotNull(pollingJob) { "Network-usage collector is not started" }
        stopPolling()
        if (mutableHealth.value.status != CollectorStatus.FAILED) {
            mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED)
        }
    }

    override suspend fun resume() {
        check(pollingJob == null) { "Network-usage collector is already active" }
        coverageStartUtcMillis = collectorContext.clocks.now().wallTimeUtcMillis
        startPolling()
    }

    override suspend fun stop() {
        stopPolling()
        mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
    }

    private fun startPolling() {
        mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        pollingJob = collectorContext.scope.launch(Dispatchers.Default) {
            val intervalMillis = TimeUnit.MINUTES.toMillis(configuration.pollIntervalMinutes.toLong())
            while (isActive) {
                delay(intervalMillis)
                collectInterval()
            }
        }
    }

    private suspend fun stopPolling() {
        pollingJob?.cancel()
        pollingJob?.join()
        pollingJob = null
    }

    private suspend fun collectInterval() {
        val token = collectorContext.eventSink.captureToken() ?: return
        val observed = collectorContext.clocks.now()
        val end = observed.wallTimeUtcMillis
        if (end <= coverageStartUtcMillis) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "WALL_CLOCK_NOT_FORWARD")
            return
        }
        try {
            configuration.transports.sortedBy(NetworkTransport::name).forEach { transport ->
                val bucket = withContext(Dispatchers.IO) {
                    statsManager.querySummaryForDevice(
                        transport.toLegacyNetworkType(),
                        null,
                        coverageStartUtcMillis,
                        end,
                    )
                }
                val result = collectorContext.eventSink.emit(
                    token,
                    EventDraft(
                        collectorId = NetworkUsageConfiguration.ID,
                        payloadSchemaVersion = 1,
                        observedTime = observed,
                        payloadType = "NETWORK_USAGE_AGGREGATE",
                        fields = mapOf(
                            "transport" to transport.name,
                            "coverage_start_utc_millis" to coverageStartUtcMillis.toString(),
                            "coverage_end_utc_millis" to end.toString(),
                            "rx_bytes" to bucket.rxBytes.toString(),
                            "tx_bytes" to bucket.txBytes.toString(),
                            "rx_packets" to bucket.rxPackets.toString(),
                            "tx_packets" to bucket.txPackets.toString(),
                        ),
                    ),
                )
                if (result == EmitResult.StorageFailure) {
                    mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "STORAGE_WRITE_FAILED")
                    return
                }
            }
            coverageStartUtcMillis = end
        } catch (failure: SecurityException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "USAGE_ACCESS_REVOKED")
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "NETWORK_STATS_QUERY_FAILED")
        }
    }

    private fun NetworkTransport.toLegacyNetworkType(): Int = when (this) {
        NetworkTransport.MOBILE -> LEGACY_NETWORK_TYPE_MOBILE
        NetworkTransport.WIFI -> LEGACY_NETWORK_TYPE_WIFI
    }

    private companion object {
        // NetworkStatsManager's public API requires ConnectivityManager's stable integer network-type values.
        const val LEGACY_NETWORK_TYPE_MOBILE = 0
        const val LEGACY_NETWORK_TYPE_WIFI = 1
    }
}
