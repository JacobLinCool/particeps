package cool.jacoblin.particeps.core.resource

import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId

enum class ResourceHealthStatus { INACTIVE, PREPARED, APPLIED, SUSPENDED, FAILED }

data class ResourceHealth(
    val key: ResourceKey,
    val status: ResourceHealthStatus,
    val generation: ResourceGeneration?,
    val profileId: String?,
    val expectedProfileSha256: Sha256Digest?,
    val appliedProfileSha256: Sha256Digest?,
    val failureReason: String?,
) {
    init {
        validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256)
        require(failureReason == null || FAILURE_REASON.matches(failureReason)) { "Invalid resource failure reason" }
        when (status) {
            ResourceHealthStatus.INACTIVE -> require(
                generation == null && profileId == null && expectedProfileSha256 == null &&
                    appliedProfileSha256 == null && failureReason == null,
            ) { "Inactive health cannot retain resource evidence" }
            ResourceHealthStatus.PREPARED -> require(
                generation != null && profileId != null && expectedProfileSha256 != null &&
                    appliedProfileSha256 == null && failureReason == null,
            ) { "Prepared health requires desired evidence and no applied digest" }
            ResourceHealthStatus.APPLIED -> require(
                generation != null && profileId != null && expectedProfileSha256 != null &&
                    appliedProfileSha256 == expectedProfileSha256 && failureReason == null,
            ) { "Applied health requires exact desired and applied evidence" }
            ResourceHealthStatus.SUSPENDED -> require(
                generation != null && profileId != null && expectedProfileSha256 != null &&
                    appliedProfileSha256 == expectedProfileSha256 && failureReason == null,
            ) { "Suspended health requires exact desired and applied evidence" }
            ResourceHealthStatus.FAILED -> require(
                (generation == null) == (profileId == null) &&
                    appliedProfileSha256 == null && failureReason != null,
            ) { "Failed health requires one typed reason and no applied digest" }
        }
    }

    private companion object { val FAILURE_REASON = Regex("[A-Z][A-Z0-9_]{2,63}") }
}

interface BoundResourceReceipt {
    val key: ResourceKey
    val generation: ResourceGeneration
    val profileId: String?
    val expectedProfileSha256: Sha256Digest?
    val appliedProfileSha256: Sha256Digest?
}

data class PrepareReceipt(
    override val key: ResourceKey,
    override val generation: ResourceGeneration,
    override val profileId: String?,
    override val expectedProfileSha256: Sha256Digest?,
    override val appliedProfileSha256: Sha256Digest?,
    val requestId: String,
) : BoundResourceReceipt {
    init {
        validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256)
        require(REQUEST_ID.matches(requestId)) { "Invalid resource request ID" }
    }
    private companion object { val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") }
}

data class SuspendReceipt(
    override val key: ResourceKey,
    override val generation: ResourceGeneration,
    override val profileId: String?,
    override val expectedProfileSha256: Sha256Digest?,
    override val appliedProfileSha256: Sha256Digest?,
    val boundary: ResearchTime,
) : BoundResourceReceipt {
    init { validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256) }
}

data class FlushReceipt(
    override val key: ResourceKey,
    override val generation: ResourceGeneration,
    override val profileId: String?,
    override val expectedProfileSha256: Sha256Digest?,
    override val appliedProfileSha256: Sha256Digest?,
    val boundary: ResearchTime,
    val cursor: String?,
    val complete: Boolean,
) : BoundResourceReceipt {
    init {
        validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256)
        require(cursor == null || cursor.length in 1..512) { "Invalid retrospective cursor" }
    }
}

data class ApplyReceipt(
    override val key: ResourceKey,
    override val generation: ResourceGeneration,
    override val profileId: String?,
    override val expectedProfileSha256: Sha256Digest?,
    override val appliedProfileSha256: Sha256Digest?,
) : BoundResourceReceipt {
    init { validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256) }
}

data class VerifyReceipt(
    override val key: ResourceKey,
    override val generation: ResourceGeneration,
    override val profileId: String?,
    override val expectedProfileSha256: Sha256Digest?,
    override val appliedProfileSha256: Sha256Digest?,
    val healthy: Boolean,
    val failureReason: String?,
) : BoundResourceReceipt {
    init {
        validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256)
        require(healthy == (failureReason == null)) { "Verification failure requires one reason" }
        require(failureReason == null || FAILURE_REASON.matches(failureReason)) { "Invalid verification failure reason" }
    }

    private companion object { val FAILURE_REASON = Regex("[A-Z][A-Z0-9_]{2,63}") }
}

