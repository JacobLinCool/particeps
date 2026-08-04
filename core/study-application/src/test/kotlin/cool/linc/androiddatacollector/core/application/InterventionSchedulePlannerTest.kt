package cool.linc.androiddatacollector.core.application

import cool.linc.androiddatacollector.core.definition.AppLifecycleConfiguration
import cool.linc.androiddatacollector.core.definition.DailyLocalSchedule
import cool.linc.androiddatacollector.core.definition.ExportConfiguration
import cool.linc.androiddatacollector.core.definition.InterventionConfiguration
import cool.linc.androiddatacollector.core.definition.InterventionSchedule
import cool.linc.androiddatacollector.core.definition.InterventionTrigger
import cool.linc.androiddatacollector.core.definition.IntervalSchedule
import cool.linc.androiddatacollector.core.definition.NotificationAction
import cool.linc.androiddatacollector.core.definition.OneTimeSchedule
import cool.linc.androiddatacollector.core.definition.RelativeClock
import cool.linc.androiddatacollector.core.definition.RandomLocalWindow
import cool.linc.androiddatacollector.core.definition.RandomWindowSchedule
import cool.linc.androiddatacollector.core.definition.SignerIdentity
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.ExperimentTransition
import cool.linc.androiddatacollector.core.model.OccurrenceState
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.TransitionReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionSchedulePlannerTest {
    private val planner = InterventionSchedulePlanner()

    @Test
    fun oneTimeCalendarAndActiveSchedulesDivergeOnlyAcrossPause() {
        val calendar = plan(OneTimeSchedule(60, RelativeClock.CALENDAR_TIME), runningMetadata(), 30)
        val active = plan(OneTimeSchedule(60, RelativeClock.ACTIVE_RUNNING_TIME), runningMetadata(), 30)
        assertEquals(at(60).wallTimeUtcMillis, calendar.scheduledFor.wallTimeUtcMillis)
        assertEquals(at(60).wallTimeUtcMillis, active.scheduledFor.wallTimeUtcMillis)

        val paused = runningMetadata().copy(
            transitions = listOf(
                transition(ExperimentState.READY, ExperimentState.RUNNING, TransitionReason.PARTICIPANT_STARTED, 0),
                transition(ExperimentState.RUNNING, ExperimentState.PAUSED, TransitionReason.PARTICIPANT_PAUSED, 30),
                transition(ExperimentState.PAUSED, ExperimentState.RUNNING, TransitionReason.PARTICIPANT_RESUMED, 90),
            ),
        )
        assertEquals(at(60).wallTimeUtcMillis, plan(OneTimeSchedule(60, RelativeClock.CALENDAR_TIME), paused, 100).scheduledFor.wallTimeUtcMillis)
        assertEquals(at(120).wallTimeUtcMillis, plan(OneTimeSchedule(60, RelativeClock.ACTIVE_RUNNING_TIME), paused, 100).scheduledFor.wallTimeUtcMillis)
    }

    @Test
    fun intervalAdvancesOnlyAfterPriorOccurrenceIsTerminal() {
        val configuration = configuration(IntervalSchedule(10, 30, RelativeClock.CALENDAR_TIME))
        val metadata = runningMetadata()
        val first = planner.next(configuration, metadata, at(5), ZoneId.of("UTC")).single()
        assertEquals(at(10).wallTimeUtcMillis, first.scheduledFor.wallTimeUtcMillis)
        val retry = planner.next(configuration, metadata, at(20), ZoneId.of("UTC")).single()
        assertEquals(first.occurrenceId, retry.occurrenceId)
        assertEquals(first.scheduledFor.wallTimeUtcMillis, retry.scheduledFor.wallTimeUtcMillis)

        val completed = metadata.copy(
            occurrences = mapOf(first.occurrenceId to first.copy(state = OccurrenceState.NOTIFICATION_POSTED)),
        )
        val second = planner.next(configuration, completed, at(20), ZoneId.of("UTC")).single()
        assertEquals(at(40).wallTimeUtcMillis, second.scheduledFor.wallTimeUtcMillis)
        assertNotEquals(first.occurrenceId, second.occurrenceId)
    }

    @Test
    fun dailyOccurrenceFollowsCurrentZoneWithoutChangingItsIdentity() {
        val configuration = configuration(DailyLocalSchedule("08:00"))
        val metadata = runningMetadata()
        val utc = planner.next(configuration, metadata, at(0), ZoneId.of("UTC")).single()
        val dateLine = planner.next(configuration, metadata, at(0), ZoneId.of("Pacific/Kiritimati")).single()

        assertEquals(at(8 * 60).wallTimeUtcMillis, utc.scheduledFor.wallTimeUtcMillis)
        assertEquals(at(18 * 60).wallTimeUtcMillis, dateLine.scheduledFor.wallTimeUtcMillis)
        assertEquals("daily:0", utc.scheduleKey)
        assertEquals(utc.occurrenceId, dateLine.occurrenceId)
    }

    @Test
    fun activeScheduleUsesElapsedTimeAcrossWallClockChange() {
        val metadata = runningMetadata()
        val wallJumpedForward = ResearchTime(
            wallTimeUtcMillis = at(180).wallTimeUtcMillis,
            elapsedRealtimeNanos = at(30).elapsedRealtimeNanos,
            bootSessionId = "boot-one",
        )
        val occurrence = planner.next(
            configuration(OneTimeSchedule(60, RelativeClock.ACTIVE_RUNNING_TIME)),
            metadata,
            wallJumpedForward,
            ZoneId.of("UTC"),
        ).single()

        assertEquals(at(210).wallTimeUtcMillis, occurrence.scheduledFor.wallTimeUtcMillis)
        val calendar = planner.next(
            configuration(OneTimeSchedule(60, RelativeClock.CALENDAR_TIME)),
            metadata,
            wallJumpedForward,
            ZoneId.of("UTC"),
        ).single()
        assertEquals(at(210).wallTimeUtcMillis, calendar.scheduledFor.wallTimeUtcMillis)
    }

    @Test
    fun reconstructionIsIdempotentAndTerminalStudiesHaveNoWork() {
        val configuration = configuration(OneTimeSchedule(15, RelativeClock.CALENDAR_TIME))
        val metadata = runningMetadata()
        val first = planner.next(configuration, metadata, at(1), ZoneId.of("UTC"))
        val afterProcessDeath = planner.next(configuration, metadata, at(2, "new-boot"), ZoneId.of("UTC"))
        assertEquals(first.single().occurrenceId, afterProcessDeath.single().occurrenceId)
        assertEquals(first.single().scheduledFor.wallTimeUtcMillis, afterProcessDeath.single().scheduledFor.wallTimeUtcMillis)

        val completed = metadata.copy(state = ExperimentState.COMPLETED)
        assertTrue(planner.next(configuration, completed, at(2), ZoneId.of("UTC")).isEmpty())
    }

    @Test
    fun activeScheduleWaitsWhilePaused() {
        val metadata = runningMetadata().copy(
            state = ExperimentState.PAUSED,
            transitions = listOf(
                transition(ExperimentState.READY, ExperimentState.RUNNING, TransitionReason.PARTICIPANT_STARTED, 0),
                transition(ExperimentState.RUNNING, ExperimentState.PAUSED, TransitionReason.PARTICIPANT_PAUSED, 30),
            ),
        )
        assertTrue(
            planner.next(
                configuration(OneTimeSchedule(60, RelativeClock.ACTIVE_RUNNING_TIME)),
                metadata,
                at(180),
                ZoneId.of("UTC"),
            ).isEmpty(),
        )
    }

    @Test
    fun randomWindowUsesCsprngChoiceThenReusesTheDurableOccurrenceExactly() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(RandomLocalWindow("09:00", "12:00")),
            occurrencesPerWindow = 2,
            maximumOccurrencesPerDay = 2,
            maximumOccurrencesTotal = 4,
            minimumSeparationMinutes = 30,
        )
        val configuration = configuration(schedule)
        val metadata = runningMetadata()
        val chooseLatest = InterventionSchedulePlanner { bound -> bound - 1 }
        val first = chooseLatest.next(configuration, metadata, at(1), ZoneId.of("UTC")).single()

        assertEquals("random:2026-01-01:0:0", first.scheduleKey)
        assertEquals(at(11 * 60 + 29).wallTimeUtcMillis, first.scheduledFor.wallTimeUtcMillis)

        val persisted = metadata.copy(occurrences = mapOf(first.occurrenceId to first))
        val afterProcessDeath = InterventionSchedulePlanner { 0 }
            .next(configuration, persisted, at(5, "new-boot"), ZoneId.of("Pacific/Kiritimati"))
            .single()
        assertEquals(first, afterProcessDeath)

        val posted = persisted.copy(
            occurrences = mapOf(first.occurrenceId to first.copy(state = OccurrenceState.NOTIFICATION_POSTED)),
        )
        val second = InterventionSchedulePlanner { 0 }
            .next(configuration, posted, at(5), ZoneId.of("UTC"))
            .single()
        assertEquals("random:2026-01-01:0:1", second.scheduleKey)
        assertEquals(at(11 * 60 + 59).wallTimeUtcMillis, second.scheduledFor.wallTimeUtcMillis)
    }

    @Test
    fun randomWindowHonorsTheSignedDailyAndTotalCaps() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(RandomLocalWindow("09:00", "12:00")),
            occurrencesPerWindow = 2,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 1,
            minimumSeparationMinutes = 30,
        )
        val configuration = configuration(schedule)
        val metadata = runningMetadata()
        val first = InterventionSchedulePlanner { 0 }
            .next(configuration, metadata, at(1), ZoneId.of("UTC"))
            .single()
        val completed = metadata.copy(
            occurrences = mapOf(first.occurrenceId to first.copy(state = OccurrenceState.NOTIFICATION_POSTED)),
        )

        assertTrue(
            InterventionSchedulePlanner { 0 }
                .next(configuration, completed, at(10), ZoneId.of("UTC"))
                .isEmpty(),
        )
    }

    @Test
    fun randomWindowDoesNotReopenACompletedEarlierDateAfterClockRollback() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(RandomLocalWindow("09:00", "10:00")),
            occurrencesPerWindow = 1,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 2,
            minimumSeparationMinutes = 30,
        )
        val configuration = configuration(schedule)
        val metadata = runningMetadata()
        val tomorrow = InterventionSchedulePlanner { 0 }
            .next(configuration, metadata, at(13 * 60), ZoneId.of("UTC"))
            .single()
        assertEquals("random:2026-01-02:0:0", tomorrow.scheduleKey)
        val completed = metadata.copy(
            occurrences = mapOf(
                tomorrow.occurrenceId to tomorrow.copy(state = OccurrenceState.NOTIFICATION_POSTED),
            ),
        )

        assertTrue(
            InterventionSchedulePlanner { 0 }
                .next(configuration, completed, at(8 * 60), ZoneId.of("UTC"))
                .isEmpty(),
        )
    }

    @Test
    fun randomWindowUsesTheMonotonicStudyAnchorAfterWallClockRollback() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(RandomLocalWindow("09:00", "10:00")),
            occurrencesPerWindow = 1,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 2,
            minimumSeparationMinutes = 30,
        )
        val start = ResearchTime(
            Instant.parse("2026-01-02T00:00:00Z").toEpochMilli(),
            1_000_000_000,
            "boot-rollback",
        )
        val afterRollback = ResearchTime(
            Instant.parse("2026-01-01T01:00:00Z").toEpochMilli(),
            3_601_000_000_000,
            "boot-rollback",
        )

        val occurrence = InterventionSchedulePlanner { 0 }
            .next(configuration(schedule), runningMetadata(start), afterRollback, ZoneId.of("UTC"))
            .single()

        assertEquals("random:2026-01-01:0:0", occurrence.scheduleKey)
        assertEquals(
            Instant.parse("2026-01-01T09:00:00Z").toEpochMilli(),
            occurrence.scheduledFor.wallTimeUtcMillis,
        )
    }

    @Test
    fun randomWindowUsesChronologyNotLocalDateOrderingAfterCrossingTheDateLine() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(RandomLocalWindow("09:00", "10:00")),
            occurrencesPerWindow = 1,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 2,
            minimumSeparationMinutes = 30,
        )
        val configuration = configuration(schedule)
        val metadata = runningMetadata()
        val first = InterventionSchedulePlanner { 0 }
            .next(configuration, metadata, at(13 * 60), ZoneId.of("Pacific/Kiritimati"))
            .single()
        assertEquals("random:2026-01-02:0:0", first.scheduleKey)
        assertEquals(at(19 * 60).wallTimeUtcMillis, first.scheduledFor.wallTimeUtcMillis)
        val completed = metadata.copy(
            occurrences = mapOf(first.occurrenceId to first.copy(state = OccurrenceState.NOTIFICATION_POSTED)),
        )

        val chronologicallyLater = InterventionSchedulePlanner { 0 }
            .next(configuration, completed, at(20 * 60), ZoneId.of("Etc/GMT+12"))
            .single()

        assertEquals("random:2026-01-01:0:0", chronologicallyLater.scheduleKey)
        assertEquals(at(21 * 60).wallTimeUtcMillis, chronologicallyLater.scheduledFor.wallTimeUtcMillis)
        assertTrue(
            chronologicallyLater.scheduledFor.wallTimeUtcMillis > first.scheduledFor.wallTimeUtcMillis,
        )
    }

    @Test
    fun localMinuteResolutionSkipsDstGapsAndChoosesTheFirstOverlapOccurrence() {
        val newYork = ZoneId.of("America/New_York")

        assertNull(localMinuteInstant(LocalDate.of(2026, 3, 8), 2 * 60 + 30, newYork))
        assertEquals(
            Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),
            localMinuteInstant(LocalDate.of(2026, 11, 1), 1 * 60 + 30, newYork),
        )
    }

    @Test
    fun randomDailyCapCountsOnlyMaterializedOccurrencesSoEveningRemainsEligible() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(
                RandomLocalWindow("09:00", "10:00"),
                RandomLocalWindow("18:00", "19:00"),
            ),
            occurrencesPerWindow = 1,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 1,
            minimumSeparationMinutes = 30,
        )

        val occurrence = InterventionSchedulePlanner { 0 }
            .next(configuration(schedule), runningMetadata(), at(12 * 60), ZoneId.of("UTC"))
            .single()

        assertEquals("random:2026-01-01:1:0", occurrence.scheduleKey)
        assertEquals(at(18 * 60).wallTimeUtcMillis, occurrence.scheduledFor.wallTimeUtcMillis)
    }

    @Test
    fun randomDailyCapTruncatesInSignedWindowOrderBeforeMinuteRandomization() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(
                RandomLocalWindow("09:00", "10:00"),
                RandomLocalWindow("18:00", "19:00"),
            ),
            occurrencesPerWindow = 1,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 2,
            minimumSeparationMinutes = 30,
        )

        val occurrence = InterventionSchedulePlanner { bound -> bound - 1 }
            .next(configuration(schedule), runningMetadata(), at(1), ZoneId.of("UTC"))
            .single()

        assertEquals("random:2026-01-01:0:0", occurrence.scheduleKey)
        assertEquals(at(9 * 60 + 59).wallTimeUtcMillis, occurrence.scheduledFor.wallTimeUtcMillis)
    }

    @Test
    fun dstGapWindowDoesNotConsumeTheDailyCapBeforeAValidWindow() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(
                RandomLocalWindow("02:00", "03:00"),
                RandomLocalWindow("04:00", "05:00"),
            ),
            occurrencesPerWindow = 1,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 1,
            minimumSeparationMinutes = 30,
        )
        val start = researchTime(Instant.parse("2026-03-08T05:00:00Z"))

        val occurrence = InterventionSchedulePlanner { 0 }
            .next(configuration(schedule), runningMetadata(start), start, ZoneId.of("America/New_York"))
            .single()

        assertEquals("random:2026-03-08:1:0", occurrence.scheduleKey)
        assertEquals(
            Instant.parse("2026-03-08T08:00:00Z").toEpochMilli(),
            occurrence.scheduledFor.wallTimeUtcMillis,
        )
    }

    @Test
    fun onePromptCapsCanChooseAcrossTheEntireSignedWindow() {
        val schedule = RandomWindowSchedule(
            localWindows = listOf(RandomLocalWindow("08:00", "12:00")),
            occurrencesPerWindow = 8,
            maximumOccurrencesPerDay = 1,
            maximumOccurrencesTotal = 1,
            minimumSeparationMinutes = 30,
        )

        val occurrence = InterventionSchedulePlanner { bound -> bound - 1 }
            .next(configuration(schedule), runningMetadata(), at(1), ZoneId.of("UTC"))
            .single()

        assertEquals(at(11 * 60 + 59).wallTimeUtcMillis, occurrence.scheduledFor.wallTimeUtcMillis)
    }

    @Test
    fun dailyLocalAlsoSkipsDstGapInsteadOfShiftingOutsideTheSignedTime() {
        val zone = ZoneId.of("America/New_York")
        val start = researchTime(Instant.parse("2026-03-07T05:00:00Z"))
        val metadata = runningMetadata(start)
        val configuration = configuration(DailyLocalSchedule("02:30"), durationHours = 72)
        val first = planner.next(configuration, metadata, start, zone).single()
        assertEquals(Instant.parse("2026-03-07T07:30:00Z").toEpochMilli(), first.scheduledFor.wallTimeUtcMillis)
        val completed = metadata.copy(
            occurrences = mapOf(first.occurrenceId to first.copy(state = OccurrenceState.NOTIFICATION_POSTED)),
        )

        val next = planner.next(
            configuration,
            completed,
            researchTime(Instant.parse("2026-03-07T08:00:00Z")),
            zone,
        ).single()

        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), next.scheduledFor.wallTimeUtcMillis)
    }

    private fun plan(schedule: InterventionSchedule, metadata: StudyMetadata, nowMinutes: Long) =
        planner.next(configuration(schedule), metadata, at(nowMinutes), ZoneId.of("UTC")).single()

    private fun runningMetadata(start: ResearchTime = at(0)) = StudyMetadata.initial("schedule-test", "schedule-config").copy(
        state = ExperimentState.RUNNING,
        transitions = listOf(
            ExperimentTransition(
                ExperimentState.READY,
                ExperimentState.RUNNING,
                TransitionReason.PARTICIPANT_STARTED,
                start,
            ),
        ),
    )

    private fun transition(
        from: ExperimentState,
        to: ExperimentState,
        reason: TransitionReason,
        minutes: Long,
    ) = ExperimentTransition(from, to, reason, at(minutes))

    private fun configuration(schedule: InterventionSchedule, durationHours: Int = 48) = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "schedule-test",
        configurationId = "schedule-config",
        issuedAt = Instant.parse("2025-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
        title = "Schedule test",
        researcherName = "Researcher",
        researcherContact = "research@example.invalid",
        purpose = "Test deterministic intervention scheduling.",
        durationHours = durationHours,
        consentDocumentVersion = "v1",
        consentSummary = "Test consent.",
        assignedParticipantId = null,
        collectors = listOf(AppLifecycleConfiguration(true)),
        surveys = emptyList(),
        interventions = listOf(
            InterventionConfiguration(
                "test-notice",
                NotificationAction("Study notice", "A study notice is ready."),
                listOf(InterventionTrigger("test-trigger", schedule, 180)),
            ),
        ),
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
        export = ExportConfiguration("export-key", RAW_PUBLIC_KEY),
        upload = null,
    )

    private fun at(minutes: Long, boot: String = "boot-one") = ResearchTime(
        wallTimeUtcMillis = BASE_UTC_MILLIS + minutes * 60_000,
        elapsedRealtimeNanos = 1_000_000_000 + minutes * 60_000_000_000,
        bootSessionId = boot,
    )

    private fun researchTime(instant: Instant) = ResearchTime(
        wallTimeUtcMillis = instant.toEpochMilli(),
        elapsedRealtimeNanos = 1_000_000_000,
        bootSessionId = "boot-dst",
    )

    private companion object {
        val BASE_UTC_MILLIS: Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
