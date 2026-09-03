package cool.jacoblin.particeps

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
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
        notifications.notify(
            NOTIFICATION_TAG,
            0,
            NotificationCompat.Builder(context, ParticepsNotificationChannels.RECOVERY)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(context.getString(R.string.recovery_notification_title))
                .setContentText(context.getString(R.string.recovery_notification_body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build(),
        )
    }

    override fun clear() {
        notifications.cancel(NOTIFICATION_TAG, 0)
    }

    companion object {
        const val ACTION_OPEN_RECOVERY = "cool.jacoblin.particeps.action.OPEN_RECOVERY"
        const val NOTIFICATION_TAG = "particeps-recovery"
        private const val TAG = "ParticepsRecovery"
    }
}
