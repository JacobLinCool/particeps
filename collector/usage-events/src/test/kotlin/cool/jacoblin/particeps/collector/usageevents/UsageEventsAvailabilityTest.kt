package cool.jacoblin.particeps.collector.usageevents

import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorFlushResult
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.collector.StudyScopedTokenEncoder
import cool.jacoblin.particeps.core.definition.UsageEventsV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.ResearchTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventsAvailabilityTest {
    @Test
    fun availableEmptyQueryAdvancesCoverageWithoutInventingEvents() = withFixture {
        collector.start()
        assertEquals(CollectorFlushResult.Complete(time(2_000), "2000"), collector.flushThrough(time(2_000), null))
        assertEquals(listOf(1_000L to 2_000L), query.queries)
        assertEquals(1, sink.coverage.size)
        assertEquals("1000", sink.coverage.single().coverage.startInclusive)
        assertEquals("2000", sink.coverage.single().coverage.endExclusive)
        assertTrue(sink.batches.isEmpty())
    }

    @Test
    fun revokedAccessBeforeQueryDoesNotClaimCoverageOrReadEvents() = withFixture {
        collector.start()
        query.unavailable = UsageEventsUnavailableReason.ACCESS_DENIED
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        assertFailure("USAGE_ACCESS_REVOKED")
        assertTrue(query.queries.isEmpty())
        assertTrue(sink.coverage.isEmpty())
        assertTrue(sink.batches.isEmpty())
    }

    @Test
    fun revokedAccessDuringEmptyQueryDoesNotBecomeZeroUsage() = withFixture {
        collector.start()
        query.duringQuery = { query.unavailable = UsageEventsUnavailableReason.ACCESS_DENIED }
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        assertEquals(listOf(1_000L to 2_000L), query.queries)
        assertFailure("USAGE_ACCESS_REVOKED")
        assertTrue(sink.coverage.isEmpty())
        assertTrue(sink.batches.isEmpty())
    }

    @Test
    fun revokedAccessDuringNonemptyQueryRejectsWholeObservation() = withFixture {
        collector.start()
        query.result = listOf(UsageSourceEvent("ACTIVITY_RESUMED", 1_500, "com.example.reader", "ReaderActivity"))
        query.duringQuery = { query.unavailable = UsageEventsUnavailableReason.ACCESS_DENIED }
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        assertFailure("USAGE_ACCESS_REVOKED")
        assertTrue(sink.coverage.isEmpty())
        assertTrue(sink.batches.isEmpty())
    }

    @Test
    fun lockedUserCannotClaimAnEmptyCoverageInterval() = withFixture {
        collector.start()
        query.unavailable = UsageEventsUnavailableReason.USER_LOCKED
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        assertFailure("USAGE_EVENTS_USER_LOCKED")
        assertTrue(sink.coverage.isEmpty())
    }

    @Test
    fun sameBoundaryFlushStillChecksAvailability() = withFixture {
        collector.start()
        query.unavailable = UsageEventsUnavailableReason.ACCESS_DENIED
        assertTrue(collector.flushThrough(time(1_000), null) is CollectorFlushResult.Failed)
        assertFailure("USAGE_ACCESS_REVOKED")
        assertTrue(sink.coverage.isEmpty())
    }

    @Test
    fun restoringPermissionCannotSilentlyReuseAFailedSource() = withFixture {
        collector.start()
        query.unavailable = UsageEventsUnavailableReason.ACCESS_DENIED
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        query.unavailable = null
        assertTrue(collector.flushThrough(time(3_000), null) is CollectorFlushResult.Failed)
        collector.pause()
        assertFailure("USAGE_ACCESS_REVOKED")
        assertTrue(runCatching { collector.resume() }.exceptionOrNull() is IllegalStateException)
        assertTrue(query.queries.isEmpty())
        assertTrue(sink.coverage.isEmpty())
    }

    @Test
    fun queryExceptionDoesNotClaimCoverage() = withFixture {
        collector.start()
        query.duringQuery = { throw IllegalStateException("Platform query failed") }
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        assertFailure("USAGE_EVENTS_QUERY_FAILED")
        assertTrue(sink.coverage.isEmpty())
    }

    @Test
    fun accessInspectionFailureDoesNotClaimCoverage() = withFixture {
        collector.start()
        query.duringAvailability = { throw IllegalStateException("AppOps service unavailable") }
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        assertFailure("USAGE_EVENTS_QUERY_FAILED")
        assertTrue(query.queries.isEmpty())
        assertTrue(sink.coverage.isEmpty())
    }

    @Test
    fun deniedAdmissionDoesNotSkipTheUncommittedQueryWindow() = withFixture {
        collector.start()
        sink.result = EmitBatchResult.RejectedByAdmissionGate
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Failed)
        sink.result = EmitBatchResult.Accepted(1, 0)
        assertTrue(collector.flushThrough(time(3_000), null) is CollectorFlushResult.Complete)
        assertEquals(listOf(1_000L to 2_000L, 1_000L to 3_000L), query.queries)
        assertEquals(listOf(0L, 0L), sink.coverage.map { it.producerOrdinal })
        assertFalse(collector.health.value.status == CollectorStatus.FAILED)
    }

    @Test
    fun normalEventsKeepTheirSourceTimeAndOpaqueComponent() = withFixture {
        collector.start()
        query.result = listOf(UsageSourceEvent("ACTIVITY_RESUMED", 1_500, "com.example.reader", "ReaderActivity"))
        assertTrue(collector.flushThrough(time(2_000), null) is CollectorFlushResult.Complete)
        val event = sink.batches.single().events.single()
        assertEquals("1500", event.fields["source_time_utc_millis"])
        assertEquals("com.example.reader", event.fields["package_name"])
        assertEquals("a".repeat(64), event.fields["activity_component_token"])
        assertEquals(time(2_000), event.observedTime)
        assertTrue(sink.coverage.isEmpty())
    }

    private fun withFixture(test: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            fixture.test()
        } finally {
            fixture.collector.stop()
            fixture.scope.cancel()
        }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob())
        val query = FakeQuery()
        val sink = Sink()
        val collector = UsageEventsCollector(
            query,
            UsageEventsV1ProfileConfiguration(86_400),
            CollectorContext(
                scope, sink, object : ResearchClocks { override fun now() = time(1_000) },
                requireNotNull(ProtocolEventSourceRegistry[UsageEventsV1ProfileConfiguration.SOURCE_ID]),
                1, StudyScopedTokenEncoder { _, _ -> "a".repeat(64) }, false,
            ),
        )

        fun assertFailure(reason: String) {
            assertEquals(CollectorStatus.FAILED, collector.health.value.status)
            assertEquals(reason, collector.health.value.reasonCode)
        }
    }

    private class FakeQuery : UsageEventsQuery {
        var unavailable: UsageEventsUnavailableReason? = null
        var result = emptyList<UsageSourceEvent>()
        var duringAvailability: () -> Unit = {}
        var duringQuery: () -> Unit = {}
        val queries = mutableListOf<Pair<Long, Long>>()
        override fun unavailableReason(): UsageEventsUnavailableReason? {
            duringAvailability()
            return unavailable
        }
        override fun events(startUtcMillis: Long, endUtcMillis: Long): List<UsageSourceEvent> {
            queries += startUtcMillis to endUtcMillis
            duringQuery()
            return result
        }
    }

    private class Sink : EventSink {
        private val token = object : AdmissionToken {}
        var result: EmitBatchResult? = null
        val coverage = mutableListOf<CoverageAdvance>()
        val batches = mutableListOf<SourceEventBatch>()
        override fun captureToken() = token
        override fun captureBarrierFlushToken(boundary: ResearchTime) = token
        override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult {
            batches += batch
            return result ?: EmitBatchResult.Accepted(1, batch.events.size)
        }
        override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance): EmitBatchResult {
            coverage += advance
            return result ?: EmitBatchResult.Accepted(1, 0)
        }
    }

    private companion object {
        fun time(wallMillis: Long) = ResearchTime(wallMillis, wallMillis * 1_000_000, "usage-access-test")
    }
}
