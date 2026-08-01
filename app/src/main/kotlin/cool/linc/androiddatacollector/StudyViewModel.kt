package cool.linc.androiddatacollector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cool.linc.androiddatacollector.core.application.StudySessionManager
import cool.linc.androiddatacollector.core.application.UploadStatus
import cool.linc.androiddatacollector.core.collector.AccessStatus
import cool.linc.androiddatacollector.core.collector.CollectorHealth
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.export.ExportReceipt
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.runtime.CommandResult
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
    val message: String?
    val busy: Boolean

    data object Initializing : StudyUiState {
        override val message: String? = null
        override val busy: Boolean = true
    }

    data class NoStudy(
        override val message: String?,
        override val busy: Boolean,
    ) : StudyUiState

    data class ActiveStudy(
        val configuration: StudyConfiguration,
        val metadata: StudyMetadata,
        val access: List<AccessStatus>,
        val collectorHealth: Map<String, CollectorHealth>,
        val lastExport: ExportReceipt?,
        val upload: UploadStatus?,
        val signerAnchored: Boolean,
        override val message: String?,
        override val busy: Boolean,
    ) : StudyUiState
}

class StudyViewModel(
    private val session: StudySessionManager,
) : ViewModel() {
    private val localMessage = MutableStateFlow<String?>(null)
    private val operationBusy = MutableStateFlow(false)

    val state: StateFlow<StudyUiState> = combine(
        session.snapshot,
        localMessage,
        operationBusy,
    ) { snapshot, message, operating ->
        val incident = message ?: snapshot.incidentCode ?: snapshot.runtime.incidentCode
        val configuration = snapshot.configuration
        when {
            !snapshot.initialized -> StudyUiState.Initializing
            configuration == null -> StudyUiState.NoStudy(incident, operating)
            else -> StudyUiState.ActiveStudy(
                configuration = configuration,
                metadata = checkNotNull(snapshot.runtime.metadata) { "Active study metadata is unavailable" },
                access = snapshot.access,
                collectorHealth = snapshot.runtime.collectorHealth,
                lastExport = snapshot.lastExport,
                upload = snapshot.upload,
                signerAnchored = snapshot.signerAnchored,
                message = incident,
                busy = operating,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StudyUiState.Initializing)

    fun importSignedConfiguration(load: () -> ByteArray) = operation(INCIDENT_IMPORT_FAILED) {
        val bytes = withContext(Dispatchers.IO) { load() }
        require(bytes.size <= MAXIMUM_CONFIGURATION_ENVELOPE_BYTES) { "Configuration is too large" }
        session.importSignedConfiguration(bytes)
    }

    fun reviewStudy() = command(session::reviewStudy)
    fun acceptConsent() = command(session::acceptConsent)
    fun completeAccessSetup() = command(session::completeAccessSetup)
    fun start() = command(session::start)
    fun pause() = command(session::pause)
    fun resume() = command(session::resume)
    fun finish() = command(session::finish)
    fun withdraw() = command(session::withdraw)

    fun export(openDestination: () -> OutputStream) = operation(INCIDENT_EXPORT_FAILED) {
        val destination = withContext(Dispatchers.IO) { openDestination() }
        session.exportTo(destination)
        localMessage.value = "EXPORT_COMPLETE"
    }

    fun deleteLocalData() = operation(INCIDENT_DELETE_FAILED) {
        session.deleteLocalData()
        localMessage.value = "LOCAL_DATA_DELETED"
    }

    fun refreshAccess() = session.refreshAccess()

    fun reportMessage(code: String) {
        localMessage.value = code
    }

    private fun command(execute: suspend () -> CommandResult) = operation(INCIDENT_COMMAND_FAILED) {
        val result = execute()
        localMessage.value = (result as? CommandResult.Failed)?.reasonCode
    }

    private fun operation(
        failureCode: String,
        execute: suspend () -> Unit,
    ) {
        if (!operationBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            localMessage.value = null
            try {
                execute()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                localMessage.value = failureCode
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

    private companion object {
        const val MAXIMUM_CONFIGURATION_ENVELOPE_BYTES = 1_100_000
        const val INCIDENT_IMPORT_FAILED = "CONFIGURATION_IMPORT_FAILED"
        const val INCIDENT_EXPORT_FAILED = "EXPORT_FAILED"
        const val INCIDENT_DELETE_FAILED = "LOCAL_DATA_DELETE_FAILED"
        const val INCIDENT_COMMAND_FAILED = "COMMAND_FAILED"
    }
}
