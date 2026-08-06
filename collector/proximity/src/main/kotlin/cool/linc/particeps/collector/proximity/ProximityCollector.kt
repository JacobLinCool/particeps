package cool.linc.particeps.collector.proximity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.SystemClock
import cool.linc.particeps.collector.sensorcommon.AndroidSensorCollector
import cool.linc.particeps.core.collector.AccessKind
import cool.linc.particeps.core.collector.AccessRequirement
import cool.linc.particeps.core.collector.Collector
import cool.linc.particeps.core.collector.CollectorContext
import cool.linc.particeps.core.collector.CollectorDescriptor
import cool.linc.particeps.core.collector.CollectorPlugin
import cool.linc.particeps.core.collector.LatestValueRateGate
import cool.linc.particeps.core.collector.PrivacyClass
import cool.linc.particeps.core.collector.ProtocolEventContracts
import cool.linc.particeps.core.definition.CollectorConfiguration
import cool.linc.particeps.core.definition.ProximityConfiguration
import cool.linc.particeps.core.model.EventDraft
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
import kotlin.math.abs

class ProximityCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = ProximityConfiguration.ID,
        displayName = "Proximity",
        privacyClass = PrivacyClass.SENSITIVE,
        eventContract = requireNotNull(ProtocolEventContracts[ProximityConfiguration.ID]),
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? ProximityConfiguration
            ?: throw IllegalArgumentException("Invalid proximity configuration")
        return setOf(AccessRequirement(AccessKind.PROXIMITY_HARDWARE, typed.required))
    }

    override fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector {
        val typed = configuration as? ProximityConfiguration
            ?: throw IllegalArgumentException("Invalid proximity configuration")
        return ProximityCollector(applicationContext, typed, context)
    }
}

private class ProximityCollector(
    androidContext: Context,
    private val configuration: ProximityConfiguration,
    collectorContext: CollectorContext,
) : AndroidSensorCollector(
    androidContext = androidContext,
    collectorContext = collectorContext,
    sensorType = Sensor.TYPE_PROXIMITY,
    samplingPeriodUs = configuration.minimumEventIntervalMs * 1_000,
    maximumReportLatencyUs = 0,
    threadName = "particeps-proximity",
    queueCapacity = 256,
) {
    private val rateGate = LatestValueRateGate<ProximitySample>(
        configuration.minimumEventIntervalMs.toLong(),
    ) { previous, current ->
        sameProximitySample(previous, current, configuration.changeThresholdMillimeters)
    }
    private var pendingScheduled = false
    private val pendingRunnable = Runnable { sourceCallback(::publishPending) }

    override suspend fun onSourceRegistering() {
        val latest = context.eventSink.latestEvent(ProximityConfiguration.ID) ?: return
        rateGate.restoreLastEmission(
            value = latest.proximitySampleOrNull(),
            currentElapsedMillis = SystemClock.elapsedRealtime(),
        )
    }

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
    changeThresholdMillimeters: Int,
): Boolean = previous.near == current.near &&
    previous.maximumRangeCentimeters == current.maximumRangeCentimeters &&
    (previous.distanceCentimeters == current.distanceCentimeters ||
        abs(previous.distanceCentimeters - current.distanceCentimeters) * 10 <
        changeThresholdMillimeters)

internal fun ProximitySample.eventDraft() = EventDraft(
    collectorId = ProximityConfiguration.ID,
    payloadSchemaVersion = 1,
    observedTime = observedTime,
    payloadType = "PROXIMITY_SAMPLE",
    fields = mapOf(
        "source_elapsed_realtime_nanos" to sourceTimestampNanos.toString(),
        "distance_centimeters" to distanceCentimeters.toString(),
        "maximum_range_centimeters" to maximumRangeCentimeters.toString(),
        "near" to near.toString(),
    ),
)

internal fun RecordedEvent.proximitySampleOrNull(): ProximitySample? {
    if (
        collectorId != ProximityConfiguration.ID ||
        payloadSchemaVersion != 1 ||
        payloadType != "PROXIMITY_SAMPLE"
    ) return null
    val sample = proximitySample(
        distanceCentimeters = fields["distance_centimeters"]?.toFloatOrNull() ?: return null,
        maximumRangeCentimeters = fields["maximum_range_centimeters"]?.toFloatOrNull() ?: return null,
        sourceTimestampNanos = fields["source_elapsed_realtime_nanos"]?.toLongOrNull() ?: return null,
        observedTime = observedTime,
    ) ?: return null
    val recordedNear = fields["near"]?.toBooleanStrictOrNull() ?: return null
    return sample.takeIf { it.near == recordedNear }
}
