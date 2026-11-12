package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint

/**
 * When each collector recorded data, in hours of study calendar time since Start, and the spans
 * the study was paused. It is a read-only projection of committed data for the participant's own
 * view; the App shows it only when the signed disclosure policy allows it.
 */
data class CollectionTimeline(
    /** Collector source ID to (study hour to event count). Hour 0 is the first hour after Start. */
    val hourlyCounts: Map<String, Map<Int, Long>> = emptyMap(),
    /** Paused spans in study calendar milliseconds; an open span has a null end. */
    val pausedSpans: List<PausedSpan> = emptyList(),
) {
    data class PausedSpan(val startMillis: Long, val endMillis: Long?)
}

/**
 * Folds commits, in order, into a [CollectionTimeline]. The runtime feeds it every commit it writes,
 * and cold-start recovery feeds it the retained log in the same authenticated pass, so it never
 * scans storage on its own. Memory is bounded by sources times study hours.
 */
internal class CollectionTimelineBuilder(private val isCollector: (String) -> Boolean) {
    private val hourly = sortedMapOf<String, java.util.TreeMap<Int, Long>>()
    private val paused = mutableListOf<CollectionTimeline.PausedSpan>()
    private var lastState: ExperimentState? = null
    private var snapshot: CollectionTimeline = CollectionTimeline()
    private var dirty = false

    fun observe(commit: EngineCommit) {
        val clock = commit.successorProjection.clockCheckpoint
        if (clock != null) {
            commit.events.forEach { event ->
                val sourceId = event.type.sourceId.value
                if (!isCollector(sourceId)) return@forEach
                val hour = (calendarNanosAt(event, clock) / NANOS_PER_HOUR).toInt()
                val counts = hourly.getOrPut(sourceId) { java.util.TreeMap() }
                counts[hour] = (counts[hour] ?: 0L) + 1L
                dirty = true
            }
            val state = commit.successorProjection.state
            val wasPaused = lastState == ExperimentState.PAUSED
            val isPaused = state == ExperimentState.PAUSED
            val atMillis = clock.calendarElapsedNanos / NANOS_PER_MILLI
            if (isPaused && !wasPaused) {
                paused += CollectionTimeline.PausedSpan(atMillis, null)
                dirty = true
            } else if (!isPaused && wasPaused) {
                paused.lastOrNull()?.takeIf { it.endMillis == null }?.let {
                    paused[paused.lastIndex] = it.copy(endMillis = maxOf(it.startMillis, atMillis))
                }
                dirty = true
            }
        }
        lastState = commit.successorProjection.state
    }

    fun timeline(): CollectionTimeline {
        if (dirty) {
            snapshot = CollectionTimeline(
                hourlyCounts = hourly.mapValues { (_, counts) -> counts.toMap() },
                pausedSpans = paused.toList(),
            )
            dirty = false
        }
        return snapshot
    }

    /**
     * Places an event on the study calendar: in the commit's boot, by monotonic time back from the
     * commit's clock anchor; otherwise at the commit itself, since another boot's monotonic clock
     * cannot be compared.
     */
    private fun calendarNanosAt(event: RecordedEvent, clock: StudyClockCheckpoint): Long {
        val anchor = clock.anchor
        val observed = event.observedTime
        if (observed.bootSessionId != anchor.bootSessionId) return clock.calendarElapsedNanos
        val before = anchor.elapsedRealtimeNanos - observed.elapsedRealtimeNanos
        return (clock.calendarElapsedNanos - maxOf(0L, before)).coerceAtLeast(0L)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_HOUR = 3_600_000_000_000L
    }
}
