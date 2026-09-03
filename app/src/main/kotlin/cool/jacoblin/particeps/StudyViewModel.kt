package cool.jacoblin.particeps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cool.jacoblin.particeps.core.application.StartupStage
import cool.jacoblin.particeps.core.application.StudyAccessStatus
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.application.StudyRecoveryStatus
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.application.StudySessionSnapshot
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface StudyUiState {
    val message: ParticipantMessage?
    val busy: Boolean
    val recoveryStatus: ParticipantRecoveryState?

    data class Initializing(val stage: StartupStage?) : StudyUiState {
        override val message: ParticipantMessage? = null
        override val busy: Boolean = true
        override val recoveryStatus: ParticipantRecoveryState = ParticipantRecoveryState.RECOVERING
    }

    data class NoStudy(
        override val message: ParticipantMessage?,
        override val busy: Boolean,
        override val recoveryStatus: ParticipantRecoveryState?,
    ) : StudyUiState

    data class ActiveStudy(
        val model: ParticipantStudyUiModel,
        override val message: ParticipantMessage?,
        override val busy: Boolean,
        override val recoveryStatus: ParticipantRecoveryState?,
    ) : StudyUiState
}

/**
 * The only projection from the signed/runtime domain into Compose.
 *
 * Internal failure reasons and all automation/resource state terminate here. Compose receives the
 * closed [ParticipantStudyUiModel] allowlist and generic participant messages only.
 */
class StudyViewModel(
    private val session: StudySessionManager,
) : ViewModel() {
    private val localMessage = MutableStateFlow<ParticipantMessage?>(null)
    private val operationBusy = MutableStateFlow(false)

    val state: StateFlow<StudyUiState> = combine(
        session.snapshot,
        localMessage,
        operationBusy,
    ) { snapshot, message, operating ->
        val recovery = snapshot.recoveryStatus.toParticipantRecoveryState()
        val visibleMessage = message ?: when (snapshot.recoveryStatus) {
            StudyRecoveryStatus.RECOVERED_PAUSED -> ParticipantMessage.STUDY_PAUSED_FOR_SAFETY
            StudyRecoveryStatus.NONE,
            StudyRecoveryStatus.ACTION_REQUIRED,
            -> null
        }
        when {
            !snapshot.initialized -> StudyUiState.Initializing(snapshot.startupStage)
            snapshot.study == null -> StudyUiState.NoStudy(visibleMessage, operating, recovery)
            else -> StudyUiState.ActiveStudy(
                model = snapshot.toParticipantUiModel(),
                message = visibleMessage,
                busy = operating,
                recoveryStatus = recovery,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StudyUiState.Initializing(null))

    fun importSignedConfiguration(load: () -> ByteArray) = operation(
        ParticipantMessage.CONFIGURATION_IMPORT_FAILED,
    ) {
        val bytes = withContext(Dispatchers.IO) { load() }
        require(bytes.size <= SignedConfigurationCodec.MAXIMUM_ENVELOPE_BYTES) {
            "Configuration is too large"
        }
        session.importSignedConfiguration(bytes)
    }

    fun importJoin(link: JoinLink, load: suspend () -> ByteArray) = operation(
        ParticipantMessage.JOIN_IMPORT_FAILED,
    ) {
        session.importSignedConfiguration(load(), link)
    }

    fun reviewStudy() = command(session::reviewStudy)
    fun acceptConsent() = command(session::acceptConsent)
    fun completeAccessSetup() = command(session::completeAccessSetup)
    fun start() = command(session::start)
    fun pause() = command(session::pause)
    fun resume() = command(session::resume)
    fun complete() = command(session::complete)
    fun withdraw() = command(session::withdraw)
    fun safetyPauseForPlatformAccessLoss() = command(session::safetyPauseForPlatformAccessLoss)

    fun retryRecovery() = operation(ParticipantMessage.OPERATION_FAILED) {
        session.retryRecovery()
    }

    fun resetAndRestart() = operation(ParticipantMessage.RESET_FAILED) {
        session.resetAfterRecoveryFailure()
    }

    fun export(openDestination: () -> OutputStream) = operation(ParticipantMessage.EXPORT_FAILED) {
        val destination = withContext(Dispatchers.IO) { openDestination() }
        session.exportTo(destination)
        localMessage.value = ParticipantMessage.EXPORT_COMPLETE
    }

    fun deleteLocalData() = operation(ParticipantMessage.DELETE_FAILED) {
        session.deleteLocalData()
        localMessage.value = ParticipantMessage.LOCAL_DATA_DELETED
    }

    fun refreshAccess() = operation(ParticipantMessage.ACCESS_INSPECTION_FAILED) {
        session.reconcileAccess()
    }

    fun reportMessage(message: ParticipantMessage) {
        localMessage.value = message
    }

    private fun command(execute: suspend () -> StudyCommandResult) = operation(
        ParticipantMessage.OPERATION_FAILED,
    ) {
        localMessage.value = when (execute()) {
            StudyCommandResult.Success -> null
            StudyCommandResult.InvalidState -> ParticipantMessage.OPERATION_FAILED
            StudyCommandResult.InvalidInput -> ParticipantMessage.OPERATION_FAILED
            StudyCommandResult.AccessRequired -> ParticipantMessage.ACCESS_INSPECTION_FAILED
            StudyCommandResult.FailedClosed -> ParticipantMessage.STUDY_PAUSED_FOR_SAFETY
        }
    }

    private fun operation(
        failureMessage: ParticipantMessage,
        execute: suspend () -> Unit,
    ) {
        if (!operationBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            localMessage.value = null
            try {
                execute()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                localMessage.value = failureMessage
            } finally {
                operationBusy.value = false
            }
        }
    }

    class Factory(
        private val session: StudySessionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == StudyViewModel::class.java) { "Unsupported ViewModel class" }
            return StudyViewModel(session) as T
        }
    }
}

