package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.runtime.CommandResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

/** Restores the process-wide application session to the state every stateful test owns. */
internal suspend fun StudySessionManager.clearStudyDataForTest() {
    withTimeout(TEST_SESSION_TIMEOUT_MILLIS) { snapshot.first { it.initialized } }

    if (snapshot.value.configuration != null) {
        val state = requireNotNull(snapshot.value.runtime.metadata).state
        if (state != ExperimentState.COMPLETED && state != ExperimentState.WITHDRAWN) {
            assertEquals(
                "Failed to withdraw the study left in the shared instrumentation process",
                CommandResult.Success,
                withdraw(),
            )
        }
        deleteLocalData()
    }

    assertNull("Instrumentation-test cleanup left an active study", snapshot.value.configuration)
    assertFalse("Instrumentation-test cleanup left deletion pending", snapshot.value.deletionPending)
}

private const val TEST_SESSION_TIMEOUT_MILLIS = 40_000L
