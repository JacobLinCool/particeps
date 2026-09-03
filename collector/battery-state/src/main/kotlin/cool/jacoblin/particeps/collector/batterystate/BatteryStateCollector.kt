package cool.jacoblin.particeps.collector.batterystate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.LatestValueRateGate
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.SerializedCallbackCollector
import cool.jacoblin.particeps.core.collector.SourceRegistrationResult
import cool.jacoblin.particeps.core.collector.SourceTeardownResult
import cool.jacoblin.particeps.core.collector.completeSourceTeardown
import cool.jacoblin.particeps.core.collector.registerSourceWithRollback
import cool.jacoblin.particeps.core.definition.BatteryStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BatteryStateCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = BatteryStateV1ProfileConfiguration.SOURCE_ID,
        displayName = "Battery state",
        accessKinds = emptySet(),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[BatteryStateV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
        require(configuration is BatteryStateV1ProfileConfiguration) { "Invalid battery-state configuration" }
        return BatteryStateCollector(applicationContext, context)
    }
}

private class BatteryStateCollector(
    private val applicationContext: Context,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, 64) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val rateGate = LatestValueRateGate<BatterySnapshot>(
        MINIMUM_INTERVAL_MILLIS,
        ::sameBatteryState,
    )
    private val pendingRunnable = Runnable(::publishPending)
    private var pendingScheduled = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val battery = if (intent?.action == Intent.ACTION_BATTERY_CHANGED) intent else batteryIntent()
            battery?.let(::snapshot)?.let(::offer)
        }
    }

    override suspend fun registerSource(): SourceRegistrationResult = withContext(Dispatchers.Main.immediate) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        var receiverRegistered = false
        registerSourceWithRollback(
            register = {
                val sticky = applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
                sticky?.let(::snapshot)?.let(::offer)
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

    private fun batteryIntent(): Intent? = applicationContext.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    )

    private fun snapshot(intent: Intent): BatterySnapshot? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = batteryPercentage(level, scale) ?: return null
        return BatterySnapshot(
            observedTime = context.clocks.now(),
            percentage = percentage,
            chargingState = chargingState(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)),
            chargingSource = chargingSource(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
            powerSaveEnabled = powerManager.isPowerSaveMode,
        )
    }

    private fun offer(snapshot: BatterySnapshot) {
        handle(rateGate.offer(snapshot, SystemClock.elapsedRealtime()))
    }

    private fun publishPending() {
        pendingScheduled = false
        handle(rateGate.poll(SystemClock.elapsedRealtime()))
    }

    private fun handle(decision: LatestValueRateGate.Decision<BatterySnapshot>) {
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

    private fun publish(snapshot: BatterySnapshot) {
        capture(snapshot::eventDraft)
    }

    private companion object { const val MINIMUM_INTERVAL_MILLIS = 60_000L }
}

internal data class BatterySnapshot(
    val observedTime: ResearchTime,
    val percentage: Int,
    val chargingState: String,
    val chargingSource: String,
    val powerSaveEnabled: Boolean,
)

internal fun sameBatteryState(previous: BatterySnapshot, current: BatterySnapshot): Boolean =
    previous.percentage == current.percentage &&
        previous.chargingState == current.chargingState &&
        previous.chargingSource == current.chargingSource &&
        previous.powerSaveEnabled == current.powerSaveEnabled

internal fun batteryPercentage(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else ((level.toLong() * 100) / scale).toInt().coerceIn(0, 100)

internal fun chargingState(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
    BatteryManager.BATTERY_STATUS_FULL -> "FULL"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
    else -> "UNKNOWN"
}

internal fun chargingSource(bits: Int): String {
    val sources = buildList {
        if (bits and BatteryManager.BATTERY_PLUGGED_AC != 0) add("AC")
        if (bits and BatteryManager.BATTERY_PLUGGED_USB != 0) add("USB")
        if (bits and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) add("WIRELESS")
        if (bits and BatteryManager.BATTERY_PLUGGED_DOCK != 0) add("DOCK")
    }
    return when (sources.size) {
        0 -> if (bits == 0) "NONE" else "UNKNOWN"
        1 -> sources.single()
        else -> "MULTIPLE"
    }
}

internal fun BatterySnapshot.eventDraft() = EventDraft(
    type = EventTypeKey(EventSourceId(BatteryStateV1ProfileConfiguration.SOURCE_ID), 1, "BATTERY_STATE"),
    observedTime = observedTime,
    fields = mapOf(
        "percentage" to percentage.toString(),
        "charging_state" to chargingState,
        "charging_source" to chargingSource,
        "power_save_enabled" to powerSaveEnabled.toString(),
    ),
)
