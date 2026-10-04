package cool.jacoblin.particeps

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Process-local, bounded command receipts. Losing this process never authorizes replay. */
internal class HostHarnessOperations(
    private val scope: CoroutineScope,
    val processId: String = UUID.randomUUID().toString(),
    private val maximumRecords: Int = 4096,
    private val timeoutMillis: Long = 90_000,
) {
    internal data class Receipt(val status: String, val operationId: String, val result: String? = null)
    private data class Record(val fingerprint: String, var receipt: Receipt)
    private val monitor = Any()
    private val records = mutableMapOf<String, Record>()
    private var active: String? = null

    init {
        require(maximumRecords > 0 && timeoutMillis > 0)
    }

    fun status(expectedProcessId: String, operationId: String): Receipt = synchronized(monitor) {
        if (expectedProcessId != processId) return@synchronized Receipt("PROCESS_CHANGED", operationId)
        records[operationId]?.receipt ?: Receipt("UNKNOWN_OPERATION", operationId)
    }

    fun admit(
        expectedProcessId: String,
        operationId: String,
        fingerprint: String,
        failureResult: (Exception) -> String = { it::class.java.simpleName },
        work: suspend () -> String,
    ): Receipt {
        synchronized(monitor) {
            if (expectedProcessId != processId) return Receipt("PROCESS_CHANGED", operationId)
            if (runCatching { UUID.fromString(operationId).toString() }.getOrNull() != operationId) {
                return Receipt("INVALID_OPERATION_ID", operationId)
            }
            records[operationId]?.let { prior ->
                return if (prior.fingerprint == fingerprint) prior.receipt else Receipt("CONFLICT", operationId)
            }
            if (active != null) return Receipt("BUSY", operationId)
            // Never evict a receipt: an old UUID must not become a new destructive command.
            if (records.size >= maximumRecords) return Receipt("CAPACITY_EXHAUSTED", operationId)
            records[operationId] = Record(fingerprint, Receipt("RUNNING", operationId))
            active = operationId
        }
        scope.launch {
            val receipt = try {
                Receipt("SUCCEEDED", operationId, withTimeout(timeoutMillis) { work() })
            } catch (failure: Exception) {
                Receipt("FAILED", operationId, failureResult(failure))
            }
            synchronized(monitor) {
                check(active == operationId)
                checkNotNull(records[operationId]).receipt = receipt
                active = null
            }
        }
        return Receipt("RUNNING", operationId)
    }
}