private fun StudySessionSnapshot.toParticipantUiModel(): ParticipantStudyUiModel {
    val summary = checkNotNull(study) { "Participant study summary is unavailable" }
    val state = checkNotNull(runtime.state) { "Participant runtime state is unavailable" }
    val categories = summary.dataCategories.map { category ->
        ParticipantDataCategory(
            kind = category.sourceId.toParticipantDataKind(),
            optional = !category.required,
        )
    }
    return ParticipantStudyUiModel(
        experimentId = summary.experimentId,
        title = summary.title,
        purpose = summary.purpose,
        researcherName = summary.researcherName,
        researcherContact = summary.researcherContact,
        durationHours = summary.durationHours,
        consentSummary = summary.consentSummary,
        consentDocumentVersion = summary.consentDocumentVersion,
        configurationId = summary.configurationId,
        signerFingerprint = summary.signerFingerprint,
        signerAnchored = summary.signerAnchored,
        assignedParticipantId = summary.assignedParticipantId,
        participantInstanceId = checkNotNull(runtime.participantInstanceId) {
            "Participant instance ID is unavailable"
        },
        dataCategories = categories,
        access = access.map { it.toParticipantAccess(categories) },
        upload = summary.upload?.let {
            ParticipantUploadDisclosure(it.destinationHost, it.intervalMinutes, it.allowMetered)
        },
        state = state,
        lifetimeDataEventCount = runtime.lifetimeDataEventCount,
        durableThroughCommit = runtime.durableThroughCommit,
        uploadedThroughCommit = runtime.uploadedThroughCommit,
        retainedFromCommit = runtime.retainedFromCommit,
        startedAtUtcMillis = runtime.startedAtUtcMillis,
        pausedAtUtcMillis = runtime.lastObservedAtUtcMillis.takeIf {
            state == cool.jacoblin.particeps.core.model.ExperimentState.PAUSED
        },
        endedAtUtcMillis = runtime.lastObservedAtUtcMillis.takeIf {
            state in setOf(
                cool.jacoblin.particeps.core.model.ExperimentState.COMPLETED,
                cool.jacoblin.particeps.core.model.ExperimentState.WITHDRAWN,
            )
        },
        lastExport = lastExport?.let { ParticipantExportSummary(it.commitCount, it.eventCount) },
        trafficShapingDisclosureRequired = summary.mayAdjustAppTransferSpeed,
    )
}

