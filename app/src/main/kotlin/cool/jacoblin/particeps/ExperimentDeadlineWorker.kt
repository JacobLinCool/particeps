package cool.jacoblin.particeps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.runtime.CommandResult
import kotlinx.coroutines.flow.first

class ExperimentDeadlineWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expectedExperimentId = inputData.getString(KEY_EXPERIMENT_ID) ?: return Result.failure()
        val session = (applicationContext as CollectorApplication).session
        val snapshot = session.snapshot.first { it.initialized }
        if (snapshot.configuration?.experimentId != expectedExperimentId) return Result.success()
        return when (snapshot.runtime.metadata?.state) {
            ExperimentState.RUNNING,
            ExperimentState.PAUSED -> if (session.completeAfterDuration() == CommandResult.Success) {
                Result.success()
            } else {
                Result.failure()
            }
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_EXPERIMENT_ID = "experiment_id"
    }
}
