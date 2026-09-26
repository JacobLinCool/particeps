package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyTimelineAdvance

/**
 * The study clock rules: how a commit advances, starts or recovers the durable clock checkpoint.
 * Extracted from [ExperimentRuntime]; not a concurrent actor. Every member runs on the caller's
 * coroutine while the caller holds the runtime mutex, and never suspends. It reads runtime state
 * through [RuntimeMemory] at use time and never retains it.
 */
internal class RuntimeClockPolicy(private val ctx: RuntimeContext) {
    fun advanceClock(current: RuntimeDocument, now: ResearchTime): StudyClockCheckpoint {
        val old = current.clockCheckpoint ?: return initialClock(now)
        return when (val advanced = ctx.timeline.advance(old, current.state, now, ctx.clocks.trustedUtcMillis())) {
            is StudyTimelineAdvance.Advanced -> advanced.checkpoint
            StudyTimelineAdvance.TrustedUtcRequired -> throw ClockDiscontinuity()
        }
    }

    fun initialClock(now: ResearchTime): StudyClockCheckpoint =
        ctx.timeline.startedAt(now, ctx.clocks.trustedUtcMillis(), ctx.initialZoneId)

    /**
     * Advances only the calendar lifetime during fail-closed recovery. The interval after the last
     * authenticated checkpoint was never verified RUNNING time, so it cannot advance active-time
     * automations. An untrusted cross-boot interval has no defensible coordinate at all: retain the
     * last reliable anchor so Resume still requires an explicit trusted re-anchor.
     */
    fun recoveryClock(current: RuntimeDocument, now: ResearchTime): StudyClockCheckpoint {
        val checkpoint = current.clockCheckpoint
            ?: return initialClock(now).copy(deadlineUtcTrusted = false)
        return when (
            val advanced = ctx.timeline.advance(
                checkpoint,
                ExperimentState.PAUSED,
                now,
                ctx.clocks.trustedUtcMillis(),
            )
        ) {
            is StudyTimelineAdvance.Advanced -> advanced.checkpoint
            StudyTimelineAdvance.TrustedUtcRequired -> checkpoint
        }
    }

    fun advanceClockForTerminal(current: RuntimeDocument, now: ResearchTime): StudyClockCheckpoint =
        runCatching { advanceClock(current, now) }.getOrElse { recoveryClock(current, now) }
}

/**
 * The signed study deadline timer: its identity, reconciliation, retirement and collection boundary.
 * Extracted from [ExperimentRuntime]; not a concurrent actor. Every member runs on the caller's
 * coroutine while the caller holds the runtime mutex, and never suspends. It reads runtime state
 * through [RuntimeMemory] at use time and never retains it.
 */
internal class StudyDeadlineTimers(private val ctx: RuntimeContext) {
    private val memory = ctx.memory

    fun reconcileStudyDeadlineTimer(
        clock: StudyClockCheckpoint,
        causalSequence: Long,
        replacementReason: String,
    ): DeadlineTimerUpdate {
        require(!ctx.timeline.isElapsed(clock)) { "Elapsed studies cannot schedule a deadline timer" }
        val target = ctx.timeline.sameBootDeadline(clock)
        val current = memory.studyDeadlineTimer
        if (current != null &&
            current.target == TimerTarget.SameBootMonotonic(target.bootSessionId, target.elapsedRealtimeNanos) &&
            current.logicalDeadlineUtcMillis == target.wallTimeUtcMillis
        ) {
            return DeadlineTimerUpdate.EMPTY
        }
        val timer = DurableTimer(
            id = studyDeadlineTimerId(),
            automationId = STUDY_DURATION_AUTOMATION_ID,
            generation = (current?.generation ?: 0uL) + 1uL,
            causalSequence = causalSequence.coerceAtLeast(1L),
            producerKey = STUDY_DEADLINE_PRODUCER_KEY,
            target = TimerTarget.SameBootMonotonic(target.bootSessionId, target.elapsedRealtimeNanos),
            logicalDeadlineUtcMillis = target.wallTimeUtcMillis,
            expiresAtUtcMillis = null,
        )
        return DeadlineTimerUpdate(
            events = buildList {
                current?.let { add(RuntimeEventFactory.timerRetired(it, replacementReason, clock.anchor)) }
                add(RuntimeEventFactory.timerScheduled(timer, clock.anchor))
            },
            mutations = listOf(upsertStudyDeadlineTimer(timer)),
            effects = PostCommitEffects(
                timerIntents = buildList {
                    current?.let { add(TimerIntent.Retire(it.id, it.generation)) }
                    add(TimerIntent.Schedule(timer))
                },
            ),
        )
    }

    fun retireStudyDeadlineTimer(now: ResearchTime, reason: String): DeadlineTimerUpdate {
        val timer = memory.studyDeadlineTimer ?: return DeadlineTimerUpdate.EMPTY
        return DeadlineTimerUpdate(
            events = listOf(RuntimeEventFactory.timerRetired(timer, reason, now)),
            mutations = listOf(removeStudyDeadlineTimer()),
            effects = PostCommitEffects(timerIntents = listOf(TimerIntent.Retire(timer.id, timer.generation))),
        )
    }

    fun requireStudyDeadlineTimer(timer: DurableTimer) {
        require(timer.id == studyDeadlineTimerId()) { "Study deadline timer ID mismatch" }
        require(timer.automationId == STUDY_DURATION_AUTOMATION_ID) { "Study deadline timer owner mismatch" }
        require(timer.producerKey == STUDY_DEADLINE_PRODUCER_KEY) { "Study deadline producer mismatch" }
        require(timer.target is TimerTarget.SameBootMonotonic) { "Study deadline must use a same-boot target" }
        require(timer.logicalDeadlineUtcMillis != null && timer.expiresAtUtcMillis == null) {
            "Study deadline timer has invalid wall-time evidence"
        }
    }

    fun studyDeadlineTimerId(): String = digest(
        "particeps-study-deadline-timer-v1",
        ctx.study.configurationSha256,
        STUDY_DURATION_AUTOMATION_ID,
        STUDY_DEADLINE_PRODUCER_KEY,
    )

    fun deadlineCollectionBoundary(timer: DurableTimer): ResearchTime {
        requireStudyDeadlineTimer(timer)
        val target = timer.target as TimerTarget.SameBootMonotonic
        require(target.elapsedRealtimeNanos > 0) { "Study deadline cannot precede the monotonic epoch" }
        return ResearchTime(
            wallTimeUtcMillis = requireNotNull(timer.logicalDeadlineUtcMillis),
            elapsedRealtimeNanos = target.elapsedRealtimeNanos - 1,
            bootSessionId = target.bootSessionId,
        )
    }

    fun upsertStudyDeadlineTimer(timer: DurableTimer) = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.STUDY_DEADLINE_TIMER, STUDY_DEADLINE_COMPONENT_ID),
        RuntimeMutationOperation.UPSERT,
        RuntimeComponentCodec.encodeTimer(timer.also(::requireStudyDeadlineTimer)),
    )

    fun removeStudyDeadlineTimer() = RuntimeMutation(
        RuntimeComponentKey(RuntimeComponentKind.STUDY_DEADLINE_TIMER, STUDY_DEADLINE_COMPONENT_ID),
        RuntimeMutationOperation.REMOVE,
        null,
    )
}