data class ResumeReceipt(
    override val key: ResourceKey,
    override val generation: ResourceGeneration,
    override val profileId: String?,
    override val expectedProfileSha256: Sha256Digest?,
    override val appliedProfileSha256: Sha256Digest?,
    val resumed: Boolean,
    val failureReason: String?,
) : BoundResourceReceipt {
    init {
        validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256)
        require(resumed == (failureReason == null)) { "Resume failure requires one reason" }
        require(failureReason == null || FAILURE_REASON.matches(failureReason)) { "Invalid resume failure reason" }
    }

    private companion object { val FAILURE_REASON = Regex("[A-Z][A-Z0-9_]{2,63}") }
}

enum class ReleaseEvidence { INACTIVE, NOT_APPLIED, APPLIED }

data class ReleaseReceipt(
    override val key: ResourceKey,
    override val generation: ResourceGeneration,
    override val profileId: String?,
    override val expectedProfileSha256: Sha256Digest?,
    override val appliedProfileSha256: Sha256Digest?,
    val evidence: ReleaseEvidence,
    val released: Boolean,
) : BoundResourceReceipt {
    init {
        validateBindingShape(profileId, expectedProfileSha256, appliedProfileSha256)
        when (evidence) {
            ReleaseEvidence.INACTIVE -> require(
                profileId == null && expectedProfileSha256 == null && appliedProfileSha256 == null,
            ) { "Inactive release evidence must use exact null profile and digests" }
            ReleaseEvidence.NOT_APPLIED -> require(
                profileId != null && expectedProfileSha256 != null && appliedProfileSha256 == null,
            ) { "Not-applied release evidence requires desired identity only" }
            ReleaseEvidence.APPLIED -> require(
                profileId != null && expectedProfileSha256 != null && appliedProfileSha256 != null,
            ) { "Applied release evidence requires desired and applied digests" }
        }
    }
}

data class ResourceTerminalFailure(
    val key: ResourceKey,
    val generation: ResourceGeneration?,
    val reason: String,
) {
    init { require(REASON.matches(reason)) { "Invalid terminal resource reason" } }
    private companion object { val REASON = Regex("[A-Z][A-Z0-9_]{2,63}") }
}

fun interface ResourceTerminalFailureListener {
    fun onTerminalFailure(failure: ResourceTerminalFailure)
}

/**
 * Platform implementations own side effects; the runtime owns durable truth and generation
 * arbitration. Every operation is bound to one immutable desired state, and the runtime must call
 * the corresponding `require*` verifier before mutating its applied resource vector.
 *
 * [release] names the state being removed. A pre-commit failure may return
 * [ReleaseEvidence.NOT_APPLIED], but only exact post-release [ResourceHealthStatus.INACTIVE]
 * evidence completes either a normal release or cleanup.
 */
interface StatefulResourceActuator {
    val key: ResourceKey
    val supportsHotProfileSwap: Boolean

    fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?)
    suspend fun prepare(desired: DesiredResourceState, requestId: String): PrepareReceipt
    suspend fun suspendAt(desired: DesiredResourceState, boundary: ResearchTime): SuspendReceipt
    suspend fun flushThrough(
        desired: DesiredResourceState,
        boundary: ResearchTime,
        cursor: String?,
    ): FlushReceipt
    suspend fun apply(desired: DesiredResourceState): ApplyReceipt
    suspend fun verify(desired: DesiredResourceState): VerifyReceipt
    suspend fun resume(desired: DesiredResourceState): ResumeReceipt
    suspend fun onAdmissionOpened(desired: DesiredResourceState): ResourceHealth =
        health().also { it.requireAppliedMatches(desired) }
    suspend fun release(desired: DesiredResourceState): ReleaseReceipt
    fun health(): ResourceHealth
}

/** Evidence binding every resource-authored audit draft to runtime-owned durable state. */
data class ResourceAuditEvidence(
    val key: ResourceKey,
    val generation: ResourceGeneration,
    val profileId: String,
    val appliedProfileSha256: Sha256Digest,
)

sealed interface ResourceAuditRequest {
    val evidence: ResourceAuditEvidence
    val conditionEpochId: ConditionEpochId
    val observedAt: ResearchTime

    data class EpochActivated(
        override val evidence: ResourceAuditEvidence,
        override val conditionEpochId: ConditionEpochId,
        override val observedAt: ResearchTime,
        val activatedAt: ResearchTime,
        val signedConfigurationSha256: Sha256Digest,
    ) : ResourceAuditRequest

