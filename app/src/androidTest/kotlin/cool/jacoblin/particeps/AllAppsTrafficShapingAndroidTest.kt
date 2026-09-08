package cool.jacoblin.particeps

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.collector.vpnstate.VpnStateCollectorPlugin
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.StudyScopedTokenEncoder
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.definition.TrafficShapingProfile
import cool.jacoblin.particeps.core.definition.VpnStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.platform.AndroidResearchClocks
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host-controlled integration: a local server sends 262144 ASCII Z bytes after receiving one byte. */
@RunWith(AndroidJUnit4::class)
class AllAppsTrafficShapingAndroidTest {
    @Test fun allAppsIncludesTheResearchAppAndForwardsThroughTheCappedVpn() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString("traffic_test_endpoint")
        assumeTrue("Requires the local TCP test server", endpoint != null)
        val address = requireNotNull(endpoint).split(':')
        require(address.size == 2)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vpnEvents = VpnEventSink()
        val plugin = VpnStateCollectorPlugin(context)
        val vpnCollector = plugin.create(
            VpnStateV1ProfileConfiguration(),
            CollectorContext(
                scope, vpnEvents, AndroidResearchClocks(context, "all-apps-vpn-state-test"),
                plugin.descriptor.sourceContract, 1, StudyScopedTokenEncoder { _, _ -> "a".repeat(64) },
            ),
        )
        val actuator = TrafficShapingActuator.createAndroid(
            context = context,
            targetPackages = emptyList(),
            allApps = true,
            notificationFactory = { CollectionService.trafficShapingForegroundNotification(it, "Integration test") },
        )
        val profile = SignedResourceProfile("limited-500", TrafficShapingProfile("limited-500", 500, 500).canonicalBytes())
        val desired = DesiredResourceState(ResourceKey(ResourceKind.ACTUATOR, TrafficShapingActuator.RESOURCE_ID), ResourceGeneration(1uL), true, profile)
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        try {
            vpnCollector.start()
            vpnCollector.onAdmissionOpened()
            withTimeout(5_000) { while (vpnEvents.events().isEmpty()) delay(50) }
            actuator.prepare(desired, UUID.randomUUID().toString())
            actuator.apply(desired)
            assertTrue(actuator.verify(desired).healthy)
            assertTrue(actuator.resume(desired).resumed)
            withTimeout(5_000) {
                while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) delay(50)
            }
            withTimeout(5_000) {
                while (vpnEvents.events().lastOrNull()?.fields?.get("connected") != "true") delay(50)
            }
            // The research app is deliberately absent from any selected-package list. Its default
            // socket must traverse the all-app VPN, while the engine's protected sockets avoid loops.
            val before = android.os.SystemClock.elapsedRealtime()
            val payload = Socket().use { socket ->
                socket.soTimeout = 15_000
                socket.connect(InetSocketAddress(address[0], address[1].toInt()), 5_000)
                socket.getOutputStream().write(1)
                socket.getInputStream().readBytes()
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - before
            assertEquals(262_144, payload.size)
            assertTrue(payload.all { it == 'Z'.code.toByte() })
            // A generous bound detects an uncapped path without asserting exact packet timing.
            assertTrue("256 KiB crossed the 500 kbps VPN in only $elapsed ms", elapsed >= 2_000)
            android.util.Log.i("AllAppsTrafficTest", "Transferred ${payload.size} bytes in $elapsed ms")
            assertEquals(ResourceHealthStatus.APPLIED, actuator.health().status)
            actuator.release(desired)
            withTimeout(5_000) {
                while (vpnEvents.events().lastOrNull()?.fields?.get("connected") != "false") delay(50)
            }
            vpnEvents.events().forEachIndexed { index, event ->
                assertEquals("vpn_state.v1", event.type.sourceId.value)
                assertEquals("VPN_STATUS", event.type.eventType)
                assertTrue(requireNotNull(ProtocolEventSourceRegistry["vpn_state.v1"]).accepts(event, index + 1L, null))
            }
        } finally {
            try {
                actuator.release(desired)
            } finally {
                try {
                    if (vpnCollector.requiresStop) vpnCollector.stop()
                } finally {
                    scope.cancel()
                    shell("appops set ${context.packageName} ACTIVATE_VPN default")
                }
            }
        }
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private class VpnEventSink : EventSink {
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
        override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult =
            error("VPN state is a live callback source")
    }
}
