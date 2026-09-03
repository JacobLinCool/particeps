package cool.jacoblin.particeps.core.definition

import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.resource.ResourceKey

enum class FieldOperator { EQ, NE, LT, LTE, GT, GTE, IN }
enum class EvaluationClock { OBSERVED_RESEARCH_TIME, PRIMARY_SOURCE_TIME }
enum class DurationClock { ACTIVE_RUNNING_TIME, CALENDAR_TIME }

data class FieldPredicate(
    val field: String,
    val operator: FieldOperator,
    val value: String? = null,
    val values: List<String>? = null,
)

data class EventMatcher(
    val event: EventTypeKey,
    val predicates: List<FieldPredicate> = emptyList(),
)

sealed interface Aggregate {
    data object Count : Aggregate
    data class Sum(val field: String) : Aggregate
}

data class NumericComparison(val operator: FieldOperator, val value: String)

sealed interface Trigger {
    data class EventMatch(val selector: EventMatcher, val evaluationClock: EvaluationClock) : Trigger
    data class Sequence(
        val steps: List<EventMatcher>,
        val withinSeconds: Int,
        val evaluationClock: EvaluationClock,
    ) : Trigger
    data class WindowThreshold(
        val selector: EventMatcher,
        val windowSeconds: Int,
        val evaluationClock: EvaluationClock,
        val aggregate: Aggregate,
        val comparison: NumericComparison,
    ) : Trigger
    data class ConditionRisingEdge(val condition: StateCondition) : Trigger
    data class Schedule(val schedule: AutomationSchedule) : Trigger
}

sealed interface StateCondition {
    data object StudySessionActive : StateCondition
    data class EventLatch(val setWhen: List<EventMatcher>, val resetWhen: List<EventMatcher>) : StateCondition
    data class KeyedPresence(
        val enterWhen: List<EventMatcher>,
        val exitWhen: List<EventMatcher>,
        val keyField: String,
    ) : StateCondition
    data class HeldFor(val condition: StateCondition, val durationSeconds: Int, val clock: DurationClock) : StateCondition
    data class ElapsedAtLeast(val durationSeconds: Int, val clock: DurationClock) : StateCondition
    data class WindowThreshold(
        val selector: EventMatcher,
        val windowSeconds: Int,
        val evaluationClock: EvaluationClock,
        val aggregate: Aggregate,
        val comparison: NumericComparison,
    ) : StateCondition
    data class All(val conditions: List<StateCondition>) : StateCondition
    data class Any(val conditions: List<StateCondition>) : StateCondition
    data class Not(val condition: StateCondition) : StateCondition
}

sealed interface AutomationSchedule {
    data class OneTime(val offsetMinutes: Int, val clock: DurationClock) : AutomationSchedule
    data class Interval(val startOffsetMinutes: Int, val intervalMinutes: Int, val clock: DurationClock) : AutomationSchedule
    data class DailyLocal(val localTime: String) : AutomationSchedule
    data class RandomWindow(
        val localWindows: List<LocalTimeWindow>,
        val occurrencesPerWindow: Int,
        val maximumOccurrencesPerDay: Int,
        val maximumOccurrencesTotal: Int,
        val minimumSeparationMinutes: Int,
    ) : AutomationSchedule
}

data class LocalTimeWindow(val startLocalTime: String, val endLocalTime: String)
data class Cooldown(val durationSeconds: Int, val clock: DurationClock)

sealed interface AutomationDefinition { val id: String }

data class OccurrenceAutomation(
    override val id: String,
    val trigger: Trigger,
    val guard: StateCondition?,
    val interventionId: String,
    val availabilitySeconds: Int,
    val cooldown: Cooldown?,
    val maximumActivations: Int,
) : AutomationDefinition

data class ResourceConditionCase(val condition: StateCondition, val profileId: String?)

data class ResourceBindingAutomation(
    override val id: String,
    val resource: ResourceKey,
    val cases: List<ResourceConditionCase>,
    val defaultProfileId: String?,
) : AutomationDefinition

data class DeclaredResource(
    val key: ResourceKey,
    val required: Boolean,
    val profileDigests: Map<String, String>,
)

data class InterventionDefinition(val id: String, val required: Boolean)

/** Exact, immutable projection supplied to the closed-world automation compiler after signature verification. */
data class AutomationCompilerInput(
    val configurationSha256: String,
    val studyDurationSeconds: Long,
    val resources: List<DeclaredResource>,
    val interventions: List<InterventionDefinition>,
    val automations: List<AutomationDefinition>,
)
