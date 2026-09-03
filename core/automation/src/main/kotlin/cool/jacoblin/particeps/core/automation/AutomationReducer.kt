package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.Aggregate
import cool.jacoblin.particeps.core.definition.DurationClock
import cool.jacoblin.particeps.core.definition.EvaluationClock
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.NumericComparison
import cool.jacoblin.particeps.core.definition.OccurrenceAutomation
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.Trigger
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import java.math.BigInteger
import java.time.ZoneId

enum class StudySessionState { READY, ACTIVATING, RUNNING, PAUSING, PAUSED, COMPLETED, WITHDRAWN }
data class ReducerClock(
    val now: ResearchTime,
    val activeElapsedNanos: Long,
    val calendarElapsedNanos: Long,
    val zoneId: String,
) {
    init {
        require(activeElapsedNanos >= 0) { "Active elapsed time must be non-negative" }
        require(calendarElapsedNanos >= activeElapsedNanos) { "Calendar elapsed time must cover active time" }
        ZoneId.of(zoneId)
    }
}
data class AutomationEvent(
    val sequenceNumber: Long,
    val key: EventTypeKey,
    val observedTime: ResearchTime,
    val primarySourceTime: ResearchTime?,
    val fields: Map<String, String>,
) {
    init {
        require(sequenceNumber > 0) { "Automation event sequence must be positive" }
        require(fields.size <= 32) { "Automation event has too many fields" }
        require(fields.keys.all(FIELD_NAME::matches)) { "Invalid automation event field" }
    }

    private companion object { val FIELD_NAME = Regex("[a-z][a-z0-9_]{0,63}") }
}

sealed interface ReducerInput { val sequenceNumber: Long; val clock: ReducerClock
    data class Event(override val sequenceNumber: Long, override val clock: ReducerClock, val event: AutomationEvent) : ReducerInput
    data class Lifecycle(override val sequenceNumber: Long, override val clock: ReducerClock, val state: StudySessionState) : ReducerInput
    data class TimerDue(
        override val sequenceNumber: Long,
        override val clock: ReducerClock,
        val timerId: String,
        val automationId: String,
        val generation: ULong,
        val causalSequence: Long,
        val target: TimerTarget,
        val logicalDue: ResearchTime,
    ) : ReducerInput
    data class TimerMaterialized(override val sequenceNumber: Long, override val clock: ReducerClock, val timer: DurableTimer) : ReducerInput
    data class QualityGap(
        override val sequenceNumber: Long,
        override val clock: ReducerClock,
        val sourceId: EventSourceId,
    ) : ReducerInput

    data class ClockDiscontinuity(
        override val sequenceNumber: Long,
        override val clock: ReducerClock,
        val restartResources: Set<ResourceKey>,
    ) : ReducerInput
}

data class WindowEntry(
    val sequenceNumber: Long,
    val timeNanos: Long,
    val bootSessionId: String,
    val numericValue: BigInteger,
)
data class SequencePartial(
    val nextStep: Int,
    val firstSequenceNumber: Long,
    val lastSequenceNumber: Long,
    val firstTimeNanos: Long,
    val bootSessionId: String,
)
data class CooldownMark(val activeElapsedNanos: Long, val calendarElapsedNanos: Long)
data class DesiredProfile(val generation: ResourceGeneration, val profileId: String?)
data class AutomationCheckpoint(
    val evaluatedThroughSequence: Long = 0,
    val lifecycle: StudySessionState = StudySessionState.READY,
    val studyStartUtcMillis: Long? = null,
    val lastActiveElapsedNanos: Long = 0,
    val lastCalendarElapsedNanos: Long = 0,
    val latchValues: Map<String, Boolean> = emptyMap(),
    val presenceKeys: Map<String, Set<String>> = emptyMap(),
    val heldSinceNanos: Map<String, Long> = emptyMap(),
    val priorConditionValues: Map<String, Boolean> = emptyMap(),
    val windows: Map<String, List<WindowEntry>> = emptyMap(),
    val sequences: Map<String, List<SequencePartial>> = emptyMap(),
    val activationCounts: Map<String, Int> = emptyMap(),
    val cooldownMarks: Map<String, CooldownMark> = emptyMap(),
    val desiredResources: Map<ResourceKey, DesiredProfile> = emptyMap(),
    val timers: Map<String, DurableTimer> = emptyMap(),
    val timerGenerations: Map<String, ULong> = emptyMap(),
    val materializedTimers: Map<String, List<MaterializedTimerSummary>> = emptyMap(),
) {
    init {
        require(evaluatedThroughSequence >= 0) { "Reducer cursor must be non-negative" }
        require(studyStartUtcMillis == null || studyStartUtcMillis >= 0) { "Study start must be non-negative" }
        require(lifecycle == StudySessionState.READY || studyStartUtcMillis != null) {
            "Started lifecycle state requires a durable study start"
        }
        require(lastActiveElapsedNanos >= 0 && lastCalendarElapsedNanos >= lastActiveElapsedNanos) {
            "Invalid reducer clocks"
        }
        require(latchValues.keys.all(::validStateKey)) { "Invalid latch state key" }
        require(presenceKeys.keys.all(::validStateKey) && presenceKeys.values.all { it.size <= 256 }) {
            "Invalid keyed-presence state"
        }
        require(heldSinceNanos.keys.all(::validStateKey) && heldSinceNanos.values.all { it >= 0 }) {
            "Invalid held-condition state"
        }
        require(priorConditionValues.keys.all(::validStateKey)) { "Invalid prior-condition state" }
        require(windows.keys.all(::validStateKey) && windows.values.all { entries ->
            entries.size <= 4_096 && entries.zipWithNext().all { (first, second) ->
                first.sequenceNumber < second.sequenceNumber
            } && entries.all { it.sequenceNumber > 0 && it.timeNanos >= 0 && it.bootSessionId.isNotBlank() }
        }) { "Invalid window state" }
        require(sequences.keys.all(::validStateKey) && sequences.values.all { partials ->
            partials.size <= 4_096 && partials.all {
                it.nextStep > 0 && it.firstSequenceNumber > 0 &&
                    it.lastSequenceNumber >= it.firstSequenceNumber && it.firstTimeNanos >= 0 &&
                    it.bootSessionId.isNotBlank()
            }
        }) { "Invalid sequence state" }
        require(activationCounts.keys.all(::validAutomationId) && activationCounts.values.all { it in 0..512 }) {
            "Invalid activation counts"
        }
        require(activationCounts.values.sumOf(Int::toLong) <= 512) { "Lifetime activation bound exceeded" }
        require(cooldownMarks.keys.all(::validAutomationId) && cooldownMarks.values.all {
            it.activeElapsedNanos >= 0 && it.calendarElapsedNanos >= it.activeElapsedNanos
        }) { "Invalid cooldown state" }
        require(timers.size <= 512 && timers.all { (id, timer) -> id == timer.id }) { "Invalid timer map" }
        require(timerGenerations.keys.all(::validStateKey) && timerGenerations.values.all { it > 0uL }) {
            "Invalid timer generations"
        }
        require(materializedTimers.keys.all(::validAutomationId) && materializedTimers.values.sumOf { it.size } <= 512) {
            "Invalid materialized timer state"
        }
        require(materializedTimers.values.all { summaries ->
            summaries.map { it.producerKey }.distinct().size == summaries.size && summaries.all {
                it.producerKey.length in 1..160 && it.selectedUtcMillis >= 0
            }
        }) { "Invalid materialized timer summaries" }
        require(CheckpointDigester.encodedBytes(this) <= MAX_CHECKPOINT_BYTES) {
            "Automation checkpoint exceeds 512 KiB"
        }
    }

    fun digest(): String = CheckpointDigester.digest(this)

    private companion object {
        const val MAX_CHECKPOINT_BYTES = 512 * 1_024
        val AUTOMATION_ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        fun validStateKey(value: String): Boolean = value.length in 1..512 && '\u0000' !in value
        fun validAutomationId(value: String): Boolean = AUTOMATION_ID.matches(value)
    }
}

