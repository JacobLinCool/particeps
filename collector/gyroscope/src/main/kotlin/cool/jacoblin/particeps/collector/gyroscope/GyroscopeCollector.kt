package cool.jacoblin.particeps.collector.gyroscope

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import cool.jacoblin.particeps.collector.sensorcommon.AndroidSensorCollector
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.GyroscopeV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime

class GyroscopeCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = GyroscopeV1ProfileConfiguration.SOURCE_ID,
        displayName = "Gyroscope",
        accessKinds = setOf(AccessKind.GYROSCOPE_HARDWARE),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[GyroscopeV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
        val typed = configuration as? GyroscopeV1ProfileConfiguration
            ?: throw IllegalArgumentException("Invalid gyroscope configuration")
        return GyroscopeCollector(applicationContext, typed, context)
    }
}

private class GyroscopeCollector(
    androidContext: Context,
    configuration: GyroscopeV1ProfileConfiguration,
    collectorContext: CollectorContext,
) : AndroidSensorCollector(
    androidContext = androidContext,
    collectorContext = collectorContext,
    sensorType = Sensor.TYPE_GYROSCOPE,
    samplingPeriodUs = configuration.samplingPeriodUs.toInt(),
    maximumReportLatencyUs = configuration.maximumReportLatencyUs.toInt(),
    threadName = "particeps-gyroscope",
    queueCapacity = 2_048,
) {
    override fun eventDraft(event: SensorEvent): EventDraft? = gyroscopeEvent(
        values = event.values,
        timestampNanos = event.timestamp,
        accuracy = event.accuracy,
        observedTime = context.clocks.now(),
    )
}

internal fun gyroscopeEvent(
    values: FloatArray,
    timestampNanos: Long,
    accuracy: Int,
    observedTime: ResearchTime,
): EventDraft? {
    if (
        timestampNanos < 0 ||
        values.size < 3 ||
        !values[0].isFinite() ||
        !values[1].isFinite() ||
        !values[2].isFinite()
    ) return null
    return EventDraft(
        type = EventTypeKey(
            EventSourceId(GyroscopeV1ProfileConfiguration.SOURCE_ID),
            1,
            "GYROSCOPE_SAMPLE",
        ),
        observedTime = observedTime,
        fields = mapOf(
            "source_elapsed_realtime_nanos" to timestampNanos.toString(),
            "x_radians_per_second" to values[0].toString(),
            "y_radians_per_second" to values[1].toString(),
            "z_radians_per_second" to values[2].toString(),
            "accuracy" to accuracy.toString(),
        )
    )
}
