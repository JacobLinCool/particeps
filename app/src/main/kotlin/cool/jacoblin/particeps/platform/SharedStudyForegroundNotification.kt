package cool.jacoblin.particeps.platform

import android.app.Notification
import android.app.Service
import cool.jacoblin.particeps.actuator.trafficshaping.ForegroundServiceStarter
import cool.jacoblin.particeps.actuator.trafficshaping.ForegroundServiceStopper
import cool.jacoblin.particeps.actuator.trafficshaping.SharedForegroundNotificationLease
import java.util.IdentityHashMap

/** Serialized lease implementation, independently constructible for release-order verification. */
internal class SerializedSharedForegroundNotificationLease : SharedForegroundNotificationLease {
    private data class Presentation(
        val id: Int,
        val channelId: String?,
        val title: String?,
        val text: String?,
        val category: String?,
        val smallIconResourceId: Int,
        val ongoing: Boolean,
    )

    private data class Owner(
        val presentation: Presentation,
        val notification: Notification,
        val foregroundServiceType: Int,
        val starter: ForegroundServiceStarter,
        val stopper: ForegroundServiceStopper,
    )

    private val lock = Any()
    private val owners = IdentityHashMap<Any, Owner>()

    override fun acquire(
        owner: Any,
        id: Int,
        notification: Notification,
        foregroundServiceType: Int,
        starter: ForegroundServiceStarter,
        stopper: ForegroundServiceStopper,
    ) = synchronized(lock) {
        val presentation = notification.presentation(id)
        owners.entries
            .firstOrNull { it.key !== owner }
            ?.value
            ?.let { peer ->
                require(peer.presentation == presentation) {
                    "Concurrent study foreground services must use identical neutral notification copy"
                }
            }

        // Commit ownership only after Android accepts the foreground transition. Re-acquiring the
        // same owner is how the collector host safely updates its foreground-service type.
        starter.start(id, notification, foregroundServiceType)
        owners[owner] = Owner(presentation, notification, foregroundServiceType, starter, stopper)
    }

    override fun release(owner: Any) = synchronized(lock) {
        val departing = owners.remove(owner) ?: return@synchronized
        val remaining = owners.values.firstOrNull()
        if (remaining == null) {
            departing.stopper.stop(Service.STOP_FOREGROUND_REMOVE)
            return@synchronized
        }

        try {
            // Reassert the remaining service first. DETACH then relinquishes only the departing
            // service's foreground relationship; the participant-visible notification stays live.
            remaining.starter.start(
                remaining.presentation.id,
                remaining.notification,
                remaining.foregroundServiceType,
            )
        } catch (failure: Throwable) {
            owners[owner] = departing
            throw failure
        }
        departing.stopper.stop(Service.STOP_FOREGROUND_DETACH)
    }

    internal fun ownerCountForTest(): Int = synchronized(lock) { owners.size }

    private fun Notification.presentation(id: Int): Presentation = Presentation(
        id = id,
        channelId = channelId,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        category = category,
        smallIconResourceId = smallIcon?.resId ?: 0,
        ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
    )
}

/** Process-scoped lease for the one neutral foreground notification used by study resources. */
object SharedStudyForegroundNotification : SharedForegroundNotificationLease by
    SerializedSharedForegroundNotificationLease()
