package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.AutomationSchedule
import cool.jacoblin.particeps.core.definition.DurationClock
import cool.jacoblin.particeps.core.definition.LocalTimeWindow
import cool.jacoblin.particeps.core.definition.OccurrenceAutomation
import cool.jacoblin.particeps.core.definition.Trigger
import cool.jacoblin.particeps.core.model.ResearchTime
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerProducerTest {
    @Test
    fun standardOneTimeUsesSignedClockAndStableIdentity() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val schedule = AutomationSchedule.OneTime(60, DurationClock.CALENDAR_TIME)
        val request = request(schedule, start, start + DAY_MILLIS, start)
        val timer = (StandardTimerProducer().produce(request) as TimerProductionResult.Materialized).timer
        assertEquals(start + 60 * 60_000L, (timer.target as TimerTarget.CalendarUtc).utcMillis)
        assertEquals("one-time", timer.producerKey)
        assertEquals(1uL, timer.generation)
        assertEquals(DeterministicIds.timerId(CONFIG_DIGEST, AUTOMATION_ID, "one-time"), timer.id)
    }

    @Test
    fun standardIntervalAdvancesPastMaterializedOrdinal() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val schedule = AutomationSchedule.Interval(0, 30, DurationClock.CALENDAR_TIME)
        val firstSummary = MaterializedTimerSummary("interval:0", start, terminal = true)
        val timer = (
            StandardTimerProducer().produce(
                request(schedule, start, start + DAY_MILLIS, start, materialized = listOf(firstSummary), generation = 1uL),
            ) as TimerProductionResult.Materialized
            ).timer
        assertEquals("interval:1", timer.producerKey)
        assertEquals(start + 30 * 60_000L, timer.logicalDeadlineUtcMillis)
        assertEquals(2uL, timer.generation)
    }

    @Test
    fun committedPendingTimerIsReturnedWithoutRedraw() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val schedule = randomSchedule(listOf(LocalTimeWindow("09:00", "10:00")))
        val pending = DurableTimer(
            DeterministicIds.timerId(CONFIG_DIGEST, AUTOMATION_ID, "random:2026-01-01:0:0"),
            AUTOMATION_ID,
            1uL,
            1,
            "random:2026-01-01:0:0",
            TimerTarget.CalendarUtc(start + 9 * 60 * 60_000L),
            start + 9 * 60 * 60_000L,
            start + 9 * 60 * 60_000L + 900_000L,
        )
        val producer = RandomWindowTimerProducer(BoundedRandomSource { error("must not redraw") })
        val result = producer.produce(
            request(schedule, start, start + DAY_MILLIS, start, pending = pending),
        ) as TimerProductionResult.Materialized
        assertSame(pending, result.timer)
    }

    @Test
    fun randomWindowSelectsFirstAndLastEligibleMinuteWithoutModulo() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val schedule = randomSchedule(listOf(LocalTimeWindow("09:00", "09:10")))
        val first = (RandomWindowTimerProducer(BoundedRandomSource { 0 }).produce(
            request(schedule, start, start + DAY_MILLIS, start + 8 * 60 * 60_000L),
        ) as TimerProductionResult.Materialized).timer
        val last = (RandomWindowTimerProducer(BoundedRandomSource { bound -> bound - 1 }).produce(
            request(schedule, start, start + DAY_MILLIS, start + 8 * 60 * 60_000L),
        ) as TimerProductionResult.Materialized).timer
        assertEquals(Instant.parse("2026-01-01T09:00:00Z").toEpochMilli(), first.logicalDeadlineUtcMillis)
        assertEquals(Instant.parse("2026-01-01T09:09:00Z").toEpochMilli(), last.logicalDeadlineUtcMillis)
    }

    @Test
    fun laterOrdinalReservationPreservesMinimumSeparation() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val schedule = AutomationSchedule.RandomWindow(
            listOf(LocalTimeWindow("09:00", "09:31")),
            occurrencesPerWindow = 2,
            maximumOccurrencesPerDay = 2,
            maximumOccurrencesTotal = 2,
            minimumSeparationMinutes = 30,
        )
        val first = (RandomWindowTimerProducer(BoundedRandomSource { bound -> bound - 1 }).produce(
            request(schedule, start, start + DAY_MILLIS, start),
        ) as TimerProductionResult.Materialized).timer
        assertEquals(Instant.parse("2026-01-01T09:00:00Z").toEpochMilli(), first.logicalDeadlineUtcMillis)

        val firstSummary = MaterializedTimerSummary(first.producerKey, requireNotNull(first.logicalDeadlineUtcMillis), terminal = true)
        val second = (RandomWindowTimerProducer(BoundedRandomSource { 0 }).produce(
            request(schedule, start, start + DAY_MILLIS, start, listOf(firstSummary), generation = 1uL),
        ) as TimerProductionResult.Materialized).timer
        assertEquals(Instant.parse("2026-01-01T09:30:00Z").toEpochMilli(), second.logicalDeadlineUtcMillis)
    }

    @Test
    fun pastWindowConsumesNoCapacityAndLaterWindowRemainsEligible() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val schedule = randomSchedule(
            listOf(LocalTimeWindow("09:00", "10:00"), LocalTimeWindow("14:00", "15:00")),
            daily = 1,
            total = 1,
        )
        val timer = (RandomWindowTimerProducer(BoundedRandomSource { 0 }).produce(
            request(schedule, start, start + DAY_MILLIS, start + 12 * 60 * 60_000L),
        ) as TimerProductionResult.Materialized).timer
        assertEquals("random:2026-01-01:1:0", timer.producerKey)
        assertEquals(Instant.parse("2026-01-01T14:00:00Z").toEpochMilli(), timer.logicalDeadlineUtcMillis)
    }

    @Test
    fun dstGapIsSkippedAndOverlapUsesFirstChronologicalInstant() {
        val gapStart = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        val gapSchedule = randomSchedule(
            listOf(LocalTimeWindow("02:00", "02:10"), LocalTimeWindow("03:00", "03:10")),
            daily = 1,
            total = 1,
        )
        val gapTimer = (RandomWindowTimerProducer(BoundedRandomSource { 0 }).produce(
            request(
                gapSchedule,
                gapStart,
                Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(),
                gapStart,
                zoneId = "America/New_York",
            ),
        ) as TimerProductionResult.Materialized).timer
        assertEquals("random:2026-03-08:1:0", gapTimer.producerKey)
        assertEquals(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), gapTimer.logicalDeadlineUtcMillis)

        val overlapStart = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
        val overlapSchedule = randomSchedule(listOf(LocalTimeWindow("01:30", "01:31")))
        val overlapTimer = (RandomWindowTimerProducer(BoundedRandomSource { 0 }).produce(
            request(
                overlapSchedule,
                overlapStart,
                Instant.parse("2026-11-02T05:00:00Z").toEpochMilli(),
                overlapStart,
                zoneId = "America/New_York",
            ),
        ) as TimerProductionResult.Materialized).timer
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), overlapTimer.logicalDeadlineUtcMillis)
    }

    @Test
    fun producerDoesNothingOutsideRunningOrAfterTotalCap() {
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val schedule = randomSchedule(listOf(LocalTimeWindow("09:00", "10:00")), total = 1)
        val paused = request(schedule, start, start + DAY_MILLIS, start).copy(sessionState = StudySessionState.PAUSED)
        assertTrue(RandomWindowTimerProducer(BoundedRandomSource { 0 }).produce(paused) is TimerProductionResult.Deferred)

        val full = request(
            schedule,
            start,
            start + DAY_MILLIS,
            start,
            materialized = listOf(MaterializedTimerSummary("random:2026-01-01:0:0", start + 9 * 3_600_000L, true)),
            generation = 1uL,
        )
        assertTrue(RandomWindowTimerProducer(BoundedRandomSource { 0 }).produce(full) is TimerProductionResult.Exhausted)
    }

    private fun request(
        schedule: AutomationSchedule,
        start: Long,
        deadline: Long,
        now: Long,
        materialized: List<MaterializedTimerSummary> = emptyList(),
        generation: ULong = 0uL,
        pending: DurableTimer? = null,
        zoneId: String = "UTC",
    ) = TimerProductionRequest(
        CONFIG_DIGEST,
        OccurrenceAutomation(
            AUTOMATION_ID,
            Trigger.Schedule(schedule),
            null,
            "check-in",
            900,
            null,
            512,
        ),
        schedule,
        ReducerClock(
            ResearchTime(now, 0, "boot-1"),
            activeElapsedNanos = 0,
            calendarElapsedNanos = (now - start).coerceAtLeast(0) * 1_000_000L,
            zoneId = zoneId,
        ),
        start,
        deadline,
        1,
        generation,
        StudySessionState.RUNNING,
        pending,
        materialized,
    )

    private fun randomSchedule(
        windows: List<LocalTimeWindow>,
        daily: Int = 1,
        total: Int = 10,
    ) = AutomationSchedule.RandomWindow(
        windows,
        occurrencesPerWindow = 1,
        maximumOccurrencesPerDay = daily,
        maximumOccurrencesTotal = total,
        minimumSeparationMinutes = 1,
    )

    private companion object {
        const val CONFIG_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        const val AUTOMATION_ID = "random-check-in"
        const val DAY_MILLIS = 86_400_000L
    }
}