data class ActionRequest(
    val actionId: String,
    val automationId: String,
    val interventionId: String,
    val causalIdentity: String,
    val logicalDeadlineUtcMillis: Long?,
    val expiresAtUtcMillis: Long,
)
enum class SuppressionReason { GUARD_FALSE, COOLDOWN, MAXIMUM_ACTIVATIONS, EXPIRED, STALE_TIMER }
data class AutomationAudit(val automationId: String, val matched: Boolean, val suppressionReason: SuppressionReason?, val causalIdentity: String)
data class ReductionResult(
    val checkpoint: AutomationCheckpoint,
    val actionRequests: List<ActionRequest>,
    val timerIntents: List<TimerIntent>,
    val timerProductionRequests: List<TimerProductionRequest>,
    val resourceChanges: Map<ResourceKey, DesiredProfile>,
    val audits: List<AutomationAudit>,
)

class AutomationReducer {
    fun reduceBatch(
        program: CompiledAutomationProgram,
        checkpoint: AutomationCheckpoint,
        inputs: List<ReducerInput>,
    ): ReductionResult {
        require(inputs.isNotEmpty()) { "Reducer batch must not be empty" }
        val mutable = MutableCheckpoint(checkpoint)
        val actions = mutableListOf<ActionRequest>()
        val audits = mutableListOf<AutomationAudit>()
        val timerIntents = mutableListOf<TimerIntent>()

        inputs.forEachIndexed { index, input ->
            val expectedSequence = Math.addExact(checkpoint.evaluatedThroughSequence, index.toLong() + 1L)
            require(input.sequenceNumber == expectedSequence) { "Reducer input sequences must be contiguous" }
            mutable.beginInput(input)
            val dueResolution = when (input) {
                is ReducerInput.Event -> {
                    require(input.event.sequenceNumber == input.sequenceNumber) { "Nested event sequence mismatch" }
                    DueResolution.None
                }
                is ReducerInput.Lifecycle -> {
                    mutable.applyLifecycle(input.state, input.clock, timerIntents)
                    DueResolution.None
                }
                is ReducerInput.TimerDue -> mutable.acceptDueTimer(input, timerIntents)
                is ReducerInput.TimerMaterialized -> {
                    mutable.materializeTimer(program, input, timerIntents)
                    DueResolution.None
                }
                is ReducerInput.QualityGap -> {
                    mutable.resetSessionState(timerIntents)
                    DueResolution.None
                }
                is ReducerInput.ClockDiscontinuity -> {
                    mutable.resetSessionState(timerIntents)
                    mutable.resetCalendarState(timerIntents)
                    mutable.restartResources(program, input.restartResources)
                    DueResolution.None
                }
            }
            val dueTimer = (dueResolution as? DueResolution.Accepted)?.timer
            if (dueResolution == DueResolution.Stale) {
                val dueInput = input as ReducerInput.TimerDue
                program.occurrenceAutomations.singleOrNull {
                    it.id == dueInput.automationId && it.trigger is Trigger.Schedule
                }?.let {
                    audits += AutomationAudit(
                        automationId = it.id,
                        matched = false,
                        suppressionReason = SuppressionReason.STALE_TIMER,
                        causalIdentity = "timer:${dueInput.timerId}",
                    )
                }
            }

            program.occurrenceAutomations.forEach { automation ->
                val rootPath = "occurrence:${automation.id}"
                val guardValue = automation.guard?.let {
                    mutable.evaluateCondition(program, it, "$rootPath:guard", input, timerIntents, automation.id)
                } ?: true
                val matches = if (
                    mutable.lifecycle == StudySessionState.RUNNING &&
                    input !is ReducerInput.QualityGap &&
                    input !is ReducerInput.ClockDiscontinuity
                ) {
                    mutable.evaluateTrigger(program, automation, rootPath, input, dueTimer, timerIntents)
                } else {
                    emptyList()
                }
                matches.forEach { match ->
                    val outcome = mutable.requestAction(program, automation, match, guardValue, input.clock)
                    audits += outcome.audit
                    outcome.request?.let(actions::add)
                }
            }

            program.resourceBindings.forEach { binding ->
                binding.cases.forEachIndexed { caseIndex, case ->
                    val path = "binding:${binding.id}:case:$caseIndex"
                    val value = mutable.evaluateCondition(
                        program,
                        case.condition,
                        path,
                        input,
                        timerIntents,
                        binding.id,
                    )
                    mutable.rememberConditionResult(path, value)
                }
            }
            mutable.finishInput(input)
        }

        val finalInput = inputs.last()
        // SourceObservation/EngineCommit is the atomic semantic boundary. Conditions consume
        // every ordered input, but desired generations are allocated once from the final state so
        // a delayed enter+exit observation cannot create an unapplied transient generation.
        val resourceChanges = mutable.reconcileResources(program)
        val productionRequests = mutable.timerProductionRequests(program, finalInput.clock)
        val resultCheckpoint = mutable.freeze()
        require(resultCheckpoint.timers.size <= MAX_TIMERS) { "AUTOMATION_ENGINE_FAILURE: timer bound exceeded" }
        require(resultCheckpoint.activationCounts.values.sumOf(Int::toLong) <= MAX_ACTIVATIONS) {
            "AUTOMATION_ENGINE_FAILURE: activation bound exceeded"
        }
        return ReductionResult(
            checkpoint = resultCheckpoint,
            actionRequests = actions,
            timerIntents = timerIntents.distinct().sortedWith(TIMER_INTENT_ORDER),
            timerProductionRequests = productionRequests,
            resourceChanges = resourceChanges,
            audits = audits,
        )
    }

