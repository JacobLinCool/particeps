package cool.linc.particeps.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentStateMachineTest {
    private val stateMachine = ExperimentStateMachine()
    private val time = ResearchTime(
        wallTimeUtcMillis = 1_000,
        elapsedRealtimeNanos = 2_000,
        bootSessionId = "boot-test",
    )

    @Test
    fun legalParticipantJourneyPersistsEveryBoundary() {
        var metadata = StudyMetadata.initial("core-demo", "config-demo")

        metadata = stateMachine.transition(
            metadata,
            ExperimentState.CONFIG_VERIFIED,
            TransitionReason.CONFIGURATION_SIGNATURE_VERIFIED,
            time,
        )
        metadata = stateMachine.transition(
            metadata,
            ExperimentState.CONSENT_PENDING,
            TransitionReason.CONSENT_REVIEW_OPENED,
            time,
        )
        metadata = stateMachine.transition(
            metadata,
            ExperimentState.ACCESS_SETUP,
            TransitionReason.CONSENT_ACCEPTED,
            time,
        )
        metadata = stateMachine.transition(
            metadata,
            ExperimentState.READY,
            TransitionReason.ACCESS_PREFLIGHT_PASSED,
            time,
        )
        metadata = stateMachine.transition(
            metadata,
            ExperimentState.RUNNING,
            TransitionReason.PARTICIPANT_STARTED,
            time,
        )
        metadata = stateMachine.transition(
            metadata,
            ExperimentState.PAUSED,
            TransitionReason.PARTICIPANT_PAUSED,
            time,
        )
        metadata = stateMachine.transition(
            metadata,
            ExperimentState.RUNNING,
            TransitionReason.PARTICIPANT_RESUMED,
            time,
        )
        metadata = stateMachine.transition(
            metadata,
            ExperimentState.COMPLETED,
            TransitionReason.PARTICIPANT_FINISHED_EARLY,
            time,
        )

        assertEquals(ExperimentState.COMPLETED, metadata.state)
        assertEquals(8, metadata.transitions.size)
        assertEquals(ExperimentState.IMPORTED, metadata.transitions.first().from)
        assertEquals(ExperimentState.COMPLETED, metadata.transitions.last().to)
    }

    @Test
    fun exportIsNotAnExperimentState() {
        assertFalse(ExperimentState.entries.any { it.name == "EXPORTED" })
    }

    @Test
    fun withdrawalIsAvailableFromEveryNonWithdrawnState() {
        ExperimentState.entries
            .filterNot { it == ExperimentState.WITHDRAWN }
            .forEach { state ->
                assertTrue("Expected withdrawal from $state", stateMachine.canTransition(state, ExperimentState.WITHDRAWN))
            }
    }

    @Test
    fun illegalTransitionAndMismatchedReasonFailClosed() {
        val initial = StudyMetadata.initial("core-demo", "config-demo")

        assertThrows(IllegalArgumentException::class.java) {
            stateMachine.transition(
                initial,
                ExperimentState.RUNNING,
                TransitionReason.PARTICIPANT_STARTED,
                time,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            stateMachine.transition(
                initial,
                ExperimentState.CONFIG_VERIFIED,
                TransitionReason.PARTICIPANT_STARTED,
                time,
            )
        }
    }
}
