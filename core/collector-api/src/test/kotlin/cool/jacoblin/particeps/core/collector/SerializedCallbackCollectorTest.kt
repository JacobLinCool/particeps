package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.MAX_OBSERVATION_EVENTS
import cool.jacoblin.particeps.core.model.ResearchTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
        val collector = TestCollector(context(sink), queueCapacity = 4, consumer())

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
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
        collector.start()

        collector.trigger()
        collector.triggerAll(3)
        collector.stop()

        assertFalse(collector.draftConstructed)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun storageFailureIsStableAcrossPauseDrain() = runTest {
        val sink = FakeSink(storageFailure = true)
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
        collector.start()

        collector.trigger()
        collector.pause()

        assertEquals(CollectorHealth(CollectorStatus.FAILED, "STORAGE_WRITE_FAILED"), collector.health.value)
        collector.stop()
    }

    @Test
    fun rejectedSubmissionRetriesTheSameProducerOrdinal() = runTest {
        val sink = FakeSink(rejectFirst = true)
        val collector = TestCollector(context(sink), queueCapacity = 2, consumer())
        collector.start()

        collector.trigger()
        runCurrent()
        collector.trigger()
        collector.stop()

        assertEquals(listOf(0L, 0L), sink.producerOrdinals)
    }

    @Test
    fun queuedCallbacksAreSubmittedAsOneObservationWithoutWaitingForMore() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 8, consumer())
        collector.start()

        repeat(3) { collector.trigger() }
        runCurrent()
        assertEquals(listOf(3), sink.batchSizes)

        // A later callback is not held back to fill a batch: it is submitted as soon as it queues.
        collector.trigger()
        runCurrent()
        assertEquals(listOf(3, 1), sink.batchSizes)
        assertEquals(listOf(0L, 1L), sink.producerOrdinals)
        assertEquals(0L, testScheduler.currentTime)
        collector.stop()
    }

    @Test
    fun barrierIsNeverMergedAcrossAndCompletesOnlyAfterEarlierEventsAreHandled() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 8, consumer())
        collector.start()
        val release = CompletableDeferred<Unit>()
        sink.beforeDecision = { batch -> if (batch.producerOrdinal == 0L) release.await() }

        repeat(2) { collector.trigger() }
        val pausing = async(start = CoroutineStart.UNDISPATCHED) { collector.pause() }
        repeat(2) { collector.trigger() }
        runCurrent()

        assertEquals(listOf(2), sink.offeredSizes)
        assertFalse("A barrier completed before its earlier events committed", pausing.isCompleted)

        release.complete(Unit)
        runCurrent()

        assertTrue(pausing.isCompleted)
        assertEquals(listOf(2, 2), sink.batchSizes)
        assertEquals(listOf(0L, 1L), sink.producerOrdinals)
        collector.resume()
        collector.stop()
    }

    @Test
    fun batchSplitsAtTheWorstCaseEncodedByteBound() = runTest {
        val sink = FakeSink()
        val contract = requireNotNull(ProtocolEventSourceRegistry["app_lifecycle.v1"])
        val limit = 1_024 * 1_024 / contract.maximumEncodedEventBytes
        assertEquals(limit, callbackBatchEventLimit(contract))
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
        collector.start()

        // One oversized callback splits the same way as many queued callbacks.
        collector.triggerAll(limit + 1)
        runCurrent()

        assertEquals(listOf(limit, 1), sink.batchSizes)
        assertEquals(listOf(0L, 1L), sink.producerOrdinals)
        collector.stop()
    }

    @Test
    fun batchSplitsAtTheObservationEventCountBound() = runTest {
        val sink = FakeSink()
        val base = requireNotNull(ProtocolEventSourceRegistry["app_lifecycle.v1"])
        val compact = base.copy(events = base.events.mapValues { (_, event) -> event.copy(maximumEncodedEventBytes = 128) })
        assertEquals(MAX_OBSERVATION_EVENTS, callbackBatchEventLimit(compact))
        val collector = TestCollector(
            context(sink, contract = compact),
            queueCapacity = MAX_OBSERVATION_EVENTS + 1,
            consumer(),
        )
        collector.start()

        repeat(MAX_OBSERVATION_EVENTS + 1) { collector.trigger() }
        runCurrent()

        assertEquals(listOf(MAX_OBSERVATION_EVENTS, 1), sink.batchSizes)
        collector.stop()
    }

    @Test
    fun batchLimitHonorsTheRegistryPerBatchBound() {
        val base = requireNotNull(ProtocolEventSourceRegistry["app_lifecycle.v1"])
        val bounded = base.copy(
            events = base.events.mapValues { (name, event) ->
                event.copy(maximumEncodedEventBytes = 128, maximumEventsPerBatch = if (name == "ACTIVITY_PAUSED") 7 else null)
            },
        )

        assertEquals(7, callbackBatchEventLimit(bounded))
        assertEquals(1, callbackBatchEventLimit(base, encodedByteBudget = 1))
    }

    @Test
    fun onlyEqualAdmissionTokensAreMerged() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 8, consumer())
        collector.start()

        // Distinct but equal tokens are interchangeable; a new gate generation is not.
        sink.token = GenerationToken(1)
        collector.trigger()
        sink.token = GenerationToken(1)
        collector.trigger()
        sink.token = GenerationToken(2)
        collector.trigger()
        runCurrent()

        assertEquals(listOf(2, 1), sink.batchSizes)
        assertEquals(listOf(GenerationToken(1), GenerationToken(2)), sink.acceptedTokens)
        collector.stop()
    }

    @Test
    fun observedTimeRegressionAndBootChangeStartANewObservation() = runTest {
        val sink = FakeSink()
        val clocks = ScriptedClocks(
            time(10),
            time(10),
            time(5),
            time(7),
            time(8, boot = "boot-other"),
            time(9, boot = "boot-other"),
        )
        val collector = TestCollector(context(sink, clocks = clocks), queueCapacity = 8, consumer())
        collector.start()

        repeat(6) { collector.trigger() }
        runCurrent()

        assertEquals(listOf(2, 2, 2), sink.batchSizes)
        sink.accepted.forEach { batch ->
            batch.events.zipWithNext().forEach { (left, right) ->
                assertEquals(left.observedTime.bootSessionId, right.observedTime.bootSessionId)
                assertTrue(left.observedTime.elapsedRealtimeNanos <= right.observedTime.elapsedRealtimeNanos)
            }
        }
        collector.stop()
    }

    @Test
    fun rejectedMergedBatchCommitsItsAdmittedPrefixWithContiguousOrdinals() = runTest {
        val sink = FakeSink()
        val clocks = ScriptedClocks(*Array(11) { index -> time(index + 1L) })
        val collector = TestCollector(context(sink, clocks = clocks), queueCapacity = 16, consumer())
        collector.start()
        sink.deadlineNanos = 7

        repeat(10) { collector.trigger() }
        runCurrent()

        assertEquals((1L..6L).toList(), sink.events.map { it.observedTime.elapsedRealtimeNanos })
        assertEquals(listOf(0L, 1L), sink.producerOrdinals)
        // Halving to the first acceptance, then the whole remainder again: 10 rejected, 5 accepted,
        // 5 and 2 rejected, 1 accepted, 4 and 2 rejected, and the refused single event ends it.
        assertEquals(listOf(10, 5, 5, 2, 1, 4, 2, 1), sink.offeredSizes)

        sink.deadlineNanos = Long.MAX_VALUE
        collector.trigger()
        runCurrent()
        assertEquals(listOf(0L, 1L, 2L), sink.producerOrdinals)
        assertEquals(CollectorStatus.ACTIVE, collector.health.value.status)
        collector.stop()
    }

    @Test
    fun wholeRejectedBatchIsDroppedWithLogarithmicOffers() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 64, consumer())
        collector.start()
        sink.deadlineNanos = 0

        repeat(50) { collector.trigger() }
        runCurrent()

        assertTrue(sink.accepted.isEmpty())
        assertEquals(listOf(50, 25, 12, 6, 3, 1), sink.offeredSizes)
        sink.deadlineNanos = Long.MAX_VALUE
        collector.trigger()
        runCurrent()
        assertEquals(listOf(0L), sink.producerOrdinals)
        collector.stop()
    }

    @Test
    fun admissionThatWidensAfterARejectionStillAdmitsTheRemainder() = runTest {
        val sink = FakeSink()
        // Past the deadline an open epoch refuses everything; the deadline drain then admits every
        // event observed before its boundary. Here the drain begins after the first two offers.
        sink.decide = { if (sink.offeredSizes.size <= 2) EmitBatchResult.RejectedByAdmissionGate else null }
        val collector = TestCollector(context(sink), queueCapacity = 16, consumer())
        collector.start()

        repeat(16) { collector.trigger() }
        runCurrent()

        // 16 and 8 are refused before the drain, 4 is accepted, and the remainder is offered again.
        assertEquals(listOf(16, 8, 4, 12), sink.offeredSizes)
        assertEquals(listOf(4, 12), sink.batchSizes)
        assertEquals(listOf(0L, 1L), sink.producerOrdinals)
        assertEquals(CollectorStatus.ACTIVE, collector.health.value.status)
        collector.stop()
    }

    @Test
    fun contractViolationRecordsTheValidEventsBeforeItAndFailsAtTheOffendingEvent() = runTest {
        val sink = FakeSink()
        sink.decide = { batch ->
            if (batch.events.any { it.fields["activity_class"] == "bad" }) {
                EmitBatchResult.ContractViolation
            } else {
                null
            }
        }
        val collector = TestCollector(context(sink), queueCapacity = 8, consumer())
        collector.start()

        collector.trigger(activityClass = "first")
        collector.trigger(activityClass = "second")
        collector.trigger(activityClass = "bad")
        collector.trigger(activityClass = "after")
        runCurrent()

        // The events captured before the offending one are recorded, as each alone would have been.
        assertEquals(listOf(4, 2, 2, 1), sink.offeredSizes)
        assertEquals(listOf("first", "second"), sink.events.map { it.fields.getValue("activity_class") })
        assertEquals(listOf(0L), sink.producerOrdinals)
        assertEquals(CollectorHealth(CollectorStatus.FAILED, "EVENT_CONTRACT_VIOLATION"), collector.health.value)
        collector.stop()
    }

    @Test
    fun partlyRecordedBatchOffersTheRestUnderTheNextProducerOrdinal() = runTest {
        val sink = FakeSink()
        // Like the runtime, record a live batch only up to the event at which a resource changes.
        sink.record = { batch ->
            batch.events.indexOfFirst { it.fields["activity_class"] == "trigger" }
                .takeIf { it > 0 } ?: batch.events.size
        }
        val collector = TestCollector(context(sink), queueCapacity = 8, consumer())
        collector.start()

        listOf("before", "before", "trigger", "after").forEach { collector.trigger(activityClass = it) }
        runCurrent()

        assertEquals(listOf(4, 2), sink.offeredSizes)
        assertEquals(listOf(2, 2), sink.batchSizes)
        assertEquals("trigger", sink.accepted.last().events.first().fields.getValue("activity_class"))
        assertEquals(listOf(0L, 1L), sink.producerOrdinals)
        assertEquals(CollectorStatus.ACTIVE, collector.health.value.status)
        collector.stop()
    }

    @Test
    fun acceptanceThatRecordsNoEventFailsInsteadOfOfferingForever() = runTest {
        val sink = FakeSink()
        sink.record = { 0 }
        val collector = TestCollector(context(sink), queueCapacity = 8, consumer())
        collector.start()

        repeat(2) { collector.trigger() }
        runCurrent()

        assertEquals(listOf(2), sink.offeredSizes)
        assertEquals(CollectorHealth(CollectorStatus.FAILED, "EVENT_SINK_CONTRACT_VIOLATION"), collector.health.value)
        collector.stop()
    }

    @Test
    fun captureAllQueuesOneMessageUnderOneToken() = runTest {
        val sink = FakeSink()
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
        collector.start()

        collector.triggerAll(3)
        assertEquals(1, sink.capturedTokens)
        // The one-slot queue is still full, so queueing an empty delivery would fail the collector.
        collector.triggerAll(0)
        runCurrent()

        assertEquals(listOf(3), sink.batchSizes)
        assertEquals(CollectorStatus.ACTIVE, collector.health.value.status)
        collector.stop()
    }

    @Test
    fun failedSourceRegistrationRollsBackConsumerAndCanRetry() = runTest {
        val collector = TestCollector(context(FakeSink()), queueCapacity = 1, consumer())
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
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
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
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
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
        val collector = TestCollector(context(FakeSink()), queueCapacity = 1, consumer())
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
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
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
        val collector = TestCollector(context(sink), queueCapacity = 1, consumer())
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

    /** Runs the consumer only when the test yields, so queue contents at each wake are exact. */
    private fun TestScope.consumer(): CoroutineDispatcher = StandardTestDispatcher(testScheduler)

    private fun TestScope.context(
        sink: FakeSink,
        clocks: ResearchClocks = FixedClocks,
        contract: RegistrySourceContract = requireNotNull(ProtocolEventSourceRegistry["app_lifecycle.v1"]),
    ) = CollectorContext(
        scope = backgroundScope,
        eventSink = sink,
        clocks = clocks,
        sourceContract = contract,
        resourceGeneration = 3,
        tokenEncoder = StudyScopedTokenEncoder { _, _ -> "0".repeat(64) },
    )

    private object FixedClocks : ResearchClocks {
        override fun now() = time(2_000)
    }

    private class ScriptedClocks(vararg times: ResearchTime) : ResearchClocks {
        private val remaining = ArrayDeque(times.toList())

        override fun now(): ResearchTime = remaining.removeFirst()
    }

    private data class GenerationToken(val generation: Long) : AdmissionToken

    private class TestCollector(
        context: CollectorContext,
        queueCapacity: Int,
        consumerDispatcher: CoroutineDispatcher,
    ) : SerializedCallbackCollector(context, queueCapacity, consumerDispatcher) {
        var registerCount = 0
        var unregisterCount = 0
        var draftConstructed = false
        var failNextRegistration = false
        var leaveNextRegistrationUncertain = false
        var failNextUnregistration = false
        var leaveNextUnregistrationUncertain = false

        fun trigger(activityClass: String = "test.Activity") = capture { draft(activityClass) }

        fun triggerAll(count: Int) = captureAll { List(count) { draft("test.Activity") } }

        private fun draft(activityClass: String): EventDraft {
            draftConstructed = true
            return EventDraft(
                EventTypeKey(EventSourceId("app_lifecycle.v1"), 1, "ACTIVITY_RESUMED"),
                context.clocks.now(),
                mapOf("activity_class" to activityClass),
            )
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

    /**
     * Admits like the runtime gate: a whole batch or nothing, and, under [deadlineNanos], only a
     * batch whose every observed time is strictly before that deadline.
     */
    private class FakeSink(
        private val admit: Boolean = true,
        private val storageFailure: Boolean = false,
        private val rejectFirst: Boolean = false,
    ) : EventSink {
        var token: AdmissionToken = object : AdmissionToken {}
        var deadlineNanos = Long.MAX_VALUE
        var decide: (SourceEventBatch) -> EmitBatchResult? = { null }
        /** How many leading events of an admitted batch the one observation records. */
        var record: (SourceEventBatch) -> Int = { it.events.size }
        var beforeDecision: suspend (SourceEventBatch) -> Unit = {}
        val events = mutableListOf<EventDraft>()
        val producerOrdinals = mutableListOf<Long>()
        val offeredSizes = mutableListOf<Int>()
        val accepted = mutableListOf<SourceEventBatch>()
        val acceptedTokens = mutableListOf<AdmissionToken>()
        val batchSizes: List<Int> get() = accepted.map { it.events.size }
        var capturedTokens = 0
        private var calls = 0

        override fun captureToken(): AdmissionToken? = token.takeIf { admit }?.also { capturedTokens += 1 }

        override fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken? = null

        override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult {
            calls += 1
            offeredSizes += batch.events.size
            beforeDecision(batch)
            if (rejectFirst && calls == 1) {
                producerOrdinals += batch.producerOrdinal
                return EmitBatchResult.RejectedByAdmissionGate
            }
            if (batch.events.any { it.observedTime.elapsedRealtimeNanos >= deadlineNanos }) {
                return EmitBatchResult.RejectedByAdmissionGate
            }
            decide(batch)?.let { return it }
            producerOrdinals += batch.producerOrdinal
            if (storageFailure) return EmitBatchResult.StorageFailure
            val recorded = batch.events.take(record(batch))
            events += recorded
            if (recorded.isNotEmpty()) accepted += batch.copy(events = recorded)
            acceptedTokens += token
            return EmitBatchResult.Accepted(
                observationSequence = producerOrdinals.size.toLong(),
                recordedEvents = recorded.size,
            )
        }

        override suspend fun advanceCoverage(
            token: AdmissionToken,
            advance: CoverageAdvance,
        ): EmitBatchResult = error("Live collector must not advance retrospective coverage")
    }

    private companion object {
        fun time(nanos: Long, boot: String = "boot-test") = ResearchTime(1_000, nanos, boot)
    }
}
