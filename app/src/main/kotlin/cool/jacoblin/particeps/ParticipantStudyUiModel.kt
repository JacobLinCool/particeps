package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.model.ExperimentState

/**
 * The complete allowlist of study data Compose may observe.
 *
 * Signed automation, package names, resource profiles, traffic caps, condition epochs, runtime
 * digests, owner UIDs, and typed internal failures deliberately have no representation here.
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
    val configurationId: String,
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
    val startedAtUtcMillis: Long?,
    val pausedAtUtcMillis: Long?,
    val endedAtUtcMillis: Long?,
    val lastExport: ParticipantExportSummary?,
    val trafficShapingDisclosureRequired: Boolean,
)

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
    NETWORK_USAGE,
    PROXIMITY,
    TEMPORAL_CONTEXT,
    USAGE_EVENTS,
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
)

enum class ParticipantMessage {
    CONFIGURATION_IMPORT_FAILED,
    JOIN_IMPORT_FAILED,
    EXPORT_FAILED,
    ACCESS_INSPECTION_FAILED,
    OPERATION_FAILED,
    RESET_FAILED,
    DELETE_FAILED,
    EXPORT_COMPLETE,
    LOCAL_DATA_DELETED,
    STUDY_PAUSED_FOR_SAFETY,
}

enum class ParticipantRecoveryState {
    RECOVERING,
    RECOVERED,
    ACTION_REQUIRED,
}
