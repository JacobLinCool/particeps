package cool.jacoblin.particeps.platform

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.lifecycle.LiveData
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkOperationAwaitTest {
    @Test
    fun waitsForWorkManagerPersistenceAcknowledgement() = runBlocking {
        val operation = ControllableOperation()
        val awaiting = async { awaitWorkPersistence(operation) }
        yield()

        assertFalse(awaiting.isCompleted)
        operation.succeed()

        awaiting.await()
        assertTrue(awaiting.isCompleted)
    }

    @Test
    fun propagatesAsynchronousPersistenceFailure() = runBlocking {
        supervisorScope {
            val operation = ControllableOperation()
            val expected = IllegalStateException("WorkManager transaction failed")
            val awaiting = async { awaitWorkPersistence(operation) }
            yield()

            operation.fail(expected)
            val actual = runCatching { awaiting.await() }.exceptionOrNull()

            assertTrue(actual is IllegalStateException)
            assertEquals(expected.message, actual?.message)
        }
    }

    @Test
    fun invokesEveryMutationAndAggregatesSynchronousAndAsynchronousFailures() = runBlocking {
        supervisorScope {
            val invoked = mutableListOf<String>()
            val synchronous = IllegalStateException("mutation rejected")
            val firstOperation = ControllableOperation()
            val secondOperation = ControllableOperation()
            val awaiting = async {
                awaitWorkMutations(
                    listOf(
                        {
                            invoked += "sync-failure"
                            throw synchronous
                        },
                        {
                            invoked += "first-operation"
                            firstOperation
                        },
                        {
                            invoked += "second-operation"
                            secondOperation
                        },
                    ),
                )
            }
            yield()

            assertEquals(
                listOf("sync-failure", "first-operation", "second-operation"),
                invoked,
            )
            firstOperation.fail(IllegalStateException("first transaction failed"))
            secondOperation.fail(IllegalStateException("second transaction failed"))

            val actual = runCatching { awaiting.await() }.exceptionOrNull()

            assertTrue(actual is IllegalStateException)
            assertEquals(synchronous.message, actual?.message)
            val suppressedMessages = listOfNotNull(actual, actual?.cause)
                .flatMap { it.suppressed.toList() }
                .map { it.message }
            assertEquals(
                listOf("first transaction failed", "second transaction failed"),
                suppressedMessages,
            )
        }
    }

    private class ControllableOperation : Operation {
        private lateinit var completer: CallbackToFutureAdapter.Completer<Operation.State.SUCCESS>
        private val resultFuture = CallbackToFutureAdapter.getFuture<Operation.State.SUCCESS> { supplied ->
            completer = supplied
            "Controllable WorkManager operation"
        }

        fun succeed() {
            check(completer.set(Operation.SUCCESS))
        }

        fun fail(failure: Throwable) {
            check(completer.setException(failure))
        }

        override fun getState(): LiveData<Operation.State> =
            error("Operation.await must use the completion future")

        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = resultFuture
    }
}
