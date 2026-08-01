package cool.linc.androiddatacollector.core.application

import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.collector.AccessStatus
import cool.linc.androiddatacollector.core.collector.CollectorRegistry
import cool.linc.androiddatacollector.core.collector.StudyAccessGateway
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.export.ExportReceipt
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.StudyStore
import cool.linc.androiddatacollector.core.protocol.ActiveStudyStore
import cool.linc.androiddatacollector.core.protocol.VerifiedConfiguration
import cool.linc.androiddatacollector.core.runtime.CommandResult
import cool.linc.androiddatacollector.core.runtime.ExperimentRuntime
import cool.linc.androiddatacollector.core.runtime.RuntimeSnapshot
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

fun interface StudyStoreFactory { fun create(configuration: StudyConfiguration): StudyStore }

fun interface ExperimentRuntimeFactory {
    fun create(configuration: StudyConfiguration, store: StudyStore, availableAccess: () -> Set<AccessKind>): ExperimentRuntime
}

interface StudyCollectionHost {
    fun start(studyTitle: String, usesLocation: Boolean)
    fun stop()
}

interface StudyWorkScheduler {
    fun schedule(configuration: StudyConfiguration)

    /**
     * Cancels reminders and the study deadline, leaving scheduled delivery in place.
     *
     * Used when a study ends. Collection is over, but events already recorded and not yet delivered
     * are still owed to the researcher — stranding them here would defeat the point of uploading at
     * all, since the participant may never perform a manual export.
     */
    fun cancelCollectionWork(experimentId: String)

    /** Cancels everything, including undelivered work. Used when the data itself is going away. */
    fun cancel(experimentId: String)
}

fun interface StudyExporter {
    suspend fun export(configuration: StudyConfiguration, metadata: StudyMetadata, events: StudyStore, destination: OutputStream): ExportReceipt
}

/**
 * Delivers one encrypted bundle covering `[fromSequence, toSequence]` to the study's endpoint.
 *
 * Implementations receive the same HPKE-encrypted bundle a participant would export by hand, so
 * the endpoint stores ciphertext it cannot read. Returning normally means the endpoint confirmed
 * receipt and the watermark may advance; anything else must throw.
 */
fun interface StudyUploader {
    suspend fun upload(
        configuration: StudyConfiguration,
        metadata: StudyMetadata,
        events: StudyStore,
        fromSequence: Long,
        toSequence: Long,
    ): ExportReceipt
}

/**
 * Thrown by a [StudyUploader] to explain why delivery failed.
 *
 * Carries a fixed reason code rather than a message, for the same reason collector health does:
 * whatever surfaces here can reach a log or a screen, and must not be able to hold study data.
 */
class StudyUploadException(
    val reasonCode: String,
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
)

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
    val incidentCode: String? = null,
)

