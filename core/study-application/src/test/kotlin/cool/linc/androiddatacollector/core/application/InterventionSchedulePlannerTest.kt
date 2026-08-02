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
import cool.linc.androiddatacollector.core.definition.SignerIdentity
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.ExperimentTransition
import cool.linc.androiddatacollector.core.model.OccurrenceState
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.TransitionReason
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun plan(schedule: InterventionSchedule, metadata: StudyMetadata, nowMinutes: Long) =
        planner.next(configuration(schedule), metadata, at(nowMinutes), ZoneId.of("UTC")).single()

    private fun runningMetadata() = StudyMetadata.initial("schedule-test", "schedule-config").copy(
        state = ExperimentState.RUNNING,
        transitions = listOf(
            transition(ExperimentState.READY, ExperimentState.RUNNING, TransitionReason.PARTICIPANT_STARTED, 0),
        ),
    )

    private fun transition(
        from: ExperimentState,
        to: ExperimentState,
        reason: TransitionReason,
        minutes: Long,
    ) = ExperimentTransition(from, to, reason, at(minutes))

    private fun configuration(schedule: InterventionSchedule) = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "schedule-test",
        configurationId = "schedule-config",
        issuedAt = Instant.parse("2025-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        minimumAppVersion = 1,
        title = "Schedule test",
        researcherName = "Researcher",
        researcherContact = "research@example.invalid",
        purpose = "Test deterministic intervention scheduling.",
        durationHours = 48,
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
        signer = SignerIdentity("test-signer", "x".repeat(32)),
        export = ExportConfiguration("export-key", "x".repeat(32)),
        upload = null,
    )

    private fun at(minutes: Long, boot: String = "boot-one") = ResearchTime(
        wallTimeUtcMillis = BASE_UTC_MILLIS + minutes * 60_000,
        elapsedRealtimeNanos = 1_000_000_000 + minutes * 60_000_000_000,
        bootSessionId = boot,
    )

    private companion object {
        val BASE_UTC_MILLIS: Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    }
}
