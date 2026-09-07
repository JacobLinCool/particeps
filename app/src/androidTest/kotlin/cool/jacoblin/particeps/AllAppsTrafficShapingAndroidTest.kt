package cool.jacoblin.particeps

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.core.definition.TrafficShapingProfile
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
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
            actuator.prepare(desired, UUID.randomUUID().toString())
            actuator.apply(desired)
            assertTrue(actuator.verify(desired).healthy)
            assertTrue(actuator.resume(desired).resumed)
            withTimeout(5_000) {
                while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) delay(50)
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
        } finally {
            actuator.release(desired)
            shell("appops set ${context.packageName} ACTIVATE_VPN default")
        }
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
