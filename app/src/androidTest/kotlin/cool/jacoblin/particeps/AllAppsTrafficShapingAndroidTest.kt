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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith

/** Host-controlled integration: a local server sends 262144 ASCII Z bytes after receiving one byte. */
@RunWith(AndroidJUnit4::class)
class AllAppsTrafficShapingAndroidTest {
    @Test fun allAppsIncludesTheResearchAppAndForwardsThroughTheCappedVpn() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString("traffic_test_endpoint")
        if (arguments.getString("particepsHostHarness") == "true") {
            require(!endpoint.isNullOrBlank()) { "The blocking host gate requires its local TCP test server" }
        }
        assumeTrue("Requires the local TCP test server", endpoint != null)
        val connectionCount = when (arguments.getString("traffic_test_connections") ?: "1") {
            "1" -> 1
            "5" -> 5
            else -> error("traffic_test_connections must be 1 or 5")
        }
        val observeTraffic = when (arguments.getString("traffic_test_observer") ?: "false") {
            "false" -> false
            "true" -> true
            else -> error("traffic_test_observer must be true or false")
        }
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
                requiresPromptCommits = false,
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
        val observer = if (observeTraffic) AllAppsTrafficObserver(actuator, desired, connectionCount) else null
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
            observer?.start(scope)
            withTimeout(5_000) {
                while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) delay(50)
            }
            withTimeout(5_000) {
                while (vpnEvents.events().lastOrNull()?.fields?.get("connected") != "true") delay(50)
            }
            // The research app is deliberately absent from any selected-package list. Its default
            // socket must traverse the all-app VPN, while the engine's protected sockets avoid loops.
            readFixtureConnections(InetSocketAddress(address[0], address[1].toInt()), connectionCount, observer)
            assertEquals(ResourceHealthStatus.APPLIED, actuator.health().status)
            observer?.finish()
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
            observer?.finish()
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

    private suspend fun readFixtureConnections(
        address: InetSocketAddress,
        connectionCount: Int,
        observer: AllAppsTrafficObserver?,
    ) = coroutineScope {
        val sockets = List(connectionCount) { Socket() }
        val remainingConnections = AtomicInteger(connectionCount)
        val start = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        try {
            sockets.mapIndexed { index, socket ->
                async(Dispatchers.IO) {
                    readFixtureConnection(socket, address, index, remainingConnections, start, observer?.connections?.get(index))
                }
            }.awaitAll()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // awaitAll fails promptly. Close every socket before coroutineScope joins siblings,
            // including a sibling blocked in connect/read or cancelled before its body ran.
            var closeFailure: IOException? = null
            sockets.forEach { socket ->
                try {
                    socket.close()
                } catch (error: IOException) {
                    val previous = closeFailure
                    if (previous == null) closeFailure = error else previous.addSuppressed(error)
                }
            }
            closeFailure?.let { error ->
                val original = failure
                if (original == null) throw error else original.addSuppressed(error)
            }
        }
    }

    private suspend fun readFixtureConnection(
        socket: Socket,
        address: InetSocketAddress,
        connectionIndex: Int,
        remainingConnections: AtomicInteger,
        start: CompletableDeferred<Unit>,
        progress: AllAppsTrafficObserver.ConnectionProgress?,
    ) {
        val before = android.os.SystemClock.elapsedRealtime()
        val received = ByteArrayOutputStream()
        var stage = "connect"
        var firstByteElapsed: Long? = null
        var lastProgressElapsed: Long? = null
        var readStartedElapsed: Long? = null
        var transferStartedElapsed: Long? = null
        var maxReadIdleMillis = 0L
        var reachedEof = false
        fun recordProgress(errorClass: String? = null) {
            progress?.record(
                before, stage, received.size(), transferStartedElapsed, firstByteElapsed,
                lastProgressElapsed, reachedEof, errorClass,
            )
        }
        recordProgress()
        try {
            val payload = socket.use {
                socket.soTimeout = 15_000
                socket.connect(address, 5_000)
                stage = "barrier"
                recordProgress()
                if (remainingConnections.decrementAndGet() == 0) start.complete(Unit)
                start.await()
                stage = "trigger"
                transferStartedElapsed = android.os.SystemClock.elapsedRealtime() - before
                recordProgress()
                socket.getOutputStream().write(1)
                stage = "read"
                readStartedElapsed = android.os.SystemClock.elapsedRealtime() - before
                recordProgress()
                val input = socket.getInputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                // Match readBytes: retain every byte and continue until EOF, including after
                // the expected payload length. SO_TIMEOUT remains per-read inactivity.
                while (true) {
                    val count = input.read(buffer)
                    val completedElapsed = android.os.SystemClock.elapsedRealtime() - before
                    maxReadIdleMillis = maxOf(
                        maxReadIdleMillis,
                        completedElapsed - requireNotNull(lastProgressElapsed ?: readStartedElapsed),
                    )
                    if (count < 0) {
                        reachedEof = true
                        recordProgress()
                        break
                    }
                    if (count > 0) {
                        received.write(buffer, 0, count)
                        if (firstByteElapsed == null) firstByteElapsed = completedElapsed
                        lastProgressElapsed = completedElapsed
                        recordProgress()
                    }
                }
                stage = "close"
                recordProgress()
                received.toByteArray()
            }
            val totalElapsed = android.os.SystemClock.elapsedRealtime() - before
            val elapsed = totalElapsed - requireNotNull(transferStartedElapsed)
            stage = "validate"
            recordProgress()
            assertEquals(262_144, payload.size)
            assertTrue(payload.all { it == 'Z'.code.toByte() })
            // A generous per-connection bound detects an uncapped path without asserting exact timing.
            assertTrue("256 KiB crossed the 500 kbps VPN in only $elapsed ms", elapsed >= 2_000)
            stage = "complete"
            recordProgress()
            android.util.Log.i(
                "AllAppsTrafficTest",
                "Connection $connectionIndex transferred ${payload.size} bytes in $elapsed ms (total $totalElapsed ms)",
            )
        } catch (failure: Throwable) {
            recordProgress(failure.javaClass.simpleName)
            val failedElapsed = android.os.SystemClock.elapsedRealtime() - before
            val idleMillis = (lastProgressElapsed ?: readStartedElapsed)?.let { failedElapsed - it }
            android.util.Log.e(
                "AllAppsTrafficTest",
                JSONObject()
                    .put("connection_index", connectionIndex)
                    .put("stage", stage)
                    .put("error_class", failure.javaClass.simpleName)
                    .put("received_bytes", received.size())
                    .put("elapsed_millis", failedElapsed)
                    .put("transfer_elapsed_millis", transferStartedElapsed?.let { failedElapsed - it } ?: JSONObject.NULL)
                    .put("first_byte_elapsed_millis", firstByteElapsed ?: JSONObject.NULL)
                    .put("last_progress_elapsed_millis", lastProgressElapsed ?: JSONObject.NULL)
                    .put("read_idle_millis", idleMillis ?: JSONObject.NULL)
                    .put("max_read_idle_millis", maxOf(maxReadIdleMillis, idleMillis ?: 0L))
                    .put("reached_eof", reachedEof)
                    .put("full_payload_awaiting_eof", received.size() == 262_144 && !reachedEof)
                    .toString(),
            )
            throw failure
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
            EmitBatchResult.Accepted(++sequence, batch.events.size)
        }
        override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult =
            error("VPN state is a live callback source")
    }
}
