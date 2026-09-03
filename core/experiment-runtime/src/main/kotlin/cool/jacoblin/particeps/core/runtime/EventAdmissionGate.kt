package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.ResearchTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal class EventAdmissionGate(
    private val now: () -> ResearchTime,
) {
    private val identity = Any()
    private var generation = 0L
    private var mode = Mode.CLOSED
    private var conditionEpochId: ConditionEpochId? = null
    private var drainBoundary: ResearchTime? = null
    private var drainSignal: CompletableDeferred<Unit>? = null
    private var barrierToken: EpochToken? = null
    private var exclusiveDeadline: ResearchTime? = null

    @Synchronized
    fun open(epochId: ConditionEpochId, deadline: ResearchTime): AdmissionToken {
        check(mode == Mode.CLOSED) { "Admission gate must be closed before opening" }
        require(deadline.elapsedRealtimeNanos > 0) { "Admission deadline must be positive" }
        generation += 1
        mode = Mode.ACTIVE
        conditionEpochId = epochId
        exclusiveDeadline = deadline
        drainBoundary = null
        drainSignal = CompletableDeferred()
        barrierToken = null
        return EpochToken(identity, generation, TokenKind.NORMAL)
    }

    @Synchronized
    fun capture(): AdmissionToken? = when (mode) {
        Mode.ACTIVE -> if (now().isStrictlyBefore(requireNotNull(exclusiveDeadline))) {
            EpochToken(identity, generation, TokenKind.NORMAL)
        } else {
            null
        }
        Mode.CLOSED, Mode.DRAINING -> null
    }

    @Synchronized
    fun beginDrain(boundary: ResearchTime): AdmissionToken {
        check(mode == Mode.ACTIVE) { "Admission gate is not active" }
        mode = Mode.DRAINING
        drainBoundary = boundary
        val token = EpochToken(identity, generation, TokenKind.BARRIER_FLUSH)
        barrierToken = token
        requireNotNull(drainSignal).complete(Unit)
        return token
    }

    @Synchronized
    fun captureBarrierFlush(boundary: ResearchTime): AdmissionToken? = barrierToken?.takeIf {
        mode == Mode.DRAINING && drainBoundary == boundary
    }

    @Synchronized
    fun classify(
        token: AdmissionToken,
        observedTimes: List<ResearchTime>,
    ): AdmissionDecision {
        val epochToken = token.epochToken() ?: return AdmissionDecision.Rejected
        if (epochToken.generation != generation) return AdmissionDecision.Rejected
        return when (epochToken.kind) {
            TokenKind.NORMAL -> when (mode) {
                Mode.ACTIVE -> {
                    val deadline = requireNotNull(exclusiveDeadline)
                    if (!now().isStrictlyBefore(deadline) ||
                        observedTimes.any { observed -> !observed.isStrictlyBefore(deadline) }
                    ) {
                        AdmissionDecision.Rejected
                    } else {
                        AdmissionDecision.Active(
                            requireNotNull(conditionEpochId),
                            requireNotNull(drainSignal),
                        )
                    }
                }
                Mode.DRAINING -> {
                    val boundary = requireNotNull(drainBoundary)
                    val deadline = requireNotNull(exclusiveDeadline)
                    if (observedTimes.all { observed ->
                            observed.isAtOrBefore(boundary) && observed.isStrictlyBefore(deadline)
                        }
                    ) {
                        AdmissionDecision.PreDrain(requireNotNull(conditionEpochId), boundary)
                    } else {
                        AdmissionDecision.Rejected
                    }
                }
                Mode.CLOSED -> AdmissionDecision.Rejected
            }
            TokenKind.BARRIER_FLUSH -> if (mode == Mode.DRAINING && epochToken === barrierToken) {
                val boundary = requireNotNull(drainBoundary)
                val deadline = requireNotNull(exclusiveDeadline)
                if (observedTimes.all { observed ->
                        observed.isAtOrBefore(boundary) && observed.isStrictlyBefore(deadline)
                    }
                ) {
                    AdmissionDecision.BoundaryFlush(requireNotNull(conditionEpochId), boundary)
                } else {
                    AdmissionDecision.Rejected
                }
            } else {
                AdmissionDecision.Rejected
            }
        }
    }

    @Synchronized
    fun close(token: AdmissionToken) {
        check(token.epochToken()?.generation == generation) { "Cannot close a stale admission generation" }
        mode = Mode.CLOSED
        conditionEpochId = null
        exclusiveDeadline = null
        drainBoundary = null
        drainSignal?.complete(Unit)
        drainSignal = null
        barrierToken = null
    }

    @Synchronized
    fun forceClose() {
        mode = Mode.CLOSED
        conditionEpochId = null
        exclusiveDeadline = null
        drainBoundary = null
        drainSignal?.complete(Unit)
        drainSignal = null
        barrierToken = null
    }

    @Synchronized
    fun isOpen(): Boolean = mode == Mode.ACTIVE

    private fun AdmissionToken.epochToken(): EpochToken? = (this as? EpochToken)
        ?.takeIf { it.owner === identity }

    private fun ResearchTime.isAtOrBefore(boundary: ResearchTime): Boolean =
        bootSessionId == boundary.bootSessionId && elapsedRealtimeNanos <= boundary.elapsedRealtimeNanos

    private fun ResearchTime.isStrictlyBefore(boundary: ResearchTime): Boolean =
        bootSessionId == boundary.bootSessionId && elapsedRealtimeNanos < boundary.elapsedRealtimeNanos

    private class EpochToken(
        val owner: Any,
        val generation: Long,
        val kind: TokenKind,
    ) : AdmissionToken

    private enum class TokenKind { NORMAL, BARRIER_FLUSH }

    private enum class Mode {
        CLOSED,
        ACTIVE,
        DRAINING,
    }
}

internal sealed interface AdmissionDecision {
    data class Active(
        val conditionEpochId: ConditionEpochId,
        val drainSignal: Deferred<Unit>,
    ) : AdmissionDecision
    data class PreDrain(
        val conditionEpochId: ConditionEpochId,
        val boundary: ResearchTime,
    ) : AdmissionDecision
    data class BoundaryFlush(
        val conditionEpochId: ConditionEpochId,
        val boundary: ResearchTime,
    ) : AdmissionDecision
    data object Rejected : AdmissionDecision
}
