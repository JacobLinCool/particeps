package cool.jacoblin.particeps.collector.proximity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.SystemClock
import cool.jacoblin.particeps.collector.sensorcommon.AndroidSensorCollector
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.LatestValueRateGate
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.ProximityV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import kotlin.math.abs

class ProximityCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = ProximityV1ProfileConfiguration.SOURCE_ID,
        displayName = "Proximity",
        accessKinds = setOf(AccessKind.PROXIMITY_HARDWARE),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[ProximityV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
        val typed = configuration as? ProximityV1ProfileConfiguration
            ?: throw IllegalArgumentException("Invalid proximity configuration")
        return ProximityCollector(applicationContext, typed, context)
    }
}

private class ProximityCollector(
    androidContext: Context,
    private val configuration: ProximityV1ProfileConfiguration,
    collectorContext: CollectorContext,
) : AndroidSensorCollector(
    androidContext = androidContext,
    collectorContext = collectorContext,
    sensorType = Sensor.TYPE_PROXIMITY,
    samplingPeriodUs = (configuration.minimumEventIntervalMs * 1_000).toInt(),
    maximumReportLatencyUs = 0,
    threadName = "particeps-proximity",
    queueCapacity = 256,
) {
    private val rateGate = LatestValueRateGate<ProximitySample>(
        configuration.minimumEventIntervalMs,
    ) { previous, current ->
        sameProximitySample(previous, current, configuration.changeThresholdMillimeters)
    }
    private var pendingScheduled = false
    private val pendingRunnable = Runnable { sourceCallback(::publishPending) }

    override fun onSensorEvent(event: SensorEvent) {
        val distance = event.values.firstOrNull() ?: return
        val maximumRange = event.sensor.maximumRange
        val sample = proximitySample(distance, maximumRange, event.timestamp, context.clocks.now()) ?: return
        handle(rateGate.offer(sample, SystemClock.elapsedRealtime()))
    }

    override fun onSourceUnregistering() {
        sourceHandler?.removeCallbacks(pendingRunnable)
        rateGate.clearPending()
        pendingScheduled = false
    }

    private fun publishPending() {
        pendingScheduled = false
        handle(rateGate.poll(SystemClock.elapsedRealtime()))
    }

    private fun handle(decision: LatestValueRateGate.Decision<ProximitySample>) {
        when (decision) {
            is LatestValueRateGate.Decision.Emit -> {
                sourceHandler?.removeCallbacks(pendingRunnable)
                pendingScheduled = false
                publish(decision.value)
            }
            is LatestValueRateGate.Decision.Defer -> if (!pendingScheduled) {
                pendingScheduled = true
                sourceHandler?.postDelayed(pendingRunnable, decision.delayMillis)
            }
            LatestValueRateGate.Decision.Suppress -> {
                sourceHandler?.removeCallbacks(pendingRunnable)
                pendingScheduled = false
            }
        }
    }

    private fun publish(sample: ProximitySample) = emit(sample.eventDraft())
}

internal data class ProximitySample(
    val observedTime: ResearchTime,
    val sourceTimestampNanos: Long,
    val distanceCentimeters: Float,
    val maximumRangeCentimeters: Float,
    val near: Boolean,
)

internal fun proximitySample(
    distanceCentimeters: Float,
    maximumRangeCentimeters: Float,
    sourceTimestampNanos: Long,
    observedTime: ResearchTime,
): ProximitySample? {
    if (
        !distanceCentimeters.isFinite() ||
        !maximumRangeCentimeters.isFinite() ||
        distanceCentimeters < 0 ||
        maximumRangeCentimeters < 0 ||
        sourceTimestampNanos < 0
    ) return null
    return ProximitySample(
        observedTime = observedTime,
        sourceTimestampNanos = sourceTimestampNanos,
        distanceCentimeters = distanceCentimeters,
        maximumRangeCentimeters = maximumRangeCentimeters,
        near = distanceCentimeters < maximumRangeCentimeters,
    )
}

internal fun sameProximitySample(
    previous: ProximitySample,
    current: ProximitySample,
    changeThresholdMillimeters: Long,
): Boolean = previous.near == current.near &&
    previous.maximumRangeCentimeters == current.maximumRangeCentimeters &&
    (previous.distanceCentimeters == current.distanceCentimeters ||
        abs(previous.distanceCentimeters - current.distanceCentimeters) * 10 <
        changeThresholdMillimeters)

internal fun ProximitySample.eventDraft() = EventDraft(
    type = EventTypeKey(EventSourceId(ProximityV1ProfileConfiguration.SOURCE_ID), 1, "PROXIMITY_SAMPLE"),
    observedTime = observedTime,
    fields = mapOf(
        "source_elapsed_realtime_nanos" to sourceTimestampNanos.toString(),
        "distance_centimeters" to distanceCentimeters.toString(),
        "maximum_range_centimeters" to maximumRangeCentimeters.toString(),
        "near" to near.toString(),
    ),
)
