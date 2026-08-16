package cool.jacoblin.particeps.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.model.ResearchTime
import java.security.MessageDigest

class AndroidResearchClocks(
    context: Context,
    experimentId: String,
) : ResearchClocks {
    private val applicationContext = context.applicationContext
    private val bootSessionId: String

    init {
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        bootSessionId = MessageDigest.getInstance("SHA-256")
            .digest("$experimentId:$bootCount".toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    override fun now(): ResearchTime = ResearchTime(
        wallTimeUtcMillis = System.currentTimeMillis(),
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        bootSessionId = bootSessionId,
    )

    override fun trustedUtcMillis(): Long? {
        val networkTime = runCatching { SystemClock.currentNetworkTimeClock().millis() }.getOrNull()
        if (networkTime != null) return networkTime
        val automaticTimeEnabled = Settings.Global.getInt(
            applicationContext.contentResolver,
            Settings.Global.AUTO_TIME,
            0,
        ) == 1
        return System.currentTimeMillis().takeIf { automaticTimeEnabled }
    }
}
