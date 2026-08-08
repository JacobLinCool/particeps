package cool.jacoblin.particeps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cool.jacoblin.particeps.core.application.StudySessionSnapshot
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal sealed interface SafetyPauseWorkerDecision {
    data object Retry : SafetyPauseWorkerDecision
    data object Complete : SafetyPauseWorkerDecision
    data class Attempt(
        val experimentId: String,
        val reason: SafetyPauseReason,
    ) : SafetyPauseWorkerDecision
}

internal fun safetyPauseWorkerDecision(
    encodedExperimentId: String?,
    encodedReason: String?,
    snapshot: StudySessionSnapshot,
): SafetyPauseWorkerDecision {
    val experimentId = encodedExperimentId
        ?.takeIf(String::isNotBlank)
        ?: return SafetyPauseWorkerDecision.Retry
    val reason = encodedReason
        ?.let { encoded -> SafetyPauseReason.entries.singleOrNull { it.name == encoded } }
        ?: return SafetyPauseWorkerDecision.Retry
    val configuration = snapshot.configuration
    if (configuration == null) {
        return if (snapshot.safetyPauseStatus != null || snapshot.recoveryBlocked) {
            SafetyPauseWorkerDecision.Retry
        } else {
            SafetyPauseWorkerDecision.Complete
        }
    }
    return if (configuration.experimentId == experimentId) {
        SafetyPauseWorkerDecision.Attempt(experimentId, reason)
    } else {
        SafetyPauseWorkerDecision.Complete
    }
}

/** Independently retries one typed fail-closed PAUSED boundary and its cleanup. */
class SafetyPauseWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as CollectorApplication
        val snapshot = application.session.snapshot.first { it.initialized }
        val decision = safetyPauseWorkerDecision(
            encodedExperimentId = inputData.getString(KEY_EXPERIMENT_ID),
            encodedReason = inputData.getString(KEY_REASON),
            snapshot = snapshot,
        )
        return when (decision) {
            SafetyPauseWorkerDecision.Retry -> Result.retry()
            SafetyPauseWorkerDecision.Complete -> Result.success()
            is SafetyPauseWorkerDecision.Attempt -> try {
                if (application.session.retrySafetyPause(decision.experimentId, decision.reason)) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_EXPERIMENT_ID = "experiment_id"
        const val KEY_REASON = "reason"
    }
}
