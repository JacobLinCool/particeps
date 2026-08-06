package cool.jacoblin.particeps.collector.ambientlight

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.SystemClock
import cool.jacoblin.particeps.collector.sensorcommon.AndroidSensorCollector
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.LatestValueRateGate
import cool.jacoblin.particeps.core.collector.PrivacyClass
import cool.jacoblin.particeps.core.collector.ProtocolEventContracts
import cool.jacoblin.particeps.core.definition.AmbientLightConfiguration
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import kotlin.math.abs

class AmbientLightCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = AmbientLightConfiguration.ID,
        displayName = "Ambient light",
        privacyClass = PrivacyClass.SENSITIVE,
        eventContract = requireNotNull(ProtocolEventContracts[AmbientLightConfiguration.ID]),
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? AmbientLightConfiguration
            ?: throw IllegalArgumentException("Invalid ambient-light configuration")
        return setOf(AccessRequirement(AccessKind.AMBIENT_LIGHT_HARDWARE, typed.required))
    }

    override fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector {
        val typed = configuration as? AmbientLightConfiguration
            ?: throw IllegalArgumentException("Invalid ambient-light configuration")
        return AmbientLightCollector(applicationContext, typed, context)
    }
}

private class AmbientLightCollector(
    androidContext: Context,
    private val configuration: AmbientLightConfiguration,
    collectorContext: CollectorContext,
) : AndroidSensorCollector(
    androidContext = androidContext,
    collectorContext = collectorContext,
    sensorType = Sensor.TYPE_LIGHT,
    samplingPeriodUs = configuration.samplingPeriodUs,
    maximumReportLatencyUs = 0,
    threadName = "particeps-ambient-light",
    queueCapacity = 256,
) {
    private val rateGate = LatestValueRateGate<AmbientLightSample>(
        (configuration.samplingPeriodUs + 999L) / 1_000L,
    ) { previous, current ->
        sameAmbientLightSample(previous, current, configuration.changeThresholdMillilux)
    }
    private var pendingScheduled = false
    private val pendingRunnable = Runnable { sourceCallback(::publishPending) }

    override suspend fun onSourceRegistering() {
        val latest = context.eventSink.latestEvent(AmbientLightConfiguration.ID) ?: return
        rateGate.restoreLastEmission(
            value = latest.ambientLightSampleOrNull(),
            currentElapsedMillis = SystemClock.elapsedRealtime(),
        )
    }

    override fun onSensorEvent(event: SensorEvent) {
        val sample = ambientLightSample(
            lux = event.values.firstOrNull() ?: return,
            sourceTimestampNanos = event.timestamp,
            accuracy = event.accuracy,
            observedTime = context.clocks.now(),
        ) ?: return
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

    private fun handle(decision: LatestValueRateGate.Decision<AmbientLightSample>) {
        when (decision) {
            is LatestValueRateGate.Decision.Emit -> {
                sourceHandler?.removeCallbacks(pendingRunnable)
                pendingScheduled = false
                emit(decision.value.eventDraft())
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
}

internal data class AmbientLightSample(
    val observedTime: ResearchTime,
    val sourceTimestampNanos: Long,
    val illuminanceLux: Float,
    val accuracy: Int,
)

internal fun ambientLightSample(
    lux: Float,
    sourceTimestampNanos: Long,
    accuracy: Int,
    observedTime: ResearchTime,
): AmbientLightSample? = if (!lux.isFinite() || lux < 0 || sourceTimestampNanos < 0) {
    null
} else {
    AmbientLightSample(observedTime, sourceTimestampNanos, lux, accuracy)
}

internal fun sameAmbientLightSample(
    previous: AmbientLightSample,
    current: AmbientLightSample,
    changeThresholdMillilux: Int,
): Boolean = previous.illuminanceLux == current.illuminanceLux ||
    abs(previous.illuminanceLux - current.illuminanceLux) * 1_000 < changeThresholdMillilux

internal fun AmbientLightSample.eventDraft() = EventDraft(
    collectorId = AmbientLightConfiguration.ID,
    payloadSchemaVersion = 1,
    observedTime = observedTime,
    payloadType = "AMBIENT_LIGHT_SAMPLE",
    fields = mapOf(
        "source_elapsed_realtime_nanos" to sourceTimestampNanos.toString(),
        "illuminance_lux" to illuminanceLux.toString(),
        "accuracy" to accuracy.toString(),
    ),
)

internal fun RecordedEvent.ambientLightSampleOrNull(): AmbientLightSample? {
    if (
        collectorId != AmbientLightConfiguration.ID ||
        payloadSchemaVersion != 1 ||
        payloadType != "AMBIENT_LIGHT_SAMPLE"
    ) return null
    return ambientLightSample(
        lux = fields["illuminance_lux"]?.toFloatOrNull() ?: return null,
        sourceTimestampNanos = fields["source_elapsed_realtime_nanos"]?.toLongOrNull() ?: return null,
        accuracy = fields["accuracy"]?.toIntOrNull() ?: return null,
        observedTime = observedTime,
    )
}
