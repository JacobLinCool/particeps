package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.math.BigInteger
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationCheckpointCodecTest {
    @Test
    fun completeCheckpointRoundTripsCanonically() {
        val resource = ResourceKey(ResourceKind.COLLECTOR, "battery_state.v1")
        val timer = DurableTimer(
            id = "a".repeat(64),
            automationId = "daily-check-in",
            generation = 2uL,
            causalSequence = 8,
            producerKey = "daily:2026-08-23",
            target = TimerTarget.CalendarUtc(1_800_000_000_000),
            logicalDeadlineUtcMillis = 1_800_000_000_000,
            expiresAtUtcMillis = 1_800_000_300_000,
        )
        val checkpoint = AutomationCheckpoint(
            evaluatedThroughSequence = 8,
            lifecycle = StudySessionState.RUNNING,
            studyStartUtcMillis = 1_799_000_000_000,
            lastActiveElapsedNanos = 5_000,
            lastCalendarElapsedNanos = 6_000,
            latchValues = mapOf("latch:usage" to true),
            presenceKeys = mapOf("presence:usage" to setOf("b", "a")),
            heldSinceNanos = mapOf("held:usage" to 4_000),
            priorConditionValues = mapOf("condition:usage" to false),
            windows = mapOf("window:usage" to listOf(WindowEntry(7, 3_000, "boot-one", BigInteger.TEN))),
            sequences = mapOf("sequence:usage" to listOf(SequencePartial(2, 4, 7, 2_000, "boot-one"))),
            activationCounts = mapOf("daily-check-in" to 1),
            cooldownMarks = mapOf("daily-check-in" to CooldownMark(4_000, 5_000)),
            desiredResources = mapOf(resource to DesiredProfile(ResourceGeneration(3uL), "continuous")),
            timers = mapOf(timer.id to timer),
            timerGenerations = mapOf("timer:daily-check-in" to 2uL),
            materializedTimers = mapOf(
                "daily-check-in" to listOf(MaterializedTimerSummary("daily:2026-08-22", 1_799_000_000_000, true)),
            ),
        )

        val encoded = AutomationCheckpointCodec.encode(checkpoint)

        assertEquals(checkpoint, AutomationCheckpointCodec.decode(encoded))
        assertEquals(encoded, AutomationCheckpointCodec.encode(AutomationCheckpointCodec.decode(encoded)))
    }

    @Test
    fun nonCanonicalBase64urlAndTrailingBytesAreRejected() {
        val encoded = AutomationCheckpointCodec.encode(AutomationCheckpoint())
        val prefix = encoded.substringBefore(':') + ':'
        val payload = Base64.getUrlDecoder().decode(encoded.removePrefix(prefix))
        val withTrailingByte = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(payload + byteArrayOf(0))

        assertThrows(IllegalArgumentException::class.java) { AutomationCheckpointCodec.decode("$encoded=") }
        assertThrows(IllegalArgumentException::class.java) { AutomationCheckpointCodec.decode(withTrailingByte) }
    }
}
