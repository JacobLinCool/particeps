package cool.linc.androiddatacollector.collector.sensorcommon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.SerializedCallbackCollector
import cool.linc.androiddatacollector.core.collector.SourceCallbackBoundary
import cool.linc.androiddatacollector.core.collector.SourceRegistrationResult
import cool.linc.androiddatacollector.core.collector.SourceTeardownResult
import cool.linc.androiddatacollector.core.collector.completeSourceTeardown
import cool.linc.androiddatacollector.core.collector.registerSourceWithRollback
import cool.linc.androiddatacollector.core.model.EventDraft

/** Common listener-thread ownership for raw Android sensor collectors. */
abstract class AndroidSensorCollector(
    androidContext: Context,
    collectorContext: CollectorContext,
    private val sensorType: Int,
    private val samplingPeriodUs: Int,
    private val maximumReportLatencyUs: Int,
    private val threadName: String,
    queueCapacity: Int,
) : SerializedCallbackCollector(collectorContext, queueCapacity), SensorEventListener {
    private val sensorManager = androidContext.getSystemService(SensorManager::class.java)
    private val sensor by lazy {
        sensorManager.getDefaultSensor(sensorType)
            ?: throw IllegalStateException("Required sensor hardware is unavailable")
    }
    private val callbackBoundary = SourceCallbackBoundary()
    private var sourceThread: HandlerThread? = null
    protected var sourceHandler: Handler? = null
        private set

    final override fun onSensorChanged(event: SensorEvent) {
        sourceCallback {
            if (event.sensor.type == sensorType) onSensorEvent(event)
        }
    }

    final override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    protected open fun onSensorEvent(event: SensorEvent) {
        eventDraft(event)?.let(::emit)
    }

    protected open fun eventDraft(event: SensorEvent): EventDraft? = null

    protected fun emit(event: EventDraft) = capture { event }

    override suspend fun registerSource(): SourceRegistrationResult {
        onSourceRegistering()
        val thread = HandlerThread(threadName).also { it.start() }
        val handler = Handler(thread.looper)
        sourceThread = thread
        sourceHandler = handler
        callbackBoundary.activate()
        var listenerRegistered = false
        return registerSourceWithRollback(
            register = {
                check(
                    sensorManager.registerListener(
                        this,
                        sensor,
                        samplingPeriodUs,
                        maximumReportLatencyUs,
                        handler,
                    ),
                ) { "Android rejected the sensor listener" }
                listenerRegistered = true
            },
            rollback = {
                completeSourceTeardown(
                    { if (listenerRegistered) sensorManager.unregisterListener(this, sensor) },
                    { callbackBoundary.deactivate(::onSourceUnregistering) },
                    { releaseSource(thread, handler) },
                )
            },
        )
    }

    override suspend fun unregisterSource(): SourceTeardownResult {
        val handler = sourceHandler
        val thread = sourceThread
        completeSourceTeardown(
            { sensorManager.unregisterListener(this, sensor) },
            { callbackBoundary.deactivate(::onSourceUnregistering) },
            { releaseSource(thread, handler) },
        )
        return SourceTeardownResult.Released
    }

    private fun releaseSource(thread: HandlerThread?, handler: Handler?) {
        handler?.removeCallbacksAndMessages(null)
        sourceHandler = null
        thread?.quitSafely()
        sourceThread = null
    }

    protected open suspend fun onSourceRegistering() = Unit

    protected open fun onSourceUnregistering() = Unit

    /** Serializes sensor and handler callbacks against teardown of collector-specific state. */
    protected fun sourceCallback(block: () -> Unit) {
        callbackBoundary.runIfActive(block)
    }
}
