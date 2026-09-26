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

    @Test fun resolvedDateWindowsAgreeWithTheDirectWalkAtEveryInstant() {
        val random = java.util.Random(20260925)
        val zones = listOf(
            "Asia/Taipei", "America/New_York", "America/Santiago", "Europe/London",
            "Australia/Lord_Howe", "Pacific/Apia", "America/Havana", "UTC",
        )
        val windows = listOf(
            StateCondition.StudyLocalWindow(1, 7, "09:00", "21:00"),
            StateCondition.StudyLocalWindow(3, 5, "12:00", "17:00"),
            StateCondition.StudyLocalWindow(2, 2, "02:30", "04:00"),
            StateCondition.StudyLocalWindow(1, 30, "00:00", "01:30"),
            StateCondition.StudyLocalWindow(4, 9, "23:00", "23:59"),
        )
        // Starts around DST changes, a skipped calendar day, and ordinary dates.
        val starts = listOf(
            "2011-12-27T10:00:00Z", "2026-03-06T15:00:00Z", "2026-03-27T23:30:00Z", "2026-04-02T12:00:00Z",
            "2026-09-04T03:00:00Z", "2026-10-02T14:00:00Z", "2026-10-30T14:00:00Z",
        ).map(::ms)
        var compared = 0
        zones.forEach { zoneId ->
            val zone = java.time.ZoneId.of(zoneId)
            windows.forEach { window ->
                starts.forEach { start ->
                    val table = StudyLocalWindowTable(
                        window.firstDay, window.lastDay, java.time.LocalTime.parse(window.startLocalTime),
                        java.time.LocalTime.parse(window.endLocalTime), start, zone,
                    )
                    val span = (window.lastDay + 3L) * 86_400_000L
                    repeat(300) {
                        val now = start - 2 * 86_400_000L + (random.nextDouble() * (span + 2 * 86_400_000L)).toLong()
                        assertEquals(studyLocalWindow(window, start, now, zoneId), table.state(now))
                        compared++
                    }
                    // Every window boundary and every local midnight, and a millisecond either side.
                    val edges = mutableListOf<Long>()
                    var cursor: Long? = start - 86_400_000L
                    while (cursor != null) {
                        edges += cursor
                        cursor = studyLocalWindow(window, start, cursor, zoneId).nextBoundaryUtcMillis
                    }
                    val firstDate = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate().minusDays(1)
                    (0L..window.lastDay + 2L).forEach { day ->
                        edges += firstDate.plusDays(day).atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    edges.forEach { edge ->
                        (edge - 1..edge + 1).forEach { now ->
                            assertEquals(studyLocalWindow(window, start, now, zoneId), table.state(now))
                        }
                    }
                }
            }
        }
        assertEquals(zones.size * windows.size * starts.size * 300, compared)
    }

    @Test fun deviceZoneChangeReevaluatesTheWindowUsingObservedZone() {
        val now = ms("2026-09-08T05:00:00Z")
        assertTrue(studyLocalWindow(condition, start, now, "Asia/Taipei").active)
        assertFalse(studyLocalWindow(condition, start, now, "UTC").active)
    }
}
