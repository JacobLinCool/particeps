package cool.jacoblin.particeps.core.definition

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedCollectorProfileContractsTest {
    @Test
    fun everyGeneratedAuthoringDefaultRoundTripsThroughItsTypedProfile() {
        GeneratedCollectorProfileContracts.contracts.forEach { (sourceId, contract) ->
            val profile = GeneratedCollectorProfileCodec.authoringDefault(sourceId)
            val encoded = GeneratedCollectorProfileCodec.encode(profile)

            assertEquals(sourceId, profile.sourceId)
            assertTrue(sourceId, contract.accepts(encoded))
            assertEquals(profile, GeneratedCollectorProfileCodec.decode(sourceId, encoded))
        }
    }

    @Test
    fun retrospectiveProfilesUseSecondGranularityAndRejectLegacyMinuteKeys() {
        val network = JsonParser.parseString(
            """{"poll_interval_seconds":15,"transports":["mobile","wifi"]}""",
        ).asJsonObject
        val usage = JsonParser.parseString("""{"poll_interval_seconds":15}""").asJsonObject

        assertEquals(15L, (GeneratedCollectorProfileCodec.decode("network_usage.v1", network) as NetworkUsageV1ProfileConfiguration).pollIntervalSeconds)
        assertEquals(15L, (GeneratedCollectorProfileCodec.decode("usage_events.v1", usage) as UsageEventsV1ProfileConfiguration).pollIntervalSeconds)

        val legacy = JsonParser.parseString("""{"poll_interval_minutes":1}""").asJsonObject
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedCollectorProfileCodec.decode("usage_events.v1", legacy)
        }
    }

    @Test
    fun exactProfileValidationRejectsUnknownFieldsAndNonCanonicalEnumArrays() {
        val unknown = JsonParser.parseString("""{"unexpected":true}""").asJsonObject
        assertFalse(GeneratedCollectorProfileContracts["app_lifecycle.v1"]!!.accepts(unknown))

        val unsorted = JsonParser.parseString(
            """{"poll_interval_seconds":900,"transports":["wifi","mobile"]}""",
        ).asJsonObject
        assertFalse(GeneratedCollectorProfileContracts["network_usage.v1"]!!.accepts(unsorted))
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedCollectorProfileCodec.decode("network_usage.v1", unsorted)
        }
    }
}
