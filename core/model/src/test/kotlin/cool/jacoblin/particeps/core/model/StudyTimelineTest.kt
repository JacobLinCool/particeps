package cool.jacoblin.particeps.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyTimelineTest {
    private val timeline = StudyTimeline(durationMillis = 60_000)
    private val start = time("boot-a", monotonicNanos = 1_000_000_000, utcMillis = 10_000)

    @Test
    fun sameBootUsesOnlyMonotonicTimeAndNeverWallClock() {
        val checkpoint = timeline.startedAt(start)
        val observed = time("boot-a", monotonicNanos = 4_500_000_000, utcMillis = 1)

        val advanced = timeline.advance(
            checkpoint,
            ExperimentState.RUNNING,
            observed,
            trustedUtcMillis = null,
        ) as StudyTimelineAdvance.Advanced

        assertFalse(advanced.crossedBoot)
        assertEquals(3_500_000_000, advanced.checkpoint.studyElapsedNanos)
        assertEquals(3_500_000_000, advanced.checkpoint.activeCollectionElapsedNanos)
        assertEquals(observed, advanced.checkpoint.anchor)
    }

    @Test
    fun rebootDowntimeCountsTowardLifetimeButNotActiveCollection() {
        val beforeReboot = (timeline.advance(
            timeline.startedAt(start, trustedUtcMillis = start.wallTimeUtcMillis),
            ExperimentState.RUNNING,
            time("boot-a", 6_000_000_000, 15_000),
            trustedUtcMillis = null,
        ) as StudyTimelineAdvance.Advanced).checkpoint

        val recovered = timeline.advance(
            beforeReboot,
            ExperimentState.RUNNING,
            time("boot-b", 200_000_000, 40_000),
            trustedUtcMillis = 40_000,
        ) as StudyTimelineAdvance.Advanced

        assertTrue(recovered.crossedBoot)
        assertEquals(30_000_000_000, recovered.checkpoint.studyElapsedNanos)
        assertEquals(5_000_000_000, recovered.checkpoint.activeCollectionElapsedNanos)
    }

    @Test
    fun participantPauseDoesNotAdvanceActiveCollectionClock() {
        val paused = timeline.startedAt(start).copy(
            studyElapsedNanos = 10_000_000_000,
            activeCollectionElapsedNanos = 4_000_000_000,
            anchor = time("boot-a", 11_000_000_000, 20_000),
        )

        val advanced = timeline.advance(
            paused,
            ExperimentState.PAUSED,
            time("boot-a", 21_000_000_000, 5_000),
            trustedUtcMillis = null,
        ) as StudyTimelineAdvance.Advanced

        assertEquals(20_000_000_000, advanced.checkpoint.studyElapsedNanos)
        assertEquals(4_000_000_000, advanced.checkpoint.activeCollectionElapsedNanos)
    }

    @Test
    fun rebootWithoutTrustedUtcStaysClosed() {
        assertSame(
            StudyTimelineAdvance.TrustedUtcRequired,
            timeline.advance(
                timeline.startedAt(start),
                ExperimentState.RUNNING,
                time("boot-b", 1, 20_000),
                trustedUtcMillis = null,
            ),
        )
    }

    @Test
    fun rebootCannotTrustADeadlineThatWasCreatedFromUntrustedWallTime() {
        assertSame(
            StudyTimelineAdvance.TrustedUtcRequired,
            timeline.advance(
                timeline.startedAt(start),
                ExperimentState.RUNNING,
                time("boot-b", 1, 20_000),
                trustedUtcMillis = 20_000,
            ),
        )
    }

    @Test
    fun sameBootTrustedReadingEstablishesDeadlineForLaterReboot() {
        val anchored = timeline.advance(
            timeline.startedAt(start),
            ExperimentState.RUNNING,
            time("boot-a", 6_000_000_000, 15_000),
            trustedUtcMillis = 15_000,
        ) as StudyTimelineAdvance.Advanced
        val recovered = timeline.advance(
            anchored.checkpoint,
            ExperimentState.RUNNING,
            time("boot-b", 1, 20_000),
            trustedUtcMillis = 20_000,
        ) as StudyTimelineAdvance.Advanced

        assertTrue(anchored.checkpoint.deadlineUtcTrusted)
        assertTrue(recovered.crossedBoot)
        assertEquals(10_000_000_000, recovered.checkpoint.studyElapsedNanos)
        assertEquals(5_000_000_000, recovered.checkpoint.activeCollectionElapsedNanos)
    }

    @Test
    fun trustedClockRollbackAcrossRepeatedRebootsCannotReduceElapsedTime() {
        val firstRecovery = (timeline.advance(
            timeline.startedAt(start, trustedUtcMillis = start.wallTimeUtcMillis),
            ExperimentState.RUNNING,
            time("boot-b", 1, 50_000),
            trustedUtcMillis = 50_000,
        ) as StudyTimelineAdvance.Advanced).checkpoint
        val secondRecovery = timeline.advance(
            firstRecovery,
            ExperimentState.PAUSED,
            time("boot-c", 1, 30_000),
            trustedUtcMillis = 30_000,
        ) as StudyTimelineAdvance.Advanced

        assertEquals(40_000_000_000, firstRecovery.studyElapsedNanos)
        assertEquals(firstRecovery.studyElapsedNanos, secondRecovery.checkpoint.studyElapsedNanos)
        assertEquals(firstRecovery.activeCollectionElapsedNanos, secondRecovery.checkpoint.activeCollectionElapsedNanos)
    }

    @Test
    fun exactDeadlineIsRejectedWhilePreviousNanosecondIsAdmitted() {
        val checkpoint = timeline.startedAt(start)

        assertTrue(timeline.admits(checkpoint, time("boot-a", 60_999_999_999, 0)))
        assertFalse(timeline.admits(checkpoint, time("boot-a", 61_000_000_000, 0)))
        assertFalse(timeline.admits(checkpoint, time("boot-b", 1, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameBootMonotonicRollbackIsRejected() {
        timeline.advance(
            timeline.startedAt(start),
            ExperimentState.RUNNING,
            time("boot-a", 999_999_999, 10_001),
            trustedUtcMillis = null,
        )
    }

    private fun time(boot: String, monotonicNanos: Long, utcMillis: Long) = ResearchTime(
        wallTimeUtcMillis = utcMillis,
        elapsedRealtimeNanos = monotonicNanos,
        bootSessionId = boot,
    )
}
