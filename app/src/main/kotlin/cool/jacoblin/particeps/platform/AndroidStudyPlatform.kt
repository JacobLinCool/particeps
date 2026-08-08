package cool.jacoblin.particeps.platform

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cool.jacoblin.particeps.CollectionService
import cool.jacoblin.particeps.DailyStatusWorker
import cool.jacoblin.particeps.ExperimentDeadlineWorker
import cool.jacoblin.particeps.MainActivity
import cool.jacoblin.particeps.ParticepsNotificationChannels
import cool.jacoblin.particeps.R
import cool.jacoblin.particeps.SafetyPauseWorker
import cool.jacoblin.particeps.SurveyActivity
import cool.jacoblin.particeps.UploadWorker
import cool.jacoblin.particeps.core.application.StudyCollectionHost
import cool.jacoblin.particeps.core.application.StudyWorkScheduler
import cool.jacoblin.particeps.core.application.participantStartedAt
import cool.jacoblin.particeps.core.application.studyLifetime
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.definition.UploadConfiguration
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import cool.jacoblin.particeps.core.runtime.OccurrenceClaimResult
import cool.jacoblin.particeps.core.runtime.OccurrenceExpiryResult
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidStudyCollectionHost(
    private val context: Context,
) : StudyCollectionHost {
    override suspend fun start(studyTitle: String, usesLocation: Boolean) {
        CollectionService.start(context, studyTitle, usesLocation)
        retractStaleDailyReminder()
    }

    override fun stop() {
        CollectionService.stop(context)
        retractStaleDailyReminder()
    }

    /**
     * Drops a standing daily reminder whenever collection starts or stops.
     *
     * The reminder is posted once a day and states which state the study is in, so the moment that
     * changes the notification sitting on the lock screen is a false statement — and the worst
     * direction to be wrong in is a paused study still asserting "Still collecting", which is the
     * exact opposite of what the reminder exists to say. Retracting is enough: the next daily run
     * posts the truth, whereas re-posting here would turn a daily reminder into a notification on
     * every pause and resume.
     */
    private fun retractStaleDailyReminder() {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(DailyStatusWorker.NOTIFICATION_TAG, 0)
    }
}

