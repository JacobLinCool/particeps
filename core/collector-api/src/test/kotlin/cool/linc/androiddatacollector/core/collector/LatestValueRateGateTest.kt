package cool.linc.androiddatacollector.core.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestValueRateGateTest {
    @Test
    fun emitsFirstValueThenCoalescesToLatestAtDeadline() {
        val gate = LatestValueRateGate<String>(1_000)

        assertEquals(emit("first"), gate.offer("first", 10_000))
        assertEquals(defer(800), gate.offer("second", 10_200))
        assertEquals(defer(600), gate.offer("latest", 10_400))
        assertEquals(defer(1), gate.poll(10_999))
        assertEquals(emit("latest"), gate.poll(11_000))
        assertEquals(LatestValueRateGate.Decision.Suppress, gate.poll(12_000))
    }

    @Test
    fun duplicateOfLastEmissionCancelsPendingValue() {
        val gate = LatestValueRateGate<String>(1_000)

        gate.offer("stable", 0)
        gate.offer("changed", 100)
        assertEquals(LatestValueRateGate.Decision.Suppress, gate.offer("stable", 200))
        assertEquals(LatestValueRateGate.Decision.Suppress, gate.poll(1_000))
    }

    @Test
    fun callerSuppliedEquivalenceDefinesAMeaningfulChange() {
        val gate = LatestValueRateGate<Int>(1_000) { previous, current ->
            kotlin.math.abs(previous - current) < 5
        }

        assertEquals(emit(100), gate.offer(100, 0))
        assertEquals(LatestValueRateGate.Decision.Suppress, gate.offer(104, 1_000))
        assertEquals(emit(105), gate.offer(105, 1_000))
    }

    @Test
    fun clearingPendingDoesNotRewriteTheEmissionWatermark() {
        val gate = LatestValueRateGate<String>(1_000)

        gate.offer("first", 5_000)
        gate.offer("discarded", 5_100)
        gate.clearPending()
        assertEquals(LatestValueRateGate.Decision.Suppress, gate.poll(6_000))
        assertEquals(emit("next"), gate.offer("next", 6_000))
    }

    @Test
    fun processRestartRestoresValueAndUsesAConservativeFullIntervalFence() {
        val gate = LatestValueRateGate<String>(1_000)
        gate.restoreLastEmission(
            value = "stable",
            currentElapsedMillis = 10_200,
        )

        assertEquals(LatestValueRateGate.Decision.Suppress, gate.offer("stable", 10_200))
        assertEquals(defer(1_000), gate.offer("changed", 10_200))
        assertEquals(emit("changed"), gate.poll(11_200))
    }

    @Test
    fun undecodableValueStillRestoresTheHardBoundAndResumeDoesNotMoveIt() {
        val gate = LatestValueRateGate<String>(1_000)
        gate.restoreLastEmission(
            value = null,
            currentElapsedMillis = 200,
        )
        gate.restoreLastEmission(value = "later-resume", currentElapsedMillis = 500)

        assertEquals(defer(1_000), gate.offer("first-after-reboot", 200))
        assertEquals(emit("first-after-reboot"), gate.poll(1_200))
    }

    private fun <T> emit(value: T) = LatestValueRateGate.Decision.Emit(value)
    private fun defer(delayMillis: Long) = LatestValueRateGate.Decision.Defer(delayMillis)
}
