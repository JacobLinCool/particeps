package cool.jacoblin.particeps.collector.accelerometer

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
import cool.jacoblin.particeps.core.definition.AccelerometerV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey

class AccelerometerCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = AccelerometerV1ProfileConfiguration.SOURCE_ID,
        displayName = "Accelerometer",
        accessKinds = setOf(AccessKind.ACCELEROMETER_HARDWARE),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[AccelerometerV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(
        configuration: CollectorProfileConfiguration,
        context: CollectorContext,
    ): Collector = AccelerometerCollector(
        applicationContext,
        configuration as? AccelerometerV1ProfileConfiguration
            ?: throw IllegalArgumentException("Invalid accelerometer configuration"),
        context,
    )
}

private class AccelerometerCollector(
    androidContext: Context,
    configuration: AccelerometerV1ProfileConfiguration,
    collectorContext: CollectorContext,
) : AndroidSensorCollector(
    androidContext = androidContext,
    collectorContext = collectorContext,
    sensorType = Sensor.TYPE_ACCELEROMETER,
    samplingPeriodUs = configuration.samplingPeriodUs.toInt(),
    maximumReportLatencyUs = configuration.maximumReportLatencyUs.toInt(),
    threadName = "particeps-accelerometer",
    queueCapacity = CHANNEL_CAPACITY,
) {
    override fun eventDraft(event: SensorEvent): EventDraft? {
        if (
            event.timestamp < 0 ||
            event.values.size < VECTOR_SIZE ||
            !event.values[0].isFinite() ||
            !event.values[1].isFinite() ||
            !event.values[2].isFinite()
        ) return null
        return EventDraft(
            type = EventTypeKey(
                EventSourceId(AccelerometerV1ProfileConfiguration.SOURCE_ID),
                1,
                "ACCELEROMETER_SAMPLE",
            ),
            observedTime = context.clocks.now(),
            fields = mapOf(
                "source_elapsed_realtime_nanos" to event.timestamp.toString(),
                "x_meters_per_second_squared" to event.values[0].toString(),
                "y_meters_per_second_squared" to event.values[1].toString(),
                "z_meters_per_second_squared" to event.values[2].toString(),
                "accuracy" to event.accuracy.toString(),
            ),
        )
    }

    private companion object {
        const val VECTOR_SIZE = 3
        const val CHANNEL_CAPACITY = 2_048
    }
}
