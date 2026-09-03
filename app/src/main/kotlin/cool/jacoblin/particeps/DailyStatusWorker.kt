package cool.jacoblin.particeps

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cool.jacoblin.particeps.core.model.ExperimentState
import kotlinx.coroutines.flow.first

/**
 * One notification a day saying whether the study is still collecting, or still paused.
 *
 * The paused half is the reason this exists. A pause is the one state a participant can leave a
 * study in by accident: nothing on the phone changes, no notification is showing, and a study that
 * was meant to run for a fortnight quietly records nothing. The running half is not padding either
 * — a study that collects for weeks should keep saying so rather than becoming invisible, because
 * consent that nobody is reminded of is consent in name only.
 *
 * It reports state and nothing else. No counts, no collector names, and deliberately not the study
 * title either: this arrives every day for the study's whole duration, including while paused, and
 * a lock screen is readable by whoever is holding the phone. The title line is the app's own name,
 * which the launcher and Android's own settings already show. The ongoing collection notification
 * is equally neutral and never repeats researcher-authored study text on the lock screen.
 */
class DailyStatusWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as CollectorApplication
        val snapshot = application.session.snapshot.first { it.initialized }
        val activeState = snapshot.runtime.state?.takeIf {
            it == ExperimentState.RUNNING || it == ExperimentState.PAUSED
        }
        if (snapshot.study == null || activeState == null) {
            // Finished, withdrawn, deleted, or never started. Nothing to remind anyone about, and
            // the periodic request outlives the study unless it retires itself here.
            return Result.success()
        }
        if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Not a retry: the next daily run is soon enough, and retrying would spend the
            // participant's battery re-checking a permission only they can grant.
            return Result.success()
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_TAG,
            0,
            dailyStatusNotification(applicationContext, activeState),
        )
        return Result.success()
    }

    companion object {
        /** One tag, so today's reminder replaces yesterday's rather than stacking up. */
        const val NOTIFICATION_TAG = "daily-status"
    }
}

/** Fixed-copy notification projection; signed researcher text is intentionally not an input. */
internal fun dailyStatusNotification(context: Context, state: ExperimentState): android.app.Notification {
    require(state == ExperimentState.RUNNING || state == ExperimentState.PAUSED) {
        "Daily status is only defined for an active study"
    }
    val text = context.getString(
        if (state == ExperimentState.PAUSED) R.string.daily_paused_unknown else R.string.daily_running,
    )
    return android.app.Notification.Builder(context, ParticepsNotificationChannels.DAILY_STATUS)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(text)
        .setStyle(android.app.Notification.BigTextStyle().bigText(text))
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setAutoCancel(true)
        .build()
}
