package cool.linc.androiddatacollector.core.runtime

import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AdmissionToken
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorHealth
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.CollectorRegistry
import cool.linc.androiddatacollector.core.collector.CollectorStatus
import cool.linc.androiddatacollector.core.collector.EmitResult
import cool.linc.androiddatacollector.core.collector.EventSink
import cool.linc.androiddatacollector.core.collector.ResearchClocks
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.ExperimentStateMachine
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.StudyStore
import cool.linc.androiddatacollector.core.model.TransitionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RuntimeSnapshot(
    val metadata: StudyMetadata? = null,
    val collectorHealth: Map<String, CollectorHealth> = emptyMap(),
    val incidentCode: String? = null,
)

sealed interface CommandResult {
    data object Success : CommandResult

    data class Failed(val reasonCode: String) : CommandResult
}

class ExperimentRuntime(
    val configuration: StudyConfiguration,
    private val store: StudyStore,
    private val collectorRegistry: CollectorRegistry,
    internal val clocks: ResearchClocks,
    private val scope: CoroutineScope,
    private val availableAccess: () -> Set<AccessKind>,
) : EventSink {
    private val stateMachine = ExperimentStateMachine()
    private val admissionGate = EventAdmissionGate()
    private val commandMutex = Mutex()
    private val metadataMutex = Mutex()
    private val collectorEntries = mutableMapOf<String, CollectorEntry>()
    private val healthJobs = mutableListOf<Job>()
    private var currentMetadata: StudyMetadata? = null

    private val mutableSnapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun initialize(): CommandResult = executeCommand(requireInitialized = false) {
        check(currentMetadata == null) { "Runtime is already initialized" }
        val loaded = store.loadMetadata() ?: StudyMetadata.initial(
            configuration.experimentId,
            configuration.configurationId,
        ).also { initial ->
            store.initialize(initial)
        }
        check(loaded.experimentId == configuration.experimentId) { "Experiment ID mismatch" }
        check(loaded.configurationId == configuration.configurationId) { "Configuration ID mismatch" }
        currentMetadata = loaded
        createCollectors()
        mutableSnapshot.update {
            it.copy(
                metadata = loaded,
                collectorHealth = collectorEntries.mapValues { entry -> entry.value.collector.health.value },
            )
        }
        if (loaded.state == ExperimentState.RUNNING) {
            admissionGate.open()
            activateCollectors()
        }
    }

    suspend fun reviewStudy(): CommandResult = executeCommand {
        transitionTo(
            ExperimentState.CONFIG_VERIFIED,
            TransitionReason.CONFIGURATION_SIGNATURE_VERIFIED,
        )
        transitionTo(
            ExperimentState.CONSENT_PENDING,
            TransitionReason.CONSENT_REVIEW_OPENED,
        )
    }

    suspend fun acceptConsent(): CommandResult = executeCommand {
        transitionTo(ExperimentState.ACCESS_SETUP, TransitionReason.CONSENT_ACCEPTED)
    }

    suspend fun completeAccessSetup(availableAccess: Set<AccessKind>): CommandResult = executeCommand {
        val missingRequired = configuredPlugins()
            .flatMap { (collectorConfiguration, plugin) ->
                plugin.accessRequirements(collectorConfiguration)
            }
            .filter { it.required && it.kind !in availableAccess }
        require(missingRequired.isEmpty()) {
            "Required access is missing: ${missingRequired.map { it.kind }}"
        }
        transitionTo(ExperimentState.READY, TransitionReason.ACCESS_PREFLIGHT_PASSED)
    }

    suspend fun start(): CommandResult = executeCommand {
        transitionTo(ExperimentState.RUNNING, TransitionReason.PARTICIPANT_STARTED)
        admissionGate.open()
        activateCollectors()
    }

    suspend fun pause(): CommandResult = executeCommand {
        drainAndTransition(
            to = ExperimentState.PAUSED,
            reason = TransitionReason.PARTICIPANT_PAUSED,
            stopCollectors = false,
        )
    }

    suspend fun resume(): CommandResult = executeCommand {
        transitionTo(ExperimentState.RUNNING, TransitionReason.PARTICIPANT_RESUMED)
        admissionGate.open()
        activateCollectors()
    }

    suspend fun finishEarly(): CommandResult = executeCommand {
        val state = requireMetadata().state
        if (state == ExperimentState.RUNNING) {
            drainAndTransition(
                to = ExperimentState.COMPLETED,
                reason = TransitionReason.PARTICIPANT_FINISHED_EARLY,
                stopCollectors = true,
            )
        } else {
            transitionTo(ExperimentState.COMPLETED, TransitionReason.PARTICIPANT_FINISHED_EARLY)
            stopCollectors()
        }
    }

    suspend fun completeAfterDuration(): CommandResult = executeCommand {
        val state = requireMetadata().state
        if (state == ExperimentState.RUNNING) {
            drainAndTransition(
                to = ExperimentState.COMPLETED,
                reason = TransitionReason.STUDY_DURATION_ELAPSED,
                stopCollectors = true,
            )
        } else {
            transitionTo(ExperimentState.COMPLETED, TransitionReason.STUDY_DURATION_ELAPSED)
            stopCollectors()
        }
    }

    suspend fun withdraw(): CommandResult = executeCommand {
        when (requireMetadata().state) {
            ExperimentState.RUNNING -> drainAndTransition(
                to = ExperimentState.WITHDRAWN,
                reason = TransitionReason.PARTICIPANT_WITHDREW,
                stopCollectors = true,
            )

            ExperimentState.PAUSED -> {
                transitionTo(ExperimentState.WITHDRAWN, TransitionReason.PARTICIPANT_WITHDREW)
                stopCollectors()
            }

            else -> transitionTo(ExperimentState.WITHDRAWN, TransitionReason.PARTICIPANT_WITHDREW)
        }
    }

    suspend fun metadataForExport(): StudyMetadata = metadataMutex.withLock {
        val metadata = requireMetadata()
        require(metadata.state in EXPORTABLE_STATES) { "Experiment cannot be exported from ${metadata.state}" }
        metadata.copy(
            transitions = metadata.transitions.toList(),
            lastEvents = metadata.lastEvents.toMap(),
        )
    }

    /**
     * Records that an endpoint confirmed receipt through [sequenceInclusive], and returns the
     * metadata as persisted.
     *
     * The watermark only ever moves forward. An upload that finishes after a later one already
     * landed, or after the study reset, must not walk it backwards — nothing below the watermark
     * is guaranteed to still exist once eviction is in play.
     */
    suspend fun confirmUploaded(sequenceInclusive: Long): StudyMetadata = metadataMutex.withLock {
        val metadata = requireMetadata()
        require(sequenceInclusive in 0 until metadata.nextSequenceNumber) {
            "Upload watermark exceeds the durable event count"
        }
        if (sequenceInclusive <= metadata.uploadedThroughSequence) return@withLock metadata
        val updated = metadata.copy(uploadedThroughSequence = sequenceInclusive)
        store.saveMetadata(updated)
        currentMetadata = updated
        publishMetadata(updated)
        updated
    }

    /**
     * Reclaims local space when the study is close to its quota, and returns the metadata as
     * persisted.
     *
     * Full local retention is the norm. This only does anything once usage crosses
     * [EVICT_ABOVE_FRACTION], and then only removes whole segments an endpoint already confirmed,
     * stopping at [EVICT_DOWN_TO_FRACTION]. If nothing qualifies — nothing delivered yet, or the
     * undelivered events sit in the one segment still being appended to — the store fills up and
     * the existing fail-closed transition to `PAUSED` still applies, which is the correct outcome:
     * dropping undelivered research data to make room would be worse than stopping.
     */
    suspend fun reclaimLocalSpace(): StudyMetadata = metadataMutex.withLock {
        val metadata = requireMetadata()
        val usage = store.storageUsage()
        if (usage.fraction <= EVICT_ABOVE_FRACTION) return@withLock metadata
        val updated = store.evictThrough(
            metadata,
            targetBytes = (usage.quotaBytes * EVICT_DOWN_TO_FRACTION).toLong(),
        )
        if (updated === metadata) return@withLock metadata
        currentMetadata = updated
        publishMetadata(updated)
        updated
    }

    /** Releases process-owned collectors without changing the durable participant state. */
    suspend fun shutdown() = commandMutex.withLock {
        admissionGate.forceClose()
        stopCollectors()
        healthJobs.forEach(Job::cancel)
        healthJobs.clear()
    }

    override fun captureToken(): AdmissionToken? = admissionGate.capture()

    override suspend fun emit(
        token: AdmissionToken,
        event: EventDraft,
    ): EmitResult {
        if (!admissionGate.accepts(token, event.observedTime.elapsedRealtimeNanos)) {
            return EmitResult.RejectedByAdmissionGate
        }

        return metadataMutex.withLock {
            if (!admissionGate.accepts(token, event.observedTime.elapsedRealtimeNanos)) {
                return@withLock EmitResult.RejectedByAdmissionGate
            }
            val metadata = requireMetadata()
            val recorded = RecordedEvent(
                sequenceNumber = metadata.nextSequenceNumber,
                collectorId = event.collectorId,
                payloadSchemaVersion = event.payloadSchemaVersion,
                observedTime = event.observedTime,
                payloadType = event.payloadType,
                fields = event.fields.toSortedMap(),
            )
            val updated = metadata.copy(
                eventCount = metadata.eventCount + 1,
                nextSequenceNumber = metadata.nextSequenceNumber + 1,
                lastEvents = metadata.lastEvents + (recorded.collectorId to recorded),
            )
            try {
                store.appendEvent(recorded)
                currentMetadata = updated
                publishMetadata(updated)
                EmitResult.Accepted(recorded.sequenceNumber)
            } catch (failure: Throwable) {
                failure.rethrowIfCancellation()
                admissionGate.forceClose()
                mutableSnapshot.update { it.copy(incidentCode = INCIDENT_STORAGE_WRITE_FAILED) }
                scope.launch { failClosedAfterStorageFailure() }
                EmitResult.StorageFailure
            }
        }
    }

    override suspend fun latestEvent(collectorId: String): RecordedEvent? = metadataMutex.withLock {
        requireMetadata().lastEvents[collectorId]
    }

    private suspend fun executeCommand(
        requireInitialized: Boolean = true,
        command: suspend () -> Unit,
    ): CommandResult = commandMutex.withLock {
        mutableSnapshot.update { it.copy(incidentCode = null) }
        try {
            if (requireInitialized) check(currentMetadata != null) { "Runtime is not initialized" }
            command()
            CommandResult.Success
        } catch (_: IllegalArgumentException) {
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_COMMAND_REJECTED) }
            CommandResult.Failed(INCIDENT_COMMAND_REJECTED)
        } catch (_: IllegalStateException) {
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_COMMAND_REJECTED) }
            CommandResult.Failed(INCIDENT_COMMAND_REJECTED)
        } catch (failure: Throwable) {
            failure.rethrowIfCancellation()
            admissionGate.forceClose()
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_RUNTIME_FAILURE) }
            CommandResult.Failed(INCIDENT_RUNTIME_FAILURE)
        }
    }

    private fun createCollectors() {
        check(collectorEntries.isEmpty()) { "Collectors already exist" }
        configuredPlugins().forEach { (configuration, plugin) ->
            val collector = plugin.create(
                configuration,
                CollectorContext(
                    scope = scope,
                    eventSink = this,
                    clocks = clocks,
                ),
            )
            collectorEntries[plugin.descriptor.id] = CollectorEntry(
                collector = collector,
                configuration = configuration,
                plugin = plugin,
            )
            healthJobs += scope.launch {
                collector.health.collect { health ->
                    updateCollectorHealth(plugin.descriptor.id, health)
                }
            }
        }
    }

    private fun configuredPlugins() = configuration.collectors.map { collectorConfiguration ->
        collectorConfiguration to collectorRegistry.pluginFor(collectorConfiguration)
    }

    private suspend fun activateCollectors() {
        collectorEntries.forEach { (id, entry) ->
            val missingAccess = entry.plugin.accessRequirements(entry.configuration)
                .filter { it.kind !in availableAccess() }
            if (missingAccess.isNotEmpty()) {
                updateCollectorHealth(
                    id,
                    CollectorHealth(CollectorStatus.BLOCKED_ACCESS, "ACCESS_UNAVAILABLE"),
                )
                return@forEach
            }
            try {
                if (entry.hasStarted) {
                    entry.collector.resume()
                } else {
                    entry.collector.start()
                    entry.hasStarted = true
                }
            } catch (failure: Throwable) {
                failure.rethrowIfCancellation()
                updateCollectorHealth(id, CollectorHealth(CollectorStatus.FAILED, "COLLECTOR_START_FAILED"))
            }
        }
    }

    private suspend fun pauseCollectors() {
        collectorEntries.forEach { (id, entry) ->
            if (!entry.hasStarted) return@forEach
            try {
                entry.collector.pause()
            } catch (failure: Throwable) {
                failure.rethrowIfCancellation()
                updateCollectorHealth(id, CollectorHealth(CollectorStatus.FAILED, "COLLECTOR_PAUSE_FAILED"))
            }
        }
    }

    private suspend fun stopCollectors() {
        collectorEntries.forEach { (id, entry) ->
            if (!entry.hasStarted) return@forEach
            try {
                entry.collector.stop()
            } catch (failure: Throwable) {
                failure.rethrowIfCancellation()
                updateCollectorHealth(id, CollectorHealth(CollectorStatus.FAILED, "COLLECTOR_STOP_FAILED"))
            } finally {
                entry.hasStarted = false
            }
        }
    }

    private suspend fun drainAndTransition(
        to: ExperimentState,
        reason: TransitionReason,
        stopCollectors: Boolean,
    ) {
        val boundary = clocks.now()
        val token = admissionGate.beginDrain(boundary.elapsedRealtimeNanos)
        try {
            transitionTo(to, reason, boundary)
        } catch (failure: Throwable) {
            admissionGate.restoreActive(token)
            throw failure
        }
        try {
            if (stopCollectors) stopCollectors() else pauseCollectors()
        } finally {
            admissionGate.close(token)
        }
    }

    private suspend fun transitionTo(
        state: ExperimentState,
        reason: TransitionReason,
        time: cool.linc.androiddatacollector.core.model.ResearchTime = clocks.now(),
    ) {
        metadataMutex.withLock {
            val updated = stateMachine.transition(requireMetadata(), state, reason, time)
            store.saveMetadata(updated)
            currentMetadata = updated
            publishMetadata(updated)
        }
    }

    private suspend fun failClosedAfterStorageFailure() {
        commandMutex.withLock {
            pauseCollectors()
            val metadata = currentMetadata ?: return@withLock
            if (metadata.state != ExperimentState.RUNNING) return@withLock
            try {
                transitionTo(ExperimentState.PAUSED, TransitionReason.STORAGE_FAILURE)
            } catch (failure: Throwable) {
                failure.rethrowIfCancellation()
                mutableSnapshot.update { it.copy(incidentCode = INCIDENT_PAUSE_PERSISTENCE_FAILED) }
            }
        }
    }

    private fun requireMetadata(): StudyMetadata = checkNotNull(currentMetadata) { "Runtime is not initialized" }

    private fun publishMetadata(metadata: StudyMetadata) {
        mutableSnapshot.update { it.copy(metadata = metadata) }
    }

    private fun updateCollectorHealth(
        collectorId: String,
        health: CollectorHealth,
    ) {
        mutableSnapshot.update { snapshot ->
            snapshot.copy(collectorHealth = snapshot.collectorHealth + (collectorId to health))
        }
    }

    private data class CollectorEntry(
        val collector: Collector,
        val configuration: cool.linc.androiddatacollector.core.definition.CollectorConfiguration,
        val plugin: CollectorPlugin,
        var hasStarted: Boolean = false,
    )

    private companion object {
        val EXPORTABLE_STATES = setOf(
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN,
        )
        const val INCIDENT_COMMAND_REJECTED = "COMMAND_REJECTED"
        const val INCIDENT_RUNTIME_FAILURE = "RUNTIME_FAILURE"
        /**
         * Reclaiming starts only under real pressure and stops well short of the quota, so a study
         * that comfortably fits keeps every event on the device for the participant to export.
         */
        const val EVICT_ABOVE_FRACTION = 0.80
        const val EVICT_DOWN_TO_FRACTION = 0.60

        const val INCIDENT_STORAGE_WRITE_FAILED = "STORAGE_WRITE_FAILED"
        const val INCIDENT_PAUSE_PERSISTENCE_FAILED = "PAUSE_PERSISTENCE_FAILED"
    }
}

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
