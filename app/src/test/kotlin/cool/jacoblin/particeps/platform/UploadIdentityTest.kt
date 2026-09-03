package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.application.StudyUploadPlan
import cool.jacoblin.particeps.core.export.ExportReceipt
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadIdentityTest {
    @Test
    fun commitHeadersExposeNoParticipantOrAutomationIdentity() {
        val headers = uploadHeaders(
            StudyUploadPlan(
                experimentId = "identity-test",
                configurationSha256 = "0".repeat(64),
                researcherKeyId = "export-key",
                endpoint = "https://example.invalid/v1",
                intervalMinutes = 60,
                allowMetered = false,
            ),
            ExportReceipt(
                bundleId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                configurationSha256 = "0".repeat(64),
                firstCommitSequence = 1,
                lastCommitSequence = 9,
                commitCount = 9,
                eventCount = 17,
                sha256 = "1".repeat(64),
                byteCount = 10,
            ),
        )

        assertTrue(headers.keys.none { it.contains("Participant", ignoreCase = true) })
        assertTrue(headers.keys.none { it.contains("Assigned", ignoreCase = true) })
        assertTrue(headers.keys.none { it.contains("Automation", ignoreCase = true) })
        assertTrue(headers.keys.none { it.contains("Epoch", ignoreCase = true) })
        assertFalse(headers.containsKey("X-Particeps-Sequence-From"))
        assertTrue(headers["X-Particeps-Commit-From"] == "1")
        assertTrue(headers["X-Particeps-Commit-To"] == "9")
    }
}
