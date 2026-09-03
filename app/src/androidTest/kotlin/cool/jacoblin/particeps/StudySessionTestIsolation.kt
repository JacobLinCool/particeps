package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.application.StudySessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

/** Restores the process-wide application session to the state every stateful test owns. */
internal suspend fun StudySessionManager.clearStudyDataForTest() {
    withTimeout(TEST_SESSION_TIMEOUT_MILLIS) { snapshot.first { it.initialized } }

    if (snapshot.value.study != null) {
        // The durable manager owns the legal terminal transition: pre-consent states are deleted
        // directly, while active states are withdrawn before their EngineCommit chain is cleared.
        deleteLocalData()
    }

    assertNull("Instrumentation-test cleanup left an active study", snapshot.value.study)
    assertFalse("Instrumentation-test cleanup left deletion pending", snapshot.value.deletionPending)
}

private const val TEST_SESSION_TIMEOUT_MILLIS = 40_000L
