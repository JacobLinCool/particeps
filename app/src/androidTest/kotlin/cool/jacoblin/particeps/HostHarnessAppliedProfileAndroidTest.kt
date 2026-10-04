package cool.jacoblin.particeps

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pins the shell JSON wire format against Android's real JSONObject implementation. */
@RunWith(AndroidJUnit4::class)
class HostHarnessAppliedProfileAndroidTest {
    @Test
    fun unsignedGenerationIsAnExactIntegerTokenAndNotAQuotedOrRoundedNumber() {
        val state = HostHarnessAppliedProfile(
            status = "VERIFIED",
            state = "RUNNING",
            admissionOpen = true,
            revision = 7,
            activeRunningElapsedMillis = 31_234,
            conditionEpochId = "123e4567-e89b-42d3-a456-426614174000",
            appliedResourceVectorSha256 = "a".repeat(64),
            profileId = "cap-512",
            appliedProfileSha256 = "b".repeat(64),
            resourceGeneration = BigInteger("18446744073709551615"),
        )
        val encoded = state.toJson()

        // Android's parser itself converts integers outside Long to Double; inspect the actual
        // wire token, which the Python host reads as an exact arbitrary-precision integer.
        assertTrue(encoded, Regex("\"resource_generation\":18446744073709551615(?=[,}])").containsMatchIn(encoded))
        val parsed = JSONObject(encoded)
        assertEquals(1, parsed.getInt("schema_version"))
        assertEquals("VERIFIED", parsed.getString("status"))
        assertTrue(parsed.getBoolean("admission_open"))
        assertEquals(7L, parsed.getLong("revision"))
        assertEquals(31_234L, parsed.getLong("active_running_elapsed_millis"))
    }

    @Test
    fun unavailableProfileUsesExplicitNullsInsteadOfOmittingProofFields() {
        val parsed = JSONObject(HostHarnessAppliedProfile.noStudy().toJson())

        assertEquals("NO_STUDY", parsed.getString("status"))
        assertFalse(parsed.getBoolean("admission_open"))
        for (key in listOf(
            "profile_id", "applied_profile_sha256", "resource_generation",
            "condition_epoch_id", "applied_resource_vector_sha256",
        )) {
            assertTrue("Missing $key", parsed.has(key))
            assertTrue("Expected null $key", parsed.isNull(key))
        }
    }
}
