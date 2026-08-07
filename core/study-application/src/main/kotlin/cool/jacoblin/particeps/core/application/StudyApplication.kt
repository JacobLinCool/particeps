package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessStatus
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.StudyAccessGateway
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.ResearchTime
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
import cool.jacoblin.particeps.core.runtime.SurveyAnswer
import cool.jacoblin.particeps.core.runtime.SurveySubmissionResult
import java.io.OutputStream
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

fun interface StudyVerifier { fun verify(envelopeBytes: ByteArray): VerifiedConfiguration }

fun interface StudyStoreFactory {
    fun create(experimentId: String, maximumLocalBytes: Long): StudyStore
}

fun interface ExperimentRuntimeFactory {
    fun create(configuration: StudyConfiguration, store: StudyStore, availableAccess: () -> Set<AccessKind>): ExperimentRuntime
}

interface StudyCollectionHost {
    fun start(studyTitle: String, usesLocation: Boolean)
    fun stop()
}

interface StudyWorkScheduler {
    fun schedule(configuration: StudyConfiguration)

    /** Rebuilds delivery and durable expiry work after state, clock, reboot, or time-zone recovery. */
    fun replaceInterventionWork(
        configuration: StudyConfiguration,
        deliveries: List<InterventionOccurrence>,
        expiries: List<InterventionOccurrence>,
    )

    /** Adds the successor of a completed trigger without disturbing unrelated work. */
    fun enqueueOccurrence(configuration: StudyConfiguration, occurrence: InterventionOccurrence)

    /** Cancels delivery/expiry work and visible notifications while a study is paused. */
    fun cancelInterventionWork(experimentId: String, occurrenceIds: Set<String>)

    /** Idempotently removes notifications that durable occurrence state proves are no longer visible. */
    fun cancelInterventionNotifications(occurrenceIds: Set<String>)

    /**
     * Cancels reminders and the study deadline, leaving scheduled delivery in place.
     *
     * Used when a study ends. Collection is over, but events already recorded and not yet delivered
     * are still owed to the researcher — stranding them here would defeat the point of uploading at
     * all, since the participant may never perform a manual export.
     */
    fun cancelCollectionWork(experimentId: String, occurrenceIds: Set<String>)

