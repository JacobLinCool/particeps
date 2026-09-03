package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeEventFactoryTest {
    @Test
    fun timerAuditUsesStableClockDomainTargets() {
        val active = timer(TimerTarget.ActiveElapsed(180_000_000_000), logicalWall = null)
        val calendar = timer(TimerTarget.CalendarUtc(1_800_000_000_000), logicalWall = 1_800_000_000_000)
        val monotonic = timer(
            TimerTarget.SameBootMonotonic("boot-one", 90_000_000_000),
            logicalWall = 1_700_000_060_000,
        )

        assertEquals(
            ResearchTime(0, 180_000_000_000, "active-running-time"),
            RuntimeEventFactory.timerLogicalTarget(active),
        )
        assertEquals(
            ResearchTime(1_800_000_000_000, 0, "calendar-time"),
            RuntimeEventFactory.timerLogicalTarget(calendar),
        )
        assertEquals(
            ResearchTime(1_700_000_060_000, 90_000_000_000, "boot-one"),
            RuntimeEventFactory.timerLogicalTarget(monotonic),
        )

        val early = ResearchTime(1_700_000_000_000, 30_000_000_000, "boot-one")
        val late = ResearchTime(1_700_000_030_000, 60_000_000_000, "boot-one")
        assertEquals(
            RuntimeEventFactory.timerScheduled(active, early).fields["logical_due_research_time"],
            RuntimeEventFactory.timerScheduled(active, late).fields["logical_due_research_time"],
        )
        assertEquals(
            RuntimeEventFactory.timerScheduled(monotonic, early).fields["logical_due_research_time"],
            RuntimeEventFactory.timerRetired(monotonic, "FIRED", late).fields["logical_due_research_time"],
        )
    }

    private fun timer(target: TimerTarget, logicalWall: Long?) = DurableTimer(
        id = "a".repeat(64),
        automationId = "timer-rule",
        generation = 1uL,
        causalSequence = 1,
        producerKey = "producer-key",
        target = target,
        logicalDeadlineUtcMillis = logicalWall,
        expiresAtUtcMillis = null,
    )
}
