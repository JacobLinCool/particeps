package cool.jacoblin.particeps.core.model

sealed interface StudyTimelineAdvance {
    data class Advanced(
        val checkpoint: StudyClockCheckpoint,
        val crossedBoot: Boolean,
    ) : StudyTimelineAdvance

    data object TrustedUtcRequired : StudyTimelineAdvance
}

class TrustedStudyTimeUnavailable : IllegalStateException(
    "A trusted UTC reading is required to advance the study across a reboot",
)

/** Pure monotonic/calendar study clocks. No wall-clock value bridges boots without trusted UTC. */
class StudyTimeline(durationMillis: Long) {
    init {
        require(durationMillis > 0) { "Study duration must be positive" }
    }

    val durationMillis: Long = durationMillis
    private val durationNanos = Math.multiplyExact(durationMillis, NANOS_PER_MILLISECOND)

    fun startedAt(time: ResearchTime, trustedUtcMillis: Long?, zoneId: String): StudyClockCheckpoint =
        StudyClockCheckpoint(
            calendarElapsedNanos = 0,
            activeRunningElapsedNanos = 0,
            anchor = time,
            deadlineUtcMillis = Math.addExact(trustedUtcMillis ?: time.wallTimeUtcMillis, durationMillis),
            deadlineUtcTrusted = trustedUtcMillis != null,
            zoneId = zoneId,
        )

    fun advance(
        checkpoint: StudyClockCheckpoint,
        stateAtAnchor: ExperimentState,
        observedAt: ResearchTime,
        trustedUtcMillis: Long?,
    ): StudyTimelineAdvance {
        require(stateAtAnchor in STARTED_STATES) { "Study timeline cannot advance before Start" }
        if (checkpoint.anchor.bootSessionId == observedAt.bootSessionId) {
            require(observedAt.elapsedRealtimeNanos >= checkpoint.anchor.elapsedRealtimeNanos) {
                "Monotonic study clock moved backwards"
            }
            val delta = observedAt.elapsedRealtimeNanos - checkpoint.anchor.elapsedRealtimeNanos
            var advanced = checkpoint.copy(
                calendarElapsedNanos = saturatingAdd(checkpoint.calendarElapsedNanos, delta),
                activeRunningElapsedNanos = if (stateAtAnchor == ExperimentState.RUNNING) {
                    saturatingAdd(checkpoint.activeRunningElapsedNanos, delta)
                } else {
                    checkpoint.activeRunningElapsedNanos
                },
                anchor = observedAt,
            )
            if (!advanced.deadlineUtcTrusted && trustedUtcMillis != null) {
                advanced = advanced.copy(
                    deadlineUtcMillis = addSaturated(trustedUtcMillis, remainingMillis(advanced)),
                    deadlineUtcTrusted = true,
                )
            }
            return StudyTimelineAdvance.Advanced(advanced, crossedBoot = false)
        }

        val trustedNow = trustedUtcMillis ?: return StudyTimelineAdvance.TrustedUtcRequired
        if (!checkpoint.deadlineUtcTrusted) return StudyTimelineAdvance.TrustedUtcRequired
        require(trustedNow >= 0) { "Trusted UTC must be non-negative" }
        val elapsedByDeadline = multiplySaturated(
            (durationMillis - (checkpoint.deadlineUtcMillis - trustedNow)).coerceIn(0, durationMillis),
            NANOS_PER_MILLISECOND,
        )
        return StudyTimelineAdvance.Advanced(
            checkpoint.copy(
                calendarElapsedNanos = maxOf(checkpoint.calendarElapsedNanos, elapsedByDeadline),
                activeRunningElapsedNanos = checkpoint.activeRunningElapsedNanos,
                anchor = observedAt,
            ),
            crossedBoot = true,
        )
    }

    fun remainingNanos(checkpoint: StudyClockCheckpoint): Long =
        (durationNanos - checkpoint.calendarElapsedNanos).coerceAtLeast(0)

    fun remainingMillis(checkpoint: StudyClockCheckpoint): Long =
        (remainingNanos(checkpoint) + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND

    fun isElapsed(checkpoint: StudyClockCheckpoint): Boolean = remainingNanos(checkpoint) == 0L

    /** Exact exclusive lifetime boundary in the checkpoint anchor's monotonic boot domain. */
    fun sameBootDeadline(checkpoint: StudyClockCheckpoint): ResearchTime = ResearchTime(
        wallTimeUtcMillis = checkpoint.deadlineUtcMillis,
        elapsedRealtimeNanos = addSaturated(
            checkpoint.anchor.elapsedRealtimeNanos,
            remainingNanos(checkpoint),
        ),
        bootSessionId = checkpoint.anchor.bootSessionId,
    )

    fun admits(checkpoint: StudyClockCheckpoint, observedAt: ResearchTime): Boolean {
        if (checkpoint.anchor.bootSessionId != observedAt.bootSessionId) return false
        if (observedAt.elapsedRealtimeNanos < checkpoint.anchor.elapsedRealtimeNanos) return false
        val deadline = addSaturated(checkpoint.anchor.elapsedRealtimeNanos, remainingNanos(checkpoint))
        return observedAt.elapsedRealtimeNanos < deadline
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun multiplySaturated(left: Long, right: Long): Long =
        if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right

    private fun addSaturated(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val STARTED_STATES = setOf(
            ExperimentState.ACTIVATING,
            ExperimentState.RUNNING,
            ExperimentState.PAUSING,
            ExperimentState.PAUSED,
            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN,
        )
    }
}
