package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.ActionRequest
import cool.jacoblin.particeps.core.automation.AutomationAudit
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.SuppressionReason
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.RegistrySourceKind
import cool.jacoblin.particeps.core.collector.SourceQualityGapReason
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.PeriodicResourceAuditSource
import cool.jacoblin.particeps.core.resource.ResourceAuditReceipt

internal object RuntimeEventFactory {
    fun validateResourceAudit(
        source: PeriodicResourceAuditSource,
        receipt: ResourceAuditReceipt,
        epoch: ConditionEpoch,
        expectedObservedAt: ResearchTime,
    ): List<EventDraft> {
        require(receipt.evidence.key == source.key) { "Resource audit receipt key mismatch" }
        val contract = requireNotNull(ProtocolEventSourceRegistry[source.sourceId.value]) {
            "Missing resource audit event source: ${source.sourceId.value}"
        }
        require(contract.sourceKind == RegistrySourceKind.SYSTEM) { "Resource audit source must be SYSTEM" }
        require(contract.emissionAuthority == cool.jacoblin.particeps.core.collector.RegistryEmissionAuthority.RUNTIME_ONLY) {
            "Resource audit source must be runtime-only"
        }
        require(contract.schemaVersion == source.schemaVersion) { "Resource audit source schema mismatch" }
        receipt.events.forEach { draft ->
            require(draft.type.sourceId == source.sourceId && draft.type.schemaVersion == source.schemaVersion) {
                "Resource audit draft source mismatch"
            }
            val event = requireNotNull(contract.events[draft.type.eventType]) {
                "Unknown resource audit event: ${draft.type.eventType}"
            }
            require(event.accepts(draft.fields)) { "Resource audit event violates its generated contract" }
            require(draft.observedTime == expectedObservedAt) { "Resource audit observed time mismatch" }
            require(draft.fields["condition_epoch_id"] == epoch.id.value) {
                "Resource audit event is not bound to the active epoch"
            }
            require(draft.fields["resource_generation"] == receipt.evidence.generation.toString()) {
                "Resource audit event generation evidence mismatch"
            }
            require(draft.fields["profile_id"] == receipt.evidence.profileId) {
                "Resource audit event profile evidence mismatch"
            }
            draft.fields["applied_profile_sha256"]?.let { digest ->
                require(digest == receipt.evidence.appliedProfileSha256.value) {
                    "Resource audit event digest evidence mismatch"
                }
            }
            draft.fields["signed_configuration_sha256"]?.let { digest ->
                require(digest == epoch.configurationSha256) {
                    "Resource audit event configuration evidence mismatch"
                }
            }
        }
        return receipt.events
    }

    fun lifecycle(
        type: String,
        commandId: String,
        previousState: ExperimentState?,
        currentState: ExperimentState,
        transitionReason: String,
        now: ResearchTime,
        causeSequence: Long? = null,
    ): EventDraft = system(
        STUDY_RUNTIME,
        type,
        now,
        buildMap {
            put("command_id", commandId)
            put("current_state", currentState.name)
            previousState?.takeIf { it in PUBLIC_LIFECYCLE_STATES }?.let { put("previous_state", it.name) }
            put("transition_reason", transitionReason)
            causeSequence?.let { put("cause_sequence", it.toString()) }
        },
    )

    fun qualityGap(
        sourceId: EventSourceId,
        reason: SourceQualityGapReason,
        now: ResearchTime,
    ): EventDraft = system(
        STUDY_RUNTIME,
        "SOURCE_QUALITY_GAP",
        now,
        mapOf("source_id" to sourceId.value, "reason" to reason.name),
    )

