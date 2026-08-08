package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason

/** The durable, absolute study lifetime derived from the participant's one explicit start. */
data class StudyLifetime(
    val participantStartedAt: ResearchTime,
    val elapsedMillis: Long,
    val remainingMillis: Long,
) {
    val elapsed: Boolean get() = remainingMillis == 0L
}

/**
 * Derives the signed study duration from durable transition history without resetting it on resume.
 *
 * Android's monotonic clock is authoritative only while both observations belong to one boot.
 * Wall time cannot safely bridge a reboot: it may have moved backwards without crossing the
 * participant-start wall timestamp, which would extend the signed duration. Callers therefore
 * fail closed after a reboot instead of manufacturing a later deadline from an untrusted clock.
 */
fun studyLifetime(
    configuration: StudyConfiguration,
    metadata: StudyMetadata,
    observedAt: ResearchTime,
): StudyLifetime {
    require(metadata.experimentId == configuration.experimentId) { "Experiment ID mismatch" }
    require(metadata.configurationId == configuration.configurationId) { "Configuration ID mismatch" }
    require(metadata.state in STARTED_STUDY_STATES) { "Study has not started" }
    val start = participantStartedAt(metadata)
    require(start.bootSessionId == observedAt.bootSessionId) {
        "Study duration cannot be proven across boot sessions"
    }
    require(observedAt.elapsedRealtimeNanos >= start.elapsedRealtimeNanos) {
        "Monotonic study clock moved backwards"
    }
    val elapsedMillis =
        (observedAt.elapsedRealtimeNanos - start.elapsedRealtimeNanos) / NANOS_PER_MILLISECOND
    val durationMillis = configuration.durationHours.toLong() * MILLIS_PER_HOUR
    return StudyLifetime(
        participantStartedAt = start,
        elapsedMillis = elapsedMillis,
        remainingMillis = (durationMillis - elapsedMillis).coerceAtLeast(0L),
    )
}

/** Returns the one immutable participant-start boundary after validating its state transition. */
fun participantStartedAt(metadata: StudyMetadata): ResearchTime {
    val start = metadata.transitions.singleOrNull { it.reason == TransitionReason.PARTICIPANT_STARTED }
        ?: error("Started study must contain exactly one participant-start transition")
    require(start.from == ExperimentState.READY && start.to == ExperimentState.RUNNING) {
        "Participant-start transition has an invalid boundary"
    }
    return start.time
}

private val STARTED_STUDY_STATES = setOf(
    ExperimentState.RUNNING,
    ExperimentState.PAUSED,
    ExperimentState.COMPLETED,
    ExperimentState.WITHDRAWN,
)
private const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
