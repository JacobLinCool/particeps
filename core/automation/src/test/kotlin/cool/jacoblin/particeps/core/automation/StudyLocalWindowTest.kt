package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.StateCondition
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class StudyLocalWindowTest {
    private val condition = StateCondition.StudyLocalWindow(3, 5, "12:00", "17:00")
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
    private val start = ms("2026-09-06T02:15:00Z") // Taipei, day 1 at 10:15.

    @Test fun participantLocalDatesHaveExclusiveEndAndOnlyThreeTreatmentDays() {
        fun state(time: String) = studyLocalWindow(condition, start, ms(time), "Asia/Taipei")
        assertEquals(StudyLocalWindowState(false, ms("2026-09-08T04:00:00Z")), state("2026-09-07T06:00:00Z"))
        assertEquals(StudyLocalWindowState(true, ms("2026-09-08T09:00:00Z")), state("2026-09-08T04:00:00Z"))
        assertEquals(StudyLocalWindowState(false, ms("2026-09-09T04:00:00Z")), state("2026-09-08T09:00:00Z"))
        assertTrue(state("2026-09-10T08:59:59.999Z").active)
        assertEquals(StudyLocalWindowState(false, null), state("2026-09-10T09:00:00Z"))
        assertEquals(StudyLocalWindowState(false, null), studyLocalWindow(condition, null, start, "Asia/Taipei"))
    }

    @Test fun localDaysFollowDstInsteadOfAdding24Hours() {
        val beforeDst = ms("2026-03-06T15:00:00Z")
        assertEquals(StudyLocalWindowState(false, ms("2026-03-08T16:00:00Z")),
            studyLocalWindow(condition, beforeDst, beforeDst, "America/New_York"))
        val gap = StateCondition.StudyLocalWindow(3, 3, "02:30", "04:00")
        assertEquals(StudyLocalWindowState(false, null), studyLocalWindow(gap, beforeDst, beforeDst, "America/New_York"))
        val overlap = StateCondition.StudyLocalWindow(3, 3, "01:30", "02:30")
        val fallStart = ms("2026-10-30T14:00:00Z")
        assertEquals(StudyLocalWindowState(false, ms("2026-11-01T05:30:00Z")),
            studyLocalWindow(overlap, fallStart, fallStart, "America/New_York"))
    }

    @Test fun deviceZoneChangeReevaluatesTheWindowUsingObservedZone() {
        val now = ms("2026-09-08T05:00:00Z")
        assertTrue(studyLocalWindow(condition, start, now, "Asia/Taipei").active)
        assertFalse(studyLocalWindow(condition, start, now, "UTC").active)
    }
}
