package cool.linc.particeps.platform

import cool.linc.particeps.core.definition.AppLifecycleConfiguration
import cool.linc.particeps.core.definition.ExportConfiguration
import cool.linc.particeps.core.definition.SignerIdentity
import cool.linc.particeps.core.definition.StudyConfiguration
import cool.linc.particeps.core.definition.UploadConfiguration
import cool.linc.particeps.core.export.ExportReceipt
import cool.linc.particeps.core.model.StudyMetadata
import cool.linc.particeps.core.protocol.VerifiedConfiguration
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadIdentityTest {
    @Test
    fun importsMintDistinctInstancesAndUploadHeadersExposeNoParticipantIdentity() {
        val first = StudyMetadata.initial("identity-test", "identity-config", "assigned-secret")
        val second = StudyMetadata.initial("identity-test", "identity-config", "assigned-secret")
        assertNotEquals(first.participantInstanceId, second.participantInstanceId)
        assertEquals("assigned-secret", first.assignedParticipantId)

        val configuration = configuration()
        val verified = VerifiedConfiguration(
            configuration,
            byteArrayOf(1),
            configuration.signer.keyId,
            ByteArray(64),
            "0".repeat(64),
            false,
        )
        val headers = uploadHeaders(
            verified,
            ExportReceipt(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                verified.configurationSha256,
                1,
                9,
                9,
                "1".repeat(64),
                10,
            ),
        )
        assertTrue(headers.keys.none { it.contains("Participant", ignoreCase = true) })
        assertTrue(headers.keys.none { it.contains("Assigned", ignoreCase = true) })
        assertTrue(headers.values.none { it == first.participantInstanceId })
        assertTrue(headers.values.none { it == "assigned-secret" })
    }

    private fun configuration() = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "identity-test",
        configurationId = "identity-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
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
        signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
        export = ExportConfiguration("export-key", RAW_PUBLIC_KEY),
        upload = UploadConfiguration("https://example.invalid/v1", 60, false),
    )

    private companion object {
        const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
