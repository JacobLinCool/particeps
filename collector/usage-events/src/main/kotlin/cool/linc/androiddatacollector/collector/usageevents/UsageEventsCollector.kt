package cool.linc.androiddatacollector.collector.usageevents

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.definition.UsageEventsConfiguration
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

class UsageEventsCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = UsageEventsConfiguration.ID,
        payloadSchemaVersion = 1,
        displayName = "App and screen usage events",
        privacyClass = PrivacyClass.SENSITIVE,
        maximumEncodedEventBytes = 4_096,
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? UsageEventsConfiguration
            ?: throw IllegalArgumentException("Invalid usage-events configuration")
        return setOf(AccessRequirement(AccessKind.USAGE_ACCESS, typed.required))
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector = UsageEventsCollector(
        applicationContext,
        configuration as? UsageEventsConfiguration
            ?: throw IllegalArgumentException("Invalid usage-events configuration"),
        context,
    )
}

private class UsageEventsCollector(
    context: Context,
    private val configuration: UsageEventsConfiguration,
    private val collectorContext: CollectorContext,
) : Collector {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
    override val health: StateFlow<CollectorHealth> = mutableHealth.asStateFlow()
    private var pollingJob: Job? = null
    private var queryStartUtcMillis = 0L

    override suspend fun start() {
        check(pollingJob == null) { "Usage-events collector is already started" }
        queryStartUtcMillis = collectorContext.eventSink
            .latestEvent(UsageEventsConfiguration.ID)
            ?.fields
            ?.get("source_time_utc_millis")
            ?.toLongOrNull()
            ?.plus(1)
            ?: collectorContext.clocks.now().wallTimeUtcMillis
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
        queryStartUtcMillis = collectorContext.clocks.now().wallTimeUtcMillis
        startPolling()
    }

    override suspend fun stop() {
        stopPolling()
        mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
    }

    private fun startPolling() {
        mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        pollingJob = collectorContext.scope.launch(Dispatchers.Default) {
            val interval = TimeUnit.MINUTES.toMillis(configuration.pollIntervalMinutes.toLong())
            while (isActive) {
                delay(interval)
                poll()
            }
        }
    }

    private suspend fun stopPolling() {
        pollingJob?.cancel()
        pollingJob?.join()
        pollingJob = null
    }

    private suspend fun poll() {
        val token = collectorContext.eventSink.captureToken() ?: return
        val observed = collectorContext.clocks.now()
        val end = observed.wallTimeUtcMillis
        if (end <= queryStartUtcMillis) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "WALL_CLOCK_NOT_FORWARD")
            return
        }
        try {
            val sourceEvents = withContext(Dispatchers.IO) {
                val result = mutableListOf<SourceEvent>()
                val events = usageStatsManager.queryEvents(queryStartUtcMillis, end)
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    event.typeName()?.let { type ->
                        result += SourceEvent(type, event.timeStamp, event.packageName)
                    }
                }
                result
            }
            sourceEvents.forEach { source ->
                val fields = buildMap {
                    put("source_time_utc_millis", source.timestamp.toString())
                    source.packageName?.takeIf(String::isNotBlank)?.let { put("package_name", it) }
                }
                if (
                    collectorContext.eventSink.emit(
                        token,
                        EventDraft(
                            collectorId = UsageEventsConfiguration.ID,
                            payloadSchemaVersion = 1,
                            observedTime = observed,
                            payloadType = source.type,
                            fields = fields,
                        ),
                    ) == EmitResult.StorageFailure
                ) {
                    mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "STORAGE_WRITE_FAILED")
                    return
                }
            }
            queryStartUtcMillis = end
        } catch (failure: SecurityException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "USAGE_ACCESS_REVOKED")
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "USAGE_EVENTS_QUERY_FAILED")
        }
    }

    private fun UsageEvents.Event.typeName(): String? = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
        UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
        else -> null
    }

    private data class SourceEvent(
        val type: String,
        val timestamp: Long,
        val packageName: String?,
    )
}
