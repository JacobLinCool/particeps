package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.automation.AutomationCompiler
import cool.jacoblin.particeps.core.automation.CompilationResult
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.RandomWindowTimerProducer
import cool.jacoblin.particeps.core.automation.SecureRandomSource
import cool.jacoblin.particeps.core.automation.StandardTimerProducer
import cool.jacoblin.particeps.core.automation.toAutomationCompilerInput
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessInspectionRequest
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.LocationAccessProfile
import cool.jacoblin.particeps.core.collector.NotificationAccessFeature
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.collector.StudyAccessGateway
import cool.jacoblin.particeps.core.definition.InterventionConfiguration
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.LocationV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.MultipleChoiceQuestion
import cool.jacoblin.particeps.core.definition.ScaleQuestion
import cool.jacoblin.particeps.core.definition.ShortTextQuestion
import cool.jacoblin.particeps.core.definition.SingleChoiceQuestion
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.definition.SurveyDefinition
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.definition.resourceKey
import cool.jacoblin.particeps.core.definition.signedProfiles
import cool.jacoblin.particeps.core.export.BundleKind
import cool.jacoblin.particeps.core.export.BundleProducer
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.export.ExportSnapshot
import cool.jacoblin.particeps.core.export.ResearchExport
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyResetStore
import cool.jacoblin.particeps.core.model.StudyStorageResetter
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.protocol.ActiveStudyRecord
import cool.jacoblin.particeps.core.protocol.ActiveStudyStore
import cool.jacoblin.particeps.core.protocol.JoinLink
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.runtime.ActionExecutionFailure
import cool.jacoblin.particeps.core.runtime.ActionOutboxNotifier
import cool.jacoblin.particeps.core.runtime.DurableActionInvocation
import cool.jacoblin.particeps.core.runtime.ExperimentRuntime
import cool.jacoblin.particeps.core.runtime.GeneratedEventContractRegistry
import cool.jacoblin.particeps.core.runtime.NoOpActionOutboxNotifier
import cool.jacoblin.particeps.core.runtime.NoOpTimerWakeupAdapter
import cool.jacoblin.particeps.core.runtime.RuntimeCommandResult
import cool.jacoblin.particeps.core.runtime.RuntimeEntropySource
import cool.jacoblin.particeps.core.runtime.RuntimeInitializationResult
import cool.jacoblin.particeps.core.runtime.RuntimeResourceHost
import cool.jacoblin.particeps.core.runtime.RuntimeSnapshot
import cool.jacoblin.particeps.core.runtime.RuntimeStudyIdentity
import cool.jacoblin.particeps.core.runtime.SecureRuntimeEntropySource
import cool.jacoblin.particeps.core.runtime.SelectingTimerProducer
import cool.jacoblin.particeps.core.runtime.TimerWakeupAdapter
import java.io.OutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID
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

fun interface StudyVerifier {
    fun verify(envelopeBytes: ByteArray): VerifiedConfiguration
}

fun interface AcceptedStudyVerifier {
    fun verify(envelopeBytes: ByteArray): VerifiedConfiguration
}

fun interface StudyStoreFactory {
    fun create(experimentId: String, maximumLocalBytes: Long): StudyStore
}

/** Platform implementations may supply the traffic-shaping actuator and future signed actuators. */
fun interface PlatformResourceActuatorFactory {
    fun create(key: ResourceKey, configuration: StudyConfiguration): StatefulResourceActuator?
}

object NoPlatformResourceActuators : PlatformResourceActuatorFactory {
    override fun create(key: ResourceKey, configuration: StudyConfiguration): StatefulResourceActuator? = null
}

/** Process-liveness wrapper hook; it is deliberately outside the signed resource vector. */
fun interface CollectorActuatorDecorator {
    fun decorate(
        study: StudyConfiguration,
        declaration: CollectorResourceConfiguration,
        delegate: StatefulResourceActuator,
    ): StatefulResourceActuator
}

object IdentityCollectorActuatorDecorator : CollectorActuatorDecorator {
    override fun decorate(
        study: StudyConfiguration,
        declaration: CollectorResourceConfiguration,
        delegate: StatefulResourceActuator,
    ): StatefulResourceActuator = delegate
}

data class StudyRuntimeAssembly(
    val runtime: ExperimentRuntime,
    private val eventSink: BindableEventSink,
    private val tokenEncoder: BindableStudyScopedTokenEncoder,
) {
    fun bindRuntimeSecrets(document: RuntimeDocument) {
        tokenEncoder.bindBase64Url(document.activityTokenKeyBase64Url)
    }

    fun close() {
        eventSink.unbind(runtime)
        tokenEncoder.clear()
        runtime.close()
    }
}

fun interface StudyRuntimeAssemblyFactory {
    fun create(configuration: VerifiedConfiguration, store: StudyStore): StudyRuntimeAssembly
}

/**
 * Production assembly for the one durable coordinator. Timer wakeups and action notifications are
 * adapters only; their authoritative state remains in the runtime commit chain.
 */
