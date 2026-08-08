package cool.jacoblin.particeps

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.runtime.CommandResult
import cool.jacoblin.particeps.platform.AndroidStudyWorkScheduler
import cool.jacoblin.particeps.platform.AtomicSafetyPauseStore
import cool.jacoblin.particeps.platform.SafetyPauseWorkIdentity
import cool.jacoblin.particeps.platform.awaitWorkPersistence
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production WorkManager database and the worker's acknowledged self-retirement. */
@RunWith(AndroidJUnit4::class)
class WorkManagerSafetyPauseIntegrationTest {
    @Test
    fun enqueueAcknowledgementIsQueryableAndWorkerRetiresItsOwnDurableWitness() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as CollectorApplication
        val session = application.session
        withTimeout(TIMEOUT_MILLIS) { session.snapshot.first { it.initialized } }
        assertNull("The integration test requires an isolated app data directory", session.snapshot.value.configuration)

        val workManager = WorkManager.getInstance(context)
        val scheduler = AndroidStudyWorkScheduler(context)
        val marker = AtomicSafetyPauseStore(context)
        val reason = SafetyPauseReason.REQUIRED_ACCESS_MISSING
        try {
            session.importSignedConfiguration(requireNotNull(DemoStudy.load)(context.resources))
            val experimentId = requireNotNull(session.snapshot.value.configuration).experimentId
            val workName = SafetyPauseWorkIdentity.workName(experimentId, reason)
            val existingIds = workManager.getWorkInfosForUniqueWork(workName)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .mapTo(mutableSetOf()) { it.id }

            marker.markPending(reason)

            // This method does not return until AndroidX WorkManager acknowledges its real Room
            // transaction. Therefore its newly inserted row must already be queryable here.
            scheduler.scheduleSafetyPauseRetry(experimentId, reason)
            val inserted = workManager.getWorkInfosForUniqueWork(workName)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .filterNot { it.id in existingIds }
            assertEquals("One acknowledged unique-work row must be committed", 1, inserted.size)
            val insertedId = inserted.single().id
            assertTrue(SafetyPauseWorkIdentity.COMMON_TAG in inserted.single().tags)

            val terminal = awaitWorkInfo(workManager, insertedId) { it.state.isFinished }

            // Clearing the durable marker asks WorkManager to cancel the common tag while this
            // worker is itself running. The NonCancellable acknowledgement path must let that
            // cancellation transaction commit, leaving no active retry witness behind.
            assertEquals(WorkInfo.State.CANCELLED, terminal.state)
            assertNull(marker.pendingReason())
            assertNull(session.snapshot.value.safetyPauseStatus)
            assertNull(scheduler.pendingSafetyPauseReason(experimentId))
            assertFalse(
                workManager.getWorkInfosByTag(SafetyPauseWorkIdentity.COMMON_TAG)
                    .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .any { !it.state.isFinished },
            )
        } finally {
            awaitWorkPersistence(workManager.cancelAllWorkByTag(SafetyPauseWorkIdentity.COMMON_TAG))
            marker.clear()
            if (session.snapshot.value.configuration != null) {
                assertEquals(CommandResult.Success, session.withdraw())
                session.deleteLocalData()
            }
        }
    }

    private suspend fun awaitWorkInfo(
        workManager: WorkManager,
        id: UUID,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val info = workManager.getWorkInfoById(id)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (info != null && predicate(info)) return info
            delay(POLL_INTERVAL_MILLIS)
        }
        val last = workManager.getWorkInfoById(id)
            .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        error("Work $id did not reach its expected state; last=$last")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 50L
        const val FUTURE_TIMEOUT_SECONDS = 5L
    }
}
