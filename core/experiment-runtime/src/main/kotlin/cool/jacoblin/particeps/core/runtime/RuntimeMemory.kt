package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.AutomationReducer
import cool.jacoblin.particeps.core.automation.CompiledAutomationProgram
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.StudyTimeline
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailure
import java.util.SortedMap
import java.util.SortedSet
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The runtime's in-memory projection of its commit chain, owned by [ExperimentRuntime]. Every field
 * is read and written only while the caller holds the runtime mutex, except [barrierBuffer], which
 * collector threads read without it. Commits and recovery replace these values, so code reads them
 * here at the point of use and never keeps its own copy.
 */
internal class RuntimeMemory {
    var document: RuntimeDocument? = null
    var automationCheckpoint = AutomationCheckpoint()
    var appliedResources = sortedMapOf<ResourceKey, AppliedResourceState>()
    var resourceCleanupAttempts = sortedMapOf<ResourceKey, DurableResourceCleanup>()
    var pendingResourceContainment: ResourceContainment? = null
    var resourceAuditTimers = sortedMapOf<String, DurableTimer>()
    var studyDeadlineTimer: DurableTimer? = null
    var actionInvocations = sortedMapOf<String, DurableActionInvocation>()
    var latestUploadAcknowledgement: DurableUploadAcknowledgement? = null
    var stateEntry: StateEntry? = null
    @Volatile var barrierBuffer: BarrierInputBuffer? = null

    fun requireDocument(): RuntimeDocument = checkNotNull(document) { "Runtime is not initialized" }
}

/**
 * The fixed collaborators of one [ExperimentRuntime], built once after its constructor checks. It
 * carries no lock, scope or channel, so code that receives it can neither lock, launch nor enqueue.
 */
internal class RuntimeContext(
    val study: RuntimeStudyIdentity,
    val store: StudyStore,
    val program: CompiledAutomationProgram,
    val hosts: SortedMap<ResourceKey, RuntimeResourceHost>,
    val interventionRequiredById: SortedMap<String, Boolean>,
    val surveyInterventionIds: SortedSet<String>,
    val clocks: ResearchClocks,
    val zoneId: () -> String,
    val initialZoneId: String,
    val timeline: StudyTimeline,
    val timerProducer: RuntimeTimerProducer,
    val timerWakeups: TimerWakeupAdapter,
    val actionNotifier: ActionOutboxNotifier,
    val entropy: RuntimeEntropySource,
    val reducer: AutomationReducer,
    val gate: EventAdmissionGate,
    val mutableSnapshot: MutableStateFlow<RuntimeSnapshot>,
    val memory: RuntimeMemory,
) {
    fun commandId(kind: String, sequence: Long): String = digest(
        "particeps-runtime-command-v1",
        study.configurationSha256,
        kind,
        sequence.toString(),
    )
}

/**
 * The only upward calls extracted code makes: containment that ends in a durable pause or deadline
 * completion. [ExperimentRuntime] alone implements it, resolving [LifecycleCoordinator] or
 * [RuntimeRecovery] at call time, and every call runs under the runtime mutex its caller holds.
 */
internal interface RuntimeContainment {
    suspend fun safetyPauseLocked(
        reason: SafetyPauseReason,
        causeSequence: Long?,
        resourceFailure: ResourceTerminalFailure? = null,
    )

    suspend fun recoverFailClosedLocked(pending: PendingEngineInput?)

    suspend fun completePausedAtDeadlineLocked(now: ResearchTime)
}
