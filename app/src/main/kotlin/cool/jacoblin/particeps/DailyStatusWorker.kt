package cool.jacoblin.particeps

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
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
 * does carry the study title, but only while collection is actually running and only as its
 * secondary line; a daily reminder that repeated it would be a standing disclosure instead.
 */
class DailyStatusWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as CollectorApplication
        val snapshot = application.session.snapshot.first { it.initialized }
        val metadata = snapshot.runtime.metadata
        val state = metadata?.state
        if (snapshot.configuration == null ||
            (state != ExperimentState.RUNNING && state != ExperimentState.PAUSED)
        ) {
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

        val text = when (state) {
            ExperimentState.PAUSED -> {
                val pausedAt = metadata.transitions
                    .lastOrNull { it.to == ExperimentState.PAUSED }
                    ?.time
                    ?.wallTimeUtcMillis
                if (pausedAt == null) {
                    applicationContext.getString(R.string.daily_paused_unknown)
                } else {
                    applicationContext.getString(
                        R.string.daily_paused_since,
                        DateUtils.formatDateTime(
                            applicationContext,
                            pausedAt,
                            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or
                                DateUtils.FORMAT_ABBREV_ALL,
                        ),
                    )
                }
            }
            else -> applicationContext.getString(R.string.daily_running)
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.daily_channel),
                // Low: this arrives every day for as long as the study runs. Anything that makes a
                // sound daily for a fortnight is a reason to uninstall the app, which would end the
                // study far more effectively than a missed reminder.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.notify(
            NOTIFICATION_TAG,
            0,
            android.app.Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(applicationContext.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        Intent(applicationContext, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    companion object {
        /** One tag, so today's reminder replaces yesterday's rather than stacking up. */
        const val NOTIFICATION_TAG = "daily-status"
        private const val CHANNEL_ID = "research-daily-status-v1"
    }
}
