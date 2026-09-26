package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.AutomationSchedule
import cool.jacoblin.particeps.core.definition.DurationClock
import cool.jacoblin.particeps.core.definition.OccurrenceAutomation
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface TimerTarget {
    data class CalendarUtc(val utcMillis: Long) : TimerTarget {
        init { require(utcMillis >= 0) { "Calendar target must be non-negative" } }
    }
    data class ActiveElapsed(val elapsedNanos: Long) : TimerTarget {
        init { require(elapsedNanos >= 0) { "Active target must be non-negative" } }
    }
    data class SameBootMonotonic(val bootSessionId: String, val elapsedRealtimeNanos: Long) : TimerTarget {
        init {
            require(bootSessionId.isNotBlank()) { "Boot session ID must not be blank" }
            require(elapsedRealtimeNanos >= 0) { "Monotonic target must be non-negative" }
        }
    }
}
data class DurableTimer(
    val id: String,
    val automationId: String,
    val generation: ULong,
    val causalSequence: Long,
    val producerKey: String,
    val target: TimerTarget,
    val logicalDeadlineUtcMillis: Long?,
    val expiresAtUtcMillis: Long?,
) {
    init {
        require(TIMER_ID.matches(id)) { "Invalid timer ID" }
        require(AUTOMATION_ID.matches(automationId)) { "Invalid timer automation ID" }
        require(generation > 0uL) { "Timer generation must be positive" }
        require(causalSequence > 0) { "Timer causal sequence must be positive" }
        require(producerKey.length in 1..160 && '\u0000' !in producerKey) { "Invalid timer producer key" }
        require(logicalDeadlineUtcMillis == null || logicalDeadlineUtcMillis >= 0) { "Invalid logical deadline" }
        require(expiresAtUtcMillis == null || expiresAtUtcMillis >= 0) { "Invalid timer expiry" }
        require(
            logicalDeadlineUtcMillis == null || expiresAtUtcMillis == null ||
                expiresAtUtcMillis >= logicalDeadlineUtcMillis,
        ) { "Timer expiry precedes its deadline" }
    }

    /**
     * This timer's component of the checkpoint digest preimage, kept once built: a timer is carried
     * unchanged through every checkpoint until it fires or is replaced. Not a constructor property,
     * so equality, hashing and `copy` are unchanged.
     */
    @Volatile
    internal var checkpointComponent: String? = null

    private companion object {
        val TIMER_ID = Regex("[0-9a-f]{64}")
        val AUTOMATION_ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
    }
}
sealed interface TimerIntent {
    data class Schedule(val timer: DurableTimer) : TimerIntent
    data class Retire(val timerId: String, val generation: ULong) : TimerIntent
}
data class MaterializedTimerSummary(val producerKey: String, val selectedUtcMillis: Long, val terminal: Boolean)
data class TimerProductionRequest(
    val configurationSha256: String,
    val automation: OccurrenceAutomation,
    val schedule: AutomationSchedule,
    val clock: ReducerClock,
    val studyStartUtcMillis: Long,
    val studyDeadlineUtcMillis: Long,
    val causalSequence: Long,
    val currentGeneration: ULong,
    val sessionState: StudySessionState,
    val pendingTimer: DurableTimer?,
    val materialized: List<MaterializedTimerSummary>,
) {
    init {
        require(studyStartUtcMillis >= 0 && studyDeadlineUtcMillis > studyStartUtcMillis) { "Invalid study interval" }
        require(causalSequence > 0) { "Timer production requires a causal sequence" }
        require(currentGeneration < ULong.MAX_VALUE) { "Timer generation overflow" }
        require(materialized.map { it.producerKey }.distinct().size == materialized.size) {
            "Duplicate materialized producer key"
        }
        require(pendingTimer == null || pendingTimer.automationId == automation.id) { "Pending timer automation mismatch" }
    }
}
sealed interface TimerProductionResult {
    data class Materialized(val timer: DurableTimer) : TimerProductionResult
    data object Deferred : TimerProductionResult
    data object Exhausted : TimerProductionResult
}
fun interface TimerProducer {
    fun produce(request: TimerProductionRequest): TimerProductionResult
}
fun interface BoundedRandomSource { fun nextInt(boundExclusive: Int): Int }
class SecureRandomSource(private val secureRandom: SecureRandom = SecureRandom()) : BoundedRandomSource {
    override fun nextInt(boundExclusive: Int): Int = secureRandom.nextInt(boundExclusive)
}
class StandardTimerProducer : TimerProducer {
    override fun produce(request: TimerProductionRequest): TimerProductionResult {
        request.pendingTimer?.let { return TimerProductionResult.Materialized(it) }
        if (request.sessionState != StudySessionState.RUNNING) return TimerProductionResult.Deferred
        val schedule = request.schedule
        require(schedule !is AutomationSchedule.RandomWindow) { "Random schedules require RandomWindowTimerProducer" }
        val produced = when (schedule) {
            is AutomationSchedule.OneTime -> produceOneTime(request, schedule)
            is AutomationSchedule.Interval -> produceInterval(request, schedule)
            is AutomationSchedule.DailyLocal -> produceDaily(request, schedule)
            is AutomationSchedule.RandomWindow -> error("Checked above")
        }
        return produced?.let(TimerProductionResult::Materialized) ?: TimerProductionResult.Exhausted
    }

