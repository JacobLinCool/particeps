package cool.jacoblin.particeps

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.definition.NotificationAction
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.runtime.ActionExecutionFailure
import cool.jacoblin.particeps.platform.UploadAttempt
import cool.jacoblin.particeps.platform.UploadTransportException
import cool.jacoblin.particeps.platform.actionExpiryWorkName
import cool.jacoblin.particeps.platform.awaitWorkPersistence
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class RuntimeTimerWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val timerId = inputData.getString(KEY_TIMER_ID)?.takeIf(TIMER_ID::matches) ?: return Result.failure()
        val generation = inputData.getString(KEY_GENERATION)
            ?.takeIf(UNSIGNED_DECIMAL::matches)
            ?.toULongOrNull()
            ?.takeIf { it > 0uL }
            ?: return Result.failure()
        val session = (applicationContext as CollectorApplication).session
        val snapshot = session.snapshot.first { it.initialized }
        if (snapshot.study == null) return Result.success()
        // A durable timer commit can retire the unique WorkManager row that is currently
        // delivering it. Finish the coordinator transition even when that self-retirement
        // cancels this worker; process death is still handled by the runtime's fail-closed
        // recovery protocol.
        return when (runTimerWakeupAtomically { session.onTimerDue(timerId, generation) }) {
            StudyCommandResult.Success,
            StudyCommandResult.FailedClosed,
            -> Result.success()
            StudyCommandResult.InvalidState -> when (session.snapshot.value.runtime.state) {
                ExperimentState.RUNNING,
                ExperimentState.PAUSED,
                ExperimentState.ACTIVATING,
                ExperimentState.PAUSING,
                -> Result.retry()
                else -> Result.success()
            }
            StudyCommandResult.AccessRequired,
            StudyCommandResult.InvalidInput,
            -> Result.failure()
        }
    }

    companion object {
        internal const val KEY_TIMER_ID = "timer_id"
        internal const val KEY_GENERATION = "timer_generation"
        private val TIMER_ID = Regex("[0-9a-f]{64}")
        private val UNSIGNED_DECIMAL = Regex("[1-9][0-9]*")

        fun input(timerId: String, generation: ULong): Data = Data.Builder()
            .putString(KEY_TIMER_ID, timerId)
            .putString(KEY_GENERATION, generation.toString())
            .build()
    }
}

internal suspend fun <T> runTimerWakeupAtomically(block: suspend () -> T): T =
    withContext(NonCancellable) { block() }

class ActionOutboxWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val actionId = inputData.getString(KEY_ACTION_ID)?.takeIf(ACTION_ID::matches) ?: return Result.failure()
        val application = applicationContext as CollectorApplication
        val session = application.session
        session.snapshot.first { it.initialized }
        val invocation = session.claimAction(actionId) ?: return Result.success()
        val intervention = session.intervention(invocation.interventionId)
        if (intervention == null) {
            session.recordActionResult(actionId, false, ActionExecutionFailure.RECONCILIATION_FAILED)
            return Result.success()
        }
        if (System.currentTimeMillis() >= invocation.expiresAtUtcMillis) {
            return when (intervention.action) {
                is SurveyAction -> session.expireSurvey(actionId).asTerminalWorkerResult()
                is NotificationAction -> session.recordActionResult(
                    actionId,
                    false,
                    ActionExecutionFailure.EXPIRED,
                ).asTerminalWorkerResult()
            }
        }

        val action = intervention.action
        val contentIntent = when (action) {
            is NotificationAction -> PendingIntent.getActivity(
                applicationContext,
                actionId.stableRequestCode(),
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            is SurveyAction -> PendingIntent.getActivity(
                applicationContext,
                actionId.stableRequestCode(),
                Intent(applicationContext, SurveyActivity::class.java)
                    .putExtra(SurveyActivity.ACTION_ID, actionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = Notification.Builder(applicationContext, ParticepsNotificationChannels.INTERVENTIONS)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(action.notificationTitle)
            .setContentText(action.notificationMessage)
            .setStyle(Notification.BigTextStyle().bigText(action.notificationMessage))
            .setContentIntent(contentIntent)
            .setAutoCancel(action is SurveyAction)
            .build()
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        val displayed = try {
            application.actionOutboxNotifier.displayIfRunning(
                actionId = actionId,
                isRunning = {
                    session.snapshot.value.runtime.state == ExperimentState.RUNNING &&
                        System.currentTimeMillis() < invocation.expiresAtUtcMillis
                },
            ) {
                if (System.currentTimeMillis() >= invocation.expiresAtUtcMillis) {
                    throw ActionDisplayException(ActionExecutionFailure.EXPIRED)
                }
                if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw ActionDisplayException(ActionExecutionFailure.DELIVERY_FAILED)
                }
                try {
                    notifications.notify(actionId, 0, notification)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    throw ActionDisplayException(ActionExecutionFailure.DELIVERY_FAILED, failure)
                }
                if (action is SurveyAction) {
                    try {
                        scheduleSurveyExpiry(applicationContext, actionId, invocation.expiresAtUtcMillis)
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        try {
                            notifications.cancel(actionId, 0)
                        } catch (cleanupFailure: Throwable) {
                            if (cleanupFailure is CancellationException) throw cleanupFailure
                            failure.addSuppressed(cleanupFailure)
                        }
                        throw ActionDisplayException(ActionExecutionFailure.RECONCILIATION_FAILED, failure)
                    }
                }
            }
        } catch (failure: ActionDisplayException) {
            return session.recordActionResult(actionId, false, failure.reason).asTerminalWorkerResult()
        }
        if (!displayed) {
            if (
                session.snapshot.value.runtime.state == ExperimentState.RUNNING &&
                System.currentTimeMillis() >= invocation.expiresAtUtcMillis
            ) {
                return when (action) {
                    is SurveyAction -> session.expireSurvey(actionId).asTerminalWorkerResult()
                    is NotificationAction -> session.recordActionResult(
                        actionId,
                        false,
                        ActionExecutionFailure.EXPIRED,
                    ).asTerminalWorkerResult()
                }
            }
            return Result.success()
        }

        return when (action) {
            is NotificationAction -> session.recordActionResult(actionId, true).asTerminalWorkerResult()
            is SurveyAction -> Result.success()
        }
    }

    companion object {
        const val KEY_ACTION_ID = "action_id"
        private val ACTION_ID = Regex("[0-9a-f]{64}")
    }
}

private class ActionDisplayException(
    val reason: ActionExecutionFailure,
    cause: Throwable? = null,
) : RuntimeException(reason.name, cause)

class ActionExpiryWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val actionId = inputData.getString(ActionOutboxWorker.KEY_ACTION_ID)
            ?.takeIf { it.length == 64 && it.all { character -> character in '0'..'9' || character in 'a'..'f' } }
            ?: return Result.failure()
        val application = applicationContext as CollectorApplication
        val session = application.session
        session.snapshot.first { it.initialized }
        var retractFailure: Throwable? = null
        try {
            // Mark the action inactive under the same gate used by display before committing
            // expiry. A worker that claimed just before the deadline can no longer display after
            // this point, even if WorkManager has not cancelled it yet.
            application.actionOutboxNotifier.retractVisible(actionId)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            retractFailure = failure
        }
        val result = session.expireSurvey(actionId)
        if (result == StudyCommandResult.InvalidState &&
            session.snapshot.value.runtime.state == ExperimentState.RUNNING
        ) {
            // The wake may have arrived just before the authoritative runtime deadline. Reconcile
            // through the runtime so the durable pending action is re-armed and the display gate is
            // reopened only when it is still eligible.
            session.reconcileActionOutbox()
        }
        return if (retractFailure == null) result.asTerminalWorkerResult() else Result.retry()
    }
}

