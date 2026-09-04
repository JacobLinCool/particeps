package cool.jacoblin.particeps

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingVpnService
import cool.jacoblin.particeps.nativebinding.trafficshaping.Trafficshaping
import dalvik.system.BaseDexClassLoader

/** Debug-only API 37 checks that do not launch UI or invoke system task snapshots. */
class Api37CompatibilityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

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
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        check(Manifest.permission.QUERY_ALL_PACKAGES in permissions) {
            "QUERY_ALL_PACKAGES is missing"
        }
        check("android.permission.ACCESS_LOCAL_NETWORK" in permissions) {
            "ACCESS_LOCAL_NETWORK is missing"
        }

        val vpnService = context.packageManager.getServiceInfo(
            ComponentName(context, TrafficShapingVpnService::class.java),
            PackageManager.ComponentInfoFlags.of(
                (
                    PackageManager.MATCH_DIRECT_BOOT_AWARE or
                        PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    ).toLong(),
            ),
        )
        check(vpnService.permission == Manifest.permission.BIND_VPN_SERVICE) {
            "Traffic-shaping service permission is invalid"
        }
        check(
            vpnService.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED != 0,
        ) { "Traffic-shaping service is not system-exempted" }

        val nativeLibrary = (context.classLoader as? BaseDexClassLoader)?.findLibrary("gojni")
        check(!nativeLibrary.isNullOrBlank()) {
            "Native traffic-shaping library cannot be resolved by the app class loader"
        }
        Trafficshaping.touch()
    }
}
