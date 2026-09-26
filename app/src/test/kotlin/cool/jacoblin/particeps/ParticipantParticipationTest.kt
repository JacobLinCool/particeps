package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.application.ParticipantRuntimeStatus
import cool.jacoblin.particeps.core.model.ExperimentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantParticipationTest {
    @Test
    fun studyDaysAreTwentyFourHourPeriodsSinceStartAndEndWithThePlannedEnd() {
        val participation = status(ExperimentState.RUNNING).toParticipation(durationHours = 120)

        assertEquals(5, participation.studyDayCount)
        assertEquals(START + 120 * HOUR, participation.plannedEndUtcMillis)
        assertEquals(1, participation.studyDayAt(START))
        assertEquals(1, participation.studyDayAt(START + 24 * HOUR - 1))
        assertEquals(2, participation.studyDayAt(START + 24 * HOUR))
        assertEquals(5, participation.studyDayAt(START + 120 * HOUR - 1))
        // The last day does not roll over into a day the study does not have.
        assertEquals(5, participation.studyDayAt(START + 130 * HOUR))
    }

    @Test
    fun aShorterLastDayStillCountsAsAStudyDay() {
        assertEquals(2, status(ExperimentState.RUNNING).toParticipation(durationHours = 36).studyDayCount)
        assertEquals(1, status(ExperimentState.RUNNING).toParticipation(durationHours = 1).studyDayCount)
        assertEquals(1, status(ExperimentState.RUNNING).toParticipation(durationHours = 24).studyDayCount)
    }

    @Test
    fun anUntrustedDeadlineIsNotPresentedAsThePlannedEnd() {
        val participation = status(ExperimentState.RUNNING, deadlineTrusted = false).toParticipation(120)

        assertNull(participation.plannedEndUtcMillis)
        assertEquals(1, participation.studyDayAt(START + HOUR))
    }

    @Test
    fun runningCollectionKeepsGrowingPastTheLastCommitWhilePausedTimeHolds() {
        // Two hours in, of which 90 minutes were collecting, measured at the last commit.
        val participation = status(
            ExperimentState.RUNNING,
            active = 90 * MINUTE,
            calendar = 2 * HOUR,
            measuredAt = START + 2 * HOUR,
        ).toParticipation(120)
        val anHourAfterTheCommit = START + 3 * HOUR

        assertEquals(150 * MINUTE, participation.activeCollection.millisAt(anHourAfterTheCommit))
        assertEquals(30 * MINUTE, participation.pausedMillisAt(anHourAfterTheCommit))
    }

    @Test
    fun pausedTimeGrowsWhileCollectingTimeHolds() {
        val participation = status(
            ExperimentState.PAUSED,
            active = 90 * MINUTE,
            calendar = 2 * HOUR,
            measuredAt = START + 2 * HOUR,
        ).toParticipation(120)
        val anHourLater = START + 3 * HOUR

        assertEquals(ParticipantElapsedTime.Settled(90 * MINUTE), participation.activeCollection)
        assertEquals(90 * MINUTE, participation.activeCollection.millisAt(anHourLater))
        assertEquals(90 * MINUTE, participation.pausedMillisAt(anHourLater))
        assertEquals(1, participation.studyDayAt(anHourLater))
    }

    @Test
    fun anEndedStudyHasSettledTotalsAndNoCurrentDayOrPlannedEnd() {
        listOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN).forEach { ended ->
            val participation = status(
                ended,
                active = 5 * HOUR,
                calendar = 50 * HOUR,
                measuredAt = START + 50 * HOUR,
                stateEnteredAt = START + 6 * HOUR,
                stateEnteredCalendar = 6 * HOUR,
            ).toParticipation(120)
            val muchLater = START + 400 * HOUR

            assertTrue(participation.ended)
            assertNull(participation.studyDayAt(muchLater))
            assertNull(participation.plannedEndUtcMillis)
            assertEquals(5 * HOUR, participation.activeCollection.millisAt(muchLater))
            // Paused time stops at the end even though later commits keep advancing the clock.
            assertEquals(HOUR, participation.pausedMillisAt(muchLater))
            assertEquals(ParticipantElapsedTime.Settled(6 * HOUR), participation.studyLength)
        }
    }

    @Test
    fun aPhoneClockAheadOfNetworkTimeShowsNoPauseThatDidNotHappen() {
        // Start was anchored to network time, but this phone's clock runs 40 minutes fast, so every
        // measurement instant is 40 minutes later than network time. Nothing was ever paused.
        val skew = 40 * MINUTE
        val participation = status(
            ExperimentState.RUNNING,
            active = 23 * HOUR + 50 * MINUTE,
            calendar = 23 * HOUR + 50 * MINUTE,
            measuredAt = START + 23 * HOUR + 50 * MINUTE + skew,
        ).toParticipation(120)
        val fiveMinutesLater = START + 23 * HOUR + 55 * MINUTE + skew

        assertEquals(0L, participation.pausedMillisAt(fiveMinutesLater))
        assertEquals(23 * HOUR + 55 * MINUTE, participation.studyLength?.millisAt(fiveMinutesLater))
        // Still day 1: day 2 begins 24 hours of study time after Start, not 40 minutes early.
        assertEquals(1, participation.studyDayAt(fiveMinutesLater))
    }

    @Test
    fun aPhoneClockBehindNetworkTimeDoesNotHideARealPause() {
        val skew = -30 * MINUTE
        val participation = status(
            ExperimentState.PAUSED,
            active = 2 * HOUR,
            calendar = 3 * HOUR,
            measuredAt = START + 3 * HOUR + skew,
        ).toParticipation(120)

        assertEquals(HOUR, participation.pausedMillisAt(START + 3 * HOUR + skew))
    }

    @Test
    fun movingThePhoneClockForwardMidStudyAddsNeitherPausedTimeNorADay() {
        // Three hours in, never paused, the participant sets the clock a day ahead. The re-anchor
        // commit measures the monotonic study time at the new wall time.
        val jump = 24 * HOUR
        val participation = status(
            ExperimentState.RUNNING,
            active = 3 * HOUR,
            calendar = 3 * HOUR,
            measuredAt = START + 3 * HOUR + jump,
        ).toParticipation(120)
        val anHourLater = START + 4 * HOUR + jump

        assertEquals(0L, participation.pausedMillisAt(anHourLater))
        assertEquals(4 * HOUR, participation.activeCollection.millisAt(anHourLater))
        assertEquals(1, participation.studyDayAt(anHourLater))
    }

    @Test
    fun aDeadlineProcessedLateSettlesAtTheSignedDuration() {
        // A 120-hour study; the phone was off from hour 100 to hour 200, and the completion commit
        // landed after the phone came back, past the planned end.
        val participation = status(
            ExperimentState.COMPLETED,
            active = 100 * HOUR,
            calendar = 201 * HOUR,
            measuredAt = START + 201 * HOUR,
            stateEnteredAt = START + 200 * HOUR,
            stateEnteredCalendar = 200 * HOUR,
        ).toParticipation(120)
        val later = START + 300 * HOUR

        assertEquals(ParticipantElapsedTime.Settled(120 * HOUR), participation.studyLength)
        assertEquals(20 * HOUR, participation.pausedMillisAt(later))
    }

    @Test
    fun aStudyPausedPastItsDeadlineStopsGrowingAtTheSignedDuration() {
        val participation = status(
            ExperimentState.PAUSED,
            active = 100 * HOUR,
            calendar = 110 * HOUR,
            measuredAt = START + 110 * HOUR,
        ).toParticipation(120)
        val farPastTheEnd = START + 400 * HOUR

        assertEquals(120 * HOUR, participation.studyLength?.millisAt(farPastTheEnd))
        assertEquals(20 * HOUR, participation.pausedMillisAt(farPastTheEnd))
        assertEquals(5, participation.studyDayAt(farPastTheEnd))
    }

    @Test
    fun anEndedStudyWhoseEndLeftTheRetainedHistoryShowsNoPausedTime() {
        val participation = status(
            ExperimentState.COMPLETED,
            stateEnteredAt = null,
            stateEnteredCalendar = null,
        ).toParticipation(120)

        assertNull(participation.studyLength)
        assertNull(participation.pausedMillisAt(START + HOUR))
    }

    @Test
    fun aStudyThatHasNotStartedHasNoLengthOrDay() {
        val participation = ParticipantRuntimeStatus(state = ExperimentState.READY).toParticipation(120)

        assertFalse(participation.ended)
        assertNull(participation.studyLength)
        assertNull(participation.studyDayAt(START))
        assertEquals(0L, participation.activeCollection.millisAt(START))
    }

    private fun status(
        state: ExperimentState,
        active: Long = 0,
        calendar: Long = 0,
        measuredAt: Long? = START,
        deadlineTrusted: Boolean = true,
        stateEnteredAt: Long? = START,
        stateEnteredCalendar: Long? = 0,
    ) = ParticipantRuntimeStatus(
        state = state,
        participantInstanceId = "00000000-0000-4000-8000-000000000000",
        startedAtUtcMillis = START,
        deadlineUtcMillis = START + 120 * HOUR,
        deadlineUtcTrusted = deadlineTrusted,
        activeRunningElapsedMillis = active,
        calendarElapsedMillis = calendar,
        elapsedMeasuredAtUtcMillis = measuredAt,
        stateEnteredAtUtcMillis = stateEnteredAt,
        stateEnteredCalendarElapsedMillis = stateEnteredCalendar,
    )

    private companion object {
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
