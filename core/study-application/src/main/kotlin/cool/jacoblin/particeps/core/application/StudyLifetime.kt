package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyTimeline
import cool.jacoblin.particeps.core.model.StudyTimelineAdvance
import cool.jacoblin.particeps.core.model.TrustedStudyTimeUnavailable
import cool.jacoblin.particeps.core.model.TransitionReason

/** The durable, absolute study lifetime derived from the participant's one explicit start. */
data class StudyLifetime(
    val participantStartedAt: ResearchTime,
    val elapsedMillis: Long,
    val remainingMillis: Long,
    val checkpoint: StudyClockCheckpoint,
) {
    val elapsed: Boolean get() = remainingMillis == 0L
}

/**
 * Derives the signed study duration from durable transition history without resetting it on resume.
 *
 * Same-boot advancement is monotonic. Cross-boot advancement is accepted only with [trustedUtcMillis]
 * and is derived from the durable absolute deadline, so a clock rollback can never extend duration.
 */
fun studyLifetime(
    configuration: StudyConfiguration,
    metadata: StudyMetadata,
    observedAt: ResearchTime,
    trustedUtcMillis: Long? = null,
): StudyLifetime {
    require(metadata.experimentId == configuration.experimentId) { "Experiment ID mismatch" }
    require(metadata.configurationId == configuration.configurationId) { "Configuration ID mismatch" }
    require(metadata.state in STARTED_STUDY_STATES) { "Study has not started" }
    val start = participantStartedAt(metadata)
    val durationMillis = configuration.durationHours.toLong() * MILLIS_PER_HOUR
    val timeline = StudyTimeline(durationMillis)
    val checkpoint = requireNotNull(metadata.clockCheckpoint) {
        "Started study is missing its v2 clock checkpoint"
    }
    val advanced = when (val result = timeline.advance(checkpoint, metadata.state, observedAt, trustedUtcMillis)) {
        is StudyTimelineAdvance.Advanced -> result.checkpoint
        StudyTimelineAdvance.TrustedUtcRequired -> throw TrustedStudyTimeUnavailable()
    }
    return StudyLifetime(
        participantStartedAt = start,
        elapsedMillis = advanced.studyElapsedNanos / NANOS_PER_MILLISECOND,
        remainingMillis = timeline.remainingMillis(advanced),
        checkpoint = advanced,
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
