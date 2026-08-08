package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.application.SafetyPauseStatus
import cool.jacoblin.particeps.core.application.StudySessionSnapshot
import cool.jacoblin.particeps.core.definition.AppLifecycleConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyPauseWorkerPolicyTest {
    @Test
    fun invalidIdentityRetriesInsteadOfDiscardingFailClosedEvidence() {
        val snapshot = StudySessionSnapshot(initialized = true)

        assertEquals(
            SafetyPauseWorkerDecision.Retry,
            safetyPauseWorkerDecision(null, SafetyPauseReason.REQUIRED_ACCESS_MISSING.name, snapshot),
        )
        assertEquals(
            SafetyPauseWorkerDecision.Retry,
            safetyPauseWorkerDecision("study", "UNKNOWN", snapshot),
        )
    }

    @Test
    fun blockedRecoveryAndTypedMarkerRetainWorkWithoutConfiguration() {
        val recoveryFailure = StudySessionSnapshot(
            initialized = true,
            recoveryBlocked = true,
            incidentCode = "STUDY_IMPORT_FAILED",
        )
        val pendingMarker = StudySessionSnapshot(
            initialized = true,
            safetyPauseStatus = SafetyPauseStatus.Pending(
                SafetyPauseReason.COLLECTION_HOST_FAILURE,
            ),
        )

        assertEquals(
            SafetyPauseWorkerDecision.Retry,
            safetyPauseWorkerDecision(
                "study",
                SafetyPauseReason.COLLECTION_HOST_FAILURE.name,
                recoveryFailure,
            ),
        )
        assertEquals(
            SafetyPauseWorkerDecision.Retry,
            safetyPauseWorkerDecision(
                "study",
                SafetyPauseReason.COLLECTION_HOST_FAILURE.name,
                pendingMarker,
            ),
        )
    }

    @Test
    fun absentOrDifferentStudyCompletesButMatchingStudyAttemptsRetry() {
        val reason = SafetyPauseReason.REQUIRED_ACCESS_MISSING
        assertEquals(
            SafetyPauseWorkerDecision.Complete,
            safetyPauseWorkerDecision("study", reason.name, StudySessionSnapshot(initialized = true)),
        )

        val active = StudySessionSnapshot(
            initialized = true,
            configuration = configuration("study"),
        )
        assertEquals(
            SafetyPauseWorkerDecision.Complete,
            safetyPauseWorkerDecision("other", reason.name, active),
        )
        assertEquals(
            SafetyPauseWorkerDecision.Attempt("study", reason),
            safetyPauseWorkerDecision("study", reason.name, active),
        )
    }

    private fun configuration(experimentId: String): StudyConfiguration = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = experimentId,
        configurationId = "configuration",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
        title = "Study",
        researcherName = "Researcher",
        researcherContact = "researcher@example.invalid",
        purpose = "Exercise the safety-pause worker policy.",
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
        const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
