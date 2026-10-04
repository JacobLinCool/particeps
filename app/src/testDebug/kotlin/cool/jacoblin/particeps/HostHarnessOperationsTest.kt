package cool.jacoblin.particeps

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostHarnessOperationsTest {
    @Test
    fun admissionAndStatusNeverWaitForLongWorkAndDuplicateRunsOnlyOnce() = runTest {
        val operations = HostHarnessOperations(this)
        val id = UUID.randomUUID().toString()
        val finish = CompletableDeferred<Unit>()
        var invocations = 0
        val work: suspend () -> String = { invocations++; finish.await(); "RESET" }
        assertEquals("RUNNING", operations.admit(operations.processId, id, "reset", work = work).status)
        runCurrent()
        repeat(5) {
            assertEquals("RUNNING", operations.status(operations.processId, id).status)
            assertEquals("RUNNING", operations.admit(operations.processId, id, "reset", work = work).status)
        }
        assertEquals(1, invocations)
        finish.complete(Unit)
        runCurrent()
        val completed = operations.status(operations.processId, id)
        assertEquals("SUCCEEDED", completed.status)
        assertEquals("RESET", completed.result)
        assertEquals(completed, operations.admit(operations.processId, id, "reset", work = work))
        assertEquals(1, invocations)
    }

    @Test
    fun changedPayloadBusyAndUnknownProcessNeverExecuteWork() = runTest {
        val operations = HostHarnessOperations(this)
        val id = UUID.randomUUID().toString()
        val finish = CompletableDeferred<Unit>()
        operations.admit(operations.processId, id, "reset") { finish.await(); "RESET" }
        assertEquals("CONFLICT", operations.admit(operations.processId, id, "provision") { error("replay") }.status)
        assertEquals("BUSY", operations.admit(operations.processId, UUID.randomUUID().toString(), "reset") { error("parallel") }.status)
        assertEquals("PROCESS_CHANGED", operations.admit(UUID.randomUUID().toString(), id, "reset") { error("old process") }.status)
        assertEquals("PROCESS_CHANGED", operations.status(UUID.randomUUID().toString(), id).status)
        finish.complete(Unit)
        runCurrent()
    }

    @Test
    fun boundedReceiptsAreNeverEvictedToPermitDestructiveReplay() = runTest {
        val operations = HostHarnessOperations(this, maximumRecords = 1)
        val id = UUID.randomUUID().toString()
        operations.admit(operations.processId, id, "reset") { "RESET" }
        runCurrent()
        assertEquals("CAPACITY_EXHAUSTED", operations.admit(operations.processId, UUID.randomUUID().toString(), "reset") { error("overflow") }.status)
        assertEquals("SUCCEEDED", operations.admit(operations.processId, id, "reset") { error("replay") }.status)
        assertEquals("UNKNOWN_OPERATION", operations.status(operations.processId, UUID.randomUUID().toString()).status)
    }

    @Test
    fun timeoutIsTerminalAndDoesNotRerunOrBlockTheNextCommand() = runTest {
        val operations = HostHarnessOperations(this, timeoutMillis = 100)
        val id = UUID.randomUUID().toString()
        var invocations = 0
        operations.admit(operations.processId, id, "reset") { invocations++; CompletableDeferred<Unit>().await(); "RESET" }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("FAILED", operations.status(operations.processId, id).status)
        assertEquals("FAILED", operations.admit(operations.processId, id, "reset") { error("replay") }.status)
        assertEquals(1, invocations)
        val next = UUID.randomUUID().toString()
        operations.admit(operations.processId, next, "profile") { "proof" }
        runCurrent()
        assertEquals("SUCCEEDED", operations.status(operations.processId, next).status)
    }

    @Test
    fun failuresStayFailedAndMalformedOperationIdsAreRejected() = runTest {
        val operations = HostHarnessOperations(this)
        assertEquals("INVALID_OPERATION_ID", operations.admit(operations.processId, "invalid", "reset") { error("invalid") }.status)
        val id = UUID.randomUUID().toString()
        operations.admit(operations.processId, id, "reset") { throw IllegalStateException("private message") }
        runCurrent()
        assertEquals(HostHarnessOperations.Receipt("FAILED", id, "IllegalStateException"), operations.status(operations.processId, id))
    }
}
