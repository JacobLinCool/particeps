package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.collector.AdmissionToken
internal class EventAdmissionGate {
    private val identity = Any()
    private var epoch = 0L
    private var mode = Mode.CLOSED
    private var drainBoundaryElapsedNanos = Long.MIN_VALUE

    @Synchronized
    fun open(): AdmissionToken {
        check(mode == Mode.CLOSED) { "Admission gate must be closed before opening" }
        epoch += 1
        mode = Mode.ACTIVE
        drainBoundaryElapsedNanos = Long.MIN_VALUE
        return EpochToken(identity, epoch)
    }

    @Synchronized
    fun capture(): AdmissionToken? = if (mode == Mode.ACTIVE) EpochToken(identity, epoch) else null

    @Synchronized
    fun beginDrain(boundaryElapsedNanos: Long): AdmissionToken {
        check(mode == Mode.ACTIVE) { "Admission gate is not active" }
        require(boundaryElapsedNanos >= 0) { "Drain boundary must be non-negative" }
        mode = Mode.DRAINING
        drainBoundaryElapsedNanos = boundaryElapsedNanos
        return EpochToken(identity, epoch)
    }

    @Synchronized
    fun accepts(
        token: AdmissionToken,
        observedElapsedNanos: Long,
    ): Boolean = token.epoch() == epoch && when (mode) {
        Mode.ACTIVE -> true
        Mode.DRAINING -> observedElapsedNanos < drainBoundaryElapsedNanos
        Mode.CLOSED -> false
    }

    @Synchronized
    fun close(token: AdmissionToken) {
        check(token.epoch() == epoch) { "Cannot close a stale admission epoch" }
        mode = Mode.CLOSED
        drainBoundaryElapsedNanos = Long.MIN_VALUE
    }

    @Synchronized
    fun forceClose() {
        mode = Mode.CLOSED
        drainBoundaryElapsedNanos = Long.MIN_VALUE
    }

    private fun AdmissionToken.epoch(): Long = (this as? EpochToken)
        ?.takeIf { it.owner === identity }
        ?.epoch
        ?: Long.MIN_VALUE

    private class EpochToken(
        val owner: Any,
        val epoch: Long,
    ) : AdmissionToken

    private enum class Mode {
        CLOSED,
        ACTIVE,
        DRAINING,
    }
}
