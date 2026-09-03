package cool.jacoblin.particeps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.collector.ambientlight.AmbientLightCollectorPlugin
import cool.jacoblin.particeps.collector.batterystate.BatteryStateCollectorPlugin
import cool.jacoblin.particeps.collector.gyroscope.GyroscopeCollectorPlugin
import cool.jacoblin.particeps.collector.proximity.ProximityCollectorPlugin
import cool.jacoblin.particeps.collector.temporalcontext.TemporalContextCollectorPlugin
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.StudyScopedTokenEncoder
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.definition.AmbientLightV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.BatteryStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.GyroscopeV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.ProximityV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.TemporalContextV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.platform.AndroidResearchClocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the P2 Android sources through the current batch-only collector contract. */
@RunWith(AndroidJUnit4::class)
class P2CollectorEmulatorTest {
    @Test
    fun p2CollectorsCaptureTypedBatchesAndHonorLifecycleBoundaries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val syntheticInputsExpected = syntheticInputsExpected()
        requireSensorFixture(context, syntheticInputsExpected)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = RecordingEventSink()
        val fixtures = fixtures(context)
        val collectors = fixtures.map { (plugin, configuration) ->
            plugin.create(
                configuration,
                CollectorContext(
                    scope = scope,
                    eventSink = sink,
                    clocks = AndroidResearchClocks(context, "p2-emulator-test"),
                    sourceContract = plugin.descriptor.sourceContract,
                    resourceGeneration = RESOURCE_GENERATION,
                    tokenEncoder = StudyScopedTokenEncoder { domain, value -> "$domain:$value" },
                ),
            )
        }

