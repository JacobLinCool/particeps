package cool.jacoblin.particeps.core.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Runs [action] under the mutex, or returns null when admission starts draining.
 * A barrier can hold the mutex while awaiting this submission's collector flush, so a waiter must
 * stop waiting for the mutex as soon as drain begins. The caller rechecks admission inside [action].
 */
internal suspend fun <T : Any> Mutex.withLockUntilDrain(
    drainSignal: Deferred<Unit>,
    action: suspend () -> T,
): T? {
    if (drainSignal.isCompleted) return null
    val owner = Any()
    if (tryLock(owner)) {
        return try {
            action()
        } finally {
            unlock(owner)
        }
    }

    return coroutineScope {
        val acquisition = async(start = CoroutineStart.UNDISPATCHED) { lock(owner) }
        try {
            val acquired = select {
                drainSignal.onAwait { false }
                acquisition.onAwait { true }
            }
            if (acquired) action() else null
        } finally {
            // Cancellation may arrive after lock() acquired ownership but before select resumed.
            // Join the contender before checking ownership, including when the caller is cancelled.
            withContext(NonCancellable) {
                acquisition.cancelAndJoin()
                if (holdsLock(owner)) unlock(owner)
            }
        }
    }
}
