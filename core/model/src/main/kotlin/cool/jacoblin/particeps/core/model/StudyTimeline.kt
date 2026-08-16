package cool.jacoblin.particeps.core.model

/** Outcome of advancing a durable study clock to one observed boundary. */
sealed interface StudyTimelineAdvance {
    data class Advanced(
        val checkpoint: StudyClockCheckpoint,
        val crossedBoot: Boolean,
    ) : StudyTimelineAdvance

    /** A reboot was observed but no authenticated/network or auto-time-backed UTC was available. */
    data object TrustedUtcRequired : StudyTimelineAdvance
}

class TrustedStudyTimeUnavailable : IllegalStateException(
    "A trusted UTC reading is required to advance the study across a reboot",
)

/**
 * One source of truth for lifetime, event admission, deadline work and intervention clocks.
 *
 * The caller supplies a trusted UTC reading only when Android can prove its source is acceptable.
 * Wall time inside [ResearchTime] is descriptive and is never used to bridge boot sessions.
 */
class StudyTimeline(durationMillis: Long) {
    init {
        require(durationMillis > 0) { "Study duration must be positive" }
    }

    val durationMillis: Long = durationMillis
    private val durationNanos: Long = Math.multiplyExact(durationMillis, NANOS_PER_MILLISECOND)

    fun startedAt(
        time: ResearchTime,
        trustedUtcMillis: Long? = null,
    ): StudyClockCheckpoint = StudyClockCheckpoint(
        studyElapsedNanos = 0,
        activeCollectionElapsedNanos = 0,
        anchor = time,
        deadlineUtcMillis = Math.addExact(trustedUtcMillis ?: time.wallTimeUtcMillis, durationMillis),
        deadlineUtcTrusted = trustedUtcMillis != null,
    )

    /** One-time migration from the exact current metadata layout into the v2 clocks. */
    fun migrateCurrentV1(
        metadata: StudyMetadata,
        observedAt: ResearchTime,
        trustedUtcMillis: Long?,
    ): StudyTimelineAdvance {
        require(metadata.clockCheckpoint == null) { "Metadata already has a v2 clock checkpoint" }
        val start = metadata.transitions.singleOrNull { it.reason == TransitionReason.PARTICIPANT_STARTED }
            ?: error("Started v1 metadata must contain exactly one participant-start transition")
        require(start.from == ExperimentState.READY && start.to == ExperimentState.RUNNING) {
            "Participant-start transition has an invalid boundary"
        }
        val migratedActive = migratedActiveMillis(metadata, observedAt)
        val initial = startedAt(start.time)
        val advanced = advance(initial, metadata.state, observedAt, trustedUtcMillis)
        return when (advanced) {
            is StudyTimelineAdvance.Advanced -> advanced.copy(
                checkpoint = advanced.checkpoint.copy(
                    activeCollectionElapsedNanos = minOf(
                        migratedActive,
                        advanced.checkpoint.studyElapsedNanos,
                    ),
                ),
            )
            StudyTimelineAdvance.TrustedUtcRequired -> advanced
        }
    }

