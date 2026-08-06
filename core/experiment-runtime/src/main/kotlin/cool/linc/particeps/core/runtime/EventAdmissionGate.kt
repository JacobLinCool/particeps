package cool.linc.particeps.core.runtime

import cool.linc.particeps.core.collector.AdmissionToken
internal class EventAdmissionGate {
    private var epoch = 0L
    private var mode = Mode.CLOSED
    private var drainBoundaryElapsedNanos = Long.MIN_VALUE

    @Synchronized
    fun open(): AdmissionToken {
        check(mode == Mode.CLOSED) { "Admission gate must be closed before opening" }
        epoch += 1
        mode = Mode.ACTIVE
        drainBoundaryElapsedNanos = Long.MIN_VALUE
        return EpochToken(epoch)
    }

    @Synchronized
    fun capture(): AdmissionToken? = if (mode == Mode.ACTIVE) EpochToken(epoch) else null

    @Synchronized
    fun beginDrain(boundaryElapsedNanos: Long): AdmissionToken {
        check(mode == Mode.ACTIVE) { "Admission gate is not active" }
        require(boundaryElapsedNanos >= 0) { "Drain boundary must be non-negative" }
        mode = Mode.DRAINING
        drainBoundaryElapsedNanos = boundaryElapsedNanos
        return EpochToken(epoch)
    }

    @Synchronized
    fun restoreActive(token: AdmissionToken) {
        check(token.epoch() == epoch && mode == Mode.DRAINING) { "Cannot restore a stale admission epoch" }
        mode = Mode.ACTIVE
        drainBoundaryElapsedNanos = Long.MIN_VALUE
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

    private fun AdmissionToken.epoch(): Long = (this as? EpochToken)?.epoch ?: Long.MIN_VALUE

    private data class EpochToken(val epoch: Long) : AdmissionToken

    private enum class Mode {
        CLOSED,
        ACTIVE,
        DRAINING,
    }
}
