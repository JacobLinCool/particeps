package cool.linc.androiddatacollector.core.collector

import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.ResearchTime
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
        var failNextUnregistration = false

        fun trigger() = capture {
            draftConstructed = true
            EventDraft("test_collector.v1", 1, context.clocks.now(), "TEST", emptyMap())
        }

        override suspend fun registerSource() {
            registerCount += 1
            if (failNextRegistration) {
                failNextRegistration = false
                error("Registration failed")
            }
        }

        override suspend fun unregisterSource() {
            unregisterCount += 1
            if (failNextUnregistration) {
                failNextUnregistration = false
                error("Unregistration failed")
            }
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