    private companion object {
        const val MAX_TIMERS = 512
        const val MAX_ACTIVATIONS = 512L
        val TIMER_INTENT_ORDER = compareBy<TimerIntent>(
            { when (it) { is TimerIntent.Retire -> it.timerId; is TimerIntent.Schedule -> it.timer.id } },
            { if (it is TimerIntent.Retire) 0 else 1 },
        )
    }
}

private data class TriggerMatch(
    val causalIdentity: String,
    val logicalTime: ResearchTime,
    val logicalDeadlineUtcMillis: Long?,
    val triggerKind: String,
)

private data class ActionOutcome(val request: ActionRequest?, val audit: AutomationAudit)

private sealed interface DueResolution {
    data object None : DueResolution
    data object Deferred : DueResolution
    data object Stale : DueResolution
    data class Accepted(val timer: DurableTimer) : DueResolution
}

private class MutableCheckpoint(checkpoint: AutomationCheckpoint) {
    var evaluatedThroughSequence = checkpoint.evaluatedThroughSequence
    var lifecycle = checkpoint.lifecycle
    var studyStartUtcMillis = checkpoint.studyStartUtcMillis
    var lastActiveElapsedNanos = checkpoint.lastActiveElapsedNanos
    var lastCalendarElapsedNanos = checkpoint.lastCalendarElapsedNanos
    private val latchValues = checkpoint.latchValues.toMutableMap()
    private val presenceKeys = checkpoint.presenceKeys.mapValuesTo(mutableMapOf()) { it.value.toMutableSet() }
    private val heldSinceNanos = checkpoint.heldSinceNanos.toMutableMap()
    private val priorConditionValues = checkpoint.priorConditionValues.toMutableMap()
    private val windows = checkpoint.windows.mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
    private val sequences = checkpoint.sequences.mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
    private val activationCounts = checkpoint.activationCounts.toMutableMap()
    private val cooldownMarks = checkpoint.cooldownMarks.toMutableMap()
    private val desiredResources = checkpoint.desiredResources.toMutableMap()
    private val timers = checkpoint.timers.toMutableMap()
    private val timerGenerations = checkpoint.timerGenerations.toMutableMap()
    private val materializedTimers = checkpoint.materializedTimers.mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
    private val forcedResourceRestarts = mutableSetOf<ResourceKey>()
    private val latestConditionResults = mutableMapOf<String, Boolean>()
    private var currentClock: ReducerClock? = null
    private var currentInputSequence: Long = 0

    fun beginInput(input: ReducerInput) {
        require(input.clock.activeElapsedNanos >= lastActiveElapsedNanos) { "Active clock moved backward" }
        require(input.clock.calendarElapsedNanos >= lastCalendarElapsedNanos) { "Calendar clock moved backward" }
        currentClock = input.clock
        currentInputSequence = input.sequenceNumber
        latestConditionResults.clear()
    }

    fun finishInput(input: ReducerInput) {
        evaluatedThroughSequence = input.sequenceNumber
        lastActiveElapsedNanos = input.clock.activeElapsedNanos
        lastCalendarElapsedNanos = input.clock.calendarElapsedNanos
    }

    fun applyLifecycle(state: StudySessionState, clock: ReducerClock, timerIntents: MutableList<TimerIntent>) {
        require(state == lifecycle || state in allowedDestinations(lifecycle)) {
            "Invalid lifecycle transition $lifecycle -> $state"
        }
        lifecycle = state
        if (state == StudySessionState.ACTIVATING && studyStartUtcMillis == null) {
            studyStartUtcMillis = clock.now.wallTimeUtcMillis
        }
        if (state in SESSION_RESET_STATES) resetSessionState(timerIntents)
    }

    fun resetSessionState(timerIntents: MutableList<TimerIntent>) {
        latchValues.clear()
        presenceKeys.clear()
        heldSinceNanos.clear()
        priorConditionValues.clear()
        windows.clear()
        sequences.clear()
        timers.values.filter { it.producerKey.startsWith(CONDITION_TIMER_PREFIX) }.forEach { timer ->
            timerIntents += TimerIntent.Retire(timer.id, timer.generation)
            timers.remove(timer.id)
        }
    }

    fun resetCalendarState(timerIntents: MutableList<TimerIntent>) {
        val retired = timers.values.filter { it.target is TimerTarget.CalendarUtc }
        retired.forEach { timer ->
            timerIntents += TimerIntent.Retire(timer.id, timer.generation)
            timers.remove(timer.id)
        }
        val retiredProducerKeys = retired.mapTo(mutableSetOf(), DurableTimer::producerKey)
        materializedTimers.replaceAll { _, summaries ->
            summaries.filterNot { it.producerKey in retiredProducerKeys }.toMutableList()
        }
        materializedTimers.entries.removeIf { it.value.isEmpty() }
    }

    fun restartResources(program: CompiledAutomationProgram, resources: Set<ResourceKey>) {
        val declared = program.input.resources.mapTo(hashSetOf()) { it.key }
        require(resources.all(declared::contains)) { "Clock discontinuity references an undeclared resource" }
        forcedResourceRestarts += resources
    }

    fun acceptDueTimer(input: ReducerInput.TimerDue, timerIntents: MutableList<TimerIntent>): DueResolution {
        val timer = timers[input.timerId]
        if (timer == null || timer.generation != input.generation) {
            timerIntents += TimerIntent.Retire(input.timerId, input.generation)
            return DueResolution.Stale
        }
        require(timer.automationId == input.automationId) { "Timer automation mismatch" }
        require(timer.causalSequence == input.causalSequence) { "Timer causal sequence mismatch" }
        require(timer.target == input.target) { "Timer target mismatch" }
        require(input.logicalDue == timer.auditCoordinate()) { "Timer logical target mismatch" }
        if (lifecycle != StudySessionState.RUNNING) return DueResolution.Deferred
        require(isDue(timer.target, input.clock)) { "Timer due input precedes its durable target" }
        timers.remove(timer.id)
        timerIntents += TimerIntent.Retire(timer.id, timer.generation)
        materializedTimers[timer.automationId]?.replaceAll { summary ->
            if (summary.producerKey == timer.producerKey) summary.copy(terminal = true) else summary
        }
        return DueResolution.Accepted(timer)
    }

