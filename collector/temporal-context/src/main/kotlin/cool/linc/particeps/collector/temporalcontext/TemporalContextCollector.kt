package cool.linc.particeps.collector.temporalcontext

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cool.linc.particeps.core.collector.AccessRequirement
import cool.linc.particeps.core.collector.Collector
import cool.linc.particeps.core.collector.CollectorContext
import cool.linc.particeps.core.collector.CollectorDescriptor
import cool.linc.particeps.core.collector.CollectorPlugin
import cool.linc.particeps.core.collector.LatestValueRateGate
import cool.linc.particeps.core.collector.PrivacyClass
import cool.linc.particeps.core.collector.ProtocolEventContracts
import cool.linc.particeps.core.collector.SerializedCallbackCollector
import cool.linc.particeps.core.collector.SourceRegistrationResult
import cool.linc.particeps.core.collector.SourceTeardownResult
import cool.linc.particeps.core.collector.completeSourceTeardown
import cool.linc.particeps.core.collector.registerSourceWithRollback
import cool.linc.particeps.core.definition.CollectorConfiguration
import cool.linc.particeps.core.definition.TemporalContextConfiguration
import cool.linc.particeps.core.model.EventDraft
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TemporalContextCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = TemporalContextConfiguration.ID,
        displayName = "Temporal context",
        privacyClass = PrivacyClass.SENSITIVE,
        eventContract = requireNotNull(ProtocolEventContracts[TemporalContextConfiguration.ID]),
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        require(configuration is TemporalContextConfiguration) { "Invalid temporal-context configuration" }
        return emptySet()
    }

    override fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector {
        require(configuration is TemporalContextConfiguration) { "Invalid temporal-context configuration" }
        return TemporalContextCollector(applicationContext, context)
    }
}

private class TemporalContextCollector(
    private val applicationContext: Context,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, 64) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rateGate = LatestValueRateGate<TemporalEvent>(
        MINIMUM_INTERVAL_MILLIS,
        ::sameTemporalEvent,
    )
    private val pendingRunnable = Runnable(::publishPending)
    private var observedContext: TemporalSnapshot? = null
    private var pendingScheduled = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val observedTime = this@TemporalContextCollector.context.clocks.now()
            val snapshot = snapshot(observedTime)
            val reason = when (intent?.action) {
                Intent.ACTION_TIMEZONE_CHANGED -> "TIMEZONE_CHANGED"
                Intent.ACTION_TIME_CHANGED -> "TIME_SET"
                Intent.ACTION_TIME_TICK -> if (snapshot != observedContext) "UTC_OFFSET_CHANGED" else null
                else -> null
            }
            observedContext = snapshot
            reason?.let { offer(TemporalEvent(it, snapshot, observedTime)) }
        }
    }

    override suspend fun registerSource(): SourceRegistrationResult = withContext(Dispatchers.Main.immediate) {
        val latest = context.eventSink.latestEvent(TemporalContextConfiguration.ID)
        if (latest != null) {
            rateGate.restoreLastEmission(
                value = latest.temporalEventOrNull(),
                currentElapsedMillis = SystemClock.elapsedRealtime(),
            )
        }
        val hasPriorEvent = latest != null
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        var receiverRegistered = false
        registerSourceWithRollback(
            register = {
                applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
                val observedTime = context.clocks.now()
                val current = snapshot(observedTime)
                observedContext = current
                offer(TemporalEvent(if (hasPriorEvent) "RECONCILED" else "STUDY_STARTED", current, observedTime))
            },
            rollback = {
                completeSourceTeardown(
                    { if (receiverRegistered) applicationContext.unregisterReceiver(receiver) },
                    ::clearPending,
                )
            },
        )
    }

    override suspend fun unregisterSource(): SourceTeardownResult = withContext(Dispatchers.Main.immediate) {
        completeSourceTeardown(
            { applicationContext.unregisterReceiver(receiver) },
            ::clearPending,
        )
        SourceTeardownResult.Released
    }

    private fun clearPending() {
        mainHandler.removeCallbacks(pendingRunnable)
        rateGate.clearPending()
        pendingScheduled = false
    }

    private fun snapshot(observedTime: ResearchTime): TemporalSnapshot {
        return temporalSnapshot(ZoneId.systemDefault(), observedTime)
    }

    private fun offer(event: TemporalEvent) {
        handle(rateGate.offer(event, SystemClock.elapsedRealtime()))
    }

    private fun publishPending() {
        pendingScheduled = false
        handle(rateGate.poll(SystemClock.elapsedRealtime()))
    }

    private fun handle(decision: LatestValueRateGate.Decision<TemporalEvent>) {
        when (decision) {
            is LatestValueRateGate.Decision.Emit -> {
                mainHandler.removeCallbacks(pendingRunnable)
                pendingScheduled = false
                publish(decision.value)
            }
            is LatestValueRateGate.Decision.Defer -> if (!pendingScheduled) {
                pendingScheduled = true
                mainHandler.postDelayed(pendingRunnable, decision.delayMillis)
            }
            LatestValueRateGate.Decision.Suppress -> {
                mainHandler.removeCallbacks(pendingRunnable)
                pendingScheduled = false
            }
        }
    }

    private fun publish(event: TemporalEvent) {
        capture(event::eventDraft)
    }

    private companion object { const val MINIMUM_INTERVAL_MILLIS = 60_000L }
}

