package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.ReducerClock
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.ResourceAuditEvidence
import cool.jacoblin.particeps.core.resource.ResourceAuditRemovalReason
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailure
import java.time.ZoneId

internal fun String.toResourceAuditRemovalReason(): ResourceAuditRemovalReason = when (this) {
    "PARTICIPANT_PAUSED" -> ResourceAuditRemovalReason.PARTICIPANT_PAUSED
    "STUDY_COMPLETED" -> ResourceAuditRemovalReason.STUDY_COMPLETED
    "STUDY_WITHDRAWN" -> ResourceAuditRemovalReason.STUDY_WITHDRAWN
    else -> error("Unsupported resource audit lifecycle reason: $this")
}

internal fun ResourceTerminalFailure?.toResourceAuditRemovalReason(
    safetyReason: SafetyPauseReason,
): ResourceAuditRemovalReason = when (this?.reason) {
    "ACTIVATION_TIMEOUT" -> ResourceAuditRemovalReason.ACTIVATION_TIMEOUT
    "NATIVE_ENGINE_FAILED" -> ResourceAuditRemovalReason.FORWARDER_FAILURE
    "OWNED_VPN_LOST", "OWNED_VPN_NOT_CONFIRMED" -> ResourceAuditRemovalReason.OWNED_VPN_NETWORK_LOST
    "PROFILE_MISMATCH" -> ResourceAuditRemovalReason.PROFILE_MISMATCH
    "SOCKET_PROTECTOR_MISSING" -> ResourceAuditRemovalReason.SOCKET_PROTECT_FAILURE
    "TARGET_PACKAGE_CHANGED", "TARGET_PACKAGE_INVALID" -> ResourceAuditRemovalReason.TARGET_PACKAGE_CHANGED
    "TUN_CLOSED" -> ResourceAuditRemovalReason.TUN_IO_FAILURE
    "TUN_ESTABLISH_FAILED" -> ResourceAuditRemovalReason.TUN_ESTABLISH_FAILURE
    "VPN_CONSENT_REQUIRED", "VPN_REVOKED", "LOCAL_NETWORK_PERMISSION_REQUIRED" ->
        ResourceAuditRemovalReason.VPN_PERMISSION_REVOKED
    "FOREGROUND_SERVICE_FAILED" -> ResourceAuditRemovalReason.VPN_SERVICE_START_FAILURE
    else -> when (safetyReason) {
        SafetyPauseReason.PROCESS_RECOVERY_UNPROVEN ->
            ResourceAuditRemovalReason.RECOVERY_WITHOUT_CONFIRMED_VPN
        SafetyPauseReason.REQUIRED_ACCESS_MISSING -> ResourceAuditRemovalReason.VPN_PERMISSION_REVOKED
        SafetyPauseReason.TRAFFIC_CONDITION_LOST -> ResourceAuditRemovalReason.OWNED_VPN_NETWORK_LOST
        SafetyPauseReason.COLLECTION_HOST_FAILURE,
        SafetyPauseReason.WORK_SCHEDULING_FAILURE,
        SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
        SafetyPauseReason.STORAGE_FAILURE,
        SafetyPauseReason.AUTOMATION_ENGINE_FAILURE,
        SafetyPauseReason.REQUIRED_RESOURCE_FAILURE,
        -> ResourceAuditRemovalReason.SYSTEM_SAFETY_PAUSE
    }
}

internal fun ExperimentState.toSessionState(): StudySessionState = when (this) {
    ExperimentState.READY -> StudySessionState.READY
    ExperimentState.ACTIVATING -> StudySessionState.ACTIVATING
    ExperimentState.RUNNING -> StudySessionState.RUNNING
    ExperimentState.PAUSING -> StudySessionState.PAUSING
    ExperimentState.PAUSED -> StudySessionState.PAUSED
    ExperimentState.COMPLETED -> StudySessionState.COMPLETED
    ExperimentState.WITHDRAWN -> StudySessionState.WITHDRAWN
    else -> throw IllegalArgumentException("Enrollment state has no automation lifecycle projection")
}

internal fun reducerClock(clock: StudyClockCheckpoint): ReducerClock = ReducerClock(
    now = clock.anchor,
    activeElapsedNanos = clock.activeRunningElapsedNanos,
    calendarElapsedNanos = clock.calendarElapsedNanos,
    zoneId = clock.zoneId,
)

internal fun timerIsDue(timer: DurableTimer, clock: ReducerClock): Boolean = when (val target = timer.target) {
    is TimerTarget.CalendarUtc -> clock.now.wallTimeUtcMillis >= target.utcMillis
    is TimerTarget.ActiveElapsed -> clock.activeElapsedNanos >= target.elapsedNanos
    is TimerTarget.SameBootMonotonic ->
        clock.now.bootSessionId == target.bootSessionId && clock.now.elapsedRealtimeNanos >= target.elapsedRealtimeNanos
}

internal fun canonicalZoneId(value: String): String = ZoneId.of(value).id.also { canonical ->
    require(canonical == value && (canonical == "UTC" || '/' in canonical)) {
        "Runtime requires a canonical IANA zone ID"
    }
}

internal fun lifecycleInputsToPause(
    checkpoint: AutomationCheckpoint,
    clock: ReducerClock,
): List<ReducerInput.Lifecycle> {
    var sequence = checkpoint.evaluatedThroughSequence
    return when (checkpoint.lifecycle) {
        StudySessionState.READY -> listOf(
            ReducerInput.Lifecycle(++sequence, clock, StudySessionState.WITHDRAWN),
        )
        StudySessionState.ACTIVATING, StudySessionState.RUNNING -> listOf(
            ReducerInput.Lifecycle(++sequence, clock, StudySessionState.PAUSING),
            ReducerInput.Lifecycle(++sequence, clock, StudySessionState.PAUSED),
        )
        StudySessionState.PAUSING -> listOf(
            ReducerInput.Lifecycle(++sequence, clock, StudySessionState.PAUSED),
        )
        StudySessionState.PAUSED -> error("Study is already paused")
        StudySessionState.COMPLETED, StudySessionState.WITHDRAWN -> error("Terminal study cannot pause")
    }
}

internal fun dropRetrospectiveSourceCheckpoints(
    checkpoints: Map<EventSourceId, SourceCheckpoint>,
): Map<EventSourceId, SourceCheckpoint> = checkpoints.filterKeys { sourceId ->
    ProtocolEventSourceRegistry[sourceId.value]?.isRetrospective != true
}

internal fun AppliedResourceState.auditEvidence() = ResourceAuditEvidence(
    key = key,
    generation = desiredGeneration,
    profileId = requireNotNull(profileId),
    appliedProfileSha256 = requireNotNull(appliedProfileSha256),
)

internal fun durableCleanup(desired: DesiredResourceState): DurableResourceCleanup {
    val profile = requireNotNull(desired.profile) { "Inactive desired state cannot require cleanup" }
    return DurableResourceCleanup(desired.key, desired.generation, profile.id, profile.expectedSha256)
}

internal fun inactiveResource(key: ResourceKey, generation: ResourceGeneration) = AppliedResourceState(
    key,
    generation,
    null,
    null,
    AppliedResourceStatus.INACTIVE,
    null,
)

internal fun optionalFailure(
    key: ResourceKey,
    desired: DesiredResourceState,
    reason: String,
) = AppliedResourceState(
    key,
    desired.generation,
    desired.profile!!.id,
    null,
    AppliedResourceStatus.OPTIONAL_FAILED,
    reason,
)