class EventDrivenRuntimeAssemblyFactory(
    private val collectorRegistry: CollectorRegistry,
    private val clocks: ResearchClocks,
    private val scope: CoroutineScope,
    private val platformActuators: PlatformResourceActuatorFactory = NoPlatformResourceActuators,
    private val collectorActuatorDecorator: CollectorActuatorDecorator = IdentityCollectorActuatorDecorator,
    private val timerWakeups: TimerWakeupAdapter = NoOpTimerWakeupAdapter,
    private val actionNotifier: ActionOutboxNotifier = NoOpActionOutboxNotifier,
    private val zoneId: () -> String = { ZoneId.systemDefault().id },
    private val entropy: RuntimeEntropySource = SecureRuntimeEntropySource(),
) : StudyRuntimeAssemblyFactory {
    override fun create(configuration: VerifiedConfiguration, store: StudyStore): StudyRuntimeAssembly {
        val signed = configuration.configuration
        val compilation = AutomationCompiler(GeneratedEventContractRegistry).compile(
            signed.toAutomationCompilerInput(configuration.configurationSha256),
        )
        val program = when (compilation) {
            is CompilationResult.Success -> compilation.program
            is CompilationResult.Failure -> throw StudyAssemblyException(
                code = "AUTOMATION_CONFIGURATION_INVALID",
                cause = IllegalArgumentException(
                    compilation.issues.joinToString(separator = ";") { "${it.path}:${it.code}" },
                ),
            )
        }
        val eventSink = BindableEventSink()
        val tokenEncoder = BindableStudyScopedTokenEncoder()
        val collectorHosts = signed.collectors.map { declaration ->
            val plugin = collectorRegistry.plugins.singleOrNull { it.descriptor.id == declaration.id }
                ?: throw StudyAssemblyException("COLLECTOR_NOT_COMPILED")
            val profiles = declaration.signedProfiles().associateByTo(sortedMapOf()) { it.id }
            val collectorActuator = CollectorResourceActuator(
                declaration = declaration,
                plugin = plugin,
                scope = scope,
                eventSink = eventSink,
                clocks = clocks,
                tokenEncoder = tokenEncoder,
            )
            val decorated = collectorActuatorDecorator.decorate(signed, declaration, collectorActuator)
            require(decorated.key == declaration.resourceKey) { "Collector decorator changed the resource key" }
            RuntimeResourceHost(
                key = declaration.resourceKey,
                required = declaration.required,
                profiles = profiles,
                actuator = decorated,
            )
        }
        val actuatorHosts = buildList {
            val shaping = signed.trafficShaping as? TrafficShapingConfiguration.Enabled ?: return@buildList
            val key = shaping.resourceKey
            add(
                RuntimeResourceHost(
                    key = key,
                    required = true,
                    profiles = shaping.signedProfiles().associateByTo(sortedMapOf()) { it.id },
                    actuator = platformActuators.create(key, signed),
                ),
            )
        }
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity(
                experimentId = signed.experimentId,
                configurationId = signed.configurationId,
                configurationSha256 = configuration.configurationSha256,
                durationSeconds = Math.multiplyExact(signed.durationHours.toLong(), SECONDS_PER_HOUR),
                assignedParticipantId = signed.assignedParticipantId,
            ),
            store = store,
            program = program,
            surveyInterventionIds = signed.interventions
                .filter { it.action is SurveyAction }
                .mapTo(sortedSetOf()) { it.id },
            resourceHosts = (collectorHosts + actuatorHosts).sortedBy(RuntimeResourceHost::key),
            clocks = clocks,
            scope = scope,
            zoneId = zoneId,
            timerProducer = SelectingTimerProducer(
                deterministic = StandardTimerProducer(),
                randomWindow = RandomWindowTimerProducer(SecureRandomSource()),
            ),
            timerWakeups = timerWakeups,
            actionNotifier = actionNotifier,
            entropy = entropy,
        )
        eventSink.bind(runtime)
        return StudyRuntimeAssembly(runtime, eventSink, tokenEncoder)
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3_600L
    }
}

class StudyAssemblyException(
    val code: String,
    cause: Throwable? = null,
) : IllegalStateException(code, cause) {
    init {
        require(REASON.matches(code)) { "Invalid study assembly failure code" }
    }

    private companion object {
        val REASON = Regex("[A-Z][A-Z0-9_]{2,63}")
    }
}

interface RecoveryReporter {
    /** Diagnostic sinks may retain [failure]; participant UI receives only a generic status. */
    fun actionRequired(failure: Throwable?)
    fun clear()
}

data class StudyUploadPlan(
    val experimentId: String,
    val configurationSha256: String,
    val researcherKeyId: String,
    val endpoint: String,
    val intervalMinutes: Int,
    val allowMetered: Boolean,
)

data class UploadReconciliation(
    val plan: StudyUploadPlan,
    val uploadedThroughCommit: Long,
)

interface StudyUploadCoordinator {
    suspend fun reconcile(context: UploadReconciliation)
    suspend fun acknowledge(bundleId: UUID)
    suspend fun prepareDeletion(experimentId: String)
    suspend fun clear(experimentId: String)
    suspend fun clearAll()
}

object NoOpStudyUploadCoordinator : StudyUploadCoordinator {
    override suspend fun reconcile(context: UploadReconciliation) = Unit
    override suspend fun acknowledge(bundleId: UUID) = Unit
    override suspend fun prepareDeletion(experimentId: String) = Unit
    override suspend fun clear(experimentId: String) = Unit
    override suspend fun clearAll() = Unit
}

interface StudyUploadScheduler {
    suspend fun ensureScheduled(plan: StudyUploadPlan)
    suspend fun cancel(experimentId: String)
    suspend fun cancelAll()
}

object NoOpStudyUploadScheduler : StudyUploadScheduler {
    override suspend fun ensureScheduled(plan: StudyUploadPlan) = Unit
    override suspend fun cancel(experimentId: String) = Unit
    override suspend fun cancelAll() = Unit
}

enum class StartupStage {
    LOADING_STUDY,
    VERIFYING_STORAGE,
    RESTORING_RUNTIME,
}

enum class StudyRecoveryStatus {
    NONE,
    RECOVERED_PAUSED,
    ACTION_REQUIRED,
}

sealed interface StudyCommandResult {
    data object Success : StudyCommandResult
    data object InvalidState : StudyCommandResult
    data object AccessRequired : StudyCommandResult
    data object FailedClosed : StudyCommandResult
    data object InvalidInput : StudyCommandResult
}

