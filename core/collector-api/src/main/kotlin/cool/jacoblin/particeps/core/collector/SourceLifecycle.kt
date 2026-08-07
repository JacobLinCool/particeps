package cool.jacoblin.particeps.core.collector

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Explicit outcome of source registration.
 *
 * A thrown exception before returning means registration made no externally visible change.
 * Multi-step registrations must use [registerSourceWithRollback] so a failed rollback is reported
 * as [SourceRegistrationResult.Uncertain] and the collector will refuse to register another source
 * over it.
 */
sealed interface SourceRegistrationResult {
    data object Registered : SourceRegistrationResult

    data class Released(val failure: Throwable) : SourceRegistrationResult

    data class Uncertain(val failure: Throwable) : SourceRegistrationResult
}

/**
 * Explicit outcome of source teardown.
 *
 * Returning either outcome promises that callbacks are physically released or independently
 * isolated, so registering a fresh source generation is safe. Throwing instead leaves physical
 * state uncertain; [SerializedCallbackCollector] then refuses to register over that source.
 */
sealed interface SourceTeardownResult {
    data object Released : SourceTeardownResult

    /** Cleanup completed safely but still has a diagnostic failure for the caller to surface. */
    data class ReleasedWithFailure(val failure: Throwable) : SourceTeardownResult
}

/** One callback generation, serialized against its registration and teardown boundaries. */
class SourceCallbackBoundary {
    private val lock = Any()
    private var active = false

    fun activate(beforeFirstCallback: () -> Unit = {}) = synchronized(lock) {
        check(!active) { "Source callback generation is already active" }
        beforeFirstCallback()
        active = true
    }

    fun runIfActive(block: () -> Unit): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        block()
        true
    }

    fun deactivate(afterLastCallback: () -> Unit = {}) = synchronized(lock) {
        if (!active) return@synchronized
        active = false
        afterLastCallback()
    }
}

/** Completes rollback in a non-cancellable context and reports whether release is proven. */
suspend fun registerSourceWithRollback(
    register: suspend () -> Unit,
    rollback: suspend () -> Unit,
): SourceRegistrationResult = try {
    register()
    SourceRegistrationResult.Registered
} catch (failure: Throwable) {
    try {
        withContext(NonCancellable) { rollback() }
        SourceRegistrationResult.Released(failure)
    } catch (rollbackFailure: Throwable) {
        if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
        SourceRegistrationResult.Uncertain(failure)
    }
}

/** Runs every teardown operation before rethrowing the first failure with later failures attached. */
suspend fun completeSourceTeardown(vararg operations: suspend () -> Unit) {
    require(operations.isNotEmpty()) { "Source teardown needs at least one operation" }
    var failure: Throwable? = null
    withContext(NonCancellable) {
        operations.forEach { operation ->
            try {
                operation()
            } catch (next: Throwable) {
                val first = failure
                if (first == null) {
                    failure = next
                } else if (first !== next) {
                    first.addSuppressed(next)
                }
            }
        }
    }
    failure?.let { throw it }
}
