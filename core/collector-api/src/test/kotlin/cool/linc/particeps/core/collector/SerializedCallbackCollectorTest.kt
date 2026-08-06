package cool.linc.particeps.core.collector

import cool.linc.particeps.core.model.EventDraft
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedCallbackCollectorTest {
    @Test
    fun lifecycleRegistersExactlyOneSourceAndDrainsAcceptedEvents() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 4)

        collector.start()
        collector.trigger()
        collector.pause()

        assertEquals(1, collector.registerCount)
        assertEquals(1, collector.unregisterCount)
        assertEquals(1, sink.events.size)
        assertEquals(CollectorStatus.PAUSED, collector.health.value.status)

        collector.resume()
        collector.stop()

        assertEquals(2, collector.registerCount)
        assertEquals(2, collector.unregisterCount)
        assertEquals(CollectorStatus.STOPPED, collector.health.value.status)
    }

    @Test
    fun rejectedAdmissionDoesNotConstructAnObservation() = runTest {
        val sink = FakeSink(admit = false)
        val collector = TestCollector(context(sink), queueCapacity = 1)
        collector.start()

        collector.trigger()
        collector.stop()

        assertFalse(collector.draftConstructed)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun storageFailureIsStableAcrossPauseDrain() = runTest {
        val sink = FakeSink(storageFailure = true)
        val collector = TestCollector(context(sink), queueCapacity = 1)
        collector.start()

        collector.trigger()
        collector.pause()

        assertEquals(CollectorHealth(CollectorStatus.FAILED, "STORAGE_WRITE_FAILED"), collector.health.value)
        collector.stop()
    }

    @Test
    fun failedSourceRegistrationRollsBackConsumerAndCanRetry() = runTest {
        val collector = TestCollector(context(FakeSink()), queueCapacity = 1)
        collector.failNextRegistration = true

        val failure = runCatching { collector.start() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(CollectorStatus.FAILED, collector.health.value.status)

        collector.start()
        collector.stop()
        assertEquals(2, collector.registerCount)
        assertEquals(CollectorStatus.STOPPED, collector.health.value.status)
    }

    @Test
    fun failedSourceUnregistrationStillDrainsAndStopsConsumer() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 1)
        collector.start()
        collector.trigger()
        collector.failNextUnregistration = true

        val failure = runCatching { collector.stop() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, sink.events.size)
        assertEquals(CollectorHealth(CollectorStatus.FAILED, "SOURCE_UNREGISTRATION_FAILED"), collector.health.value)
        collector.stop()
    }

    @Test
    fun uncertainStopKeepsConsumerAliveUntilTeardownCanBeRetried() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 1)
        collector.start()
        collector.leaveNextUnregistrationUncertain = true

        val firstFailure = runCatching { collector.stop() }.exceptionOrNull()
        assertTrue(firstFailure is IllegalStateException)
        assertEquals(CollectorHealth(CollectorStatus.FAILED, "SOURCE_UNREGISTRATION_FAILED"), collector.health.value)

        collector.trigger()
        collector.stop()

        assertEquals(1, collector.registerCount)
        assertEquals(2, collector.unregisterCount)
        assertEquals(1, sink.events.size)
        assertEquals(CollectorStatus.STOPPED, collector.health.value.status)
    }

    @Test
    fun uncertainRegistrationBlocksAnotherGenerationUntilStopReleasesIt() = runTest {
        val collector = TestCollector(context(FakeSink()), queueCapacity = 1)
        collector.leaveNextRegistrationUncertain = true

        val startFailure = runCatching { collector.start() }.exceptionOrNull()
        assertTrue(startFailure is IllegalStateException)

        val resumeFailure = runCatching { collector.resume() }.exceptionOrNull()
        assertTrue(resumeFailure is IllegalStateException)
        assertEquals(1, collector.registerCount)

        collector.stop()
        assertEquals(1, collector.unregisterCount)
        assertEquals(CollectorStatus.STOPPED, collector.health.value.status)
    }

    @Test
    fun failedPauseTeardownStillDrainsAndCanResumeWithAFreshSource() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 1)
        collector.start()
        collector.trigger()
        collector.failNextUnregistration = true

        val failure = runCatching { collector.pause() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, sink.events.size)
        assertEquals(CollectorHealth(CollectorStatus.FAILED, "SOURCE_UNREGISTRATION_FAILED"), collector.health.value)

        collector.resume()
        collector.trigger()
        collector.stop()

        assertEquals(2, collector.registerCount)
        assertEquals(2, collector.unregisterCount)
        assertEquals(2, sink.events.size)
        assertEquals(CollectorStatus.STOPPED, collector.health.value.status)
    }

    @Test
    fun uncertainPauseTeardownDrainsButRefusesToRegisterOverTheSource() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 1)
        collector.start()
        collector.trigger()
        collector.leaveNextUnregistrationUncertain = true

        val pauseFailure = runCatching { collector.pause() }.exceptionOrNull()

        assertTrue(pauseFailure is IllegalStateException)
        assertEquals(1, sink.events.size)
        assertEquals(CollectorHealth(CollectorStatus.FAILED, "SOURCE_UNREGISTRATION_FAILED"), collector.health.value)

        val resumeFailure = runCatching { collector.resume() }.exceptionOrNull()
        assertTrue(resumeFailure is IllegalStateException)
        assertEquals(1, collector.registerCount)

        // A later teardown retry may establish a known released state before final shutdown.
        collector.stop()
        assertEquals(2, collector.unregisterCount)
    }

    private fun kotlinx.coroutines.test.TestScope.context(sink: FakeSink) = CollectorContext(
        scope = backgroundScope,
        eventSink = sink,
        clocks = object : ResearchClocks {
            override fun now() = ResearchTime(1_000, 2_000, "boot-test")
        },
    )

    private class TestCollector(
        context: CollectorContext,
        queueCapacity: Int,
    ) : SerializedCallbackCollector(context, queueCapacity) {
        var registerCount = 0
        var unregisterCount = 0
        var draftConstructed = false
        var failNextRegistration = false
        var leaveNextRegistrationUncertain = false
        var failNextUnregistration = false
        var leaveNextUnregistrationUncertain = false

        fun trigger() = capture {
            draftConstructed = true
            EventDraft("test_collector.v1", 1, context.clocks.now(), "TEST", emptyMap())
        }

        override suspend fun registerSource(): SourceRegistrationResult {
            registerCount += 1
            if (leaveNextRegistrationUncertain) {
                leaveNextRegistrationUncertain = false
                return SourceRegistrationResult.Uncertain(
                    IllegalStateException("Registration rollback state is uncertain"),
                )
            }
            if (failNextRegistration) {
                failNextRegistration = false
                return SourceRegistrationResult.Released(IllegalStateException("Registration failed"))
            }
            return SourceRegistrationResult.Registered
        }

        override suspend fun unregisterSource(): SourceTeardownResult {
            unregisterCount += 1
            if (leaveNextUnregistrationUncertain) {
                leaveNextUnregistrationUncertain = false
                error("Unregistration state is uncertain")
            }
            if (failNextUnregistration) {
                failNextUnregistration = false
                return SourceTeardownResult.ReleasedWithFailure(
                    IllegalStateException("Unregistration reported a failure after release"),
                )
            }
            return SourceTeardownResult.Released
        }
    }

    private class FakeSink(
        private val admit: Boolean = true,
        private val storageFailure: Boolean = false,
    ) : EventSink {
        private val token = object : AdmissionToken {}
        val events = mutableListOf<EventDraft>()

        override fun captureToken(): AdmissionToken? = token.takeIf { admit }

        override suspend fun emit(token: AdmissionToken, event: EventDraft): EmitResult {
            events += event
            return if (storageFailure) EmitResult.StorageFailure else EmitResult.Accepted(events.size.toLong())
        }

        override suspend fun latestEvent(collectorId: String): RecordedEvent? = null
    }
}
