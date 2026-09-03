package cool.jacoblin.particeps.core.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFieldContractTest {
    @Test
    fun int32UsesOneCanonicalSignedDecimalSpellingAndDescriptorBounds() {
        val percentage = requireNotNull(
            ProtocolEventSourceRegistry.event("battery_state.v1", 1, "BATTERY_STATE"),
        ).fields.getValue("percentage")

        listOf("0", "1", "100").forEach { assertTrue(it, percentage.decode(it) != null) }
        listOf("-1", "-0", "+1", "01", "101").forEach { assertFalse(it, percentage.decode(it) != null) }
    }

    @Test
    fun floatsUseOnlyTheProtocolDecimalGrammar() {
        val value = requireNotNull(
            ProtocolEventSourceRegistry.event("gyroscope.v1", 1, "GYROSCOPE_SAMPLE"),
        ).fields.getValue("x_radians_per_second")

        listOf("0", "-0", "+1", "01", ".5", "1.", "1e-3", "1E+3").forEach {
            assertTrue(it, value.decode(it) != null)
        }
        listOf("", " ", "0x10", "0b10", "NaN", "Infinity", "1_0").forEach {
            assertFalse(it, value.decode(it) != null)
        }
    }

    @Test
    fun embeddedJsonMayBeNonCanonicalButRejectsDuplicateObjectMembers() {
        val answers = requireNotNull(
            ProtocolEventSourceRegistry.event("interventions.v1", 1, "SURVEY_SUBMITTED"),
        ).fields.getValue("answers_json")

        listOf(
            " { \"answer\": 1.0, \"nested\": {\"accepted\": true} } ",
            "[1, 2, null, false]",
        ).forEach { assertTrue(it, answers.decode(it) != null) }
        listOf(
            "{\"answer\":1,\"answer\":2}",
            "{\"answer\":1,\"\\u0061nswer\":2}",
            "{\"nested\":{\"key\":1,\"key\":2}}",
            "\uFEFF{\"answer\":1}",
        ).forEach { assertFalse(it, answers.decode(it) != null) }
    }
}
