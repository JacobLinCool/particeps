package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessInspectionRequest
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.CollectorAccessRequirement
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.LocationAccessProfile
import cool.jacoblin.particeps.core.collector.NotificationAccessFeature
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.collector.StudyAccessGateway
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.LocationConfiguration
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.protocol.ActiveStudyStore
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import cool.jacoblin.particeps.core.runtime.CommandResult
import cool.jacoblin.particeps.core.runtime.ExperimentRuntime
import cool.jacoblin.particeps.core.runtime.OccurrenceClaimResult
import cool.jacoblin.particeps.core.runtime.OccurrenceDispatch
import cool.jacoblin.particeps.core.runtime.OccurrenceExpiryResult
import cool.jacoblin.particeps.core.runtime.RuntimeSnapshot
import cool.jacoblin.particeps.core.runtime.SafetyPauseWitness
import cool.jacoblin.particeps.core.runtime.SurveyAnswer
import cool.jacoblin.particeps.core.runtime.SurveySubmissionResult
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface StudyVerifier { fun verify(envelopeBytes: ByteArray): VerifiedConfiguration }

fun interface StudyStoreFactory {
    fun create(experimentId: String, maximumLocalBytes: Long): StudyStore
}

fun interface ExperimentRuntimeFactory {
    fun create(
        configuration: StudyConfiguration,
        store: StudyStore,
        safetyPauseWitness: SafetyPauseWitness,
    ): ExperimentRuntime
}

interface StudyCollectionHost {
    /** Returns only after the platform has acknowledged the requested foreground-service type. */
    suspend fun start(studyTitle: String, usesLocation: Boolean)
    fun stop()
}

/** App-private typed marker for any fail-closed pause that must survive process death. */
interface SafetyPauseStore {
    suspend fun pendingReason(): SafetyPauseReason?
    suspend fun markPending(reason: SafetyPauseReason)
    suspend fun clear()
}

interface StudyWorkScheduler {
    /** Idempotently persists all work implied by the durable study state. */
    suspend fun ensureCollectionWork(
        configuration: StudyConfiguration,
        metadata: StudyMetadata,
        observedAt: ResearchTime,
    )

    /** Rebuilds delivery and expiry work after state or same-boot clock/time-zone reconciliation. */
    suspend fun replaceInterventionWork(
        configuration: StudyConfiguration,
        deliveries: List<InterventionOccurrence>,
        expiries: List<InterventionOccurrence>,
    )

    /** Adds the successor of a completed trigger without disturbing unrelated work. */
    suspend fun enqueueOccurrence(configuration: StudyConfiguration, occurrence: InterventionOccurrence)

    /** Cancels delivery/expiry work and visible notifications while a study is paused. */
    suspend fun cancelInterventionWork(experimentId: String, occurrenceIds: Set<String>)

    /** Idempotently removes notifications that durable occurrence state proves are no longer visible. */
    fun cancelInterventionNotifications(occurrenceIds: Set<String>)

    /** Schedules an independent retry when the typed safety pause is not fully durable and clean. */
    suspend fun scheduleSafetyPauseRetry(experimentId: String, reason: SafetyPauseReason)

    /** Reads an active typed retry before recovery is allowed to start any host or collector. */
    suspend fun pendingSafetyPauseReason(experimentId: String): SafetyPauseReason?

    suspend fun cancelSafetyPauseRetry()

    /**
     * Cancels interventions, reminders and the study deadline, leaving the upload tail in place.
     *
     * Used when a study ends. Collection is over, but events already recorded and not yet delivered
     * are still owed to the researcher — stranding them here would defeat the point of uploading at
     * all, since the participant may never perform a manual export.
     */
    suspend fun cancelCollectionWork(experimentId: String, occurrenceIds: Set<String>)

    /** Cancels everything, including undelivered work. Used when the data itself is going away. */
    suspend fun cancel(experimentId: String)
}

fun interface StudyExporter {
    suspend fun export(
        configuration: VerifiedConfiguration,
        metadata: StudyMetadata,
        events: StudyStore,
        destination: OutputStream,
    ): ExportReceipt
}

/**
 * Delivers one encrypted bundle covering `[fromSequence, toSequence]` to the study's endpoint.
 *
 * Implementations receive the same HPKE-encrypted bundle a participant would export by hand, so
 * the endpoint stores ciphertext it cannot read. Returning normally means the endpoint confirmed
 * receipt and the watermark may advance; anything else must throw.
 */
interface StudyUploader {
    /**
     * Recovers the one durable staged bundle before deciding whether another upload is needed.
     * A stage already covered by [StudyMetadata.uploadedThroughSequence] is safe to remove; every
     * other stage must remain byte-for-byte identical for its next request.
     */
    suspend fun reconcile(configuration: VerifiedConfiguration, metadata: StudyMetadata)

    suspend fun upload(
        configuration: VerifiedConfiguration,
        metadata: StudyMetadata,
        events: StudyStore,
        fromSequence: Long,
        toSequence: Long,
    ): ExportReceipt

    /** Prevents a staged-but-not-started request and cancels any request already in flight. */
    suspend fun prepareDeletion()

    /** Removes the durable stage only after the matching upload watermark was persisted. */
    suspend fun acknowledge(bundleId: java.util.UUID)

    /** Removes every staged upload when the participant deletes the study. */
    suspend fun clear()
}

/**
 * Thrown by a [StudyUploader] to explain why delivery failed.
 *
 * Carries a fixed reason code rather than a message, for the same reason collector health does:
 * whatever surfaces here can reach a log or a screen, and must not be able to hold study data.
 */
