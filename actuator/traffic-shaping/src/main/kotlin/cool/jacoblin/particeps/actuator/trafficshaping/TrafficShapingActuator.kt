package cool.jacoblin.particeps.actuator.trafficshaping

import android.content.Context
import android.os.SystemClock
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ApplyReceipt
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.FlushReceipt
import cool.jacoblin.particeps.core.resource.PrepareReceipt
import cool.jacoblin.particeps.core.resource.PeriodicResourceAuditSource
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.ReleaseReceipt
import cool.jacoblin.particeps.core.resource.ResumeReceipt
import cool.jacoblin.particeps.core.resource.ResourceAuditEvidence
import cool.jacoblin.particeps.core.resource.ResourceAuditReceipt
import cool.jacoblin.particeps.core.resource.ResourceAuditRequest
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceHealth
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailure
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailureListener
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.SuspendReceipt
import cool.jacoblin.particeps.core.resource.VerifyReceipt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class TrafficShapingActuator internal constructor(
    private val platform: TrafficShapingPlatform,
    targetPackages: TargetPackageSet,
    private val monotonicMillis: () -> Long,
) : StatefulResourceActuator, PeriodicResourceAuditSource {
    override val key = ResourceKey(ResourceKind.ACTUATOR, RESOURCE_ID)
    override val supportsHotProfileSwap: Boolean = true
    override val sourceId = EventSourceId(AUDIT_SOURCE_ID)
    override val schemaVersion: Int = 1
    override val intervalSeconds: Long = 60

    private val operations = Mutex()
    private val stateLock = Any()
    private val targetPackageListSha256 = Sha256Digest.of(
        targetPackages.packages.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
            .toByteArray(Charsets.UTF_8),
    )
    private var terminalFailureListener: ResourceTerminalFailureListener? = null
    private var desiredGeneration: ResourceGeneration? = null
    private var desiredProfile: SignedResourceProfile? = null
    private var appliedProfileSha256: Sha256Digest? = null
    private var preparedOperation: PreparedOperation? = null
    private var lastReleaseReceipt: ReleaseReceipt? = null
    private var verifiedVpnGenerationId: String? = null
    private var activationDeadlineMillis: Long? = null
    private var resourceHealth = ResourceHealth(
        key = key,
        status = ResourceHealthStatus.INACTIVE,
        generation = null,
        profileId = null,
        expectedProfileSha256 = null,
        appliedProfileSha256 = null,
        failureReason = null,
    )

    init {
        platform.setTerminalFailureListener(::onPlatformTerminalFailure)
    }

    override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) {
        synchronized(stateLock) { terminalFailureListener = listener }
    }

    override suspend fun prepare(
        desired: DesiredResourceState,
        requestId: String,
    ): PrepareReceipt = operations.withLock {
        val profile = requireDesiredProfile(desired)
        health().takeIf { it.status == ResourceHealthStatus.FAILED }?.let { failed ->
            throw TrafficShapingActuatorException(requireNotNull(failed.failureReason))
        }
        val currentGeneration = synchronized(stateLock) { desiredGeneration }
        if (currentGeneration != null) {
            require(desired.generation >= currentGeneration) { "Stale traffic-shaping prepare generation" }
            if (desired.generation == currentGeneration) {
                require(profile == synchronized(stateLock) { desiredProfile }) {
                    "A traffic-shaping generation cannot identify two profiles"
                }
            }
        }
        val operation = PreparedOperation(desired, requestId)
        synchronized(stateLock) {
            val previous = preparedOperation
            check(previous == null || previous == operation) {
                "Another traffic-shaping operation is already prepared"
            }
            preparedOperation = operation
            lastReleaseReceipt = null
        }
        activationDeadlineMillis = monotonicMillis().saturatedAdd(ACTIVATION_TIMEOUT_MILLIS)
        runWithinActivationDeadline { platform.prepare(requestId) }
        updateHealthUnlessFailed(ResourceHealthStatus.PREPARED, desired, null)
        PrepareReceipt(
            key = key,
            generation = desired.generation,
            profileId = profile.id,
            expectedProfileSha256 = profile.expectedSha256,
            appliedProfileSha256 = null,
            requestId = requestId,
        )
    }

    override suspend fun suspendAt(
        desired: DesiredResourceState,
        boundary: ResearchTime,
    ): SuspendReceipt = operations.withLock {
        val applied = requireCurrentMatches(desired)
        if (desired.profile != null) {
            runTimedOperation { platform.suspendForwarding() }
            updateHealthUnlessFailed(ResourceHealthStatus.SUSPENDED, desired, applied)
        }
        SuspendReceipt(
            key = key,
            generation = desired.generation,
            profileId = desired.profile?.id,
            expectedProfileSha256 = desired.profile?.expectedSha256,
            appliedProfileSha256 = applied,
            boundary = boundary,
        )
    }

    override suspend fun flushThrough(
        desired: DesiredResourceState,
        boundary: ResearchTime,
        cursor: String?,
    ): FlushReceipt =
        operations.withLock {
            val applied = requireCurrentMatches(desired)
            val snapshot = if (desired.profile == null) {
                null
            } else {
                requireNotNull(platform.snapshot()) { "Traffic-shaping flush requires native evidence" }.also {
                    require(it.profileSha256 == applied) { "Traffic-shaping flush profile digest mismatch" }
                    require(it.vpnGenerationId == synchronized(stateLock) { verifiedVpnGenerationId }) {
                        "Traffic-shaping flush VPN generation mismatch"
                    }
                }
            }
            FlushReceipt(
                key = key,
                generation = desired.generation,
                profileId = desired.profile?.id,
                expectedProfileSha256 = desired.profile?.expectedSha256,
                appliedProfileSha256 = applied,
                boundary = boundary,
                cursor = snapshot?.let { "native-generation:${it.nativeGeneration}" } ?: cursor,
                complete = true,
            )
        }

    override suspend fun apply(desired: DesiredResourceState): ApplyReceipt = operations.withLock {
        require(desired.key == key) { "Traffic-shaping resource key mismatch" }
        val profile = requireDesiredProfile(desired)
        val prepared = synchronized(stateLock) { preparedOperation }
        checkNotNull(prepared) { "Traffic-shaping apply was not prepared" }
        require(prepared.desired == desired) { "Traffic-shaping apply does not match its prepared state" }
        health().takeIf { it.status == ResourceHealthStatus.FAILED }?.let { failed ->
            throw TrafficShapingActuatorException(requireNotNull(failed.failureReason))
        }
        val currentGeneration = synchronized(stateLock) { desiredGeneration }
        if (currentGeneration != null && desired.generation < currentGeneration) {
            throw TrafficShapingActuatorException(TrafficShapingFailureReason.STALE_GENERATION)
        }
        if (currentGeneration == desired.generation) {
            require(profile == synchronized(stateLock) { desiredProfile }) {
                "A resource generation cannot identify two profiles"
            }
            synchronized(stateLock) { preparedOperation = null }
            return@withLock receiptFor(desired)
        }

        ensureActivationDeadline()
        val applied = runWithinActivationDeadline { platform.apply(profile) }
        if (applied != profile.expectedSha256) {
            synchronized(stateLock) {
                desiredGeneration = desired.generation
                desiredProfile = profile
                appliedProfileSha256 = applied
                preparedOperation = null
            }
            onPlatformTerminalFailure(TrafficShapingFailureReason.PROFILE_MISMATCH)
            throw TrafficShapingActuatorException(TrafficShapingFailureReason.PROFILE_MISMATCH)
        }
        synchronized(stateLock) {
            resourceHealth.takeIf { it.status == ResourceHealthStatus.FAILED }?.let { failed ->
                throw TrafficShapingActuatorException(requireNotNull(failed.failureReason))
            }
            desiredGeneration = desired.generation
            desiredProfile = profile
            appliedProfileSha256 = applied
            preparedOperation = null
            resourceHealth = ResourceHealth(
                key = key,
                status = ResourceHealthStatus.SUSPENDED,
                generation = desired.generation,
                profileId = profile.id,
                expectedProfileSha256 = profile.expectedSha256,
                appliedProfileSha256 = applied,
                failureReason = null,
            )
        }
        receiptFor(desired)
    }

    override suspend fun verify(desired: DesiredResourceState): VerifyReceipt = operations.withLock {
        require(desired.key == key) { "Traffic-shaping resource key mismatch" }
        val profile = requireDesiredProfile(desired)
        requireCurrentMatches(desired)
        val proof = runWithinActivationDeadline {
            platform.verify(profile.expectedSha256)
        }
        health().takeIf { it.status == ResourceHealthStatus.FAILED }?.let { failed ->
            return@withLock VerifyReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                proof.appliedProfileSha256,
                false,
                requireNotNull(failed.failureReason),
            )
        }
        val failureReason = proof.failureReason(profile.expectedSha256)
        if (failureReason != null) {
            onPlatformTerminalFailure(failureReason)
            return@withLock VerifyReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                proof.appliedProfileSha256,
                false,
                failureReason,
            )
        }
        activationDeadlineMillis = null
        synchronized(stateLock) {
            resourceHealth.takeIf { it.status == ResourceHealthStatus.FAILED }?.let { failed ->
                return@withLock VerifyReceipt(
                    key,
                    desired.generation,
                    profile.id,
                    profile.expectedSha256,
                    proof.appliedProfileSha256,
                    false,
                    requireNotNull(failed.failureReason),
                )
            }
            appliedProfileSha256 = proof.appliedProfileSha256
            verifiedVpnGenerationId = requireNotNull(proof.vpnGenerationId)
            resourceHealth = ResourceHealth(
                key = key,
                status = ResourceHealthStatus.APPLIED,
                generation = desired.generation,
                profileId = profile.id,
                expectedProfileSha256 = profile.expectedSha256,
                appliedProfileSha256 = proof.appliedProfileSha256,
                failureReason = null,
            )
        }
        VerifyReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            proof.appliedProfileSha256,
            true,
            null,
        )
    }

    override suspend fun resume(desired: DesiredResourceState): ResumeReceipt = operations.withLock {
        val profile = requireDesiredProfile(desired)
        val digest = requireNotNull(requireCurrentMatches(desired)) {
            "Cannot resume inactive traffic shaping"
        }
        runTimedOperation { platform.resumeForwarding() }
        updateHealthUnlessFailed(ResourceHealthStatus.APPLIED, desired, digest)
        ResumeReceipt(
            key = key,
            generation = desired.generation,
            profileId = profile.id,
            expectedProfileSha256 = profile.expectedSha256,
            appliedProfileSha256 = digest,
            resumed = true,
            failureReason = null,
        )
    }

    override suspend fun release(desired: DesiredResourceState): ReleaseReceipt = operations.withLock {
        require(desired.key == key) { "Traffic-shaping release resource mismatch" }
        synchronized(stateLock) { lastReleaseReceipt }?.takeIf { receipt ->
            synchronized(stateLock) {
                desiredGeneration == null && preparedOperation == null && receipt.matches(desired)
            }
        }?.let { return@withLock it }
        val applied = synchronized(stateLock) {
            val activeGeneration = desiredGeneration
            val activeProfile = desiredProfile
            val pending = preparedOperation
            when {
                pending?.desired == desired &&
                    (activeGeneration != desired.generation || activeProfile != desired.profile) -> null
                activeGeneration != null && activeProfile != null -> {
                    require(desired.generation == activeGeneration) { "Stale traffic-shaping release generation" }
                    require(desired.profile == activeProfile) { "Traffic-shaping release profile mismatch" }
                    appliedProfileSha256
                }
                pending?.desired == desired -> null
                pending != null -> throw IllegalArgumentException(
                    "Traffic-shaping release does not match prepared state",
                )
                else -> {
                    require(desired.profile == null) { "Traffic shaping is inactive for the release state" }
                    null
                }
            }
        }
        runTimedOperation { platform.release() }
        activationDeadlineMillis = null
        synchronized(stateLock) {
            desiredGeneration = null
            desiredProfile = null
            appliedProfileSha256 = null
            verifiedVpnGenerationId = null
            preparedOperation = null
        }
        updateHealth(ResourceHealthStatus.INACTIVE, null, null, null)
        ReleaseReceipt(
            key = key,
            generation = desired.generation,
            profileId = desired.profile?.id,
            expectedProfileSha256 = desired.profile?.expectedSha256,
            appliedProfileSha256 = applied,
            evidence = when {
                desired.profile == null -> ReleaseEvidence.INACTIVE
                applied == null -> ReleaseEvidence.NOT_APPLIED
                else -> ReleaseEvidence.APPLIED
            },
            released = true,
        ).also { receipt -> synchronized(stateLock) { lastReleaseReceipt = receipt } }
    }

    override fun health(): ResourceHealth = synchronized(stateLock) { resourceHealth }

    fun snapshot(): TrafficShapingCounterSnapshot? = platform.snapshot()

    override suspend fun audit(request: ResourceAuditRequest): ResourceAuditReceipt = operations.withLock {
        val evidence = request.evidence
        require(evidence.key == key) { "Traffic audit resource mismatch" }
        require(evidence.generation == synchronized(stateLock) { desiredGeneration }) {
            "Traffic audit generation is stale"
        }
        val profile = requireNotNull(synchronized(stateLock) { desiredProfile }) {
            "Traffic audit requires an applied profile"
        }
        require(evidence.profileId == profile.id) { "Traffic audit profile mismatch" }
        require(evidence.appliedProfileSha256 == profile.expectedSha256) {
            "Traffic audit digest mismatch"
        }
        require(evidence.appliedProfileSha256 == synchronized(stateLock) { appliedProfileSha256 }) {
            "Traffic audit is not bound to verified native evidence"
        }
        val vpnGenerationId = requireNotNull(synchronized(stateLock) { verifiedVpnGenerationId }) {
            "Traffic audit requires a verified VPN generation"
        }
        val profileCaps = TrafficProfileCaps.decode(profile)
        val drafts = when (request) {
            is ResourceAuditRequest.EpochActivated -> listOf(
                EventDraft(
                    EventTypeKey(sourceId, schemaVersion, PROFILE_APPLIED_EVENT),
                    request.observedAt,
                    buildMap {
                        put("activation_research_time", researchTimeJson(request.activatedAt))
                        put("applied_profile_sha256", evidence.appliedProfileSha256.value)
                        put("condition_epoch_id", request.conditionEpochId.value)
                        profileCaps.downlinkKbps?.let { put("downlink_kbps", it.toString()) }
                        put("profile_id", evidence.profileId)
                        put("resource_generation", evidence.generation.toString())
                        put("signed_configuration_sha256", request.signedConfigurationSha256.value)
                        put("target_package_list_sha256", targetPackageListSha256.value)
                        profileCaps.uplinkKbps?.let { put("uplink_kbps", it.toString()) }
                        put("verification_completed_research_time", researchTimeJson(request.observedAt))
                        put("vpn_generation_id", vpnGenerationId)
                    },
                ),
            )
            is ResourceAuditRequest.Periodic -> listOf(
                snapshotDraft(
                    request,
                    requireVerifiedSnapshot(evidence, vpnGenerationId),
                    "PERIODIC",
                    request.logicalDeadline,
                ),
            )
            is ResourceAuditRequest.EpochBoundary -> {
                val snapshot = requireVerifiedSnapshot(evidence, vpnGenerationId)
                listOf(
                    snapshotDraft(request, snapshot, "EPOCH_BOUNDARY", request.boundary),
                    EventDraft(
                        EventTypeKey(sourceId, schemaVersion, PROFILE_REMOVED_EVENT),
                        request.observedAt,
                        counterFields(request, snapshot) + mapOf(
                            "boundary_research_time" to researchTimeJson(request.boundary),
                            "removal_reason" to request.reason.name,
                        ),
                    ),
                )
            }
        }
        ResourceAuditReceipt(evidence, drafts)
    }

    private fun requireVerifiedSnapshot(
        evidence: ResourceAuditEvidence,
        vpnGenerationId: String,
    ): TrafficShapingCounterSnapshot {
        val snapshot = requireNotNull(platform.snapshot()) { "Traffic counters are unavailable" }
        require(snapshot.profileSha256 == evidence.appliedProfileSha256) {
            "Traffic counter profile digest mismatch"
        }
        require(snapshot.vpnGenerationId == vpnGenerationId) { "Traffic counter VPN generation mismatch" }
        return snapshot
    }

    private fun snapshotDraft(
        request: ResourceAuditRequest,
        snapshot: TrafficShapingCounterSnapshot,
        reason: String,
        logicalDeadline: ResearchTime,
    ) = EventDraft(
        EventTypeKey(sourceId, schemaVersion, SNAPSHOT_EVENT),
        request.observedAt,
        counterFields(request, snapshot) + mapOf(
            "logical_deadline_research_time" to researchTimeJson(logicalDeadline),
            "observation_research_time" to researchTimeJson(request.observedAt),
            "snapshot_reason" to reason,
        ),
    )

    private fun counterFields(
        request: ResourceAuditRequest,
        snapshot: TrafficShapingCounterSnapshot,
    ): Map<String, String> = mapOf(
        "condition_epoch_id" to request.conditionEpochId.value,
        "downlink_bytes" to snapshot.downlinkBytes.toString(),
        "downlink_packets" to snapshot.downlinkPackets.toString(),
        "downlink_throttled_nanoseconds" to snapshot.downlinkThrottledNanos.toString(),
        "profile_id" to request.evidence.profileId,
        "resource_generation" to request.evidence.generation.toString(),
        "uplink_bytes" to snapshot.uplinkBytes.toString(),
        "uplink_packets" to snapshot.uplinkPackets.toString(),
        "uplink_throttled_nanoseconds" to snapshot.uplinkThrottledNanos.toString(),
        "vpn_generation_id" to snapshot.vpnGenerationId,
    )

    private fun receiptFor(desired: DesiredResourceState): ApplyReceipt = ApplyReceipt(
        key = key,
        generation = desired.generation,
        profileId = desired.profile?.id,
        expectedProfileSha256 = desired.profile?.expectedSha256,
        appliedProfileSha256 = synchronized(stateLock) { appliedProfileSha256 },
    )

    private fun requireDesiredProfile(desired: DesiredResourceState): SignedResourceProfile {
        require(desired.key == key) { "Traffic-shaping resource key mismatch" }
        val profile = requireNotNull(desired.profile) { "Traffic-shaping operation requires an active profile" }
        TrafficProfileCaps.decode(profile)
        return profile
    }

    private fun requireCurrentMatches(desired: DesiredResourceState): Sha256Digest? {
        require(desired.key == key) { "Traffic-shaping resource key mismatch" }
        val generation = synchronized(stateLock) { desiredGeneration }
        val profile = synchronized(stateLock) { desiredProfile }
        val applied = synchronized(stateLock) { appliedProfileSha256 }
        if (generation == null && profile == null) {
            require(desired.profile == null) { "Traffic shaping is inactive for the requested state" }
            require(applied == null) { "Inactive traffic shaping retained applied evidence" }
            return null
        }
        require(generation == desired.generation) { "Stale traffic-shaping generation" }
        require(profile == desired.profile) { "Traffic-shaping profile or digest mismatch" }
        require(applied == desired.profile?.expectedSha256) { "Traffic-shaping applied digest mismatch" }
        return applied
    }

    private fun ReleaseReceipt.matches(desired: DesiredResourceState): Boolean =
        key == desired.key &&
            generation == desired.generation &&
            profileId == desired.profile?.id &&
            expectedProfileSha256 == desired.profile?.expectedSha256

    private fun ensureActivationDeadline() {
        if (activationDeadlineMillis == null) {
            activationDeadlineMillis = monotonicMillis().saturatedAdd(ACTIVATION_TIMEOUT_MILLIS)
        }
    }

    private suspend fun <T> runWithinActivationDeadline(block: suspend () -> T): T {
        val deadline = activationDeadlineMillis
            ?: throw TrafficShapingActuatorException(TrafficShapingFailureReason.ACTIVATION_TIMEOUT)
        val remaining = deadline - monotonicMillis()
        if (remaining <= 0) {
            onPlatformTerminalFailure(TrafficShapingFailureReason.ACTIVATION_TIMEOUT)
            throw TrafficShapingActuatorException(TrafficShapingFailureReason.ACTIVATION_TIMEOUT)
        }
        return try {
            withTimeout(remaining) { block() }
        } catch (failure: TimeoutCancellationException) {
            onPlatformTerminalFailure(TrafficShapingFailureReason.ACTIVATION_TIMEOUT)
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.ACTIVATION_TIMEOUT,
                failure,
            )
        }
    }

    private suspend fun <T> runTimedOperation(block: suspend () -> T): T = try {
        withTimeout(ACTIVATION_TIMEOUT_MILLIS) { block() }
    } catch (failure: TimeoutCancellationException) {
        onPlatformTerminalFailure(TrafficShapingFailureReason.ACTIVATION_TIMEOUT)
        throw TrafficShapingActuatorException(TrafficShapingFailureReason.ACTIVATION_TIMEOUT, failure)
    }

    private fun onPlatformTerminalFailure(reason: String) {
        val listener: ResourceTerminalFailureListener?
        val generation: ResourceGeneration?
        synchronized(stateLock) {
            if (resourceHealth.status == ResourceHealthStatus.FAILED) return
            val profile = desiredProfile ?: preparedOperation?.desired?.profile
            val healthGeneration = desiredGeneration ?: preparedOperation?.desired?.generation
            generation = healthGeneration
            resourceHealth = ResourceHealth(
                key = key,
                status = ResourceHealthStatus.FAILED,
                generation = healthGeneration,
                profileId = profile?.id,
                expectedProfileSha256 = profile?.expectedSha256,
                appliedProfileSha256 = null,
                failureReason = reason,
            )
            listener = terminalFailureListener
        }
        listener?.onTerminalFailure(ResourceTerminalFailure(key, generation, reason))
    }

    private fun updateHealth(
        status: ResourceHealthStatus,
        desired: DesiredResourceState?,
        digest: Sha256Digest?,
        failureReason: String?,
    ) {
        synchronized(stateLock) {
            resourceHealth = ResourceHealth(
                key = key,
                status = status,
                generation = desired?.generation,
                profileId = desired?.profile?.id,
                expectedProfileSha256 = desired?.profile?.expectedSha256,
                appliedProfileSha256 = digest,
                failureReason = failureReason,
            )
        }
    }

    private fun updateHealthUnlessFailed(
        status: ResourceHealthStatus,
        desired: DesiredResourceState,
        digest: Sha256Digest?,
    ) {
        synchronized(stateLock) {
            resourceHealth.takeIf { it.status == ResourceHealthStatus.FAILED }?.let { failed ->
                throw TrafficShapingActuatorException(requireNotNull(failed.failureReason))
            }
            resourceHealth = ResourceHealth(
                key = key,
                status = status,
                generation = desired.generation,
                profileId = desired.profile?.id,
                expectedProfileSha256 = desired.profile?.expectedSha256,
                appliedProfileSha256 = digest,
                failureReason = null,
            )
        }
    }

    private data class PreparedOperation(
        val desired: DesiredResourceState,
        val requestId: String,
    )

    companion object {
        const val RESOURCE_ID = "traffic-shaping.v1"
        const val AUDIT_SOURCE_ID = "traffic_shaping.v1"
        const val ACTIVATION_TIMEOUT_MILLIS = 10_000L

        fun createAndroid(
            context: Context,
            targetPackages: List<String>,
            notificationFactory: TrafficShapingNotificationFactory,
        ): TrafficShapingActuator {
            val targets = TargetPackageSet.of(targetPackages)
            return TrafficShapingActuator(
                platform = AndroidTrafficShapingPlatform(
                    context.applicationContext,
                    targets,
                    notificationFactory,
                ),
                targetPackages = targets,
                monotonicMillis = SystemClock::elapsedRealtime,
            )
        }

        private const val PROFILE_APPLIED_EVENT = "TRAFFIC_SHAPING_PROFILE_APPLIED"
        private const val SNAPSHOT_EVENT = "TRAFFIC_SHAPING_SNAPSHOT"
        private const val PROFILE_REMOVED_EVENT = "TRAFFIC_SHAPING_PROFILE_REMOVED"
    }
}

