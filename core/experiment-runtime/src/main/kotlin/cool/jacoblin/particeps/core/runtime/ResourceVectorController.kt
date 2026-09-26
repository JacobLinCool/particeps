package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DesiredProfile
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.requireAppliedMatches
import cool.jacoblin.particeps.core.resource.requireCleanupReleased
import cool.jacoblin.particeps.core.resource.requireInactiveMatches
import cool.jacoblin.particeps.core.resource.requireMatches
import cool.jacoblin.particeps.core.resource.requireReleased
import kotlinx.coroutines.CancellationException

/**
 * Drives every resource actuator through apply, suspend, flush, resume and release, and derives
 * the applied resource vector from durable state. Extracted from [ExperimentRuntime]; not a
 * concurrent actor. Every member runs on the caller's coroutine while the caller holds the runtime
 * mutex, and suspends only in actuator calls and the store append of the paused cleanup commit.
 * It reads runtime state through [RuntimeMemory] at use time and never retains it.
 */
internal class ResourceVectorController(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
) {
    private val memory = ctx.memory

    suspend fun suspendAppliedResourcesLocked(boundary: ResearchTime) {
        currentAppliedVector().resources.filter { it.status == AppliedResourceStatus.APPLIED }
            .sortedBy(AppliedResourceState::key)
            .forEach { applied ->
                val desired = desiredState(applied)
                requireNotNull(ctx.hosts.getValue(applied.key).actuator)
                    .suspendAt(desired, boundary)
                    .requireMatches(desired, boundary)
            }
    }

    fun activeRetrospectiveResourceKeys(): Set<ResourceKey> = memory.appliedResources.values
        .asSequence()
        .filter { it.status == AppliedResourceStatus.APPLIED && it.key.kind == ResourceKind.COLLECTOR }
        .map(AppliedResourceState::key)
        .filter { key -> ProtocolEventSourceRegistry[key.id]?.isRetrospective == true }
        .toSortedSet()

    suspend fun suspendAndFlushLocked(boundary: ResearchTime): Map<EventSourceId, String?> {
        val appliedByKey = currentAppliedVector().resources.associateBy(AppliedResourceState::key)
        appliedByKey.values.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
            val actuator = requireNotNull(ctx.hosts.getValue(applied.key).actuator)
            val desired = desiredState(applied)
            actuator.suspendAt(desired, boundary).requireMatches(desired, boundary)
        }
        val retrospectiveCursors = sortedMapOf<EventSourceId, String?>()
        ctx.hosts.values.sortedBy(RuntimeResourceHost::key).forEach { host ->
            val actuator = host.actuator ?: return@forEach
            val applied = appliedByKey.getValue(host.key)
            if (applied.status != AppliedResourceStatus.APPLIED) return@forEach
            val desired = desiredState(applied)
            val sourceId = if (host.key.kind == ResourceKind.COLLECTOR) EventSourceId(host.key.id) else null
            val cursor = if (sourceId != null) {
                memory.requireDocument().sourceCheckpoints[sourceId]?.cursor
            } else {
                null
            }
            val receipt = actuator.flushThrough(desired, boundary, cursor)
            receipt.requireMatches(desired, boundary)
            if (
                sourceId != null &&
                requireNotNull(ProtocolEventSourceRegistry[sourceId.value]).isRetrospective
            ) {
                retrospectiveCursors[sourceId] = receipt.cursor
            }
        }
        return retrospectiveCursors
    }

    suspend fun applyDesiredVectorLocked(
        desiredProfiles: Map<ResourceKey, DesiredProfile>,
        requestId: String,
    ): AppliedResourceVector {
        check(memory.pendingResourceContainment == null) { "A prior resource containment plan is unresolved" }
        val applied = mutableListOf<AppliedResourceState>()
        val verifiedApplied = mutableListOf<Pair<StatefulResourceActuator, DesiredResourceState>>()
        val verifiedInactive = sortedMapOf<ResourceKey, ResourceGeneration>()
        val attempted = sortedMapOf<ResourceKey, DesiredResourceState>()
        try {
            ctx.hosts.values.sortedBy(RuntimeResourceHost::key).forEach { host ->
                val desiredProfile = requireNotNull(desiredProfiles[host.key]) { "Missing desired resource state" }
                val profile = desiredProfile.profileId?.let { host.profiles.getValue(it) }
                val desired = DesiredResourceState(host.key, desiredProfile.generation, host.required, profile)
                val actuator = host.actuator
                val trustedDesired = memory.appliedResources[host.key]
                    ?.takeIf { it.status == AppliedResourceStatus.APPLIED }
                    ?.let(::desiredState)
                if (profile == null) {
                    val prior = memory.appliedResources[host.key]
                    if (actuator != null && prior?.status == AppliedResourceStatus.APPLIED) {
                        val priorDesired = desiredState(prior)
                        actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        verifiedInactive[host.key] = desired.generation
                    }
                    applied += AppliedResourceState(
                        host.key,
                        desired.generation,
                        null,
                        null,
                        AppliedResourceStatus.INACTIVE,
                        null,
                    )
                    return@forEach
                }
                if (actuator == null) {
                    if (host.required) throw RequiredResourceFailure()
                    applied += optionalFailure(host.key, desired, "RESOURCE_NOT_COMPILED")
                    return@forEach
                }
                try {
                    // Platform work can start before prepare returns, so persist this identity if
                    // containment becomes necessary at any later point in the call.
                    attempted[host.key] = desired
                    actuator.prepare(desired, requestId).requireMatches(desired, requestId)
                    actuator.apply(desired).requireMatches(desired)
                    actuator.verify(desired).requireMatches(desired)
                    attempted.remove(host.key)
                    verifiedApplied += actuator to desired
                    applied += AppliedResourceState(
                        host.key,
                        desired.generation,
                        profile.id,
                        profile.expectedSha256,
                        AppliedResourceStatus.APPLIED,
                        null,
                    )
                } catch (failure: Throwable) {
                    try {
                        val release = actuator.release(desired)
                        if (trustedDesired?.sameIdentity(desired) == true) {
                            release.requireReleased(desired, actuator.health())
                        } else {
                            release.requireCleanupReleased(desired, actuator.health())
                        }
                        attempted.remove(host.key)
                        verifiedInactive[host.key] = desired.generation
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                        attempted[host.key] = desired
                        throw RequiredResourceFailure()
                    }
                    if (failure is CancellationException) throw failure
                    if (host.required) throw RequiredResourceFailure()
                    applied += optionalFailure(host.key, desired, "RESOURCE_APPLY_FAILED")
                }
            }
        } catch (failure: Throwable) {
            var cleanupFailed = false
            verifiedApplied.asReversed().forEach { (actuator, desired) ->
                try {
                    actuator.release(desired).requireReleased(desired, actuator.health())
                    attempted.remove(desired.key)
                    verifiedInactive[desired.key] = desired.generation
                } catch (cleanupFailure: Throwable) {
                    cleanupFailed = true
                    attempted[desired.key] = desired
                    failure.addSuppressed(cleanupFailure)
                }
            }
            memory.pendingResourceContainment = ResourceContainment(verifiedInactive, attempted)
            if (failure is CancellationException && !cleanupFailed) throw failure
            throw RequiredResourceFailure()
        }
        memory.pendingResourceContainment = null
        return AppliedResourceVector(applied.sortedBy(AppliedResourceState::key))
    }

    suspend fun resumeAppliedVectorLocked(vector: AppliedResourceVector) {
        try {
            vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
                val actuator = requireNotNull(ctx.hosts.getValue(applied.key).actuator)
                val desired = desiredState(applied)
                actuator.resume(desired).requireMatches(desired)
                actuator.health().requireAppliedMatches(desired)
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw RequiredResourceFailure()
        }
    }

    suspend fun notifyAdmissionOpenedLocked(vector: AppliedResourceVector) {
        try {
            vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
                val actuator = requireNotNull(ctx.hosts.getValue(applied.key).actuator)
                val desired = desiredState(applied)
                actuator.onAdmissionOpened(desired).requireAppliedMatches(desired)
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw RequiredResourceFailure()
        }
    }

    suspend fun releaseAllResourcesLocked() {
        releaseVectorLocked(currentAppliedVector())
    }

    suspend fun releaseVectorLocked(vector: AppliedResourceVector) {
        vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }
            .sortedByDescending(AppliedResourceState::key)
            .forEach { applied ->
            val actuator = requireNotNull(ctx.hosts.getValue(applied.key).actuator)
            val desired = desiredState(applied)
            try {
                actuator.release(desired).requireReleased(desired, actuator.health())
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                throw RequiredResourceFailure()
            }
        }
    }

    suspend fun finalizePausedResourceCleanupLocked(recovery: Boolean) {
        val current = memory.requireDocument()
        require(current.state == ExperimentState.PAUSED && current.activeConditionEpoch == null) {
            "Resource cleanup finalization requires a closed paused study"
        }
        val trusted = currentAppliedVector().resources.associateBy(AppliedResourceState::key)
        val failures = mutableListOf<Throwable>()
        ctx.hosts.values.sortedByDescending(RuntimeResourceHost::key).forEach { host ->
            val actuator = host.actuator
            val prior = trusted.getValue(host.key)
            val attempted = memory.resourceCleanupAttempts[host.key]?.let(::cleanupDesiredState)
            val priorDesired = prior.takeIf { it.status == AppliedResourceStatus.APPLIED }?.let(::desiredState)
            val attemptedWasTrusted = attempted != null && priorDesired != null &&
                attempted.sameIdentity(priorDesired)
            if (actuator == null) {
                if (attempted != null || priorDesired != null) {
                    failures += RequiredResourceFailure()
                }
                return@forEach
            }
            try {
                if (recovery) {
                    val health = actuator.health()
                    if (runCatching { health.requireInactiveMatches(host.key) }.isSuccess) {
                        return@forEach
                    }
                    when {
                        attemptedWasTrusted && health.matchesTrustedApplied(requireNotNull(priorDesired)) ->
                            actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        priorDesired != null && health.matchesTrustedApplied(priorDesired) ->
                            actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        attempted != null && !attemptedWasTrusted && health.matchesCleanupAttempt(attempted) ->
                            actuator.release(attempted).requireCleanupReleased(attempted, actuator.health())
                        else -> throw RequiredResourceFailure()
                    }
                } else {
                    when {
                        attemptedWasTrusted -> {
                            val trustedDesired = requireNotNull(priorDesired)
                            actuator.release(trustedDesired).requireReleased(trustedDesired, actuator.health())
                        }
                        attempted != null ->
                            actuator.release(attempted).requireCleanupReleased(attempted, actuator.health())
                        priorDesired != null ->
                            actuator.release(priorDesired).requireReleased(priorDesired, actuator.health())
                        else -> Unit
                    }
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failures += failure
            }
        }
        if (failures.isNotEmpty()) {
            throw RequiredResourceFailure().also { aggregate -> failures.forEach(aggregate::addSuppressed) }
        }

        val resourceMutations = inactiveResourceMutations(memory.automationCheckpoint.desiredResources)
        val cleanupMutations = memory.resourceCleanupAttempts.keys.map(::removeResourceCleanup)
        val alreadyFinal = memory.appliedResources.values.all { it.status == AppliedResourceStatus.INACTIVE } &&
            memory.resourceCleanupAttempts.isEmpty()
        if (!alreadyFinal) {
            commitLog.appendCommitLocked(
                inputKind = EngineInputKind.RESOURCE_RESULT,
                checkpoint = memory.automationCheckpoint,
                state = ExperimentState.PAUSED,
                epoch = null,
                clock = current.clockCheckpoint,
                extraMutations = resourceMutations + cleanupMutations,
            )
        }
    }

    private fun desiredState(applied: AppliedResourceState): DesiredResourceState {
        require(applied.status == AppliedResourceStatus.APPLIED) { "Desired state requires an applied resource" }
        val host = ctx.hosts.getValue(applied.key)
        val profileId = requireNotNull(applied.profileId) { "Applied resource has no profile" }
        val profile = host.profiles.getValue(profileId)
        require(profile.expectedSha256 == applied.appliedProfileSha256) { "Applied resource digest mismatch" }
        return DesiredResourceState(applied.key, applied.desiredGeneration, host.required, profile)
    }

    private fun cleanupDesiredState(cleanup: DurableResourceCleanup): DesiredResourceState {
        val host = ctx.hosts.getValue(cleanup.key)
        val profile = host.profiles.getValue(cleanup.profileId)
        require(profile.expectedSha256 == cleanup.expectedProfileSha256) {
            "Durable cleanup profile digest mismatch"
        }
        return DesiredResourceState(cleanup.key, cleanup.generation, host.required, profile)
    }

    fun deriveRecoveryCleanupAttempts(
        trustedVector: AppliedResourceVector,
        desiredProfiles: Map<ResourceKey, DesiredProfile>,
    ): Map<ResourceKey, DurableResourceCleanup> {
        val trusted = trustedVector.resources.associateBy(AppliedResourceState::key)
        return desiredProfiles.mapNotNull { (key, desiredProfile) ->
            val profileId = desiredProfile.profileId ?: return@mapNotNull null
            val host = ctx.hosts.getValue(key)
            if (host.actuator == null) return@mapNotNull null
            val profile = host.profiles.getValue(profileId)
            val prior = trusted.getValue(key)
            val alreadyTrusted = prior.status == AppliedResourceStatus.APPLIED &&
                prior.desiredGeneration == desiredProfile.generation &&
                prior.profileId == profileId &&
                prior.appliedProfileSha256 == profile.expectedSha256
            if (alreadyTrusted) null else key to DurableResourceCleanup(
                key,
                desiredProfile.generation,
                profileId,
                profile.expectedSha256,
            )
        }.toMap()
    }

    private fun cool.jacoblin.particeps.core.resource.ResourceHealth.matchesCleanupAttempt(
        desired: DesiredResourceState,
    ): Boolean =
        key == desired.key &&
            generation == desired.generation &&
            profileId == desired.profile?.id &&
            expectedProfileSha256 == desired.profile?.expectedSha256 &&
            status in ATTEMPTED_CLEANUP_HEALTH_STATES

    private fun cool.jacoblin.particeps.core.resource.ResourceHealth.matchesTrustedApplied(
        desired: DesiredResourceState,
    ): Boolean =
        key == desired.key &&
            generation == desired.generation &&
            profileId == desired.profile?.id &&
            expectedProfileSha256 == desired.profile?.expectedSha256 &&
            appliedProfileSha256 == desired.profile?.expectedSha256 &&
            status in TRUSTED_APPLIED_HEALTH_STATES

    private fun DesiredResourceState.sameIdentity(other: DesiredResourceState): Boolean =
        key == other.key &&
            generation == other.generation &&
            profile?.id == other.profile?.id &&
            profile?.expectedSha256 == other.profile?.expectedSha256

    fun currentAppliedVector(): AppliedResourceVector = AppliedResourceVector(
        ctx.hosts.keys.map { key ->
            memory.appliedResources[key] ?: inactiveResource(key, memory.automationCheckpoint.desiredResources[key]?.generation ?: ResourceGeneration(1uL))
        }.sortedBy(AppliedResourceState::key),
    )

    fun newEpoch(vector: AppliedResourceVector, now: ResearchTime): ConditionEpoch = ConditionEpoch(
        id = ConditionEpochId(ctx.entropy.next(RuntimeEntropyKind.CONDITION_EPOCH_UUID)),
        configurationSha256 = ctx.study.configurationSha256,
        appliedResourceVectorSha256 = vector.conditionDigest.value,
        activatedAt = now,
    )

    fun inactiveResourceMutations(desired: Map<ResourceKey, DesiredProfile>): List<RuntimeMutation> =
        ctx.hosts.keys.map { key ->
            val generation = desired[key]?.generation
                ?: memory.appliedResources[key]?.desiredGeneration
                ?: ResourceGeneration(1uL)
            upsertResource(inactiveResource(key, generation))
        }
}
