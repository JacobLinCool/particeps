package cool.jacoblin.particeps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.collector.gyroscope.GyroscopeCollectorPlugin
import cool.jacoblin.particeps.collector.networkstate.NetworkStateCollectorPlugin
import cool.jacoblin.particeps.collector.networkthroughput.NetworkThroughputCollectorPlugin
import cool.jacoblin.particeps.collector.notificationevents.NotificationEventsCollectorPlugin
import cool.jacoblin.particeps.collector.screenstate.ScreenStateCollectorPlugin
import cool.jacoblin.particeps.collector.vpnstate.VpnStateCollectorPlugin
import cool.jacoblin.particeps.core.collector.*
import cool.jacoblin.particeps.core.definition.*
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.platform.AndroidResearchClocks
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudySourcesAndroidTest {
    @Test fun platformCallbacksPreserveMetadataAndStopAtPause() = runBlocking {
        val androidContext = ApplicationProvider.getApplicationContext<Context>()
        val manager = androidContext.getSystemService(NotificationManager::class.java)
        val component = ComponentName(androidContext.packageName, "cool.jacoblin.particeps.collector.notificationevents.ResearchNotificationListenerService")
        val hadAccess = manager.isNotificationListenerAccessGranted(component)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = Sink()
        val fixtures = listOf(
            GyroscopeCollectorPlugin(androidContext) to GyroscopeV1ProfileConfiguration(samplingPeriodUs = 1_000_000, maximumReportLatencyUs = 0),
            NetworkStateCollectorPlugin(androidContext) to NetworkStateV1ProfileConfiguration(true),
            VpnStateCollectorPlugin(androidContext) to VpnStateV1ProfileConfiguration(),
            ScreenStateCollectorPlugin(androidContext) to ScreenStateV1ProfileConfiguration(),
            NetworkThroughputCollectorPlugin() to NetworkThroughputV1ProfileConfiguration(5),
            NotificationEventsCollectorPlugin() to NotificationEventsV1ProfileConfiguration(),
        )
        val collectors = fixtures.map { (plugin, profile) ->
            plugin.create(profile, CollectorContext(scope, sink, AndroidResearchClocks(androidContext, "study-sources-test"),
                plugin.descriptor.sourceContract, 1, StudyScopedTokenEncoder { _, _ -> "a".repeat(64) }))
        }
        try {
            shell("pm grant ${androidContext.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            shell("cmd notification allow_listener ${component.flattenToString()}")
            collectors.forEach { collector ->
                withTimeout(10_000) {
                    while (true) {
                        try { collector.start(); break } catch (_: IllegalStateException) { delay(50) }
                    }
                }
                collector.onAdmissionOpened()
            }
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Research source test", NotificationManager.IMPORTANCE_DEFAULT))
            fun post() = manager.notify(8123, Notification.Builder(androidContext, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("private test title")
                .setContentText("private test body must not be collected").build())
            post()
            withTimeout(12_000) {
                while (fixtures.any { (plugin, _) -> sink.events().none { it.type.sourceId.value == plugin.descriptor.id } }) delay(50)
            }
            val notification = sink.events().last { it.type.sourceId.value == "notification_events.v1" }
            assertEquals(androidContext.packageName, notification.fields["package_name"])
            assertEquals(setOf("package_name", "notification_token", "post_time_epoch_millis"), notification.fields.keys)
            assertFalse(notification.fields.values.any { "private test" in it })
            shell("input keyevent KEYCODE_SLEEP")
            withTimeout(5_000) {
                while (sink.events().none { it.type.sourceId.value == "screen_state.v1" && it.fields["interactive"] == "false" }) delay(50)
            }
            val gyroWhileOff = sink.events().count { it.type.sourceId.value == "gyroscope.v1" }
            withTimeout(5_000) {
                while (sink.events().count { it.type.sourceId.value == "gyroscope.v1" } <= gyroWhileOff) delay(50)
            }
            shell("input keyevent KEYCODE_WAKEUP")
            withTimeout(5_000) {
                while (sink.events().last { it.type.sourceId.value == "screen_state.v1" }.fields["interactive"] != "true") delay(50)
            }
            for ((index, event) in sink.events().withIndex()) {
                assertTrue(requireNotNull(ProtocolEventSourceRegistry[event.type.sourceId.value]).accepts(event, index + 1L, null))
            }
            collectors.forEach { it.pause() }
            val paused = sink.events().size
            post()
            delay(750)
            assertEquals(paused, sink.events().size)
            collectors.forEach { it.resume(); it.onAdmissionOpened() }
            post()
            withTimeout(5_000) { while (sink.events().drop(paused).none { it.type.sourceId.value == "notification_events.v1" }) delay(50) }
            collectors.forEach { assertEquals(CollectorStatus.ACTIVE, it.health.value.status) }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            collectors.asReversed().filter { it.requiresStop }.forEach { it.stop() }
            manager.cancel(8123)
            manager.deleteNotificationChannel(CHANNEL)
            if (!hadAccess) shell("cmd notification disallow_listener ${component.flattenToString()}")
            scope.cancel()
        }
    }
    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
    private class Sink : EventSink {
        private val token = object : AdmissionToken {}
        private val recorded = mutableListOf<EventDraft>()
        private var sequence = 0L
        fun events(): List<EventDraft> = synchronized(recorded) { recorded.toList() }
        override fun captureToken(): AdmissionToken = token
        override fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken? = null
        override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult = synchronized(recorded) {
            recorded.addAll(batch.events)
            EmitBatchResult.Accepted(++sequence)
        }
        override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult = error("Live sources only")
    }
    private companion object { const val CHANNEL = "study-source-test" }
}
