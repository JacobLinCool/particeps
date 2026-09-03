package cool.jacoblin.particeps.collector.usagecommon

import android.app.AppOpsManager
import android.content.Context
import android.os.Build

/** The sole AppOps interpretation shared by setup and every UsageStats-backed collector. */
@Suppress("DEPRECATION")
fun isUsageAccessGranted(context: Context): Boolean {
    val applicationContext = context.applicationContext
    val appOps = applicationContext.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationContext.applicationInfo.uid,
            applicationContext.packageName,
            null,
        )
    } else {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationContext.applicationInfo.uid,
            applicationContext.packageName,
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}