    fun materializeTimer(
        program: CompiledAutomationProgram,
        input: ReducerInput.TimerMaterialized,
        timerIntents: MutableList<TimerIntent>,
    ) {
        require(lifecycle == StudySessionState.RUNNING) { "Timers may be materialized only while RUNNING" }
        val timer = input.timer
        val automation = program.occurrenceAutomations.singleOrNull { it.id == timer.automationId }
            ?: throw IllegalArgumentException("Timer references a non-schedule automation")
        val scheduleTrigger = automation.trigger as? Trigger.Schedule
            ?: throw IllegalArgumentException("Timer references a non-schedule trigger")
        require(timer.id == DeterministicIds.timerId(program.input.configurationSha256, timer.automationId, timer.producerKey)) {
            "Timer identity mismatch"
        }
        val existing = timers[timer.id]
        if (existing != null) {
            require(existing == timer) { "Conflicting timer materialization" }
            return
        }
        val expectedGeneration = (timerGenerations[timer.automationId] ?: 0uL) + 1uL
        require(timer.generation == expectedGeneration) { "Stale timer generation" }
        require(materializedTimers[timer.automationId].orEmpty().none { it.producerKey == timer.producerKey }) {
            "Timer producer key was already materialized"
        }
        require(timer.causalSequence <= evaluatedThroughSequence) {
            "Timer causal sequence has not been evaluated"
        }
        val start = requireNotNull(studyStartUtcMillis) { "Timer materialization requires a study start" }
        val deadline = Math.addExact(start, Math.multiplyExact(program.input.studyDurationSeconds, 1_000L))
        ScheduleTimerValidator.requireValid(
            TimerProductionRequest(
                configurationSha256 = program.input.configurationSha256,
                automation = automation,
                schedule = scheduleTrigger.schedule,
                clock = input.clock,
                studyStartUtcMillis = start,
                studyDeadlineUtcMillis = deadline,
                causalSequence = timer.causalSequence,
                currentGeneration = timerGenerations[timer.automationId] ?: 0uL,
                sessionState = lifecycle,
                pendingTimer = null,
                materialized = materializedTimers[timer.automationId].orEmpty().toList(),
            ),
            timer,
        )
        timers[timer.id] = timer
        timerGenerations[timer.automationId] = timer.generation
        materializedTimers.getOrPut(timer.automationId, ::mutableListOf) += MaterializedTimerSummary(
            producerKey = timer.producerKey,
            selectedUtcMillis = timer.logicalDeadlineUtcMillis ?: 0L,
            terminal = false,
        )
        timerIntents += TimerIntent.Schedule(timer)
    }

    fun evaluateTrigger(
        program: CompiledAutomationProgram,
        automation: OccurrenceAutomation,
        rootPath: String,
        input: ReducerInput,
        dueTimer: DurableTimer?,
        timerIntents: MutableList<TimerIntent>,
    ): List<TriggerMatch> = when (val trigger = automation.trigger) {
        is Trigger.EventMatch -> {
            val event = (input as? ReducerInput.Event)?.event
            if (event != null && matches(program, trigger.selector, event)) {
                listOf(eventMatch(event, trigger.evaluationClock, "event_match"))
            } else emptyList()
        }
        is Trigger.Sequence -> processSequence(program, automation.id, trigger, input)
        is Trigger.WindowThreshold -> {
            val condition = updateWindow(
                program,
                "$rootPath:trigger:window",
                trigger.selector,
                trigger.windowSeconds,
                trigger.evaluationClock,
                trigger.aggregate,
                trigger.comparison,
                input,
                timerIntents,
                automation.id,
            )
            val previous = priorConditionValues.put("$rootPath:trigger:window-edge", condition) ?: false
            if (!previous && condition) {
                val event = (input as? ReducerInput.Event)?.event
                val entries = windows["$rootPath:trigger:window"].orEmpty()
                if (event == null || entries.isEmpty()) emptyList() else listOf(
                    windowMatch(event, trigger.evaluationClock, entries.first().sequenceNumber),
                )
            } else emptyList()
        }
        is Trigger.ConditionRisingEdge -> {
            val value = evaluateCondition(
                program,
                trigger.condition,
                "$rootPath:trigger:condition",
                input,
                timerIntents,
                automation.id,
            )
            val previous = priorConditionValues.put("$rootPath:trigger:condition-edge", value) ?: false
            if (!previous && value) listOf(conditionMatch(input, dueTimer)) else emptyList()
        }
        is Trigger.Schedule -> if (dueTimer?.automationId == automation.id && !dueTimer.producerKey.startsWith(CONDITION_TIMER_PREFIX)) {
            listOf(timerMatch(input, dueTimer))
        } else emptyList()
    }

    fun rememberConditionResult(path: String, value: Boolean) {
        latestConditionResults[path] = value
    }

