package cool.linc.particeps.collector.temporalcontext

import cool.linc.particeps.core.collector.LatestValueRateGate
import cool.linc.particeps.core.collector.ProtocolEventContracts
import cool.linc.particeps.core.definition.TemporalContextConfiguration
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
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
        assertEquals(TemporalContextConfiguration.ID, event.collectorId)
        assertTrue(requireNotNull(ProtocolEventContracts[TemporalContextConfiguration.ID]).accepts(event, 1))
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

    @Test
    fun processRestartRestoresClockSetRateWatermarkWithoutSuppressingItForever() {
        val previous = TemporalEvent(
            "TIME_SET",
            TemporalSnapshot("UTC", 0, false),
            ResearchTime(10_000, 10_000_000_000, "boot-a"),
        )
        val draft = previous.eventDraft()
        val recorded = RecordedEvent(
            1,
            draft.collectorId,
            draft.payloadSchemaVersion,
            draft.observedTime,
            draft.payloadType,
            draft.fields,
        )
        val gate = LatestValueRateGate(60_000L, ::sameTemporalEvent)
        gate.restoreLastEmission(
            recorded.temporalEventOrNull(),
            10_100,
        )

        val current = previous.copy(observedTime = ResearchTime(10_100, 10_100_000_000, "boot-a"))
        assertEquals(LatestValueRateGate.Decision.Defer(60_000), gate.offer(current, 10_100))
        assertEquals(LatestValueRateGate.Decision.Emit(current), gate.poll(70_100))
    }

    private fun time(wallMillis: Long) = ResearchTime(wallMillis, wallMillis, "boot-a")
}
