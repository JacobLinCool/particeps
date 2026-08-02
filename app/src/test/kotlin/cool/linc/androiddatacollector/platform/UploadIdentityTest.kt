package cool.linc.androiddatacollector.platform

import cool.linc.androiddatacollector.core.definition.AppLifecycleConfiguration
import cool.linc.androiddatacollector.core.definition.ExportConfiguration
import cool.linc.androiddatacollector.core.definition.SignerIdentity
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.definition.UploadConfiguration
import cool.linc.androiddatacollector.core.model.StudyMetadata
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadIdentityTest {
    @Test
    fun importsAlwaysMintDistinctInstancesAndHeadersNeverExposeAssignedCode() {
        val first = StudyMetadata.initial("identity-test", "identity-config", "assigned-secret")
        val second = StudyMetadata.initial("identity-test", "identity-config", "assigned-secret")
        assertNotEquals(first.participantInstanceId, second.participantInstanceId)
        assertEquals("assigned-secret", first.assignedParticipantId)

        val headers = uploadHeaders(configuration(), first, 1, 9)
        assertEquals(first.participantInstanceId, headers["X-ADC-Participant-Instance"])
        assertTrue(headers.keys.none { it.contains("Assigned", ignoreCase = true) })
        assertTrue(headers.values.none { it == "assigned-secret" })
    }

    private fun configuration() = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "identity-test",
        configurationId = "identity-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        minimumAppVersion = 1,
        title = "Identity test",
        researcherName = "Researcher",
        researcherContact = "research@example.invalid",
        purpose = "Test upload identity separation.",
        durationHours = 1,
        consentDocumentVersion = "v1",
        consentSummary = "Test consent.",
        assignedParticipantId = "assigned-secret",
        collectors = listOf(AppLifecycleConfiguration(true)),
        surveys = emptyList(),
        interventions = emptyList(),
        maximumLocalBytes = 16_777_216,
        signer = SignerIdentity("test-signer", "x".repeat(32)),
        export = ExportConfiguration("export-key", "x".repeat(32)),
        upload = UploadConfiguration("https://example.invalid/v1", 60, false),
    )
}