    fun evaluateCondition(
        program: CompiledAutomationProgram,
        condition: StateCondition,
        path: String,
        input: ReducerInput,
        timerIntents: MutableList<TimerIntent>,
        automationId: String,
    ): Boolean = when (condition) {
        StateCondition.StudySessionActive -> lifecycle in ACTIVE_SESSION_STATES
        is StateCondition.EventLatch -> {
            val event = (input as? ReducerInput.Event)?.event
            if (event != null) {
                val reset = condition.resetWhen.any { matches(program, it, event) }
                val set = condition.setWhen.any { matches(program, it, event) }
                when {
                    reset -> latchValues[path] = false
                    set -> latchValues[path] = true
                }
            }
            latchValues[path] ?: false
        }
        is StateCondition.KeyedPresence -> {
            val keys = presenceKeys.getOrPut(path, ::mutableSetOf)
            val event = (input as? ReducerInput.Event)?.event
            if (event != null) {
                val key = event.fields[condition.keyField]
                val exits = condition.exitWhen.any { matches(program, it, event) }
                val enters = condition.enterWhen.any { matches(program, it, event) }
                if ((exits || enters) && key == null) throw IllegalStateException("AUTOMATION_ENGINE_FAILURE: presence key missing")
                when {
                    exits -> keys.remove(key)
                    enters -> {
                        if (keys.size >= MAX_PRESENCE_KEYS && key !in keys) {
                            throw IllegalStateException("AUTOMATION_ENGINE_FAILURE: presence key bound exceeded")
                        }
                        keys += requireNotNull(key)
                    }
                }
            }
            keys.isNotEmpty()
        }
        is StateCondition.HeldFor -> {
            val child = evaluateCondition(
                program,
                condition.condition,
                "$path:child",
                input,
                timerIntents,
                automationId,
            )
            val nowNanos = durationClockNanos(condition.clock, input.clock)
            if (!child) {
                heldSinceNanos.remove(path)
                retireConditionTimer(path, timerIntents)
                false
            } else {
                val since = heldSinceNanos.getOrPut(path) { nowNanos }
                val due = Math.addExact(since, secondsToNanos(condition.durationSeconds))
                if (nowNanos >= due) {
                    retireConditionTimer(path, timerIntents)
                    true
                } else {
                    ensureConditionTimer(program, automationId, path, condition.clock, due, timerIntents)
                    false
                }
            }
        }
        is StateCondition.ElapsedAtLeast -> {
            val nowNanos = durationClockNanos(condition.clock, input.clock)
            val due = secondsToNanos(condition.durationSeconds)
            if (nowNanos >= due) {
                retireConditionTimer(path, timerIntents)
                true
            } else {
                ensureConditionTimer(program, automationId, path, condition.clock, due, timerIntents)
                false
            }
        }
        is StateCondition.WindowThreshold -> updateWindow(
            program,
            path,
            condition.selector,
            condition.windowSeconds,
            condition.evaluationClock,
            condition.aggregate,
            condition.comparison,
            input,
            timerIntents,
            automationId,
        )
        is StateCondition.All -> condition.conditions.mapIndexed { index, child ->
            evaluateCondition(program, child, "$path:$index", input, timerIntents, automationId)
        }.all { it }
        is StateCondition.Any -> condition.conditions.mapIndexed { index, child ->
            evaluateCondition(program, child, "$path:$index", input, timerIntents, automationId)
        }.any { it }
        is StateCondition.Not -> !evaluateCondition(
            program,
            condition.condition,
            "$path:not",
            input,
            timerIntents,
            automationId,
        )
    }

    fun requestAction(
        program: CompiledAutomationProgram,
        automation: OccurrenceAutomation,
        match: TriggerMatch,
        guardValue: Boolean,
        clock: ReducerClock,
    ): ActionOutcome {
        val count = activationCounts[automation.id] ?: 0
        val suppression = when {
            count >= automation.maximumActivations -> SuppressionReason.MAXIMUM_ACTIVATIONS
            !guardValue -> SuppressionReason.GUARD_FALSE
            cooldownActive(automation, clock) -> SuppressionReason.COOLDOWN
            Math.addExact(match.logicalTime.wallTimeUtcMillis, automation.availabilitySeconds.toLong() * 1_000L) <=
                clock.now.wallTimeUtcMillis -> SuppressionReason.EXPIRED
            else -> null
        }
        if (suppression != null) {
            return ActionOutcome(
                null,
                AutomationAudit(automation.id, matched = true, suppressionReason = suppression, match.causalIdentity),
            )
        }
        val logicalDeadline = match.logicalDeadlineUtcMillis?.toString().orEmpty()
        val actionId = DeterministicIds.actionId(
            program.input.configurationSha256,
            automation.id,
            automation.interventionId,
            match.triggerKind,
            match.causalIdentity,
            logicalDeadline,
        )
        val studyDeadline = Math.addExact(
            requireNotNull(studyStartUtcMillis) { "RUNNING study has no start" },
            Math.multiplyExact(program.input.studyDurationSeconds, 1_000L),
        )
        val expiryBase = match.logicalTime.wallTimeUtcMillis
        val expires = minOf(
            Math.addExact(expiryBase, automation.availabilitySeconds.toLong() * 1_000L),
            studyDeadline,
        )
        activationCounts[automation.id] = count + 1
        cooldownMarks[automation.id] = CooldownMark(clock.activeElapsedNanos, clock.calendarElapsedNanos)
        return ActionOutcome(
            ActionRequest(
                actionId,
                automation.id,
                automation.interventionId,
                match.causalIdentity,
                match.logicalDeadlineUtcMillis,
                expires,
            ),
            AutomationAudit(automation.id, matched = true, suppressionReason = null, match.causalIdentity),
        )
    }

    fun reconcileResources(
        program: CompiledAutomationProgram,
    ): Map<ResourceKey, DesiredProfile> {
        val changes = linkedMapOf<ResourceKey, DesiredProfile>()
        program.resourceBindings.sortedBy { it.resource }.forEach { binding ->
            val selected = if (lifecycle in ACTIVE_SESSION_STATES) {
                val selectedCase = binding.cases.withIndex().firstOrNull { (index, case) ->
                    latestConditionResults.getValue("binding:${binding.id}:case:$index")
                }
                if (selectedCase == null) binding.defaultProfileId else selectedCase.value.profileId
            } else null
            val previous = desiredResources[binding.resource]
            val forceRestart = binding.resource in forcedResourceRestarts &&
                previous?.profileId != null && selected != null
            if (previous == null || previous.profileId != selected || forceRestart) {
                val generation = previous?.generation?.next() ?: ResourceGeneration(1uL)
                val desired = DesiredProfile(generation, selected)
                desiredResources[binding.resource] = desired
                changes[binding.resource] = desired
            }
        }
        return changes
    }

    fun timerProductionRequests(
        program: CompiledAutomationProgram,
        clock: ReducerClock,
    ): List<TimerProductionRequest> {
        if (lifecycle != StudySessionState.RUNNING) return emptyList()
        val start = studyStartUtcMillis ?: return emptyList()
        val deadline = Math.addExact(start, Math.multiplyExact(program.input.studyDurationSeconds, 1_000L))
        return program.occurrenceAutomations.mapNotNull { automation ->
            val schedule = (automation.trigger as? Trigger.Schedule)?.schedule ?: return@mapNotNull null
            if ((activationCounts[automation.id] ?: 0) >= automation.maximumActivations) return@mapNotNull null
            TimerProductionRequest(
                configurationSha256 = program.input.configurationSha256,
                automation = automation,
                schedule = schedule,
                clock = clock,
                studyStartUtcMillis = start,
                studyDeadlineUtcMillis = deadline,
                currentGeneration = timerGenerations[automation.id] ?: 0uL,
                causalSequence = evaluatedThroughSequence,
                sessionState = lifecycle,
                pendingTimer = timers.values.singleOrNull {
                    it.automationId == automation.id && !it.producerKey.startsWith(CONDITION_TIMER_PREFIX)
                },
                materialized = materializedTimers[automation.id].orEmpty().toList(),
            )
        }
    }

