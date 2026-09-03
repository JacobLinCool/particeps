package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.ResearchTime
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAdmissionGateTest {
    @Test
    fun drainingSeparatesPreDrainAndExactBoundaryFlushCapabilities() {
        var now = time(1)
        val gate = EventAdmissionGate { now }
        val epoch = ConditionEpochId("123e4567-e89b-42d3-a456-426614174000")
        val token = gate.open(epoch, time(1_000))

        assertTrue(gate.classify(token, listOf(time(99))) is AdmissionDecision.Active)
        val flushToken = gate.beginDrain(time(100))
        assertNull(gate.capture())
        assertNull(gate.captureBarrierFlush(time(99)))
        assertNotNull(gate.captureBarrierFlush(time(100)))
        assertNotNull(flushToken)
        assertTrue(gate.classify(token, listOf(time(99))) is AdmissionDecision.PreDrain)
        assertTrue(gate.classify(flushToken, listOf(time(100))) is AdmissionDecision.BoundaryFlush)
        assertTrue(gate.classify(token, listOf(time(101))) is AdmissionDecision.Rejected)
        assertTrue(gate.classify(token, listOf(time(99, "other-boot"))) is AdmissionDecision.Rejected)

        gate.close(token)
        assertTrue(gate.classify(token, listOf(time(1))) is AdmissionDecision.Rejected)
    }

    @Test
    fun tokensAreBoundToOneGateGeneration() {
        val first = EventAdmissionGate { time(1) }
        val second = EventAdmissionGate { time(1) }
        val epoch = ConditionEpochId("123e4567-e89b-42d3-a456-426614174000")
        val firstToken = first.open(epoch, time(1_000))
        first.beginDrain(time(10))
        first.close(firstToken)
        val replacement = first.open(epoch, time(1_000))
        val other = second.open(epoch, time(1_000))

        assertTrue(first.classify(firstToken, listOf(time(1))) is AdmissionDecision.Rejected)
        assertTrue(first.classify(replacement, listOf(time(11))) is AdmissionDecision.Active)
        assertTrue(first.classify(other, listOf(time(11))) is AdmissionDecision.Rejected)
        assertTrue(second.classify(replacement, listOf(time(11))) is AdmissionDecision.Rejected)
    }

    @Test
    fun exactDeadlineRejectsCaptureEventsAndCoverageOnlySubmissions() {
        var now = time(99)
        val gate = EventAdmissionGate { now }
        val epoch = ConditionEpochId("123e4567-e89b-42d3-a456-426614174000")
        val token = gate.open(epoch, time(100))

        assertNotNull(gate.capture())
        assertTrue(gate.classify(token, listOf(time(99))) is AdmissionDecision.Active)
        now = time(100)
        assertNull(gate.capture())
        assertTrue(gate.classify(token, listOf(time(99))) is AdmissionDecision.Rejected)
        assertTrue(gate.classify(token, emptyList()) is AdmissionDecision.Rejected)
    }

    @Test
    fun drainNeverAdmitsAnObservationAtOrAfterTheSignedDeadline() {
        var now = time(99)
        val gate = EventAdmissionGate { now }
        val epoch = ConditionEpochId("123e4567-e89b-42d3-a456-426614174000")
        val token = gate.open(epoch, time(100))
        val flushToken = gate.beginDrain(time(100))
        now = time(101)

        assertTrue(gate.classify(token, listOf(time(99))) is AdmissionDecision.PreDrain)
        assertTrue(gate.classify(token, listOf(time(100))) is AdmissionDecision.Rejected)
        assertTrue(gate.classify(flushToken, listOf(time(100))) is AdmissionDecision.Rejected)
    }

    private fun time(nanos: Long, boot: String = "boot-a") = ResearchTime(1_000, nanos, boot)
}
