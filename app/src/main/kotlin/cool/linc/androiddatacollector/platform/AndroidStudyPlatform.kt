package cool.linc.androiddatacollector.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cool.linc.androiddatacollector.CollectionService
import cool.linc.androiddatacollector.ExperimentDeadlineWorker
import cool.linc.androiddatacollector.MainActivity
import cool.linc.androiddatacollector.R
import cool.linc.androiddatacollector.SurveyActivity
import cool.linc.androiddatacollector.UploadWorker
import cool.linc.androiddatacollector.core.application.StudyCollectionHost
import cool.linc.androiddatacollector.core.application.StudyWorkScheduler
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.definition.SurveyAction
import cool.linc.androiddatacollector.core.definition.UploadConfiguration
import cool.linc.androiddatacollector.core.model.InterventionOccurrence
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class AndroidStudyCollectionHost(
    private val context: Context,
) : StudyCollectionHost {
    override fun start(studyTitle: String, usesLocation: Boolean) {
        CollectionService.start(context, studyTitle, usesLocation)
    }

    override fun stop() {
        CollectionService.stop(context)
    }
}

class AndroidStudyWorkScheduler(
    context: Context,
) : StudyWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(configuration: StudyConfiguration) {
        val deadline = OneTimeWorkRequestBuilder<ExperimentDeadlineWorker>()
            .setInitialDelay(configuration.durationHours.toLong(), TimeUnit.HOURS)
            .setInputData(
                Data.Builder()
                    .putString(ExperimentDeadlineWorker.KEY_EXPERIMENT_ID, configuration.experimentId)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(
            deadlineWorkName(configuration.experimentId),
            ExistingWorkPolicy.REPLACE,
            deadline,
        )
        configuration.upload?.let { scheduleUpload(configuration.experimentId, it, ExistingWorkPolicy.REPLACE) }
    }

    override fun replaceInterventionWork(
        configuration: StudyConfiguration,
        occurrences: List<InterventionOccurrence>,
    ) {
        workManager.cancelAllWorkByTag(interventionTag(configuration.experimentId))
        occurrences.forEach { enqueueOccurrence(configuration, it, ExistingWorkPolicy.REPLACE) }
    }

    override fun enqueueOccurrence(configuration: StudyConfiguration, occurrence: InterventionOccurrence) {
        enqueueOccurrence(configuration, occurrence, ExistingWorkPolicy.KEEP)
    }

    private fun enqueueOccurrence(
        configuration: StudyConfiguration,
        occurrence: InterventionOccurrence,
        policy: ExistingWorkPolicy,
    ) {
        val now = System.currentTimeMillis()
        val delay = (occurrence.scheduledFor.wallTimeUtcMillis - now).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<InterventionWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(InterventionWorker.KEY_OCCURRENCE_ID, occurrence.occurrenceId).build())
            .addTag(interventionTag(configuration.experimentId))
            .build()
        workManager.enqueueUniqueWork(
            occurrenceWorkName(configuration.experimentId, occurrence.occurrenceId),
            policy,
            request,
        )
        val expiry = OneTimeWorkRequestBuilder<InterventionExpiryWorker>()
            .setInitialDelay((occurrence.expiresAtUtcMillis - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(InterventionWorker.KEY_OCCURRENCE_ID, occurrence.occurrenceId).build())
            .addTag(interventionTag(configuration.experimentId))
            .build()
        workManager.enqueueUniqueWork(
            "${occurrenceWorkName(configuration.experimentId, occurrence.occurrenceId)}-expiry",
            policy,
            expiry,
        )
    }

    /**
     * Enqueues one delivery attempt, which re-enqueues its successor when it finishes.
     *
     * Not a [androidx.work.PeriodicWorkRequest]: that floor is 15 minutes, and silently clamping a
     * shorter configured cadence would make the consent screen's stated frequency untrue. A
     * self-renewing one-time chain honours whatever the signed configuration asked for.
     *
     * The cost of the chain is that it has no platform-side repetition to fall back on, so
     * [reschedulePendingWork] re-establishes it whenever a session initialises.
     */
    fun scheduleUpload(
        experimentId: String,
        upload: UploadConfiguration,
        policy: ExistingWorkPolicy,
    ) {
        val constraints = Constraints.Builder()
            // Default to Wi-Fi. Uploading a study over a participant's mobile data is a cost they
            // did not agree to unless the signed configuration says so.
            .setRequiredNetworkType(
                if (upload.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
            )
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInitialDelay(upload.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(UploadWorker.KEY_EXPERIMENT_ID, experimentId)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(uploadWorkName(experimentId), policy, request)
    }

    /**
     * Re-establishes the delivery chain after a process restart. KEEP, so a link already waiting
     * is left alone rather than having its delay reset on every app start.
     */
    fun reschedulePendingWork(configuration: StudyConfiguration) {
        configuration.upload?.let {
            scheduleUpload(configuration.experimentId, it, ExistingWorkPolicy.KEEP)
        }
    }

    override fun cancelCollectionWork(experimentId: String) {
        workManager.cancelAllWorkByTag(interventionTag(experimentId))
        workManager.cancelUniqueWork(deadlineWorkName(experimentId))
    }

    override fun cancel(experimentId: String) {
        cancelCollectionWork(experimentId)
        workManager.cancelUniqueWork(uploadWorkName(experimentId))
    }

    private fun interventionTag(experimentId: String) = "adc-intervention-$experimentId"
    private fun occurrenceWorkName(experimentId: String, occurrenceId: String) = "adc-intervention-$experimentId-$occurrenceId"
    private fun deadlineWorkName(experimentId: String) = "adc-deadline-$experimentId"
    companion object {
        fun uploadWorkName(experimentId: String) = "adc-upload-$experimentId"
    }
}

class InterventionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val occurrenceId = inputData.getString(KEY_OCCURRENCE_ID) ?: return Result.failure()
        val application = applicationContext as cool.linc.androiddatacollector.CollectorApplication
        if (application.session.snapshot.first { it.initialized }.configuration == null) return Result.success()
        val dispatch = application.session.claimOccurrence(occurrenceId) ?: return Result.success()
        if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // The durable claim remains POSTING. A retry can post it after permission is restored,
            // or atomically expire it once its availability window closes.
            return Result.retry()
        }
        val target = if (dispatch.action is SurveyAction) SurveyActivity::class.java else MainActivity::class.java
        val intent = Intent(applicationContext, target)
            .setAction(ACTION_OPEN_OCCURRENCE)
            .setData(Uri.Builder().scheme("adc").authority("occurrence").appendPath(occurrenceId).build())
            .putExtra(KEY_OCCURRENCE_ID, occurrenceId)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.intervention_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.notify(
            occurrenceId,
            0,
            android.app.Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(dispatch.action.notificationTitle)
                .setContentText(dispatch.action.notificationMessage)
                .setStyle(android.app.Notification.BigTextStyle().bigText(dispatch.action.notificationMessage))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setTimeoutAfter((dispatch.occurrence.expiresAtUtcMillis - System.currentTimeMillis()).coerceAtLeast(1))
                .build(),
        )
        application.session.markNotificationPosted(occurrenceId)
        return Result.success()
    }

    companion object {
        const val KEY_OCCURRENCE_ID = "occurrence_id"
        const val ACTION_OPEN_OCCURRENCE = "cool.linc.androiddatacollector.OPEN_OCCURRENCE"
        private const val CHANNEL_ID = "research-interventions-v1"
    }
}

/** Records the terminal no-response outcome even when the participant never taps a notification. */
class InterventionExpiryWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val occurrenceId = inputData.getString(InterventionWorker.KEY_OCCURRENCE_ID) ?: return Result.failure()
        val application = applicationContext as cool.linc.androiddatacollector.CollectorApplication
        if (application.session.snapshot.first { it.initialized }.configuration == null) return Result.success()
        application.session.claimOccurrence(occurrenceId)
        applicationContext.getSystemService(NotificationManager::class.java).cancel(occurrenceId, 0)
        return Result.success()
    }
}
