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
import cool.linc.androiddatacollector.core.runtime.OccurrenceClaimResult
import cool.linc.androiddatacollector.core.runtime.OccurrenceExpiryResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

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
        configuration.upload?.let {
            scheduleUpload(
                configuration.experimentId,
                configuration.configurationId,
                it,
                ExistingWorkPolicy.REPLACE,
            )
        }
    }

    override fun replaceInterventionWork(
        configuration: StudyConfiguration,
        deliveries: List<InterventionOccurrence>,
        expiries: List<InterventionOccurrence>,
    ) {
        workManager.cancelAllWorkByTag(InterventionWorkIdentity.deliveryTag(configuration.experimentId))
        workManager.cancelAllWorkByTag(InterventionWorkIdentity.expiryTag(configuration.experimentId))
        deliveries.forEach { enqueueDelivery(configuration, it, ExistingWorkPolicy.REPLACE) }
        expiries.forEach { enqueueExpiry(configuration, it, ExistingWorkPolicy.REPLACE) }
    }

    override fun enqueueOccurrence(configuration: StudyConfiguration, occurrence: InterventionOccurrence) {
        enqueueOccurrence(configuration, occurrence, ExistingWorkPolicy.KEEP)
    }

    private fun enqueueOccurrence(
        configuration: StudyConfiguration,
        occurrence: InterventionOccurrence,
        policy: ExistingWorkPolicy,
    ) {
        enqueueDelivery(configuration, occurrence, policy)
        enqueueExpiry(configuration, occurrence, policy)
    }

    private fun enqueueDelivery(
        configuration: StudyConfiguration,
        occurrence: InterventionOccurrence,
        policy: ExistingWorkPolicy,
    ) {
        val now = System.currentTimeMillis()
        val delay = (occurrence.scheduledFor.wallTimeUtcMillis - now).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<InterventionWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(InterventionWorker.KEY_OCCURRENCE_ID, occurrence.occurrenceId).build())
            .addTag(InterventionWorkIdentity.deliveryTag(configuration.experimentId))
            .build()
        workManager.enqueueUniqueWork(
            InterventionWorkIdentity.deliveryName(configuration.experimentId, occurrence.occurrenceId),
            policy,
            request,
        )
    }

    private fun enqueueExpiry(
        configuration: StudyConfiguration,
        occurrence: InterventionOccurrence,
        policy: ExistingWorkPolicy,
    ) {
        val now = System.currentTimeMillis()
        val expiry = OneTimeWorkRequestBuilder<InterventionExpiryWorker>()
            .setInitialDelay((occurrence.expiresAtUtcMillis - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(InterventionWorker.KEY_OCCURRENCE_ID, occurrence.occurrenceId).build())
            .addTag(InterventionWorkIdentity.expiryTag(configuration.experimentId))
            .build()
        workManager.enqueueUniqueWork(
            InterventionWorkIdentity.expiryName(configuration.experimentId, occurrence.occurrenceId),
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
        configurationId: String,
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
                    .putString(UploadWorker.KEY_CONFIGURATION_ID, configurationId)
                    .build(),
            )
            .addTag(uploadTag(experimentId))
            .build()
        workManager.enqueueUniqueWork(uploadWorkName(experimentId, configurationId), policy, request)
    }

    /**
     * Re-establishes the delivery chain after a process restart. KEEP, so a link already waiting
     * is left alone rather than having its delay reset on every app start.
     */
    fun reschedulePendingWork(configuration: StudyConfiguration) {
        configuration.upload?.let {
            scheduleUpload(
                configuration.experimentId,
                configuration.configurationId,
                it,
                ExistingWorkPolicy.KEEP,
            )
        }
    }

    override fun cancelInterventionWork(experimentId: String, occurrenceIds: Set<String>) {
        workManager.cancelAllWorkByTag(InterventionWorkIdentity.deliveryTag(experimentId))
        workManager.cancelAllWorkByTag(InterventionWorkIdentity.expiryTag(experimentId))
        cancelInterventionNotifications(occurrenceIds)
    }

    override fun cancelInterventionNotifications(occurrenceIds: Set<String>) {
        occurrenceIds.forEach { notificationManager.cancel(it, 0) }
    }

    override fun cancelCollectionWork(experimentId: String, occurrenceIds: Set<String>) {
        cancelInterventionWork(experimentId, occurrenceIds)
        workManager.cancelUniqueWork(deadlineWorkName(experimentId))
    }

    override fun cancel(experimentId: String) {
        cancelCollectionWork(experimentId, emptySet())
        workManager.cancelAllWorkByTag(uploadTag(experimentId))
    }

    private fun deadlineWorkName(experimentId: String) = "adc-deadline-$experimentId"
    private fun uploadTag(experimentId: String) = "adc-upload-$experimentId"
    companion object {
        fun uploadWorkName(experimentId: String, configurationId: String) =
            "adc-upload-$experimentId-$configurationId"
    }
}

internal object InterventionWorkIdentity {
    fun deliveryTag(experimentId: String) = "adc-intervention-delivery-$experimentId"
    fun expiryTag(experimentId: String) = "adc-intervention-expiry-$experimentId"
    fun deliveryName(experimentId: String, occurrenceId: String) = "adc-intervention-$experimentId-$occurrenceId"
    fun expiryName(experimentId: String, occurrenceId: String) = "${deliveryName(experimentId, occurrenceId)}-expiry"
}

