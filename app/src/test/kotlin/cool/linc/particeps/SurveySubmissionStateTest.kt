package cool.linc.particeps

import cool.linc.particeps.core.runtime.SurveyAnswer
import cool.linc.particeps.core.runtime.SurveySubmissionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveySubmissionStateTest {
    @Test
    fun alreadySubmittedReplacesLocalDraftWithDurableAnswer() {
        val draft = mapOf("question-one" to SurveyAnswer.Text("losing draft"))
        val committed = mapOf("question-one" to SurveyAnswer.Text("durable answer"))

        val state = submissionState(
            SurveyScreenState(loading = false, answers = draft),
            SurveySubmissionResult.ALREADY_SUBMITTED,
            committed,
        )

        assertEquals(committed, state.answers)
        assertTrue(state.submitted)
        assertFalse(state.editable)
        assertEquals("submitted", state.message)
    }

    @Test
    fun expiryPermanentlyDisablesEditingWhileInvalidInputCanBeCorrected() {
        val current = SurveyScreenState(loading = false, editable = false)

        val expired = submissionState(current, SurveySubmissionResult.EXPIRED)
        assertFalse(expired.editable)
        assertEquals("expired", expired.message)

        val invalid = submissionState(current, SurveySubmissionResult.INVALID)
        assertTrue(invalid.editable)
        assertEquals("invalid", invalid.message)
    }
}
