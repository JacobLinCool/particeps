package cool.jacoblin.particeps.collector.temporalcontext

import cool.jacoblin.particeps.core.collector.LatestValueRateGate
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.definition.TemporalContextV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.ResearchTime
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalContextCollectorTest {
    @Test
    fun derivesOffsetAndDaylightSavingFromTheCapturedInstant() {
        val winter = time(Instant.parse("2026-01-15T12:00:00Z").toEpochMilli())
        val summer = time(Instant.parse("2026-07-15T12:00:00Z").toEpochMilli())
        val zone = ZoneId.of("America/New_York")

        assertEquals(-18_000, temporalSnapshot(zone, winter).utcOffsetSeconds)
        assertFalse(temporalSnapshot(zone, winter).daylightSavingTime)
        assertEquals(-14_400, temporalSnapshot(zone, summer).utcOffsetSeconds)
        assertTrue(temporalSnapshot(zone, summer).daylightSavingTime)
    }

    @Test
    fun deferredDraftKeepsCaptureTimeWhileDeduplicationIgnoresOnlyThatTime() {
        val snapshot = TemporalSnapshot("UTC", 0, false)
        val captured = TemporalEvent("TIME_SET", snapshot, time(10))
        val later = TemporalEvent("TIME_SET", snapshot, time(30))
        val event = captured.eventDraft()

        assertFalse(sameTemporalEvent(captured, later))
        assertEquals(captured.observedTime, event.observedTime)
        assertEquals(TemporalContextV1ProfileConfiguration.SOURCE_ID, event.type.sourceId.value)
        assertTrue(requireNotNull(ProtocolEventSourceRegistry[TemporalContextV1ProfileConfiguration.SOURCE_ID]).accepts(event, 1, null))
        assertFalse(sameTemporalEvent(captured, later.copy(reason = "TIMEZONE_CHANGED")))
        assertFalse(sameTemporalEvent(captured, later.copy(snapshot = snapshot.copy(utcOffsetSeconds = 60))))
        assertTrue(
            sameTemporalEvent(
                captured.copy(reason = "RECONCILED"),
                later.copy(reason = "RECONCILED"),
            ),
        )
    }

    @Test
    fun repeatedClockSetBroadcastsCoalesceWithinTheBoundButEmitAgainAfterIt() {
        val snapshot = TemporalSnapshot("UTC", 0, false)
        val first = TemporalEvent("TIME_SET", snapshot, time(10))
        val second = TemporalEvent("TIME_SET", snapshot, time(20))
        val gate = LatestValueRateGate(60_000L, ::sameTemporalEvent)

        assertEquals(LatestValueRateGate.Decision.Emit(first), gate.offer(first, 1_000))
        assertEquals(LatestValueRateGate.Decision.Defer(59_000), gate.offer(second, 2_000))
        assertEquals(LatestValueRateGate.Decision.Emit(second), gate.poll(61_000))
        val third = TemporalEvent("TIME_SET", snapshot, time(30))
        assertEquals(LatestValueRateGate.Decision.Emit(third), gate.offer(third, 121_000))
    }

    private fun time(wallMillis: Long) = ResearchTime(wallMillis, wallMillis, "boot-a")
}
