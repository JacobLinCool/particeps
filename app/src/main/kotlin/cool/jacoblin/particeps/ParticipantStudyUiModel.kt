package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.model.ExperimentState

/**
 * The complete allowlist of study data Compose may observe.
 *
 * Signed automation, package names, resource profiles, traffic caps, condition epochs, runtime
 * digests, owner UIDs, and typed internal failures deliberately have no representation here. Nor
 * does the configuration ID: it differs between study arms and carries a digest of the whole
 * signed configuration, so showing it would let two participants tell their arms apart.
 */
data class ParticipantStudyUiModel(
    val experimentId: String,
    val title: String,
    val purpose: String,
    val researcherName: String,
    val researcherContact: String,
    val durationHours: Int,
    val consentSummary: String,
    val consentDocumentVersion: String,
    val signerFingerprint: String,
    val signerAnchored: Boolean,
    val assignedParticipantId: String?,
    val participantInstanceId: String,
    val dataCategories: List<ParticipantDataCategory>,
    val access: List<ParticipantAccessItem>,
    val upload: ParticipantUploadDisclosure?,
    val state: ExperimentState,
    val lifetimeDataEventCount: Long,
    val durableThroughCommit: Long,
    val uploadedThroughCommit: Long,
    val retainedFromCommit: Long,
    val pausedAtUtcMillis: Long?,
    val participation: ParticipantParticipationSummary,
    val lastExport: ParticipantExportSummary?,
    val trafficShapingDisclosureRequired: Boolean,
)

/**
 * Participation facts that are the same kind of fact in every study arm: which day of the study it
 * is, when it is planned to end, and how its time so far divides between collecting and paused.
 * Each figure comes from the study clock and the participant's own lifecycle commands; none is
 * derived from automation, resource, or scheduling state.
 */
data class ParticipantParticipationSummary(
    /** How many study days the signed duration spans; a shorter last day still counts as one. */
    val studyDayCount: Int,
    /** Null while the phone's clock cannot yet be trusted to place the end. */
    val plannedEndUtcMillis: Long?,
    /**
     * Time since Start, the same figure the header shows: still growing while the study is under
     * way, settled at the end once it is over, and never longer than the signed duration. Null
     * before Start, and for an ended study whose end is no longer in the retained history.
     */
    val studyLength: ParticipantElapsedTime?,
    /** Time spent collecting. It grows only while the study is collecting. */
    val activeCollection: ParticipantElapsedTime,
    val ended: Boolean,
) {
    /**
     * Day N of [studyDayCount]: day 1 is the first 24 hours after Start, day 2 the next 24 hours,
     * and so on, so it is consistent with the planned end, which falls at the end of the last day.
     * It is not a calendar date and does not turn over at midnight. An ended study has no current
     * day.
     */
    fun studyDayAt(nowUtcMillis: Long): Int? {
        if (ended) return null
        val length = studyLength?.millisAt(nowUtcMillis) ?: return null
        return (length / MILLIS_PER_DAY + 1).coerceAtMost(studyDayCount.toLong()).toInt()
    }

    /** Study length minus collecting time: paused, or settling into or out of collection. */
    fun pausedMillisAt(nowUtcMillis: Long): Long? = studyLength?.let { length ->
        (length.millisAt(nowUtcMillis) - activeCollection.millisAt(nowUtcMillis)).coerceAtLeast(0)
    }

    companion object {
        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L
    }
}

/**
 * A running total that is either settled or growing one-for-one with the wall clock. The study
 * clock only advances when something is recorded, so a total shown live has to be extended here
 * rather than stopping at the last recording.
 */
sealed interface ParticipantElapsedTime {
    fun millisAt(nowUtcMillis: Long): Long

    data class Settled(val millis: Long) : ParticipantElapsedTime {
        override fun millisAt(nowUtcMillis: Long): Long = millis
    }

    /**
     * [zeroAtUtcMillis] is the wall time, on this phone's clock, at which this total would have
     * read zero; it never grows past [limitMillis], the signed study duration.
     */
    data class Growing(val zeroAtUtcMillis: Long, val limitMillis: Long) : ParticipantElapsedTime {
        override fun millisAt(nowUtcMillis: Long): Long = (nowUtcMillis - zeroAtUtcMillis).coerceIn(0, limitMillis)
    }
}

data class ParticipantDataCategory(
    val kind: ParticipantDataKind,
    val optional: Boolean,
)

enum class ParticipantDataKind {
    ACCELEROMETER,
    AMBIENT_LIGHT,
    APP_LIFECYCLE,
    BATTERY_STATE,
    GYROSCOPE,
    KEYBOARD_TOUCH,
    LOCATION,
    NETWORK_STATE,
    NETWORK_THROUGHPUT,
    SCREEN_STATE,
    NETWORK_USAGE,
    PROXIMITY,
    TEMPORAL_CONTEXT,
    USAGE_EVENTS,
    VPN_STATE,
}

data class ParticipantAccessItem(
    val kind: AccessKind,
    val required: Boolean,
    val owners: List<ParticipantAccessOwner>,
    val resolution: ParticipantAccessResolution,
    val guidance: SetupGuidance?,
) {
    val granted: Boolean get() = resolution == ParticipantAccessResolution.Satisfied
}

sealed interface ParticipantAccessOwner {
    val required: Boolean

    data class DataCategory(
        val kind: ParticipantDataKind,
        override val required: Boolean,
    ) : ParticipantAccessOwner

    data object StudyNotifications : ParticipantAccessOwner {
        override val required: Boolean = true
    }
}

sealed interface ParticipantAccessResolution {
    data object Satisfied : ParticipantAccessResolution
    data class ActionRequired(val action: SetupAction) : ParticipantAccessResolution
    data class BlockedByPrerequisites(val missing: List<AccessKind>) : ParticipantAccessResolution
    data object Unavailable : ParticipantAccessResolution
}

data class ParticipantUploadDisclosure(
    val destinationHost: String,
    val intervalMinutes: Int,
    val allowMetered: Boolean,
)

data class ParticipantExportSummary(
    val commitCount: Long,
    val eventCount: Long,
    val byteCount: Long,
)

enum class ParticipantMessage {
    CONFIGURATION_IMPORT_FAILED,
    JOIN_IMPORT_FAILED,
    ACCESS_INSPECTION_FAILED,
    OPERATION_FAILED,
    RESET_FAILED,
    DELETE_FAILED,
    LOCAL_DATA_DELETED,
    STUDY_REMOVED,
    STUDY_PAUSED_FOR_SAFETY,
}

enum class ParticipantRecoveryState {
    RECOVERING,
    RECOVERED,
    ACTION_REQUIRED,
}
