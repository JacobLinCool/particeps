package cool.jacoblin.particeps.collector.accelerometer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import cool.jacoblin.particeps.collector.sensorcommon.AndroidSensorCollector
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.definition.AccelerometerConfiguration
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.collector.PrivacyClass
import cool.jacoblin.particeps.core.collector.ProtocolEventContracts
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin

class AccelerometerCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = AccelerometerConfiguration.ID,
        displayName = "Accelerometer",
        privacyClass = PrivacyClass.SENSITIVE,
        eventContract = requireNotNull(ProtocolEventContracts[AccelerometerConfiguration.ID]),
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? AccelerometerConfiguration
            ?: throw IllegalArgumentException("Invalid accelerometer configuration")
        return setOf(AccessRequirement(AccessKind.ACCELEROMETER_HARDWARE, typed.required))
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector = AccelerometerCollector(
        applicationContext,
        configuration as? AccelerometerConfiguration
            ?: throw IllegalArgumentException("Invalid accelerometer configuration"),
        context,
    )
}

private class AccelerometerCollector(
    androidContext: Context,
    configuration: AccelerometerConfiguration,
    collectorContext: CollectorContext,
) : AndroidSensorCollector(
    androidContext = androidContext,
    collectorContext = collectorContext,
    sensorType = Sensor.TYPE_ACCELEROMETER,
    samplingPeriodUs = configuration.samplingPeriodUs,
    maximumReportLatencyUs = configuration.maximumReportLatencyUs,
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
            collectorId = AccelerometerConfiguration.ID,
            payloadSchemaVersion = 1,
            observedTime = context.clocks.now(),
            payloadType = "ACCELEROMETER_SAMPLE",
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