class AndroidStudyWorkScheduler(
    context: Context,
) : StudyWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override suspend fun ensureCollectionWork(
        configuration: StudyConfiguration,
        metadata: StudyMetadata,
        observedAt: ResearchTime,
    ) {
        val plan = collectionWorkPlan(configuration, metadata, observedAt)
        val mutations = mutableListOf<() -> Operation>()
        plan.deadlineDelayMillis?.let { deadlineDelayMillis ->
            val deadline = OneTimeWorkRequestBuilder<ExperimentDeadlineWorker>()
                .setInitialDelay(deadlineDelayMillis, TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .putString(ExperimentDeadlineWorker.KEY_EXPERIMENT_ID, configuration.experimentId)
                        .build(),
                )
                .build()
            mutations += {
                workManager.enqueueUniqueWork(
                    deadlineWorkName(configuration.experimentId),
                    plan.deadlinePolicy,
                    deadline,
                )
            }
        }
        if (plan.scheduleDailyStatus) {
            mutations += ::scheduleDailyStatus
        }
        if (plan.scheduleUpload) configuration.upload?.let { upload ->
            mutations += {
                uploadOperation(
                    configuration.experimentId,
                    configuration.configurationId,
                    upload,
                    ExistingWorkPolicy.KEEP,
                )
            }
        }
        awaitWorkMutations(mutations)
    }

    /**
     * The daily reminder, which runs for as long as the study is either collecting or paused.
     *
     * Periodic rather than a self-renewing chain, unlike delivery: a day is far above WorkManager's
     * fifteen-minute floor, so nothing is silently clamped, and periodic work is re-established by
     * the platform across reboots without this app having to remember to do it. KEEP so that
     * re-entering a study — a resume, a process restart — does not push the next reminder a full
     * day away each time.
     *
     * The schedule is deliberately not cancelled on pause — a paused study is exactly the case the
     * reminder exists for — and [cancelCollectionWork] retires it when the study actually ends. The
     * already-posted notification is a separate matter: pausing retracts it, because it states a
     * state that has just stopped being true. See [AndroidStudyCollectionHost].
     */
    private fun scheduleDailyStatus(): Operation =
        workManager.enqueueUniquePeriodicWork(
            DAILY_STATUS_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyStatusWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .build(),
        )

    override suspend fun replaceInterventionWork(
        configuration: StudyConfiguration,
        deliveries: List<InterventionOccurrence>,
        expiries: List<InterventionOccurrence>,
    ) {
        awaitWorkMutations(
            listOf(
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.deliveryTag(configuration.experimentId)) },
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.expiryTag(configuration.experimentId)) },
            ),
        )
        awaitWorkMutations(
            deliveries.map { occurrence ->
                { enqueueDelivery(configuration, occurrence, ExistingWorkPolicy.REPLACE) }
            } + expiries.map { occurrence ->
                { enqueueExpiry(configuration, occurrence, ExistingWorkPolicy.REPLACE) }
            },
        )
    }

    override suspend fun enqueueOccurrence(configuration: StudyConfiguration, occurrence: InterventionOccurrence) {
        awaitWorkMutations(
            listOf(
                { enqueueDelivery(configuration, occurrence, ExistingWorkPolicy.KEEP) },
                { enqueueExpiry(configuration, occurrence, ExistingWorkPolicy.KEEP) },
            ),
        )
    }

    private fun enqueueDelivery(
        configuration: StudyConfiguration,
        occurrence: InterventionOccurrence,
        policy: ExistingWorkPolicy,
    ): Operation {
        val now = System.currentTimeMillis()
        val delay = (occurrence.scheduledFor.wallTimeUtcMillis - now).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<InterventionWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(InterventionWorker.KEY_OCCURRENCE_ID, occurrence.occurrenceId).build())
            .addTag(InterventionWorkIdentity.deliveryTag(configuration.experimentId))
            .build()
        return workManager.enqueueUniqueWork(
            InterventionWorkIdentity.deliveryName(configuration.experimentId, occurrence.occurrenceId),
            policy,
            request,
        )
    }

    private fun enqueueExpiry(
        configuration: StudyConfiguration,
        occurrence: InterventionOccurrence,
        policy: ExistingWorkPolicy,
    ): Operation {
        val now = System.currentTimeMillis()
        val expiry = OneTimeWorkRequestBuilder<InterventionExpiryWorker>()
            .setInitialDelay((occurrence.expiresAtUtcMillis - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(InterventionWorker.KEY_OCCURRENCE_ID, occurrence.occurrenceId).build())
            .addTag(InterventionWorkIdentity.expiryTag(configuration.experimentId))
            .build()
        return workManager.enqueueUniqueWork(
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
     * [ensureCollectionWork] re-establishes it on Start, Resume, same-boot reconciliation, and
     * terminal upload-tail repair.
     */
    internal suspend fun scheduleUpload(
        experimentId: String,
        configurationId: String,
        upload: UploadConfiguration,
        policy: ExistingWorkPolicy,
    ) {
        awaitWorkMutations(listOf({ uploadOperation(experimentId, configurationId, upload, policy) }))
    }

    private fun uploadOperation(
        experimentId: String,
        configurationId: String,
        upload: UploadConfiguration,
        policy: ExistingWorkPolicy,
    ): Operation {
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
        return workManager.enqueueUniqueWork(uploadWorkName(experimentId, configurationId), policy, request)
    }

    override suspend fun cancelInterventionWork(experimentId: String, occurrenceIds: Set<String>) {
        awaitCleanupMutations(
            notificationCleanup = { cancelInterventionNotifications(occurrenceIds) },
            mutations = listOf(
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.deliveryTag(experimentId)) },
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.expiryTag(experimentId)) },
            ),
        )
    }

    override fun cancelInterventionNotifications(occurrenceIds: Set<String>) {
        occurrenceIds.forEach { notificationManager.cancel(it, 0) }
    }

    override suspend fun scheduleSafetyPauseRetry(experimentId: String, reason: SafetyPauseReason) {
        val request = OneTimeWorkRequestBuilder<SafetyPauseWorker>()
            .also { builder ->
                SafetyPauseWorkIdentity.tags(experimentId, reason).forEach(builder::addTag)
            }
            .setInputData(
                Data.Builder()
                    .putString(SafetyPauseWorker.KEY_EXPERIMENT_ID, experimentId)
                    .putString(SafetyPauseWorker.KEY_REASON, reason.name)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        awaitWorkMutations(
            listOf({
                workManager.enqueueUniqueWork(
                    SafetyPauseWorkIdentity.workName(experimentId, reason),
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }),
        )
    }

    override suspend fun pendingSafetyPauseReason(experimentId: String): SafetyPauseReason? =
        withContext(Dispatchers.IO) {
            val active = workManager.getWorkInfosByTag(SafetyPauseWorkIdentity.COMMON_TAG).get()
                .filterNot { it.state.isFinished }
            SafetyPauseWorkIdentity.activeReason(experimentId, active.map { it.tags })
        }

    override suspend fun cancelSafetyPauseRetry() {
        withContext(NonCancellable) {
            awaitWorkMutations(listOf({ workManager.cancelAllWorkByTag(SafetyPauseWorkIdentity.COMMON_TAG) }))
        }
    }

    override suspend fun cancelCollectionWork(experimentId: String, occurrenceIds: Set<String>) {
        awaitCleanupMutations(
            notificationCleanup = {
                cancelInterventionNotifications(occurrenceIds)
                // Finished or withdrawn: the reminder has nothing left to remind anyone of, and
                // today's notification must not outlive the study it describes.
                notificationManager.cancel(DailyStatusWorker.NOTIFICATION_TAG, 0)
            },
            mutations = listOf(
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.deliveryTag(experimentId)) },
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.expiryTag(experimentId)) },
                { workManager.cancelUniqueWork(deadlineWorkName(experimentId)) },
                { workManager.cancelUniqueWork(DAILY_STATUS_WORK_NAME) },
            ),
        )
    }

    override suspend fun cancel(experimentId: String) {
        awaitCleanupMutations(
            notificationCleanup = { notificationManager.cancel(DailyStatusWorker.NOTIFICATION_TAG, 0) },
            mutations = listOf(
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.deliveryTag(experimentId)) },
                { workManager.cancelAllWorkByTag(InterventionWorkIdentity.expiryTag(experimentId)) },
                { workManager.cancelUniqueWork(deadlineWorkName(experimentId)) },
                { workManager.cancelUniqueWork(DAILY_STATUS_WORK_NAME) },
                { workManager.cancelAllWorkByTag(uploadTag(experimentId)) },
            ),
        )
    }

    private fun deadlineWorkName(experimentId: String) = "particeps-deadline-$experimentId"
    private val DAILY_STATUS_WORK_NAME = "particeps-daily-status"
    private fun uploadTag(experimentId: String) = "particeps-upload-$experimentId"
    companion object {
        fun uploadWorkName(experimentId: String, configurationId: String) =
            "particeps-upload-$experimentId-$configurationId"
    }
}

