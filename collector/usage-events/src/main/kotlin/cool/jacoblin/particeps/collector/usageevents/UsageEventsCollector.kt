package cool.jacoblin.particeps.collector.usageevents

import android.content.Context
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
import cool.jacoblin.particeps.core.definition.UsageEventsV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.MAX_OBSERVATION_EVENTS
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

class UsageEventsCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = UsageEventsV1ProfileConfiguration.SOURCE_ID,
        displayName = "App and screen usage events",
        accessKinds = setOf(AccessKind.USAGE_ACCESS),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[UsageEventsV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(
        configuration: CollectorProfileConfiguration,
        context: CollectorContext,
    ): Collector = UsageEventsCollector(
        AndroidUsageEventsQuery(applicationContext),
        configuration as? UsageEventsV1ProfileConfiguration
            ?: throw IllegalArgumentException("Invalid usage-events configuration"),
        context,
    )
}

internal class UsageEventsCollector(
    private val query: UsageEventsQuery,
    private val configuration: UsageEventsV1ProfileConfiguration,
    private val collectorContext: CollectorContext,
) : Collector {
    private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
    override val health: StateFlow<CollectorHealth> = mutableHealth.asStateFlow()
    override val observationMode: CollectorObservationMode = CollectorObservationMode.RETROSPECTIVE
    private var pollingJob: Job? = null
    private var queryStartUtcMillis = 0L
    private var producerOrdinal = 0L
    private val observationMutex = Mutex()

    override suspend fun start() {
        check(pollingJob == null) { "Usage-events collector is already started" }
        val unavailable = query.unavailableReason()
        if (unavailable != null) {
            mutableHealth.value = CollectorHealth(
                if (unavailable == UsageEventsUnavailableReason.ACCESS_DENIED) {
                    CollectorStatus.BLOCKED_ACCESS
                } else {
                    CollectorStatus.FAILED
                },
                unavailable.reasonCode,
            )
            throw IllegalStateException(unavailable.reasonCode)
        }
        queryStartUtcMillis = collectorContext.clocks.now().wallTimeUtcMillis
        startPolling()
    }

    override suspend fun pause() {
        checkNotNull(pollingJob) { "Usage-events collector is not started" }
        stopPolling()
        if (mutableHealth.value.status != CollectorStatus.FAILED) {
            mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED)
        }
    }

    override suspend fun resume() {
        check(pollingJob == null) { "Usage-events collector is already active" }
        check(mutableHealth.value.status == CollectorStatus.PAUSED) { "Failed usage source cannot resume" }
        startPolling()
    }

    override suspend fun stop() {
        stopPolling()
        mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
    }

    override suspend fun flushThrough(boundary: ResearchTime, cursor: String?): CollectorFlushResult {
        if (!isUsageEventsFlushCursorValid(cursor, queryStartUtcMillis)) {
            return CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_QUALITY_GAP)
        }
        if (boundary.wallTimeUtcMillis < queryStartUtcMillis) {
            return CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_QUALITY_GAP)
        }
        val completed = observationMutex.withLock {
            if (mutableHealth.value.status == CollectorStatus.FAILED || !checkAvailability()) {
                return@withLock false
            }
            if (boundary.wallTimeUtcMillis == queryStartUtcMillis) {
                advanceEmptyCoverage(boundary)
            } else {
                collectThrough(boundary, barrierFlush = true)
            }
        }
        return if (completed) {
            CollectorFlushResult.Complete(boundary, queryStartUtcMillis.toString())
        } else {
            CollectorFlushResult.Failed(CollectorFlushFailureReason.SOURCE_FAILURE)
        }
    }

    private fun startPolling() {
        mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        pollingJob = collectorContext.scope.launch(Dispatchers.Default) {
            val interval = TimeUnit.SECONDS.toMillis(configuration.pollIntervalSeconds)
            while (isActive) {
                delay(interval)
                observationMutex.withLock { collectThrough(collectorContext.clocks.now(), barrierFlush = false) }
                if (mutableHealth.value.status == CollectorStatus.FAILED) break
            }
        }
    }

    private suspend fun stopPolling() {
        pollingJob?.cancel()
        pollingJob?.join()
        pollingJob = null
    }

    private suspend fun collectThrough(observed: ResearchTime, barrierFlush: Boolean): Boolean {
        if (mutableHealth.value.status == CollectorStatus.FAILED) return false
        val token = if (barrierFlush) {
            collectorContext.eventSink.captureBarrierFlushToken(observed)
        } else {
            collectorContext.eventSink.captureToken()
        } ?: return false
        val end = observed.wallTimeUtcMillis
        if (end <= queryStartUtcMillis) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "WALL_CLOCK_NOT_FORWARD")
            return false
        }
        try {
            val sourceEvents = withContext(Dispatchers.IO) {
                // Android returns an empty iterator for denied access and some service failures.
                // Never interpret an unavailable query as a successful zero-event interval.
                if (!checkAvailability()) return@withContext null
                val result = query.events(queryStartUtcMillis, end)
                if (!checkAvailability()) return@withContext null
                result
            } ?: return false
            if (sourceEvents.size > MAX_OBSERVATION_EVENTS) {
                mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "EVENT_BATCH_LIMIT_EXCEEDED")
                return false
            }
            val coverage = SourceCoverage(
                SourceClockBasis.SOURCE_WALL_TIME,
                queryStartUtcMillis.toString(),
                end.toString(),
            )
            val drafts = sourceEvents.map { source ->
                val fields = buildMap {
                    put("source_time_utc_millis", source.timestamp.toString())
                    source.packageName?.takeIf(String::isNotBlank)?.let { put("package_name", it) }
                    source.activityComponent?.let { component ->
                        val tokenValue = collectorContext.tokenEncoder.encode(ACTIVITY_TOKEN_DOMAIN, component)
                        require(SHA256.matches(tokenValue)) { "Token encoder returned a non-canonical digest" }
                        put("activity_component_token", tokenValue)
                    }
                }
                EventDraft(
                    type = EventTypeKey(EventSourceId(UsageEventsV1ProfileConfiguration.SOURCE_ID), 1, source.type),
                    observedTime = observed,
                    fields = fields,
                )
            }
            val result = if (drafts.isEmpty()) {
                collectorContext.eventSink.advanceCoverage(
                    token,
                    CoverageAdvance(
                        sourceId = EventSourceId(UsageEventsV1ProfileConfiguration.SOURCE_ID),
                        schemaVersion = 1,
                        resourceGeneration = collectorContext.resourceGeneration,
                        producerOrdinal = producerOrdinal,
                        coverage = coverage,
                    ),
                )
            } else {
                collectorContext.eventSink.emitBatch(
                    token,
                    SourceEventBatch(
                        sourceId = EventSourceId(UsageEventsV1ProfileConfiguration.SOURCE_ID),
                        schemaVersion = 1,
                        resourceGeneration = collectorContext.resourceGeneration,
                        producerOrdinal = producerOrdinal,
                        events = drafts,
                        coverage = coverage,
                    ),
                )
            }
            if (!handleResult(result)) return false
            producerOrdinal = producerOrdinalAfter(result, producerOrdinal)
            queryStartUtcMillis = end
            return true
        } catch (failure: SecurityException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "USAGE_ACCESS_REVOKED")
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "USAGE_EVENTS_QUERY_FAILED")
        }
        return false
    }

    private suspend fun advanceEmptyCoverage(boundary: ResearchTime): Boolean {
        val token = collectorContext.eventSink.captureBarrierFlushToken(boundary) ?: return false
        val coordinate = queryStartUtcMillis.toString()
        val result = collectorContext.eventSink.advanceCoverage(
            token,
            CoverageAdvance(
                sourceId = EventSourceId(UsageEventsV1ProfileConfiguration.SOURCE_ID),
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

    private fun checkAvailability(): Boolean {
        val reason = try {
            query.unavailableReason()?.reasonCode ?: return true
        } catch (_: SecurityException) {
            "USAGE_ACCESS_REVOKED"
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            "USAGE_EVENTS_QUERY_FAILED"
        }
        mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, reason)
        return false
    }

    private companion object {
        const val ACTIVITY_TOKEN_DOMAIN = "usage-events.activity-component.v1"
        val SHA256 = Regex("[0-9a-f]{64}")
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
internal fun isUsageEventsFlushCursorValid(cursor: String?, currentStartUtcMillis: Long): Boolean =
    cursor == null || cursor.toLongOrNull()?.let { it <= currentStartUtcMillis } == true
