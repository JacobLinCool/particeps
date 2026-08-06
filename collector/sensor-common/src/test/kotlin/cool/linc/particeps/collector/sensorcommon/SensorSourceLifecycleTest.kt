package cool.linc.particeps.collector.sensorcommon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import cool.linc.particeps.core.collector.SourceCallbackBoundary
import cool.linc.particeps.core.collector.SourceRegistrationResult
import cool.linc.particeps.core.collector.completeSourceTeardown
import cool.linc.particeps.core.collector.registerSourceWithRollback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSourceLifecycleTest {
    @Test
    fun inFlightCallbackFinishesBeforeTeardownAndNoLaterCallbackCanMutateState() {
        val boundary = SourceCallbackBoundary()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val mutations = mutableListOf<String>()
        boundary.activate()
        val callback = Thread {
            boundary.runIfActive {
                entered.countDown()
                check(release.await(1, TimeUnit.SECONDS))
                mutations += "callback"
            }
            completed.countDown()
        }
        callback.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val teardown = Thread {
            boundary.deactivate { mutations += "teardown" }
        }
        teardown.start()

        assertFalse(completed.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        callback.join(1_000)
        teardown.join(1_000)
        assertEquals(listOf("callback", "teardown"), mutations)
        assertFalse(boundary.runIfActive { mutations += "late-callback" })
        assertEquals(listOf("callback", "teardown"), mutations)
    }

    @Test
    fun failedSensorManagerRegistrationRollsBackTheSource() = runBlocking {
        val calls = mutableListOf<String>()
        val registrationFailure = IllegalStateException("registration")

        val result = registerSourceWithRollback(
                register = {
                    calls += "register"
                    throw registrationFailure
                },
                rollback = {
                    completeSourceTeardown(
                        { calls += "sensor-manager-unregister" },
                        { calls += "collector-pending-clear" },
                        { calls += "handler-callbacks-and-thread-release" },
                    )
                },
            )

        assertTrue(result is SourceRegistrationResult.Released)
        assertSame(registrationFailure, (result as SourceRegistrationResult.Released).failure)
        assertEquals(
            listOf(
                "register",
                "sensor-manager-unregister",
                "collector-pending-clear",
                "handler-callbacks-and-thread-release",
            ),
            calls,
        )
    }

    @Test
    fun queuedCallbackUsesTheSameBoundaryAsLiveSensorCallbacks() {
        val boundary = SourceCallbackBoundary()
        var publications = 0
        val queuedCallback = Runnable { boundary.runIfActive { publications++ } }
        boundary.activate()

        queuedCallback.run()
        boundary.deactivate {}
        queuedCallback.run()

        assertEquals(1, publications)
    }

    @Test
    fun unregisterFailureStillClearsCollectorPendingWorkAndHandlerCallbacks() = runBlocking {
        val calls = mutableListOf<String>()
        val unregisterFailure = IllegalStateException("unregister")

        val thrown = runCatching {
            completeSourceTeardown(
                {
                    calls += "sensor-manager-unregister"
                    throw unregisterFailure
                },
                { calls += "collector-pending-clear" },
                { calls += "handler-callbacks-and-thread-release" },
            )
        }.exceptionOrNull()

        assertSame(unregisterFailure, thrown)
        assertEquals(
            listOf(
                "sensor-manager-unregister",
                "collector-pending-clear",
                "handler-callbacks-and-thread-release",
            ),
            calls,
        )
    }
}