    private fun produceOneTime(request: TimerProductionRequest, schedule: AutomationSchedule.OneTime): DurableTimer? {
        val producerKey = "one-time"
        if (request.materialized.any { it.producerKey == producerKey }) return null
        return when (schedule.clock) {
            DurationClock.CALENDAR_TIME -> {
                val deadline = checkedAddMillis(request.studyStartUtcMillis, schedule.offsetMinutes.toLong() * MILLIS_PER_MINUTE)
                if (!calendarDeadlineEligible(request, deadline)) null else calendarTimer(request, producerKey, deadline)
            }
            DurationClock.ACTIVE_RUNNING_TIME -> {
                val target = checkedMultiplyNanos(schedule.offsetMinutes.toLong(), SECONDS_PER_MINUTE)
                activeTimer(request, producerKey, target)
            }
        }
    }

    private fun produceInterval(request: TimerProductionRequest, schedule: AutomationSchedule.Interval): DurableTimer? {
        var ordinal = 0L
        val materializedKeys = request.materialized.mapTo(hashSetOf(), MaterializedTimerSummary::producerKey)
        while (ordinal < request.automation.maximumActivations.toLong()) {
            val producerKey = "interval:$ordinal"
            if (producerKey !in materializedKeys) {
                val offsetMinutes = addExact(
                    schedule.startOffsetMinutes.toLong(),
                    multiplyExact(ordinal, schedule.intervalMinutes.toLong()),
                )
                return when (schedule.clock) {
                    DurationClock.CALENDAR_TIME -> {
                        val deadline = checkedAddMillis(request.studyStartUtcMillis, multiplyExact(offsetMinutes, MILLIS_PER_MINUTE))
                        if (deadline >= request.studyDeadlineUtcMillis) null
                        else if (minimumExpiry(request, deadline) <= request.clock.now.wallTimeUtcMillis) {
                            ordinal++
                            continue
                        } else {
                            calendarTimer(request, producerKey, deadline)
                        }
                    }
                    DurationClock.ACTIVE_RUNNING_TIME -> {
                        val target = checkedMultiplyNanos(offsetMinutes, SECONDS_PER_MINUTE)
                        val studyDurationNanos = multiplyExact(
                            request.studyDeadlineUtcMillis - request.studyStartUtcMillis,
                            NANOS_PER_MILLI,
                        )
                        if (target >= studyDurationNanos) {
                            null
                        } else {
                            activeTimer(request, producerKey, target)
                        }
                    }
                }
            }
            ordinal++
        }
        return null
    }