    fun freeze(): AutomationCheckpoint = AutomationCheckpoint(
        evaluatedThroughSequence = evaluatedThroughSequence,
        lifecycle = lifecycle,
        studyStartUtcMillis = studyStartUtcMillis,
        lastActiveElapsedNanos = lastActiveElapsedNanos,
        lastCalendarElapsedNanos = lastCalendarElapsedNanos,
        latchValues = latchValues.toSortedMap(),
        presenceKeys = presenceKeys.filterValues { it.isNotEmpty() }.toSortedMap().mapValues { it.value.toSortedSet() },
        heldSinceNanos = heldSinceNanos.toSortedMap(),
        priorConditionValues = priorConditionValues.toSortedMap(),
        windows = windows.filterValues { it.isNotEmpty() }.toSortedMap().mapValues { it.value.toList() },
        sequences = sequences.filterValues { it.isNotEmpty() }.toSortedMap().mapValues { it.value.toList() },
        activationCounts = activationCounts.toSortedMap(),
        cooldownMarks = cooldownMarks.toSortedMap(),
        desiredResources = desiredResources.toSortedMap(),
        timers = timers.toSortedMap(),
        timerGenerations = timerGenerations.toSortedMap(),
        materializedTimers = materializedTimers.toSortedMap().mapValues { it.value.toList() },
    )

    private fun processSequence(
        program: CompiledAutomationProgram,
        automationId: String,
        trigger: Trigger.Sequence,
        input: ReducerInput,
    ): List<TriggerMatch> {
        val event = (input as? ReducerInput.Event)?.event ?: return emptyList()
        val occurrenceTime = eventTime(event, trigger.evaluationClock)
        val path = "occurrence:$automationId:trigger:sequence"
        val retained = sequences.getOrPut(path, ::mutableListOf)
        val windowNanos = secondsToNanos(trigger.withinSeconds)
        val next = mutableListOf<SequencePartial>()
        val matches = mutableListOf<TriggerMatch>()
        retained.forEach { partial ->
            if (partial.bootSessionId != occurrenceTime.bootSessionId) return@forEach
            require(occurrenceTime.elapsedRealtimeNanos >= partial.firstTimeNanos) {
                "Sequence source time moved backward"
            }
            if (occurrenceTime.elapsedRealtimeNanos - partial.firstTimeNanos > windowNanos) return@forEach
            if (matches(program, trigger.steps[partial.nextStep], event)) {
                if (partial.nextStep == trigger.steps.lastIndex) {
                    matches += TriggerMatch(
                        causalIdentity = "range:${partial.firstSequenceNumber}:${event.sequenceNumber}",
                        logicalTime = occurrenceTime,
                        logicalDeadlineUtcMillis = null,
                        triggerKind = "sequence",
                    )
                } else {
                    next += partial.copy(nextStep = partial.nextStep + 1, lastSequenceNumber = event.sequenceNumber)
                }
            } else {
                next += partial
            }
        }
        if (matches(program, trigger.steps.first(), event)) {
            next += SequencePartial(
                nextStep = 1,
                firstSequenceNumber = event.sequenceNumber,
                lastSequenceNumber = event.sequenceNumber,
                firstTimeNanos = occurrenceTime.elapsedRealtimeNanos,
                bootSessionId = occurrenceTime.bootSessionId,
            )
        }
        if (next.size > MAX_SEQUENCE_ENTRIES) throw IllegalStateException("AUTOMATION_ENGINE_FAILURE: sequence bound exceeded")
        retained.clear()
        retained += next
        return matches
    }

    private fun updateWindow(
        program: CompiledAutomationProgram,
        path: String,
        selector: EventMatcher,
        windowSeconds: Int,
        evaluationClock: EvaluationClock,
        aggregate: Aggregate,
        comparison: NumericComparison,
        input: ReducerInput,
        timerIntents: MutableList<TimerIntent>,
        automationId: String,
    ): Boolean {
        val event = (input as? ReducerInput.Event)?.event
        val entries = windows.getOrPut(path, ::mutableListOf)
        val referenceTime = event?.let { eventTime(it, evaluationClock) } ?: input.clock.now
        val earliest = referenceTime.elapsedRealtimeNanos - secondsToNanos(windowSeconds)
        entries.removeAll { it.bootSessionId != referenceTime.bootSessionId || it.timeNanos <= earliest }
        if (event != null) {
            entries.lastOrNull { it.bootSessionId == referenceTime.bootSessionId }?.let { last ->
                require(referenceTime.elapsedRealtimeNanos >= last.timeNanos) { "Window source time moved backward" }
            }
            if (matches(program, selector, event)) {
                val value = when (aggregate) {
                    Aggregate.Count -> BigInteger.ONE
                    is Aggregate.Sum -> decodeIntegerField(program, event, aggregate.field)
                }
                entries += WindowEntry(
                    event.sequenceNumber,
                    referenceTime.elapsedRealtimeNanos,
                    referenceTime.bootSessionId,
                    value,
                )
                if (entries.size > MAX_WINDOW_ENTRIES) {
                    throw IllegalStateException("AUTOMATION_ENGINE_FAILURE: window bound exceeded")
                }
            }
        }
        entries.firstOrNull()?.let { first ->
            ensureConditionTimerTarget(
                program,
                automationId,
                path,
                TimerTarget.SameBootMonotonic(
                    first.bootSessionId,
                    Math.addExact(first.timeNanos, secondsToNanos(windowSeconds)),
                ),
                timerIntents,
            )
        } ?: retireConditionTimer(path, timerIntents)
        val aggregateValue = when (aggregate) {
            Aggregate.Count -> BigInteger.valueOf(entries.size.toLong())
            is Aggregate.Sum -> entries.fold(BigInteger.ZERO) { total, entry -> total + entry.numericValue }
        }
        return compareInteger(aggregateValue, comparison)
    }

    private fun matches(program: CompiledAutomationProgram, matcher: EventMatcher, event: AutomationEvent): Boolean {
        if (event.key != matcher.event) return false
        val contract = requireNotNull(program.contracts[matcher.event])
        return matcher.predicates.all { predicate ->
            val actualCanonical = event.fields[predicate.field] ?: return@all false
            val fieldContract = requireNotNull(contract.fields[predicate.field])
            val actual = TypedFieldDecoder.decodeEventWire(fieldContract, actualCanonical)
            if (predicate.operator == FieldOperator.IN) {
                predicate.values.orEmpty().any { expected ->
                    compareTyped(
                        actual,
                        TypedFieldDecoder.decodePredicateLiteral(fieldContract, expected),
                        FieldOperator.EQ,
                    )
                }
            } else {
                val expected = TypedFieldDecoder.decodePredicateLiteral(fieldContract, requireNotNull(predicate.value))
                compareTyped(actual, expected, predicate.operator)
            }
        }
    }

