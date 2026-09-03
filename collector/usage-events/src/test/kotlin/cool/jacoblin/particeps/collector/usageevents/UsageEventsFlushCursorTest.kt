package cool.jacoblin.particeps.collector.usageevents

import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventsFlushCursorTest {
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
        assertTrue(isUsageEventsFlushCursorValid(null, 2_000))
        assertTrue(isUsageEventsFlushCursorValid("2000", 2_000))
        assertTrue(isUsageEventsFlushCursorValid("1000", 2_000))
    }

    @Test
    fun rejectsMalformedAndFutureDurableCursors() {
        assertFalse(isUsageEventsFlushCursorValid("not-a-clock", 2_000))
        assertFalse(isUsageEventsFlushCursorValid("2001", 2_000))
    }
}
