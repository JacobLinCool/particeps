package cool.jacoblin.particeps.collector.batterystate

import android.os.BatteryManager
import cool.jacoblin.particeps.core.collector.LatestValueRateGate
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.accepts
import cool.jacoblin.particeps.core.definition.BatteryStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.ResearchTime
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
        assertEquals(BatteryStateV1ProfileConfiguration.SOURCE_ID, event.type.sourceId.value)
        assertTrue(requireNotNull(ProtocolEventSourceRegistry[BatteryStateV1ProfileConfiguration.SOURCE_ID]).accepts(event, 1, null))
        assertFalse(sameBatteryState(captured, later.copy(percentage = 49)))
    }

    private fun snapshot(time: ResearchTime) = BatterySnapshot(
        observedTime = time,
        percentage = 50,
        chargingState = "DISCHARGING",
        chargingSource = "NONE",
        powerSaveEnabled = false,
    )
}
