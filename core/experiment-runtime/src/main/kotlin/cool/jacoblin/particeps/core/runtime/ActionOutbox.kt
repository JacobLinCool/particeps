package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SafetyPauseReason

/**
 * The durable action outbox: claims, results, survey transitions, expiry and the pending set that
 * platform adapters display or retract. Extracted from [ExperimentRuntime]; not a concurrent actor.
 * Every member runs on the caller's coroutine while the caller holds the runtime mutex, and suspends
 * only in the store append, the notifier and the containment port. It reads runtime state through
 * [RuntimeMemory] at use time and never retains it.
 */
internal class ActionOutbox(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val clockPolicy: RuntimeClockPolicy,
    private val containmentPort: RuntimeContainment,
) {
    private val memory = ctx.memory

    suspend fun claimActionLocked(actionId: String): DurableActionInvocation? {
        val currentDocument = memory.requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) return null
        val current = memory.actionInvocations[actionId] ?: return null
        if (current.state == RuntimeActionState.SUCCEEDED || current.state == RuntimeActionState.FAILED) {
            return null
        }
        val now = ctx.clocks.now()
        if (now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            expireActionLocked(current, now)
            return null
        }
        if (current.state == RuntimeActionState.CLAIMED || current.state == RuntimeActionState.OPENED) {
            return current
        }
        val claimed = current.copy(state = RuntimeActionState.CLAIMED)
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = memory.automationCheckpoint,
            extraMutations = listOf(upsertAction(claimed)),
        )
        return claimed
    }

    suspend fun recordActionResultLocked(
        actionId: String,
        succeeded: Boolean,
        failure: ActionExecutionFailure?,
    ): RuntimeCommandResult {
        val current = memory.actionInvocations[actionId]
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.state == RuntimeActionState.SUCCEEDED || current.state == RuntimeActionState.FAILED) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        require(succeeded == (failure == null)) { "Failed action results require one typed reason" }
        val now = ctx.clocks.now()
        return if (!succeeded && failure == ActionExecutionFailure.EXPIRED) {
            if (now.wallTimeUtcMillis < current.expiresAtUtcMillis) {
                return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
            }
            expireActionLocked(current, now)
        } else {
            recordActionResultLocked(current, succeeded, failure, now)
        }
    }

    suspend fun recordActionResultLocked(
        current: DurableActionInvocation,
        succeeded: Boolean,
        reportedFailure: ActionExecutionFailure?,
        now: ResearchTime,
    ): RuntimeCommandResult {
        require(succeeded == (reportedFailure == null)) { "Failed action results require one typed reason" }
        require(reportedFailure != ActionExecutionFailure.EXPIRED) {
            "Availability expiry must use the centralized expiry transition"
        }
        val requiredDeliveryFailure = !succeeded &&
            reportedFailure in REQUIRED_DELIVERY_FAILURES &&
            ctx.interventionRequiredById.getValue(current.interventionId)
        val durableFailure = if (requiredDeliveryFailure) {
            ActionExecutionFailure.REQUIRED_ACTION_FAILED
        } else {
            reportedFailure
        }
        val updated = current.copy(
            state = if (succeeded) RuntimeActionState.SUCCEEDED else RuntimeActionState.FAILED,
            failureReason = durableFailure?.name,
        )
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = memory.automationCheckpoint,
            eventDrafts = listOf(RuntimeEventFactory.actionResult(current, succeeded, durableFailure, now)),
            extraMutations = listOf(upsertAction(updated)),
            clock = clockPolicy.advanceClock(memory.requireDocument(), now),
        )
        if (!requiredDeliveryFailure) return RuntimeCommandResult.Success

        containmentPort.safetyPauseLocked(SafetyPauseReason.WORK_SCHEDULING_FAILURE, current.causalSequence)
        return RuntimeCommandResult.FailedClosed(SafetyPauseReason.WORK_SCHEDULING_FAILURE)
    }

    suspend fun openSurveyLocked(actionId: String, interventionId: String): RuntimeCommandResult {
        val currentDocument = memory.requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val current = memory.actionInvocations[actionId]
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state == RuntimeActionState.OPENED) return RuntimeCommandResult.Success
        if (current.state in TERMINAL_ACTION_STATES) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        val now = ctx.clocks.now()
        if (now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            expireActionLocked(current, now)
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_EXPIRED)
        }
        val updated = current.copy(state = RuntimeActionState.OPENED, openedAt = now)
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = memory.automationCheckpoint,
            eventDrafts = listOf(RuntimeEventFactory.surveyOpened(updated, now)),
            extraMutations = listOf(upsertAction(updated)),
            clock = clockPolicy.advanceClock(currentDocument, now),
        )
        return RuntimeCommandResult.Success
    }

    suspend fun submitSurveyLocked(
        actionId: String,
        interventionId: String,
        surveyId: String,
        answersJson: String,
    ): RuntimeCommandResult {
        val currentDocument = memory.requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val current = memory.actionInvocations[actionId]
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state in TERMINAL_ACTION_STATES) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        if (current.state != RuntimeActionState.OPENED) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_NOT_OPEN)
        }
        val now = ctx.clocks.now()
        if (now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            expireActionLocked(current, now)
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_EXPIRED)
        }
        val updated = current.copy(state = RuntimeActionState.SUCCEEDED, failureReason = null)
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = memory.automationCheckpoint,
            eventDrafts = listOf(
                RuntimeEventFactory.surveySubmitted(current, surveyId, answersJson, now),
                RuntimeEventFactory.actionResult(current, succeeded = true, failure = null, now = now),
            ),
            extraMutations = listOf(upsertAction(updated)),
            clock = clockPolicy.advanceClock(currentDocument, now),
        )
        return RuntimeCommandResult.Success
    }

    suspend fun dismissSurveyLocked(actionId: String, interventionId: String): RuntimeCommandResult {
        val current = memory.actionInvocations[actionId]
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state != RuntimeActionState.OPENED) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_NOT_OPEN)
        }
        // A dismissal is intentionally non-terminal. The signed availability window remains the
        // durable authority and the same action ID may reconcile/reopen without a second event.
        return RuntimeCommandResult.Success
    }

    suspend fun expireSurveyLocked(actionId: String, interventionId: String): RuntimeCommandResult {
        val currentDocument = memory.requireDocument()
        if (currentDocument.state != ExperimentState.RUNNING) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        val current = memory.actionInvocations[actionId]
            ?: return RuntimeCommandResult.Rejected(RuntimeCommandRejection.UNKNOWN_ACTION)
        if (current.interventionId != interventionId) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.interventionId !in ctx.surveyInterventionIds) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.SURVEY_MISMATCH)
        }
        if (current.state in TERMINAL_ACTION_STATES) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL)
        }
        val now = ctx.clocks.now()
        if (now.wallTimeUtcMillis < current.expiresAtUtcMillis) {
            return RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE)
        }
        return expireActionLocked(current, now)
    }

    suspend fun expireActionLocked(
        current: DurableActionInvocation,
        now: ResearchTime,
    ): RuntimeCommandResult {
        require(now.wallTimeUtcMillis >= current.expiresAtUtcMillis) {
            "Availability expiry cannot precede its durable deadline"
        }
        require(current.state !in TERMINAL_ACTION_STATES) { "Terminal action cannot expire again" }
        val updated = current.copy(
            state = RuntimeActionState.FAILED,
            failureReason = ActionExecutionFailure.EXPIRED.name,
        )
        val eventDrafts = buildList {
            if (current.interventionId in ctx.surveyInterventionIds) {
                add(RuntimeEventFactory.surveyExpired(current, now))
            }
            add(
                RuntimeEventFactory.actionResult(
                    current,
                    succeeded = false,
                    failure = ActionExecutionFailure.EXPIRED,
                    now = now,
                ),
            )
        }
        val currentDocument = memory.requireDocument()
        commitLog.appendCommitLocked(
            inputKind = EngineInputKind.ACTION_RESULT,
            checkpoint = memory.automationCheckpoint,
            eventDrafts = eventDrafts,
            extraMutations = listOf(upsertAction(updated)),
            clock = currentDocument.clockCheckpoint?.let { clockPolicy.advanceClock(currentDocument, now) },
        )
        return RuntimeCommandResult.Success
    }

    fun pendingActionsLocked(): List<DurableActionInvocation> =
        memory.actionInvocations.values.filter { it.state in PENDING_ACTION_STATES }

    fun pendingActionIdsLocked(): List<String> = memory.actionInvocations.values
        .filter { it.state in PENDING_ACTION_STATES }
        .map(DurableActionInvocation::actionId)

    suspend fun retractInactiveActionsLocked() {
        check(memory.requireDocument().state != ExperimentState.RUNNING) {
            "RUNNING actions must be reconciled, not retracted"
        }
        pendingActionIdsLocked().takeIf { it.isNotEmpty() }?.let { actionIds ->
            ctx.actionNotifier.onActionsInactive(actionIds)
        }
    }
}
