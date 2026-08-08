package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.model.SafetyPauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SafetyPauseMarkerCodecTest {
    @Test
    fun everyClosedReasonRoundTripsWithoutIdentityData() {
        SafetyPauseReason.entries.forEach { reason ->
            val encoded = SafetyPauseMarkerCodec.encode(reason)

            assertEquals(reason, SafetyPauseMarkerCodec.decode(encoded))
            assertEquals(
                "PARTICEPS_SAFETY_PAUSE_V1\n${reason.name}\n",
                encoded.toString(Charsets.US_ASCII),
            )
        }
    }

    @Test
    fun unknownTruncatedAndExtendedMarkersAreRejected() {
        listOf(
            "PARTICEPS_SAFETY_PAUSE_V1\nUNKNOWN_REASON\n",
            "PARTICEPS_SAFETY_PAUSE_V1\nCOLLECTION_HOST_FAILURE",
            "PARTICEPS_SAFETY_PAUSE_V1\nCOLLECTION_HOST_FAILURE\nextra\n",
        ).forEach { marker ->
            val failure = runCatching {
                SafetyPauseMarkerCodec.decode(marker.toByteArray(Charsets.US_ASCII))
            }.exceptionOrNull()

            check(failure is IllegalStateException)
        }
    }
}

class SafetyPauseWorkIdentityTest {
    @Test
    fun workIdentityIsScopedByStudyAndClosedReason() {
        val hostFailureTags = SafetyPauseWorkIdentity.tags(
            "study-a",
            SafetyPauseReason.COLLECTION_HOST_FAILURE,
        )

        assertEquals(
            SafetyPauseReason.COLLECTION_HOST_FAILURE,
            SafetyPauseWorkIdentity.activeReason("study-a", listOf(hostFailureTags)),
        )
        assertNull(SafetyPauseWorkIdentity.activeReason("study-b", listOf(hostFailureTags)))
        assertNotEquals(
            SafetyPauseWorkIdentity.workName("study-a", SafetyPauseReason.COLLECTION_HOST_FAILURE),
            SafetyPauseWorkIdentity.workName("study-a", SafetyPauseReason.REQUIRED_ACCESS_MISSING),
        )
        assertNotEquals(
            SafetyPauseWorkIdentity.workName("study-a", SafetyPauseReason.COLLECTION_HOST_FAILURE),
            SafetyPauseWorkIdentity.workName("study-b", SafetyPauseReason.COLLECTION_HOST_FAILURE),
        )
    }

    @Test
    fun malformedUnknownAndMultipleActiveReasonsAreRejected() {
        val accessTags = SafetyPauseWorkIdentity.tags(
            "study-a",
            SafetyPauseReason.REQUIRED_ACCESS_MISSING,
        )
        val hostTags = SafetyPauseWorkIdentity.tags(
            "study-a",
            SafetyPauseReason.COLLECTION_HOST_FAILURE,
        )
        val unknownTags = accessTags
            .filterNot { it.startsWith("particeps-safety-pause-reason:") }
            .toSet() + "particeps-safety-pause-reason:UNKNOWN"

        assertThrows(IllegalStateException::class.java) {
            SafetyPauseWorkIdentity.activeReason("study-a", listOf(unknownTags))
        }
        assertThrows(IllegalStateException::class.java) {
            SafetyPauseWorkIdentity.activeReason("study-a", listOf(accessTags, hostTags))
        }
        assertThrows(IllegalStateException::class.java) {
            SafetyPauseWorkIdentity.activeReason(
                "study-a",
                listOf(accessTags - SafetyPauseWorkIdentity.COMMON_TAG),
            )
        }
    }
}