    private fun produceDaily(request: TimerProductionRequest, schedule: AutomationSchedule.DailyLocal): DurableTimer? {
        val zone = ZoneId.of(request.clock.zoneId)
        val localTime = LocalTime.parse(schedule.localTime, LOCAL_TIME_FORMAT)
        val nowInstant = Instant.ofEpochMilli(request.clock.now.wallTimeUtcMillis)
        val lastInstant = Instant.ofEpochMilli(request.studyDeadlineUtcMillis - 1)
        var date = maxOf(
            Instant.ofEpochMilli(request.studyStartUtcMillis).atZone(zone).toLocalDate(),
            nowInstant.atZone(zone).toLocalDate(),
        )
        val endDate = lastInstant.atZone(zone).toLocalDate()
        val materializedKeys = request.materialized.mapTo(hashSetOf(), MaterializedTimerSummary::producerKey)
        while (!date.isAfter(endDate)) {
            val producerKey = "daily:$date"
            if (producerKey !in materializedKeys) {
                val instant = firstInstant(LocalDateTime.of(date, localTime), zone)
                if (instant != null) {
                    val deadline = instant.toEpochMilli()
                    val expiry = minimumExpiry(request, deadline)
                    if (deadline >= request.studyStartUtcMillis && deadline < request.studyDeadlineUtcMillis &&
                        expiry > request.clock.now.wallTimeUtcMillis
                    ) {
                        return calendarTimer(request, producerKey, deadline)
                    }
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun activeTimer(request: TimerProductionRequest, producerKey: String, targetNanos: Long): DurableTimer? {
        val studyDurationNanos = multiplyExact(
            request.studyDeadlineUtcMillis - request.studyStartUtcMillis,
            NANOS_PER_MILLI,
        )
        if (targetNanos < 0 || targetNanos >= studyDurationNanos) return null
        return DurableTimer(
            id = DeterministicIds.timerId(request.configurationSha256, request.automation.id, producerKey),
            automationId = request.automation.id,
            generation = request.currentGeneration + 1uL,
            causalSequence = request.causalSequence,
            producerKey = producerKey,
            target = TimerTarget.ActiveElapsed(targetNanos),
            logicalDeadlineUtcMillis = null,
            expiresAtUtcMillis = null,
        )
    }

    private fun calendarTimer(request: TimerProductionRequest, producerKey: String, deadline: Long): DurableTimer = DurableTimer(
        id = DeterministicIds.timerId(request.configurationSha256, request.automation.id, producerKey),
        automationId = request.automation.id,
        generation = request.currentGeneration + 1uL,
        causalSequence = request.causalSequence,
        producerKey = producerKey,
        target = TimerTarget.CalendarUtc(deadline),
        logicalDeadlineUtcMillis = deadline,
        expiresAtUtcMillis = minimumExpiry(request, deadline),
    )

    private fun calendarDeadlineEligible(request: TimerProductionRequest, deadline: Long): Boolean =
        deadline in request.studyStartUtcMillis until request.studyDeadlineUtcMillis &&
            minimumExpiry(request, deadline) > request.clock.now.wallTimeUtcMillis

    private companion object {
        val LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
class RandomWindowTimerProducer(private val random: BoundedRandomSource) : TimerProducer {
    override fun produce(request: TimerProductionRequest): TimerProductionResult {
        request.pendingTimer?.let { return TimerProductionResult.Materialized(it) }
        if (request.sessionState != StudySessionState.RUNNING) return TimerProductionResult.Deferred
        val schedule = request.schedule as? AutomationSchedule.RandomWindow
            ?: throw IllegalArgumentException("RandomWindowTimerProducer requires a random_window schedule")
        val candidates = RandomWindowEligibility.next(request, schedule) ?: return TimerProductionResult.Exhausted
        val selectedIndex = random.nextInt(candidates.utcMillis.size)
        require(selectedIndex in candidates.utcMillis.indices) { "Random source returned an out-of-range index" }
        return TimerProductionResult.Materialized(candidates.timer(request, candidates.utcMillis[selectedIndex]))
    }
}

/** Closed-world validation used by both the coordinator producer and reducer replay. */
object ScheduleTimerValidator {
    fun requireValid(request: TimerProductionRequest, timer: DurableTimer) {
        require(request.pendingTimer == null) { "A schedule timer is already pending" }
        when (val schedule = request.schedule) {
            is AutomationSchedule.RandomWindow -> {
                val candidates = RandomWindowEligibility.next(request, schedule)
                    ?: throw IllegalArgumentException("Random schedule is exhausted")
                require(timer.producerKey == candidates.producerKey) { "Random timer producer key is not next" }
                val selected = (timer.target as? TimerTarget.CalendarUtc)?.utcMillis
                    ?: throw IllegalArgumentException("Random timer must use a calendar target")
                require(selected in candidates.utcMillis) { "Random timer selection is outside the eligible set" }
                require(timer == candidates.timer(request, selected)) { "Random timer fields do not match its selection" }
            }
            else -> {
                val produced = StandardTimerProducer().produce(request)
                val expected = (produced as? TimerProductionResult.Materialized)?.timer
                    ?: throw IllegalArgumentException("Standard schedule is not eligible")
                require(timer == expected) { "Standard timer does not match the signed schedule" }
            }
        }
    }
}

private data class RandomWindowCandidates(val producerKey: String, val utcMillis: List<Long>) {
    init { require(utcMillis.isNotEmpty()) { "Random candidate set must not be empty" } }

    fun timer(request: TimerProductionRequest, selected: Long): DurableTimer = DurableTimer(
        id = DeterministicIds.timerId(request.configurationSha256, request.automation.id, producerKey),
        automationId = request.automation.id,
        generation = request.currentGeneration + 1uL,
        causalSequence = request.causalSequence,
        producerKey = producerKey,
        target = TimerTarget.CalendarUtc(selected),
        logicalDeadlineUtcMillis = selected,
        expiresAtUtcMillis = minimumExpiry(request, selected),
    )
}

private object RandomWindowEligibility {
    fun next(request: TimerProductionRequest, schedule: AutomationSchedule.RandomWindow): RandomWindowCandidates? {
        if (request.materialized.size >= schedule.maximumOccurrencesTotal) return null
        val zone = ZoneId.of(request.clock.zoneId)
        val nowMillis = request.clock.now.wallTimeUtcMillis
        val earliestDate = Instant.ofEpochMilli(request.studyStartUtcMillis).atZone(zone).toLocalDate()
        val latestDate = Instant.ofEpochMilli(request.studyDeadlineUtcMillis - 1).atZone(zone).toLocalDate()
        val materializedKeys = request.materialized.mapTo(hashSetOf(), MaterializedTimerSummary::producerKey)
        val chronologicalFloor = request.materialized.maxOfOrNull(MaterializedTimerSummary::selectedUtcMillis)
        val separationMillis = schedule.minimumSeparationMinutes.toLong() * MILLIS_PER_MINUTE
        var date = earliestDate
        while (!date.isAfter(latestDate)) {
            val datePrefix = "random:$date:"
            val remainingDaily = schedule.maximumOccurrencesPerDay -
                request.materialized.count { it.producerKey.startsWith(datePrefix) }
            if (remainingDaily > 0) {
                schedule.localWindows.forEachIndexed { windowIndex, window ->
                    val startMinute = parseMinute(window.startLocalTime)
                    val endMinute = parseMinute(window.endLocalTime)
                    for (ordinal in 0 until schedule.occurrencesPerWindow) {
                        val totalRemaining = schedule.maximumOccurrencesTotal - request.materialized.size
                        if (totalRemaining <= 0) return null
                        val producerKey = "random:$date:$windowIndex:$ordinal"
                        if (producerKey in materializedKeys) continue
                        val laterUnmaterialized = ((ordinal + 1) until schedule.occurrencesPerWindow).count { later ->
                            "random:$date:$windowIndex:$later" !in materializedKeys
                        }
                        val reservedLater = minOf(laterUnmaterialized, remainingDaily - 1, totalRemaining - 1)
                        val latestMinute = endMinute - 1 - reservedLater * schedule.minimumSeparationMinutes
                        if (latestMinute < startMinute) continue
                        val preceding = if (ordinal == 0) null else request.materialized.singleOrNull {
                            it.producerKey == "random:$date:$windowIndex:${ordinal - 1}"
                        }?.selectedUtcMillis
                        val eligible = ArrayList<Long>(latestMinute - startMinute + 1)
                        for (minute in startMinute..latestMinute) {
                            val local = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60))
                            val instant = firstInstant(local, zone) ?: continue
                            val candidate = instant.toEpochMilli()
                            if (candidate < request.studyStartUtcMillis || candidate < nowMillis ||
                                candidate >= request.studyDeadlineUtcMillis ||
                                (chronologicalFloor != null && candidate <= chronologicalFloor) ||
                                (preceding != null && candidate - preceding < separationMillis) ||
                                request.materialized.any {
                                    absoluteDifference(candidate, it.selectedUtcMillis) < separationMillis
                                }
                            ) continue
                            eligible += candidate
                        }
                        if (eligible.isNotEmpty()) return RandomWindowCandidates(producerKey, eligible)
                    }
                }
            }
            date = date.plusDays(1)
        }
        return null
    }
}

private fun minimumExpiry(request: TimerProductionRequest, deadlineUtcMillis: Long): Long = minOf(
    checkedAddMillis(deadlineUtcMillis, request.automation.availabilitySeconds.toLong() * MILLIS_PER_SECOND),
    request.studyDeadlineUtcMillis,
)

private fun firstInstant(localDateTime: LocalDateTime, zone: ZoneId): Instant? {
    val offsets = zone.rules.getValidOffsets(localDateTime)
    if (offsets.isEmpty()) return null
    return offsets.map { offset: ZoneOffset -> localDateTime.toInstant(offset) }.minOrNull()
}

private fun parseMinute(value: String): Int = value.substring(0, 2).toInt() * 60 + value.substring(3).toInt()

private fun absoluteDifference(left: Long, right: Long): Long = when {
    left >= right -> left - right
    right >= left -> right - left
    else -> Long.MAX_VALUE
}

private fun checkedAddMillis(left: Long, right: Long): Long = addExact(left, right)
private fun checkedMultiplyNanos(value: Long, secondsPerUnit: Long): Long =
    multiplyExact(multiplyExact(value, secondsPerUnit), NANOS_PER_SECOND)

private fun addExact(left: Long, right: Long): Long = Math.addExact(left, right)
private fun multiplyExact(left: Long, right: Long): Long = Math.multiplyExact(left, right)

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_DAY = 86_400L

internal data class StudyLocalWindowState(val active: Boolean, val nextBoundaryUtcMillis: Long?)

internal fun studyLocalWindow(
    condition: cool.jacoblin.particeps.core.definition.StateCondition.StudyLocalWindow,
    studyStartUtcMillis: Long?, nowUtcMillis: Long, zoneId: String,
): StudyLocalWindowState {
    if (studyStartUtcMillis == null) return StudyLocalWindowState(false, null)
    return studyLocalWindow(
        condition.firstDay,
        condition.lastDay,
        LocalTime.parse(condition.startLocalTime),
        LocalTime.parse(condition.endLocalTime),
        studyStartUtcMillis,
        nowUtcMillis,
        ZoneId.of(zoneId),
    )
}

/** [studyLocalWindow] with the signed local times already parsed and the zone resolved. */
internal fun studyLocalWindow(
    firstDay: Int,
    lastDay: Int,
    startTime: LocalTime,
    endTime: LocalTime,
    studyStartUtcMillis: Long?,
    nowUtcMillis: Long,
    zone: ZoneId,
): StudyLocalWindowState {
    if (studyStartUtcMillis == null) return StudyLocalWindowState(false, null)
    val startDate = Instant.ofEpochMilli(studyStartUtcMillis).atZone(zone).toLocalDate()
    val lastDate = startDate.plusDays(lastDay.toLong() - 1)
    var date = maxOf(startDate.plusDays(firstDay.toLong() - 1), Instant.ofEpochMilli(nowUtcMillis).atZone(zone).toLocalDate())
    while (date <= lastDate) {
        val start = firstInstant(date.atTime(startTime), zone)?.toEpochMilli()
        val end = firstInstant(date.atTime(endTime), zone)?.toEpochMilli()
        if (start != null && end != null && end > start && nowUtcMillis < end) {
            return StudyLocalWindowState(nowUtcMillis >= start, if (nowUtcMillis < start) start else end)
        }
        date = date.plusDays(1)
    }
    return StudyLocalWindowState(false, null)
}

/**
 * The windows of one study-local-window condition for one study start and zone, one per study date
 * from its first day through its last. A date keeps no window where either local time falls in a
 * gap or the window would not open before it closes. [state] walks these dates from the same first
 * date [studyLocalWindow] does, so it returns the same state for every instant while resolving each
 * date's instants once instead of for every input.
 */
internal class StudyLocalWindowTable(
    firstDay: Int,
    lastDay: Int,
    startTime: LocalTime,
    endTime: LocalTime,
    val studyStartUtcMillis: Long,
    val zone: ZoneId,
) {
    private val firstEpochDay: Long
    private val opens: LongArray
    private val closes: LongArray
    private val present: BooleanArray

    init {
        val startDate = Instant.ofEpochMilli(studyStartUtcMillis).atZone(zone).toLocalDate()
        val firstDate = startDate.plusDays(firstDay.toLong() - 1)
        val lastDate = startDate.plusDays(lastDay.toLong() - 1)
        firstEpochDay = firstDate.toEpochDay()
        val dates = maxOf(0L, lastDate.toEpochDay() - firstEpochDay + 1).toInt()
        opens = LongArray(dates)
        closes = LongArray(dates)
        present = BooleanArray(dates)
        for (index in 0 until dates) {
            val date = firstDate.plusDays(index.toLong())
            val start = firstInstant(date.atTime(startTime), zone)?.toEpochMilli()
            val end = firstInstant(date.atTime(endTime), zone)?.toEpochMilli()
            if (start != null && end != null && end > start) {
                opens[index] = start
                closes[index] = end
                present[index] = true
            }
        }
    }

    fun state(nowUtcMillis: Long): StudyLocalWindowState {
        // The local epoch day `atZone(zone).toLocalDate()` computes, without building the date-time.
        val instant = Instant.ofEpochMilli(nowUtcMillis)
        val today = Math.floorDiv(instant.epochSecond + zone.rules.getOffset(instant).totalSeconds, SECONDS_PER_DAY)
        var index = maxOf(0L, today - firstEpochDay)
        while (index < present.size) {
            val date = index.toInt()
            if (present[date] && nowUtcMillis < closes[date]) {
                val start = opens[date]
                return StudyLocalWindowState(nowUtcMillis >= start, if (nowUtcMillis < start) start else closes[date])
            }
            index++
        }
        return StudyLocalWindowState(false, null)
    }
}
