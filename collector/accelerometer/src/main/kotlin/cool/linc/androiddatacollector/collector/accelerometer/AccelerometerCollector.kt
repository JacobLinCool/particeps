package cool.linc.androiddatacollector.collector.accelerometer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.definition.AccelerometerConfiguration
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.SerializedCallbackCollector

class AccelerometerCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = AccelerometerConfiguration.ID,
        payloadSchemaVersion = 1,
        displayName = "Accelerometer",
        privacyClass = PrivacyClass.SENSITIVE,
        maximumEncodedEventBytes = 2_048,
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
    private val configuration: AccelerometerConfiguration,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY),
    SensorEventListener {
    private val sensorManager = androidContext.getSystemService(SensorManager::class.java)
    private val sensor by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: throw IllegalStateException("Accelerometer hardware is unavailable")
    }
    private var handlerThread: HandlerThread? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < VECTOR_SIZE) return
        capture {
            EventDraft(
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
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) = Unit

    override suspend fun registerSource() {
        val thread = HandlerThread("adc-accelerometer").also { it.start() }
        try {
            check(
                sensorManager.registerListener(
                    this,
                    sensor,
                    configuration.samplingPeriodUs,
                    configuration.maximumReportLatencyUs,
                    Handler(thread.looper),
                ),
            ) { "Android rejected the accelerometer listener" }
            handlerThread = thread
        } catch (failure: Throwable) {
            thread.quitSafely()
            throw failure
        }
    }

    override suspend fun unregisterSource() {
        sensorManager.unregisterListener(this, sensor)
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private companion object {
        const val VECTOR_SIZE = 3
        const val CHANNEL_CAPACITY = 2_048
    }
}