class StudyUploadException(
    val reasonCode: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(reasonCode, cause) {
    init {
        require(REASON_CODE.matches(reasonCode)) { "Invalid upload reason code" }
    }

    private companion object {
        val REASON_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
    }
}

/** Outcome of the most recent upload attempt, shown to the participant. */
data class UploadStatus(
    val uploadedThroughSequence: Long,
    val pendingCount: Long,
    val lastSuccessAtUtcMillis: Long? = null,
    val lastFailureCode: String? = null,
    val lastFailureRetryable: Boolean? = null,
)

sealed interface UploadAttemptResult {
    data object NoWork : UploadAttemptResult
    data class Confirmed(val receipt: ExportReceipt) : UploadAttemptResult
    data class Failed(val reasonCode: String, val retryable: Boolean) : UploadAttemptResult
}

/** Result of rechecking the immutable participant-start deadline before terminal completion. */
sealed interface DurationCompletionResult {
    data object Completed : DurationCompletionResult

    data class NotDue(val remainingMillis: Long) : DurationCompletionResult {
        init {
            require(remainingMillis > 0) { "An early deadline must retain a positive delay" }
        }
    }

    data object Inactive : DurationCompletionResult
    data class Failed(val commandResult: CommandResult.Failed) : DurationCompletionResult
}

enum class StudyAccessFeature {
    STUDY_NOTIFICATIONS,
}

sealed interface StudyAccessOwner {
    val required: Boolean

    data class Collector(
        val collectorId: String,
        override val required: Boolean,
    ) : StudyAccessOwner {
        init {
            require(collectorId.isNotBlank()) { "Access owner collector ID must not be blank" }
        }
    }

    data class Feature(
        val feature: StudyAccessFeature,
        override val required: Boolean,
    ) : StudyAccessOwner
}

data class StudyAccessPlanItem(
    val requirement: AccessRequirement,
    val owners: Set<StudyAccessOwner>,
) {
    init {
        require(owners.isNotEmpty()) { "Planned access must have at least one owner" }
        require(requirement.required == owners.any(StudyAccessOwner::required)) {
            "Planned access requiredness must match its owners"
        }
    }
}

data class StudyAccessStatus(
    val requirement: AccessRequirement,
    val owners: Set<StudyAccessOwner>,
    val resolution: AccessResolution,
    val guidance: SetupGuidance?,
) {
    val granted: Boolean get() = resolution == AccessResolution.Satisfied
}

data class StudySessionSnapshot(
    val initialized: Boolean = false,
    val configuration: StudyConfiguration? = null,
    val runtime: RuntimeSnapshot = RuntimeSnapshot(),
    val access: List<StudyAccessStatus> = emptyList(),
    /**
     * False when the study's signer was not pinned by this build, so the app cannot vouch for who
     * published it. The consent screen says so rather than letting the researcher name stand alone.
     */
    val signerAnchored: Boolean = false,
    val lastExport: ExportReceipt? = null,
    /** Kept separate from [lastExport] so a background upload never overwrites what the
     *  participant sees for their own export. */
    val upload: UploadStatus? = null,
    /** A durable deletion tombstone exists; collection and upload must never resume. */
    val deletionPending: Boolean = false,
    /** A safety boundary is pending; unreadable marker state is closed and never activates a study. */
    val safetyPauseStatus: SafetyPauseStatus? = null,
    /** Recovery failed closed; ordinary study actions cannot clear this process-lifetime latch. */
    val recoveryBlocked: Boolean = false,
    val incidentCode: String? = null,
)

sealed interface SafetyPauseStatus {
    data class Pending(val reason: SafetyPauseReason) : SafetyPauseStatus
    data object MarkerUnreadable : SafetyPauseStatus
}

class StudyAccessPolicy {
    fun plan(collectorRequirements: List<CollectorAccessRequirement>): List<StudyAccessPlanItem> {
        val ownedRequirements = collectorRequirements.map { entry ->
            OwnedAccessRequirement(
                requirement = entry.requirement,
                owner = StudyAccessOwner.Collector(entry.collectorId, entry.requirement.required),
            )
        } + OwnedAccessRequirement(
            requirement = AccessRequirement(AccessKind.NOTIFICATIONS, required = true),
            owner = StudyAccessOwner.Feature(StudyAccessFeature.STUDY_NOTIFICATIONS, required = true),
        )
        return ownedRequirements
            .groupBy { it.requirement.kind }
            .map { (kind, entries) ->
                val owners = entries.mapTo(mutableSetOf(), OwnedAccessRequirement::owner)
                StudyAccessPlanItem(
                    requirement = AccessRequirement(kind, owners.any(StudyAccessOwner::required)),
                    owners = owners,
                )
            }
    }

    private data class OwnedAccessRequirement(
        val requirement: AccessRequirement,
        val owner: StudyAccessOwner,
    )
}

class StudySessionManager(
    private val activeStudyStore: ActiveStudyStore,
    private val verifier: StudyVerifier,
    private val storeFactory: StudyStoreFactory,
    private val runtimeFactory: ExperimentRuntimeFactory,
    private val collectorRegistry: CollectorRegistry,
    private val accessGateway: StudyAccessGateway,
    private val collectionHost: StudyCollectionHost,
    private val safetyPauseStore: SafetyPauseStore,
    private val workScheduler: StudyWorkScheduler,
    private val exporter: StudyExporter,
    private val uploader: StudyUploader,
    private val accessPolicy: StudyAccessPolicy,
    private val scope: CoroutineScope,
    private val schedulePlanner: InterventionSchedulePlanner = InterventionSchedulePlanner(),
) {
    private val sessionMutex = Mutex()

    /**
     * Serialises uploads against each other without blocking pause or withdraw. Network I/O never
     * runs under [sessionMutex]. Deletion first persists its tombstone, asks the uploader to cancel,
     * then waits here so it cannot erase a stage while request teardown is still using that file.
     */
    private val uploadMutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(StudySessionSnapshot())
    val snapshot: StateFlow<StudySessionSnapshot> = mutableSnapshot.asStateFlow()

    private var runtime: ExperimentRuntime? = null
    private var studyStore: StudyStore? = null
    private var verifiedConfiguration: VerifiedConfiguration? = null
    private var runtimeObservation: Job? = null
    private var deletionPending = false
    private var safetyPauseStatus: SafetyPauseStatus? = null
    private var recoveryBlocked = false
    private var collectionHostStarted = false
    private var collectionHostUsesLocation = false

    suspend fun initialize() = sessionMutex.withLock {
        check(!mutableSnapshot.value.initialized) { "Study session is already initialized" }
        try {
            safetyPauseStatus = try {
                safetyPauseStore.pendingReason()?.let(SafetyPauseStatus::Pending)
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                // An unreadable marker is not evidence that the safety boundary completed.
                safetyPauseStatus = SafetyPauseStatus.MarkerUnreadable
                throw failure
            }
            when (val saved = activeStudyStore.load()) {
                null -> {
                    clearSafetyPauseLocked()
                    mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                }
                is ActiveStudyRecord.Active -> activate(saved.envelopeBytes, persistEnvelope = false, joinLink = null)
                is ActiveStudyRecord.DeletionPending -> {
                    deletionPending = true
                    mutableSnapshot.value = StudySessionSnapshot(
                        deletionPending = true,
                        safetyPauseStatus = safetyPauseStatus,
                    )
                    completePendingDeletion(saved)
                    clearSafetyPauseLocked()
                    mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                }
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            recoveryBlocked = true
            mutableSnapshot.update {
                it.copy(
                    initialized = true,
                    deletionPending = deletionPending,
                    safetyPauseStatus = safetyPauseStatus,
                    recoveryBlocked = true,
                    incidentCode = INCIDENT_STUDY_RECOVERY_FAILED,
                )
            }
        }
    }

    suspend fun importSignedConfiguration(bytes: ByteArray, joinLink: JoinLink? = null) = sessionMutex.withLock {
        check(!recoveryBlocked) {
            "Repair the blocked active-study recovery before importing another study"
        }
        check(runtime == null && !deletionPending) {
            "Finish pending study deletion before importing another"
        }
        try {
            activate(bytes, persistEnvelope = true, joinLink = joinLink)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_STUDY_IMPORT_FAILED) }
            throw failure
        }
    }

    suspend fun reviewStudy(): CommandResult = command(execute = { it.reviewStudy() })
    suspend fun acceptConsent(): CommandResult = command(
        execute = { it.acceptConsent() },
        onSuccess = ::refreshAccessLocked,
    )
    suspend fun completeAccessSetup(): CommandResult = sessionMutex.withLock {
        refreshAccessLocked()
        if (!requiredAccessReady()) {
            return@withLock publish(CommandResult.Failed(INCIDENT_REQUIRED_ACCESS_MISSING))
        }
        publish(requireRuntime().completeAccessSetup(currentGrantedKinds()))
    }

    suspend fun start(): CommandResult = sessionMutex.withLock {
        val current = requireRuntime()
        if (!retrySafetyPauseLocked(current.configuration.experimentId)) {
            return@withLock CommandResult.Failed(INCIDENT_SAFETY_PAUSE_PENDING)
        }
        refreshAccessLocked()
        if (!requiredAccessReady()) {
            return@withLock publish(CommandResult.Failed(INCIDENT_REQUIRED_ACCESS_MISSING))
        }
        val availableAccess = currentGrantedKinds()
        try {
            ensureCollectionHostLocked(current.configuration.title, usesLocation(availableAccess))
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return@withLock publish(CommandResult.Failed(INCIDENT_COLLECTION_HOST_FAILED))
        }
        val result = try {
            current.start(availableAccess)
        } catch (failure: Throwable) {
            return@withLock containCommandActivationFailureLocked(
                current = current,
                failure = failure,
                defaultReason = SafetyPauseReason.COLLECTION_HOST_FAILURE,
                defaultIncidentCode = INCIDENT_COLLECTION_HOST_FAILED,
            )
        }
        if (result != CommandResult.Success) {
            current.snapshot.value.pendingSafetyPauseReason?.let {
                return@withLock containCommandActivationFailureLocked(
                    current = current,
                    failure = CommandActivationFailure(result),
                    defaultReason = it,
                    defaultIncidentCode = it.incidentCode(),
                )
            }
            withContext(NonCancellable) {
                runAllCleanupSteps(
                    { stopCollectionHostLocked() },
                    {
                        workScheduler.cancelInterventionWork(
                            current.configuration.experimentId,
                            occurrenceIds(current),
                        )
                    },
                )
            }
            return@withLock publish(result)
        }
        try {
            ensureCollectionWorkLocked(current)
            syncInterventionsLocked(current)
            publish(result)
        } catch (failure: Throwable) {
            containCommandActivationFailureLocked(
                current = current,
                failure = failure,
                defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
            )
        }
    }

    suspend fun pause(): CommandResult = sessionMutex.withLock {
        val current = requireRuntime()
        current.snapshot.value.pendingSafetyPauseReason?.let { reason ->
            handleRuntimeSafetyPauseRequestLocked(current, reason)
            return@withLock CommandResult.Failed(reason.incidentCode())
        }
        val teardownPrearmed = current.snapshot.value.metadata?.state == ExperimentState.RUNNING
        if (teardownPrearmed) {
            armTeardownSafetyWitnessLocked(current)?.let {
                return@withLock it
            }
        }
        val result = try {
            current.pause()
        } catch (cancellation: CancellationException) {
            if (teardownPrearmed) {
                containPrearmedTeardownCancellationLocked(current, cancellation)
            }
            throw cancellation
        }
        current.snapshot.value.pendingSafetyPauseReason?.let { reason ->
            handleRuntimeSafetyPauseRequestLocked(current, reason)
            return@withLock CommandResult.Failed(reason.incidentCode())
        }
        if (result == CommandResult.Success) {
            finishPrearmedTeardownLocked(current)?.let { return@withLock it }
        }
        publish(result)
    }
    suspend fun resume(): CommandResult = sessionMutex.withLock {
        val current = requireRuntime()
        if (!retrySafetyPauseLocked(current.configuration.experimentId)) {
            return@withLock CommandResult.Failed(INCIDENT_SAFETY_PAUSE_PENDING)
        }
        val lifetime = try {
            studyLifetime(current.configuration, requireNotNull(current.snapshot.value.metadata), current.now())
        } catch (failure: Throwable) {
            return@withLock containCommandActivationFailureLocked(
                current = current,
                failure = failure,
                defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
            )
        }
        if (lifetime.elapsed) {
            return@withLock completeExpiredStudyLocked(current)
        }
        refreshAccessLocked()
        if (!requiredAccessReady()) {
            return@withLock publish(CommandResult.Failed(INCIDENT_REQUIRED_ACCESS_MISSING))
        }
        try {
            // A paused study already carries the immutable participant-start boundary, so repair
            // the deadline/upload/daily work before reopening either the host or a collector.
            ensureCollectionWorkLocked(current)
        } catch (failure: Throwable) {
            return@withLock containCommandActivationFailureLocked(
                current = current,
                failure = failure,
                defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
            )
        }
        val availableAccess = currentGrantedKinds()
        try {
            ensureCollectionHostLocked(current.configuration.title, usesLocation(availableAccess))
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return@withLock publish(CommandResult.Failed(INCIDENT_COLLECTION_HOST_FAILED))
        }
        val result = try {
            current.resume(availableAccess)
        } catch (failure: Throwable) {
            return@withLock containCommandActivationFailureLocked(
                current = current,
                failure = failure,
                defaultReason = SafetyPauseReason.COLLECTION_HOST_FAILURE,
                defaultIncidentCode = INCIDENT_COLLECTION_HOST_FAILED,
            )
        }
        if (result != CommandResult.Success) {
            current.snapshot.value.pendingSafetyPauseReason?.let {
                return@withLock containCommandActivationFailureLocked(
                    current = current,
                    failure = CommandActivationFailure(result),
                    defaultReason = it,
                    defaultIncidentCode = it.incidentCode(),
                )
            }
            withContext(NonCancellable) {
                runAllCleanupSteps(
                    { stopCollectionHostLocked() },
                    {
                        workScheduler.cancelInterventionWork(
                            current.configuration.experimentId,
                            occurrenceIds(current),
                        )
                    },
                )
            }
        } else {
            try {
                syncInterventionsLocked(current)
            } catch (failure: Throwable) {
                return@withLock containCommandActivationFailureLocked(
                    current = current,
                    failure = failure,
                    defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                    defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
                )
            }
        }
        publish(result)
    }

    suspend fun reconcileScheduledWork(recoverStalePosting: Boolean = false) = sessionMutex.withLock {
        runtime?.let { current ->
            val metadata = current.snapshot.value.metadata ?: return@let
            try {
                if (metadata.state in ACTIVE_STUDY_STATES &&
                    studyLifetime(current.configuration, metadata, current.now()).elapsed
                ) {
                    completeExpiredStudyLocked(current)
                } else if (
                    metadata.state == ExperimentState.RUNNING &&
                    current.snapshot.value.incidentCode == null
                ) {
                    ensureCollectionWorkLocked(current)
                    syncInterventionsLocked(current, recoverStalePosting)
                } else if (metadata.state == ExperimentState.PAUSED) {
                    ensureCollectionWorkLocked(current)
                    workScheduler.cancelInterventionWork(
                        current.configuration.experimentId,
                        occurrenceIds(current),
                    )
                } else if (metadata.state in TERMINAL_STATES) {
                    workScheduler.cancelCollectionWork(
                        current.configuration.experimentId,
                        occurrenceIds(current),
                    )
                    if (metadata.hasParticipantStarted()) ensureCollectionWorkLocked(current)
                } else {
                    workScheduler.cancelInterventionWork(
                        current.configuration.experimentId,
                        occurrenceIds(current),
                    )
                }
            } catch (failure: Throwable) {
                if (current.snapshot.value.metadata?.state == ExperimentState.RUNNING) {
                    containRunningSideEffectFailureLocked(current, failure)
                }
                if (current.snapshot.value.metadata?.state == ExperimentState.PAUSED) {
                    containCommandActivationFailureLocked(
                        current = current,
                        failure = failure,
                        defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                        defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
                    )
                    return@let
                }
                throw failure
            }
        }
    }

    suspend fun claimOccurrenceIfDue(occurrenceId: String): OccurrenceClaimResult = sessionMutex.withLock {
        requireRuntime().claimOccurrenceIfDue(occurrenceId)
    }

    suspend fun expireOccurrenceIfDue(occurrenceId: String): OccurrenceExpiryResult = sessionMutex.withLock {
        requireRuntime().expireOccurrenceIfDue(occurrenceId)
    }

    suspend fun markNotificationPosted(occurrenceId: String): Boolean = sessionMutex.withLock {
        requireRuntime().markNotificationPosted(occurrenceId)
    }

    /** Idempotently restores the trigger chain after an occurrence reaches a durable lifecycle state. */
    suspend fun scheduleSuccessor(occurrenceId: String) = sessionMutex.withLock {
        val current = requireRuntime()
        if (current.snapshot.value.metadata?.state == ExperimentState.RUNNING) {
            try {
                scheduleNextLocked(current, occurrenceId)
            } catch (failure: Throwable) {
                containRunningSideEffectFailureLocked(current, failure)
            }
        }
    }

    suspend fun openOccurrence(occurrenceId: String): OccurrenceDispatch? =
        sessionMutex.withLock { requireRuntime().openOccurrence(occurrenceId) }

    suspend fun submitSurvey(
        occurrenceId: String,
        answers: Map<String, SurveyAnswer>,
    ): SurveySubmissionResult = sessionMutex.withLock {
        requireRuntime().submitSurvey(occurrenceId, answers)
    }

    suspend fun surveySubmissionEvent(occurrenceId: String) =
        sessionMutex.withLock { requireRuntime().surveySubmissionEvent(occurrenceId) }

    suspend fun finish(): CommandResult = terminalCommand { it.finishEarly() }

    /**
     * Completes an active study only after proving the exact same-boot monotonic deadline is due.
     *
     * WorkManager delays are advisory and may wake early. Keeping this check inside the session
     * lock prevents an early or stale worker from creating a terminal transition.
     */
    suspend fun completeAfterDurationIfDue(): DurationCompletionResult = sessionMutex.withLock {
        val current = requireRuntime()
        val metadata = requireNotNull(current.snapshot.value.metadata)
        if (metadata.state !in ACTIVE_STUDY_STATES) {
            return@withLock DurationCompletionResult.Inactive
        }
        val lifetime = try {
            studyLifetime(current.configuration, metadata, current.now())
        } catch (failure: Throwable) {
            val result = containCommandActivationFailureLocked(
                current = current,
                failure = failure,
                defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
            )
            return@withLock when (result) {
                CommandResult.Success -> error("Safety containment returned an impossible success result")
                is CommandResult.Failed -> DurationCompletionResult.Failed(result)
            }
        }
        if (!lifetime.elapsed) {
            return@withLock DurationCompletionResult.NotDue(lifetime.remainingMillis)
        }
        when (val result = completeExpiredStudyLocked(current)) {
            CommandResult.Success -> DurationCompletionResult.Completed
            is CommandResult.Failed -> DurationCompletionResult.Failed(result)
        }
    }

    suspend fun withdraw(): CommandResult = terminalCommand { it.withdraw() }

    suspend fun exportTo(destination: OutputStream): ExportReceipt = sessionMutex.withLock {
        val current = requireRuntime()
        val receipt = destination.use {
            exporter.export(
                requireNotNull(verifiedConfiguration),
                current.metadataForExport(),
                requireNotNull(studyStore),
                it,
            )
        }
        mutableSnapshot.update { it.copy(lastExport = receipt, incidentCode = null) }
        receipt
    }

    /**
     * Sends one chunk of undelivered events to the study's endpoint, if it has one.
     *
     * Deliberately not shaped like [exportTo]. The session lock is taken twice, briefly — once to
     * read the range and once to commit the watermark — and the network transfer happens between
     * them under [uploadMutex] only. Holding [sessionMutex] across an HTTP request would block the
     * participant from pausing or withdrawing for as long as the network is unresponsive.
     */
    suspend fun uploadPending(): UploadAttemptResult = uploadMutex.withLock {
        val context = uploadContext() ?: return@withLock UploadAttemptResult.NoWork
        val plan = planUpload() ?: run {
            try {
                // No request will run, but a crash may have left an already-committed stage whose
                // manifest still needs removing.
                uploader.reconcile(context.configuration, context.metadata)
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                return@withLock publishUploadFailure(context.metadata, failure)
            }
            reclaimConfirmedSpace()
            return@withLock UploadAttemptResult.NoWork
        }
        val receipt = try {
            uploader.upload(plan.configuration, plan.metadata, plan.store, plan.from, plan.to)
                .also { validateReceipt(plan, it) }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return@withLock publishUploadFailure(plan.metadata, failure)
        }

        try {
            commitUploadWatermark(plan, receipt)
            uploader.acknowledge(receipt.bundleId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return@withLock publishUploadFailure(
                plan.metadata,
                failure,
                defaultCode = INCIDENT_UPLOAD_COMMIT_FAILED,
                defaultRetryable = true,
            )
        }
        reclaimConfirmedSpace()
        UploadAttemptResult.Confirmed(receipt)
    }

    /**
     * True when the study has ended and everything it collected has been delivered, so scheduled
     * delivery has nothing left to do and can be retired.
     */
    fun uploadDrained(): Boolean {
        val metadata = mutableSnapshot.value.runtime.metadata ?: return false
        return metadata.state in TERMINAL_STATES &&
            metadata.uploadedThroughSequence >= metadata.eventCount
    }

    private suspend fun uploadContext(): UploadContext? = sessionMutex.withLock {
        if (deletionPending) return@withLock null
        val current = runtime ?: return@withLock null
        if (current.configuration.upload == null) return@withLock null
        val metadata = current.snapshot.value.metadata ?: return@withLock null
        UploadContext(requireNotNull(verifiedConfiguration), metadata)
    }

    /** Null when the study does not upload, has no active runtime, or has nothing undelivered. */
    private suspend fun planUpload(): UploadPlan? = sessionMutex.withLock {
        val current = runtime ?: return@withLock null
        if (current.configuration.upload == null) return@withLock null
        // Before RUNNING there is nothing to send, and asking for an export snapshot would throw.
        if (current.snapshot.value.metadata?.state !in UPLOADABLE_STATES) return@withLock null
        val metadata = current.metadataForExport()
        val durable = metadata.nextSequenceNumber - 1
        val from = metadata.uploadedThroughSequence + 1
        if (from > durable) return@withLock null
        // Ask for everything outstanding. How much actually fits is decided while the bundle
        // streams, and comes back in the receipt.
        UploadPlan(
            configuration = requireNotNull(verifiedConfiguration),
            metadata = metadata,
            store = requireNotNull(studyStore),
            from = from,
            to = durable,
        )
    }

    private fun validateReceipt(plan: UploadPlan, receipt: ExportReceipt) {
        require(receipt.configurationSha256 == plan.configuration.configurationSha256) {
            "Upload receipt configuration digest mismatch"
        }
        require(receipt.firstSequence == plan.from) { "Upload receipt range start mismatch" }
        require(receipt.lastSequence in plan.from..plan.to) { "Upload receipt range end mismatch" }
        require(receipt.eventCount == receipt.lastSequence - receipt.firstSequence + 1) {
            "Upload receipt event count mismatch"
        }
        require(receipt.byteCount in 1..MAXIMUM_UPLOAD_BYTES) { "Upload receipt byte count is out of bounds" }
        require(SHA256_HEX.matches(receipt.sha256)) { "Upload receipt digest is invalid" }
    }

    private suspend fun commitUploadWatermark(plan: UploadPlan, receipt: ExportReceipt) = sessionMutex.withLock {
        // The study may have been withdrawn, deleted or replaced while the request was in flight.
        val current = checkNotNull(runtime) { "Study was deleted during upload" }
        check(requireNotNull(verifiedConfiguration).configurationSha256 == plan.configuration.configurationSha256) {
            "Study changed during upload"
        }
        // The receipt, not the plan: a budgeted bundle may have stopped short, and the rest goes
        // out on the next run.
        val updated = current.confirmUploaded(receipt.lastSequence)
        mutableSnapshot.update {
            it.copy(
                upload = UploadStatus(
                    uploadedThroughSequence = updated.uploadedThroughSequence,
                    pendingCount = updated.eventCount - updated.uploadedThroughSequence,
                    lastSuccessAtUtcMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun reclaimConfirmedSpace() = sessionMutex.withLock {
        val current = runtime ?: return@withLock
        try {
            val updated = current.reclaimLocalSpace()
            mutableSnapshot.update { snapshot ->
                val upload = snapshot.upload ?: return@update snapshot
                snapshot.copy(
                    upload = upload.copy(
                        uploadedThroughSequence = updated.uploadedThroughSequence,
                        pendingCount = updated.eventCount - updated.uploadedThroughSequence,
                    ),
                )
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            mutableSnapshot.update { it.copy(incidentCode = INCIDENT_RECLAIM_FAILED) }
        }
    }

    private fun publishUploadFailure(
        metadata: StudyMetadata,
        failure: Throwable,
        defaultCode: String = INCIDENT_UPLOAD_FAILED,
        defaultRetryable: Boolean = false,
    ): UploadAttemptResult.Failed {
        val classified = failure as? StudyUploadException
        val reason = classified?.reasonCode ?: defaultCode
        val retryable = classified?.retryable ?: defaultRetryable
        // An upload failure is not a collection incident. Keep any storage/access incident visible.
        mutableSnapshot.update {
            it.copy(
                upload = it.upload?.copy(
                    lastFailureCode = reason,
                    lastFailureRetryable = retryable,
                ) ?: UploadStatus(
                    uploadedThroughSequence = metadata.uploadedThroughSequence,
                    pendingCount = metadata.eventCount - metadata.uploadedThroughSequence,
                    lastFailureCode = reason,
                    lastFailureRetryable = retryable,
                ),
            )
        }
        return UploadAttemptResult.Failed(reason, retryable)
    }

    private data class UploadContext(
        val configuration: VerifiedConfiguration,
        val metadata: StudyMetadata,
    )

    private class UploadPlan(
        val configuration: VerifiedConfiguration,
        val metadata: StudyMetadata,
        val store: StudyStore,
        val from: Long,
        val to: Long,
    )

    suspend fun deleteLocalData() {
        val deletion = sessionMutex.withLock {
            val current = requireRuntime()
            require(current.snapshot.value.metadata?.state in TERMINAL_STATES) {
                "Withdraw or complete the study before deleting its data"
            }
            val target = ActiveStudyRecord.DeletionPending(
                current.configuration.experimentId,
                current.configuration.maximumLocalBytes,
            )
            activeStudyStore.markDeletionPending(target.experimentId, target.maximumLocalBytes)
            deletionPending = true
            mutableSnapshot.update { it.copy(deletionPending = true) }
            runtimeObservation?.cancel()
            DeletionContext(target, requireNotNull(studyStore))
        }

        // The tombstone is already durable. Quiesce a request without waiting for its full
        // network timeout, then take the session upload lock so watermark handling has finished.
        uploader.prepareDeletion()
        uploadMutex.withLock {
            sessionMutex.withLock {
                completeDeletion(deletion.target, deletion.store)
                runtime = null
                studyStore = null
                verifiedConfiguration = null
                runtimeObservation = null
                deletionPending = false
                mutableSnapshot.value = StudySessionSnapshot(initialized = true)
            }
        }
    }

    private data class DeletionContext(
        val target: ActiveStudyRecord.DeletionPending,
        val store: StudyStore,
    )

    private suspend fun completePendingDeletion(deletion: ActiveStudyRecord.DeletionPending) {
        completeDeletion(
            deletion,
            storeFactory.create(deletion.experimentId, deletion.maximumLocalBytes),
        )
        deletionPending = false
    }

    /**
     * Best-effort all cleanup, while keeping the tombstone unless every step succeeds.
     * This makes each crash/failure point retryable without ever restoring upload capability.
     */
    private suspend fun completeDeletion(
        deletion: ActiveStudyRecord.DeletionPending,
        store: StudyStore,
    ) {
        var firstFailure: Exception? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Exception) {
                failure.rethrowCancellation()
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }

        attempt { stopCollectionHostLocked() }
        attempt { workScheduler.cancel(deletion.experimentId) }
        attempt { uploader.clear() }
        attempt { clearSafetyPauseLocked(deletion.experimentId) }
        attempt { store.clear() }
        if (firstFailure == null) attempt { activeStudyStore.clear() }
        firstFailure?.let { throw it }
    }

    suspend fun reconcileAccess() = sessionMutex.withLock {
        runtime?.let { current ->
            current.snapshot.value.pendingSafetyPauseReason?.let { reason ->
                if (!handleRuntimeSafetyPauseRequestLocked(current, reason)) {
                    return@withLock
                }
            }
        }
        runtime?.let { pending ->
            if (!retrySafetyPauseLocked(pending.configuration.experimentId)) {
                return@withLock
            }
        }
        val current = runtime ?: return@withLock
        val metadata = current.snapshot.value.metadata
        if (metadata != null && metadata.state in ACTIVE_STUDY_STATES) {
            val lifetime = try {
                studyLifetime(current.configuration, metadata, current.now())
            } catch (failure: Throwable) {
                containCommandActivationFailureLocked(
                    current = current,
                    failure = failure,
                    defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                    defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
                )
                return@withLock
            }
            if (lifetime.elapsed) {
                completeExpiredStudyLocked(current)
                return@withLock
            }
        }
        try {
            refreshAccessLocked()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            runtime?.takeIf { current ->
                current.snapshot.value.metadata?.state == ExperimentState.RUNNING
            }?.let { current ->
                pauseForAccessLossLocked(current, INCIDENT_ACCESS_INSPECTION_FAILED)
            } ?: publish(CommandResult.Failed(INCIDENT_ACCESS_INSPECTION_FAILED))
            throw failure
        }
        if (requiredAccessReady()) {
            if (
                current.snapshot.value.metadata?.state == ExperimentState.RUNNING &&
                current.snapshot.value.incidentCode == null
            ) {
                val result = reconcileRunningCollectorsLocked(current)
                if (result != CommandResult.Success) {
                    publish(result)
                    return@withLock
                }
            }
            mutableSnapshot.update { snapshot ->
                if (snapshot.incidentCode == INCIDENT_REQUIRED_ACCESS_MISSING) {
                    snapshot.copy(incidentCode = null)
                } else {
                    snapshot
                }
            }
            return@withLock
        }
        if (current.snapshot.value.metadata?.state == ExperimentState.RUNNING) {
            pauseForAccessLossLocked(current, INCIDENT_REQUIRED_ACCESS_MISSING)
        }
    }

    /**
     * Revalidates a service intent that Android redelivered from a prior service/process lifetime.
     * The service remains on a neutral restoration notification until this returns true.
     */
    suspend fun reconcileRedeliveredCollectionHost(): Boolean = sessionMutex.withLock {
        collectionHostStarted = false
        collectionHostUsesLocation = false
        val current = runtime ?: return@withLock false
        if (current.snapshot.value.metadata?.state != ExperimentState.RUNNING) return@withLock false
        try {
            refreshAccessLocked()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            pauseForAccessLossLocked(current, INCIDENT_ACCESS_INSPECTION_FAILED)
            return@withLock false
        }
        if (!requiredAccessReady()) {
            pauseForAccessLossLocked(current, INCIDENT_REQUIRED_ACCESS_MISSING)
            return@withLock false
        }
        val result = reconcileRunningCollectorsLocked(current)
        publish(result)
        result == CommandResult.Success
    }

    private suspend fun reconcileRunningCollectorsLocked(current: ExperimentRuntime): CommandResult {
        val availableAccess = currentGrantedKinds()
        val needsLocationHost = usesLocation(availableAccess)
        if (needsLocationHost) {
            try {
                ensureCollectionHostLocked(current.configuration.title, usesLocation = true)
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    containHostReconciliationCancellationLocked(current, failure)
                }
                // Keep every location collector behind its closed access boundary while allowing
                // unrelated optional collectors to reconcile normally.
                current.reconcileCollectorAccess(availableAccess - LOCATION_COLLECTION_ACCESS)
                try {
                    ensureCollectionHostLocked(current.configuration.title, usesLocation = false)
                } catch (recoveryFailure: Throwable) {
                    if (recoveryFailure is CancellationException) {
                        containHostReconciliationCancellationLocked(current, recoveryFailure)
                    }
                    failure.addSuppressed(recoveryFailure)
                    return pauseForSafetyFailureLocked(
                        current = current,
                        reason = SafetyPauseReason.COLLECTION_HOST_FAILURE,
                        incidentCode = INCIDENT_COLLECTION_HOST_FAILED,
                    )
                }
                return CommandResult.Failed(INCIDENT_COLLECTION_HOST_FAILED)
            }
        }

        val result = current.reconcileCollectorAccess(availableAccess)
        if (result != CommandResult.Success) return result

        if (!needsLocationHost) {
            try {
                // Location collectors have already been paused and gated before the platform type
                // is removed. This also restores a non-location host after service redelivery.
                ensureCollectionHostLocked(current.configuration.title, usesLocation = false)
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    containHostReconciliationCancellationLocked(current, failure)
                }
                return pauseForSafetyFailureLocked(
                    current = current,
                    reason = SafetyPauseReason.COLLECTION_HOST_FAILURE,
                    incidentCode = INCIDENT_COLLECTION_HOST_FAILED,
                )
            }
        }
        return CommandResult.Success
    }

    private suspend fun containHostReconciliationCancellationLocked(
        current: ExperimentRuntime,
        cancellation: CancellationException,
    ): Nothing {
        try {
            withContext(NonCancellable) {
                establishSafetyPauseLocked(
                    current = current,
                    reason = SafetyPauseReason.COLLECTION_HOST_FAILURE,
                    incidentCode = INCIDENT_COLLECTION_HOST_FAILED,
                )
            }
        } catch (containmentFailure: Throwable) {
            cancellation.addSuppressed(containmentFailure)
        }
        throw cancellation
    }

    private suspend fun pauseForAccessLossLocked(
        current: ExperimentRuntime,
        incidentCode: String,
    ): CommandResult = pauseForSafetyFailureLocked(
        current = current,
        reason = SafetyPauseReason.REQUIRED_ACCESS_MISSING,
        incidentCode = incidentCode,
    )

    private suspend fun pauseForSafetyFailureLocked(
        current: ExperimentRuntime,
        reason: SafetyPauseReason,
        incidentCode: String,
    ): CommandResult = establishSafetyPauseLocked(current, reason, incidentCode).commandResult

    private data class SafetyPauseProtocolResult(
        val commandResult: CommandResult,
        val durableHandoff: Boolean,
        val effectiveReason: SafetyPauseReason,
        val requestAcknowledged: Boolean,
    )

    private suspend fun establishSafetyPauseLocked(
        current: ExperimentRuntime,
        reason: SafetyPauseReason,
        incidentCode: String,
    ): SafetyPauseProtocolResult {
        val callerContext = currentCoroutineContext()
        val result = withContext(NonCancellable) {
            establishSafetyPauseNonCancellable(current, reason, incidentCode)
        }
        callerContext.ensureActive()
        return result
    }

    private suspend fun establishSafetyPauseNonCancellable(
        current: ExperimentRuntime,
        reason: SafetyPauseReason,
        incidentCode: String,
    ): SafetyPauseProtocolResult {
        val closedReason = current.closeAdmissionForSafetyFailure(reason)
        val effectiveReason = current.snapshot.value.pendingSafetyPauseReason ?: closedReason
        if (effectiveReason == null) {
            return SafetyPauseProtocolResult(
                commandResult = publish(CommandResult.Failed(INCIDENT_COMMAND_REJECTED)),
                durableHandoff = false,
                effectiveReason = reason,
                requestAcknowledged = false,
            )
        }
        var markerFailure = false
        try {
            markSafetyPauseLocked(effectiveReason)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            markerFailure = true
        }
        val result = when (current.snapshot.value.metadata?.state) {
            ExperimentState.RUNNING -> current.pauseForSafetyFailure(effectiveReason)
            ExperimentState.READY,
            ExperimentState.PAUSED,
            -> current.retrySafetyPause(effectiveReason)
            else -> CommandResult.Failed(INCIDENT_COMMAND_REJECTED)
        }
        val cleanupFailed = cleanupAfterSafetyPauseLocked(current)
        var markerCleanupFailure = false
        if (result == CommandResult.Success && !cleanupFailed) {
            try {
                clearSafetyPauseLocked()
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                markerCleanupFailure = true
            }
        }
        var retrySchedulingFailure = false
        var retryScheduled = false
        if (result != CommandResult.Success || cleanupFailed || markerCleanupFailure) {
            try {
                workScheduler.scheduleSafetyPauseRetry(current.configuration.experimentId, effectiveReason)
                retryScheduled = true
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                retrySchedulingFailure = true
            }
        }
        val commandResult = publish(
            when {
                retrySchedulingFailure -> CommandResult.Failed(INCIDENT_SAFETY_PAUSE_RETRY_SCHEDULING_FAILED)
                result != CommandResult.Success -> result
                markerFailure -> CommandResult.Failed(INCIDENT_SAFETY_PAUSE_MARKER_FAILED)
                markerCleanupFailure -> CommandResult.Failed(INCIDENT_SAFETY_PAUSE_MARKER_CLEAR_FAILED)
                cleanupFailed -> CommandResult.Failed(INCIDENT_SAFETY_PAUSE_SHUTDOWN_FAILED)
                else -> CommandResult.Failed(
                    if (effectiveReason == reason) incidentCode else effectiveReason.incidentCode(),
                )
            },
        )
        val durableHandoff = (result == CommandResult.Success && !cleanupFailed) || retryScheduled
        val requestAcknowledged = durableHandoff &&
            current.acknowledgeSafetyPauseRequest(effectiveReason)
        return SafetyPauseProtocolResult(
            commandResult = commandResult,
            durableHandoff = durableHandoff,
            effectiveReason = effectiveReason,
            requestAcknowledged = requestAcknowledged,
        )
    }

    /**
     * Completes a runtime-owned typed request under the session lock.
     *
     * Returning false leaves the runtime signal intact. The observer retries without relying on a
     * presentation incident, while every admission boundary remains closed.
     */
    private suspend fun handleRuntimeSafetyPauseRequestLocked(
        current: ExperimentRuntime,
        reason: SafetyPauseReason,
    ): Boolean {
        if (runtime !== current || current.snapshot.value.pendingSafetyPauseReason != reason) return true
        val metadataState = current.snapshot.value.metadata?.state
        return when (metadataState) {
            ExperimentState.RUNNING -> establishSafetyPauseLocked(
                current = current,
                reason = reason,
                incidentCode = reason.incidentCode(),
            ).requestAcknowledged

            ExperimentState.PAUSED -> {
                if (retrySafetyPauseLocked(current.configuration.experimentId, reason)) {
                    true
                } else {
                    try {
                        workScheduler.scheduleSafetyPauseRetry(current.configuration.experimentId, reason)
                        current.acknowledgeSafetyPauseRequest(reason)
                    } catch (failure: Throwable) {
                        failure.rethrowCancellation()
                        publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_RETRY_SCHEDULING_FAILED))
                        false
                    }
                }
            }

            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN -> false

            else -> false
        }
    }

    private suspend fun processRuntimeSafetyPauseRequest(
        current: ExperimentRuntime,
        reason: SafetyPauseReason,
    ) {
        while (true) {
            val completed = sessionMutex.withLock {
                handleRuntimeSafetyPauseRequestLocked(current, reason)
            }
            if (completed) return
            delay(RUNTIME_SAFETY_PAUSE_RETRY_DELAY_MILLIS)
        }
    }

    /** WorkManager entry point; returns false only when the typed safety pause must be retried. */
    suspend fun retrySafetyPause(
        experimentId: String,
        expectedReason: SafetyPauseReason,
    ): Boolean = sessionMutex.withLock {
        retrySafetyPauseLocked(experimentId, expectedReason)
    }

    private suspend fun retrySafetyPauseLocked(
        experimentId: String,
        expectedReason: SafetyPauseReason? = (safetyPauseStatus as? SafetyPauseStatus.Pending)?.reason,
    ): Boolean {
        val current = runtime ?: return true
        if (current.configuration.experimentId != experimentId) return true
        val durableReason = try {
            safetyPauseStore.pendingReason()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            safetyPauseStatus = SafetyPauseStatus.MarkerUnreadable
            mutableSnapshot.update {
                it.copy(
                    safetyPauseStatus = SafetyPauseStatus.MarkerUnreadable,
                    incidentCode = INCIDENT_SAFETY_PAUSE_MARKER_UNREADABLE,
                )
            }
            return false
        }
        val workReason = try {
            workScheduler.pendingSafetyPauseReason(experimentId)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_RETRY_INSPECTION_FAILED))
            return false
        }
        val distinctReasons = setOfNotNull(durableReason, workReason, expectedReason)
        if (distinctReasons.size > 1) {
            publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_REASON_CONFLICT))
            return false
        }
        val reason = distinctReasons.singleOrNull()
        if (reason == null) {
            if (current.hasPendingSafetyPause()) {
                publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_MARKER_UNREADABLE))
                return false
            }
            safetyPauseStatus = null
            mutableSnapshot.update { it.copy(safetyPauseStatus = null) }
            return true
        }
        safetyPauseStatus = SafetyPauseStatus.Pending(reason)
        mutableSnapshot.update { it.copy(safetyPauseStatus = safetyPauseStatus) }

        val metadata = current.snapshot.value.metadata
        val runtimeLatched = metadata?.state in setOf(
            ExperimentState.READY,
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
        )
        val result = when {
            runtimeLatched ->
                current.retrySafetyPause(reason)
            else -> CommandResult.Success
        }
        if (result != CommandResult.Success) {
            publish(result)
            return false
        }

        val cleanupFailed = cleanupAfterSafetyPauseLocked(current)
        if (cleanupFailed) {
            publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_SHUTDOWN_FAILED))
            return false
        }
        try {
            clearSafetyPauseLocked()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_MARKER_CLEAR_FAILED))
            return false
        }
        if (runtimeLatched) {
            check(current.acknowledgeSafetyPauseRequest(reason)) {
                "Durable safety retry did not clear its matching runtime latch"
            }
        }
        publish(CommandResult.Failed(reason.incidentCode()))
        return true
    }

    private suspend fun cleanupAfterSafetyPauseLocked(current: ExperimentRuntime): Boolean {
        var cleanupFailed = false
        try {
            stopCollectionHostLocked()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            cleanupFailed = true
        }
        try {
            if (current.snapshot.value.metadata?.state in TERMINAL_STATES) {
                workScheduler.cancelCollectionWork(
                    current.configuration.experimentId,
                    occurrenceIds(current),
                )
            } else {
                workScheduler.cancelInterventionWork(
                    current.configuration.experimentId,
                    occurrenceIds(current),
                )
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            cleanupFailed = true
        }
        return cleanupFailed
    }

    private suspend fun refreshAccessLocked() {
        val configuration = mutableSnapshot.value.configuration ?: return
        val plan = accessPlan(configuration)
        mutableSnapshot.update {
            it.copy(access = inspectAccess(configuration, plan))
        }
    }

    private suspend fun activate(
        envelopeBytes: ByteArray,
        persistEnvelope: Boolean,
        joinLink: JoinLink?,
    ) {
        joinLink?.let { expected ->
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(envelopeBytes)
                .joinToString("") { "%02x".format(it) }
            require(actual == expected.artifactSha256) { "Join artifact digest mismatch" }
        }
        val verified = verifier.verify(envelopeBytes)
        joinLink?.let { expected ->
            require(verified.configuration.signer.fingerprint == expected.displayFingerprint()) {
                "Join signer fingerprint mismatch"
            }
        }
        val configuration = verified.configuration
        val markerReason = (safetyPauseStatus as? SafetyPauseStatus.Pending)?.reason
        val workReason = workScheduler.pendingSafetyPauseReason(configuration.experimentId)
        check(markerReason == null || workReason == null || markerReason == workReason) {
            "Safety-pause marker and active retry have conflicting reasons"
        }
        val recoveredPendingReason = markerReason ?: workReason
        if (recoveredPendingReason != null) {
            safetyPauseStatus = SafetyPauseStatus.Pending(recoveredPendingReason)
        }
        configuration.collectors.forEach(collectorRegistry::pluginFor)
        val plan = accessPlan(configuration)
        val inspectionRequest = accessInspectionRequest(configuration, plan)
        val access = inspectAccess(plan, inspectionRequest)
        val createdStore = storeFactory.create(configuration.experimentId, configuration.maximumLocalBytes)
        var createdRuntime: ExperimentRuntime? = null
        var recoveredSafetyPause: SafetyPauseReason? = null
        try {
            val created = runtimeFactory.create(
                configuration,
                createdStore,
                SafetyPauseWitness { reason ->
                    persistRuntimeSafetyWitness(configuration.experimentId, reason)
                },
            )
            createdRuntime = created
            check(created.initialize(recoveredPendingReason) == CommandResult.Success) {
                "Runtime initialization failed"
            }
            if (persistEnvelope) activeStudyStore.save(envelopeBytes)
            val recoveredState = created.snapshot.value.metadata?.state
            val pendingReason = recoveredPendingReason
            var recoveredRunningAccess: Set<AccessKind>? = null
            if (recoveredState == ExperimentState.RUNNING) {
                val availableAccess = access.filter(StudyAccessStatus::granted)
                    .mapTo(mutableSetOf()) { it.requirement.kind }
                val requiredAccessMissing = access.any { it.requirement.required && !it.granted }
                val reason = pendingReason ?: SafetyPauseReason.REQUIRED_ACCESS_MISSING.takeIf {
                    requiredAccessMissing
                }
                if (reason != null) {
                    if (pendingReason == null) {
                        safetyPauseStatus = SafetyPauseStatus.Pending(reason)
                        persistRuntimeSafetyWitness(configuration.experimentId, reason)
                    }
                    check(created.pauseRecoveredForSafetyFailure(reason) == CommandResult.Success) {
                        "Recovered running study could not enter its typed safety pause"
                    }
                    check(!cleanupAfterSafetyPauseLocked(created)) {
                        "Recovered safety pause cleanup did not complete"
                    }
                    check(created.acknowledgeSafetyPauseRequest(reason)) {
                        "Recovered safety pause did not clear its in-memory typed latch"
                    }
                    recoveredSafetyPause = reason
                    clearSafetyPauseLocked(configuration.experimentId)
                } else {
                    recoveredRunningAccess = availableAccess
                }
            } else if (pendingReason != null) {
                if (recoveredState in setOf(ExperimentState.READY, ExperimentState.PAUSED)) {
                    check(created.retrySafetyPause(pendingReason) == CommandResult.Success) {
                        "Recovered safe study did not acknowledge its typed safety marker"
                    }
                }
                check(!cleanupAfterSafetyPauseLocked(created)) {
                    "Recovered safety pause cleanup did not complete"
                }
                if (recoveredState in setOf(ExperimentState.READY, ExperimentState.PAUSED)) {
                    check(created.acknowledgeSafetyPauseRequest(pendingReason)) {
                        "Recovered safe study did not clear its in-memory typed latch"
                    }
                }
                recoveredSafetyPause = pendingReason
                clearSafetyPauseLocked(configuration.experimentId)
            }
            var recoveredMetadata = requireNotNull(created.snapshot.value.metadata)
            if (recoveredMetadata.state in ACTIVE_STUDY_STATES) {
                val lifetime = try {
                    studyLifetime(configuration, recoveredMetadata, created.now())
                } catch (failure: Throwable) {
                    containCommandActivationFailureLocked(
                        current = created,
                        failure = failure,
                        defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                        defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
                    )
                    recoveredRunningAccess = null
                    recoveredSafetyPause = SafetyPauseReason.WORK_SCHEDULING_FAILURE
                    recoveredMetadata = requireNotNull(created.snapshot.value.metadata)
                    null
                }
                if (lifetime?.elapsed == true) {
                    check(completeExpiredStudyLocked(created) == CommandResult.Success) {
                        "Expired recovered study could not complete"
                    }
                    recoveredRunningAccess = null
                    recoveredSafetyPause = null
                    recoveredMetadata = requireNotNull(created.snapshot.value.metadata)
                    workScheduler.ensureCollectionWork(configuration, recoveredMetadata, created.now())
                } else if (lifetime != null) {
                    try {
                        workScheduler.ensureCollectionWork(configuration, recoveredMetadata, created.now())
                        if (recoveredMetadata.state == ExperimentState.PAUSED) {
                            workScheduler.cancelInterventionWork(
                                configuration.experimentId,
                                recoveredMetadata.occurrences.keys,
                            )
                        }
                    } catch (failure: Throwable) {
                        if (recoveredMetadata.state != ExperimentState.RUNNING) throw failure
                        containCommandActivationFailureLocked(
                            current = created,
                            failure = failure,
                            defaultReason = SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                            defaultIncidentCode = INCIDENT_WORK_SCHEDULING_FAILED,
                        )
                        recoveredRunningAccess = null
                        recoveredSafetyPause = SafetyPauseReason.WORK_SCHEDULING_FAILURE
                    }
                }
            } else if (recoveredMetadata.hasParticipantStarted()) {
                // A crash can commit COMPLETED/WITHDRAWN before platform cleanup. Retire every
                // collection/deadline/reminder/intervention side effect, then KEEP-repair only the
                // encrypted upload tail still owed to the researcher.
                workScheduler.cancelCollectionWork(
                    configuration.experimentId,
                    recoveredMetadata.occurrences.keys,
                )
                workScheduler.ensureCollectionWork(configuration, recoveredMetadata, created.now())
            }
            recoveredRunningAccess?.let { availableAccess ->
                try {
                    ensureCollectionHostLocked(configuration.title, usesLocation(availableAccess))
                    check(created.activateRecoveredRunning(availableAccess) == CommandResult.Success) {
                        "Recovered running study could not reactivate collectors"
                    }
                } catch (failure: Throwable) {
                    containCommandActivationFailureLocked(
                        current = created,
                        failure = failure,
                        defaultReason = SafetyPauseReason.COLLECTION_HOST_FAILURE,
                        defaultIncidentCode = INCIDENT_COLLECTION_HOST_FAILED,
                    )
                    recoveredSafetyPause = SafetyPauseReason.COLLECTION_HOST_FAILURE
                }
            }
            runtime = created
            studyStore = createdStore
            verifiedConfiguration = verified
            mutableSnapshot.value = StudySessionSnapshot(
                initialized = true,
                configuration = configuration,
                runtime = created.snapshot.value,
                access = access,
                signerAnchored = verified.signerAnchored,
                safetyPauseStatus = safetyPauseStatus,
                incidentCode = recoveredSafetyPause?.incidentCode(),
            )
            runtimeObservation?.cancel()
            runtimeObservation = scope.launch {
                created.snapshot.collect { runtimeSnapshot ->
                    mutableSnapshot.update { current ->
                        if (runtime === created) current.copy(runtime = runtimeSnapshot) else current
                    }
                    runtimeSnapshot.pendingSafetyPauseReason?.let { reason ->
                        processRuntimeSafetyPauseRequest(created, reason)
                    }
                }
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            createdRuntime?.let { created -> suppressCleanupFailure(failure) { created.shutdown() } }
            if (collectionHostStarted) suppressCleanupFailure(failure) { stopCollectionHostLocked() }
            if (persistEnvelope) {
                suppressCleanupFailure(failure, createdStore::clear)
                suppressCleanupFailure(failure, activeStudyStore::clear)
            }
            throw failure
        }
    }

    private suspend fun command(
        execute: suspend (ExperimentRuntime) -> CommandResult,
        onSuccess: suspend () -> Unit = {},
    ): CommandResult = sessionMutex.withLock {
        val result = execute(requireRuntime())
        if (result == CommandResult.Success) onSuccess()
        publish(result)
    }

    private suspend fun syncInterventionsLocked(
        current: ExperimentRuntime,
        recoverStalePosting: Boolean = false,
    ) {
        val metadata = current.snapshot.value.metadata ?: return
        if (metadata.state != ExperimentState.RUNNING) return
        // External side-effect cleanup comes before planning or durable writes. A quota/storage
        // failure while ensuring another occurrence must not leave a crash-stale notification.
        workScheduler.cancelInterventionNotifications(
            metadata.occurrences.values
                .filter {
                    it.state in NON_VISIBLE_OCCURRENCE_STATES ||
                        (recoverStalePosting && it.state == OccurrenceState.POSTING)
                }
                .mapTo(mutableSetOf()) { it.occurrenceId },
        )
        val deliveries = schedulePlanner.next(
            current.configuration,
            metadata,
            current.now(),
            java.time.ZoneId.systemDefault(),
        ).map { current.ensureOccurrence(it) }
        val surveyInterventionIds = current.configuration.interventions
            .filter { it.action is SurveyAction }
            .mapTo(mutableSetOf()) { it.id }
        val expiries = current.snapshot.value.metadata?.occurrences?.values
            ?.filter { occurrence ->
                occurrence.state in EXPIRABLE_UNOPENED_OCCURRENCE_STATES ||
                    (occurrence.state == OccurrenceState.OPENED && occurrence.interventionId in surveyInterventionIds)
            }
            .orEmpty()
        workScheduler.replaceInterventionWork(current.configuration, deliveries, expiries)
    }

    private suspend fun containRunningSideEffectFailureLocked(
        current: ExperimentRuntime,
        failure: Throwable,
    ): Nothing {
        val pendingReason = current.snapshot.value.pendingSafetyPauseReason
        try {
            withContext(NonCancellable) {
                establishSafetyPauseLocked(
                    current = current,
                    reason = pendingReason ?: SafetyPauseReason.WORK_SCHEDULING_FAILURE,
                    incidentCode = pendingReason?.incidentCode() ?: INCIDENT_WORK_SCHEDULING_FAILED,
                )
            }
        } catch (containmentFailure: Throwable) {
            failure.addSuppressed(containmentFailure)
        }
        throw failure
    }

    /**
     * Arms a process-death witness before any participant pause or terminal source teardown.
     *
     * This deliberately does not latch the runtime reason: when the command completes normally,
     * its durable transition remains PARTICIPANT_PAUSED or the requested terminal reason. A marker
     * write is sufficient; acknowledged WorkManager persistence is the closed fallback.
     */
    private suspend fun armTeardownSafetyWitnessLocked(current: ExperimentRuntime): CommandResult? {
        val callerContext = currentCoroutineContext()
        val failure = withContext(NonCancellable) {
            try {
                persistRuntimeSafetyWitness(
                    current.configuration.experimentId,
                    SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                )
                safetyPauseStatus = SafetyPauseStatus.Pending(
                    SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                )
                mutableSnapshot.update { it.copy(safetyPauseStatus = safetyPauseStatus) }
                null
            } catch (caught: Throwable) {
                caught
            }
        }
        if (failure != null) {
            callerContext.ensureActive()
            failure.rethrowCancellation()
            return publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_RETRY_SCHEDULING_FAILED))
        }
        try {
            callerContext.ensureActive()
        } catch (cancellation: CancellationException) {
            containPrearmedTeardownCancellationLocked(current, cancellation)
        }
        return null
    }

    private suspend fun containPrearmedTeardownCancellationLocked(
        current: ExperimentRuntime,
        cancellation: CancellationException,
    ): Nothing {
        try {
            withContext(NonCancellable) {
                establishSafetyPauseLocked(
                    current = current,
                    reason = current.snapshot.value.pendingSafetyPauseReason
                        ?: SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                    incidentCode = current.snapshot.value.pendingSafetyPauseReason?.incidentCode()
                        ?: INCIDENT_COLLECTION_TEARDOWN_FAILED,
                )
            }
        } catch (containmentFailure: Throwable) {
            cancellation.addSuppressed(containmentFailure)
        }
        throw cancellation
    }

    /**
     * Finishes cleanup after a pre-armed teardown and retires its witness only after every
     * platform mutation is acknowledged. A failed cleanup receives an autonomous typed retry.
     */
    private suspend fun finishPrearmedTeardownLocked(
        current: ExperimentRuntime,
        cancelCollectionWork: Boolean = false,
    ): CommandResult? = withContext(NonCancellable) {
        val cleanupFailure = try {
            runAllCleanupSteps(
                { stopCollectionHostLocked() },
                {
                    if (cancelCollectionWork) {
                        // Terminal collection work retires; upload work intentionally survives.
                        workScheduler.cancelCollectionWork(
                            current.configuration.experimentId,
                            occurrenceIds(current),
                        )
                    } else {
                        workScheduler.cancelInterventionWork(
                            current.configuration.experimentId,
                            occurrenceIds(current),
                        )
                    }
                },
            )
            null
        } catch (failure: Throwable) {
            failure
        }
        if (cleanupFailure != null) {
            return@withContext try {
                workScheduler.scheduleSafetyPauseRetry(
                    current.configuration.experimentId,
                    SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                )
                publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_SHUTDOWN_FAILED))
            } catch (schedulingFailure: Throwable) {
                cleanupFailure.addSuppressed(schedulingFailure)
                publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_RETRY_SCHEDULING_FAILED))
            }
        }
        try {
            // Marker first, then awaited retry cancellation: either side of a crash retains a
            // witness until the durable participant/terminal boundary and cleanup are complete.
            clearSafetyPauseLocked(current.configuration.experimentId)
            null
        } catch (failure: Throwable) {
            publish(CommandResult.Failed(INCIDENT_SAFETY_PAUSE_MARKER_CLEAR_FAILED))
        }
    }

    private suspend fun containCommandActivationFailureLocked(
        current: ExperimentRuntime,
        failure: Throwable,
        defaultReason: SafetyPauseReason,
        defaultIncidentCode: String,
    ): CommandResult {
        val pendingReason = current.snapshot.value.pendingSafetyPauseReason
        var protocol: SafetyPauseProtocolResult? = null
        var containmentFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                protocol = establishSafetyPauseLocked(
                    current = current,
                    reason = pendingReason ?: defaultReason,
                    incidentCode = pendingReason?.incidentCode() ?: defaultIncidentCode,
                )
            } catch (caught: Throwable) {
                containmentFailure = caught
            }
        }
        containmentFailure?.let(failure::addSuppressed)
        failure.rethrowCancellation()
        return protocol?.commandResult ?: throw failure
    }

    private class CommandActivationFailure(result: CommandResult) :
        IllegalStateException("Runtime activation failed: $result")

    private suspend fun ensureCollectionWorkLocked(current: ExperimentRuntime) {
        workScheduler.ensureCollectionWork(
            configuration = current.configuration,
            metadata = requireNotNull(current.snapshot.value.metadata),
            observedAt = current.now(),
        )
    }

    private suspend fun scheduleNextLocked(current: ExperimentRuntime, completedOccurrenceId: String) {
        val triggerId = current.snapshot.value.metadata?.occurrences?.get(completedOccurrenceId)?.triggerId ?: return
        val metadata = current.snapshot.value.metadata ?: return
        schedulePlanner.next(
            current.configuration,
            metadata,
            current.now(),
            java.time.ZoneId.systemDefault(),
            triggerId,
        ).map { current.ensureOccurrence(it) }
            .forEach { workScheduler.enqueueOccurrence(current.configuration, it) }
    }

    private suspend fun terminalCommand(
        execute: suspend (ExperimentRuntime) -> CommandResult,
    ): CommandResult = sessionMutex.withLock {
        val current = requireRuntime()
        executeTerminalCommandLocked(current, execute)
    }

    private suspend fun completeExpiredStudyLocked(current: ExperimentRuntime): CommandResult =
        executeTerminalCommandLocked(current) { it.completeAfterDuration() }

    private suspend fun executeTerminalCommandLocked(
        current: ExperimentRuntime,
        execute: suspend (ExperimentRuntime) -> CommandResult,
    ): CommandResult {
        val prearmed = current.snapshot.value.metadata?.state in ACTIVE_STUDY_STATES
        if (prearmed) {
            armTeardownSafetyWitnessLocked(current)?.let { return it }
        }
        val result = try {
            execute(current)
        } catch (cancellation: CancellationException) {
            if (prearmed) containPrearmedTeardownCancellationLocked(current, cancellation)
            throw cancellation
        }
        return finishTerminalCommandLocked(current, result, prearmed)
    }

    private suspend fun finishTerminalCommandLocked(
        current: ExperimentRuntime,
        result: CommandResult,
        prearmed: Boolean,
    ): CommandResult {
        current.snapshot.value.pendingSafetyPauseReason?.let { reason ->
            handleRuntimeSafetyPauseRequestLocked(current, reason)
            return CommandResult.Failed(reason.incidentCode())
        }
        if (result == CommandResult.Success) {
            if (prearmed) {
                finishPrearmedTeardownLocked(
                    current = current,
                    cancelCollectionWork = true,
                )?.let { return it }
            } else {
                withContext(NonCancellable) {
                    runAllCleanupSteps(
                        { stopCollectionHostLocked() },
                        {
                            // Not cancel(): the study is over, but its undelivered tail is not.
                            workScheduler.cancelCollectionWork(
                                current.configuration.experimentId,
                                occurrenceIds(current),
                            )
                        },
                        { clearSafetyPauseLocked(current.configuration.experimentId) },
                    )
                }
            }
        }
        return publish(result)
    }

    private suspend fun runAllCleanupSteps(vararg steps: suspend () -> Unit) {
        var firstFailure: Throwable? = null
        steps.forEach { step ->
            try {
                step()
            } catch (failure: Throwable) {
                val existing = firstFailure
                if (existing == null) firstFailure = failure else existing.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun occurrenceIds(current: ExperimentRuntime): Set<String> =
        current.snapshot.value.metadata?.occurrences?.keys.orEmpty()

    private fun StudyMetadata.hasParticipantStarted(): Boolean =
        transitions.any { it.reason == cool.jacoblin.particeps.core.model.TransitionReason.PARTICIPANT_STARTED }

    private fun SafetyPauseReason.incidentCode(): String = when (this) {
        SafetyPauseReason.REQUIRED_ACCESS_MISSING -> INCIDENT_REQUIRED_ACCESS_MISSING
        SafetyPauseReason.COLLECTION_HOST_FAILURE -> INCIDENT_COLLECTION_HOST_FAILED
        SafetyPauseReason.WORK_SCHEDULING_FAILURE -> INCIDENT_WORK_SCHEDULING_FAILED
        SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE -> INCIDENT_COLLECTION_TEARDOWN_FAILED
        SafetyPauseReason.STORAGE_FAILURE -> INCIDENT_STORAGE_WRITE_FAILED
    }

    private fun accessPlan(configuration: StudyConfiguration): List<StudyAccessPlanItem> =
        accessPolicy.plan(collectorRegistry.accessRequirements(configuration.collectors))

    private fun requirements(plan: List<StudyAccessPlanItem>): Set<AccessRequirement> =
        plan.mapTo(mutableSetOf(), StudyAccessPlanItem::requirement)

    private fun accessInspectionRequest(
        configuration: StudyConfiguration,
        plan: List<StudyAccessPlanItem>,
    ): AccessInspectionRequest {
        val requirements = requirements(plan)
        val requestedKinds = requirements.mapTo(mutableSetOf(), AccessRequirement::kind)
        val locationProfile = if (AccessKind.LOCATION_SERVICES in requestedKinds) {
            val location = configuration.collectors.filterIsInstance<LocationConfiguration>().single()
            LocationAccessProfile.from(location)
        } else {
            null
        }
        val notificationFeatures = if (AccessKind.NOTIFICATIONS in requestedKinds) {
            buildSet {
                add(NotificationAccessFeature.COLLECTION)
                add(NotificationAccessFeature.DAILY_STATUS)
                if (configuration.interventions.isNotEmpty()) {
                    add(NotificationAccessFeature.INTERVENTIONS)
                }
            }
        } else {
            emptySet()
        }
        return AccessInspectionRequest(
            requirements = requirements,
            locationProfile = locationProfile,
            notificationFeatures = notificationFeatures,
        )
    }

    private suspend fun inspectAccess(
        configuration: StudyConfiguration,
        plan: List<StudyAccessPlanItem>,
    ): List<StudyAccessStatus> = inspectAccess(plan, accessInspectionRequest(configuration, plan))

    private suspend fun inspectAccess(
        plan: List<StudyAccessPlanItem>,
        request: AccessInspectionRequest,
    ): List<StudyAccessStatus> {
        val planByKind = plan.associateBy { it.requirement.kind }
        require(planByKind.size == plan.size) { "Access plan contains duplicate kinds" }
        val inspected = accessGateway.inspect(request).statuses
        val inspectedByKind = inspected.associateBy { it.requirement.kind }
        require(inspectedByKind.size == inspected.size) { "Access inspection contains duplicate kinds" }
        require(inspectedByKind.keys == planByKind.keys) {
            "Access inspection must return every planned kind and no others"
        }
        return inspected.map { status ->
            val planned = planByKind.getValue(status.requirement.kind)
            require(status.requirement == planned.requirement) {
                "Access inspection changed planned requiredness for ${status.requirement.kind}"
            }
            StudyAccessStatus(
                requirement = planned.requirement,
                owners = planned.owners,
                resolution = status.resolution,
                guidance = status.guidance,
            )
        }
    }

    private fun currentGrantedKinds(): Set<AccessKind> = mutableSnapshot.value.access
        .filter(StudyAccessStatus::granted)
        .mapTo(mutableSetOf()) { it.requirement.kind }

    private fun requiredAccessReady(): Boolean =
        mutableSnapshot.value.access.none { it.requirement.required && !it.granted }

    private fun usesLocation(access: List<StudyAccessStatus>): Boolean {
        val grantedKinds = access.filter(StudyAccessStatus::granted)
            .mapTo(mutableSetOf()) { it.requirement.kind }
        return usesLocation(grantedKinds)
    }

    private fun usesLocation(access: Set<AccessKind>): Boolean =
        LOCATION_COLLECTION_ACCESS.all(access::contains)

    private suspend fun ensureCollectionHostLocked(studyTitle: String, usesLocation: Boolean) {
        if (collectionHostStarted && collectionHostUsesLocation == usesLocation) return
        try {
            collectionHost.start(studyTitle, usesLocation)
        } catch (failure: Throwable) {
            collectionHostStarted = false
            collectionHostUsesLocation = false
            throw failure
        }
        collectionHostStarted = true
        collectionHostUsesLocation = usesLocation
    }

    private fun stopCollectionHostLocked() {
        collectionHost.stop()
        collectionHostStarted = false
        collectionHostUsesLocation = false
    }

    private suspend fun markSafetyPauseLocked(reason: SafetyPauseReason) {
        safetyPauseStatus = SafetyPauseStatus.Pending(reason)
        mutableSnapshot.update { it.copy(safetyPauseStatus = safetyPauseStatus) }
        safetyPauseStore.markPending(reason)
    }

    /**
     * Gives runtime storage failures a process-death witness before their failing operation returns.
     * This port never acquires sessionMutex, so runtime may safely invoke it while holding its
     * metadata mutex. A confirmed typed work request is the only fallback when the marker fails.
     */
    private suspend fun persistRuntimeSafetyWitness(
        experimentId: String,
        reason: SafetyPauseReason,
    ) {
        try {
            val existing = safetyPauseStore.pendingReason()
            check(
                existing == null ||
                    existing == reason ||
                    existing == SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
            ) {
                "Runtime safety witness conflicts with the durable marker"
            }
            if (existing == null) safetyPauseStore.markPending(reason)
        } catch (markerFailure: Throwable) {
            markerFailure.rethrowCancellation()
            try {
                workScheduler.scheduleSafetyPauseRetry(experimentId, reason)
            } catch (schedulingFailure: Throwable) {
                schedulingFailure.rethrowCancellation()
                markerFailure.addSuppressed(schedulingFailure)
                throw markerFailure
            }
        }
    }

    private suspend fun clearSafetyPauseLocked(
        experimentId: String? = mutableSnapshot.value.configuration?.experimentId,
    ) = withContext(NonCancellable) {
        // If the process dies after clearing the marker but before cancellation commits, the
        // active typed work remains the recovery witness. Awaiting cancellation then guarantees a
        // resumed study can never be surprised by a stale retry. NonCancellable lets a running
        // SafetyPauseWorker retire itself without abandoning this short completion protocol.
        safetyPauseStore.clear()
        if (experimentId != null) workScheduler.cancelSafetyPauseRetry()
        safetyPauseStatus = null
        mutableSnapshot.update { it.copy(safetyPauseStatus = null) }
    }

    private fun publish(result: CommandResult): CommandResult {
        mutableSnapshot.update {
            it.copy(incidentCode = (result as? CommandResult.Failed)?.reasonCode)
        }
        return result
    }

    private fun requireRuntime(): ExperimentRuntime = checkNotNull(runtime) { "No study is loaded" }

    private suspend fun suppressCleanupFailure(
        primary: Throwable,
        cleanup: suspend () -> Unit,
    ) = try {
        cleanup()
    } catch (failure: Throwable) {
        failure.rethrowCancellation()
        primary.addSuppressed(failure)
    }

    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    private companion object {
        val TERMINAL_STATES = setOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN)
        val ACTIVE_STUDY_STATES = setOf(ExperimentState.RUNNING, ExperimentState.PAUSED)
        val LOCATION_COLLECTION_ACCESS = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        )
        val EXPIRABLE_UNOPENED_OCCURRENCE_STATES = setOf(
            OccurrenceState.SCHEDULED,
            OccurrenceState.POSTING,
            OccurrenceState.NOTIFICATION_POSTED,
        )
        val NON_VISIBLE_OCCURRENCE_STATES = setOf(
            OccurrenceState.SCHEDULED,
            OccurrenceState.OPENED,
            OccurrenceState.SURVEY_SUBMITTED,
            OccurrenceState.EXPIRED,
        )

        /**
         * States that can hold deliverable events. A study that ended still uploads, so its tail
         * reaches the researcher rather than waiting for a manual export that may never happen.
         */
        val UPLOADABLE_STATES = setOf(
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN,
        )
        const val INCIDENT_STUDY_RECOVERY_FAILED = "STUDY_RECOVERY_FAILED"
        const val INCIDENT_STUDY_IMPORT_FAILED = "STUDY_IMPORT_FAILED"
        const val INCIDENT_COMMAND_REJECTED = "COMMAND_REJECTED"
        const val INCIDENT_REQUIRED_ACCESS_MISSING = "REQUIRED_ACCESS_MISSING"
        const val INCIDENT_ACCESS_INSPECTION_FAILED = "ACCESS_INSPECTION_FAILED"
        const val INCIDENT_SAFETY_PAUSE_SHUTDOWN_FAILED = "SAFETY_PAUSE_SHUTDOWN_FAILED"
        const val INCIDENT_SAFETY_PAUSE_PENDING = "SAFETY_PAUSE_PENDING"
        const val INCIDENT_SAFETY_PAUSE_MARKER_FAILED = "SAFETY_PAUSE_MARKER_FAILED"
        const val INCIDENT_SAFETY_PAUSE_MARKER_UNREADABLE = "SAFETY_PAUSE_MARKER_UNREADABLE"
        const val INCIDENT_SAFETY_PAUSE_MARKER_CLEAR_FAILED = "SAFETY_PAUSE_MARKER_CLEAR_FAILED"
        const val INCIDENT_SAFETY_PAUSE_REASON_CONFLICT = "SAFETY_PAUSE_REASON_CONFLICT"
        const val INCIDENT_SAFETY_PAUSE_RETRY_INSPECTION_FAILED =
            "SAFETY_PAUSE_RETRY_INSPECTION_FAILED"
        const val INCIDENT_SAFETY_PAUSE_RETRY_SCHEDULING_FAILED =
            "SAFETY_PAUSE_RETRY_SCHEDULING_FAILED"
        const val INCIDENT_COLLECTION_HOST_FAILED = "COLLECTION_HOST_FAILED"
        const val INCIDENT_COLLECTION_TEARDOWN_FAILED = "COLLECTION_TEARDOWN_FAILED"
        const val INCIDENT_STORAGE_WRITE_FAILED = "STORAGE_WRITE_FAILED"
        const val INCIDENT_WORK_SCHEDULING_FAILED = "WORK_SCHEDULING_FAILED"
        const val INCIDENT_UPLOAD_FAILED = "UPLOAD_FAILED"
        const val INCIDENT_UPLOAD_COMMIT_FAILED = "UPLOAD_COMMIT_FAILED"
        const val INCIDENT_RECLAIM_FAILED = "LOCAL_RECLAIM_FAILED"
        const val MAXIMUM_UPLOAD_BYTES = 32L * 1024 * 1024
        const val RUNTIME_SAFETY_PAUSE_RETRY_DELAY_MILLIS = 10_000L
        val SHA256_HEX = Regex("[0-9a-f]{64}")

    }
}
