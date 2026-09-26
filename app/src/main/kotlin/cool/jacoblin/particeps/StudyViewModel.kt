package cool.jacoblin.particeps

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cool.jacoblin.particeps.core.application.ParticipantRuntimeStatus
import cool.jacoblin.particeps.core.application.StartupStage
import cool.jacoblin.particeps.core.application.StudyAccessStatus
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.application.StudyRecoveryStatus
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.application.StudySessionSnapshot
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.SignedConfigurationCodec
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
        val export: ParticipantExportState,
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
    private val exportController = ParticipantExportController(
        scope = viewModelScope,
        writeExport = { destination, progress -> session.exportTo(destination, progress) },
        reportDiagnostic = { Log.i("ParticepsExport", it) },
    )

    val state: StateFlow<StudyUiState> = participantUiState(
        scope = viewModelScope,
        snapshots = session.snapshot,
        messages = localMessage,
        busy = operationBusy,
        export = exportController.state,
    )

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
        exportController.clearResult()
    }

    fun chooseExportDestination(): Boolean = !operationBusy.value && exportController.chooseDestination()

    fun exportDestinationCancelled() = exportController.destinationCancelled()

    fun exportDestinationUnavailable() = exportController.destinationUnavailable()

    fun export(openDestination: () -> OutputStream, removeIncomplete: () -> Boolean) {
        exportController.start(openDestination, removeIncomplete)
    }

    fun cancelExport() = exportController.cancel()

    /**
     * Read once when the participant opens *Study and my data*, never polled. A figure that cannot
     * be measured, for instance because the study is being deleted, is simply not shown.
     */
    suspend fun localStorageBytes(): Long? = try {
        withContext(Dispatchers.IO) { session.localStorageBytes() }
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        null
    }

    fun deleteLocalData() = removeLocalStudy(ParticipantMessage.LOCAL_DATA_DELETED)

    /**
     * Leaving before Start. There is no started session to withdraw, so the study, its consent
     * record, and its setup progress are removed through the same local deletion as Delete.
     */
    fun declineStudy() {
        val state = session.snapshot.value.runtime.state ?: return
        if (participantExit(state) != ParticipantExit.DECLINE) return
        removeLocalStudy(ParticipantMessage.STUDY_REMOVED)
    }

    fun refreshAccess() {
        // Returning from the camera or document picker must not occupy the import operation slot.
        // Before a study is loaded there are no study permissions to reconcile.
        if (session.snapshot.value.study == null) return
        operation(ParticipantMessage.ACCESS_INSPECTION_FAILED) {
            session.reconcileAccess()
        }
    }

    /**
     * Whether Complete access, Start, and Resume must first pass Android's VPN and local-network
     * prerequisites. This and [collectsWithTrafficShaping] read the session, not [state], because
     * they are asked outside composition, where [state] may not have been collected for a while.
     */
    fun needsTrafficPrerequisites(): Boolean = session.snapshot.value.mayAdjustAppTransferSpeed()

    /** Whether collection is running for a study that may adjust app transfer speed. */
    fun collectsWithTrafficShaping(): Boolean = session.snapshot.value.let {
        it.mayAdjustAppTransferSpeed() && it.runtime.state == ExperimentState.RUNNING
    }

    fun reportMessage(message: ParticipantMessage) {
        localMessage.value = message
    }

    private fun removeLocalStudy(removedMessage: ParticipantMessage) {
        if (exportController.state.value.isActive) return
        operation(ParticipantMessage.DELETE_FAILED) {
            session.deleteLocalData()
            exportController.clearResult()
            localMessage.value = removedMessage
        }
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

/**
 * [StudyUiState] for a screen that is showing it. Projection stops [UI_STATE_STOP_TIMEOUT_MILLIS]
 * after the last collector leaves, so a stopped Activity soon costs nothing per commit, and the
 * next collector restarts it from the current snapshot. An Activity recreated for a configuration
 * change collects again well within the timeout, so recreation does not restart it.
 *
 * With no collector, [StateFlow.value] is the last projection, which can be older than the
 * session. Anything that decides from study state outside composition reads the session instead.
 *
 * The participant model is rebuilt only when the session snapshot changes, not on export progress,
 * busy, or message changes. It stays on the collector's thread: it takes a few microseconds, less
 * than handing it to another thread and back would.
 */
internal fun participantUiState(
    scope: CoroutineScope,
    snapshots: Flow<StudySessionSnapshot>,
    messages: Flow<ParticipantMessage?>,
    busy: Flow<Boolean>,
    export: Flow<ParticipantExportState>,
    project: (StudySessionSnapshot) -> ParticipantStudyUiModel = StudySessionSnapshot::toParticipantUiModel,
): StateFlow<StudyUiState> = combine(
    snapshots.map { snapshot ->
        ProjectedSession(snapshot, snapshot.takeIf { it.initialized && it.study != null }?.let(project))
    },
    messages,
    busy,
    export,
) { projected, message, operating, exportState ->
    val snapshot = projected.snapshot
    val recovery = snapshot.recoveryStatus.toParticipantRecoveryState()
    val visibleMessage = message ?: when (snapshot.recoveryStatus) {
        StudyRecoveryStatus.RECOVERED_PAUSED -> ParticipantMessage.STUDY_PAUSED_FOR_SAFETY
        StudyRecoveryStatus.NONE,
        StudyRecoveryStatus.ACTION_REQUIRED,
        -> null
    }
    val model = projected.model
    when {
        !snapshot.initialized -> StudyUiState.Initializing(snapshot.startupStage)
        model == null -> StudyUiState.NoStudy(visibleMessage, operating, recovery)
        else -> StudyUiState.ActiveStudy(
            model = model,
            export = exportState,
            message = visibleMessage,
            busy = operating,
            recoveryStatus = recovery,
        )
    }
}.stateIn(
    scope,
    SharingStarted.WhileSubscribed(stopTimeoutMillis = UI_STATE_STOP_TIMEOUT_MILLIS),
    StudyUiState.Initializing(null),
)

internal const val UI_STATE_STOP_TIMEOUT_MILLIS = 5_000L

private class ProjectedSession(
    val snapshot: StudySessionSnapshot,
    val model: ParticipantStudyUiModel?,
)

private fun StudySessionSnapshot.mayAdjustAppTransferSpeed(): Boolean =
    initialized && study?.mayAdjustAppTransferSpeed == true

internal fun StudySessionSnapshot.toParticipantUiModel(): ParticipantStudyUiModel {
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
        pausedAtUtcMillis = runtime.stateEnteredAtUtcMillis.takeIf { state == ExperimentState.PAUSED },
        participation = runtime.toParticipation(summary.durationHours),
        lastExport = lastExport?.let { ParticipantExportSummary(it.commitCount, it.eventCount, it.byteCount) },
        trafficShapingDisclosureRequired = summary.mayAdjustAppTransferSpeed,
    )
}

