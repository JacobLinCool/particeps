package cool.jacoblin.particeps.actuator.trafficshaping

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVpnContractTest {
    private val repositoryRoot = File(requireNotNull(System.getProperty("particeps.repositoryRoot")))

    @Test
    fun appManifestDeclaresFailClosedVpnSurface() {
        val manifest = repositoryRoot.resolve("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"))
        assertTrue(manifest.contains("android.permission.QUERY_ALL_PACKAGES"))
        assertTrue(manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(manifest.contains("android.permission.BIND_VPN_SERVICE"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"systemExempted\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android.net.VpnService.SUPPORTS_ALWAYS_ON"))
        assertTrue(manifest.contains("android:value=\"false\""))
    }

    @Test
    fun vpnBuilderUsesFixedTunPlanAndNoBypassOrPublicDns() {
        val source = repositoryRoot.resolve(
            "actuator/traffic-shaping/src/main/kotlin/cool/jacoblin/particeps/" +
                "actuator/trafficshaping/TrafficShapingVpnService.kt",
        ).readText()
        assertTrue(source.contains(".setMtu(PROTOCOL_MTU)"))
        assertTrue(source.contains(".addAddress(TUN_IPV4_ADDRESS, 32)"))
        assertTrue(source.contains(".addAddress(TUN_IPV6_ADDRESS, 128)"))
        assertTrue(source.contains(".addRoute(\"0.0.0.0\", 0)"))
        assertTrue(source.contains(".addRoute(\"::\", 0)"))
        assertTrue(source.contains(".setBlocking(true)"))
        assertTrue(source.contains(".setMetered(false)"))
        assertFalse(source.contains("addDnsServer"))
        assertFalse(source.contains("allowBypass"))
        assertTrue(source.contains("targets.packages.forEach(::addAllowedApplication)"))
    }
}
