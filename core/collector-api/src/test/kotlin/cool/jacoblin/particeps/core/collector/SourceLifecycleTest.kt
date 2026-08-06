package cool.jacoblin.particeps.core.collector

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SourceLifecycleTest {
    @Test
    fun teardownAttemptsEveryOperationAndPreservesFailureOrder() = runTest {
        val calls = mutableListOf<String>()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        val thrown = runCatching {
            completeSourceTeardown(
                {
                    calls += "first"
                    throw first
                },
                { calls += "middle" },
                {
                    calls += "last"
                    throw second
                },
            )
        }.exceptionOrNull()

        assertSame(first, thrown)
        assertEquals(listOf(second), first.suppressed.toList())
        assertEquals(listOf("first", "middle", "last"), calls)
    }

    @Test
    fun failedRegistrationReportsUncertainWhenRollbackAlsoFails() = runTest {
        val calls = mutableListOf<String>()
        val registration = IllegalStateException("registration")
        val rollback = IllegalArgumentException("rollback")

        val result = registerSourceWithRollback(
                register = {
                    calls += "register"
                    throw registration
                },
                rollback = {
                    calls += "rollback"
                    throw rollback
                },
            )

        assertEquals(SourceRegistrationResult.Uncertain(registration), result)
        assertEquals(1, registration.suppressed.size)
        assertEquals(rollback::class.java, registration.suppressed.single()::class.java)
        assertEquals(rollback.message, registration.suppressed.single().message)
        assertEquals(listOf("register", "rollback"), calls)
    }

    @Test
    fun failedRegistrationReportsReleasedWhenRollbackCompletes() = runTest {
        val registration = IllegalStateException("registration")

        val result = registerSourceWithRollback(
            register = { throw registration },
            rollback = {},
        )

        assertEquals(SourceRegistrationResult.Released(registration), result)
    }
}
