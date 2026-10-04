package cool.jacoblin.particeps

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import cool.jacoblin.particeps.core.application.RecoveryReporter

/** Safe participant notification plus full local exception-chain reporting. */
class AndroidRecoveryReporter(
    private val context: Context,
) : RecoveryReporter {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override fun actionRequired(failure: Throwable?) {
        if (failure != null && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.e(TAG, "Study recovery failed", failure)
        } else {
            Log.e(TAG, "Study recovery requires participant action")
        }
        show(R.string.recovery_notification_title, R.string.recovery_notification_body)
    }

    override fun collectionPaused(): Boolean =
        show(R.string.collection_paused_notification_title, R.string.collection_paused_notification_body)

    private fun show(@StringRes title: Int, @StringRes body: Int): Boolean {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_RECOVERY
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ParticepsNotificationChannels.RECOVERY)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(body)))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        try {
            if (!notifications.areNotificationsEnabled()) return false
            val channel = notifications.getNotificationChannel(ParticepsNotificationChannels.RECOVERY)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            if (notifications.activeNotifications.any {
                it.tag == NOTIFICATION_TAG && it.id == 0 &&
                    it.notification.extras.getCharSequence("android.title")?.toString() == context.getString(title) &&
                    it.notification.extras.getCharSequence("android.text")?.toString() == context.getString(body)
            }) return true
            notifications.notify(
                NOTIFICATION_TAG,
                0,
                notification,
            )
            return true
        } catch (_: SecurityException) {
            // Access can be revoked between checking it and posting. The durable pause remains.
            return false
        }
    }

    override fun clear() {
        try {
            notifications.cancel(NOTIFICATION_TAG, 0)
        } catch (_: SecurityException) {
            // Notification permission never controls study recovery or participant commands.
        }
    }

    companion object {
        const val ACTION_OPEN_RECOVERY = "cool.jacoblin.particeps.action.OPEN_RECOVERY"
        const val NOTIFICATION_TAG = "particeps-recovery"
        private const val TAG = "ParticepsRecovery"
    }
}
