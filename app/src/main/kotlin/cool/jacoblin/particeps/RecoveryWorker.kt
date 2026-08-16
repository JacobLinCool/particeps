package cool.jacoblin.particeps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cool.jacoblin.particeps.core.runtime.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Retries the same fail-closed recovery path used by app-open and permission return. */
class RecoveryWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as CollectorApplication
        application.session.snapshot.first { it.initialized }
        return try {
            when (application.session.retryRecovery()) {
                CommandResult.Success -> Result.success()
                is CommandResult.Failed -> Result.retry()
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            Result.retry()
        }
    }
}
