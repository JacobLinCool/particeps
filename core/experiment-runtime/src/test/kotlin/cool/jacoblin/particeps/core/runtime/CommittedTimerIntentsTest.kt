package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class CommittedTimerIntentsTest {
    @Test
    fun singleInputIntentsAreKeptInTheirOwnOrderEvenWhenItIsNotTimerIdOrder() {
        // One input arms two new timers, fires one and re-arms it, in an order that is not by ID.
        val fired = timer(B, 1uL)
        val rearmed = timer(B, 2uL)
        val before = mapOf(B to fired)
        val after = mapOf(C to timer(C, 1uL), A to timer(A, 1uL), B to rearmed)
        val intents = listOf(
            TimerIntent.Schedule(timer(C, 1uL)),
            TimerIntent.Schedule(timer(A, 1uL)),
            TimerIntent.Retire(B, 1uL),
            TimerIntent.Schedule(rearmed),
        )

        assertEquals(intents, committedTimerIntents(before, after, intents))
    }

    @Test
    fun mergedReplacementsRetireTheBaseOnceAndScheduleOnlyTheFinalGeneration() {
        // Three merged inputs each slide one window; a fourth intent repeats the base retirement.
        val before = mapOf(A to timer(A, 1uL))
        val after = mapOf(A to timer(A, 4uL))
        val intents = listOf(
            TimerIntent.Retire(A, 1uL),
            TimerIntent.Retire(A, 2uL),
            TimerIntent.Retire(A, 3uL),
            TimerIntent.Retire(A, 1uL),
            TimerIntent.Schedule(timer(A, 2uL)),
            TimerIntent.Schedule(timer(A, 3uL)),
            TimerIntent.Schedule(timer(A, 4uL)),
        )

        assertEquals(
            listOf(TimerIntent.Retire(A, 1uL), TimerIntent.Schedule(timer(A, 4uL))),
            committedTimerIntents(before, after, intents),
        )
    }

    @Test
    fun mergedArmingOfANewTimerSchedulesOnlyItsFinalGeneration() {
        val after = mapOf(A to timer(A, 2uL))
        val intents = listOf(
            TimerIntent.Retire(A, 1uL),
            TimerIntent.Schedule(timer(A, 1uL)),
            TimerIntent.Schedule(timer(A, 2uL)),
        )

        assertEquals(listOf(TimerIntent.Schedule(timer(A, 2uL))), committedTimerIntents(emptyMap(), after, intents))
    }

    @Test
    fun aTimerArmedAndCancelledWithinOneBatchLeavesNothing() {
        val intents = listOf(TimerIntent.Retire(A, 1uL), TimerIntent.Schedule(timer(A, 1uL)))

        assertEquals(emptyList<TimerIntent>(), committedTimerIntents(emptyMap(), emptyMap(), intents))
    }

    @Test
    fun aBaseTimerReplacedAndThenCancelledOnlyRetires() {
        val before = mapOf(A to timer(A, 1uL))
        val intents = listOf(
            TimerIntent.Retire(A, 1uL),
            TimerIntent.Retire(A, 2uL),
            TimerIntent.Schedule(timer(A, 2uL)),
        )

        assertEquals(listOf(TimerIntent.Retire(A, 1uL)), committedTimerIntents(before, emptyMap(), intents))
    }

    @Test
    fun aRetirementOfAGenerationTheBaseDoesNotHoldIsDropped() {
        val current = mapOf(A to timer(A, 2uL))

        assertEquals(
            emptyList<TimerIntent>(),
            committedTimerIntents(current, current, listOf(TimerIntent.Retire(A, 1uL))),
        )
    }

    private fun timer(id: String, generation: ULong) = DurableTimer(
        id = id,
        automationId = "window-binding",
        generation = generation,
        causalSequence = generation.toLong(),
        producerKey = "condition:$id",
        target = TimerTarget.SameBootMonotonic("boot-one", generation.toLong() * 1_000_000_000L),
        logicalDeadlineUtcMillis = null,
        expiresAtUtcMillis = null,
    )

    private companion object {
        val A = "a".repeat(64)
        val B = "b".repeat(64)
        val C = "c".repeat(64)
    }
}
