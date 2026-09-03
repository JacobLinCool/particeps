package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.SourceCoverage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceEventBatchTest {
    private val time = ResearchTime(1_000, 2_000, "boot-test")

    @Test
    fun retrospectiveBatchRequiresCoverageAndMatchingIdentity() {
        val event = EventDraft(
            EventTypeKey(EventSourceId("usage_events.v1"), 1, "SCREEN_INTERACTIVE"),
            time,
            mapOf("source_time_utc_millis" to "1000"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SourceEventBatch(EventSourceId("usage_events.v1"), 1, 1, 0, listOf(event))
        }

        val batch = SourceEventBatch(
            EventSourceId("usage_events.v1"),
            1,
            1,
            0,
            listOf(event),
            SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "900", "1001"),
        )
        assertEquals(1, batch.events.size)
    }

    @Test
    fun batchRejectsMixedSourceEvents() {
        val event = EventDraft(
            EventTypeKey(EventSourceId("app_lifecycle.v1"), 1, "ACTIVITY_RESUMED"),
            time,
            mapOf("activity_class" to "test.Activity"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SourceEventBatch(EventSourceId("battery_state.v1"), 1, 1, 0, listOf(event))
        }
    }

    @Test
    fun liveCollectorDefaultFlushCompletesAndRetrospectiveDefaultFailsClosed() = runTest {
        val live = FakeCollector(CollectorObservationMode.LIVE)
        val retrospective = FakeCollector(CollectorObservationMode.RETROSPECTIVE)

        assertEquals(CollectorFlushResult.Complete(time, null), live.flushThrough(time, "ignored"))
        assertEquals(
            CollectorFlushResult.Failed(CollectorFlushFailureReason.RETROSPECTIVE_FLUSH_NOT_IMPLEMENTED),
            retrospective.flushThrough(time, null),
        )
    }

    private class FakeCollector(
        override val observationMode: CollectorObservationMode,
    ) : Collector {
        override val health = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override suspend fun start() = Unit
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun stop() = Unit
    }
}
