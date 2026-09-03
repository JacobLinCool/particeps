package cool.jacoblin.particeps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingVpnService
import cool.jacoblin.particeps.nativebinding.trafficshaping.Trafficshaping
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Blocking API 37 checks that do not launch UI or exercise system task snapshots. */
@RunWith(AndroidJUnit4::class)
class Api37CompatibilityTest {
    @Suppress("DEPRECATION")
    @Test
    fun manifestAndSourceBuiltNativeLibraryAreUsableOnApi37() {
        assertTrue("This compatibility lane requires API 37 or newer", Build.VERSION.SDK_INT >= 37)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.QUERY_ALL_PACKAGES in permissions)
        assertTrue("android.permission.ACCESS_LOCAL_NETWORK" in permissions)

        val vpnService = packageInfo.services.orEmpty().singleOrNull {
            it.name == TrafficShapingVpnService::class.java.name
        }
        assertNotNull("Traffic-shaping VPN service is missing", vpnService)
        assertEquals(Manifest.permission.BIND_VPN_SERVICE, vpnService?.permission)
        assertTrue(
            "VPN service must be system-exempted",
            vpnService != null &&
                vpnService.foregroundServiceType and
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED != 0,
        )

        Trafficshaping.touch()
        val nativeLibrary = File(context.applicationInfo.nativeLibraryDir, "libgojni.so")
        assertTrue("Source-built native traffic-shaping library is absent", nativeLibrary.isFile)
        assertTrue("Source-built native traffic-shaping library is unreadable", nativeLibrary.canRead())
    }
}
