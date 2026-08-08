package cool.jacoblin.particeps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cool.jacoblin.particeps.core.application.DurationCompletionResult
import kotlinx.coroutines.flow.first

internal enum class DeadlineWorkDisposition { SUCCESS, RETRY, FAILURE }

internal fun DurationCompletionResult.deadlineWorkDisposition(): DeadlineWorkDisposition = when (this) {
    DurationCompletionResult.Completed,
    DurationCompletionResult.Inactive -> DeadlineWorkDisposition.SUCCESS
    is DurationCompletionResult.NotDue -> DeadlineWorkDisposition.RETRY
    is DurationCompletionResult.Failed -> DeadlineWorkDisposition.FAILURE
}

class ExperimentDeadlineWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expectedExperimentId = inputData.getString(KEY_EXPERIMENT_ID) ?: return Result.failure()
        val session = (applicationContext as CollectorApplication).session
        val snapshot = session.snapshot.first { it.initialized }
        if (snapshot.configuration?.experimentId != expectedExperimentId) return Result.success()
        return when (session.completeAfterDurationIfDue().deadlineWorkDisposition()) {
            DeadlineWorkDisposition.SUCCESS -> Result.success()
            DeadlineWorkDisposition.RETRY -> Result.retry()
            DeadlineWorkDisposition.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_EXPERIMENT_ID = "experiment_id"
    }
}
