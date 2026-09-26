package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.math.BigInteger
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun oneBuilderDigestEqualsThePlainPerComponentDigestForHostileText() {
        // State keys refuse NUL; the free-text values that accept it are escaped as %00.
        val hostileKey = "a%b:c=d\uD800f\uDC00g\uD83D\uDE00h\u00e9\u4e2d"
        val hostile = "$hostileKey\u0000"
        val timer = DurableTimer(
            id = "b".repeat(64),
            automationId = "daily-check-in",
            generation = ULong.MAX_VALUE,
            causalSequence = 8,
            producerKey = "condition:a%b=c",
            target = TimerTarget.SameBootMonotonic("boot:%=\u00e9", 12),
            logicalDeadlineUtcMillis = null,
            expiresAtUtcMillis = 1_800_000_300_000,
        )
        val checkpoint = AutomationCheckpoint(
            evaluatedThroughSequence = 8,
            lifecycle = StudySessionState.RUNNING,
            studyStartUtcMillis = 1_799_000_000_000,
            lastActiveElapsedNanos = 5_000,
            lastCalendarElapsedNanos = 6_000,
            latchValues = mapOf("latch:$hostileKey" to true, "latch:plain" to false),
            presenceKeys = mapOf("presence:$hostileKey" to setOf(hostile, "=", "\u0000")),
            heldSinceNanos = mapOf("held:$hostileKey" to 4_000),
            priorConditionValues = mapOf("prior:$hostileKey" to false),
            windows = mapOf("window:$hostileKey" to listOf(WindowEntry(7, 3_000, "boot:$hostile", BigInteger.TEN.negate()))),
            sequences = mapOf("sequence:$hostileKey" to listOf(SequencePartial(2, 4, 7, 2_000, "boot=$hostile"))),
            activationCounts = mapOf("daily-check-in" to 1),
            cooldownMarks = mapOf("daily-check-in" to CooldownMark(4_000, 5_000)),
            desiredResources = mapOf(
                ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1") to DesiredProfile(ResourceGeneration(3uL), null),
                ResourceKey(ResourceKind.COLLECTOR, "battery_state.v1") to DesiredProfile(ResourceGeneration(1uL), hostile),
            ),
            timers = mapOf(timer.id to timer),
            timerGenerations = mapOf("condition:$hostileKey" to ULong.MAX_VALUE),
            materializedTimers = mapOf(
                "daily-check-in" to listOf(MaterializedTimerSummary("daily:$hostile", 1_799_000_000_000, true)),
            ),
        )

        assertEquals(plainDigest(checkpoint), checkpoint.digest())
        // A timer keeps its digest component; a second checkpoint carrying it digests the same way.
        val next = checkpoint.copy(evaluatedThroughSequence = 9)
        assertEquals(plainDigest(next), next.digest())
        // An unpaired surrogate encodes as '?', so the encoding, not the text, is what round-trips.
        val encoded = AutomationCheckpointCodec.encode(checkpoint)
        assertEquals(encoded, AutomationCheckpointCodec.encode(AutomationCheckpointCodec.decode(encoded)))
    }

    @Test
    fun theSizeBoundIsTheExactUtf8LengthOfThePlainPreimage() {
        val limit = 512 * 1_024
        fun checkpoint(padding: Int): AutomationCheckpoint {
            val keys = (0 until 1_021).associate { index -> "latch-%04d-".format(index) + "\u00e9".repeat(245) to true }
            return AutomationCheckpoint(latchValues = keys + ("tail-" + "x".repeat(padding) to false))
        }
        fun size(value: AutomationCheckpoint) = plainComponents(value).fold(DOMAIN.toByteArray().size) { total, component ->
            total + 1 + component.toByteArray(Charsets.UTF_8).size
        }
        val probe = AutomationCheckpoint(latchValues = checkpoint(0).latchValues.filterKeys { !it.startsWith("tail-") })
        val padding = limit - size(probe) - 1 - "latch:tail-=false".length
        assertTrue(padding in 0..500)

        val atLimit = checkpoint(padding)
        assertEquals(limit, size(atLimit))
        assertEquals(plainDigest(atLimit), atLimit.digest())
        assertThrows(IllegalArgumentException::class.java) { checkpoint(padding + 1) }
    }

    /** `DeterministicIds.digest` over one template-built string per checkpoint fact. */
    private fun plainDigest(checkpoint: AutomationCheckpoint): String =
        DeterministicIds.digest(DOMAIN, plainComponents(checkpoint))

    private fun plainComponents(checkpoint: AutomationCheckpoint): List<String> {
        fun escape(value: String) = value.replace("%", "%25").replace("\u0000", "%00")
            .replace(":", "%3a").replace("=", "%3d")
        val components = mutableListOf<String>()
        components += "evaluated=${checkpoint.evaluatedThroughSequence}"
        components += "lifecycle=${checkpoint.lifecycle.name}"
        components += "start=${checkpoint.studyStartUtcMillis ?: ""}"
        components += "active=${checkpoint.lastActiveElapsedNanos}"
        components += "calendar=${checkpoint.lastCalendarElapsedNanos}"
        checkpoint.latchValues.toSortedMap().forEach { (key, value) -> components += "latch:${escape(key)}=$value" }
        checkpoint.presenceKeys.toSortedMap().forEach { (key, values) ->
            values.sorted().forEach { value -> components += "presence:${escape(key)}:${escape(value)}" }
        }
        checkpoint.heldSinceNanos.toSortedMap().forEach { (key, value) -> components += "held:${escape(key)}=$value" }
        checkpoint.priorConditionValues.toSortedMap().forEach { (key, value) -> components += "prior:${escape(key)}=$value" }
        checkpoint.windows.toSortedMap().forEach { (key, values) ->
            values.forEach { entry ->
                components += "window:${escape(key)}:${entry.sequenceNumber}:${entry.timeNanos}:" +
                    "${escape(entry.bootSessionId)}:${entry.numericValue}"
            }
        }
        checkpoint.sequences.toSortedMap().forEach { (key, values) ->
            values.forEach { partial ->
                components += "sequence:${escape(key)}:${partial.nextStep}:${partial.firstSequenceNumber}:" +
                    "${partial.lastSequenceNumber}:${partial.firstTimeNanos}:${escape(partial.bootSessionId)}"
            }
        }
        checkpoint.activationCounts.toSortedMap().forEach { (key, value) -> components += "activation:${escape(key)}=$value" }
        checkpoint.cooldownMarks.toSortedMap().forEach { (key, value) ->
            components += "cooldown:${escape(key)}:${value.activeElapsedNanos}:${value.calendarElapsedNanos}"
        }
        checkpoint.desiredResources.toSortedMap().forEach { (key, value) ->
            components += "resource:${key.kind.name}:${escape(key.id)}:${value.generation}:${escape(value.profileId.orEmpty())}"
        }
        checkpoint.timers.toSortedMap().forEach { (_, timer) ->
            val target = when (val value = timer.target) {
                is TimerTarget.CalendarUtc -> "calendar:${value.utcMillis}"
                is TimerTarget.ActiveElapsed -> "active:${value.elapsedNanos}"
                is TimerTarget.SameBootMonotonic -> "monotonic:${escape(value.bootSessionId)}:${value.elapsedRealtimeNanos}"
            }
            components += "timer:${timer.id}:${escape(timer.automationId)}:${timer.generation}:${timer.causalSequence}:" +
                "${escape(timer.producerKey)}:$target:${timer.logicalDeadlineUtcMillis ?: ""}:${timer.expiresAtUtcMillis ?: ""}"
        }
        checkpoint.timerGenerations.toSortedMap().forEach { (key, value) -> components += "timer-generation:${escape(key)}:$value" }
        checkpoint.materializedTimers.toSortedMap().forEach { (key, values) ->
            values.forEach { timer ->
                components += "materialized:${escape(key)}:${escape(timer.producerKey)}:${timer.selectedUtcMillis}:${timer.terminal}"
            }
        }
        return components
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

    private companion object {
        const val DOMAIN = "particeps-automation-checkpoint-v1"
    }
}
