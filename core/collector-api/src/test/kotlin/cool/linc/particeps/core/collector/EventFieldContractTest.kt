package cool.linc.particeps.core.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFieldContractTest {
    @Test
    fun int32UsesOneCanonicalSignedDecimalSpellingAndDescriptorBounds() {
        val percentage = EventFieldContract(
            type = EventFieldType.INT32,
            required = true,
            minimum = 0.0,
            maximum = 100.0,
        )

        listOf("0", "1", "100").forEach { assertTrue(it, percentage.accepts(it)) }
        listOf("-1", "-0", "+1", "01", "101").forEach { assertFalse(it, percentage.accepts(it)) }
    }

    @Test
    fun floatsUseOnlyTheProtocolDecimalGrammar() {
        val value = EventFieldContract(EventFieldType.FLOAT64, required = true)

        listOf("0", "-0", "+1", "01", ".5", "1.", "1e-3", "1E+3").forEach {
            assertTrue(it, value.accepts(it))
        }
        listOf("", " ", "0x10", "0b10", "NaN", "Infinity", "1_0").forEach {
            assertFalse(it, value.accepts(it))
        }
    }
}
