package cool.jacoblin.particeps.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdmissionLockTest {
    @Test
    fun uncontendedSubmissionRunsUnderTheLockAndReleasesIt() = runTest {
        val mutex = Mutex()
        val result = mutex.withLockUntilDrain(CompletableDeferred()) {
            assertTrue(mutex.isLocked)
            "committed"
        }
        assertEquals("committed", result)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun alreadyDrainingDoesNotRunTheSubmission() = runTest {
        val mutex = Mutex()
        val signal = CompletableDeferred(Unit)
        assertNull(mutex.withLockUntilDrain(signal) { error("Admission is draining") })
        assertFalse(mutex.isLocked)
    }

    @Test
    fun contendedSubmissionSuspendsWithoutSchedulingPollingWakeups() = runTest {
        val mutex = Mutex(locked = true)
        val waiter = async { mutex.withLockUntilDrain(CompletableDeferred()) { "committed" } }
        advanceUntilIdle()
        assertFalse(waiter.isCompleted)
        assertEquals(0L, testScheduler.currentTime)

        mutex.unlock()
        assertEquals("committed", waiter.await())
        assertFalse(mutex.isLocked)
    }

    @Test
    fun drainReleasesTheWaiterWhileTheBarrierStillOwnsTheMutex() = runTest {
        val mutex = Mutex()
        val barrierOwner = Any()
        mutex.lock(barrierOwner)
        val signal = CompletableDeferred<Unit>()
        val waiter = async { mutex.withLockUntilDrain(signal) { error("Barrier owns the mutex") } }
        runCurrent()

        signal.complete(Unit)
        assertNull(waiter.await())
        assertTrue(mutex.holdsLock(barrierOwner))
        mutex.unlock(barrierOwner)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun cancellationWhileWaitingDoesNotStealOrReleaseAnotherOwnerLock() = runTest {
        val mutex = Mutex()
        val barrierOwner = Any()
        mutex.lock(barrierOwner)
        val waiter = async { mutex.withLockUntilDrain(CompletableDeferred()) { error("Cancelled") } }
        runCurrent()

        waiter.cancelAndJoin()
        assertTrue(mutex.holdsLock(barrierOwner))
        mutex.unlock(barrierOwner)
        runCurrent()
        assertFalse(mutex.isLocked)
    }

    @Test
    fun cancellationAfterLockHandoffBeforeWaiterResumesReleasesOwnership() = runTest {
        val mutex = Mutex(locked = true)
        val waiter = async { mutex.withLockUntilDrain(CompletableDeferred()) { error("Cancelled") } }
        runCurrent()

        mutex.unlock()
        waiter.cancelAndJoin()
        assertFalse(mutex.isLocked)
        assertTrue(mutex.tryLock())
        mutex.unlock()
    }

    @Test
    fun drainRacingWithLockHandoffNeverLeaksOwnershipOrRunsAfterDrainWins() = runTest {
        val mutex = Mutex(locked = true)
        val signal = CompletableDeferred<Unit>()
        val waiter = async { mutex.withLockUntilDrain(signal) { error("Drain won the race") } }
        runCurrent()

        signal.complete(Unit)
        mutex.unlock()
        assertNull(waiter.await())
        assertFalse(mutex.isLocked)
    }

    @Test
    fun cancellationDuringTheProtectedActionReleasesOwnership() = runTest {
        val mutex = Mutex(locked = true)
        val entered = CompletableDeferred<Unit>()
        val waiter = async {
            mutex.withLockUntilDrain(CompletableDeferred()) {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        runCurrent()
        mutex.unlock()
        entered.await()
        assertTrue(mutex.isLocked)

        waiter.cancelAndJoin()
        assertFalse(mutex.isLocked)
    }
}
