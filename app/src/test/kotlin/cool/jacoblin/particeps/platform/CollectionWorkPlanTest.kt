package cool.jacoblin.particeps.platform

import androidx.work.ExistingWorkPolicy
import cool.jacoblin.particeps.core.definition.AppLifecycleConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.UploadConfiguration
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentTransition
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionWorkPlanTest {
    @Test
    fun activeRc5DeadlineIsReplacedFromTheImmutableStartInsteadOfKeptOrReset() {
        val configuration = configuration()
        val start = ResearchTime(1_000, 1_000_000_000, "boot-one")

        val plan = collectionWorkPlan(
            configuration,
            startedMetadata(start, ExperimentState.RUNNING),
            ResearchTime(101_000, 101_000_000_000, "boot-one"),
        )

        assertEquals(ExistingWorkPolicy.REPLACE, plan.deadlinePolicy)
        assertEquals(3_500_000L, plan.deadlineDelayMillis)
        assertTrue(plan.scheduleDailyStatus)
        assertTrue(plan.scheduleUpload)
    }

    @Test
    fun resumeAndTimeChangeRepairUseTheOriginalElapsedTime() {
        val configuration = configuration()
        val start = ResearchTime(1_000, 1_000_000_000, "boot-one")
        val paused = startedMetadata(start, ExperimentState.PAUSED)

        val afterResume = collectionWorkPlan(
            configuration,
            paused,
            ResearchTime(10_001_000, 601_000_000_000, "boot-one"),
        )

        assertEquals(3_000_000L, afterResume.deadlineDelayMillis)
        assertEquals(ExistingWorkPolicy.REPLACE, afterResume.deadlinePolicy)
    }

    @Test
    fun rebootRepairFailsClosedEvenWhenWallClockHasNotCrossedTheStart() {
        val configuration = configuration()
        val start = ResearchTime(1_000_000, 900_000_000_000, "boot-one")
        val metadata = startedMetadata(start, ExperimentState.RUNNING)

        assertThrows(IllegalArgumentException::class.java) {
            collectionWorkPlan(
                configuration,
                metadata,
                ResearchTime(1_600_000, 50, "boot-two"),
            )
        }
    }

    @Test
    fun terminalRepairKeepsOnlyTheUploadTail() {
        val configuration = configuration()

        val plan = collectionWorkPlan(
            configuration,
            startedMetadata(
                ResearchTime(1_000, 1_000_000, "boot-one"),
                ExperimentState.COMPLETED,
            ),
            ResearchTime(3_601_000, 1, "boot-two"),
        )

        assertNull(plan.deadlineDelayMillis)
        assertFalse(plan.scheduleDailyStatus)
        assertTrue(plan.scheduleUpload)
    }

    private fun startedMetadata(start: ResearchTime, state: ExperimentState): StudyMetadata {
        val transitions = mutableListOf(
            ExperimentTransition(
                from = ExperimentState.READY,
                to = ExperimentState.RUNNING,
                reason = TransitionReason.PARTICIPANT_STARTED,
                time = start,
            ),
        )
        when (state) {
            ExperimentState.RUNNING -> Unit
            ExperimentState.PAUSED -> transitions += ExperimentTransition(
                from = ExperimentState.RUNNING,
                to = ExperimentState.PAUSED,
                reason = TransitionReason.PARTICIPANT_PAUSED,
                time = start.copy(
                    wallTimeUtcMillis = start.wallTimeUtcMillis + 1,
                    elapsedRealtimeNanos = start.elapsedRealtimeNanos + 1,
                ),
            )
            ExperimentState.COMPLETED -> transitions += ExperimentTransition(
                from = ExperimentState.RUNNING,
                to = ExperimentState.COMPLETED,
                reason = TransitionReason.PARTICIPANT_FINISHED_EARLY,
                time = start.copy(
                    wallTimeUtcMillis = start.wallTimeUtcMillis + 1,
                    elapsedRealtimeNanos = start.elapsedRealtimeNanos + 1,
                ),
            )
            else -> error("Unsupported fixture state: $state")
        }
        return StudyMetadata.initial(EXPERIMENT_ID, CONFIGURATION_ID).copy(
            state = state,
            transitions = transitions,
        )
    }

    private fun configuration() = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = EXPERIMENT_ID,
        configurationId = CONFIGURATION_ID,
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
        title = "Work plan test",
        researcherName = "Researcher",
        researcherContact = "researcher@example.invalid",
        purpose = "Test durable WorkManager planning.",
        durationHours = 1,
        consentDocumentVersion = "v1",
        consentSummary = "Test consent.",
        assignedParticipantId = null,
        collectors = listOf(AppLifecycleConfiguration(required = true)),
        surveys = emptyList(),
        interventions = emptyList(),
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
        export = ExportConfiguration("export-key", RAW_PUBLIC_KEY),
        upload = UploadConfiguration("https://intake.example.invalid/v1", 60, false),
    )

    private companion object {
        const val EXPERIMENT_ID = "work-plan-study"
        const val CONFIGURATION_ID = "work-plan-config"
        const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
