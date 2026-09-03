package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerProducer
import cool.jacoblin.particeps.core.automation.TimerProductionRequest
import cool.jacoblin.particeps.core.automation.TimerProductionResult
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.PeriodicResourceAuditSource
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class RuntimeStudyIdentity(
    val experimentId: String,
    val configurationId: String,
    val configurationSha256: String,
    val durationSeconds: Long,
    val assignedParticipantId: String? = null,
) {
    init {
        require(ID.matches(experimentId) && ID.matches(configurationId)) { "Invalid study ID" }
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        require(durationSeconds in 1..31_536_000) { "Study duration is out of range" }
        require(assignedParticipantId == null || ASSIGNED_ID.matches(assignedParticipantId)) {
            "Invalid assigned participant ID"
        }
    }

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val ASSIGNED_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

/** One signed resource declaration and its process-local implementation, if compiled in. */
data class RuntimeResourceHost(
    val key: ResourceKey,
    val required: Boolean,
    val profiles: Map<String, SignedResourceProfile>,
    val actuator: StatefulResourceActuator?,
    val auditSource: PeriodicResourceAuditSource? = actuator as? PeriodicResourceAuditSource,
) {
    init {
        require(profiles.isNotEmpty()) { "A runtime resource needs at least one signed profile" }
        require(profiles.keys.toList() == profiles.keys.sorted()) { "Resource profiles must be sorted" }
        require(profiles.all { (id, profile) -> id == profile.id }) { "Resource profile key mismatch" }
        require(actuator == null || actuator.key == key) { "Resource actuator key mismatch" }
        require(auditSource == null || auditSource.key == key) { "Resource audit-source key mismatch" }
        require(auditSource == null || actuator != null) { "A resource audit source requires an actuator" }
        require(auditSource == null || auditSource.intervalSeconds in 1..3_600) {
            "Resource audit interval is out of range"
        }
    }
}

/** Identity of a possibly-started apply whose cleanup has not yet been verified. */
internal data class DurableResourceCleanup(
    val key: ResourceKey,
    val generation: ResourceGeneration,
    val profileId: String,
    val expectedProfileSha256: Sha256Digest,
) {
    init {
        require(PROFILE_ID.matches(profileId)) { "Invalid cleanup profile ID" }
    }

    private companion object {
        val PROFILE_ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
    }
}

fun interface RuntimeEntropySource {
    fun next(kind: RuntimeEntropyKind): String
}

enum class RuntimeEntropyKind { PARTICIPANT_INSTANCE_UUID, CONDITION_EPOCH_UUID, ACTIVITY_TOKEN_KEY }

class SecureRuntimeEntropySource(
    private val random: SecureRandom = SecureRandom(),
) : RuntimeEntropySource {
    override fun next(kind: RuntimeEntropyKind): String = when (kind) {
        RuntimeEntropyKind.PARTICIPANT_INSTANCE_UUID,
        RuntimeEntropyKind.CONDITION_EPOCH_UUID,
        -> uuidV4()
        RuntimeEntropyKind.ACTIVITY_TOKEN_KEY -> ByteArray(32).also(random::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
    }

    private fun uuidV4(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val high = bytes.copyOfRange(0, 8).fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xff)
        }
        val low = bytes.copyOfRange(8, 16).fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xff)
        }
        return UUID(high, low).toString()
    }
}

interface TimerWakeupAdapter {
    suspend fun schedule(timer: DurableTimer)
    suspend fun retire(timerId: String, generation: ULong)
}

object NoOpTimerWakeupAdapter : TimerWakeupAdapter {
    override suspend fun schedule(timer: DurableTimer) = Unit
    override suspend fun retire(timerId: String, generation: ULong) = Unit
}

interface ActionOutboxNotifier {
    suspend fun onActionReady(actionId: String)

    /**
     * Retracts every external representation of still-durable actions after the study leaves
     * RUNNING. Implementations must be idempotent; this list is derived from the commit chain and
     * therefore needs no second platform-owned checkpoint.
     */
    suspend fun onActionsInactive(actionIds: List<String>)
}

object NoOpActionOutboxNotifier : ActionOutboxNotifier {
    override suspend fun onActionReady(actionId: String) = Unit

    override suspend fun onActionsInactive(actionIds: List<String>) = Unit
}

/** Chooses the closed producer without letting the reducer read randomness or a platform clock. */
fun interface RuntimeTimerProducer {
    fun produce(request: TimerProductionRequest): TimerProductionResult
}

