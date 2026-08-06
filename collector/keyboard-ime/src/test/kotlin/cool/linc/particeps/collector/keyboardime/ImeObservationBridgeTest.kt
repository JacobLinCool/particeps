package cool.linc.particeps.collector.keyboardime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeObservationBridgeTest {
    @Test
    fun uninstallWaitsForInFlightDeliveryAndRejectsLaterObservations() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uninstallStarted = CountDownLatch(1)
        val uninstallFinished = CountDownLatch(1)
        var deliveries = 0
        ImeObservationBridge.install(samplingHz = 10) {
            entered.countDown()
            check(release.await(1, TimeUnit.SECONDS))
            deliveries++
        }
        val publisher = Thread { ImeObservationBridge.publishObservation(observation("DOWN", 100)) }
        val uninstaller = Thread {
            uninstallStarted.countDown()
            ImeObservationBridge.uninstall()
            uninstallFinished.countDown()
        }

        try {
            publisher.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            uninstaller.start()
            assertTrue(uninstallStarted.await(1, TimeUnit.SECONDS))
            assertFalse(uninstallFinished.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(uninstallFinished.await(1, TimeUnit.SECONDS))
            ImeObservationBridge.publishObservation(observation("DOWN", 200))
            assertEquals(1, deliveries)
        } finally {
            release.countDown()
            publisher.join(1_000)
            uninstaller.join(1_000)
            ImeObservationBridge.uninstall()
        }
    }

    @Test
    fun firstMoveIsDeliveredAndLaterMovesRespectTheConfiguredRate() {
        val deliveryTimes = mutableListOf<Long>()
        ImeObservationBridge.install(samplingHz = 10) { deliveryTimes += it.eventUptimeMillis }
        try {
            ImeObservationBridge.publishObservation(observation("MOVE", 100))
            ImeObservationBridge.publishObservation(observation("MOVE", 150))
            ImeObservationBridge.publishObservation(observation("MOVE", 200))
        } finally {
            ImeObservationBridge.uninstall()
        }

        assertEquals(listOf(100L, 200L), deliveryTimes)
    }

    private fun observation(action: String, eventUptimeMillis: Long) = ImeTouchObservation(
        action = action,
        eventUptimeMillis = eventUptimeMillis,
        downUptimeMillis = eventUptimeMillis,
        pointerId = 0,
        relativeX = 0.5f,
        relativeY = 0.5f,
        pressure = 1f,
        size = 1f,
        orientationRadians = 0f,
        toolType = 1,
        keyCategory = "letter",
    )
}