    private fun decodeIntegerField(program: CompiledAutomationProgram, event: AutomationEvent, field: String): BigInteger {
        val contract = requireNotNull(program.contracts[event.key]).fields[field]
            ?: throw IllegalStateException("Compiled sum field disappeared")
        return (TypedFieldDecoder.decodeEventWire(
            contract,
            requireNotNull(event.fields[field]) { "Sum field missing" },
        ) as TypedFieldValue.Integer).value
    }

    private fun cooldownActive(automation: OccurrenceAutomation, clock: ReducerClock): Boolean {
        val cooldown = automation.cooldown ?: return false
        val mark = cooldownMarks[automation.id] ?: return false
        val elapsed = when (cooldown.clock) {
            DurationClock.ACTIVE_RUNNING_TIME -> clock.activeElapsedNanos - mark.activeElapsedNanos
            DurationClock.CALENDAR_TIME -> clock.calendarElapsedNanos - mark.calendarElapsedNanos
        }
        return elapsed < secondsToNanos(cooldown.durationSeconds)
    }

    private fun ensureConditionTimer(
        program: CompiledAutomationProgram,
        automationId: String,
        path: String,
        clock: DurationClock,
        dueNanos: Long,
        timerIntents: MutableList<TimerIntent>,
    ) {
        if (lifecycle !in ACTIVE_SESSION_STATES) return
        val target = when (clock) {
            DurationClock.ACTIVE_RUNNING_TIME -> TimerTarget.ActiveElapsed(dueNanos)
            DurationClock.CALENDAR_TIME -> {
                val start = requireNotNull(studyStartUtcMillis) { "Active study has no start" }
                TimerTarget.CalendarUtc(Math.addExact(start, dueNanos / NANOS_PER_MILLI))
            }
        }
        ensureConditionTimerTarget(program, automationId, path, target, timerIntents)
    }

    private fun ensureConditionTimerTarget(
        program: CompiledAutomationProgram,
        automationId: String,
        path: String,
        target: TimerTarget,
        timerIntents: MutableList<TimerIntent>,
    ) {
        if (lifecycle !in ACTIVE_SESSION_STATES) return
        val producerKey = conditionProducerKey(path)
        val timerId = DeterministicIds.timerId(program.input.configurationSha256, automationId, producerKey)
        val existing = timers[timerId]
        if (existing?.target == target) return
        if (existing != null) {
            timers.remove(existing.id)
            timerIntents += TimerIntent.Retire(existing.id, existing.generation)
        }
        val generation = (timerGenerations[producerKey] ?: 0uL) + 1uL
        val timer = DurableTimer(
            id = timerId,
            automationId = automationId,
            generation = generation,
            causalSequence = currentInputSequence,
            producerKey = producerKey,
            target = target,
            logicalDeadlineUtcMillis = (target as? TimerTarget.CalendarUtc)?.utcMillis,
            expiresAtUtcMillis = null,
        )
        timers[timerId] = timer
        timerGenerations[producerKey] = generation
        timerIntents += TimerIntent.Schedule(timer)
    }

    private fun retireConditionTimer(path: String, timerIntents: MutableList<TimerIntent>) {
        val producerKey = conditionProducerKey(path)
        val timer = timers.values.singleOrNull { it.producerKey == producerKey } ?: return
        timers.remove(timer.id)
        timerIntents += TimerIntent.Retire(timer.id, timer.generation)
    }

    private fun eventMatch(event: AutomationEvent, clock: EvaluationClock, triggerKind: String): TriggerMatch = TriggerMatch(
        causalIdentity = "event:${event.sequenceNumber}",
        logicalTime = eventTime(event, clock),
        logicalDeadlineUtcMillis = null,
        triggerKind = triggerKind,
    )

    private fun windowMatch(event: AutomationEvent, clock: EvaluationClock, firstSequence: Long): TriggerMatch = TriggerMatch(
        causalIdentity = "range:$firstSequence:${event.sequenceNumber}",
        logicalTime = eventTime(event, clock),
        logicalDeadlineUtcMillis = null,
        triggerKind = "window_threshold",
    )

    private fun conditionMatch(input: ReducerInput, dueTimer: DurableTimer?): TriggerMatch {
        val timer = if (input is ReducerInput.TimerDue) dueTimer else null
        return if (timer != null) timerMatch(input, timer).copy(triggerKind = "condition_rising_edge")
        else TriggerMatch(
            causalIdentity = "event:${input.sequenceNumber}",
            logicalTime = input.clock.now,
            logicalDeadlineUtcMillis = null,
            triggerKind = "condition_rising_edge",
        )
    }

    private fun timerMatch(input: ReducerInput, timer: DurableTimer): TriggerMatch = TriggerMatch(
        causalIdentity = "timer:${timer.id}",
        logicalTime = if (input is ReducerInput.TimerDue && timer.logicalDeadlineUtcMillis != null) {
            input.logicalDue
        } else {
            input.clock.now
        },
        logicalDeadlineUtcMillis = timer.logicalDeadlineUtcMillis,
        triggerKind = "schedule",
    )

    private fun DurableTimer.auditCoordinate(): ResearchTime = when (val value = target) {
        is TimerTarget.CalendarUtc -> ResearchTime(value.utcMillis, 0, "calendar-time")
        is TimerTarget.ActiveElapsed -> ResearchTime(0, value.elapsedNanos, "active-running-time")
        is TimerTarget.SameBootMonotonic -> ResearchTime(
            logicalDeadlineUtcMillis ?: 0,
            value.elapsedRealtimeNanos,
            value.bootSessionId,
        )
    }

    private fun eventTime(event: AutomationEvent, clock: EvaluationClock): ResearchTime = when (clock) {
        EvaluationClock.OBSERVED_RESEARCH_TIME -> event.observedTime
        EvaluationClock.PRIMARY_SOURCE_TIME -> requireNotNull(event.primarySourceTime) {
            "PRIMARY_SOURCE_TIME event has no primary source time"
        }
    }

