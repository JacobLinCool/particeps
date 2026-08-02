package cool.linc.androiddatacollector.core.application

import cool.linc.androiddatacollector.core.definition.DailyLocalSchedule
import cool.linc.androiddatacollector.core.definition.IntervalSchedule
import cool.linc.androiddatacollector.core.definition.OneTimeSchedule
import cool.linc.androiddatacollector.core.definition.RelativeClock
import cool.linc.androiddatacollector.core.definition.StudyConfiguration
import cool.linc.androiddatacollector.core.model.ExperimentState
import cool.linc.androiddatacollector.core.model.InterventionOccurrence
import cool.linc.androiddatacollector.core.model.OccurrenceState
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.model.StudyMetadata
import cool.linc.androiddatacollector.core.model.TransitionReason
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Pure, deterministic planner. Android owns only the final best-effort WorkManager delay. */
class InterventionSchedulePlanner {
    fun next(
        configuration: StudyConfiguration,
        metadata: StudyMetadata,
        now: ResearchTime,
        zoneId: ZoneId,
        triggerId: String? = null,
    ): List<InterventionOccurrence> {
        if (metadata.state in TERMINAL_STATES) return emptyList()
        val firstStart = metadata.transitions.firstOrNull { it.reason == TransitionReason.PARTICIPANT_STARTED }?.time
            ?: return emptyList()
        val lifetimeEnd = now.wallTimeUtcMillis - elapsedMillis(firstStart, now) +
            configuration.durationHours * HOUR_MILLIS
        return configuration.interventions.flatMap { intervention ->
            intervention.triggers.filter { triggerId == null || it.id == triggerId }.mapNotNull { trigger ->
                val candidates = when (val schedule = trigger.schedule) {
                    is OneTimeSchedule -> sequenceOf(
                        relativeCandidate(schedule.offsetMinutes.toLong(), schedule.clock, metadata, firstStart, now) to
                            "relative:${schedule.offsetMinutes}",
                    )
                    is IntervalSchedule -> generateSequence(0) { it + 1 }
                        .map { index -> schedule.startOffsetMinutes + index.toLong() * schedule.intervalMinutes }
                        .takeWhile { it * MINUTE_MILLIS < configuration.durationHours * HOUR_MILLIS }
                        .map { target ->
                            relativeCandidate(target, schedule.clock, metadata, firstStart, now) to "relative:$target"
                        }
                    is DailyLocalSchedule -> dailyCandidates(firstStart, lifetimeEnd, schedule.localTime, zoneId)
                        .mapIndexed { index, scheduled -> scheduled to "daily:$index" }
                }
                candidates.takeWhile { it.first.wallTimeUtcMillis < lifetimeEnd }
                    .map { (scheduled, key) ->
                        val id = occurrenceId(configuration, intervention.id, trigger.id, key)
                        InterventionOccurrence(
                            occurrenceId = id,
                            interventionId = intervention.id,
                            triggerId = trigger.id,
                            scheduleKey = key,
                            scheduledFor = scheduled,
                            expiresAtUtcMillis = minOf(
                                scheduled.wallTimeUtcMillis + trigger.availabilityMinutes * MINUTE_MILLIS,
                                lifetimeEnd,
                            ),
                            state = OccurrenceState.SCHEDULED,
                        )
                    }
                    .firstOrNull { candidate ->
                        val existing = metadata.occurrences[candidate.occurrenceId]
                        existing == null || existing.state in PENDING_STATES
                    }
            }
        }
    }

    private fun relativeCandidate(
        offsetMinutes: Long,
        clock: RelativeClock,
        metadata: StudyMetadata,
        firstStart: ResearchTime,
        now: ResearchTime,
    ): ResearchTime {
        val offsetMillis = offsetMinutes * MINUTE_MILLIS
        if (clock == RelativeClock.CALENDAR_TIME) {
            val wall = now.wallTimeUtcMillis - elapsedMillis(firstStart, now) + offsetMillis
            return estimateResearchTime(wall, now)
        }
        val runningTransitions = metadata.transitions.filter {
            it.to == ExperimentState.RUNNING || it.from == ExperimentState.RUNNING
        }
        var accumulated = 0L
        var opened: ResearchTime? = null
        runningTransitions.forEach { transition ->
            if (transition.to == ExperimentState.RUNNING) opened = transition.time
            if (transition.from == ExperimentState.RUNNING) {
                val start = checkNotNull(opened)
                val span = elapsedMillis(start, transition.time)
                if (offsetMillis <= accumulated + span) {
                    val delta = offsetMillis - accumulated
                    return start.copy(
                        wallTimeUtcMillis = start.wallTimeUtcMillis + delta,
                        elapsedRealtimeNanos = start.elapsedRealtimeNanos + delta * 1_000_000,
                    )
                }
                accumulated += span
                opened = null
            }
        }
        val activeStart = opened
        val wall = if (activeStart != null) {
            val accrued = accumulated + elapsedMillis(activeStart, now)
            now.wallTimeUtcMillis + (offsetMillis - accrued).coerceAtLeast(0)
        } else {
            Long.MAX_VALUE
        }
        return if (wall == Long.MAX_VALUE) now.copy(wallTimeUtcMillis = wall) else estimateResearchTime(wall, now)
    }

    private fun dailyCandidates(
        firstStart: ResearchTime,
        lifetimeEnd: Long,
        localTime: String,
        zoneId: ZoneId,
    ): Sequence<ResearchTime> {
        val firstDate = Instant.ofEpochMilli(firstStart.wallTimeUtcMillis).atZone(zoneId).toLocalDate()
        val time = LocalTime.parse(localTime)
        return generateSequence(firstDate) { it.plusDays(1) }
            .map { date -> date.atTime(time).atZone(zoneId).toInstant().toEpochMilli() }
            .filter { it >= firstStart.wallTimeUtcMillis }
            .takeWhile { it < lifetimeEnd }
            .map { estimateResearchTime(it, firstStart) }
    }

    private fun estimateResearchTime(wallMillis: Long, reference: ResearchTime): ResearchTime {
        val deltaNanos = (wallMillis - reference.wallTimeUtcMillis).coerceAtLeast(0) * 1_000_000
        return ResearchTime(wallMillis, reference.elapsedRealtimeNanos + deltaNanos, reference.bootSessionId)
    }

    /** Uses the monotonic clock whenever both endpoints belong to one boot, so wall-clock edits do
     * not turn paused time into active study time. Across boots wall time is the only shared base. */
    private fun elapsedMillis(start: ResearchTime, end: ResearchTime): Long =
        if (start.bootSessionId == end.bootSessionId && end.elapsedRealtimeNanos >= start.elapsedRealtimeNanos) {
            (end.elapsedRealtimeNanos - start.elapsedRealtimeNanos) / 1_000_000
        } else {
            (end.wallTimeUtcMillis - start.wallTimeUtcMillis).coerceAtLeast(0)
        }

    private fun occurrenceId(
        configuration: StudyConfiguration,
        interventionId: String,
        triggerId: String,
        scheduleKey: String,
    ): String = MessageDigest.getInstance("SHA-256")
        .digest(
            listOf(configuration.experimentId, configuration.configurationId, interventionId, triggerId, scheduleKey)
                .joinToString("\u0000")
                .toByteArray(StandardCharsets.UTF_8),
        )
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60 * MINUTE_MILLIS
        val TERMINAL_STATES = setOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN)
        val PENDING_STATES = setOf(OccurrenceState.SCHEDULED, OccurrenceState.POSTING)
    }
}
