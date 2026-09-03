package cool.jacoblin.particeps

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingVpnService
import cool.jacoblin.particeps.nativebinding.trafficshaping.Trafficshaping
import java.io.File

/** Debug-only API 37 checks that do not launch UI or invoke system task snapshots. */
class Api37CompatibilityInstrumentation : Instrumentation() {
    override fun onStart() {
        val results = Bundle()
        try {
            verify(targetContext)
            results.putString("stream", "API 37 compatibility checks passed.\n")
            finish(Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            results.putString(
                "shortMsg",
                "${failure::class.java.simpleName}: ${failure.message ?: "no message"}",
            )
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context) {
        check(Build.VERSION.SDK_INT >= 37) { "This compatibility lane requires API 37 or newer" }

        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        check(Manifest.permission.QUERY_ALL_PACKAGES in permissions) {
            "QUERY_ALL_PACKAGES is missing"
        }
        check("android.permission.ACCESS_LOCAL_NETWORK" in permissions) {
            "ACCESS_LOCAL_NETWORK is missing"
        }

        val vpnService = checkNotNull(packageInfo.services.orEmpty().singleOrNull {
            it.name == TrafficShapingVpnService::class.java.name
        }) { "Traffic-shaping VPN service is missing" }
        check(vpnService.permission == Manifest.permission.BIND_VPN_SERVICE) {
            "Traffic-shaping service permission is invalid"
        }
        check(
            vpnService.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED != 0,
        ) { "Traffic-shaping service is not system-exempted" }

        Trafficshaping.touch()
        val nativeLibrary = File(context.applicationInfo.nativeLibraryDir, "libgojni.so")
        check(nativeLibrary.isFile && nativeLibrary.canRead()) {
            "Source-built native traffic-shaping library is unavailable"
        }
    }
}