internal data class TemporalEvent(
    val reason: String,
    val snapshot: TemporalSnapshot,
    val observedTime: ResearchTime,
)

internal data class TemporalSnapshot(
    val timezoneId: String,
    val utcOffsetSeconds: Int,
    val daylightSavingTime: Boolean,
)

internal fun sameTemporalEvent(previous: TemporalEvent, current: TemporalEvent): Boolean =
    current.reason != "TIME_SET" && previous.reason == current.reason && previous.snapshot == current.snapshot

internal fun temporalSnapshot(zone: ZoneId, observedTime: ResearchTime): TemporalSnapshot {
    val instant = Instant.ofEpochMilli(observedTime.wallTimeUtcMillis)
    return TemporalSnapshot(
        timezoneId = zone.id,
        utcOffsetSeconds = zone.rules.getOffset(instant).totalSeconds,
        daylightSavingTime = zone.rules.isDaylightSavings(instant),
    )
}

internal fun TemporalEvent.eventDraft() = EventDraft(
    collectorId = TemporalContextConfiguration.ID,
    payloadSchemaVersion = 1,
    observedTime = observedTime,
    payloadType = "TEMPORAL_CONTEXT",
    fields = mapOf(
        "change_reason" to reason,
        "timezone_id" to snapshot.timezoneId,
        "utc_offset_seconds" to snapshot.utcOffsetSeconds.toString(),
        "daylight_saving_time" to snapshot.daylightSavingTime.toString(),
    ),
)

internal fun RecordedEvent.temporalEventOrNull(): TemporalEvent? {
    if (
        collectorId != TemporalContextConfiguration.ID ||
        payloadSchemaVersion != 1 ||
        payloadType != "TEMPORAL_CONTEXT"
    ) return null
    val reason = fields["change_reason"]?.takeIf(TEMPORAL_REASONS::contains) ?: return null
    val timezoneId = fields["timezone_id"] ?: return null
    val utcOffsetSeconds = fields["utc_offset_seconds"]?.toIntOrNull() ?: return null
    val daylightSavingTime = fields["daylight_saving_time"]?.toBooleanStrictOrNull() ?: return null
    return TemporalEvent(
        reason,
        TemporalSnapshot(timezoneId, utcOffsetSeconds, daylightSavingTime),
        observedTime,
    )
}

private val TEMPORAL_REASONS = setOf(
    "RECONCILED",
    "STUDY_STARTED",
    "TIMEZONE_CHANGED",
    "TIME_SET",
    "UTC_OFFSET_CHANGED",
)