    /** Cancels everything, including undelivered work. Used when the data itself is going away. */
    fun cancel(experimentId: String)
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

data class StudySessionSnapshot(
    val initialized: Boolean = false,
    val configuration: StudyConfiguration? = null,
    val runtime: RuntimeSnapshot = RuntimeSnapshot(),
    val access: List<AccessStatus> = emptyList(),
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
    val incidentCode: String? = null,
)

class StudyAccessPolicy {
    fun requirements(configuration: StudyConfiguration, collectorRequirements: Set<AccessRequirement>): Set<AccessRequirement> {
        val interventionRequirements = if (configuration.interventions.isEmpty()) {
            emptySet()
        } else {
            setOf(AccessRequirement(AccessKind.NOTIFICATIONS, required = true))
        }
        return (collectorRequirements + interventionRequirements)
            .groupBy(AccessRequirement::kind)
            .mapTo(mutableSetOf()) { (kind, entries) ->
                AccessRequirement(kind, entries.any(AccessRequirement::required))
            }
    }
}

class StudySessionManager(
    private val activeStudyStore: ActiveStudyStore,
    private val verifier: StudyVerifier,
    private val storeFactory: StudyStoreFactory,
    private val runtimeFactory: ExperimentRuntimeFactory,
    private val collectorRegistry: CollectorRegistry,
    private val accessGateway: StudyAccessGateway,
    private val collectionHost: StudyCollectionHost,
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

    suspend fun initialize() = sessionMutex.withLock {
        check(!mutableSnapshot.value.initialized) { "Study session is already initialized" }
        try {
            when (val saved = activeStudyStore.load()) {
                null -> mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                is ActiveStudyRecord.Active -> activate(saved.envelopeBytes, persistEnvelope = false, joinLink = null)
                is ActiveStudyRecord.DeletionPending -> {
                    deletionPending = true
                    mutableSnapshot.value = StudySessionSnapshot(deletionPending = true)
                    completePendingDeletion(saved)
                    mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                }
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            mutableSnapshot.update {
                it.copy(
                    initialized = true,
                    deletionPending = deletionPending,
                    incidentCode = INCIDENT_STUDY_RECOVERY_FAILED,
                )
            }
        }
    }

    suspend fun importSignedConfiguration(bytes: ByteArray, joinLink: JoinLink? = null) = sessionMutex.withLock {
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
        onSuccess = ::refreshAccess,
    )
    suspend fun completeAccessSetup(): CommandResult = sessionMutex.withLock {
        refreshAccess()
        if (mutableSnapshot.value.access.any { it.requirement.required && !it.granted }) {
            return@withLock publish(CommandResult.Failed(INCIDENT_REQUIRED_ACCESS_MISSING))
        }
        publish(requireRuntime().completeAccessSetup(currentGrantedKinds()))
    }

    suspend fun start(): CommandResult = sessionMutex.withLock {
        refreshAccess()
        val current = requireRuntime()
        try {
            collectionHost.start(current.configuration.title, usesLocation())
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return@withLock publish(CommandResult.Failed(INCIDENT_COLLECTION_HOST_FAILED))
        }
        val result = current.start()
        if (result != CommandResult.Success) {
            collectionHost.stop()
            return@withLock publish(result)
        }
        try {
            workScheduler.schedule(current.configuration)
            syncInterventionsLocked(current)
            publish(result)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            if (current.pause() != CommandResult.Success) current.shutdown()
            workScheduler.cancel(current.configuration.experimentId)
            collectionHost.stop()
            publish(CommandResult.Failed(INCIDENT_WORK_SCHEDULING_FAILED))
        }
    }

    suspend fun pause(): CommandResult = sessionMutex.withLock {
        val current = requireRuntime()
        val result = current.pause()
        if (result == CommandResult.Success) {
            collectionHost.stop()
            workScheduler.cancelInterventionWork(
                current.configuration.experimentId,
                occurrenceIds(current),
            )
        }
        publish(result)
    }
    suspend fun resume(): CommandResult = sessionMutex.withLock {
        refreshAccess()
        val current = requireRuntime()
        try {
            collectionHost.start(current.configuration.title, usesLocation())
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            return@withLock publish(CommandResult.Failed(INCIDENT_COLLECTION_HOST_FAILED))
        }
        val result = current.resume()
        if (result != CommandResult.Success) {
            collectionHost.stop()
        } else {
            syncInterventionsLocked(current)
        }
        publish(result)
    }

    suspend fun rescheduleInterventions(recoverStalePosting: Boolean = false) = sessionMutex.withLock {
        runtime?.let { current ->
            if (current.snapshot.value.metadata?.state == ExperimentState.RUNNING) {
                syncInterventionsLocked(current, recoverStalePosting)
            } else {
                workScheduler.cancelInterventionWork(
                    current.configuration.experimentId,
                    occurrenceIds(current),
                )
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
            scheduleNextLocked(current, occurrenceId)
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
    suspend fun completeAfterDuration(): CommandResult = terminalCommand { it.completeAfterDuration() }
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

        attempt { collectionHost.stop() }
        attempt { workScheduler.cancel(deletion.experimentId) }
        attempt { uploader.clear() }
        attempt { store.clear() }
        if (firstFailure == null) attempt { activeStudyStore.clear() }
        firstFailure?.let { throw it }
    }

    fun refreshAccess() {
        val configuration = mutableSnapshot.value.configuration ?: return
        mutableSnapshot.update {
            it.copy(access = accessGateway.inspect(requirements(configuration)))
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
        configuration.collectors.forEach(collectorRegistry::pluginFor)
        val requirements = requirements(configuration)
        val access = accessGateway.inspect(requirements)
        val createdStore = storeFactory.create(configuration.experimentId, configuration.maximumLocalBytes)
        var createdRuntime: ExperimentRuntime? = null
        try {
            val created = runtimeFactory.create(
                configuration,
                createdStore,
                availableAccess = { accessGateway.grantedKinds(requirements) },
            )
            createdRuntime = created
            check(created.initialize() == CommandResult.Success) { "Runtime initialization failed" }
            if (persistEnvelope) activeStudyStore.save(envelopeBytes)
            if (created.snapshot.value.metadata?.state == ExperimentState.RUNNING) {
                collectionHost.start(
                    configuration.title,
                    usesLocation(requirements),
                )
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
            )
            runtimeObservation?.cancel()
            runtimeObservation = scope.launch {
                created.snapshot.collect { runtimeSnapshot ->
                    mutableSnapshot.update { current ->
                        if (runtime === created) current.copy(runtime = runtimeSnapshot) else current
                    }
                }
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            createdRuntime?.let { created -> suppressCleanupFailure(failure) { created.shutdown() } }
            if (persistEnvelope) {
                suppressCleanupFailure(failure, createdStore::clear)
                suppressCleanupFailure(failure, activeStudyStore::clear)
            }
            throw failure
        }
    }

    private suspend fun command(
        execute: suspend (ExperimentRuntime) -> CommandResult,
        onSuccess: () -> Unit = {},
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
        val result = execute(current)
        if (result == CommandResult.Success) {
            collectionHost.stop()
            // Not cancel(): the study is over, but its undelivered tail is not.
            workScheduler.cancelCollectionWork(
                current.configuration.experimentId,
                occurrenceIds(current),
            )
        }
        publish(result)
    }

    private fun occurrenceIds(current: ExperimentRuntime): Set<String> =
        current.snapshot.value.metadata?.occurrences?.keys.orEmpty()

    private fun requirements(configuration: StudyConfiguration): Set<AccessRequirement> =
        accessPolicy.requirements(
            configuration,
            collectorRegistry.accessRequirements(configuration.collectors),
        )

    private fun currentGrantedKinds(): Set<AccessKind> =
        accessGateway.grantedKinds(requirements(requireNotNull(mutableSnapshot.value.configuration)))

    private fun usesLocation(): Boolean =
        usesLocation(requirements(requireNotNull(mutableSnapshot.value.configuration)))

    private fun usesLocation(requirements: Set<AccessRequirement>): Boolean =
        requirements.any { it.kind == AccessKind.FINE_LOCATION } &&
            AccessKind.FINE_LOCATION in accessGateway.grantedKinds(requirements)

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
        const val INCIDENT_REQUIRED_ACCESS_MISSING = "REQUIRED_ACCESS_MISSING"
        const val INCIDENT_COLLECTION_HOST_FAILED = "COLLECTION_HOST_FAILED"
        const val INCIDENT_WORK_SCHEDULING_FAILED = "WORK_SCHEDULING_FAILED"
        const val INCIDENT_UPLOAD_FAILED = "UPLOAD_FAILED"
        const val INCIDENT_UPLOAD_COMMIT_FAILED = "UPLOAD_COMMIT_FAILED"
        const val INCIDENT_RECLAIM_FAILED = "LOCAL_RECLAIM_FAILED"
        const val MAXIMUM_UPLOAD_BYTES = 32L * 1024 * 1024
        val SHA256_HEX = Regex("[0-9a-f]{64}")

    }
}