    fun epochActivated(
        epoch: ConditionEpoch,
        vector: AppliedResourceVector,
        reason: String,
        boundary: ResearchTime,
    ): EventDraft = system(
        STUDY_CONDITION,
        "CONDITION_EPOCH_ACTIVATED",
        boundary,
        mapOf(
            "activation_reason" to reason,
            "applied_resource_vector_sha256" to vector.conditionDigest.value,
            "boundary_research_time" to researchTimeJson(boundary),
            "condition_epoch_id" to epoch.id.value,
            "resource_vector_json" to vector.canonicalJson(),
            "signed_configuration_sha256" to epoch.configurationSha256,
        ),
    )

    fun epochDeactivated(
        epoch: ConditionEpoch,
        vector: AppliedResourceVector,
        reason: String,
        boundary: ResearchTime,
    ): EventDraft = system(
        STUDY_CONDITION,
        "CONDITION_EPOCH_DEACTIVATED",
        boundary,
        mapOf(
            "applied_resource_vector_sha256" to vector.conditionDigest.value,
            "boundary_research_time" to researchTimeJson(boundary),
            "condition_epoch_id" to epoch.id.value,
            "deactivation_reason" to reason,
            "resource_vector_json" to vector.canonicalJson(),
            "signed_configuration_sha256" to epoch.configurationSha256,
        ),
    )

    fun actionRequested(
        request: ActionRequest,
        conditionSha256: String,
        causalSequence: Long,
        now: ResearchTime,
    ): EventDraft = actionEvent(
        "ACTION_REQUESTED",
        request.actionId,
        request.automationId,
        request.interventionId,
        conditionSha256,
        causalRange(request.causalIdentity, causalSequence),
        request.logicalDeadlineUtcMillis?.let { logicalTime(now, it) } ?: now,
        now,
    )

    fun actionResult(
        action: DurableActionInvocation,
        succeeded: Boolean,
        failure: ActionExecutionFailure?,
        now: ResearchTime,
    ): EventDraft = actionEvent(
        if (succeeded) "ACTION_SUCCEEDED" else "ACTION_FAILED",
        action.actionId,
        action.automationId,
        action.interventionId,
        action.conditionSha256,
        CausalRange(action.causalSequence, action.causalSequence),
        action.logicalDeadlineUtcMillis?.let { logicalTime(now, it) } ?: now,
        now,
        failure?.name,
    )

    fun surveyOpened(action: DurableActionInvocation, now: ResearchTime): EventDraft = system(
        INTERVENTIONS,
        "SURVEY_OPENED",
        now,
        interventionFields(action),
    )

    fun surveyExpired(action: DurableActionInvocation, now: ResearchTime): EventDraft = system(
        INTERVENTIONS,
        "SURVEY_EXPIRED",
        now,
        interventionFields(action),
    )

    fun surveySubmitted(
        action: DurableActionInvocation,
        surveyId: String,
        answersJson: String,
        submittedAt: ResearchTime,
    ): EventDraft {
        val openedAt = requireNotNull(action.openedAt) { "Survey submission requires durable open time" }
        val scheduledUtc = action.logicalDeadlineUtcMillis ?: action.requestedAt.wallTimeUtcMillis
        return system(
            INTERVENTIONS,
            "SURVEY_SUBMITTED",
            submittedAt,
            interventionFields(action) + mapOf(
                "answers_json" to answersJson,
                "opened_time" to researchTimeJson(openedAt),
                "scheduled_time" to researchTimeJson(logicalTime(action.requestedAt, scheduledUtc)),
                "submitted_time" to researchTimeJson(submittedAt),
                "survey_id" to surveyId,
            ),
        )
    }

    fun automationAudit(
        audit: AutomationAudit,
        conditionSha256: String,
        causalSequence: Long,
        now: ResearchTime,
    ): EventDraft? {
        if (!audit.matched && audit.suppressionReason == null) return null
        val type = if (audit.suppressionReason == null) "AUTOMATION_MATCHED" else "AUTOMATION_SUPPRESSED"
        return system(
            AUTOMATION_RUNTIME,
            type,
            now,
            buildMap {
                put("automation_id", audit.automationId)
                val range = causalRange(audit.causalIdentity, causalSequence)
                put("causal_final_sequence", range.final.toString())
                put("causal_first_sequence", range.first.toString())
                put("condition_sha256", conditionSha256)
                put("generation", "1")
                put("logical_time", researchTimeJson(now))
                put("observed_time", researchTimeJson(now))
                audit.suppressionReason?.let { put("suppression_reason", it.registryReason()) }
            },
        )
    }