private fun StudyAccessStatus.toParticipantAccess(
    categories: List<ParticipantDataCategory>,
): ParticipantAccessItem {
    val owners = buildList {
        categories.forEach { category ->
            val sourceId = category.kind.sourceId
            val usesKind = checkNotNull(ProtocolEventSourceRegistry[sourceId]) {
                "Unknown participant data source"
            }.access.any { it.kind == kind.name }
            if (usesKind) add(ParticipantAccessOwner.DataCategory(category.kind, required = !category.optional))
        }
        if (kind == AccessKind.NOTIFICATIONS) add(ParticipantAccessOwner.StudyNotifications)
    }
    return ParticipantAccessItem(
        kind = kind,
        required = required,
        owners = owners,
        resolution = when (val current = resolution) {
            AccessResolution.Satisfied -> ParticipantAccessResolution.Satisfied
            is AccessResolution.ActionRequired -> ParticipantAccessResolution.ActionRequired(current.action)
            is AccessResolution.BlockedByPrerequisites -> ParticipantAccessResolution.BlockedByPrerequisites(
                current.missing.sortedBy { it.ordinal },
            )
            is AccessResolution.Unavailable -> ParticipantAccessResolution.Unavailable
        },
        guidance = guidance,
    )
}

private fun StudyRecoveryStatus.toParticipantRecoveryState(): ParticipantRecoveryState? = when (this) {
    StudyRecoveryStatus.NONE -> null
    StudyRecoveryStatus.RECOVERED_PAUSED -> ParticipantRecoveryState.RECOVERED
    StudyRecoveryStatus.ACTION_REQUIRED -> ParticipantRecoveryState.ACTION_REQUIRED
}

private fun String.toParticipantDataKind(): ParticipantDataKind = when (this) {
    "accelerometer.v1" -> ParticipantDataKind.ACCELEROMETER
    "ambient_light.v1" -> ParticipantDataKind.AMBIENT_LIGHT
    "app_lifecycle.v1" -> ParticipantDataKind.APP_LIFECYCLE
    "battery_state.v1" -> ParticipantDataKind.BATTERY_STATE
    "gyroscope.v1" -> ParticipantDataKind.GYROSCOPE
    "keyboard_touch.v1" -> ParticipantDataKind.KEYBOARD_TOUCH
    "location.v1" -> ParticipantDataKind.LOCATION
    "network_state.v1" -> ParticipantDataKind.NETWORK_STATE
    "network_usage.v1" -> ParticipantDataKind.NETWORK_USAGE
    "proximity.v1" -> ParticipantDataKind.PROXIMITY
    "temporal_context.v1" -> ParticipantDataKind.TEMPORAL_CONTEXT
    "usage_events.v1" -> ParticipantDataKind.USAGE_EVENTS
    else -> error("Unknown participant data source")
}

private val ParticipantDataKind.sourceId: String
    get() = when (this) {
        ParticipantDataKind.ACCELEROMETER -> "accelerometer.v1"
        ParticipantDataKind.AMBIENT_LIGHT -> "ambient_light.v1"
        ParticipantDataKind.APP_LIFECYCLE -> "app_lifecycle.v1"
        ParticipantDataKind.BATTERY_STATE -> "battery_state.v1"
        ParticipantDataKind.GYROSCOPE -> "gyroscope.v1"
        ParticipantDataKind.KEYBOARD_TOUCH -> "keyboard_touch.v1"
        ParticipantDataKind.LOCATION -> "location.v1"
        ParticipantDataKind.NETWORK_STATE -> "network_state.v1"
        ParticipantDataKind.NETWORK_USAGE -> "network_usage.v1"
        ParticipantDataKind.PROXIMITY -> "proximity.v1"
        ParticipantDataKind.TEMPORAL_CONTEXT -> "temporal_context.v1"
        ParticipantDataKind.USAGE_EVENTS -> "usage_events.v1"
    }
