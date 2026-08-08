package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.application.DurationCompletionResult
import cool.jacoblin.particeps.core.runtime.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentDeadlineWorkerPolicyTest {
    @Test
    fun earlyDeadlineRetriesInsteadOfCompletingOrFailing() {
        assertEquals(
            DeadlineWorkDisposition.RETRY,
            DurationCompletionResult.NotDue(remainingMillis = 1)
                .deadlineWorkDisposition(),
        )
    }

    @Test
    fun completedAndInactiveDeadlinesFinishIdempotently() {
        assertEquals(
            DeadlineWorkDisposition.SUCCESS,
            DurationCompletionResult.Completed.deadlineWorkDisposition(),
        )
        assertEquals(
            DeadlineWorkDisposition.SUCCESS,
            DurationCompletionResult.Inactive.deadlineWorkDisposition(),
        )
    }

    @Test
    fun failedDeadlineCompletionDoesNotEnterAnUnboundedRetryLoop() {
        assertEquals(
            DeadlineWorkDisposition.FAILURE,
            DurationCompletionResult.Failed(CommandResult.Failed("STORAGE_WRITE_FAILED"))
                .deadlineWorkDisposition(),
        )
    }
}