/**
 * Coarse participation facts. Only the study clock and lifecycle state are read; the clock's
 * measurement instant is used to keep the totals live and is not itself carried forward.
 *
 * Every total is monotonic study time, extended live by this phone's wall time since it was
 * measured. None is a difference between the phone's clock and the network-time start or deadline,
 * so a phone whose clock is off, or whose clock is changed mid-study, shows neither pause time that
 * never happened nor a shifted study day. Totals stop at the signed duration, including for a study
 * whose deadline was processed late because the phone was off.
 */
internal fun ParticipantRuntimeStatus.toParticipation(durationHours: Int): ParticipantParticipationSummary {
    val ended = state in ENDED_STATES
    val durationMillis = durationHours * MILLIS_PER_HOUR
    val measuredAt = elapsedMeasuredAtUtcMillis
    val studyLength = when {
        measuredAt == null -> null
        ended -> stateEnteredCalendarElapsedMillis?.let { ParticipantElapsedTime.Settled(it.coerceAtMost(durationMillis)) }
        else -> ParticipantElapsedTime.Growing(measuredAt - calendarElapsedMillis, durationMillis)
    }
    val activeCollection = if (state == ExperimentState.RUNNING && measuredAt != null) {
        ParticipantElapsedTime.Growing(measuredAt - activeRunningElapsedMillis, durationMillis)
    } else {
        ParticipantElapsedTime.Settled(activeRunningElapsedMillis.coerceAtMost(durationMillis))
    }
    return ParticipantParticipationSummary(
        studyDayCount = (durationHours + HOURS_PER_DAY - 1) / HOURS_PER_DAY,
        plannedEndUtcMillis = deadlineUtcMillis.takeIf { deadlineUtcTrusted && !ended },
        studyLength = studyLength,
        activeCollection = activeCollection,
        ended = ended,
    )
}

private val ENDED_STATES = setOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN)
private const val HOURS_PER_DAY = 24
private const val MILLIS_PER_HOUR = 3_600_000L

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
    "screen_state.v1" -> ParticipantDataKind.SCREEN_STATE
    "network_throughput.v1" -> ParticipantDataKind.NETWORK_THROUGHPUT
    "network_state.v1" -> ParticipantDataKind.NETWORK_STATE
    "vpn_state.v1" -> ParticipantDataKind.VPN_STATE
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
        ParticipantDataKind.SCREEN_STATE -> "screen_state.v1"
        ParticipantDataKind.NETWORK_THROUGHPUT -> "network_throughput.v1"
        ParticipantDataKind.NETWORK_STATE -> "network_state.v1"
        ParticipantDataKind.VPN_STATE -> "vpn_state.v1"
        ParticipantDataKind.NETWORK_USAGE -> "network_usage.v1"
        ParticipantDataKind.PROXIMITY -> "proximity.v1"
        ParticipantDataKind.TEMPORAL_CONTEXT -> "temporal_context.v1"
        ParticipantDataKind.USAGE_EVENTS -> "usage_events.v1"
    }
