package cool.jacoblin.particeps.collector.usageevents

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.UserManager
import cool.jacoblin.particeps.collector.usagecommon.isUsageAccessGranted

internal enum class UsageEventsUnavailableReason(val reasonCode: String) {
    ACCESS_DENIED("USAGE_ACCESS_REVOKED"),
    USER_LOCKED("USAGE_EVENTS_USER_LOCKED"),
}

/**
 * A snapshot of Android's currently visible events, not an acknowledgement of complete delivery.
 * Android exposes no event-delivery watermark and can also hide Binder failures as empty results.
 * Availability probes distinguish known access failures without inventing events or backfilling a
 * closed condition epoch. Late framework delivery still requires device-level completeness checks.
 */
internal interface UsageEventsQuery {
    fun unavailableReason(): UsageEventsUnavailableReason?
    fun events(startUtcMillis: Long, endUtcMillis: Long): List<UsageSourceEvent>
}

internal data class UsageSourceEvent(
    val type: String,
    val timestamp: Long,
    val packageName: String?,
    val activityComponent: String?,
)

internal class AndroidUsageEventsQuery(context: Context) : UsageEventsQuery {
    private val applicationContext = context.applicationContext
    private val usageStatsManager = applicationContext.getSystemService(UsageStatsManager::class.java)
    private val userManager = applicationContext.getSystemService(UserManager::class.java)

    override fun unavailableReason(): UsageEventsUnavailableReason? = when {
        !isUsageAccessGranted(applicationContext) -> UsageEventsUnavailableReason.ACCESS_DENIED
        !userManager.isUserUnlocked -> UsageEventsUnavailableReason.USER_LOCKED
        else -> null
    }

    override fun events(startUtcMillis: Long, endUtcMillis: Long): List<UsageSourceEvent> {
        val result = mutableListOf<UsageSourceEvent>()
        val events = usageStatsManager.queryEvents(startUtcMillis, endUtcMillis)
            ?: throw IllegalStateException("Usage events query returned no result")
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            check(events.getNextEvent(event)) { "Usage events iterator ended unexpectedly" }
            event.typeName()?.let { type ->
                val component = if (type in ACTIVITY_EVENT_TYPES) {
                    event.className?.takeIf(String::isNotBlank)
                        ?: throw IllegalStateException("Activity event has no component")
                } else {
                    null
                }
                result += UsageSourceEvent(type, event.timeStamp, event.packageName, component)
            }
        }
        return result
    }

    private fun UsageEvents.Event.typeName(): String? = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
        UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
        else -> null
    }

    private companion object {
        val ACTIVITY_EVENT_TYPES = setOf("ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED")
    }
}