    data class Periodic(
        override val evidence: ResourceAuditEvidence,
        override val conditionEpochId: ConditionEpochId,
        override val observedAt: ResearchTime,
        val logicalDeadline: ResearchTime,
    ) : ResourceAuditRequest

    data class EpochBoundary(
        override val evidence: ResourceAuditEvidence,
        override val conditionEpochId: ConditionEpochId,
        override val observedAt: ResearchTime,
        val boundary: ResearchTime,
        val reason: ResourceAuditRemovalReason,
    ) : ResourceAuditRequest
}

/** Closed values intentionally match the generated traffic-shaping registry contract. */
enum class ResourceAuditRemovalReason {
    ACTIVATION_TIMEOUT,
    FORWARDER_FAILURE,
    OWNED_VPN_NETWORK_LOST,
    PARTICIPANT_PAUSED,
    PROFILE_MISMATCH,
    PROFILE_REPLACED,
    RECOVERY_WITHOUT_CONFIRMED_VPN,
    SOCKET_PROTECT_FAILURE,
    STUDY_COMPLETED,
    STUDY_WITHDRAWN,
    SYSTEM_SAFETY_PAUSE,
    TARGET_PACKAGE_CHANGED,
    TUN_ESTABLISH_FAILURE,
    TUN_IO_FAILURE,
    VPN_PERMISSION_REVOKED,
    VPN_SERVICE_START_FAILURE,
}

data class ResourceAuditReceipt(
    val evidence: ResourceAuditEvidence,
    val events: List<EventDraft>,
) {
    init {
        require(events.isNotEmpty()) { "A resource audit must produce at least one event" }
        require(events.size <= MAX_AUDIT_EVENTS) { "A resource audit produced too many events" }
    }

    private companion object { const val MAX_AUDIT_EVENTS = 4 }
}

/**
 * A closed, read-only source of aggregate resource evidence. Implementations cannot emit or arm
 * timers; the runtime validates and commits every returned draft against the generated registry.
 */
interface PeriodicResourceAuditSource {
    val key: ResourceKey
    val sourceId: EventSourceId
    val schemaVersion: Int
    val intervalSeconds: Long

    suspend fun audit(request: ResourceAuditRequest): ResourceAuditReceipt
}

fun PrepareReceipt.requireMatches(desired: DesiredResourceState, expectedRequestId: String) {
    requireBindingMatches(desired, "Prepare")
    require(desired.profile != null) { "Prepare requires an active profile" }
    require(appliedProfileSha256 == null) { "Prepare receipt cannot claim applied evidence" }
    require(requestId == expectedRequestId) { "Prepare receipt request ID mismatch" }
}

fun SuspendReceipt.requireMatches(desired: DesiredResourceState, expectedBoundary: ResearchTime) {
    requireBindingMatches(desired, "Suspend")
    requireAppliedEvidence(desired, "Suspend")
    require(boundary == expectedBoundary) { "Suspend receipt boundary mismatch" }
}

fun FlushReceipt.requireMatches(desired: DesiredResourceState, expectedBoundary: ResearchTime) {
    requireBindingMatches(desired, "Flush")
    requireAppliedEvidence(desired, "Flush")
    require(boundary == expectedBoundary) { "Flush receipt boundary mismatch" }
    require(complete) { "Resource flush did not prove the common boundary" }
}

fun ApplyReceipt.requireMatches(desired: DesiredResourceState) {
    requireBindingMatches(desired, "Apply")
    require(desired.profile != null) { "Apply requires an active profile" }
    requireAppliedEvidence(desired, "Apply")
}

fun VerifyReceipt.requireMatches(desired: DesiredResourceState) {
    requireBindingMatches(desired, "Verify")
    require(desired.profile != null) { "Verify requires an active profile" }
    require(healthy) { "Resource verification failed: $failureReason" }
    requireAppliedEvidence(desired, "Verify")
}

fun ResumeReceipt.requireMatches(desired: DesiredResourceState) {
    requireBindingMatches(desired, "Resume")
    require(desired.profile != null) { "Resume requires an active profile" }
    require(resumed) { "Resource resume failed: $failureReason" }
    requireAppliedEvidence(desired, "Resume")
}

fun ResourceHealth.requireAppliedMatches(desired: DesiredResourceState) {
    require(desired.profile != null) { "Applied health requires an active desired profile" }
    require(key == desired.key) { "Resource health key mismatch" }
    require(status == ResourceHealthStatus.APPLIED) { "Resource health is not applied" }
    require(generation == desired.generation) { "Stale resource health generation" }
    require(profileId == desired.profile.id) { "Resource health profile mismatch" }
    require(expectedProfileSha256 == desired.profile.expectedSha256) {
        "Resource health expected digest mismatch"
    }
    require(appliedProfileSha256 == desired.profile.expectedSha256) {
        "Resource health applied digest mismatch"
    }
}

