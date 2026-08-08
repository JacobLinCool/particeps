package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.definition.DailyLocalSchedule
import cool.jacoblin.particeps.core.definition.IntervalSchedule
import cool.jacoblin.particeps.core.definition.OneTimeSchedule
import cool.jacoblin.particeps.core.definition.RandomWindowSchedule
import cool.jacoblin.particeps.core.definition.RelativeClock
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.InterventionTrigger
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** Local planner; randomized times become durable occurrences before Android schedules work. */
class InterventionSchedulePlanner(
    private val randomIndex: (Int) -> Int = SecureRandom()::nextInt,
) {
    fun next(
        configuration: StudyConfiguration,
        metadata: StudyMetadata,
        now: ResearchTime,
        zoneId: ZoneId,
        triggerId: String? = null,
    ): List<InterventionOccurrence> {
        if (metadata.state != ExperimentState.RUNNING) return emptyList()
        val firstStart = metadata.transitions.firstOrNull { it.reason == TransitionReason.PARTICIPANT_STARTED }?.time
            ?: return emptyList()
        require(now.bootSessionId == firstStart.bootSessionId) {
            "Cannot plan active study work across an untrusted boot-time boundary"
        }
        require(now.elapsedRealtimeNanos >= firstStart.elapsedRealtimeNanos) {
            "Active study monotonic clock moved behind participant Start"
        }
        val effectiveStartWallUtcMillis = now.wallTimeUtcMillis - elapsedMillis(firstStart, now)
        val lifetimeEnd = effectiveStartWallUtcMillis +
            configuration.durationHours * HOUR_MILLIS
        return configuration.interventions.flatMap { intervention ->
            intervention.triggers.filter { triggerId == null || it.id == triggerId }.mapNotNull { trigger ->
                if (trigger.schedule is RandomWindowSchedule) {
                    return@mapNotNull nextRandomOccurrence(
                        configuration,
                        metadata,
                        intervention.id,
                        trigger,
                        effectiveStartWallUtcMillis,
                        lifetimeEnd,
                        now,
                        zoneId,
                    )
                }
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
                    is RandomWindowSchedule -> error("Handled above")
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

    private fun nextRandomOccurrence(
        configuration: StudyConfiguration,
        metadata: StudyMetadata,
        interventionId: String,
        trigger: InterventionTrigger,
        effectiveStartWallUtcMillis: Long,
        lifetimeEnd: Long,
        now: ResearchTime,
        zoneId: ZoneId,
    ): InterventionOccurrence? {
        val schedule = trigger.schedule as RandomWindowSchedule
        val existing = metadata.occurrences.values.filter {
            it.interventionId == interventionId && it.triggerId == trigger.id &&
                it.scheduleKey.startsWith(RANDOM_KEY_PREFIX)
        }
        existing.filter { it.state in PENDING_STATES }
            .minByOrNull { it.scheduledFor.wallTimeUtcMillis }
            ?.let { return it }
        if (existing.size >= schedule.maximumOccurrencesTotal) return null
        val materializedKeys = existing.mapTo(mutableSetOf()) { it.scheduleKey }
        // Exact keys prevent a repeated local date from duplicating work. The chronological floor
        // prevents wall-clock rollback from reopening elapsed time without treating local-date
        // ordering as chronology when the participant crosses the date line.
        val chronologicalFloor = existing.maxOfOrNull { it.scheduledFor.wallTimeUtcMillis }

        val firstDate = Instant.ofEpochMilli(effectiveStartWallUtcMillis).atZone(zoneId).toLocalDate()
        val finalDate = Instant.ofEpochMilli(lifetimeEnd - 1).atZone(zoneId).toLocalDate()
        var date = firstDate
        while (!date.isAfter(finalDate)) {
            val datePrefix = "$RANDOM_KEY_PREFIX$date:"
            val remainingDailyCapacity = schedule.maximumOccurrencesPerDay -
                existing.count { it.scheduleKey.startsWith(datePrefix) }
            val remainingTotalCapacity = schedule.maximumOccurrencesTotal - existing.size
            if (remainingDailyCapacity > 0 && remainingTotalCapacity > 0) {
                schedule.localWindows.forEachIndexed { windowIndex, window ->
                    repeat(schedule.occurrencesPerWindow) { ordinal ->
                        val key = "$RANDOM_KEY_PREFIX$date:$windowIndex:$ordinal"
                        if (key in materializedKeys) return@repeat
                        val previousKey = "$RANDOM_KEY_PREFIX$date:$windowIndex:${ordinal - 1}"
                        val notBefore = existing.firstOrNull { it.scheduleKey == previousKey }
                            ?.scheduledFor
                            ?.wallTimeUtcMillis
                            ?.plus(schedule.minimumSeparationMinutes * MINUTE_MILLIS)
                            ?: Long.MIN_VALUE
                        val remainingUnmaterializedInWindow =
                            (ordinal + 1 until schedule.occurrencesPerWindow).count { later ->
                                "$RANDOM_KEY_PREFIX$date:$windowIndex:$later" !in materializedKeys
                            }
                        val remainingInWindow = minOf(
                            remainingUnmaterializedInWindow,
                            remainingDailyCapacity - 1,
                            remainingTotalCapacity - 1,
                        )
                        val latestMinute = window.endMinute - 1 -
                            remainingInWindow * schedule.minimumSeparationMinutes
                        val eligible = (window.startMinute..latestMinute).mapNotNull { minute ->
                            val wallMillis = localMinuteInstant(date, minute, zoneId)
                                ?: return@mapNotNull null
                            if (
                                wallMillis < effectiveStartWallUtcMillis ||
                                wallMillis < now.wallTimeUtcMillis ||
                                (chronologicalFloor != null && wallMillis <= chronologicalFloor) ||
                                wallMillis < notBefore ||
                                wallMillis >= lifetimeEnd
                            ) return@mapNotNull null
                            val separated = existing.all { occurrence ->
                                abs(occurrence.scheduledFor.wallTimeUtcMillis - wallMillis) >=
                                    schedule.minimumSeparationMinutes * MINUTE_MILLIS
                            }
                            wallMillis.takeIf { separated }
                        }
                        if (eligible.isEmpty()) return@repeat
                        val wallMillis = eligible[randomIndex(eligible.size)]
                        val scheduled = estimateResearchTime(wallMillis, now)
                        return InterventionOccurrence(
                            occurrenceId = occurrenceId(configuration, interventionId, trigger.id, key),
                            interventionId = interventionId,
                            triggerId = trigger.id,
                            scheduleKey = key,
                            scheduledFor = scheduled,
                            expiresAtUtcMillis = minOf(
                                wallMillis + trigger.availabilityMinutes * MINUTE_MILLIS,
                                lifetimeEnd,
                            ),
                            state = OccurrenceState.SCHEDULED,
                        )
                    }
                }
            }
            date = date.plusDays(1)
        }
        return null
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
            .mapNotNull { date -> localMinuteInstant(date, time.hour * 60 + time.minute, zoneId) }
            .filter { it >= firstStart.wallTimeUtcMillis }
            .takeWhile { it < lifetimeEnd }
            .map { estimateResearchTime(it, firstStart) }
    }

    private fun estimateResearchTime(wallMillis: Long, reference: ResearchTime): ResearchTime {
        val deltaNanos = (wallMillis - reference.wallTimeUtcMillis).coerceAtLeast(0) * 1_000_000
        return ResearchTime(wallMillis, reference.elapsedRealtimeNanos + deltaNanos, reference.bootSessionId)
    }

    /** Both endpoints are proven to belong to one boot before this monotonic subtraction. */
    private fun elapsedMillis(start: ResearchTime, end: ResearchTime): Long =
        (end.elapsedRealtimeNanos - start.elapsedRealtimeNanos) / 1_000_000

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
        const val RANDOM_KEY_PREFIX = "random:"
        val PENDING_STATES = setOf(OccurrenceState.SCHEDULED, OccurrenceState.POSTING)
    }
}

/**
 * Resolves a signed local minute without silently moving it outside its window. Gap minutes do not
 * exist and are skipped. During an overlap, the first chronological occurrence is chosen.
 */
internal fun localMinuteInstant(date: LocalDate, minute: Int, zoneId: ZoneId): Long? {
    val local = date.atTime(LocalTime.of(minute / 60, minute % 60))
    return zoneId.rules.getValidOffsets(local)
        .minOfOrNull { offset -> local.atOffset(offset).toInstant().toEpochMilli() }
}