sealed interface ParticipantSurveyAnswer {
    data class Text(val value: String) : ParticipantSurveyAnswer
    data class Scale(val value: Int) : ParticipantSurveyAnswer
    data class Choice(val optionId: String) : ParticipantSurveyAnswer
    data class MultipleChoice(val optionIds: List<String>) : ParticipantSurveyAnswer
}

/** Whitelisted participant-facing projection. It intentionally contains no automation details. */
data class ParticipantStudySummary(
    val experimentId: String,
    val configurationId: String,
    val assignedParticipantId: String?,
    val title: String,
    val researcherName: String,
    val researcherContact: String,
    val purpose: String,
    val durationHours: Int,
    val consentDocumentVersion: String,
    val consentSummary: String,
    val signerFingerprint: String,
    val signerAnchored: Boolean,
    val dataCategories: List<ParticipantDataCategorySummary>,
    val mayAdjustAppTransferSpeed: Boolean,
    val upload: ParticipantUploadSummary?,
)

data class ParticipantDataCategorySummary(
    val sourceId: String,
    val required: Boolean,
)

data class ParticipantUploadSummary(
    val destinationHost: String,
    val intervalMinutes: Int,
    val allowMetered: Boolean,
)

/** Runtime projection safe for participant surfaces: no epoch, digest, profile, trigger, or reason. */
data class ParticipantRuntimeStatus(
    val state: ExperimentState? = null,
    val participantInstanceId: String? = null,
    val lifetimeDataEventCount: Long = 0,
    val durableThroughCommit: Long = 0,
    val uploadedThroughCommit: Long = 0,
    val retainedFromCommit: Long = 1,
    val startedAtUtcMillis: Long? = null,
    val deadlineUtcMillis: Long? = null,
    val deadlineUtcTrusted: Boolean = false,
    val activeRunningElapsedMillis: Long = 0,
    val calendarElapsedMillis: Long = 0,
    val lastObservedAtUtcMillis: Long? = null,
)

data class ParticipantExportSummary(
    val commitCount: Long,
    val eventCount: Long,
    val byteCount: Long,
)

data class StudyAccessStatus(
    val kind: AccessKind,
    val required: Boolean,
    val resolution: AccessResolution,
    val guidance: SetupGuidance?,
) {
    val granted: Boolean get() = resolution == AccessResolution.Satisfied
}

data class StudySessionSnapshot(
    val initialized: Boolean = false,
    val study: ParticipantStudySummary? = null,
    val runtime: ParticipantRuntimeStatus = ParticipantRuntimeStatus(),
    val access: List<StudyAccessStatus> = emptyList(),
    val lastExport: ParticipantExportSummary? = null,
    val deletionPending: Boolean = false,
    val recoveryStatus: StudyRecoveryStatus = StudyRecoveryStatus.NONE,
    val startupStage: StartupStage? = null,
)

/** Computes permissions over every signed named profile, including each location settings shape. */
class StudyAccessPolicy {
    suspend fun inspect(
        configuration: StudyConfiguration,
        collectorRegistry: CollectorRegistry,
        gateway: StudyAccessGateway,
    ): List<StudyAccessStatus> {
        val requirements = linkedMapOf<AccessKind, Boolean>()
        configuration.collectors.forEach { resource ->
            val descriptor = collectorRegistry.plugins.singleOrNull { it.descriptor.id == resource.id }?.descriptor
                ?: throw StudyAssemblyException("COLLECTOR_NOT_COMPILED")
            descriptor.accessKinds.forEach { kind ->
                requirements[kind] = requirements[kind] == true || resource.required
            }
        }
        requirements[AccessKind.NOTIFICATIONS] = true
        val requirementSet = requirements.entries
            .sortedBy { it.key.ordinal }
            .mapTo(linkedSetOf()) { (kind, required) -> AccessRequirement(kind, required) }
        val notificationFeatures = buildSet {
            add(NotificationAccessFeature.COLLECTION)
            add(NotificationAccessFeature.DAILY_STATUS)
            add(NotificationAccessFeature.RECOVERY)
            if (configuration.interventions.isNotEmpty()) add(NotificationAccessFeature.INTERVENTIONS)
        }
        val locationProfiles = configuration.collectors
            .flatMap { it.profiles }
            .mapNotNull { it.configuration as? LocationV1ProfileConfiguration }
            .map(LocationAccessProfile::from)
            .distinct()
            .sortedBy(::locationSortKey)
        val requests = if (locationProfiles.isEmpty()) {
            listOf(AccessInspectionRequest(requirementSet, notificationFeatures = notificationFeatures))
        } else {
            locationProfiles.map { profile ->
                AccessInspectionRequest(
                    requirements = requirementSet,
                    locationProfile = profile,
                    notificationFeatures = notificationFeatures,
                )
            }
        }
        val snapshots = requests.map { gateway.inspect(it) }
        return requirementSet.map { requirement ->
            val candidates = snapshots.map { snapshot ->
                snapshot.statuses.single { it.requirement.kind == requirement.kind }
            }
            val selected = candidates.firstOrNull { !it.granted } ?: candidates.first()
            StudyAccessStatus(
                kind = requirement.kind,
                required = requirement.required,
                resolution = selected.resolution,
                guidance = selected.guidance,
            )
        }
    }

    private fun locationSortKey(profile: LocationAccessProfile): String = listOf(
        profile.intervalMillis,
        profile.minimumIntervalMillis,
        profile.maximumBatchDelayMillis,
        profile.minimumDisplacementMillimeters,
        profile.priority.ordinal,
    ).joinToString(":") { it.toString().padStart(16, '0') }
}

/**
 * Compact application boundary around the durable runtime. The manager verifies signed bytes,
 * owns enrollment ordering and deletion, while every research event and side-effect intent stays
 * inside [ExperimentRuntime].
 */