/**
 * Proves that a resource has no remaining process-local state.
 *
 * This is intentionally narrower than a release receipt. The runtime may use it only while
 * recovering a durable `PAUSED` document whose last committed component vector is still
 * `APPLIED`: a crash can lose the already-verified release receipt before the follow-up inactive
 * component commit. Live release and cleanup paths must continue to verify their receipts and
 * must not downgrade an invalid receipt to health-only evidence.
 */
fun ResourceHealth.requireInactiveMatches(expectedKey: ResourceKey) {
    require(key == expectedKey) { "Inactive resource health key mismatch" }
    require(status == ResourceHealthStatus.INACTIVE) { "Resource health is not inactive" }
    require(
        generation == null &&
            profileId == null &&
            expectedProfileSha256 == null &&
            appliedProfileSha256 == null &&
            failureReason == null,
    ) { "Inactive resource health retained state or failure evidence" }
}

fun ReleaseReceipt.requireReleased(desired: DesiredResourceState, postReleaseHealth: ResourceHealth) {
    requireBindingMatches(desired, "Release")
    if (desired.profile == null) {
        require(evidence == ReleaseEvidence.INACTIVE) { "Inactive release evidence mismatch" }
    } else {
        require(evidence == ReleaseEvidence.APPLIED) { "Applied release evidence is required" }
        requireAppliedEvidence(desired, "Release")
    }
    requireReleasedHealth(desired, postReleaseHealth)
}

/**
 * Verifies containment of a current, not-yet-trusted attempt. The desired identity and expected
 * digest remain exact, while [ReleaseEvidence.APPLIED] may carry a different actual digest: an
 * actuator must be able to prove that it removed the very mismatched profile that caused apply or
 * verify to fail. Only exact post-release inactive health completes cleanup; this evidence must
 * never be used to promote the attempted state into the applied resource vector.
 */
fun ReleaseReceipt.requireCleanupReleased(
    desired: DesiredResourceState,
    postReleaseHealth: ResourceHealth,
) {
    requireBindingMatches(desired, "Release cleanup")
    if (desired.profile == null) {
        require(evidence == ReleaseEvidence.INACTIVE) { "Inactive release cleanup evidence mismatch" }
    } else {
        require(evidence != ReleaseEvidence.INACTIVE) { "Active release cleanup omitted operation evidence" }
    }
    requireReleasedHealth(desired, postReleaseHealth)
}

private fun ReleaseReceipt.requireReleasedHealth(
    desired: DesiredResourceState,
    postReleaseHealth: ResourceHealth,
) {
    require(released) { "Resource release was not confirmed" }
    require(postReleaseHealth.key == desired.key) { "Post-release health resource mismatch" }
    require(postReleaseHealth.status == ResourceHealthStatus.INACTIVE) { "Released resource is not inactive" }
    require(
        postReleaseHealth.generation == null &&
            postReleaseHealth.profileId == null &&
            postReleaseHealth.expectedProfileSha256 == null &&
            postReleaseHealth.appliedProfileSha256 == null &&
            postReleaseHealth.failureReason == null,
    ) { "Released resource retained state or failure evidence" }
}

private fun BoundResourceReceipt.requireBindingMatches(desired: DesiredResourceState, operation: String) {
    require(key == desired.key) { "$operation receipt resource mismatch" }
    require(generation == desired.generation) { "Stale ${operation.lowercase()} receipt generation" }
    require(profileId == desired.profile?.id) { "$operation receipt profile mismatch" }
    require(expectedProfileSha256 == desired.profile?.expectedSha256) {
        "$operation receipt expected digest mismatch"
    }
}

private fun BoundResourceReceipt.requireAppliedEvidence(desired: DesiredResourceState, operation: String) {
    require(appliedProfileSha256 == desired.profile?.expectedSha256) {
        "$operation receipt applied digest mismatch"
    }
}

private fun validateBindingShape(
    profileId: String?,
    expectedProfileSha256: Sha256Digest?,
    appliedProfileSha256: Sha256Digest?,
) {
    require(profileId == null || PROFILE_ID.matches(profileId)) { "Invalid resource profile ID" }
    require((profileId == null) == (expectedProfileSha256 == null)) {
        "Profile ID and expected digest must both be present or absent"
    }
    require(profileId != null || appliedProfileSha256 == null) {
        "Inactive resource evidence must use exact null profile and digests"
    }
}

private val PROFILE_ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
