package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.definition.AppLifecycleConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentTransition
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyLifetimeTest {
    @Test
    fun sameBootUsesMonotonicTimeEvenWhenWallClockMovesBackwards() {
        val start = ResearchTime(10_000, 1_000_000_000, "boot-one")

        val lifetime = studyLifetime(
            configuration(),
            startedMetadata(start),
            ResearchTime(1_000, 1_600_000_000, "boot-one"),
        )

        assertEquals(600, lifetime.elapsedMillis)
        assertEquals(3_599_400, lifetime.remainingMillis)
    }

    @Test
    fun rebootFailsClosedEvenWhenWallTimeStillFollowsTheParticipantStart() {
        val start = ResearchTime(10_000, 9_000_000_000, "boot-one")

        assertThrows(IllegalArgumentException::class.java) {
            studyLifetime(
                configuration(),
                startedMetadata(start),
                ResearchTime(610_000, 100, "boot-two"),
            )
        }
    }

    @Test
    fun elapsedStudyHasZeroRemainingInsteadOfAResetDuration() {
        val start = ResearchTime(10_000, 1_000, "boot-one")

        val lifetime = studyLifetime(
            configuration(),
            startedMetadata(start),
            ResearchTime(3_610_000, 3_610_001_000_000, "boot-one"),
        )

        assertTrue(lifetime.elapsed)
        assertEquals(0, lifetime.remainingMillis)
    }

    @Test
    fun duplicateParticipantStartTransitionsAreRejected() {
        val start = ResearchTime(10_000, 1_000, "boot-one")
        val metadata = startedMetadata(start).let { original ->
            original.copy(transitions = original.transitions + original.transitions.single())
        }

        assertThrows(IllegalStateException::class.java) {
            studyLifetime(configuration(), metadata, ResearchTime(11_000, 2_000, "boot-one"))
        }
    }

    private fun startedMetadata(start: ResearchTime): StudyMetadata = StudyMetadata.initial(
        EXPERIMENT_ID,
        CONFIGURATION_ID,
    ).copy(
        state = ExperimentState.RUNNING,
        transitions = listOf(
            ExperimentTransition(
                from = ExperimentState.READY,
                to = ExperimentState.RUNNING,
                reason = TransitionReason.PARTICIPANT_STARTED,
                time = start,
            ),
        ),
    )

    private fun configuration() = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = EXPERIMENT_ID,
        configurationId = CONFIGURATION_ID,
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
        title = "Lifetime test",
        researcherName = "Researcher",
        researcherContact = "researcher@example.invalid",
        purpose = "Test the durable study lifetime.",
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
        upload = null,
    )

    private companion object {
        const val EXPERIMENT_ID = "lifetime-study"
        const val CONFIGURATION_ID = "lifetime-config"
        const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