    fun timerScheduled(timer: DurableTimer, now: ResearchTime): EventDraft = timerEvent(
        type = "TIMER_SCHEDULED",
        timer = timer,
        now = now,
        includeCause = true,
    )

    fun timerDue(timer: DurableTimer, now: ResearchTime): EventDraft = timerEvent(
        type = "TIMER_DUE",
        timer = timer,
        now = now,
        includeCause = true,
    )

    fun timerRetired(
        timer: DurableTimer,
        reason: String,
        now: ResearchTime,
    ): EventDraft = timerEvent(
        type = "TIMER_RETIRED",
        timer = timer,
        now = now,
        retirementReason = reason,
    )

    fun researchTimeJson(time: ResearchTime): String = buildString {
        append("{\"boot_session_id\":")
        appendJsonString(time.bootSessionId)
        append(",\"monotonic_time_nanos\":")
        appendJsonString(time.elapsedRealtimeNanos.toString())
        append(",\"wall_time_utc_millis\":")
        appendJsonString(time.wallTimeUtcMillis.toString())
        append('}')
    }

    private fun actionEvent(
        type: String,
        actionId: String,
        automationId: String,
        interventionId: String,
        conditionSha256: String,
        causalRange: CausalRange,
        logicalTime: ResearchTime,
        observedTime: ResearchTime,
        failureReason: String? = null,
    ): EventDraft = system(
        AUTOMATION_RUNTIME,
        type,
        observedTime,
        buildMap {
            put("automation_id", automationId)
            put("causal_final_sequence", causalRange.final.toString())
            put("causal_first_sequence", causalRange.first.toString())
            put("condition_sha256", conditionSha256)
            failureReason?.let { put("failure_reason", it) }
            put("generation", "1")
            put("intervention_id", interventionId)
            put("invocation_id", actionId)
            put("logical_time", researchTimeJson(logicalTime))
            put("observed_time", researchTimeJson(observedTime))
        },
    )

    /** Reducer causal identities are closed-world; timer identities use the due-input fallback. */
    private fun causalRange(identity: String, fallback: Long): CausalRange {
        val parts = identity.split(':')
        val range = when (parts.firstOrNull()) {
            "event" -> parts.singleSequenceOrNull()?.let { CausalRange(it, it) }
            "range" -> if (parts.size == 3) {
                val first = parts[1].toLongOrNull()
                val final = parts[2].toLongOrNull()
                if (first != null && final != null) CausalRange(first, final) else null
            } else null
            "timer" -> CausalRange(fallback, fallback)
            else -> null
        } ?: error("Unknown reducer causal identity")
        require(range.first in 1..range.final && range.final <= fallback) {
            "Reducer causal identity is outside the committed reducer range"
        }
        return range
    }

    private fun List<String>.singleSequenceOrNull(): Long? =
        takeIf { size == 2 }?.get(1)?.toLongOrNull()

    private data class CausalRange(val first: Long, val final: Long)

    private fun interventionFields(action: DurableActionInvocation): Map<String, String> = mapOf(
        "intervention_id" to action.interventionId,
        "occurrence_id" to action.actionId,
        "scheduled_for_utc_millis" to (
            action.logicalDeadlineUtcMillis ?: action.requestedAt.wallTimeUtcMillis
            ).toString(),
        "trigger_id" to action.automationId,
    )