class SelectingTimerProducer(
    private val deterministic: TimerProducer,
    private val randomWindow: TimerProducer,
) : RuntimeTimerProducer {
    override fun produce(request: TimerProductionRequest): TimerProductionResult =
        if (request.schedule is cool.jacoblin.particeps.core.definition.AutomationSchedule.RandomWindow) {
            randomWindow.produce(request)
        } else {
            deterministic.produce(request)
        }
}

enum class RuntimeActionState { READY, CLAIMED, OPENED, SUCCEEDED, FAILED }

data class DurableActionInvocation(
    val actionId: String,
    val automationId: String,
    val interventionId: String,
    val causalSequence: Long,
    val logicalDeadlineUtcMillis: Long?,
    val expiresAtUtcMillis: Long,
    val conditionSha256: String,
    val generation: ULong,
    val requestedAt: cool.jacoblin.particeps.core.model.ResearchTime,
    val openedAt: cool.jacoblin.particeps.core.model.ResearchTime?,
    val state: RuntimeActionState,
    val failureReason: String?,
) {
    init {
        require(SHA256.matches(actionId) && SHA256.matches(conditionSha256)) { "Invalid action digest" }
        require(ID.matches(automationId) && ID.matches(interventionId)) { "Invalid action reference" }
        require(causalSequence > 0 && generation > 0uL) { "Invalid action generation or cause" }
        require(expiresAtUtcMillis >= 0) { "Invalid action expiry" }
        require(logicalDeadlineUtcMillis == null || logicalDeadlineUtcMillis >= 0) { "Invalid logical deadline" }
        require(openedAt == null || state in OPENED_STATES) { "Only an opened survey action retains open time" }
        require((state == RuntimeActionState.FAILED) == (failureReason != null)) {
            "Only a failed action carries a failure reason"
        }
        require(failureReason == null || FAILURE.matches(failureReason)) { "Invalid action failure reason" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        val ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        val FAILURE = Regex("[A-Z][A-Z0-9_]{2,63}")
        val OPENED_STATES = setOf(RuntimeActionState.OPENED, RuntimeActionState.SUCCEEDED, RuntimeActionState.FAILED)
    }
}

data class DurableUploadAcknowledgement(
    val bundleId: String,
    val firstCommit: Long,
    val throughCommit: Long,
    val bundleSha256: String,
    val acknowledgedAt: cool.jacoblin.particeps.core.model.ResearchTime,
) {
    init {
        val parsed = runCatching { UUID.fromString(bundleId) }.getOrNull()
        require(parsed != null && parsed.version() == 4 && parsed.variant() == 2) { "Invalid upload bundle ID" }
        require(firstCommit > 0 && throughCommit >= firstCommit) { "Invalid upload commit range" }
        require(SHA256.matches(bundleSha256)) { "Invalid upload bundle digest" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class RuntimeSnapshot(
    val initialized: Boolean = false,
    val state: ExperimentState? = null,
    val revision: Long = 0,
    val conditionEpochId: ConditionEpochId? = null,
    val appliedResourceVectorSha256: String? = null,
    val admissionOpen: Boolean = false,
    val pendingActionCount: Int = 0,
    val lifetimeDataEventCount: Long = 0,
    val uploadedThroughCommit: Long = 0,
    val retainedFromCommit: Long = 1,
    val calendarElapsedNanos: Long = 0,
    val activeRunningElapsedNanos: Long = 0,
    val clockAnchorWallTimeUtcMillis: Long? = null,
)

sealed interface RuntimeCommandResult {
    data object Success : RuntimeCommandResult
    data class Rejected(val reason: RuntimeCommandRejection) : RuntimeCommandResult
    data class FailedClosed(val reason: SafetyPauseReason) : RuntimeCommandResult
}

enum class RuntimeCommandRejection {
    ALREADY_INITIALIZED,
    NOT_INITIALIZED,
    INVALID_STATE,
    STALE_GENERATION,
    UNKNOWN_ACTION,
    ACTION_ALREADY_TERMINAL,
    ACTION_NOT_OPEN,
    ACTION_EXPIRED,
    SURVEY_MISMATCH,
    UPLOAD_RECEIPT_MISMATCH,
    TIMER_NOT_FOUND,
    TIMER_NOT_DUE,
}

sealed interface RuntimeInitializationResult {
    data class Ready(val recoveredFailClosed: Boolean, val snapshot: RuntimeSnapshot) : RuntimeInitializationResult
    data class Failed(val reason: SafetyPauseReason, val cause: Throwable) : RuntimeInitializationResult
}

enum class ActionExecutionFailure {
    DELIVERY_FAILED,
    EXPIRED,
    RECONCILIATION_FAILED,
    REQUIRED_ACTION_FAILED,
}
