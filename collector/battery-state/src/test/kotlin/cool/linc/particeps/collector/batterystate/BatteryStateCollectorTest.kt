package cool.linc.particeps.collector.batterystate

import android.os.BatteryManager
import cool.linc.particeps.core.collector.LatestValueRateGate
import cool.linc.particeps.core.collector.ProtocolEventContracts
import cool.linc.particeps.core.definition.BatteryStateConfiguration
import cool.linc.particeps.core.model.RecordedEvent
import cool.linc.particeps.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateCollectorTest {
    @Test
    fun mapsBoundedBatteryStateWithoutHardwareIdentity() {
        assertEquals(50, batteryPercentage(1, 2))
        assertNull(batteryPercentage(-1, 100))
        assertNull(batteryPercentage(1, 0))
        assertEquals("CHARGING", chargingState(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("UNKNOWN", chargingState(-1))
        assertEquals("NONE", chargingSource(0))
        assertEquals("USB", chargingSource(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(
            "MULTIPLE",
            chargingSource(BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB),
        )
    }

    @Test
    fun deferredDraftKeepsCaptureTimeWhileDeduplicationIgnoresOnlyThatTime() {
        val captured = snapshot(ResearchTime(10, 20, "boot-a"))
        val later = snapshot(ResearchTime(30, 40, "boot-a"))
        val event = captured.eventDraft()

        assertTrue(sameBatteryState(captured, later))
        assertEquals(captured.observedTime, event.observedTime)
        assertEquals(BatteryStateConfiguration.ID, event.collectorId)
        assertTrue(requireNotNull(ProtocolEventContracts[BatteryStateConfiguration.ID]).accepts(event, 1))
        assertFalse(sameBatteryState(captured, later.copy(percentage = 49)))
    }

    @Test
    fun processRestartRestoresTheLastBatteryValueAndRateWatermark() {
        val previous = snapshot(ResearchTime(10_000, 10_000_000_000, "boot-a"))
        val draft = previous.eventDraft()
        val recorded = RecordedEvent(
            sequenceNumber = 1,
            collectorId = draft.collectorId,
            payloadSchemaVersion = draft.payloadSchemaVersion,
            observedTime = draft.observedTime,
            payloadType = draft.payloadType,
            fields = draft.fields,
        )
        val gate = LatestValueRateGate(60_000L, ::sameBatteryState)
        gate.restoreLastEmission(
            value = recorded.batterySnapshotOrNull(),
            currentElapsedMillis = 10_100,
        )

        assertEquals(
            LatestValueRateGate.Decision.Suppress,
            gate.offer(previous.copy(observedTime = ResearchTime(10_100, 10_100_000_000, "boot-a")), 10_100),
        )
        assertEquals(
            LatestValueRateGate.Decision.Defer(60_000),
            gate.offer(previous.copy(percentage = 49), 10_100),
        )
    }

    private fun snapshot(time: ResearchTime) = BatterySnapshot(
        observedTime = time,
        percentage = 50,
        chargingState = "DISCHARGING",
        chargingSource = "NONE",
        powerSaveEnabled = false,
    )
}