class StudyAccessPolicy {
    fun requirements(configuration: StudyConfiguration, collectorRequirements: Set<AccessRequirement>): Set<AccessRequirement> {
        val promptRequirements = if (configuration.prompts.isEmpty()) {
            emptySet()
        } else {
            setOf(AccessRequirement(AccessKind.NOTIFICATIONS, required = true))
        }
        return (collectorRequirements + promptRequirements)
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
) {
    private val sessionMutex = Mutex()

    /**
     * Serialises uploads against each other without blocking participant actions. Network I/O must
     * never run under [sessionMutex]: a stalled request would freeze pause, withdraw and delete for
     * as long as the connection hangs.
     */
    private val uploadMutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(StudySessionSnapshot())
    val snapshot: StateFlow<StudySessionSnapshot> = mutableSnapshot.asStateFlow()

    private var runtime: ExperimentRuntime? = null
    private var studyStore: StudyStore? = null
    private var runtimeObservation: Job? = null

    suspend fun initialize() = sessionMutex.withLock {
        check(!mutableSnapshot.value.initialized) { "Study session is already initialized" }
        try {
            val saved = activeStudyStore.load()
            if (saved == null) {
                mutableSnapshot.value = StudySessionSnapshot(initialized = true)
            } else {
                activate(saved, persistEnvelope = false)
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            mutableSnapshot.update {
                it.copy(initialized = true, incidentCode = INCIDENT_STUDY_RECOVERY_FAILED)
            }
        }
    }

    suspend fun importSignedConfiguration(bytes: ByteArray) = sessionMutex.withLock {
        check(runtime == null) { "Delete the current study before importing another" }
        try {
            activate(bytes, persistEnvelope = true)
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
            publish(result)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            if (current.pause() != CommandResult.Success) current.shutdown()
            workScheduler.cancel(current.configuration.experimentId)
            collectionHost.stop()
            publish(CommandResult.Failed(INCIDENT_WORK_SCHEDULING_FAILED))
        }
    }

    suspend fun pause(): CommandResult = command(
        execute = { it.pause() },
        onSuccess = collectionHost::stop,
    )
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
        if (result != CommandResult.Success) collectionHost.stop()
        publish(result)
    }

    suspend fun finish(): CommandResult = terminalCommand { it.finishEarly() }
    suspend fun completeAfterDuration(): CommandResult = terminalCommand { it.completeAfterDuration() }
    suspend fun withdraw(): CommandResult = terminalCommand { it.withdraw() }

    suspend fun exportTo(destination: OutputStream): ExportReceipt = sessionMutex.withLock {
        val current = requireRuntime()
        val receipt = destination.use {
            exporter.export(
                current.configuration,
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
    suspend fun uploadPending(): CommandResult = uploadMutex.withLock {
        val plan = planUpload() ?: return@withLock CommandResult.Success

        val receipt = try {
            uploader.upload(plan.configuration, plan.metadata, plan.store, plan.from, plan.to)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            val reason = (failure as? StudyUploadException)?.reasonCode ?: INCIDENT_UPLOAD_FAILED
            // Leave incidentCode alone: a transient upload failure is not a collection incident,
            // and overwriting it would bury a storage or access problem the participant must act on.
            mutableSnapshot.update {
                it.copy(
                    upload = it.upload?.copy(lastFailureCode = reason)
                        ?: UploadStatus(plan.from - 1, 0, lastFailureCode = reason),
                )
            }
            return@withLock CommandResult.Failed(reason)
        }

        commitUpload(plan, receipt.sequenceBoundary)
        CommandResult.Success
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
            configuration = current.configuration,
            metadata = metadata,
            store = requireNotNull(studyStore),
            from = from,
            to = durable,
        )
    }

    private suspend fun commitUpload(plan: UploadPlan, deliveredThrough: Long) = sessionMutex.withLock {
        // The study may have been withdrawn, deleted or replaced while the request was in flight.
        val current = runtime ?: return@withLock
        if (current.configuration.experimentId != plan.configuration.experimentId) return@withLock
        // The receipt, not the plan: a budgeted bundle may have stopped short, and the rest goes
        // out on the next run.
        current.confirmUploaded(deliveredThrough)
        // Confirmed delivery is the only thing that makes local data reclaimable, so this is the
        // one point where reclaiming can make progress.
        val updated = current.reclaimLocalSpace()
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

    private class UploadPlan(
        val configuration: StudyConfiguration,
        val metadata: StudyMetadata,
        val store: StudyStore,
        val from: Long,
        val to: Long,
    )

    suspend fun deleteLocalData() = sessionMutex.withLock {
        val current = requireRuntime()
        require(current.snapshot.value.metadata?.state in TERMINAL_STATES) {
            "Withdraw or complete the study before deleting its data"
        }
        runtimeObservation?.cancel()
        requireNotNull(studyStore).clear()
        activeStudyStore.clear()
        workScheduler.cancel(current.configuration.experimentId)
        collectionHost.stop()
        runtime = null
        studyStore = null
        runtimeObservation = null
        mutableSnapshot.value = StudySessionSnapshot(initialized = true)
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
    ) {
        val verified = verifier.verify(envelopeBytes)
        val configuration = verified.configuration
        configuration.collectors.forEach(collectorRegistry::pluginFor)
        val requirements = requirements(configuration)
        val access = accessGateway.inspect(requirements)
        val createdStore = storeFactory.create(configuration)
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

    private suspend fun terminalCommand(
        execute: suspend (ExperimentRuntime) -> CommandResult,
    ): CommandResult = sessionMutex.withLock {
        val current = requireRuntime()
        val result = execute(current)
        if (result == CommandResult.Success) {
            collectionHost.stop()
            // Not cancel(): the study is over, but its undelivered tail is not.
            workScheduler.cancelCollectionWork(current.configuration.experimentId)
        }
        publish(result)
    }

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

    }
}
