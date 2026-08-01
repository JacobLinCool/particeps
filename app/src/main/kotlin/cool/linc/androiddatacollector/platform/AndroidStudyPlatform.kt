package cool.linc.androiddatacollector.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import cool.linc.androiddatacollector.CollectionService
import cool.linc.androiddatacollector.ExperimentDeadlineWorker
import cool.linc.androiddatacollector.UploadWorker
import cool.linc.androiddatacollector.core.application.StudyCollectionHost
import cool.linc.androiddatacollector.core.application.StudyWorkScheduler
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.definition.UploadConfiguration
import java.util.concurrent.TimeUnit

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
        configuration.prompts.forEach { prompt ->
            val request = OneTimeWorkRequestBuilder<PromptWorker>()
                .setInitialDelay(prompt.delayMinutes.toLong(), TimeUnit.MINUTES)
                .setInputData(
                    Data.Builder()
                        .putString(PromptWorker.KEY_PROMPT_ID, prompt.id)
                        .putString(PromptWorker.KEY_MESSAGE, prompt.message)
                        .build(),
                )
                .addTag(promptTag(configuration.experimentId))
                .build()
            workManager.enqueueUniqueWork(
                promptWorkName(configuration.experimentId, prompt.id),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
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
        workManager.cancelAllWorkByTag(promptTag(experimentId))
        workManager.cancelUniqueWork(deadlineWorkName(experimentId))
    }

    override fun cancel(experimentId: String) {
        cancelCollectionWork(experimentId)
        workManager.cancelUniqueWork(uploadWorkName(experimentId))
    }

    private fun promptTag(experimentId: String) = "adc-prompt-$experimentId"
    private fun promptWorkName(experimentId: String, promptId: String) = "adc-prompt-$experimentId-$promptId"
    private fun deadlineWorkName(experimentId: String) = "adc-deadline-$experimentId"
    companion object {
        fun uploadWorkName(experimentId: String) = "adc-upload-$experimentId"
    }
}

class PromptWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure()
        }
        val promptId = inputData.getString(KEY_PROMPT_ID) ?: return Result.failure()
        val message = inputData.getString(KEY_MESSAGE) ?: return Result.failure()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Research prompts", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.notify(
            promptId.hashCode(),
            android.app.Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Research prompt")
                .setContentText(message)
                .setStyle(android.app.Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    companion object {
        const val KEY_PROMPT_ID = "prompt_id"
        const val KEY_MESSAGE = "message"
        private const val CHANNEL_ID = "research-prompts"
    }
}