private data class TrafficProfileCaps(val downlinkKbps: Int?, val uplinkKbps: Int?) {
    companion object {
        private val CANONICAL_PROFILE = Regex(
            """\{"downlink_kbps":(null|[1-9][0-9]{0,6}),"id":"([a-z0-9][a-z0-9-]{2,63})","uplink_kbps":(null|[1-9][0-9]{0,6})\}""",
        )

        fun decode(profile: SignedResourceProfile): TrafficProfileCaps {
            val encoded = profile.canonicalBytes.toString(Charsets.UTF_8)
            val match = requireNotNull(CANONICAL_PROFILE.matchEntire(encoded)) {
                "Traffic profile is not exact canonical Protocol v1"
            }
            require(match.groupValues[2] == profile.id) { "Traffic profile ID mismatch" }
            fun cap(value: String): Int? = value.takeUnless { it == "null" }?.toInt()?.also {
                require(it in 1..1_000_000) { "Traffic cap is outside Protocol v1 bounds" }
            }
            return TrafficProfileCaps(cap(match.groupValues[1]), cap(match.groupValues[3]))
        }
    }
}

private fun researchTimeJson(time: ResearchTime): String = buildString {
    append("{\"boot_session_id\":\"")
    append(time.bootSessionId)
    append("\",\"monotonic_time_nanos\":\"")
    append(time.elapsedRealtimeNanos)
    append("\",\"wall_time_utc_millis\":\"")
    append(time.wallTimeUtcMillis)
    append("\"}")
}

private fun Long.saturatedAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
