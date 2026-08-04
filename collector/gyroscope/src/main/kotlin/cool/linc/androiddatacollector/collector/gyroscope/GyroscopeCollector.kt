package cool.linc.androiddatacollector.collector.gyroscope

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import cool.linc.androiddatacollector.collector.sensorcommon.AndroidSensorCollector
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.ProtocolEventContracts
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.GyroscopeConfiguration
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.model.ResearchTime

class GyroscopeCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = GyroscopeConfiguration.ID,
        displayName = "Gyroscope",
        privacyClass = PrivacyClass.SENSITIVE,
        eventContract = requireNotNull(ProtocolEventContracts[GyroscopeConfiguration.ID]),
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? GyroscopeConfiguration
            ?: throw IllegalArgumentException("Invalid gyroscope configuration")
        return setOf(AccessRequirement(AccessKind.GYROSCOPE_HARDWARE, typed.required))
    }

    override fun create(configuration: CollectorConfiguration, context: CollectorContext): Collector {
        val typed = configuration as? GyroscopeConfiguration
            ?: throw IllegalArgumentException("Invalid gyroscope configuration")
        return GyroscopeCollector(applicationContext, typed, context)
    }
}

private class GyroscopeCollector(
    androidContext: Context,
    configuration: GyroscopeConfiguration,
    collectorContext: CollectorContext,
) : AndroidSensorCollector(
    androidContext = androidContext,
    collectorContext = collectorContext,
    sensorType = Sensor.TYPE_GYROSCOPE,
    samplingPeriodUs = configuration.samplingPeriodUs,
    maximumReportLatencyUs = configuration.maximumReportLatencyUs,
    threadName = "adc-gyroscope",
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
        collectorId = GyroscopeConfiguration.ID,
        payloadSchemaVersion = 1,
        observedTime = observedTime,
        payloadType = "GYROSCOPE_SAMPLE",
        fields = mapOf(
            "source_elapsed_realtime_nanos" to timestampNanos.toString(),
            "x_radians_per_second" to values[0].toString(),
            "y_radians_per_second" to values[1].toString(),
            "z_radians_per_second" to values[2].toString(),
            "accuracy" to accuracy.toString(),
        )
    )
}
