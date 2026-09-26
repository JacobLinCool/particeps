package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.model.ExperimentState
import org.junit.Assert.assertEquals
import org.junit.Test

class ParticipantExitTest {
    @Test
    fun everyStateOffersOnlyTheExitItsCommandAccepts() {
        val expected = mapOf(
            ExperimentState.IMPORTED to ParticipantExit.DECLINE,
            ExperimentState.CONFIG_VERIFIED to ParticipantExit.DECLINE,
            ExperimentState.CONSENT_PENDING to ParticipantExit.DECLINE,
            ExperimentState.ACCESS_SETUP to ParticipantExit.DECLINE,
            ExperimentState.READY to ParticipantExit.DECLINE,
            // Transitional states settle on their own; neither command belongs to them.
            ExperimentState.ACTIVATING to null,
            ExperimentState.RUNNING to ParticipantExit.WITHDRAW,
            ExperimentState.PAUSING to null,
            ExperimentState.PAUSED to ParticipantExit.WITHDRAW,
            // Delete local data is the only step left once a study has ended.
            ExperimentState.COMPLETED to null,
            ExperimentState.WITHDRAWN to null,
        )

        assertEquals(ExperimentState.entries.toSet(), expected.keys)
        expected.forEach { (state, exit) -> assertEquals(state.name, exit, participantExit(state)) }
    }
}