internal data class CollectionWorkPlan(
    val deadlineDelayMillis: Long?,
    val deadlinePolicy: ExistingWorkPolicy,
    val scheduleDailyStatus: Boolean,
    val scheduleUpload: Boolean,
)

/** Pure, auditable policy used by every start, resume and recovery scheduling acknowledgement. */
internal fun collectionWorkPlan(
    configuration: StudyConfiguration,
    metadata: StudyMetadata,
    observedAt: ResearchTime,
): CollectionWorkPlan {
    require(metadata.experimentId == configuration.experimentId) { "Experiment ID mismatch" }
    require(metadata.configurationId == configuration.configurationId) { "Configuration ID mismatch" }
    val started = metadata.state in STARTED_STUDY_STATES
    if (!started) {
        require(metadata.transitions.none { it.reason == TransitionReason.PARTICIPANT_STARTED }) {
            "Pre-start study contains a participant start"
        }
        return CollectionWorkPlan(
            deadlineDelayMillis = null,
            deadlinePolicy = ExistingWorkPolicy.REPLACE,
            scheduleDailyStatus = false,
            scheduleUpload = false,
        )
    }
    participantStartedAt(metadata)
    val active = metadata.state in ACTIVE_STUDY_STATES
    if (!active) {
        return CollectionWorkPlan(
            deadlineDelayMillis = null,
            deadlinePolicy = ExistingWorkPolicy.REPLACE,
            scheduleDailyStatus = false,
            scheduleUpload = configuration.upload != null,
        )
    }
    val lifetime = studyLifetime(configuration, metadata, observedAt)
    return CollectionWorkPlan(
        // REPLACE is intentional: same-boot TIME_CHANGED/process recovery and rc5's reset deadline
        // must be corrected from the immutable participant-start boundary on every acknowledged
        // ensure. Cross-boot active repair is rejected by studyLifetime before reaching this plan.
        deadlineDelayMillis = lifetime.remainingMillis.takeIf { active },
        deadlinePolicy = ExistingWorkPolicy.REPLACE,
        scheduleDailyStatus = active,
        scheduleUpload = configuration.upload != null,
    )
}

private val ACTIVE_STUDY_STATES = setOf(ExperimentState.RUNNING, ExperimentState.PAUSED)
private val STARTED_STUDY_STATES = ACTIVE_STUDY_STATES + setOf(
    ExperimentState.COMPLETED,
    ExperimentState.WITHDRAWN,
)