        try {
            collectors.forEach { it.start() }
            collectors.forEach { it.onAdmissionOpened() }
            waitForCollectors(sink)

            collectors.forEach { collector ->
                assertEquals(CollectorStatus.ACTIVE, collector.health.value.status)
            }
            fixtures.forEach { (plugin, _) ->
                val captured = sink.latestCaptured(plugin.descriptor.id)
                assertEquals(RESOURCE_GENERATION, captured.resourceGeneration)
                assertTrue(
                    "${plugin.descriptor.id} emitted an event outside its Protocol v1 contract",
                    plugin.descriptor.sourceContract.accepts(
                        captured.event,
                        captured.eventSequence,
                        conditionEpochId = null,
                    ),
                )
            }
            assertPayloadSemantics(sink)
            if (syntheticInputsExpected) assertSyntheticInputs(sink)

            collectors.forEach { it.pause() }
            collectors.forEach { collector ->
                assertEquals(CollectorStatus.PAUSED, collector.health.value.status)
            }
            val countAtPause = sink.size
            delay(PAUSE_SETTLE_MILLIS)
            assertEquals(countAtPause, sink.size)

            collectors.forEach { it.resume() }
            collectors.forEach { it.onAdmissionOpened() }
            collectors.forEach { collector ->
                assertEquals(CollectorStatus.ACTIVE, collector.health.value.status)
            }
            withTimeout(EVENT_TIMEOUT_MILLIS) {
                while (sink.count(GyroscopeV1ProfileConfiguration.SOURCE_ID) < 2) delay(POLL_MILLIS)
            }

            collectors.asReversed().forEach { it.stop() }
            collectors.forEach { collector ->
                assertEquals(CollectorStatus.STOPPED, collector.health.value.status)
                assertFalse(collector.requiresStop)
            }
        } finally {
            collectors.asReversed().filter(Collector::requiresStop).forEach { collector ->
                runCatching { collector.stop() }
            }
            scope.cancel()
        }
    }

    private fun fixtures(context: Context): List<Pair<CollectorPlugin, CollectorProfileConfiguration>> = listOf(
        BatteryStateCollectorPlugin(context) to BatteryStateV1ProfileConfiguration(),
        TemporalContextCollectorPlugin(context) to TemporalContextV1ProfileConfiguration(),
        GyroscopeCollectorPlugin(context) to GyroscopeV1ProfileConfiguration(
            samplingPeriodUs = 20_000,
            maximumReportLatencyUs = 0,
        ),
        AmbientLightCollectorPlugin(context) to AmbientLightV1ProfileConfiguration(
            samplingPeriodUs = 200_000,
            changeThresholdMillilux = 0,
        ),
        ProximityCollectorPlugin(context) to ProximityV1ProfileConfiguration(
            minimumEventIntervalMs = 100,
            changeThresholdMillimeters = 0,
        ),
    )

    private fun requireSensorFixture(context: Context, syntheticInputsExpected: Boolean) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val missing = REQUIRED_SENSOR_TYPES.filter { sensorManager.getDefaultSensor(it) == null }
        if (syntheticInputsExpected) {
            assertTrue("Controlled emulator is missing sensor types $missing", missing.isEmpty())
        } else {
            assumeTrue("P2 sensor integration fixture is unavailable: $missing", missing.isEmpty())
        }
    }

    private suspend fun waitForCollectors(sink: RecordingEventSink) {
        withTimeout(EVENT_TIMEOUT_MILLIS) {
            while (!P2_COLLECTOR_IDS.all { sink.count(it) > 0 }) delay(POLL_MILLIS)
        }
    }

    private fun assertPayloadSemantics(sink: RecordingEventSink) {
        val battery = sink.latestEventDraft(BatteryStateV1ProfileConfiguration.SOURCE_ID)
        assertTrue(requireNotNull(battery.fields["percentage"]).toInt() in 0..100)

        val temporal = sink.latestEventDraft(TemporalContextV1ProfileConfiguration.SOURCE_ID)
        assertEquals("STUDY_STARTED", temporal.fields["change_reason"])
        assertTrue(requireNotNull(temporal.fields["timezone_id"]).isNotBlank())

        val gyroscope = sink.latestEventDraft(GyroscopeV1ProfileConfiguration.SOURCE_ID)
        GYROSCOPE_FIELDS.forEach { field ->
            assertTrue(requireNotNull(gyroscope.fields[field]).toFloat().isFinite())
        }

        val light = sink.latestEventDraft(AmbientLightV1ProfileConfiguration.SOURCE_ID)
        assertTrue(requireNotNull(light.fields["illuminance_lux"]).toFloat() >= 0f)

        val proximity = sink.latestEventDraft(ProximityV1ProfileConfiguration.SOURCE_ID)
        val distance = requireNotNull(proximity.fields["distance_centimeters"]).toFloat()
        val maximumRange = requireNotNull(proximity.fields["maximum_range_centimeters"]).toFloat()
        assertEquals((distance < maximumRange).toString(), proximity.fields["near"])
    }

    private fun assertSyntheticInputs(sink: RecordingEventSink) {
        val battery = sink.latestEventDraft(BatteryStateV1ProfileConfiguration.SOURCE_ID)
        assertEquals("73", battery.fields["percentage"])
        assertEquals("CHARGING", battery.fields["charging_state"])
        assertEquals("AC", battery.fields["charging_source"])

        val gyroscope = sink.latestEventDraft(GyroscopeV1ProfileConfiguration.SOURCE_ID)
        assertEquals(1.25f, requireNotNull(gyroscope.fields["x_radians_per_second"]).toFloat(), FLOAT_TOLERANCE)
        assertEquals(-2.5f, requireNotNull(gyroscope.fields["y_radians_per_second"]).toFloat(), FLOAT_TOLERANCE)
        assertEquals(0.5f, requireNotNull(gyroscope.fields["z_radians_per_second"]).toFloat(), FLOAT_TOLERANCE)

        val light = sink.latestEventDraft(AmbientLightV1ProfileConfiguration.SOURCE_ID)
        assertEquals(123f, requireNotNull(light.fields["illuminance_lux"]).toFloat(), FLOAT_TOLERANCE)

        val proximity = sink.latestEventDraft(ProximityV1ProfileConfiguration.SOURCE_ID)
        assertEquals(1f, requireNotNull(proximity.fields["distance_centimeters"]).toFloat(), FLOAT_TOLERANCE)
    }

    private fun syntheticInputsExpected(): Boolean = when (
        val value = InstrumentationRegistry.getArguments().getString(SYNTHETIC_INPUTS_ARGUMENT)
    ) {
        null, "false" -> false
        "true" -> true
        else -> error("$SYNTHETIC_INPUTS_ARGUMENT must be true or false")
    }

    private data class CapturedEvent(
        val eventSequence: Long,
        val resourceGeneration: Long,
        val producerOrdinal: Long,
        val event: EventDraft,
    )

    private class RecordingEventSink : EventSink {
        private val token = object : AdmissionToken {}
        private val events = mutableListOf<CapturedEvent>()
        private var nextObservationSequence = 1L
        private var nextEventSequence = 1L

        val size: Int
            get() = synchronized(events) { events.size }

        override fun captureToken(): AdmissionToken = token

        override fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken? = null

        override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult =
            synchronized(events) {
                check(token === this.token) { "Unexpected admission token" }
                val observationSequence = nextObservationSequence++
                batch.events.forEach { event ->
                    events += CapturedEvent(
                        eventSequence = nextEventSequence++,
                        resourceGeneration = batch.resourceGeneration,
                        producerOrdinal = batch.producerOrdinal,
                        event = event,
                    )
                }
                EmitBatchResult.Accepted(
                    observationSequence = observationSequence,
                )
            }

        override suspend fun advanceCoverage(
            token: AdmissionToken,
            advance: CoverageAdvance,
        ): EmitBatchResult = error("Live P2 collectors cannot advance retrospective coverage")

        fun count(sourceId: String): Int = synchronized(events) {
            events.count { it.event.type.sourceId.value == sourceId }
        }

        fun latestCaptured(sourceId: String): CapturedEvent = synchronized(events) {
            requireNotNull(events.lastOrNull { it.event.type.sourceId.value == sourceId })
        }

        fun latestEventDraft(sourceId: String): EventDraft = latestCaptured(sourceId).event
    }

    private companion object {
        const val RESOURCE_GENERATION = 1L
        const val EVENT_TIMEOUT_MILLIS = 10_000L
        const val PAUSE_SETTLE_MILLIS = 750L
        const val POLL_MILLIS = 25L
        const val FLOAT_TOLERANCE = 0.001f
        const val SYNTHETIC_INPUTS_ARGUMENT = "p2SyntheticInputs"
        val P2_COLLECTOR_IDS = setOf(
            BatteryStateV1ProfileConfiguration.SOURCE_ID,
            TemporalContextV1ProfileConfiguration.SOURCE_ID,
            GyroscopeV1ProfileConfiguration.SOURCE_ID,
            AmbientLightV1ProfileConfiguration.SOURCE_ID,
            ProximityV1ProfileConfiguration.SOURCE_ID,
        )
        val GYROSCOPE_FIELDS = setOf(
            "x_radians_per_second",
            "y_radians_per_second",
            "z_radians_per_second",
        )
        val REQUIRED_SENSOR_TYPES = setOf(
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
        )
    }
}
