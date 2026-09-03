package cool.jacoblin.particeps.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleStateMachineTest {
    private val stateMachine = LifecycleStateMachine()

    @Test
    fun participantJourneyUsesExplicitActivationAndPausingBoundaries() {
        val journey = listOf(
            ExperimentState.IMPORTED,
            ExperimentState.CONFIG_VERIFIED,
            ExperimentState.CONSENT_PENDING,
            ExperimentState.ACCESS_SETUP,
            ExperimentState.READY,
            ExperimentState.ACTIVATING,
            ExperimentState.RUNNING,
            ExperimentState.PAUSING,
            ExperimentState.PAUSED,
            ExperimentState.ACTIVATING,
            ExperimentState.RUNNING,
            ExperimentState.PAUSING,
            ExperimentState.COMPLETED,
        )

        journey.zipWithNext().forEach { (from, to) ->
            assertTrue("Expected $from -> $to", stateMachine.canTransition(from, to))
        }
    }

    @Test
    fun runningCannotSkipPausingAndPausedCannotSkipActivating() {
        assertFalse(stateMachine.canTransition(ExperimentState.RUNNING, ExperimentState.PAUSED))
        assertFalse(stateMachine.canTransition(ExperimentState.PAUSED, ExperimentState.RUNNING))
        assertThrows(IllegalArgumentException::class.java) {
            stateMachine.transition(ExperimentState.RUNNING, ExperimentState.PAUSED)
        }
    }

    @Test
    fun exportIsNotAnExperimentState() {
        assertFalse(ExperimentState.entries.any { it.name == "EXPORTED" })
    }
}
