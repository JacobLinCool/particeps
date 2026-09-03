package cool.jacoblin.particeps.platform

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionOutboxPlatformTest {
    @Test
    fun workIdentitiesUseTheDurableActionId() {
        val actionId = "a".repeat(64)

        assertEquals("runtime-action:$actionId", actionWorkName(actionId))
        assertEquals("runtime-action-expiry:$actionId", actionExpiryWorkName(actionId))
    }

    @Test
    fun retractionSerializesAfterCurrentDisplayAndBlocksLateClaimedWorker() = runBlocking {
        val gate = SerializedActionDisplayGate()
        val actionId = "b".repeat(64)
        gate.activate(actionId) {}
        val displayEntered = CompletableDeferred<Unit>()
        val releaseDisplay = CompletableDeferred<Unit>()
        val displayCount = AtomicInteger()
        val retractCount = AtomicInteger()

        val displayed = async(Dispatchers.Default) {
            gate.displayIfActive(actionId, isRunning = { true }) {
                displayCount.incrementAndGet()
                displayEntered.complete(Unit)
                releaseDisplay.await()
            }
        }
        displayEntered.await()
        val retracted = async(Dispatchers.Default) {
            gate.retract(listOf(actionId)) { retractCount.incrementAndGet() }
        }
        yield()
        assertFalse(retracted.isCompleted)

        releaseDisplay.complete(Unit)
        assertTrue(displayed.await())
        retracted.await()
        assertEquals(1, displayCount.get())
        assertEquals(1, retractCount.get())
        assertFalse(
            gate.displayIfActive(actionId, isRunning = { true }) {
                displayCount.incrementAndGet()
            },
        )
        assertEquals(1, displayCount.get())
    }

    @Test
    fun failedSchedulingLeavesDisplayInactiveUntilAConfirmedRearm() = runBlocking {
        val gate = SerializedActionDisplayGate()
        val actionId = "c".repeat(64)

        val failure = runCatching {
            gate.activate(actionId) { error("scheduling failed") }
        }.exceptionOrNull()
        assertEquals("scheduling failed", failure?.message)
        assertFalse(gate.displayIfActive(actionId, isRunning = { true }) {})

        gate.activate(actionId) {}
        assertTrue(gate.displayIfActive(actionId, isRunning = { true }) {})
    }
}
