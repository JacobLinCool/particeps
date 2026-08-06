package cool.linc.particeps.core.collector

/**
 * Bounds an event-driven source while retaining its newest meaningful value.
 *
 * Time is caller-supplied monotonic milliseconds, keeping this state machine independent of
 * Android handlers and making its scheduling contract directly testable.
 */
class LatestValueRateGate<T : Any>(
    private val minimumIntervalMillis: Long,
    private val equivalent: (previous: T, current: T) -> Boolean = { previous, current -> previous == current },
) {
    init { require(minimumIntervalMillis > 0) }

    private var lastEmitted: T? = null
    private var lastEmittedAtMillis = 0L
    private var hasEmitted = false
    private var pending: T? = null

    fun offer(value: T, elapsedMillis: Long): Decision<T> {
        val previous = lastEmitted
        if (previous != null && equivalent(previous, value)) {
            pending = null
            return Decision.Suppress
        }
        if (!hasEmitted || elapsedMillis - lastEmittedAtMillis >= minimumIntervalMillis) {
            pending = null
            lastEmitted = value
            lastEmittedAtMillis = elapsedMillis
            hasEmitted = true
            return Decision.Emit(value)
        }
        pending = value
        return Decision.Defer(minimumIntervalMillis - (elapsedMillis - lastEmittedAtMillis))
    }

    fun poll(elapsedMillis: Long): Decision<T> {
        val value = pending ?: return Decision.Suppress
        val previous = lastEmitted
        if (previous != null && equivalent(previous, value)) {
            pending = null
            return Decision.Suppress
        }
        val remaining = minimumIntervalMillis - (elapsedMillis - lastEmittedAtMillis)
        if (remaining > 0) return Decision.Defer(remaining)
        pending = null
        lastEmitted = value
        lastEmittedAtMillis = elapsedMillis
        hasEmitted = true
        return Decision.Emit(value)
    }

    /**
     * Restores the durable rate watermark after process recreation.
     *
     * Coalesced events preserve capture time rather than their later publication time, so the
     * durable event cannot reconstruct the exact prior deadline. Recovery therefore keeps the last
     * value for deduplication but conservatively fences one full interval from
     * [currentElapsedMillis]. Once this instance has a watermark, later resume calls are no-ops.
     * A null [value] still restores the hard rate bound when an old payload cannot be decoded.
     */
    fun restoreLastEmission(
        value: T?,
        currentElapsedMillis: Long,
    ) {
        require(currentElapsedMillis >= 0) { "Current elapsed time must be non-negative" }
        if (hasEmitted) return
        lastEmittedAtMillis = currentElapsedMillis
        lastEmitted = value
        hasEmitted = true
        pending = null
    }

    fun clearPending() {
        pending = null
    }

    sealed interface Decision<out T> {
        data class Emit<T>(val value: T) : Decision<T>
        data class Defer(val delayMillis: Long) : Decision<Nothing>
        data object Suppress : Decision<Nothing>
    }

}