    private fun allowedDestinations(from: StudySessionState): Set<StudySessionState> = when (from) {
        StudySessionState.READY -> setOf(StudySessionState.ACTIVATING, StudySessionState.WITHDRAWN)
        StudySessionState.ACTIVATING -> setOf(StudySessionState.RUNNING, StudySessionState.PAUSING)
        StudySessionState.RUNNING -> setOf(
            StudySessionState.PAUSING,
            StudySessionState.COMPLETED,
            StudySessionState.WITHDRAWN,
        )
        StudySessionState.PAUSING -> setOf(StudySessionState.PAUSED, StudySessionState.COMPLETED, StudySessionState.WITHDRAWN)
        StudySessionState.PAUSED -> setOf(StudySessionState.ACTIVATING, StudySessionState.COMPLETED, StudySessionState.WITHDRAWN)
        StudySessionState.COMPLETED, StudySessionState.WITHDRAWN -> emptySet()
    }

    private fun isDue(target: TimerTarget, clock: ReducerClock): Boolean = when (target) {
        is TimerTarget.CalendarUtc -> clock.now.wallTimeUtcMillis >= target.utcMillis
        is TimerTarget.ActiveElapsed -> clock.activeElapsedNanos >= target.elapsedNanos
        is TimerTarget.SameBootMonotonic ->
            clock.now.bootSessionId == target.bootSessionId && clock.now.elapsedRealtimeNanos >= target.elapsedRealtimeNanos
    }

    private companion object {
        val ACTIVE_SESSION_STATES = setOf(StudySessionState.ACTIVATING, StudySessionState.RUNNING)
        val SESSION_RESET_STATES = setOf(
            StudySessionState.PAUSING,
            StudySessionState.PAUSED,
            StudySessionState.COMPLETED,
            StudySessionState.WITHDRAWN,
        )
        const val MAX_PRESENCE_KEYS = 256
        const val MAX_SEQUENCE_ENTRIES = 4_096
        const val MAX_WINDOW_ENTRIES = 4_096
        const val CONDITION_TIMER_PREFIX = "condition:"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

private object CheckpointDigester {
    fun digest(checkpoint: AutomationCheckpoint): String {
        val components = components(checkpoint)
        return DeterministicIds.digest("particeps-automation-checkpoint-v1", components)
    }

    fun encodedBytes(checkpoint: AutomationCheckpoint): Int {
        val components = components(checkpoint)
        return "particeps-automation-checkpoint-v1".toByteArray(Charsets.UTF_8).size +
            components.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
    }

    private fun components(checkpoint: AutomationCheckpoint): List<String> {
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
                components += "window:${escape(key)}:${entry.sequenceNumber}:${entry.timeNanos}:${escape(entry.bootSessionId)}:${entry.numericValue}"
            }
        }
        checkpoint.sequences.toSortedMap().forEach { (key, values) ->
            values.forEach { partial ->
                components += "sequence:${escape(key)}:${partial.nextStep}:${partial.firstSequenceNumber}:${partial.lastSequenceNumber}:${partial.firstTimeNanos}:${escape(partial.bootSessionId)}"
            }
        }
        checkpoint.activationCounts.toSortedMap().forEach { (key, value) -> components += "activation:${escape(key)}=$value" }
        checkpoint.cooldownMarks.toSortedMap().forEach { (key, value) ->
            components += "cooldown:${escape(key)}:${value.activeElapsedNanos}:${value.calendarElapsedNanos}"
        }
        checkpoint.desiredResources.toSortedMap().forEach { (key, value) ->
            components += "resource:${key.kind.name}:${escape(key.id)}:${value.generation}:${escape(value.profileId.orEmpty())}"
        }
        checkpoint.timers.toSortedMap().forEach { (_, timer) -> components += timerComponent(timer) }
        checkpoint.timerGenerations.toSortedMap().forEach { (key, value) -> components += "timer-generation:${escape(key)}:$value" }
        checkpoint.materializedTimers.toSortedMap().forEach { (key, values) ->
            values.forEach { timer ->
                components += "materialized:${escape(key)}:${escape(timer.producerKey)}:${timer.selectedUtcMillis}:${timer.terminal}"
            }
        }
        return components
    }

    private fun timerComponent(timer: DurableTimer): String = buildString {
        append("timer:").append(timer.id).append(':').append(escape(timer.automationId)).append(':')
        append(timer.generation).append(':').append(timer.causalSequence).append(':')
            .append(escape(timer.producerKey)).append(':')
        when (val target = timer.target) {
            is TimerTarget.CalendarUtc -> append("calendar:").append(target.utcMillis)
            is TimerTarget.ActiveElapsed -> append("active:").append(target.elapsedNanos)
            is TimerTarget.SameBootMonotonic -> append("monotonic:").append(escape(target.bootSessionId)).append(':')
                .append(target.elapsedRealtimeNanos)
        }
        append(':').append(timer.logicalDeadlineUtcMillis ?: "").append(':').append(timer.expiresAtUtcMillis ?: "")
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '%' -> append("%25")
                '\u0000' -> append("%00")
                ':' -> append("%3a")
                '=' -> append("%3d")
                else -> append(character)
            }
        }
    }
}

private fun compareTyped(actual: TypedFieldValue, expected: TypedFieldValue, operator: FieldOperator): Boolean {
    val comparison = actual.compareTo(expected)
    return when (operator) {
        FieldOperator.EQ -> comparison == 0
        FieldOperator.NE -> comparison != 0
        FieldOperator.LT -> comparison < 0
        FieldOperator.LTE -> comparison <= 0
        FieldOperator.GT -> comparison > 0
        FieldOperator.GTE -> comparison >= 0
        FieldOperator.IN -> error("in is evaluated separately")
    }
}

private fun compareInteger(actual: BigInteger, comparison: NumericComparison): Boolean {
    val expected = comparison.value.toBigInteger()
    return when (comparison.operator) {
        FieldOperator.EQ -> actual == expected
        FieldOperator.NE -> actual != expected
        FieldOperator.LT -> actual < expected
        FieldOperator.LTE -> actual <= expected
        FieldOperator.GT -> actual > expected
        FieldOperator.GTE -> actual >= expected
        FieldOperator.IN -> error("Invalid compiled numeric comparison")
    }
}

private fun durationClockNanos(clock: DurationClock, input: ReducerClock): Long = when (clock) {
    DurationClock.ACTIVE_RUNNING_TIME -> input.activeElapsedNanos
    DurationClock.CALENDAR_TIME -> input.calendarElapsedNanos
}

private fun secondsToNanos(seconds: Int): Long = Math.multiplyExact(seconds.toLong(), 1_000_000_000L)

private fun conditionProducerKey(path: String): String =
    "condition:" + DeterministicIds.digest("particeps-condition-timer-key-v1", listOf(path)).take(40)