class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val experimentId = inputData.getString(KEY_EXPERIMENT_ID)
            ?.takeIf { STUDY_ID.matches(it) }
            ?: return Result.failure()
        val application = applicationContext as CollectorApplication
        val initialized = application.session.snapshot.first { it.initialized }
        if (initialized.study?.experimentId != experimentId) return Result.success()
        return try {
            when (val attempt = application.uploadPlatform.uploadOnce(application.session, experimentId)) {
                UploadAttempt.Stale -> Result.success()
                UploadAttempt.Retry -> Result.retry()
                is UploadAttempt.NothingToUpload -> {
                    if (application.session.snapshot.value.runtime.state in RESCHEDULABLE_STATES) {
                        application.uploadPlatform.scheduleSuccessor(attempt.plan)
                    }
                    Result.success()
                }
                is UploadAttempt.Uploaded -> {
                    if (application.session.snapshot.value.runtime.state in RESCHEDULABLE_STATES) {
                        application.uploadPlatform.scheduleSuccessor(attempt.plan)
                    }
                    Result.success()
                }
            }
        } catch (failure: UploadTransportException) {
            if (failure.retryable) Result.retry() else Result.failure()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            Result.retry()
        }
    }

    companion object {
        const val KEY_EXPERIMENT_ID = "experiment_id"
        private val STUDY_ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        private val RESCHEDULABLE_STATES = setOf(
            ExperimentState.ACTIVATING,
            ExperimentState.RUNNING,
            ExperimentState.PAUSING,
            ExperimentState.PAUSED,
        )
    }
}

private suspend fun scheduleSurveyExpiry(context: Context, actionId: String, expiresAtUtcMillis: Long) {
    val delay = (expiresAtUtcMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val request = OneTimeWorkRequestBuilder<ActionExpiryWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
        .setInputData(Data.Builder().putString(ActionOutboxWorker.KEY_ACTION_ID, actionId).build())
        .build()
    awaitWorkPersistence(
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            actionExpiryWorkName(actionId),
            ExistingWorkPolicy.REPLACE,
            request,
        ),
    )
}

private fun StudyCommandResult.asTerminalWorkerResult(): ListenableWorker.Result = when (this) {
    StudyCommandResult.Success,
    StudyCommandResult.InvalidState,
    StudyCommandResult.FailedClosed,
    -> ListenableWorker.Result.success()
    StudyCommandResult.AccessRequired,
    StudyCommandResult.InvalidInput,
    -> ListenableWorker.Result.failure()
}

private fun String.stableRequestCode(): Int = take(8).toUInt(16).toInt()
