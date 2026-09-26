package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.PeriodicResourceAuditSource
import cool.jacoblin.particeps.core.resource.ResourceAuditEvidence
import cool.jacoblin.particeps.core.resource.ResourceAuditRemovalReason
import cool.jacoblin.particeps.core.resource.ResourceAuditRequest
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.Sha256Digest

/**
 * The periodic resource audits of an epoch: their activation and boundary receipts and their
 * durable timers. Extracted from [ExperimentRuntime]; not a concurrent actor. Every member runs on
 * the caller's coroutine while the caller holds the runtime mutex, and suspends only in audit
 * source calls. It reads runtime state through [RuntimeMemory] at use time and never retains it.
 */
internal class ResourceAuditTrail(private val ctx: RuntimeContext) {
    private val memory = ctx.memory

    suspend fun activateResourceAuditsLocked(
        epoch: ConditionEpoch,
        vector: AppliedResourceVector,
        now: ResearchTime,
    ): ResourceAuditBatch {
        val events = mutableListOf<EventDraft>()
        val mutations = mutableListOf<RuntimeMutation>()
        val intents = mutableListOf<TimerIntent>()
        vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
            val source = ctx.hosts.getValue(applied.key).auditSource ?: return@forEach
            val evidence = applied.auditEvidence()
            val receipt = source.audit(
                ResourceAuditRequest.EpochActivated(
                    evidence = evidence,
                    conditionEpochId = epoch.id,
                    observedAt = now,
                    activatedAt = epoch.activatedAt,
                    signedConfigurationSha256 = Sha256Digest(ctx.study.configurationSha256),
                ),
            )
            require(receipt.evidence == evidence) { "Resource audit activation evidence mismatch" }
            events += RuntimeEventFactory.validateResourceAudit(source, receipt, epoch, now)
            val timer = resourceAuditTimer(source, evidence, epoch, now)
            require(timer.id !in memory.resourceAuditTimers) { "Duplicate resource audit timer" }
            events += RuntimeEventFactory.timerScheduled(timer, now)
            mutations += upsertResourceAuditTimer(timer)
            intents += TimerIntent.Schedule(timer)
        }
        return ResourceAuditBatch(events, mutations, PostCommitEffects(timerIntents = intents))
    }

    suspend fun deactivateResourceAuditsLocked(
        epoch: ConditionEpoch,
        vector: AppliedResourceVector,
        boundary: ResearchTime,
        reason: ResourceAuditRemovalReason,
    ): ResourceAuditBatch {
        val events = mutableListOf<EventDraft>()
        vector.resources.filter { it.status == AppliedResourceStatus.APPLIED }.forEach { applied ->
            val source = ctx.hosts.getValue(applied.key).auditSource ?: return@forEach
            val evidence = applied.auditEvidence()
            val timer = memory.resourceAuditTimers.values.singleOrNull {
                it.producerKey == resourceAuditProducerKey(applied.key)
            } ?: error("Applied auditable resource has no durable audit timer")
            require(timer.generation == evidence.generation.value) { "Stale resource audit timer generation" }
            require(timer.id == resourceAuditTimerId(source, evidence, epoch, timer.causalSequence, timer.target)) {
                "Resource audit timer is not bound to its epoch evidence"
            }
            val receipt = source.audit(
                ResourceAuditRequest.EpochBoundary(
                    evidence = evidence,
                    conditionEpochId = epoch.id,
                    observedAt = boundary,
                    boundary = boundary,
                    reason = reason,
                ),
            )
            require(receipt.evidence == evidence) { "Resource audit boundary evidence mismatch" }
            events += RuntimeEventFactory.validateResourceAudit(source, receipt, epoch, boundary)
        }
        val retirement = retireResourceAuditTimersLocked(boundary, "LIFECYCLE_ENDED")
        return ResourceAuditBatch(
            events = events + retirement.events,
            mutations = retirement.mutations,
            effects = retirement.effects,
        )
    }

    fun retireResourceAuditTimersLocked(
        now: ResearchTime,
        reason: String,
    ): ResourceAuditBatch {
        val timers = memory.resourceAuditTimers.values.sortedBy(DurableTimer::id)
        return ResourceAuditBatch(
            events = timers.map { RuntimeEventFactory.timerRetired(it, reason, now) },
            mutations = timers.map { removeResourceAuditTimer(it.id) },
            effects = PostCommitEffects(
                timerIntents = timers.map { TimerIntent.Retire(it.id, it.generation) },
            ),
        )
    }

    fun resourceAuditTimer(
        source: PeriodicResourceAuditSource,
        evidence: ResourceAuditEvidence,
        epoch: ConditionEpoch,
        now: ResearchTime,
    ): DurableTimer {
        val intervalNanos = Math.multiplyExact(source.intervalSeconds, NANOS_PER_SECOND)
        val target = TimerTarget.SameBootMonotonic(
            now.bootSessionId,
            Math.addExact(now.elapsedRealtimeNanos, intervalNanos),
        )
        val causalSequence = memory.automationCheckpoint.evaluatedThroughSequence.coerceAtLeast(1)
        return DurableTimer(
            id = resourceAuditTimerId(source, evidence, epoch, causalSequence, target),
            automationId = ctx.program.resourceBindings.single { it.resource == evidence.key }.id,
            generation = evidence.generation.value,
            causalSequence = causalSequence,
            producerKey = resourceAuditProducerKey(evidence.key),
            target = target,
            logicalDeadlineUtcMillis = Math.addExact(now.wallTimeUtcMillis, source.intervalSeconds * 1_000L),
            expiresAtUtcMillis = null,
        )
    }

    fun resourceAuditTimerId(
        source: PeriodicResourceAuditSource,
        evidence: ResourceAuditEvidence,
        epoch: ConditionEpoch,
        causalSequence: Long,
        target: TimerTarget,
    ): String {
        val monotonic = target as? TimerTarget.SameBootMonotonic
            ?: error("Resource audit timer must use same-boot monotonic time")
        return digest(
            "particeps-resource-audit-timer-v1",
            ctx.study.configurationSha256,
            source.sourceId.value,
            evidence.key.kind.name,
            evidence.key.id,
            evidence.generation.toString(),
            evidence.profileId,
            evidence.appliedProfileSha256.value,
            epoch.id.value,
            causalSequence.toString(),
            monotonic.bootSessionId,
            monotonic.elapsedRealtimeNanos.toString(),
        )
    }

    fun resourceAuditProducerKey(key: ResourceKey): String =
        "$RESOURCE_AUDIT_PRODUCER_PREFIX${key.kind.name.lowercase()}:${key.id}"
}
