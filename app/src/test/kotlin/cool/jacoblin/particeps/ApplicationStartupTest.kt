package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.access.AccessManager
import cool.jacoblin.particeps.core.application.StartupStage
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.platform.AndroidActionOutboxNotifier
import cool.jacoblin.particeps.platform.AndroidStudyUploadPlatform
import cool.jacoblin.particeps.platform.JoinArtifactDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationStartupTest {
    @Test
    fun concurrentConsumersShareOneInitializationAndOnlyReceiveTheFinishedGraph() = runTest {
        val startup = ApplicationStartup(backgroundScope, StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        var invocations = 0
        startup.start { report ->
            invocations++
            report(StartupStage.LOADING_STUDY)
            finish.await()
            UnusedGraph
        }
        assertEquals("start must dispatch construction instead of invoking it inline", 0, invocations)
        assertEquals(ApplicationStartupState.Starting(), startup.state.value)
        val consumers = List(12) { async { startup.awaitReady() } }
        runCurrent()

        assertEquals(1, invocations)
        assertEquals(ApplicationStartupState.Starting(StartupStage.LOADING_STUDY), startup.state.value)
        assertTrue("No consumer may observe a partially initialized graph", consumers.none { it.isCompleted })
        finish.complete(Unit)
        runCurrent()

        assertTrue(consumers.all { it.isCompleted })
        consumers.forEach { assertSame(UnusedGraph, it.await()) }
        assertEquals(ApplicationStartupState.Ready(UnusedGraph), startup.state.value)
        assertSame(UnusedGraph, startup.awaitReady())
        assertEquals(1, invocations)
    }

    @Test
    fun duplicateStartCannotReplaceEitherPendingOrReadyInitialization() = runTest {
        val startup = ApplicationStartup(backgroundScope, StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        startup.start { finish.await(); UnusedGraph }
        assertThrows(IllegalStateException::class.java) {
            startup.start { error("Duplicate construction must not run") }
        }
        finish.complete(Unit)
        runCurrent()
        assertSame(UnusedGraph, startup.awaitReady())
        assertThrows(IllegalStateException::class.java) {
            startup.start { error("A published graph must not be replaced") }
        }
        assertEquals(ApplicationStartupState.Ready(UnusedGraph), startup.state.value)
    }

    @Test
    fun failedConstructionReleasesEveryWaiterAndRemainsTerminalForLaterConsumers() = runTest {
        val startup = ApplicationStartup(backgroundScope, StandardTestDispatcher(testScheduler))
        val fail = CompletableDeferred<Unit>()
        val cause = IllegalStateException("Synthetic private diagnostic")
        startup.start { fail.await(); throw cause }
        val consumers = List(3) { async { runCatching { startup.awaitReady() } } }
        runCurrent()
        assertTrue(consumers.none { it.isCompleted })
        fail.complete(Unit)
        runCurrent()

        assertSame(ApplicationStartupState.Failed, startup.state.value)
        assertTrue("Failure must unblock every pending consumer", consumers.all { it.isCompleted })
        for (consumer in consumers) {
            val failure = consumer.await().exceptionOrNull()
            assertTrue(failure is ApplicationStartupException)
            // Coroutine stack-trace recovery may copy the wrapper at suspension boundaries.
            assertTrue(generateSequence(failure) { it.cause }.any { it === cause })
        }
        val later = runCatching { startup.awaitReady() }.exceptionOrNull()
        assertTrue(later is ApplicationStartupException)
        assertEquals("Application initialization failed", later?.message)
    }

    @Test
    fun cancellingOneConsumerDoesNotCancelProcessInitializationOrOtherConsumers() = runTest {
        val startup = ApplicationStartup(backgroundScope, StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        startup.start { finish.await(); UnusedGraph }
        val leaving = async { startup.awaitReady() }
        val remaining = async { startup.awaitReady() }
        runCurrent()
        leaving.cancelAndJoin()

        assertTrue(leaving.isCancelled)
        assertFalse(remaining.isCompleted)
        assertTrue(startup.state.value is ApplicationStartupState.Starting)
        finish.complete(Unit)
        runCurrent()
        assertSame(UnusedGraph, remaining.await())
        assertEquals(ApplicationStartupState.Ready(UnusedGraph), startup.state.value)
    }

    @Test
    fun alreadyCancelledOwnerNeverConstructsAndDoesNotLeaveWaitersStartingForever() = runTest {
        val owner = SupervisorJob()
        owner.cancel()
        val startup = ApplicationStartup(
            CoroutineScope(owner),
            StandardTestDispatcher(testScheduler),
        )
        var invocations = 0
        startup.start { invocations++; UnusedGraph }
        val consumer = async { runCatching { startup.awaitReady() } }
        runCurrent()

        assertEquals(0, invocations)
        assertTrue("Cancellation before the launch body runs must still release awaiters", consumer.isCompleted)
        assertTrue(consumer.await().exceptionOrNull() is CancellationException)
        assertSame(ApplicationStartupState.Failed, startup.state.value)
    }

    @Test
    fun cancellationDuringConstructionDoesNotPublishAReadyGraphOrWrapCancellation() = runTest {
        val owner = SupervisorJob()
        val startup = ApplicationStartup(CoroutineScope(owner), StandardTestDispatcher(testScheduler))
        try {
            val constructing = CompletableDeferred<Unit>()
            var returnedAfterCancellation = false
            startup.start {
                constructing.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } catch (_: CancellationException) {
                    // A factory returning despite owner cancellation must still not publish Ready.
                    returnedAfterCancellation = true
                }
                UnusedGraph
            }
            val consumer = async { runCatching { startup.awaitReady() } }
            runCurrent()
            assertTrue(constructing.isCompleted)
            owner.cancel()
            runCurrent()

            assertTrue(returnedAfterCancellation)
            assertTrue(consumer.isCompleted)
            assertTrue(consumer.await().exceptionOrNull() is CancellationException)
            assertSame(ApplicationStartupState.Failed, startup.state.value)
        } finally {
            owner.cancelAndJoin()
        }
    }

    /** These tests exercise graph publication, so no Android-backed service may be dereferenced. */
    private object UnusedGraph : ApplicationGraph {
        override val session: StudySessionManager get() = error("Graph service must not be read")
        override val accessManager: AccessManager get() = error("Graph service must not be read")
        override val joinArtifactDownloader: JoinArtifactDownloader get() = error("Graph service must not be read")
        override val actionOutboxNotifier: AndroidActionOutboxNotifier get() = error("Graph service must not be read")
        override val uploadPlatform: AndroidStudyUploadPlatform get() = error("Graph service must not be read")
        override suspend fun reconcileTimerWakeups() = error("Graph service must not be called")
    }
}