internal enum class ExpiryWorkerDirective { RETRY, COMPLETE, COMPLETE_AND_RECOVER }

internal enum class DeliveryWorkerDirective { DELIVER, RETRY, COMPLETE, RECOVER_SUCCESSOR }

internal fun deliveryWorkerDirective(result: OccurrenceClaimResult): DeliveryWorkerDirective = when (result) {
    is OccurrenceClaimResult.Due -> DeliveryWorkerDirective.DELIVER
    is OccurrenceClaimResult.NotDue -> DeliveryWorkerDirective.RETRY
    OccurrenceClaimResult.Expired,
    OccurrenceClaimResult.Terminal,
    -> DeliveryWorkerDirective.RECOVER_SUCCESSOR
    OccurrenceClaimResult.InactiveStudy,
    OccurrenceClaimResult.Missing,
    -> DeliveryWorkerDirective.COMPLETE
}

internal fun expiryWorkerDirective(result: OccurrenceExpiryResult): ExpiryWorkerDirective = when (result) {
    is OccurrenceExpiryResult.NotDue -> ExpiryWorkerDirective.RETRY
    OccurrenceExpiryResult.Expired,
    OccurrenceExpiryResult.Terminal,
    -> ExpiryWorkerDirective.COMPLETE_AND_RECOVER
    OccurrenceExpiryResult.InactiveStudy,
    OccurrenceExpiryResult.Missing,
    -> ExpiryWorkerDirective.COMPLETE
}

/** Cancels Android's external side effect unless durable POSTING -> POSTED finalization succeeds. */
internal suspend fun finalizePostedNotification(
    finalize: suspend () -> Boolean,
    cancel: () -> Unit,
): Boolean {
    val finalized = try {
        finalize()
    } catch (failure: Throwable) {
        try {
            cancel()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    if (!finalized) cancel()
    return finalized
}

/**
 * One process-local owner for notification delivery and stale-POSTING recovery.
 *
 * Recovery may cancel a notification that has not reached durable POSTED state, so it must never
 * interleave with claim -> notify -> finalize.
 */
internal object InterventionDeliveryCoordinator {
    private val mutex = Mutex()

    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }

    suspend fun <T> recoverStalePosting(operation: suspend () -> T): T = mutex.withLock { operation() }
}

class InterventionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = InterventionDeliveryCoordinator.run {
        try {
            // Claim happens after acquiring the coordinator, so a worker never acts on a stale
            // POSTING snapshot left by another in-process delivery attempt.
            deliver()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun deliver(): Result {
        val occurrenceId = inputData.getString(KEY_OCCURRENCE_ID) ?: return Result.failure()
        val application = applicationContext as cool.linc.androiddatacollector.CollectorApplication
        if (application.session.snapshot.first { it.initialized }.configuration == null) return Result.success()
        val claim = application.session.claimOccurrenceIfDue(occurrenceId)
        when (deliveryWorkerDirective(claim)) {
            DeliveryWorkerDirective.RETRY -> return Result.retry()
            DeliveryWorkerDirective.COMPLETE -> return Result.success()
            DeliveryWorkerDirective.RECOVER_SUCCESSOR -> {
                application.session.scheduleSuccessor(occurrenceId)
                return Result.success()
            }
            DeliveryWorkerDirective.DELIVER -> Unit
        }
        val dispatch = (claim as OccurrenceClaimResult.Due).dispatch
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
        var finalized = false
        try {
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
            // Expiry or a storage failure can win between the durable claim and Android's external
            // notify() side effect. Finalization is authoritative; false and exceptions clean up.
            finalized = finalizePostedNotification(
                finalize = { application.session.markNotificationPosted(occurrenceId) },
                cancel = { manager.cancel(occurrenceId, 0) },
            )
            // Deliberately outside durable finalization: a successor enqueue failure must retry
            // without retracting a notification whose POSTED state already committed.
            application.session.scheduleSuccessor(occurrenceId)
        } catch (failure: Throwable) {
            if (!finalized) {
                try {
                    manager.cancel(occurrenceId, 0)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
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
    override suspend fun doWork(): Result = try {
        expire()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        Result.retry()
    }

    private suspend fun expire(): Result {
        val occurrenceId = inputData.getString(InterventionWorker.KEY_OCCURRENCE_ID) ?: return Result.failure()
        val application = applicationContext as cool.linc.androiddatacollector.CollectorApplication
        if (application.session.snapshot.first { it.initialized }.configuration == null) return Result.success()
        return when (expiryWorkerDirective(application.session.expireOccurrenceIfDue(occurrenceId))) {
            ExpiryWorkerDirective.RETRY -> Result.retry()
            ExpiryWorkerDirective.COMPLETE -> Result.success()
            ExpiryWorkerDirective.COMPLETE_AND_RECOVER -> {
                applicationContext.getSystemService(NotificationManager::class.java).cancel(occurrenceId, 0)
                application.session.scheduleSuccessor(occurrenceId)
                Result.success()
            }
        }
    }
}