    /** Conservative v1 baseline retained when trusted UTC is not yet available after a reboot. */
    fun currentV1Baseline(metadata: StudyMetadata): StudyClockCheckpoint {
        require(metadata.clockCheckpoint == null) { "Metadata already has a v2 clock checkpoint" }
        val start = metadata.transitions.singleOrNull { it.reason == TransitionReason.PARTICIPANT_STARTED }
            ?: error("Started v1 metadata must contain exactly one participant-start transition")
        val lastSameBoot = buildList {
            add(start.time)
            addAll(metadata.transitions.map(ExperimentTransition::time))
            addAll(metadata.lastEvents.values.map(RecordedEvent::observedTime))
            addAll(metadata.occurrences.values.mapNotNull(InterventionOccurrence::openedAt))
            addAll(metadata.occurrences.values.mapNotNull(InterventionOccurrence::submittedAt))
        }.filter { it.bootSessionId == start.time.bootSessionId }
            .maxBy(ResearchTime::elapsedRealtimeNanos)
        val elapsed = lastSameBoot.elapsedRealtimeNanos - start.time.elapsedRealtimeNanos
        return startedAt(start.time).copy(
            studyElapsedNanos = elapsed,
            activeCollectionElapsedNanos = minOf(
                migratedActiveMillis(metadata, lastSameBoot),
                elapsed,
            ),
            anchor = lastSameBoot,
        )
    }

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
            val deltaNanos = observedAt.elapsedRealtimeNanos - checkpoint.anchor.elapsedRealtimeNanos
            val advanced = checkpoint.copy(
                studyElapsedNanos = saturatingAdd(checkpoint.studyElapsedNanos, deltaNanos),
                activeCollectionElapsedNanos = if (stateAtAnchor == ExperimentState.RUNNING) {
                    saturatingAdd(checkpoint.activeCollectionElapsedNanos, deltaNanos)
                } else {
                    checkpoint.activeCollectionElapsedNanos
                },
                anchor = observedAt,
            )
            val anchored = if (!advanced.deadlineUtcTrusted && trustedUtcMillis != null) {
                advanced.copy(
                    deadlineUtcMillis = addSaturated(trustedUtcMillis, remainingMillis(advanced)),
                    deadlineUtcTrusted = true,
                )
            } else {
                advanced
            }
            return StudyTimelineAdvance.Advanced(
                anchored,
                crossedBoot = false,
            )
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
                // Never decrease either accumulated clock when UTC moves backwards.
                studyElapsedNanos = maxOf(checkpoint.studyElapsedNanos, elapsedByDeadline),
                // A cross-boot RUNNING gap is not proof that collectors were active.
                activeCollectionElapsedNanos = checkpoint.activeCollectionElapsedNanos,
                anchor = observedAt,
            ),
            crossedBoot = true,
        )
    }

    fun remainingNanos(checkpoint: StudyClockCheckpoint): Long =
        (durationNanos - checkpoint.studyElapsedNanos).coerceAtLeast(0)

    fun remainingMillis(checkpoint: StudyClockCheckpoint): Long =
        (remainingNanos(checkpoint) + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND

    fun isElapsed(checkpoint: StudyClockCheckpoint): Boolean = remainingMillis(checkpoint) == 0L

    /** Exact same-boot event admission against the checkpoint-derived monotonic deadline. */
    fun admits(checkpoint: StudyClockCheckpoint, observedAt: ResearchTime): Boolean {
        if (checkpoint.anchor.bootSessionId != observedAt.bootSessionId) return false
        if (observedAt.elapsedRealtimeNanos < checkpoint.anchor.elapsedRealtimeNanos) return false
        val deadlineNanos = addSaturated(
            checkpoint.anchor.elapsedRealtimeNanos,
            remainingNanos(checkpoint),
        )
        return observedAt.elapsedRealtimeNanos < deadlineNanos
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun multiplySaturated(left: Long, right: Long): Long =
        if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right

    private fun addSaturated(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun migratedActiveMillis(metadata: StudyMetadata, observedAt: ResearchTime): Long {
        var total = 0L
        var opened: ResearchTime? = null
        metadata.transitions.forEach { transition ->
            if (transition.to == ExperimentState.RUNNING) opened = transition.time
            if (transition.from == ExperimentState.RUNNING) {
                val start = checkNotNull(opened) { "RUNNING transition history has no open boundary" }
                if (
                    start.bootSessionId == transition.time.bootSessionId &&
                    transition.time.elapsedRealtimeNanos >= start.elapsedRealtimeNanos
                ) {
                    total = saturatingAdd(
                        total,
                        transition.time.elapsedRealtimeNanos - start.elapsedRealtimeNanos,
                    )
                }
                opened = null
            }
        }
        val activeStart = opened
        if (metadata.state == ExperimentState.RUNNING && activeStart != null) {
            val durableEnd = buildList {
                addAll(metadata.transitions.map(ExperimentTransition::time))
                addAll(metadata.lastEvents.values.map(RecordedEvent::observedTime))
                addAll(metadata.occurrences.values.mapNotNull(InterventionOccurrence::openedAt))
                addAll(metadata.occurrences.values.mapNotNull(InterventionOccurrence::submittedAt))
                if (observedAt.bootSessionId == activeStart.bootSessionId) add(observedAt)
            }.filter { it.bootSessionId == activeStart.bootSessionId }
                .maxByOrNull(ResearchTime::elapsedRealtimeNanos)
            if (durableEnd != null && durableEnd.elapsedRealtimeNanos >= activeStart.elapsedRealtimeNanos) {
                total = saturatingAdd(
                    total,
                    durableEnd.elapsedRealtimeNanos - activeStart.elapsedRealtimeNanos,
                )
            }
        }
        return total
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val STARTED_STATES = setOf(
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN,
        )
    }
}