    private fun timerEvent(
        type: String,
        timer: DurableTimer,
        now: ResearchTime,
        includeCause: Boolean = false,
        retirementReason: String? = null,
    ): EventDraft = system(
        TIMER,
        type,
        now,
        buildMap {
            put("automation_id", timer.automationId)
            if (includeCause) put("causal_sequence", timer.causalSequence.toString())
            put("clock", timer.target.registryClock())
            put("generation", timer.generation.toString())
            put("logical_due_research_time", researchTimeJson(timerLogicalTarget(timer)))
            put("producer_key", timer.producerKey)
            retirementReason?.let { put("retirement_reason", it) }
            put("timer_id", timer.id)
        },
    )

    private fun system(
        sourceId: String,
        eventType: String,
        now: ResearchTime,
        fields: Map<String, String>,
    ): EventDraft {
        val source = requireNotNull(ProtocolEventSourceRegistry[sourceId]) { "Missing system event source: $sourceId" }
        require(source.sourceKind == RegistrySourceKind.SYSTEM) { "Runtime cannot author a collector source" }
        val contract = requireNotNull(source.events[eventType]) { "Unknown runtime event: $sourceId/$eventType" }
        require(contract.accepts(fields)) { "Runtime event violates its generated contract: $sourceId/$eventType" }
        return EventDraft(EventTypeKey(EventSourceId(sourceId), source.schemaVersion, eventType), now, fields)
    }

    /** Stable clock-domain coordinate; unlike a wakeup estimate it never changes across re-arming. */
    fun timerLogicalTarget(timer: DurableTimer): ResearchTime = when (val target = timer.target) {
        is TimerTarget.CalendarUtc -> ResearchTime(target.utcMillis, 0, CALENDAR_TIME_COORDINATE)
        is TimerTarget.ActiveElapsed -> ResearchTime(0, target.elapsedNanos, ACTIVE_TIME_COORDINATE)
        is TimerTarget.SameBootMonotonic -> ResearchTime(
            timer.logicalDeadlineUtcMillis ?: 0,
            target.elapsedRealtimeNanos,
            target.bootSessionId,
        )
    }

    private fun logicalTime(now: ResearchTime, wallTimeUtcMillis: Long): ResearchTime =
        ResearchTime(wallTimeUtcMillis, now.elapsedRealtimeNanos, now.bootSessionId)

    private fun TimerTarget.registryClock(): String = when (this) {
        is TimerTarget.CalendarUtc -> "CALENDAR_TIME"
        is TimerTarget.ActiveElapsed -> "ACTIVE_RUNNING_TIME"
        is TimerTarget.SameBootMonotonic -> "SAME_BOOT_MONOTONIC"
    }

    private fun SuppressionReason.registryReason(): String = when (this) {
        SuppressionReason.GUARD_FALSE -> "OPTIONAL_DEPENDENCY_FAILED"
        SuppressionReason.COOLDOWN -> "COOLDOWN"
        SuppressionReason.MAXIMUM_ACTIVATIONS -> "MAXIMUM_ACTIVATIONS"
        SuppressionReason.EXPIRED -> "AVAILABILITY_EXPIRED"
        SuppressionReason.STALE_TIMER -> "NOT_RUNNING"
    }

    private fun StringBuilder.appendJsonString(value: String) {
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

    private const val STUDY_RUNTIME = "study_runtime.v1"
    private const val STUDY_CONDITION = "study_condition.v1"
    private const val AUTOMATION_RUNTIME = "automation_runtime.v1"
    private const val TIMER = "timer.v1"
    private const val INTERVENTIONS = "interventions.v1"
    private const val ACTIVE_TIME_COORDINATE = "active-running-time"
    private const val CALENDAR_TIME_COORDINATE = "calendar-time"
    private val PUBLIC_LIFECYCLE_STATES = setOf(
        ExperimentState.ACTIVATING,
        ExperimentState.RUNNING,
        ExperimentState.PAUSING,
        ExperimentState.PAUSED,
        ExperimentState.COMPLETED,
        ExperimentState.WITHDRAWN,
    )
}
