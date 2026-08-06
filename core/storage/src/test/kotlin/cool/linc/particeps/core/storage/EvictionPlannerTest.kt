package cool.linc.particeps.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionPlannerTest {
    /** Four 100-byte segments holding sequences 1-10, 11-20, 21-30, 31- (still open). */
    private val segments = listOf(
        SegmentSummary(1, 1, 100),
        SegmentSummary(2, 11, 100),
        SegmentSummary(3, 21, 100),
        SegmentSummary(4, 31, 100),
    )

    @Test
    fun reclaimsOldestDeliveredSegmentsUntilTheTargetIsMet() {
        val plan = EvictionPlanner.plan(
            segments = segments,
            uploadedThroughSequence = 30,
            currentBytes = 400,
            targetBytes = 250,
        )

        assertEquals(listOf(1, 2), plan.segmentIndices)
        assertEquals(21L, plan.retainedFromSequence)
        assertEquals(200L, plan.reclaimedBytes)
    }

    @Test
    fun studyUnderItsTargetKeepsEverything() {
        val plan = EvictionPlanner.plan(segments, 30, currentBytes = 200, targetBytes = 250)

        assertTrue(plan.isEmpty)
        assertEquals(1L, plan.retainedFromSequence)
        assertEquals(0L, plan.reclaimedBytes)
    }

    @Test
    fun undeliveredEventsAreNeverReclaimed() {
        // Only sequences up to 15 are confirmed, so segment 2 (11-20) is partly undelivered and
        // everything from it onwards has to stay.
        val plan = EvictionPlanner.plan(segments, uploadedThroughSequence = 15, 400, 0)

        assertEquals(listOf(1), plan.segmentIndices)
        assertEquals(11L, plan.retainedFromSequence)
    }

    @Test
    fun nothingIsReclaimedWhenNoUploadHasBeenConfirmed() {
        val plan = EvictionPlanner.plan(segments, uploadedThroughSequence = 0, 400, 0)

        assertTrue(plan.isEmpty)
        assertEquals(1L, plan.retainedFromSequence)
    }

    @Test
    fun theNewestSegmentIsNeverReclaimed() {
        // Everything is delivered and the target is unreachable, but the open segment stays so a
        // reload always finds at least one event.
        val plan = EvictionPlanner.plan(segments, uploadedThroughSequence = 40, 400, 0)

        assertEquals(listOf(1, 2, 3), plan.segmentIndices)
        assertEquals(31L, plan.retainedFromSequence)
    }



    @Test
    fun aSingleSegmentStoreIsNeverReclaimed() {
        val plan = EvictionPlanner.plan(
            segments = listOf(SegmentSummary(1, 1, 100)),
            uploadedThroughSequence = 99,
            currentBytes = 100,
            targetBytes = 0,
        )

        assertTrue(plan.isEmpty)
        assertEquals(1L, plan.retainedFromSequence)
    }

    @Test
    fun anEmptyStoreReportsTheInitialFloor() {
        val plan = EvictionPlanner.plan(emptyList(), 0, 0, 0)

        assertTrue(plan.isEmpty)
        assertEquals(1L, plan.retainedFromSequence)
    }

    @Test
    fun theFloorAlwaysMatchesTheFirstSurvivingSequence() {
        // The floor the planner reports must equal the first sequence of the oldest surviving
        // segment, because reloading cross-checks the two and refuses to open the store otherwise.
        listOf(0L, 10L, 20L, 30L, 40L).forEach { watermark ->
            val plan = EvictionPlanner.plan(segments, watermark, 400, 0)
            val survivors = segments.filterNot { it.index in plan.segmentIndices }
            assertEquals(
                "watermark=$watermark",
                survivors.first().firstSequence,
                plan.retainedFromSequence,
            )
        }
    }

    @Test
    fun reclaimedSegmentsAreAlwaysAContiguousLeadingRun() {
        // Survivors must stay contiguous; a hole in the middle is indistinguishable from corruption.
        listOf(0L, 15L, 25L, 40L).forEach { watermark ->
            val plan = EvictionPlanner.plan(segments, watermark, 400, 0)
            val expected = segments.map(SegmentSummary::index).take(plan.segmentIndices.size)
            assertEquals("watermark=$watermark", expected, plan.segmentIndices)
        }
    }
}
