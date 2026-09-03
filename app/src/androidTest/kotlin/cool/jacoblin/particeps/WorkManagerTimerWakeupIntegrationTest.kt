package cool.jacoblin.particeps

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.platform.AndroidResearchClocks
import cool.jacoblin.particeps.platform.AndroidTimerWakeupAdapter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production WorkManager database as a durable-runtime timer wakeup adapter. */
@RunWith(AndroidJUnit4::class)
class WorkManagerTimerWakeupIntegrationTest {
    @Test
    fun acknowledgedTimerWakeupIsQueryableAndRetirementCancelsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        val adapter = AndroidTimerWakeupAdapter(
            context,
            AndroidResearchClocks(context, "timer-wakeup-integration"),
        )
        val timer = DurableTimer(
            id = TIMER_ID,
            automationId = "timer-test",
            generation = 7uL,
            causalSequence = 11,
            producerKey = "integration",
            target = TimerTarget.CalendarUtc(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)),
            logicalDeadlineUtcMillis = null,
            expiresAtUtcMillis = null,
        )
        val workName = "runtime-timer:${timer.id}:${timer.generation}"
        workManager.cancelUniqueWork(workName).result.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        try {
            adapter.schedule(timer)
            val inserted = workManager.getWorkInfosForUniqueWork(workName)
                .get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals("One acknowledged unique timer row must be committed", 1, inserted.size)
            assertFalse(inserted.single().state.isFinished)

            adapter.retire(timer.id, timer.generation)
            val terminal = awaitWorkInfo(workManager, inserted.single().id) { it.state.isFinished }
            assertEquals(WorkInfo.State.CANCELLED, terminal.state)
        } finally {
            workManager.cancelUniqueWork(workName).result.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
        const val TIMER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 50L
        const val FUTURE_TIMEOUT_SECONDS = 5L
    }
}
