package cool.jacoblin.particeps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class RuntimeTimerWorkerTest {
    @Test
    fun workInputContainsOnlyStableTimerIdentityAndGeneration() {
        val input = RuntimeTimerWorker.input(TIMER_ID, 42uL)

        assertEquals(
            setOf(
                "timer_id",
                "timer_generation",
            ),
            input.keyValueMap.keys,
        )
        assertEquals(TIMER_ID, input.getString("timer_id"))
        assertEquals("42", input.getString("timer_generation"))
    }

    @Test
    fun workerCancellationCannotInterruptDurableTimerTransition() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var durableTransitionCompleted = false
        val worker = launch {
            runTimerWakeupAtomically {
                entered.complete(Unit)
                release.await()
                durableTransitionCompleted = true
            }
        }

        entered.await()
        worker.cancel()
        yield()
        assertFalse(durableTransitionCompleted)

        release.complete(Unit)
        worker.cancelAndJoin()
        assertTrue(durableTransitionCompleted)
    }

    private companion object {
        const val TIMER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
