package cool.linc.androiddatacollector.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import cool.linc.androiddatacollector.core.collector.ResearchClocks
import cool.linc.androiddatacollector.core.model.ResearchTime
import java.security.MessageDigest

class AndroidResearchClocks(
    context: Context,
    experimentId: String,
) : ResearchClocks {
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
}
