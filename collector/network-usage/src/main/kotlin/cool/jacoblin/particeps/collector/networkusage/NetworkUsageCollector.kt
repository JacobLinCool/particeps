package cool.jacoblin.particeps.collector.networkusage

import android.app.usage.NetworkStatsManager
import android.content.Context
import cool.jacoblin.particeps.collector.usagecommon.isUsageAccessGranted
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorFlushFailureReason
import cool.jacoblin.particeps.core.collector.CollectorFlushResult
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorObservationMode
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.NetworkUsageV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.NetworkUsageV1TransportsValue
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.SourceCoverage
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NetworkUsageCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = NetworkUsageV1ProfileConfiguration.SOURCE_ID,
        displayName = "Aggregate network usage",
        accessKinds = setOf(AccessKind.USAGE_ACCESS),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[NetworkUsageV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(
        configuration: CollectorProfileConfiguration,
        context: CollectorContext,
    ): Collector = NetworkUsageCollector(
        applicationContext,
        configuration as? NetworkUsageV1ProfileConfiguration
            ?: throw IllegalArgumentException("Invalid network-usage configuration"),
        context,
    )
}

private class NetworkUsageCollector(
    context: Context,
    private val configuration: NetworkUsageV1ProfileConfiguration,
    private val collectorContext: CollectorContext,
) : Collector {
    private val applicationContext = context.applicationContext
    private val statsManager = context.getSystemService(NetworkStatsManager::class.java)
    private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
    override val health: StateFlow<CollectorHealth> = mutableHealth.asStateFlow()
    override val observationMode: CollectorObservationMode = CollectorObservationMode.RETROSPECTIVE
    private var pollingJob: Job? = null
    private var coverageStartUtcMillis = 0L
    private var producerOrdinal = 0L
    private val observationMutex = Mutex()

    override suspend fun start() {
        check(pollingJob == null) { "Network-usage collector is already started" }
        if (!isUsageAccessGranted(applicationContext)) {
            mutableHealth.value = CollectorHealth(CollectorStatus.BLOCKED_ACCESS, "USAGE_ACCESS_REQUIRED")
            throw SecurityException("Usage access is required")
        }
        coverageStartUtcMillis = collectorContext.clocks.now().wallTimeUtcMillis
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
        startPolling()
    }

    override suspend fun stop() {
        stopPolling()
        mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
    }

    override suspend fun flushThrough(boundary: ResearchTime, cursor: String?): CollectorFlushResult {
        if (!isNetworkUsageFlushCursorValid(cursor, coverageStartUtcMillis)) {
            return CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_QUALITY_GAP)
        }
        if (boundary.wallTimeUtcMillis < coverageStartUtcMillis) {
            return CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_QUALITY_GAP)
        }
        val completed = observationMutex.withLock {
            if (boundary.wallTimeUtcMillis == coverageStartUtcMillis) {
                advanceEmptyCoverage(boundary)
            } else {
                collectThrough(boundary, barrierFlush = true)
            }
        }
        return if (completed) {
            CollectorFlushResult.Complete(boundary, coverageStartUtcMillis.toString())
        } else {
            CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_FAILURE)
        }
    }

    private fun startPolling() {
        mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        pollingJob = collectorContext.scope.launch(Dispatchers.Default) {
            val intervalMillis = TimeUnit.SECONDS.toMillis(configuration.pollIntervalSeconds)
            while (isActive) {
                delay(intervalMillis)
                observationMutex.withLock { collectThrough(collectorContext.clocks.now(), barrierFlush = false) }
            }
        }
    }

    private suspend fun stopPolling() {
        pollingJob?.cancel()
        pollingJob?.join()
        pollingJob = null
    }

    private suspend fun collectThrough(observed: ResearchTime, barrierFlush: Boolean): Boolean {
        val token = if (barrierFlush) {
            collectorContext.eventSink.captureBarrierFlushToken(observed)
        } else {
            collectorContext.eventSink.captureToken()
        } ?: return false
        val end = observed.wallTimeUtcMillis
        if (end <= coverageStartUtcMillis) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "WALL_CLOCK_NOT_FORWARD")
            return false
        }
        try {
            val events = configuration.transports.map { transport ->
                val bucket = withContext(Dispatchers.IO) {
                    statsManager.querySummaryForDevice(
                        transport.toLegacyNetworkType(),
                        null,
                        coverageStartUtcMillis,
                        end,
                    )
                }
                EventDraft(
                    type = EventTypeKey(
                        EventSourceId(NetworkUsageV1ProfileConfiguration.SOURCE_ID),
                        1,
                        "NETWORK_USAGE_AGGREGATE",
                    ),
                    observedTime = observed,
                    fields = mapOf(
                        "transport" to transport.name,
                        "coverage_start_utc_millis" to coverageStartUtcMillis.toString(),
                        "coverage_end_utc_millis" to end.toString(),
                        "rx_bytes" to bucket.rxBytes.toString(),
                        "tx_bytes" to bucket.txBytes.toString(),
                        "rx_packets" to bucket.rxPackets.toString(),
                        "tx_packets" to bucket.txPackets.toString(),
                    ),
                )
            }
            val result = collectorContext.eventSink.emitBatch(
                token,
                SourceEventBatch(
                    sourceId = EventSourceId(NetworkUsageV1ProfileConfiguration.SOURCE_ID),
                    schemaVersion = 1,
                    resourceGeneration = collectorContext.resourceGeneration,
                    producerOrdinal = producerOrdinal,
                    events = events,
                    coverage = SourceCoverage(
                        SourceClockBasis.SOURCE_WALL_TIME,
                        coverageStartUtcMillis.toString(),
                        end.toString(),
                    ),
                ),
            )
            if (!handleResult(result)) return false
            producerOrdinal = producerOrdinalAfter(result, producerOrdinal)
            coverageStartUtcMillis = end
            return true
        } catch (failure: SecurityException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "USAGE_ACCESS_REVOKED")
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "NETWORK_STATS_QUERY_FAILED")
        }
        return false
    }

    private suspend fun advanceEmptyCoverage(observed: ResearchTime): Boolean {
        val token = collectorContext.eventSink.captureBarrierFlushToken(observed) ?: return false
        val coordinate = coverageStartUtcMillis.toString()
        val result = collectorContext.eventSink.advanceCoverage(
            token,
            CoverageAdvance(
                sourceId = EventSourceId(NetworkUsageV1ProfileConfiguration.SOURCE_ID),
                schemaVersion = 1,
                resourceGeneration = collectorContext.resourceGeneration,
                producerOrdinal = producerOrdinal,
                coverage = SourceCoverage(
                    SourceClockBasis.SOURCE_WALL_TIME,
                    coordinate,
                    coordinate,
                ),
            ),
        )
        if (!handleResult(result)) return false
        producerOrdinal = producerOrdinalAfter(result, producerOrdinal)
        return true
    }

    private fun handleResult(result: EmitBatchResult): Boolean = when (result) {
        is EmitBatchResult.Accepted -> true
        EmitBatchResult.RejectedByAdmissionGate -> false
        EmitBatchResult.ContractViolation -> false.also {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "EVENT_CONTRACT_VIOLATION")
        }
        is EmitBatchResult.SourceQualityGap -> false.also {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "SOURCE_QUALITY_GAP")
        }
        EmitBatchResult.StorageFailure -> false.also {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "STORAGE_WRITE_FAILED")
        }
    }

    private fun NetworkUsageV1TransportsValue.toLegacyNetworkType(): Int = when (this) {
        NetworkUsageV1TransportsValue.MOBILE -> LEGACY_NETWORK_TYPE_MOBILE
        NetworkUsageV1TransportsValue.WIFI -> LEGACY_NETWORK_TYPE_WIFI
    }

    private companion object {
        // NetworkStatsManager's public API requires ConnectivityManager's stable integer network-type values.
        const val LEGACY_NETWORK_TYPE_MOBILE = 0
        const val LEGACY_NETWORK_TYPE_WIFI = 1
    }
}

internal fun producerOrdinalAfter(result: EmitBatchResult, current: Long): Long = when (result) {
    is EmitBatchResult.Accepted -> Math.addExact(current, 1L)
    EmitBatchResult.ContractViolation,
    EmitBatchResult.RejectedByAdmissionGate,
    is EmitBatchResult.SourceQualityGap,
    EmitBatchResult.StorageFailure,
    -> current
}

/**
 * A durable flush cursor advances only at a barrier, while ordinary committed polls advance the
 * producer's in-memory coverage. It may therefore trail the current start. A cursor ahead of the
 * producer would skip an unobserved interval and must fail closed.
 */
internal fun isNetworkUsageFlushCursorValid(cursor: String?, currentStartUtcMillis: Long): Boolean =
    cursor == null || cursor.toLongOrNull()?.let { it <= currentStartUtcMillis } == true
