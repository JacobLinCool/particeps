package cool.jacoblin.particeps.collector.networkusage

import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUsageFlushCursorTest {
    @Test
    fun producerOrdinalAdvancesOnlyAfterAcceptedDurableOwnership() {
        val accepted = EmitBatchResult.Accepted(1)
        assertEquals(8L, producerOrdinalAfter(accepted, 7L))
        assertEquals(7L, producerOrdinalAfter(EmitBatchResult.RejectedByAdmissionGate, 7L))
        assertEquals(7L, producerOrdinalAfter(EmitBatchResult.StorageFailure, 7L))
        assertEquals(
            7L,
            producerOrdinalAfter(
                EmitBatchResult.SourceQualityGap(SourceQualityGapReason.RETROSPECTIVE_COVERAGE_GAP),
                7L,
            ),
        )
    }

    @Test
    fun acceptsMissingEqualAndTrailingDurableCursors() {
        assertTrue(isNetworkUsageFlushCursorValid(null, 2_000))
        assertTrue(isNetworkUsageFlushCursorValid("2000", 2_000))
        assertTrue(isNetworkUsageFlushCursorValid("1000", 2_000))
    }

    @Test
    fun rejectsMalformedAndFutureDurableCursors() {
        assertFalse(isNetworkUsageFlushCursorValid("not-a-clock", 2_000))
        assertFalse(isNetworkUsageFlushCursorValid("2001", 2_000))
    }
}
