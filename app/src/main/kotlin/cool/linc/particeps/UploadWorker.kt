package cool.linc.particeps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import cool.linc.particeps.core.application.UploadAttemptResult
import cool.linc.particeps.platform.AndroidStudyWorkScheduler
import kotlinx.coroutines.flow.first

/**
 * Periodically delivers undelivered events to the study's endpoint.
 *
 * Stays scheduled after the study ends, so a finished or withdrawn study still delivers its tail,
 * and retires itself once that backlog is gone. Collection never depends on this worker: if it
 * never succeeds, the study keeps recording and the participant can still export by hand.
 */
class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expectedExperimentId = inputData.getString(KEY_EXPERIMENT_ID) ?: return Result.failure()
        val expectedConfigurationId = inputData.getString(KEY_CONFIGURATION_ID) ?: return Result.failure()
        val session = (applicationContext as CollectorApplication).session
        val snapshot = session.snapshot.first { it.initialized }
        if (snapshot.deletionPending) return Result.success()
        val configuration = snapshot.configuration ?: return Result.success()
        // Configurations may share an experiment ID; both identities must match the scheduled job.
        if (configuration.experimentId != expectedExperimentId ||
            configuration.configurationId != expectedConfigurationId
        ) return Result.success()
        val upload = configuration.upload ?: return Result.success()

        // uploadPending is a no-op before collection starts, so no state check is needed here.
        when (val result = session.uploadPending()) {
            is UploadAttemptResult.Failed -> {
                // Only failures explicitly classified by the transport are retried. A malformed
                // receipt, redirect, or other terminal 4xx must not become an endless request loop.
                return if (result.retryable) Result.retry() else Result.failure()
            }
            UploadAttemptResult.NoWork,
            is UploadAttemptResult.Confirmed,
            -> Unit
        }

        // A study that has ended keeps delivering so its backlog still reaches the researcher.
        // Once the backlog is gone there is nothing left to wake up for, so the chain simply is
        // not renewed.
        if (!session.uploadDrained()) {
            AndroidStudyWorkScheduler(applicationContext).scheduleUpload(
                expectedExperimentId,
                expectedConfigurationId,
                upload,
                ExistingWorkPolicy.REPLACE,
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_EXPERIMENT_ID = "experiment_id"
        const val KEY_CONFIGURATION_ID = "configuration_id"
    }
}