class StudySessionManager(
    private val activeStudyStore: ActiveStudyStore,
    private val verifier: StudyVerifier,
    private val acceptedStudyVerifier: AcceptedStudyVerifier,
    private val storeFactory: StudyStoreFactory,
    private val runtimeFactory: StudyRuntimeAssemblyFactory,
    private val collectorRegistry: CollectorRegistry,
    private val accessGateway: StudyAccessGateway,
    private val resetStore: StudyResetStore,
    private val storageResetter: StudyStorageResetter,
    private val recoveryReporter: RecoveryReporter,
    private val accessPolicy: StudyAccessPolicy,
    private val uploadCoordinator: StudyUploadCoordinator = NoOpStudyUploadCoordinator,
    private val uploadScheduler: StudyUploadScheduler = NoOpStudyUploadScheduler,
    private val bundleProducer: BundleProducer,
    private val exportedAtUtcMillis: () -> Long,
    private val scope: CoroutineScope,
) {
    private val sessionMutex = Mutex()
    private val exportMutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(StudySessionSnapshot())
    val snapshot: StateFlow<StudySessionSnapshot> = mutableSnapshot.asStateFlow()

    private var verified: VerifiedConfiguration? = null
    private var store: StudyStore? = null
    private var assembly: StudyRuntimeAssembly? = null
    private var runtimeObservation: Job? = null
    private var activeEnvelopeBytes: ByteArray? = null

    suspend fun initialize() = sessionMutex.withLock {
        check(!mutableSnapshot.value.initialized && mutableSnapshot.value.startupStage == null) {
            "Study session is already initialized"
        }
        mutableSnapshot.value = StudySessionSnapshot(startupStage = StartupStage.LOADING_STUDY)
        try {
            resetStore.load()?.let {
                completeResetLocked()
                mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                return@withLock
            }
            when (val record = activeStudyStore.load()) {
                null -> {
                    recoveryReporter.clear()
                    mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                }
                is ActiveStudyRecord.Active -> {
                    mutableSnapshot.update { it.copy(startupStage = StartupStage.VERIFYING_STORAGE) }
                    activateLocked(
                        envelopeBytes = record.envelopeBytes,
                        configuration = acceptedStudyVerifier.verify(record.envelopeBytes),
                        persistEnvelope = false,
                        joinLink = null,
                    )
                }
                is ActiveStudyRecord.DeletionPending -> {
                    mutableSnapshot.update { it.copy(deletionPending = true) }
                    completePendingDeletionLocked(record)
                    mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                }
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            clearSessionFieldsLocked()
            recoveryReporter.actionRequired(failure)
            mutableSnapshot.value = StudySessionSnapshot(
                initialized = true,
                recoveryStatus = StudyRecoveryStatus.ACTION_REQUIRED,
            )
        }
    }

    suspend fun importSignedConfiguration(bytes: ByteArray, joinLink: JoinLink? = null) = sessionMutex.withLock {
        check(mutableSnapshot.value.initialized) { "Study session is not initialized" }
        check(mutableSnapshot.value.recoveryStatus != StudyRecoveryStatus.ACTION_REQUIRED) {
            "Reset the unrecoverable study before importing another one"
        }
        check(verified == null && activeStudyStore.load() == null) { "A study is already active" }
        val configuration = verifier.verify(bytes)
        validateJoinLink(joinLink, bytes, configuration)
        try {
            activateLocked(bytes, configuration, persistEnvelope = true, joinLink = joinLink)
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            clearSessionFieldsLocked()
            recoveryReporter.actionRequired(failure)
            mutableSnapshot.value = StudySessionSnapshot(
                initialized = true,
                recoveryStatus = StudyRecoveryStatus.ACTION_REQUIRED,
            )
            throw failure
        }
    }

    /** Explicit participant retry; it re-verifies durable state and never deletes or resumes it. */
    suspend fun retryRecovery() = sessionMutex.withLock {
        check(mutableSnapshot.value.recoveryStatus == StudyRecoveryStatus.ACTION_REQUIRED) {
            "Recovery retry is available only after a failed recovery"
        }
        closeAssemblyLocked()
        mutableSnapshot.update {
            it.copy(
                initialized = false,
                recoveryStatus = StudyRecoveryStatus.NONE,
                startupStage = StartupStage.LOADING_STUDY,
            )
        }
        try {
            when (val record = activeStudyStore.load()) {
                null -> {
                    clearSessionFieldsLocked()
                    recoveryReporter.clear()
                    mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                }
                is ActiveStudyRecord.Active -> {
                    mutableSnapshot.update { it.copy(startupStage = StartupStage.VERIFYING_STORAGE) }
                    activateLocked(
                        envelopeBytes = record.envelopeBytes,
                        configuration = acceptedStudyVerifier.verify(record.envelopeBytes),
                        persistEnvelope = false,
                        joinLink = null,
                    )
                }
                is ActiveStudyRecord.DeletionPending -> {
                    mutableSnapshot.update { it.copy(deletionPending = true) }
                    completePendingDeletionLocked(record)
                    clearSessionFieldsLocked()
                    mutableSnapshot.value = StudySessionSnapshot(initialized = true)
                }
            }
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            clearSessionFieldsLocked()
            recoveryReporter.actionRequired(failure)
            mutableSnapshot.value = StudySessionSnapshot(
                initialized = true,
                recoveryStatus = StudyRecoveryStatus.ACTION_REQUIRED,
            )
        }
    }

    suspend fun reviewStudy(): StudyCommandResult = runtimeCommand { it.beginConsentReview() }

    suspend fun acceptConsent(): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        val result = mapCommand(current.acceptConsent())
        if (result == StudyCommandResult.Success) refreshAccessLocked()
        result
    }

    suspend fun completeAccessSetup(): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        val access = refreshAccessLocked()
        if (access.any { it.required && !it.granted }) return@withLock StudyCommandResult.AccessRequired
        mapCommand(current.markReady())
    }

    suspend fun start(): StudyCommandResult = startOrResume(resume = false)

    suspend fun resume(): StudyCommandResult = startOrResume(resume = true)

    suspend fun pause(): StudyCommandResult = runtimeCommand { it.pause() }

    suspend fun complete(): StudyCommandResult = runtimeCommand { it.complete() }

    suspend fun withdraw(): StudyCommandResult = runtimeCommand { it.withdraw() }

    /** Platform-only consent/access revocation bridge; no platform reason is exposed to UI. */
    suspend fun safetyPauseForPlatformAccessLoss(): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(current.safetyPause(SafetyPauseReason.REQUIRED_ACCESS_MISSING))
    }

    suspend fun reconcileAccess(): List<StudyAccessStatus> = sessionMutex.withLock {
        val access = refreshAccessLocked()
        if (
            access.any { it.required && !it.granted } &&
            assembly?.runtime?.snapshot?.value?.state == ExperimentState.RUNNING
        ) {
            assembly?.runtime?.safetyPause(SafetyPauseReason.REQUIRED_ACCESS_MISSING)
        }
        access
    }

    suspend fun pendingActions(): List<DurableActionInvocation> = sessionMutex.withLock {
        runtimeOrInvalid()?.pendingActions().orEmpty()
    }

    suspend fun reconcileActionOutbox(): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(current.reconcileActions())
    }

    /** Re-arms platform wakeups from durable timer truth after recovery or lifecycle changes. */
    suspend fun pendingTimers(): List<DurableTimer> = sessionMutex.withLock {
        runtimeOrInvalid()?.pendingTimers().orEmpty()
    }

    suspend fun claimAction(actionId: String): DurableActionInvocation? = sessionMutex.withLock {
        runtimeOrInvalid()?.claimAction(actionId)
    }

    suspend fun recordActionResult(
        actionId: String,
        succeeded: Boolean,
        failure: ActionExecutionFailure? = null,
    ): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        val invocation = current.pendingActions().singleOrNull { it.actionId == actionId }
            ?: return@withLock StudyCommandResult.InvalidState
        val intervention = verified?.configuration?.interventions
            ?.singleOrNull { it.id == invocation.interventionId }
            ?: return@withLock StudyCommandResult.InvalidState
        if (intervention.action is SurveyAction) return@withLock StudyCommandResult.InvalidState
        mapCommand(current.recordActionResult(actionId, succeeded, failure))
    }

    suspend fun onTimerDue(
        timerId: String,
        generation: ULong,
    ): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(current.onTimerDue(timerId, generation))
    }

    suspend fun onClockDiscontinuity(): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(current.onClockDiscontinuity())
    }

    suspend fun openSurvey(actionId: String): StudyCommandResult = sessionMutex.withLock {
        val binding = surveyBindingLocked(actionId) ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(binding.runtime.openSurvey(actionId, binding.intervention.id))
    }

    /** Researcher-authored questions only; trigger, condition, epoch, and profile data stay hidden. */
    suspend fun surveyForAction(actionId: String): SurveyDefinition? = sessionMutex.withLock {
        surveyBindingLocked(actionId)?.survey
    }

    suspend fun submitSurvey(
        actionId: String,
        answers: Map<String, ParticipantSurveyAnswer>,
    ): StudyCommandResult = sessionMutex.withLock {
        val binding = surveyBindingLocked(actionId) ?: return@withLock StudyCommandResult.InvalidState
        val answersJson = encodeSurveyAnswers(binding.survey, answers)
            ?: return@withLock StudyCommandResult.InvalidInput
        mapCommand(
            binding.runtime.submitSurvey(
                actionId = actionId,
                interventionId = binding.intervention.id,
                surveyId = binding.survey.id,
                answersJson = answersJson,
            ),
        )
    }

    /** Dismissal is non-terminal; the same durable action remains available until submit/expiry. */
    suspend fun dismissSurvey(actionId: String): StudyCommandResult = sessionMutex.withLock {
        val binding = surveyBindingLocked(actionId) ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(binding.runtime.dismissSurvey(actionId, binding.intervention.id))
    }

    suspend fun expireSurvey(actionId: String): StudyCommandResult = sessionMutex.withLock {
        val binding = surveyBindingLocked(actionId) ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(binding.runtime.expireSurvey(actionId, binding.intervention.id))
    }

    fun intervention(interventionId: String): InterventionConfiguration? =
        verified?.configuration?.interventions?.singleOrNull { it.id == interventionId }

    suspend fun exportTo(destination: OutputStream): ExportReceipt = exportMutex.withLock {
        val request = sessionMutex.withLock {
            check(!mutableSnapshot.value.deletionPending) { "Study deletion is pending" }
            val configuration = checkNotNull(verified) { "No active study" }
            val currentStore = checkNotNull(store) { "No active study store" }
            val document = checkNotNull(currentStore.loadRuntime()) { "No durable runtime" }
            ExportRequest(
                snapshot = ExportSnapshot(
                    verifiedConfiguration = configuration,
                    runtime = document,
                    producer = bundleProducer,
                    bundleKind = BundleKind.MANUAL_EXPORT,
                    exportedAtUtcMillis = exportedAtUtcMillis(),
                    throughCommit = document.revision,
                ),
                store = currentStore,
                configurationSha256 = configuration.configurationSha256,
            )
        }
        val receipt = destination.use { output ->
            ResearchExport.encrypt(request.snapshot, request.store, output)
        }
        sessionMutex.withLock {
            if (
                !mutableSnapshot.value.deletionPending &&
                verified?.configurationSha256 == request.configurationSha256
            ) {
                mutableSnapshot.update {
                    it.copy(
                        lastExport = ParticipantExportSummary(
                            commitCount = receipt.commitCount,
                            eventCount = receipt.eventCount,
                            byteCount = receipt.byteCount,
                        ),
                    )
                }
            }
        }
        receipt
    }

    /** Encrypts one durable automatic-upload stage. The caller owns atomic staging and retries. */
    suspend fun prepareAutomaticUpload(
        destination: OutputStream,
        bundleId: UUID,
        maximumPlaintextBytes: Long? = null,
    ): ExportReceipt? = exportMutex.withLock {
        val request = sessionMutex.withLock {
            check(!mutableSnapshot.value.deletionPending) { "Study deletion is pending" }
            val configuration = verified ?: return@withLock null
            if (configuration.configuration.upload == null) return@withLock null
            val currentStore = store ?: return@withLock null
            val document = currentStore.loadRuntime() ?: return@withLock null
            if (document.uploadedThroughCommit >= document.revision) return@withLock null
            ExportRequest(
                snapshot = ExportSnapshot(
                    verifiedConfiguration = configuration,
                    runtime = document,
                    producer = bundleProducer,
                    bundleKind = BundleKind.AUTOMATIC_UPLOAD,
                    exportedAtUtcMillis = exportedAtUtcMillis(),
                    bundleId = bundleId,
                    fromCommit = document.uploadedThroughCommit + 1,
                    throughCommit = document.revision,
                    maximumPlaintextBytes = maximumPlaintextBytes,
                ),
                store = currentStore,
                configurationSha256 = configuration.configurationSha256,
            )
        } ?: return@withLock null
        destination.use { output -> ResearchExport.encrypt(request.snapshot, request.store, output) }
    }

    /** Persists the authenticated server acknowledgement before deleting the staged ciphertext. */
    suspend fun acknowledgeAutomaticUpload(receipt: ExportReceipt): StudyCommandResult = sessionMutex.withLock {
        val configuration = verified ?: return@withLock StudyCommandResult.InvalidState
        val plan = configuration.uploadPlan() ?: return@withLock StudyCommandResult.InvalidState
        if (receipt.configurationSha256 != configuration.configurationSha256 || receipt.commitCount == 0L) {
            return@withLock StudyCommandResult.InvalidInput
        }
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        val result = mapCommand(
            current.acknowledgeUpload(
                bundleId = receipt.bundleId.toString(),
                firstCommit = receipt.firstCommitSequence,
                throughCommit = receipt.lastCommitSequence,
                bundleSha256 = receipt.sha256,
            ),
        )
        if (result == StudyCommandResult.Success) {
            uploadCoordinator.acknowledge(receipt.bundleId)
            uploadScheduler.ensureScheduled(plan)
        }
        result
    }

    suspend fun deleteLocalData() {
        val cleanup = sessionMutex.withLock {
            val configuration = verified?.configuration
            if (configuration == null) {
                if (mutableSnapshot.value.deletionPending) return@withLock PendingCleanup(null, null)
                return
            }
            val currentRuntime = assembly?.runtime
            currentRuntime?.takeIf { it.snapshot.value.state in DELETABLE_ACTIVE_STATES }?.let {
                runCatching { it.withdraw() }
            }
            activeStudyStore.markDeletionPending(configuration.experimentId, configuration.maximumLocalBytes)
            mutableSnapshot.update { it.copy(deletionPending = true) }
            closeAssemblyLocked()
            PendingCleanup(store, configuration)
        }
        exportMutex.withLock {
            val failures = mutableListOf<Throwable>()
            cleanup.configuration?.let { configuration ->
                runCleanup(failures) { uploadCoordinator.prepareDeletion(configuration.experimentId) }
                runCleanup(failures) { uploadScheduler.cancel(configuration.experimentId) }
                runCleanup(failures) { uploadCoordinator.clear(configuration.experimentId) }
            }
            cleanup.store?.let { runCleanup(failures) { it.clear() } }
            runCleanup(failures) { activeStudyStore.clear() }
            if (failures.isNotEmpty()) throw combineFailures(failures)
        }
        sessionMutex.withLock {
            clearSessionFieldsLocked()
            recoveryReporter.clear()
            mutableSnapshot.value = StudySessionSnapshot(initialized = true)
        }
    }

    /** Destructive recovery for an unreadable active record or commit chain; never retains v1 bytes. */
    suspend fun resetAfterRecoveryFailure() = sessionMutex.withLock {
        check(mutableSnapshot.value.recoveryStatus == StudyRecoveryStatus.ACTION_REQUIRED) {
            "Recovery reset is available only after a failed recovery"
        }
        resetStore.mark(retainedEnvelopeBytes = null)
        completeResetLocked()
        mutableSnapshot.value = StudySessionSnapshot(initialized = true)
    }

    /**
     * Closes process-local callbacks without changing durable lifecycle state. If the state was
     * active, the next process must therefore recover it fail closed to PAUSED.
     */
    suspend fun shutdownProcess() = sessionMutex.withLock {
        closeAssemblyLocked()
    }

    private suspend fun activateLocked(
        envelopeBytes: ByteArray,
        configuration: VerifiedConfiguration,
        persistEnvelope: Boolean,
        joinLink: JoinLink?,
    ) {
        validateJoinLink(joinLink, envelopeBytes, configuration)
        val signed = configuration.configuration
        val nextStore = storeFactory.create(signed.experimentId, signed.maximumLocalBytes)
        val nextAssembly = runtimeFactory.create(configuration, nextStore)
        if (persistEnvelope) activeStudyStore.save(envelopeBytes.copyOf())
        mutableSnapshot.update { it.copy(startupStage = StartupStage.RESTORING_RUNTIME) }
        when (val result = nextAssembly.runtime.initialize()) {
            is RuntimeInitializationResult.Failed -> {
                nextAssembly.close()
                throw result.cause
            }
            is RuntimeInitializationResult.Ready -> {
                val document = checkNotNull(nextStore.loadRuntime()) { "Runtime initialization did not persist state" }
                nextAssembly.bindRuntimeSecrets(document)
                if (result.snapshot.state == ExperimentState.IMPORTED) {
                    require(nextAssembly.runtime.markConfigurationVerified() == RuntimeCommandResult.Success) {
                        "Verified configuration did not advance setup"
                    }
                }
                verified = configuration
                store = nextStore
                assembly = nextAssembly
                activeEnvelopeBytes = envelopeBytes.copyOf()
                observeRuntimeLocked(nextAssembly.runtime)
                val access = accessPolicy.inspect(signed, collectorRegistry, accessGateway)
                mutableSnapshot.value = StudySessionSnapshot(
                    initialized = true,
                    study = participantSummary(configuration),
                    runtime = participantRuntime(nextAssembly.runtime.snapshot.value, document, signed.durationHours),
                    access = access,
                    recoveryStatus = if (result.recoveredFailClosed) {
                        StudyRecoveryStatus.RECOVERED_PAUSED
                    } else {
                        StudyRecoveryStatus.NONE
                    },
                )
                configuration.uploadPlan()?.let { plan ->
                    uploadCoordinator.reconcile(
                        UploadReconciliation(plan, document.uploadedThroughCommit),
                    )
                    uploadScheduler.ensureScheduled(plan)
                }
                recoveryReporter.clear()
            }
        }
    }

    private suspend fun startOrResume(resume: Boolean): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        val access = refreshAccessLocked()
        if (access.any { it.required && !it.granted }) return@withLock StudyCommandResult.AccessRequired
        mapCommand(if (resume) current.resume() else current.start())
    }

    private suspend fun runtimeCommand(
        command: suspend (ExperimentRuntime) -> RuntimeCommandResult,
    ): StudyCommandResult = sessionMutex.withLock {
        val current = runtimeOrInvalid() ?: return@withLock StudyCommandResult.InvalidState
        mapCommand(command(current))
    }

    private fun runtimeOrInvalid(): ExperimentRuntime? =
        if (
            mutableSnapshot.value.initialized &&
            !mutableSnapshot.value.deletionPending &&
            mutableSnapshot.value.recoveryStatus != StudyRecoveryStatus.ACTION_REQUIRED
        ) {
            assembly?.runtime
        } else {
            null
        }

    private suspend fun refreshAccessLocked(): List<StudyAccessStatus> {
        val configuration = verified?.configuration ?: return emptyList()
        val access = accessPolicy.inspect(configuration, collectorRegistry, accessGateway)
        mutableSnapshot.update { it.copy(access = access) }
        return access
    }

    private fun observeRuntimeLocked(current: ExperimentRuntime) {
        runtimeObservation?.cancel()
        runtimeObservation = scope.launch {
            current.snapshot.collect { next ->
                if (assembly?.runtime === current) {
                    val document = store?.loadRuntime()
                    val durationHours = verified?.configuration?.durationHours
                    mutableSnapshot.update {
                        it.copy(runtime = participantRuntime(next, document, durationHours))
                    }
                }
            }
        }
    }

    private fun participantSummary(configuration: VerifiedConfiguration): ParticipantStudySummary {
        val signed = configuration.configuration
        val categories = signed.collectors.map {
            ParticipantDataCategorySummary(sourceId = it.id, required = it.required)
        }.sortedBy(ParticipantDataCategorySummary::sourceId)
        return ParticipantStudySummary(
            experimentId = signed.experimentId,
            configurationId = signed.configurationId,
            assignedParticipantId = signed.assignedParticipantId,
            title = signed.title,
            researcherName = signed.researcherName,
            researcherContact = signed.researcherContact,
            purpose = signed.purpose,
            durationHours = signed.durationHours,
            consentDocumentVersion = signed.consentDocumentVersion,
            consentSummary = signed.consentSummary,
            signerFingerprint = signed.signer.fingerprint,
            signerAnchored = configuration.signerAnchored,
            dataCategories = categories,
            mayAdjustAppTransferSpeed = signed.trafficShaping is TrafficShapingConfiguration.Enabled,
            upload = signed.upload?.let { upload ->
                ParticipantUploadSummary(
                    destinationHost = checkNotNull(java.net.URI(upload.endpoint).host),
                    intervalMinutes = upload.intervalMinutes,
                    allowMetered = upload.allowMetered,
                )
            },
        )
    }

    private suspend fun surveyBindingLocked(actionId: String): SurveyBinding? {
        val current = runtimeOrInvalid() ?: return null
        val invocation = current.pendingActions().singleOrNull { it.actionId == actionId } ?: return null
        val configuration = verified?.configuration ?: return null
        val intervention = configuration.interventions.singleOrNull { it.id == invocation.interventionId } ?: return null
        val action = intervention.action as? SurveyAction ?: return null
        val survey = configuration.surveys.singleOrNull { it.id == action.surveyId } ?: return null
        return SurveyBinding(current, intervention, survey)
    }

    private fun encodeSurveyAnswers(
        survey: SurveyDefinition,
        answers: Map<String, ParticipantSurveyAnswer>,
    ): String? {
        if (answers.keys.any { answer -> survey.questions.none { it.id == answer } }) return null
        val encoded = sortedMapOf<String, String>()
        survey.questions.forEach { question ->
            val answer = answers[question.id]
            if (answer == null) {
                if (question.required) return null
                return@forEach
            }
            val value = when (question) {
                is ShortTextQuestion -> (answer as? ParticipantSurveyAnswer.Text)?.value
                    ?.takeIf { it.length <= question.maximumLength }
                    ?.let(::jsonString)
                is ScaleQuestion -> (answer as? ParticipantSurveyAnswer.Scale)?.value
                    ?.takeIf { it in question.minimum..question.maximum }
                    ?.toString()
                is SingleChoiceQuestion -> (answer as? ParticipantSurveyAnswer.Choice)?.optionId
                    ?.takeIf { selected -> question.options.any { it.id == selected } }
                    ?.let(::jsonString)
                is MultipleChoiceQuestion -> (answer as? ParticipantSurveyAnswer.MultipleChoice)?.optionIds
                    ?.takeIf { selected ->
                        selected == selected.sorted().distinct() &&
                            selected.size in question.minimumSelections..question.maximumSelections &&
                            selected.all { id -> question.options.any { it.id == id } }
                    }
                    ?.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::jsonString)
            } ?: return null
            encoded[question.id] = value
        }
        return encoded.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (id, value) ->
            "${jsonString(id)}:$value"
        }.takeIf { it.length <= MAXIMUM_SURVEY_ANSWERS_JSON }
    }

    private fun participantRuntime(
        runtime: RuntimeSnapshot,
        document: RuntimeDocument?,
        durationHours: Int?,
    ): ParticipantRuntimeStatus {
        val clock = document?.clockCheckpoint
        val durationMillis = durationHours?.let {
            Math.multiplyExact(Math.multiplyExact(it.toLong(), 3_600L), 1_000L)
        }
        return ParticipantRuntimeStatus(
            state = runtime.state,
            participantInstanceId = document?.participantInstanceId,
            lifetimeDataEventCount = runtime.lifetimeDataEventCount,
            durableThroughCommit = runtime.revision,
            uploadedThroughCommit = runtime.uploadedThroughCommit,
            retainedFromCommit = runtime.retainedFromCommit,
            startedAtUtcMillis = if (clock != null && durationMillis != null) {
                Math.subtractExact(clock.deadlineUtcMillis, durationMillis)
            } else {
                null
            },
            deadlineUtcMillis = clock?.deadlineUtcMillis,
            deadlineUtcTrusted = clock?.deadlineUtcTrusted == true,
            activeRunningElapsedMillis = clock?.activeRunningElapsedNanos?.div(NANOS_PER_MILLISECOND) ?: 0,
            calendarElapsedMillis = clock?.calendarElapsedNanos?.div(NANOS_PER_MILLISECOND) ?: 0,
            lastObservedAtUtcMillis = clock?.anchor?.wallTimeUtcMillis,
        )
    }

    private fun mapCommand(result: RuntimeCommandResult): StudyCommandResult = when (result) {
        RuntimeCommandResult.Success -> StudyCommandResult.Success
        is RuntimeCommandResult.Rejected -> StudyCommandResult.InvalidState
        is RuntimeCommandResult.FailedClosed -> StudyCommandResult.FailedClosed
    }

    private suspend fun completePendingDeletionLocked(record: ActiveStudyRecord.DeletionPending) {
        val pendingStore = storeFactory.create(record.experimentId, record.maximumLocalBytes)
        uploadCoordinator.prepareDeletion(record.experimentId)
        uploadScheduler.cancel(record.experimentId)
        uploadCoordinator.clear(record.experimentId)
        pendingStore.clear()
        activeStudyStore.clear()
        recoveryReporter.clear()
    }

    private suspend fun completeResetLocked() {
        closeAssemblyLocked()
        val failures = mutableListOf<Throwable>()
        runCleanup(failures) { uploadScheduler.cancelAll() }
        runCleanup(failures) { uploadCoordinator.clearAll() }
        runCleanup(failures) { storageResetter.clearAll() }
        runCleanup(failures) { activeStudyStore.clear() }
        if (failures.isEmpty()) runCleanup(failures) { resetStore.clear() }
        if (failures.isNotEmpty()) throw combineFailures(failures)
        clearSessionFieldsLocked()
        recoveryReporter.clear()
    }

    private fun closeAssemblyLocked() {
        runtimeObservation?.cancel()
        runtimeObservation = null
        assembly?.close()
        assembly = null
    }

    private fun clearSessionFieldsLocked() {
        closeAssemblyLocked()
        verified = null
        store = null
        activeEnvelopeBytes?.fill(0)
        activeEnvelopeBytes = null
    }

    private suspend fun runCleanup(failures: MutableList<Throwable>, action: suspend () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            failures += failure
        }
    }

    private fun combineFailures(failures: List<Throwable>): Throwable {
        val first = failures.first()
        failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
        return first
    }

    private data class ExportRequest(
        val snapshot: ExportSnapshot,
        val store: StudyStore,
        val configurationSha256: String,
    )

    private data class PendingCleanup(
        val store: StudyStore?,
        val configuration: StudyConfiguration?,
    )

    private data class SurveyBinding(
        val runtime: ExperimentRuntime,
        val intervention: InterventionConfiguration,
        val survey: SurveyDefinition,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_SURVEY_ANSWERS_JSON = 61_440
        val DELETABLE_ACTIVE_STATES = setOf(
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
            ExperimentState.ACTIVATING,
            ExperimentState.PAUSING,
        )
    }
}

private fun validateJoinLink(
    joinLink: JoinLink?,
    envelopeBytes: ByteArray,
    configuration: VerifiedConfiguration,
) {
    if (joinLink == null) return
    require(envelopeBytes.sha256Hex() == joinLink.artifactSha256) { "Join artifact digest mismatch" }
    require(
        configuration.configuration.signer.fingerprint.replace(" ", "") == joinLink.signerFingerprint,
    ) { "Join signer fingerprint mismatch" }
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun VerifiedConfiguration.uploadPlan(): StudyUploadPlan? = configuration.upload?.let { upload ->
    StudyUploadPlan(
        experimentId = configuration.experimentId,
        configurationSha256 = configurationSha256,
        researcherKeyId = configuration.export.researcherKeyId,
        endpoint = upload.endpoint,
        intervalMinutes = upload.intervalMinutes,
        allowMetered = upload.allowMetered,
    )
}

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
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}
