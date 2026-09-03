package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.application.ParticipantSurveyAnswer
import cool.jacoblin.particeps.core.application.StudyCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveySubmissionStateTest {
    @Test
    fun committedSuccessPermanentlyDisablesEditing() {
        val draft = mapOf("question-one" to ParticipantSurveyAnswer.Text("answer"))
        val state = submissionState(
            SurveyScreenState(loading = false, answers = draft, editable = true),
            StudyCommandResult.Success,
        )

        assertEquals(draft, state.answers)
        assertTrue(state.submitted)
        assertFalse(state.editable)
        assertEquals("submitted", state.message)
    }

    @Test
    fun invalidInputCanBeCorrectedWhileUnavailableActionCannot() {
        val current = SurveyScreenState(loading = false, editable = false)

        val unavailable = submissionState(current, StudyCommandResult.InvalidState)
        assertFalse(unavailable.editable)
        assertEquals("unavailable", unavailable.message)

        val invalid = submissionState(current, StudyCommandResult.InvalidInput)
        assertTrue(invalid.editable)
        assertEquals("invalid", invalid.message)
    }
}