/** Does not report a retry boundary as durable until WorkManager commits its transaction. */
internal suspend fun awaitWorkPersistence(operation: Operation) {
    operation.await()
}

/** Invokes every mutation and awaits every returned transaction before surfacing any failure. */
internal suspend fun awaitWorkMutations(mutations: List<() -> Operation>) {
    var firstFailure: Throwable? = null
    val operations = buildList {
        mutations.forEach { mutation ->
            try {
                add(mutation())
            } catch (failure: Throwable) {
                val existing = firstFailure
                if (existing == null) firstFailure = failure else existing.addSuppressed(failure)
            }
        }
    }
    operations.forEach { operation ->
        try {
            awaitWorkPersistence(operation)
        } catch (failure: Throwable) {
            val existing = firstFailure
            if (existing == null) firstFailure = failure else existing.addSuppressed(failure)
        }
    }
    firstFailure?.let { throw it }
}

/** Notification cleanup cannot prevent any WorkManager cancellation from being attempted. */
internal suspend fun awaitCleanupMutations(
    notificationCleanup: () -> Unit,
    mutations: List<() -> Operation>,
) = withContext(NonCancellable) {
    var firstFailure: Throwable? = try {
        notificationCleanup()
        null
    } catch (failure: Throwable) {
        failure
    }
    try {
        awaitWorkMutations(mutations)
    } catch (failure: Throwable) {
        val existing = firstFailure
        if (existing == null) firstFailure = failure else existing.addSuppressed(failure)
    }
    firstFailure?.let { throw it }
}

internal object SafetyPauseWorkIdentity {
    const val COMMON_TAG = "particeps-safety-pause"
    private const val STUDY_TAG_PREFIX = "particeps-safety-pause-study:"
    private const val REASON_TAG_PREFIX = "particeps-safety-pause-reason:"

    fun workName(experimentId: String, reason: SafetyPauseReason) =
        "particeps-safety-pause-${studyIdentity(experimentId)}-${reason.name}"

    fun tags(experimentId: String, reason: SafetyPauseReason): Set<String> = setOf(
        COMMON_TAG,
        studyTag(experimentId),
        "$REASON_TAG_PREFIX${reason.name}",
    )

    fun activeReason(experimentId: String, activeWorkTags: List<Set<String>>): SafetyPauseReason? {
        val decoded = activeWorkTags.map { tags ->
            check(COMMON_TAG in tags) { "Active safety-pause work is missing its common tag" }
            val studyTags = tags.filter { it.startsWith(STUDY_TAG_PREFIX) }
            val reasonTags = tags.filter { it.startsWith(REASON_TAG_PREFIX) }
            check(studyTags.size == 1 && reasonTags.size == 1) {
                "Active safety-pause work has malformed identity tags"
            }
            val reasonName = reasonTags.single().removePrefix(REASON_TAG_PREFIX)
            val reason = SafetyPauseReason.entries.singleOrNull { it.name == reasonName }
                ?: error("Active safety-pause work has an unknown reason")
            studyTags.single() to reason
        }
        val reasons = decoded
            .filter { (tag, _) -> tag == studyTag(experimentId) }
            .mapTo(mutableSetOf()) { (_, reason) -> reason }
        check(reasons.size <= 1) { "Multiple active safety-pause reasons exist for one study" }
        return reasons.singleOrNull()
    }

    private fun studyTag(experimentId: String) = "$STUDY_TAG_PREFIX${studyIdentity(experimentId)}"

    private fun studyIdentity(experimentId: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(experimentId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal object InterventionWorkIdentity {
    fun deliveryTag(experimentId: String) = "particeps-intervention-delivery-$experimentId"
    fun expiryTag(experimentId: String) = "particeps-intervention-expiry-$experimentId"
    fun deliveryName(experimentId: String, occurrenceId: String) = "particeps-intervention-$experimentId-$occurrenceId"
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
        val application = applicationContext as cool.jacoblin.particeps.CollectorApplication
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
            // Not the join URI. This only makes each intervention's PendingIntent distinct, and it is
            // deliberately not declared in any manifest intent filter: the join filter requires
            // ACTION_VIEW with host "join" and path "/v1", which this intent has none of.
            .setData(Uri.Builder().scheme("particeps").authority("occurrence").appendPath(occurrenceId).build())
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
            manager.notify(
                occurrenceId,
                0,
                android.app.Notification.Builder(applicationContext, ParticepsNotificationChannels.INTERVENTIONS)
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
        const val ACTION_OPEN_OCCURRENCE = "cool.jacoblin.particeps.OPEN_OCCURRENCE"
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
        val application = applicationContext as cool.jacoblin.particeps.CollectorApplication
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
