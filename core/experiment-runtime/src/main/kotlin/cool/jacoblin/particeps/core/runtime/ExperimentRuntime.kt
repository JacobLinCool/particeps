package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.EmitResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.ProtocolEventContracts
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.InterventionAction
import cool.jacoblin.particeps.core.definition.MultipleChoiceQuestion
import cool.jacoblin.particeps.core.definition.ScaleQuestion
import cool.jacoblin.particeps.core.definition.ShortTextQuestion
import cool.jacoblin.particeps.core.definition.SingleChoiceQuestion
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.definition.SurveyDefinition
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyTimeline
import cool.jacoblin.particeps.core.model.StudyTimelineAdvance
import cool.jacoblin.particeps.core.model.TrustedStudyTimeUnavailable
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.StudyStoreMutationFailedClosed
import cool.jacoblin.particeps.core.model.TransitionReason
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RuntimeSnapshot(
    val metadata: StudyMetadata? = null,
    val collectorHealth: Map<String, CollectorHealth> = emptyMap(),
    /** Typed request retained until the application confirms a durable safety-pause handoff. */
    val pendingSafetyPauseReason: SafetyPauseReason? = null,
    val incidentCode: String? = null,
)

sealed interface CommandResult {
    data object Success : CommandResult

    data class Failed(val reasonCode: String) : CommandResult
}

data class OccurrenceDispatch(
    val occurrence: InterventionOccurrence,
    val action: InterventionAction,
)

/** Atomic result of claiming one occurrence at its immutable scheduled wall instant. */
sealed interface OccurrenceClaimResult {
    data class Due(val dispatch: OccurrenceDispatch) : OccurrenceClaimResult
    data class NotDue(val remainingDelayMillis: Long) : OccurrenceClaimResult {
        init {
            require(remainingDelayMillis > 0) { "An early delivery must retain a positive delay" }
        }
    }
    data object Expired : OccurrenceClaimResult
    data object Terminal : OccurrenceClaimResult
    data object Missing : OccurrenceClaimResult
    data object InactiveStudy : OccurrenceClaimResult
}

/** Atomic result of checking one durable occurrence against its signed expiry instant. */
sealed interface OccurrenceExpiryResult {
    data object Expired : OccurrenceExpiryResult
    data class NotDue(val remainingDelayMillis: Long) : OccurrenceExpiryResult {
        init {
            require(remainingDelayMillis > 0) { "An early expiry must retain a positive delay" }
        }
    }
    data object Terminal : OccurrenceExpiryResult
    data object Missing : OccurrenceExpiryResult
    data object InactiveStudy : OccurrenceExpiryResult
}

sealed interface SurveyAnswer {
    data class Text(val value: String) : SurveyAnswer
    data class Integer(val value: Int) : SurveyAnswer
    data class Choices(val optionIds: List<String>) : SurveyAnswer
}

enum class SurveySubmissionResult { ACCEPTED, ALREADY_SUBMITTED, EXPIRED, INVALID }

sealed interface TimelineRefreshResult {
    data class Updated(val crossedBoot: Boolean) : TimelineRefreshResult
    data object TrustedUtcRequired : TimelineRefreshResult
}

/** Persists a closed safety reason without acquiring any runtime or application session lock. */
fun interface SafetyPauseWitness {
    suspend fun persist(reason: SafetyPauseReason)
}

