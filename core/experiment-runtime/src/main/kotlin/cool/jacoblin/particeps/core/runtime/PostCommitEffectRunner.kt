package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerProductionRequest
import cool.jacoblin.particeps.core.automation.TimerProductionResult
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.ExperimentState
import kotlinx.coroutines.CancellationException

/**
 * Performs the platform work a commit implies after it is durable: timer wakeups, action display
 * and retraction, and timer materialization. Extracted from [ExperimentRuntime]; not a concurrent
 * actor. Every member runs on the caller's coroutine while the caller holds the runtime mutex, and
 * suspends only in wakeup, notifier, producer and store calls and, through the outbox, the
 * containment port. A required action whose display fails ends in [ContainedActionFailure] after
 * its safety pause is durable. It reads runtime state through [RuntimeMemory] at use time and never
 * retains it.
 */
internal class PostCommitEffectRunner(
    private val ctx: RuntimeContext,
    private val commitLog: CommitAssembler,
    private val outbox: ActionOutbox,
) {
    private val memory = ctx.memory

    suspend fun performPostCommitEffectsLocked(effects: PostCommitEffects) {
        effects.timerIntents.forEach { intent ->
            when (intent) {
                is TimerIntent.Schedule -> ctx.timerWakeups.schedule(intent.timer)
                is TimerIntent.Retire -> ctx.timerWakeups.retire(intent.timerId, intent.generation)
            }
        }
        if (memory.requireDocument().state == ExperimentState.RUNNING) {
            effects.actionsReady.distinct().sorted().forEach { actionId ->
                val action = memory.actionInvocations[actionId]
                    ?.takeIf { it.state in PENDING_ACTION_STATES }
                    ?: return@forEach
                val now = ctx.clocks.now()
                if (now.wallTimeUtcMillis >= action.expiresAtUtcMillis) {
                    outbox.expireActionLocked(action, now)
                    return@forEach
                }
                try {
                    ctx.actionNotifier.onActionReady(actionId)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    val result = outbox.recordActionResultLocked(
                        action,
                        succeeded = false,
                        reportedFailure = ActionExecutionFailure.RECONCILIATION_FAILED,
                        now = ctx.clocks.now(),
                    )
                    if (result is RuntimeCommandResult.FailedClosed) {
                        throw ContainedActionFailure(result.reason)
                    }
                }
            }
        }
        effects.actionsInactive.distinct().sorted().takeIf { it.isNotEmpty() }?.let { actionIds ->
            ctx.actionNotifier.onActionsInactive(actionIds)
        }
        if (effects.timerProductionRequests.isNotEmpty()) {
            materializeTimerRequestsLocked(effects.timerProductionRequests)
        }
    }

    private suspend fun materializeTimerRequestsLocked(requests: List<TimerProductionRequest>) {
        requests.forEach { request ->
            when (val produced = ctx.timerProducer.produce(request)) {
                is TimerProductionResult.Materialized -> {
                    if (memory.automationCheckpoint.timers[produced.timer.id] != null) return@forEach
                    val current = memory.requireDocument()
                    val clock = requireNotNull(current.clockCheckpoint)
                    val input = ReducerInput.TimerMaterialized(
                        memory.automationCheckpoint.evaluatedThroughSequence + 1,
                        reducerClock(clock),
                        produced.timer,
                    )
                    val reduction = ctx.reducer.reduceBatch(ctx.program, memory.automationCheckpoint, listOf(input))
                    val effects = commitLog.appendReductionLocked(
                        EngineInputKind.RANDOM_SELECTION,
                        reduction,
                        clock = clock,
                    )
                    performPostCommitEffectsLocked(effects)
                }
                TimerProductionResult.Deferred, TimerProductionResult.Exhausted -> Unit
            }
        }
    }
}
