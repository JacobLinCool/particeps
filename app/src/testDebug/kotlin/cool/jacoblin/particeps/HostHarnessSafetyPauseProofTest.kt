package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.model.ExperimentState
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostHarnessSafetyPauseProofTest {
    @Test
    fun exactlyOneSecondAndAnUnchangedCountProduceTwoActualObservations() = runTest {
        val states = MutableStateFlow(paused)
        val captured = mutableListOf<HostHarnessSafetyPauseObservation>()
        var monitoringStopped = false
        val reader = HostHarnessSafetyPauseProofReader(states.onCompletion { monitoringStopped = true }) {
            observation(states.value, testScheduler.currentTime).also(captured::add)
        }
        val pending = async { reader.read() }
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(1, captured.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), pending.await().observations.map { it.elapsedRealtimeMillis })
        assertEquals(captured, pending.await().observations)
        assertTrue("The session observer must be joined before returning a proof", monitoringStopped)
    }

    @Test
    fun everyObservationMustContainInitializedPausedStateAndTheActualLocalizedNotification() {
        val valid = observation()
        val invalid = listOf(
            valid.copy(session = paused.copy(initialized = false)),
            valid.copy(session = paused.copy(state = null)),
            valid.copy(session = paused.copy(state = ExperimentState.RUNNING)),
            valid.copy(session = paused.copy(lifetimeDataEventCount = -1)),
            valid.copy(elapsedRealtimeMillis = -1),
            valid.copy(notificationTag = null),
            valid.copy(notificationTag = "other-notification"),
            valid.copy(notificationBody = null),
            valid.copy(notificationBody = "wrong body"),
            valid.copy(notificationBody = "$BODY "),
            valid.copy(expectedNotificationBody = ""),
            valid.copy(expectedNotificationBody = "different locale"),
        )
        for (bad in invalid) {
            assertThrows(IllegalStateException::class.java) {
                HostHarnessSafetyPauseProof.from(bad, valid.copy(elapsedRealtimeMillis = 1_000))
            }
            assertThrows(IllegalStateException::class.java) {
                val second = if (bad.elapsedRealtimeMillis < 0) bad else bad.copy(elapsedRealtimeMillis = 1_000)
                HostHarnessSafetyPauseProof.from(valid, second)
            }
        }
    }

    @Test
    fun countChangesIncludingDecreasesCannotBeCertifiedAsQuiescent() {
        for (count in listOf(41L, 43L)) {
            assertThrows(IllegalStateException::class.java) {
                HostHarnessSafetyPauseProof.from(
                    observation(), observation(paused.copy(lifetimeDataEventCount = count), 1_000),
                )
            }
        }
    }

    @Test
    fun shortReversedAndOverflowProneClockIntervalsAreRejected() {
        for ((start, end) in listOf(0L to 999L, 2_000L to 1_000L, Long.MAX_VALUE to 0L)) {
            assertThrows(IllegalStateException::class.java) {
                HostHarnessSafetyPauseProof.from(observation(elapsed = start), observation(elapsed = end))
            }
        }
        val proof = HostHarnessSafetyPauseProof.from(
            observation(elapsed = Long.MAX_VALUE - 1_000), observation(elapsed = Long.MAX_VALUE),
        )
        assertEquals(Long.MAX_VALUE, proof.observations.last().elapsedRealtimeMillis)
    }

    @Test
    fun initializationPauseAndNotificationMayArriveBeforeTheObservationWindowStarts() = runTest {
        val states = MutableStateFlow(paused.copy(initialized = false, state = null))
        var body: String? = null
        val reader = HostHarnessSafetyPauseProofReader(states) {
            observation(states.value, testScheduler.currentTime).copy(notificationBody = body)
        }
        val pending = async { reader.read() }
        runCurrent()
        advanceTimeBy(100)
        states.value = paused
        runCurrent()
        advanceTimeBy(100)
        body = BODY
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(200L, 1_200L), pending.await().observations.map { it.elapsedRealtimeMillis })
    }

    @Test
    fun missingInitializationWrongStateAndWrongNotificationExpireTheOriginalOperation() = runTest {
        val invalid = listOf(
            observation(paused.copy(initialized = false)),
            observation(paused.copy(state = ExperimentState.RUNNING)),
            observation().copy(notificationTag = "wrong-tag"),
            observation().copy(notificationBody = "wrong-body"),
        )
        for (sample in invalid) {
            val operations = HostHarnessOperations(backgroundScope)
            val id = UUID.randomUUID().toString()
            val reader = HostHarnessSafetyPauseProofReader(MutableStateFlow(sample.session)) { sample }
            operations.admit(operations.processId, id, "safety_pause") { reader.read(); "unexpected proof" }
            runCurrent()
            advanceTimeBy(89_999)
            runCurrent()
            assertEquals("RUNNING", operations.status(operations.processId, id).status)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("FAILED", operations.status(operations.processId, id).status)
            assertEquals("TimeoutCancellationException", operations.status(operations.processId, id).result)
        }
    }

    @Test
    fun anyObservedStateOrCountViolationDuringTheWindowIsTerminal() = runTest {
        for (changed in listOf(
            paused.copy(initialized = false),
            paused.copy(state = ExperimentState.RUNNING),
            paused.copy(lifetimeDataEventCount = 43),
        )) {
            val states = MutableStateFlow(paused)
            val reader = HostHarnessSafetyPauseProofReader(states) { observation(states.value, testScheduler.currentTime) }
            val pending = async { runCatching { reader.read() } }
            runCurrent()
            advanceTimeBy(500)
            states.value = changed
            runCurrent()
            states.value = paused
            runCurrent()
            assertTrue("Returning to PAUSED must not erase the observed violation", pending.isCompleted)
            assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun notificationDisappearanceAtTheSecondObservationFailsWithoutRestartingTheWindow() = runTest {
        val states = MutableStateFlow(paused)
        var body: String? = BODY
        var reads = 0
        val reader = HostHarnessSafetyPauseProofReader(states) {
            reads++
            observation(states.value, testScheduler.currentTime).copy(notificationBody = body)
        }
        val pending = async { runCatching { reader.read() } }
        runCurrent()
        body = null
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        assertEquals(2, reads)
    }

    @Test
    fun aRealDelayDoesNotExcuseAnInsufficientObservedMonotonicInterval() = runTest {
        var reads = 0
        val reader = HostHarnessSafetyPauseProofReader(MutableStateFlow(paused)) {
            observation(elapsed = if (reads++ == 0) 10 else 1_009)
        }
        val pending = async { runCatching { reader.read() } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun waitingDoesNotHoldAProjectionMutexOrPreventAConcurrentCollectorFromExposingAdmission() = runTest {
        val projectionMutex = Mutex()
        val states = MutableStateFlow(paused)
        val reader = HostHarnessSafetyPauseProofReader(states) {
            check(projectionMutex.tryLock())
            try {
                observation(states.value, testScheduler.currentTime)
            } finally {
                projectionMutex.unlock()
            }
        }
        val pending = async { runCatching { reader.read() } }
        runCurrent()
        advanceTimeBy(500)
        val collector = launch {
            projectionMutex.withLock { states.value = paused.copy(lifetimeDataEventCount = 43) }
        }
        runCurrent()
        assertTrue("The producer must be able to run during the observation window", collector.isCompleted)
        assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun cancellationJoinsTheSessionObserver() = runTest {
        var monitoringStopped = false
        val reader = HostHarnessSafetyPauseProofReader(
            MutableStateFlow(paused).onCompletion { monitoringStopped = true },
        ) { observation(elapsed = testScheduler.currentTime) }
        val pending = launch { reader.read() }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(monitoringStopped)
    }

    private fun observation(
        state: HostHarnessSafetyPauseState = paused,
        elapsed: Long = 0,
    ) = HostHarnessSafetyPauseObservation(state, elapsed, AndroidRecoveryReporter.NOTIFICATION_TAG, BODY, BODY)

    private val paused = HostHarnessSafetyPauseState(true, ExperimentState.PAUSED, 42)

    private companion object {
        const val BODY = "資料收集已暫停，請開啟 Particeps 查看。"
    }
}
