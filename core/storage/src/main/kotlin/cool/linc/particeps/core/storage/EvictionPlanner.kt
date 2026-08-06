package cool.linc.particeps.core.storage

/**
 * One event segment as the planner sees it: its index, the sequence of its first frame, and its
 * size on disk. [firstSequence] is readable from the plaintext frame header without the study key.
 */
data class SegmentSummary(
    val index: Int,
    val firstSequence: Long,
    val byteCount: Long,
)

data class EvictionPlan(
    /** Segment indices to unlink, oldest first. */
    val segmentIndices: List<Int>,
    /** The lowest sequence that survives, which becomes the new retained floor. */
    val retainedFromSequence: Long,
    val reclaimedBytes: Long,
) {
    val isEmpty: Boolean get() = segmentIndices.isEmpty()
}

/**
 * Chooses which whole segments can be reclaimed, without touching the filesystem.
 *
 * This is separated from [EncryptedExperimentStore] because the rules are where the risk lives:
 * getting one wrong destroys research data that was never delivered, or leaves the store unable to
 * reload. Keeping it pure means the rules can be tested on the JVM rather than only on a device.
 *
 * A segment qualifies only when all of the following hold:
 *
 *  - **Every event in it was confirmed by an endpoint.** A segment is bounded below by its own first
 *    sequence and above by the next segment's first sequence minus one, so the whole segment is
 *    delivered when the next segment starts at or below `uploadedThroughSequence + 1`.
 *  - **It is not the newest segment.** Its upper bound is unknown while it is still being appended
 *    to, and keeping it guarantees a reload always finds at least one event.
 *
 * There is deliberately no rule about a collector's most recent event. `lastEvents` is persisted in
 * the metadata rather than rebuilt by scanning surviving frames, so reclaiming the segment that
 * happens to hold a polling collector's newest event does not lose the timestamp it resumes from.
 *
 * Segments are taken oldest first and only until usage falls to [targetBytes]; a study under its
 * target keeps everything.
 */
object EvictionPlanner {
    fun plan(
        segments: List<SegmentSummary>,
        uploadedThroughSequence: Long,
        currentBytes: Long,
        targetBytes: Long,
    ): EvictionPlan {
        val ordered = segments.sortedBy(SegmentSummary::index)
        val fallbackFloor = ordered.firstOrNull()?.firstSequence ?: 1L
        if (ordered.size < 2 || currentBytes <= targetBytes) {
            return EvictionPlan(emptyList(), fallbackFloor, 0)
        }

        val chosen = mutableListOf<Int>()
        var reclaimed = 0L
        var floor = fallbackFloor

        // The last segment is excluded outright: it is still being appended to, so its upper bound
        // is not yet known and a reload must always find at least one event.
        for (position in 0 until ordered.lastIndex) {
            if (currentBytes - reclaimed <= targetBytes) break
            val segment = ordered[position]
            val nextFirstSequence = ordered[position + 1].firstSequence
            val lastSequenceInSegment = nextFirstSequence - 1
            if (lastSequenceInSegment > uploadedThroughSequence) break

            chosen += segment.index
            reclaimed += segment.byteCount
            floor = nextFirstSequence
        }

        return EvictionPlan(chosen, floor, reclaimed)
    }
}