class ExperimentRuntime(
    val configuration: StudyConfiguration,
    private val store: StudyStore,
    private val collectorRegistry: CollectorRegistry,
    internal val clocks: ResearchClocks,
    private val scope: CoroutineScope,
    private val safetyPauseWitness: SafetyPauseWitness,
) {
    private val stateMachine = ExperimentStateMachine()
    private val studyTimeline = StudyTimeline(configuration.durationHours.toLong() * MILLIS_PER_HOUR)
    private val admissionGate = EventAdmissionGate()
    private val pendingSafetyPause = AtomicReference<SafetyPauseReason?>(null)
    private val commandMutex = Mutex()
    private val metadataMutex = Mutex()
    private val admittedWriteMutex = Mutex()
    private var admittedWriteCount = 0
    private var admittedWritesDrained = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val collectorEntries = mutableMapOf<String, CollectorEntry>()
    private val healthJobs = mutableListOf<Job>()
    private var currentMetadata: StudyMetadata? = null

    private val mutableSnapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = mutableSnapshot.asStateFlow()

    fun now() = clocks.now()
    fun trustedUtcMillis() = clocks.trustedUtcMillis()

    /** Loads durable state and creates collectors without starting any process resource. */
    suspend fun initialize(
        recoveredSafetyReason: SafetyPauseReason? = null,
    ): CommandResult = executeCommand(requireInitialized = false) {
        check(currentMetadata == null) { "Runtime is already initialized" }
        var loaded = store.loadMetadata() ?: StudyMetadata.initial(
            configuration.experimentId,
            configuration.configurationId,
            configuration.assignedParticipantId,
        ).also { initial ->
            store.initialize(initial)
        }
        store.resolvePendingAppendFailure(
            (recoveredSafetyReason ?: SafetyPauseReason.STORAGE_FAILURE).transitionReason,
        )?.let { resolved -> loaded = resolved }
        check(loaded.experimentId == configuration.experimentId) { "Experiment ID mismatch" }
        check(loaded.configurationId == configuration.configurationId) { "Configuration ID mismatch" }
        check(loaded.assignedParticipantId == configuration.assignedParticipantId) { "Assigned participant ID mismatch" }
        if (loaded.hasStarted() && loaded.clockCheckpoint == null) {
            val observedAt = clocks.now()
            val migrated = when (
                val result = studyTimeline.migrateCurrentV1(
                    loaded,
                    observedAt,
                    clocks.trustedUtcMillis(),
                )
            ) {
                is StudyTimelineAdvance.Advanced -> result.checkpoint
                StudyTimelineAdvance.TrustedUtcRequired -> studyTimeline.currentV1Baseline(loaded)
            }
            loaded = loaded.copy(clockCheckpoint = migrated)
            store.saveMetadata(loaded)
        }
        currentMetadata = loaded
        createCollectors()
        mutableSnapshot.update {
            it.copy(
                metadata = loaded,
                collectorHealth = collectorEntries.mapValues { entry -> entry.value.collector.health.value },
            )
        }
    }

    /**
     * Restores collectors only after the application has confirmed its foreground-service host.
     *
     * Keeping this separate from [initialize] prevents a recovered location collector from
     * acquiring resources before Android has acknowledged the matching foreground-service type.
     */
    suspend fun activateRecoveredRunning(availableAccess: Set<AccessKind>): CommandResult = executeCommand {
        check(requireMetadata().state == ExperimentState.RUNNING) {
            "Only a durably running study can be recovered"
        }
        admissionGate.open()
        activateCollectors(availableAccess)
    }

    /** Persists the reboot boundary before any platform recovery side effect is attempted. */
    suspend fun pauseRecoveredForDeviceReboot(): CommandResult = executeCommand {
        val metadata = requireMetadata()
        check(metadata.state == ExperimentState.RUNNING) {
            "Only a durably running study can enter reboot recovery"
        }
        val boundary = clocks.now()
        check(requireNotNull(metadata.clockCheckpoint).anchor.bootSessionId != boundary.bootSessionId) {
            "Device-reboot pause requires a changed boot session"
        }
        closeAllAdmission()
        transitionTo(ExperimentState.PAUSED, TransitionReason.DEVICE_REBOOT, boundary)
    }

    /** Reopens only the reboot pause after all application/platform validations were acknowledged. */
    suspend fun resumeAutomatically(availableAccess: Set<AccessKind>): CommandResult = executeCommand {
        val metadata = requireMetadata()
        check(
            metadata.state == ExperimentState.PAUSED &&
                metadata.transitions.lastOrNull()?.reason == TransitionReason.DEVICE_REBOOT,
        ) { "Automatic recovery requires the durable device-reboot pause" }
        check(pendingSafetyPause.get() == null) { "A safety pause prevents automatic recovery" }
        transitionTo(ExperimentState.RUNNING, TransitionReason.AUTOMATIC_RECOVERY)
        admissionGate.open()
        activateCollectors(availableAccess)
    }

    /** Advances and persists the one timeline used by deadline, admission and planning. */
    suspend fun refreshTimeline(): TimelineRefreshResult = commandMutex.withLock {
        metadataMutex.withLock {
            val metadata = requireMetadata()
            if (!metadata.hasStarted()) return@withLock TimelineRefreshResult.Updated(crossedBoot = false)
            val checkpoint = requireNotNull(metadata.clockCheckpoint)
            when (
                val result = studyTimeline.advance(
                    checkpoint,
                    metadata.state,
                    clocks.now(),
                    clocks.trustedUtcMillis(),
                )
            ) {
                StudyTimelineAdvance.TrustedUtcRequired -> TimelineRefreshResult.TrustedUtcRequired
                is StudyTimelineAdvance.Advanced -> {
                    val updated = metadata.copy(clockCheckpoint = result.checkpoint)
                    if (updated != metadata) {
                        performStoreMutation { store.saveMetadata(updated) }
                        currentMetadata = updated
                        publishMetadata(updated)
                    }
                    TimelineRefreshResult.Updated(result.crossedBoot)
                }
            }
        }
    }

    /** Converts a stale durable RUNNING state to its typed safety pause before activation. */
    suspend fun pauseRecoveredForSafetyFailure(reason: SafetyPauseReason): CommandResult = executeCommand {
        check(requireMetadata().state == ExperimentState.RUNNING) {
            "Only a durably running study can be paused during recovery"
        }
        closeAllAdmission()
        val effectiveReason = latchSafetyPauseReason(reason)
        transitionTo(ExperimentState.PAUSED, effectiveReason.transitionReason)
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
                plugin.descriptor.accessRequirements(collectorConfiguration.required)
            }
            .filter { it.required && it.kind !in availableAccess }
        require(missingRequired.isEmpty()) {
            "Required access is missing: ${missingRequired.map { it.kind }}"
        }
        transitionTo(ExperimentState.READY, TransitionReason.ACCESS_PREFLIGHT_PASSED)
    }

    suspend fun start(availableAccess: Set<AccessKind>): CommandResult = executeCommand {
        transitionTo(ExperimentState.RUNNING, TransitionReason.PARTICIPANT_STARTED)
        admissionGate.open()
        activateCollectors(availableAccess)
    }

    suspend fun pause(): CommandResult = executeCommand {
        drainAndTransition(
            to = ExperimentState.PAUSED,
            reason = TransitionReason.PARTICIPANT_PAUSED,
            stopCollectors = false,
        )
    }

    /**
     * Closes every event boundary before durably recording a non-participant safety pause.
     *
     * Admission remains closed and collectors are paused even if the metadata write fails. The
     * application owns a typed durable marker and retries this operation without losing [reason].
     */
    suspend fun closeAdmissionForSafetyFailure(reason: SafetyPauseReason): SafetyPauseReason? =
        commandMutex.withLock {
            if (currentMetadata?.state !in setOf(
                    ExperimentState.READY,
                    ExperimentState.RUNNING,
                    ExperimentState.PAUSED,
                )
            ) {
                return@withLock null
            }
            closeAllAdmission()
            latchSafetyPauseReason(reason)
        }

    suspend fun pauseForSafetyFailure(reason: SafetyPauseReason): CommandResult = commandMutex.withLock {
        mutableSnapshot.update { it.copy(incidentCode = null) }
        val metadata = currentMetadata
        if (metadata?.state != ExperimentState.RUNNING) {
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_COMMAND_REJECTED) }
            return@withLock CommandResult.Failed(INCIDENT_COMMAND_REJECTED)
        }

        closeAllAdmission()
        val effectiveReason = latchSafetyPauseReason(reason)
        val boundary = clocks.now()

        var transitionFailure: Throwable? = null
        val collectorsPaused = withContext(NonCancellable) {
            val paused = pauseCollectors()
            awaitAdmittedWritesDrained()
            paused
        }
        try {
            val afterDrain = requireMetadata()
            val alreadyCommitted = afterDrain.state == ExperimentState.PAUSED &&
                afterDrain.transitions.lastOrNull()?.reason == effectiveReason.transitionReason
            if (!alreadyCommitted) {
                transitionTo(
                    ExperimentState.PAUSED,
                    effectiveReason.transitionReason,
                    boundary,
                )
            }
        } catch (failure: Throwable) {
            transitionFailure = failure
        }

        transitionFailure?.let { failure ->
            failure.rethrowIfCancellation()
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_PAUSE_PERSISTENCE_FAILED) }
            return@withLock CommandResult.Failed(INCIDENT_PAUSE_PERSISTENCE_FAILED)
        }
        if (!collectorsPaused) {
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_COLLECTOR_PAUSE_FAILED) }
            return@withLock CommandResult.Failed(INCIDENT_COLLECTOR_PAUSE_FAILED)
        }
        CommandResult.Success
    }

    fun hasPendingSafetyPause(): Boolean {
        val current = mutableSnapshot.value
        return pendingSafetyPause.get() != null ||
            (current.metadata?.state == ExperimentState.RUNNING &&
                current.incidentCode == INCIDENT_PAUSE_PERSISTENCE_FAILED)
    }

    /**
     * Clears the runtime-owned signal only after the application established either the durable
     * PAUSED boundary and cleanup or an acknowledged durable retry carrying the same closed reason.
     */
    suspend fun acknowledgeSafetyPauseRequest(reason: SafetyPauseReason): Boolean = commandMutex.withLock {
        if (!pendingSafetyPause.compareAndSet(reason, null)) return@withLock false
        mutableSnapshot.update { current ->
            if (current.pendingSafetyPauseReason == reason) {
                current.copy(pendingSafetyPauseReason = null)
            } else {
                current
            }
        }
        true
    }

    /** Retries both the durable boundary and any collector teardown that did not complete. */
    suspend fun retrySafetyPause(reason: SafetyPauseReason): CommandResult = commandMutex.withLock {
        closeAllAdmission()
        val effectiveReason = latchSafetyPauseReason(reason)
        val metadata = currentMetadata
        val state = metadata?.state
        if (state !in setOf(ExperimentState.READY, ExperimentState.RUNNING, ExperimentState.PAUSED)) {
            return@withLock CommandResult.Failed(INCIDENT_COMMAND_REJECTED)
        }
        try {
            val collectorsPaused = withContext(NonCancellable) {
                val paused = pauseCollectors()
                awaitAdmittedWritesDrained()
                paused
            }
            if (state == ExperimentState.RUNNING) {
                transitionTo(ExperimentState.PAUSED, effectiveReason.transitionReason)
            } else {
                val resolvedAppend = store.resolvePendingAppendFailure(effectiveReason.transitionReason)
                // A failed READY/PAUSED -> RUNNING save may have atomically replaced the file before
                // its directory-fsync or coroutine acknowledgement failed. Rewriting the verified
                // in-memory pre-transition metadata is the rollback acknowledgement; the marker or
                // typed work must remain until this write succeeds.
                if (resolvedAppend == null) {
                    store.saveMetadata(requireNotNull(metadata))
                } else {
                    currentMetadata = resolvedAppend
                    publishMetadata(resolvedAppend)
                }
            }
            if (!collectorsPaused) {
                mutableSnapshot.update { it.copy(incidentCode = INCIDENT_COLLECTOR_PAUSE_FAILED) }
                return@withLock CommandResult.Failed(INCIDENT_COLLECTOR_PAUSE_FAILED)
            }
            mutableSnapshot.update { it.copy(incidentCode = null) }
            CommandResult.Success
        } catch (failure: Throwable) {
            failure.rethrowIfCancellation()
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_PAUSE_PERSISTENCE_FAILED) }
            CommandResult.Failed(INCIDENT_PAUSE_PERSISTENCE_FAILED)
        }
    }

    suspend fun resume(availableAccess: Set<AccessKind>): CommandResult = executeCommand {
        check(pendingSafetyPause.get() == null) {
            "A pending safety failure must be durably resolved before resuming"
        }
        transitionTo(ExperimentState.RUNNING, TransitionReason.PARTICIPANT_RESUMED)
        admissionGate.open()
        activateCollectors(availableAccess)
    }

    /** Reconciles optional collector access while the study itself remains running. */
    suspend fun reconcileCollectorAccess(availableKinds: Set<AccessKind>): CommandResult = executeCommand {
        check(requireMetadata().state == ExperimentState.RUNNING) {
            "Collector access can be reconciled only while running"
        }
        check(pendingSafetyPause.get() == null) {
            "Collector access cannot reopen while a safety pause is pending"
        }
        collectorEntries.forEach { (id, entry) ->
            val missingAccess = entry.plugin.descriptor
                .accessRequirements(entry.configuration.required)
                .any { requirement -> requirement.kind !in availableKinds }
            if (missingAccess) {
                // Access revocation is the admission boundary. Close it before inspecting health or
                // asking the source to tear down, because FAILED does not prove physical release.
                entry.closeAdmission()
                entry.accessBlocked = true
                if (!entry.hasStarted) {
                    publishCollectorHealth(id, entry)
                    return@forEach
                }
                if (!entry.sourcePaused) {
                    try {
                        entry.collector.pause()
                        entry.sourcePaused = true
                        entry.admissionFailureReason = null
                    } catch (failure: Throwable) {
                        failure.rethrowIfCancellation()
                        entry.admissionFailureReason = "COLLECTOR_PAUSE_FAILED"
                        publishCollectorHealth(id, entry)
                        return@forEach
                    }
                }
                publishCollectorHealth(id, entry)
                return@forEach
            }

            entry.accessBlocked = false
            when {
                entry.admissionOpen -> publishCollectorHealth(id, entry)
                !entry.hasStarted -> activateCollector(id, entry, resume = false)
                entry.sourcePaused -> activateCollector(id, entry, resume = true)
                else -> {
                    // A previous start or teardown did not establish a resumable source state.
                    // Access returning cannot make that uncertainty safe, so admission stays shut.
                    if (entry.admissionFailureReason == null) {
                        entry.admissionFailureReason = "COLLECTOR_ADMISSION_CLOSED"
                    }
                    publishCollectorHealth(id, entry)
                }
            }
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
            stopAndTransitionFromClosed(
                ExperimentState.COMPLETED,
                TransitionReason.STUDY_DURATION_ELAPSED,
            )
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
                stopAndTransitionFromClosed(
                    ExperimentState.WITHDRAWN,
                    TransitionReason.PARTICIPANT_WITHDREW,
                )
            }

            else -> stopAndTransitionFromClosed(
                ExperimentState.WITHDRAWN,
                TransitionReason.PARTICIPANT_WITHDREW,
            )
        }
    }

    suspend fun ensureOccurrence(planned: InterventionOccurrence): InterventionOccurrence = metadataMutex.withLock {
        check(pendingSafetyPause.get() == null) {
            "Occurrence planning is disabled while a safety pause is pending"
        }
        val metadata = requireMetadata()
        check(metadata.state == ExperimentState.RUNNING) {
            "Occurrences can be planned only while the study is running"
        }
        val now = clocks.now()
        check(withinSignedDuration(now)) {
            "Occurrences cannot be changed after the signed study duration"
        }
        metadata.occurrences[planned.occurrenceId]?.let { existing ->
            if (existing.state == OccurrenceState.SCHEDULED &&
                (existing.scheduledFor.wallTimeUtcMillis != planned.scheduledFor.wallTimeUtcMillis ||
                    existing.expiresAtUtcMillis != planned.expiresAtUtcMillis)
            ) {
                val revised = existing.copy(
                    scheduledFor = planned.scheduledFor,
                    expiresAtUtcMillis = planned.expiresAtUtcMillis,
                )
                appendOccurrenceEvent(
                    metadata.copy(occurrences = metadata.occurrences + (revised.occurrenceId to revised)),
                    revised,
                    "INTERVENTION_RESCHEDULED",
                    now,
                )
                return@withLock revised
            }
            return@withLock existing
        }
        require(configuration.interventions.any { it.id == planned.interventionId }) { "Unknown intervention" }
        appendOccurrenceEvent(
            metadata.copy(occurrences = metadata.occurrences + (planned.occurrenceId to planned)),
            planned,
            "INTERVENTION_SCHEDULED",
            now,
        )
        planned
    }

    suspend fun claimOccurrenceIfDue(occurrenceId: String): OccurrenceClaimResult = metadataMutex.withLock {
        val metadata = requireMetadata()
        if (metadata.state != ExperimentState.RUNNING || pendingSafetyPause.get() != null) {
            return@withLock OccurrenceClaimResult.InactiveStudy
        }
        val now = clocks.now()
        if (!withinSignedDuration(now)) return@withLock OccurrenceClaimResult.InactiveStudy
        val occurrence = metadata.occurrences[occurrenceId] ?: return@withLock OccurrenceClaimResult.Missing
        if (occurrence.state !in setOf(OccurrenceState.SCHEDULED, OccurrenceState.POSTING)) {
            return@withLock OccurrenceClaimResult.Terminal
        }
        if (now.wallTimeUtcMillis >= occurrence.expiresAtUtcMillis) {
            expireOccurrence(metadata, occurrence, now)
            return@withLock OccurrenceClaimResult.Expired
        }
        val remaining = occurrence.scheduledFor.wallTimeUtcMillis - now.wallTimeUtcMillis
        if (remaining > 0) return@withLock OccurrenceClaimResult.NotDue(remaining)
        val claimed = if (occurrence.state == OccurrenceState.SCHEDULED) {
            occurrence.copy(state = OccurrenceState.POSTING).also { next ->
                val updated = advanceCheckpoint(metadata, now).copy(
                    occurrences = metadata.occurrences + (occurrenceId to next),
                )
                performStoreMutation { store.saveMetadata(updated) }
                currentMetadata = updated
                publishMetadata(updated)
            }
        } else {
            occurrence
        }
        OccurrenceClaimResult.Due(OccurrenceDispatch(claimed, intervention(claimed).action))
    }

    /**
     * Expires one occurrence without ever claiming delivery.
     *
     * WorkManager may wake early after a wall-clock change. Returning [OccurrenceExpiryResult.NotDue]
     * leaves the durable lifecycle untouched so the adapter can retry instead of consuming the only
     * expiry job. Notification-only occurrences that were already opened are terminal; surveys stay
     * open until submitted or expired.
     */
    suspend fun expireOccurrenceIfDue(occurrenceId: String): OccurrenceExpiryResult = metadataMutex.withLock {
        val metadata = requireMetadata()
        if (metadata.state != ExperimentState.RUNNING || pendingSafetyPause.get() != null) {
            return@withLock OccurrenceExpiryResult.InactiveStudy
        }
        val now = clocks.now()
        if (!withinSignedDuration(now)) return@withLock OccurrenceExpiryResult.InactiveStudy
        val occurrence = metadata.occurrences[occurrenceId] ?: return@withLock OccurrenceExpiryResult.Missing
        if (
            occurrence.state in setOf(OccurrenceState.EXPIRED, OccurrenceState.SURVEY_SUBMITTED) ||
            (occurrence.state == OccurrenceState.OPENED && intervention(occurrence).action !is SurveyAction)
        ) {
            return@withLock OccurrenceExpiryResult.Terminal
        }
        val remaining = occurrence.expiresAtUtcMillis - now.wallTimeUtcMillis
        if (remaining > 0) return@withLock OccurrenceExpiryResult.NotDue(remaining)
        expireOccurrence(metadata, occurrence, now)
        OccurrenceExpiryResult.Expired
    }

    suspend fun markNotificationPosted(occurrenceId: String): Boolean = metadataMutex.withLock {
        val metadata = requireMetadata()
        if (metadata.state != ExperimentState.RUNNING || pendingSafetyPause.get() != null) return@withLock false
        val now = clocks.now()
        if (!withinSignedDuration(now)) return@withLock false
        val occurrence = metadata.occurrences[occurrenceId] ?: return@withLock false
        if (occurrence.state !in setOf(OccurrenceState.POSTING, OccurrenceState.NOTIFICATION_POSTED)) {
            return@withLock false
        }
        if (now.wallTimeUtcMillis >= occurrence.expiresAtUtcMillis) {
            expireOccurrence(metadata, occurrence, now)
            return@withLock false
        }
        if (occurrence.state == OccurrenceState.NOTIFICATION_POSTED) return@withLock true
        val posted = occurrence.copy(state = OccurrenceState.NOTIFICATION_POSTED)
        appendOccurrenceEvent(
            metadata.copy(occurrences = metadata.occurrences + (occurrenceId to posted)),
            posted,
            "NOTIFICATION_POSTED",
            now,
        )
        true
    }

    suspend fun openOccurrence(occurrenceId: String): OccurrenceDispatch? = metadataMutex.withLock {
        val metadata = requireMetadata()
        if (metadata.state != ExperimentState.RUNNING || pendingSafetyPause.get() != null) return@withLock null
        val now = clocks.now()
        if (!withinSignedDuration(now)) return@withLock null
        val occurrence = metadata.occurrences[occurrenceId] ?: return@withLock null
        if (now.wallTimeUtcMillis >= occurrence.expiresAtUtcMillis && occurrence.state != OccurrenceState.SURVEY_SUBMITTED) {
            expireOccurrence(metadata, occurrence, now)
            return@withLock null
        }
        if (occurrence.state in setOf(OccurrenceState.EXPIRED, OccurrenceState.SCHEDULED, OccurrenceState.POSTING)) {
            return@withLock null
        }
        if (occurrence.state == OccurrenceState.NOTIFICATION_POSTED) {
            val opened = occurrence.copy(state = OccurrenceState.OPENED, openedAt = now)
            appendOccurrenceEvent(
                metadata.copy(occurrences = metadata.occurrences + (occurrenceId to opened)),
                opened,
                if (intervention(opened).action is SurveyAction) "SURVEY_OPENED" else "INTERVENTION_OPENED",
                now,
            )
            return@withLock OccurrenceDispatch(opened, intervention(opened).action)
        }
        OccurrenceDispatch(occurrence, intervention(occurrence).action)
    }

    suspend fun submitSurvey(
        occurrenceId: String,
        answers: Map<String, SurveyAnswer>,
    ): SurveySubmissionResult = metadataMutex.withLock {
        val metadata = requireMetadata()
        if (metadata.state != ExperimentState.RUNNING || pendingSafetyPause.get() != null) {
            return@withLock SurveySubmissionResult.INVALID
        }
        val now = clocks.now()
        if (!withinSignedDuration(now)) return@withLock SurveySubmissionResult.INVALID
        val occurrence = metadata.occurrences[occurrenceId] ?: return@withLock SurveySubmissionResult.INVALID
        if (occurrence.state == OccurrenceState.SURVEY_SUBMITTED) return@withLock SurveySubmissionResult.ALREADY_SUBMITTED
        if (now.wallTimeUtcMillis >= occurrence.expiresAtUtcMillis) {
            expireOccurrence(metadata, occurrence, now)
            return@withLock SurveySubmissionResult.EXPIRED
        }
        if (occurrence.state != OccurrenceState.OPENED) return@withLock SurveySubmissionResult.INVALID
        val survey = surveyFor(occurrence) ?: return@withLock SurveySubmissionResult.INVALID
        val encoded = validateAndEncodeAnswers(survey, answers) ?: return@withLock SurveySubmissionResult.INVALID
        val submitted = occurrence.copy(
            state = OccurrenceState.SURVEY_SUBMITTED,
            submittedAt = now,
            submissionSequence = metadata.nextSequenceNumber,
        )
        appendOccurrenceEvent(
            metadata.copy(occurrences = metadata.occurrences + (occurrenceId to submitted)),
            submitted,
            "SURVEY_SUBMITTED",
            now,
            mapOf(
                "survey_id" to survey.id,
                "scheduled_time" to researchTimeJson(submitted.scheduledFor),
                "opened_time" to researchTimeJson(requireNotNull(submitted.openedAt)),
                "submitted_time" to researchTimeJson(now),
                "answers_json" to encoded,
            ),
        )
        SurveySubmissionResult.ACCEPTED
    }

    suspend fun surveySubmissionEvent(occurrenceId: String): RecordedEvent? = metadataMutex.withLock {
        val metadata = requireMetadata()
        val sequence = metadata.occurrences[occurrenceId]?.submissionSequence ?: return@withLock null
        if (sequence < metadata.retainedFromSequence) return@withLock null
        var found: RecordedEvent? = null
        store.readEvents(sequence, sequence) { found = it }
        found
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
        performStoreMutation { store.saveMetadata(updated) }
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
        val updated = performStoreMutation {
            store.evictThrough(
                metadata,
                targetBytes = (usage.quotaBytes * EVICT_DOWN_TO_FRACTION).toLong(),
            )
        }
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

    private fun captureStudyToken(): AdmissionToken? = admissionGate.capture()

    private suspend fun persistAdmittedEvent(
        studyToken: AdmissionToken,
        collectorGate: EventAdmissionGate,
        collectorToken: AdmissionToken,
        event: EventDraft,
    ): EmitResult {
        if (!registerAdmittedWrite(
                studyToken,
                collectorGate,
                collectorToken,
                event.observedTime,
            )
        ) {
            return EmitResult.RejectedByAdmissionGate
        }

        return try {
            metadataMutex.withLock {
                if (!acceptsAdmission(
                        studyToken,
                        collectorGate,
                        collectorToken,
                        event.observedTime,
                    )
                ) {
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
                val updated = advanceCheckpoint(metadata, event.observedTime).copy(
                    eventCount = metadata.eventCount + 1,
                    nextSequenceNumber = metadata.nextSequenceNumber + 1,
                    lastEvents = metadata.lastEvents + (recorded.collectorId to recorded),
                )
                try {
                    appendEventAtomicallyOrSignalStorageFailure(recorded, updated)
                    currentMetadata = updated
                    publishMetadata(updated)
                    EmitResult.Accepted(recorded.sequenceNumber)
                } catch (failure: Throwable) {
                    failure.rethrowIfCancellation()
                    EmitResult.StorageFailure
                }
            }
        } finally {
            withContext(NonCancellable) { completeAdmittedWrite() }
        }
    }

    private suspend fun registerAdmittedWrite(
        studyToken: AdmissionToken,
        collectorGate: EventAdmissionGate,
        collectorToken: AdmissionToken,
        observedAt: ResearchTime,
    ): Boolean = admittedWriteMutex.withLock {
        if (!acceptsAdmission(studyToken, collectorGate, collectorToken, observedAt)) {
            return@withLock false
        }
        if (admittedWriteCount == 0) admittedWritesDrained = CompletableDeferred()
        admittedWriteCount += 1
        true
    }

    private suspend fun completeAdmittedWrite() = admittedWriteMutex.withLock {
        check(admittedWriteCount > 0) { "Admitted write accounting underflow" }
        admittedWriteCount -= 1
        if (admittedWriteCount == 0) admittedWritesDrained.complete(Unit)
    }

    private suspend fun awaitAdmittedWritesDrained() {
        admittedWriteMutex.withLock { admittedWritesDrained }.await()
    }

    private fun acceptsAdmission(
        studyToken: AdmissionToken,
        collectorGate: EventAdmissionGate,
        collectorToken: AdmissionToken,
        observedAt: ResearchTime,
    ): Boolean = admissionGate.accepts(studyToken, observedAt.elapsedRealtimeNanos) &&
        collectorGate.accepts(collectorToken, observedAt.elapsedRealtimeNanos) &&
        withinSignedDuration(observedAt)

    private fun withinSignedDuration(observedAt: ResearchTime): Boolean {
        val checkpoint = currentMetadata?.clockCheckpoint ?: return false
        return studyTimeline.admits(checkpoint, observedAt)
    }

    private suspend fun latestEvent(collectorId: String): RecordedEvent? = metadataMutex.withLock {
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
        } catch (failure: CancellationException) {
            throw failure
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
            val collectorAdmissionGate = EventAdmissionGate()
            val collector = plugin.create(
                configuration,
                CollectorContext(
                    scope = scope,
                    eventSink = CollectorEventSink(plugin.descriptor, collectorAdmissionGate),
                    clocks = clocks,
                ),
            )
            val entry = CollectorEntry(
                collector = collector,
                configuration = configuration,
                plugin = plugin,
                admissionGate = collectorAdmissionGate,
            )
            collectorEntries[plugin.descriptor.id] = entry
            healthJobs += scope.launch {
                collector.health.collect { health ->
                    updateCollectorHealth(
                        plugin.descriptor.id,
                        entry.presentedHealth(health),
                    )
                }
            }
        }
    }

    private fun configuredPlugins() = configuration.collectors.map { collectorConfiguration ->
        collectorConfiguration to collectorRegistry.pluginFor(collectorConfiguration)
    }

    private suspend fun activateCollectors(availableKinds: Set<AccessKind>) {
        collectorEntries.forEach { (id, entry) ->
            val missingAccess = entry.plugin.descriptor.accessRequirements(entry.configuration.required)
                .filter { it.kind !in availableKinds }
            if (missingAccess.isNotEmpty()) {
                entry.closeAdmission()
                entry.accessBlocked = true
                publishCollectorHealth(id, entry)
                return@forEach
            }
            entry.accessBlocked = false
            activateCollector(id, entry, resume = entry.hasStarted)
        }
    }

    /** Opens collector admission only after its source reports a successful start or resume. */
    private suspend fun activateCollector(
        id: String,
        entry: CollectorEntry,
        resume: Boolean,
    ) {
        entry.closeAdmission()
        try {
            if (resume) {
                entry.collector.resume()
            } else {
                entry.collector.start()
                entry.hasStarted = true
            }
            entry.sourcePaused = false
            entry.admissionFailureReason = null
            entry.openAdmission()
            entry.collector.onAdmissionOpened()
            publishCollectorHealth(id, entry)
        } catch (failure: Throwable) {
            entry.closeAdmission()
            entry.hasStarted = entry.hasStarted || entry.collector.requiresStop
            failure.rethrowIfCancellation()
            entry.admissionFailureReason = if (resume) {
                "COLLECTOR_RESUME_FAILED"
            } else {
                "COLLECTOR_START_FAILED"
            }
            publishCollectorHealth(id, entry)
        }
    }

    private suspend fun pauseCollectors(): Boolean {
        var allPaused = true
        collectorEntries.forEach { (id, entry) ->
            if (!entry.hasStarted) {
                entry.closeAdmission()
                return@forEach
            }
            try {
                entry.collector.pause()
                entry.sourcePaused = true
                entry.admissionFailureReason = null
            } catch (failure: Throwable) {
                failure.rethrowIfCancellation()
                allPaused = false
                entry.admissionFailureReason = "COLLECTOR_PAUSE_FAILED"
                publishCollectorHealth(id, entry)
            } finally {
                // The global boundary is already draining or closed. Retire this collector epoch
                // after teardown as a second, collector-owned admission boundary.
                entry.closeAdmission()
            }
        }
        return allPaused
    }

    private fun closeAllAdmission() {
        admissionGate.forceClose()
        collectorEntries.values.forEach(CollectorEntry::closeAdmission)
    }

    private suspend fun stopCollectors(): Boolean {
        var allStopped = true
        collectorEntries.forEach { (id, entry) ->
            if (!entry.hasStarted) {
                entry.closeAdmission()
                return@forEach
            }
            entry.accessBlocked = false
            try {
                entry.collector.stop()
                entry.sourcePaused = false
                entry.admissionFailureReason = null
            } catch (failure: Throwable) {
                failure.rethrowIfCancellation()
                allStopped = false
                entry.admissionFailureReason = "COLLECTOR_STOP_FAILED"
                publishCollectorHealth(id, entry)
            } finally {
                entry.closeAdmission()
                entry.hasStarted = entry.collector.requiresStop
            }
        }
        return allStopped
    }

    private suspend fun drainAndTransition(
        to: ExperimentState,
        reason: TransitionReason,
        stopCollectors: Boolean,
    ) {
        val boundary = clocks.now()
        val token = admissionGate.beginDrain(boundary.elapsedRealtimeNanos)
        try {
            // Metadata is committed only after every write already admitted at the boundary has
            // finished. Closing after source teardown rejects late callbacks; a storage failure
            // either becomes the stronger pause reason or prevents a terminal export entirely.
            val sourcesReleased = if (stopCollectors) stopCollectors() else pauseCollectors()
            admissionGate.close(token)
            awaitAdmittedWritesDrained()
            if (!sourcesReleased) {
                signalSafetyFailure(
                    SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                    INCIDENT_COLLECTION_TEARDOWN_FAILED,
                )
            }
            if (stopCollectors) {
                check(pendingSafetyPause.get() == null) {
                    "A safety failure prevents the terminal transition"
                }
            }
            transitionTo(to, reason, boundary)
            check(sourcesReleased) {
                "Collector teardown failed at the pause boundary"
            }
        } catch (failure: Throwable) {
            admissionGate.forceClose()
            persistCancelledTeardownWitness(failure)
            throw failure
        }
    }

    private suspend fun stopAndTransitionFromClosed(
        to: ExperimentState,
        reason: TransitionReason,
    ) {
        val startedPaused = currentMetadata?.state == ExperimentState.PAUSED
        try {
            if (!stopCollectors()) {
                signalSafetyFailure(
                    SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                    INCIDENT_COLLECTION_TEARDOWN_FAILED,
                )
                error("Collector teardown failed before the terminal transition")
            }
            awaitAdmittedWritesDrained()
            check(pendingSafetyPause.get() == null) {
                "A safety failure prevents the terminal transition"
            }
            transitionTo(to, reason)
        } catch (failure: Throwable) {
            closeAllAdmission()
            if (startedPaused) persistCancelledTeardownWitness(failure)
            throw failure
        }
    }

    /**
     * Once a drain has started, caller cancellation cannot be allowed to erase the only evidence
     * that source teardown is incomplete. Persist the typed witness before propagating cancellation;
     * recovery will enter PAUSED before any host or collector can reopen.
     */
    private suspend fun persistCancelledTeardownWitness(failure: Throwable) {
        if (failure !is CancellationException) return
        try {
            withContext(NonCancellable) {
                signalSafetyFailure(
                    SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                    INCIDENT_COLLECTION_TEARDOWN_FAILED,
                )
            }
        } catch (witnessFailure: Throwable) {
            failure.addSuppressed(witnessFailure)
        }
    }

    private suspend fun transitionTo(
        state: ExperimentState,
        reason: TransitionReason,
        time: cool.jacoblin.particeps.core.model.ResearchTime = clocks.now(),
    ) {
        metadataMutex.withLock {
            // A storage failure can be published while a participant pause is waiting on this
            // mutex. Linearize that concurrent pause as the stronger safety transition so its
            // durable reason can never become PARTICIPANT_PAUSED after the fail-closed signal.
            val effectiveReason = if (state == ExperimentState.PAUSED) {
                pendingSafetyPause.get()?.transitionReason ?: reason
            } else {
                reason
            }
            val before = requireMetadata()
            val transitioned = stateMachine.transition(before, state, effectiveReason, time)
            val updated = if (effectiveReason == TransitionReason.PARTICIPANT_STARTED) {
                transitioned.copy(
                    clockCheckpoint = studyTimeline.startedAt(time, clocks.trustedUtcMillis()),
                )
            } else if (!before.hasStarted()) {
                transitioned
            } else {
                when (effectiveReason) {
                    TransitionReason.DEVICE_REBOOT -> {
                        when (val result = advanceCheckpointResult(before, time)) {
                            is StudyTimelineAdvance.Advanced -> transitioned.copy(clockCheckpoint = result.checkpoint)
                            // The PAUSED reboot boundary is still authoritative without UTC. Keep
                            // the old anchor so a later trusted reading can account for the gap.
                            StudyTimelineAdvance.TrustedUtcRequired -> transitioned
                        }
                    }
                    else -> transitioned.copy(clockCheckpoint = advanceCheckpoint(before, time).clockCheckpoint)
                }
            }
            performStoreMutation(mayHaveCommittedRunning = state == ExperimentState.RUNNING) {
                store.saveMetadata(updated)
            }
            currentMetadata = updated
            publishMetadata(updated)
        }
    }

    private fun requireMetadata(): StudyMetadata = checkNotNull(currentMetadata) { "Runtime is not initialized" }

    private fun StudyMetadata.hasStarted(): Boolean =
        transitions.any { it.reason == TransitionReason.PARTICIPANT_STARTED }

    private fun advanceCheckpoint(metadata: StudyMetadata, observedAt: ResearchTime): StudyMetadata =
        when (val result = advanceCheckpointResult(metadata, observedAt)) {
            is StudyTimelineAdvance.Advanced -> metadata.copy(clockCheckpoint = result.checkpoint)
            StudyTimelineAdvance.TrustedUtcRequired -> throw TrustedStudyTimeUnavailable()
        }

    private fun advanceCheckpointResult(
        metadata: StudyMetadata,
        observedAt: ResearchTime,
    ): StudyTimelineAdvance = studyTimeline.advance(
        requireNotNull(metadata.clockCheckpoint) { "Started study is missing its clock checkpoint" },
        metadata.state,
        observedAt,
        clocks.trustedUtcMillis(),
    )

    private fun intervention(occurrence: InterventionOccurrence) =
        configuration.interventions.first { it.id == occurrence.interventionId }

    private fun surveyFor(occurrence: InterventionOccurrence): SurveyDefinition? =
        (intervention(occurrence).action as? SurveyAction)?.let { action ->
            configuration.surveys.firstOrNull { it.id == action.surveyId }
        }

    private suspend fun expireOccurrence(
        metadata: StudyMetadata,
        occurrence: InterventionOccurrence,
        now: cool.jacoblin.particeps.core.model.ResearchTime,
    ) {
        if (occurrence.state in setOf(OccurrenceState.EXPIRED, OccurrenceState.SURVEY_SUBMITTED)) return
        if (occurrence.state == OccurrenceState.OPENED && intervention(occurrence).action !is SurveyAction) return
        val expired = occurrence.copy(state = OccurrenceState.EXPIRED)
        appendOccurrenceEvent(
            metadata.copy(occurrences = metadata.occurrences + (occurrence.occurrenceId to expired)),
            expired,
            if (intervention(expired).action is SurveyAction) "SURVEY_EXPIRED" else "INTERVENTION_EXPIRED",
            now,
        )
    }

    private suspend fun appendOccurrenceEvent(
        metadataAfterState: StudyMetadata,
        occurrence: InterventionOccurrence,
        payloadType: String,
        observedAt: ResearchTime,
        additionalFields: Map<String, String> = emptyMap(),
    ) {
        check(withinSignedDuration(observedAt)) {
            "Occurrence events cannot be admitted after the signed study duration"
        }
        val draft = EventDraft(
            collectorId = INTERVENTION_COLLECTOR_ID,
            payloadSchemaVersion = 1,
            observedTime = observedAt,
            payloadType = payloadType,
            fields = mapOf(
                "intervention_id" to occurrence.interventionId,
                "trigger_id" to occurrence.triggerId,
                "occurrence_id" to occurrence.occurrenceId,
                "scheduled_for_utc_millis" to occurrence.scheduledFor.wallTimeUtcMillis.toString(),
            ) + additionalFields,
        )
        check(requireNotNull(ProtocolEventContracts[INTERVENTION_COLLECTOR_ID]).accepts(
            draft,
            metadataAfterState.nextSequenceNumber,
        )) { "Runtime intervention event violates Protocol v1" }
        val event = RecordedEvent(
            sequenceNumber = metadataAfterState.nextSequenceNumber,
            collectorId = draft.collectorId,
            payloadSchemaVersion = draft.payloadSchemaVersion,
            observedTime = draft.observedTime,
            payloadType = draft.payloadType,
            fields = draft.fields.toSortedMap(),
        )
        val updated = advanceCheckpoint(metadataAfterState, observedAt).copy(
            eventCount = event.sequenceNumber,
            nextSequenceNumber = event.sequenceNumber + 1,
            lastEvents = metadataAfterState.lastEvents + (event.collectorId to event),
        )
        try {
            appendEventAtomicallyOrSignalStorageFailure(event, updated)
        } catch (failure: StudyStoreMutationFailedClosed) {
            val recovered = requireNotNull(currentMetadata)
            val recoveredEvent = recovered.lastEvents[INTERVENTION_COLLECTOR_ID]
            if (
                recovered.eventCount != updated.eventCount ||
                recoveredEvent != event ||
                recovered.occurrences[occurrence.occurrenceId] != occurrence
            ) {
                throw failure
            }
            // The occurrence mutation and its event are durable even though the whole study has
            // entered a typed storage safety pause. Reporting semantic success keeps an already
            // posted Android notification consistent with durable NOTIFICATION_POSTED state.
            return
        }
        currentMetadata = updated
        publishMetadata(updated)
    }

    /**
     * Makes every atomic event append share the same fail-closed storage boundary.
     *
     * Callers may translate or propagate the original failure, but the typed request is published
     * first while their metadata critical section is still held. This method never takes
     * [commandMutex], preserving the runtime's command -> metadata lock ordering.
     */
    private suspend fun appendEventAtomicallyOrSignalStorageFailure(
        event: RecordedEvent,
        metadata: StudyMetadata,
    ) {
        try {
            store.appendEventAtomically(event, metadata, clocks.now())
        } catch (failure: Throwable) {
            val recovered = (failure as? StudyStoreMutationFailedClosed)?.metadata
            if (recovered != null) {
                currentMetadata = recovered
                publishMetadata(recovered)
            }
            var effectiveReason = pendingSafetyPause.get() ?: SafetyPauseReason.STORAGE_FAILURE
            try {
                effectiveReason = signalStorageFailure()
            } catch (witnessFailure: Throwable) {
                failure.addSuppressed(witnessFailure)
                effectiveReason = pendingSafetyPause.get() ?: effectiveReason
            }
            if (recovered != null) {
                try {
                    store.resolvePendingAppendFailure(effectiveReason.transitionReason)?.let { resolved ->
                        currentMetadata = resolved
                        publishMetadata(resolved)
                    }
                } catch (resolutionFailure: Throwable) {
                    failure.addSuppressed(resolutionFailure)
                }
            }
            val cancellation = failure.cause as? CancellationException
            throw cancellation ?: failure
        }
    }

    /** Protects every mutable store operation that can strand a durably RUNNING study. */
    private suspend fun <T> performStoreMutation(
        mayHaveCommittedRunning: Boolean = false,
        mutation: suspend () -> T,
    ): T = try {
        mutation()
    } catch (failure: Throwable) {
        if (currentMetadata?.state == ExperimentState.RUNNING || mayHaveCommittedRunning) {
            try {
                signalStorageFailure()
            } catch (witnessFailure: Throwable) {
                failure.addSuppressed(witnessFailure)
            }
        }
        throw failure
    }

    /**
     * Closes live collection before publishing a storage request without acquiring commandMutex.
     * The atomic latch makes concurrent access/host/storage failures deterministically first-wins.
     */
    private suspend fun signalStorageFailure(): SafetyPauseReason =
        signalSafetyFailure(
            SafetyPauseReason.STORAGE_FAILURE,
            INCIDENT_STORAGE_WRITE_FAILED,
        )

    private suspend fun signalSafetyFailure(
        reason: SafetyPauseReason,
        incidentCode: String,
    ): SafetyPauseReason {
        closeAllAdmission()
        val effectiveReason = latchSafetyPauseReason(reason)
        mutableSnapshot.update { it.copy(incidentCode = incidentCode) }
        withContext(NonCancellable) {
            safetyPauseWitness.persist(effectiveReason)
        }
        return effectiveReason
    }

    private fun latchSafetyPauseReason(requested: SafetyPauseReason): SafetyPauseReason {
        while (true) {
            val existing = pendingSafetyPause.get()
            if (existing != null) {
                publishPendingSafetyPause(existing)
                return existing
            }
            if (pendingSafetyPause.compareAndSet(null, requested)) {
                publishPendingSafetyPause(requested)
                return requested
            }
        }
    }

    private fun publishPendingSafetyPause(reason: SafetyPauseReason) {
        mutableSnapshot.update { current ->
            if (current.pendingSafetyPauseReason == reason) current
            else current.copy(pendingSafetyPauseReason = reason)
        }
    }

    private fun validateAndEncodeAnswers(survey: SurveyDefinition, answers: Map<String, SurveyAnswer>): String? {
        if (answers.keys.any { key -> survey.questions.none { it.id == key } }) return null
        survey.questions.forEach { question ->
            val answer = answers[question.id]
            if (answer == null) {
                if (question.required) return null
                return@forEach
            }
            val valid = when (question) {
                is ShortTextQuestion -> answer is SurveyAnswer.Text &&
                    answer.value.length <= question.maximumLength && (!question.required || answer.value.isNotBlank())
                is ScaleQuestion -> answer is SurveyAnswer.Integer && answer.value in question.minimum..question.maximum
                is SingleChoiceQuestion -> answer is SurveyAnswer.Choices && answer.optionIds.size == 1 &&
                    answer.optionIds.single() in question.options.map { it.id }
                is MultipleChoiceQuestion -> answer is SurveyAnswer.Choices &&
                    answer.optionIds.distinct().size == answer.optionIds.size &&
                    answer.optionIds.size in question.minimumSelections..question.maximumSelections &&
                    answer.optionIds.all { id -> id in question.options.map { it.id } }
            }
            if (!valid) return null
        }
        val encoded = answers.toSortedMap().entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (id, answer) ->
            "${jsonString(id)}:${when (answer) {
                is SurveyAnswer.Text -> jsonString(answer.value)
                is SurveyAnswer.Integer -> answer.value.toString()
                is SurveyAnswer.Choices -> answer.optionIds.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonString(it) }
            }}"
        }
        return encoded.takeIf { it.toByteArray().size <= MAXIMUM_SURVEY_ANSWERS_BYTES }
    }

    private fun researchTimeJson(time: cool.jacoblin.particeps.core.model.ResearchTime): String =
        "{\"wall_time_utc_millis\":${time.wallTimeUtcMillis},\"elapsed_realtime_nanos\":${time.elapsedRealtimeNanos}," +
            "\"boot_session_id\":${jsonString(time.bootSessionId)}}"

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

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

    private fun publishCollectorHealth(
        collectorId: String,
        entry: CollectorEntry,
    ) {
        updateCollectorHealth(collectorId, entry.presentedHealth(entry.collector.health.value))
    }

    private class CollectorEntry(
        val collector: Collector,
        val configuration: cool.jacoblin.particeps.core.definition.CollectorConfiguration,
        val plugin: CollectorPlugin,
        val admissionGate: EventAdmissionGate,
    ) {
        var hasStarted: Boolean = false
        var sourcePaused: Boolean = false
        var admissionOpen: Boolean = false

        @Volatile
        var accessBlocked: Boolean = false

        @Volatile
        var admissionFailureReason: String? = null

        fun openAdmission() {
            admissionGate.open()
            admissionOpen = true
        }

        fun closeAdmission() {
            admissionGate.forceClose()
            admissionOpen = false
        }

        fun presentedHealth(sourceHealth: CollectorHealth): CollectorHealth {
            val failureReason = admissionFailureReason
            return when {
                failureReason != null -> CollectorHealth(
                    CollectorStatus.FAILED,
                    failureReason,
                )
                accessBlocked && sourceHealth.status != CollectorStatus.FAILED ->
                    CollectorHealth(CollectorStatus.BLOCKED_ACCESS, "ACCESS_UNAVAILABLE")
                else -> sourceHealth
            }
        }
    }

    private data class CollectorAdmissionToken(
        val studyToken: AdmissionToken,
        val collectorToken: AdmissionToken,
    ) : AdmissionToken

    /** Binds a collector's shared admission capability to its own declared event contract. */
    private inner class CollectorEventSink(
        private val descriptor: CollectorDescriptor,
        private val collectorAdmissionGate: EventAdmissionGate,
    ) : EventSink {
        override fun captureToken(): AdmissionToken? {
            val collectorToken = collectorAdmissionGate.capture() ?: return null
            val studyToken = captureStudyToken() ?: return null
            return CollectorAdmissionToken(studyToken, collectorToken)
        }

        override suspend fun emit(token: AdmissionToken, event: EventDraft): EmitResult {
            if (event.collectorId != descriptor.id ||
                !descriptor.eventContract.accepts(event, Long.MAX_VALUE)
            ) {
                return EmitResult.ContractViolation
            }
            val admission = token as? CollectorAdmissionToken
                ?: return EmitResult.RejectedByAdmissionGate
            return persistAdmittedEvent(
                studyToken = admission.studyToken,
                event = event,
                collectorGate = collectorAdmissionGate,
                collectorToken = admission.collectorToken,
            )
        }

        override suspend fun latestEvent(collectorId: String): RecordedEvent? {
            require(collectorId == descriptor.id) { "Collector cannot inspect another collector's event" }
            return this@ExperimentRuntime.latestEvent(collectorId)
        }
    }

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
        const val INCIDENT_COLLECTION_TEARDOWN_FAILED = "COLLECTION_TEARDOWN_FAILED"
        const val INCIDENT_PAUSE_PERSISTENCE_FAILED = "PAUSE_PERSISTENCE_FAILED"
        const val INCIDENT_COLLECTOR_PAUSE_FAILED = "COLLECTOR_PAUSE_FAILED"
        const val MAXIMUM_SURVEY_ANSWERS_BYTES = 60 * 1024
        const val INTERVENTION_COLLECTOR_ID = "interventions.v1"
        const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
    }
}

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
