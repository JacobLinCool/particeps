package cool.linc.androiddatacollector

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import cool.linc.androiddatacollector.core.runtime.CommandResult
import cool.linc.androiddatacollector.platform.AndroidStudyWorkScheduler
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
        val session = (applicationContext as CollectorApplication).session
        val snapshot = session.snapshot.first { it.initialized }
        // A different study was imported since this work was scheduled; the old job is obsolete.
        if (snapshot.configuration?.experimentId != expectedExperimentId) return Result.success()
        val upload = snapshot.configuration?.upload ?: return Result.success()

        // uploadPending is a no-op before collection starts, so no state check is needed here.
        if (session.uploadPending() != CommandResult.Success) {
            // Retry rather than fail: the usual cause is a network or endpoint problem that
            // resolves on its own, and the events are still safe on the device meanwhile. Retrying
            // keeps this link of the chain alive, so no successor is enqueued here.
            return Result.retry()
        }

        // A study that has ended keeps delivering so its backlog still reaches the researcher.
        // Once the backlog is gone there is nothing left to wake up for, so the chain simply is
        // not renewed.
        if (!session.uploadDrained()) {
            AndroidStudyWorkScheduler(applicationContext).scheduleUpload(
                expectedExperimentId,
                upload,
                ExistingWorkPolicy.REPLACE,
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_EXPERIMENT_ID = "experiment_id"
    }
}
