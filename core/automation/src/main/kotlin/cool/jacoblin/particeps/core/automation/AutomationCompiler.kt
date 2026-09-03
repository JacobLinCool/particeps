package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.Aggregate
import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.AutomationDefinition
import cool.jacoblin.particeps.core.definition.AutomationSchedule
import cool.jacoblin.particeps.core.definition.DeclaredResource
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.FieldPredicate
import cool.jacoblin.particeps.core.definition.NumericComparison
import cool.jacoblin.particeps.core.definition.OccurrenceAutomation
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.EvaluationClock
import cool.jacoblin.particeps.core.definition.Trigger
import cool.jacoblin.particeps.core.model.EventTypeKey

data class ValidationIssue(val code: String, val path: String, val message: String)
sealed interface CompilationResult {
    data class Success(val program: CompiledAutomationProgram) : CompilationResult
    data class Failure(val issues: List<ValidationIssue>) : CompilationResult
}
class CompiledAutomationProgram internal constructor(
    val input: AutomationCompilerInput,
    val occurrenceAutomations: List<OccurrenceAutomation>,
    val resourceBindings: List<ResourceBindingAutomation>,
    internal val referencedEvents: Set<EventTypeKey>,
    internal val contracts: Map<EventTypeKey, EventTypeContract>,
)

class AutomationCompiler(private val registry: EventContractRegistry) {
    fun compile(input: AutomationCompilerInput): CompilationResult {
        val validator = Validator(input, registry)
        return validator.validate()
    }
}

private class Validator(
    private val configuration: AutomationCompilerInput,
    private val registry: EventContractRegistry,
) {
    private val issues = mutableListOf<ValidationIssue>()
    private val contracts = linkedMapOf<EventTypeKey, EventTypeContract>()
    private val resourcesByKey = configuration.resources.associateBy(DeclaredResource::key)

    fun validate(): CompilationResult {
        validateRoot()
        configuration.automations.forEachIndexed { index, automation ->
            validateAutomation(automation, "automations[$index]")
        }
        validateReferencesAndGraph()
        val sortedIssues = issues.sortedWith(compareBy(ValidationIssue::path, ValidationIssue::code, ValidationIssue::message))
        if (sortedIssues.isNotEmpty()) return CompilationResult.Failure(sortedIssues)
        return CompilationResult.Success(
            CompiledAutomationProgram(
                input = configuration,
                occurrenceAutomations = configuration.automations.filterIsInstance<OccurrenceAutomation>().sortedBy { it.id },
                resourceBindings = configuration.automations.filterIsInstance<ResourceBindingAutomation>().sortedBy { it.id },
                referencedEvents = contracts.keys.toSet(),
                contracts = contracts.toMap(),
            ),
        )
    }

    private fun validateRoot() {
        if (!SHA256.matches(configuration.configurationSha256)) {
            issue("INVALID_CONFIGURATION_DIGEST", "configuration_sha256", "Expected lowercase SHA-256")
        }
        if (configuration.studyDurationSeconds !in 1..MAX_DURATION_SECONDS) {
            issue("INVALID_STUDY_DURATION", "study_duration_seconds", "Study duration must be 1–31,536,000 seconds")
        }
        val resourceKeys = configuration.resources.map { it.key }
        if (resourceKeys != resourceKeys.sorted() || resourceKeys.distinct().size != resourceKeys.size) {
            issue("UNSORTED_OR_DUPLICATE_IDS", "resources", "Entries must be sorted and unique")
        }
        if (configuration.resources.size > MAX_RESOURCES) issue("TOO_MANY_RESOURCES", "resources", "At most 64 resources are allowed")
        configuration.resources.forEachIndexed { index, resource -> validateResource(resource, "resources[$index]") }

        validateSortedUniqueStrings(configuration.interventions.map { it.id }, "interventions")
        if (configuration.interventions.size > MAX_INTERVENTIONS) {
            issue("TOO_MANY_INTERVENTIONS", "interventions", "At most 128 interventions are allowed")
        }
        configuration.interventions.forEachIndexed { index, intervention ->
            if (!ID.matches(intervention.id)) issue("INVALID_ID", "interventions[$index].id", "Invalid intervention ID")
        }

        validateSortedUniqueStrings(configuration.automations.map { it.id }, "automations")
        if (configuration.automations.size > MAX_AUTOMATIONS) {
            issue("TOO_MANY_AUTOMATIONS", "automations", "At most 128 automations are allowed")
        }
        if (configuration.automations.isEmpty() &&
            (configuration.resources.isNotEmpty() || configuration.interventions.isNotEmpty())
        ) {
            issue("EMPTY_AUTOMATION_SET", "automations", "Configured resources and interventions require automations")
        }
        val maximumActivations = configuration.automations.filterIsInstance<OccurrenceAutomation>()
            .sumOf { it.maximumActivations.toLong().coerceAtLeast(0) }
        if (maximumActivations > MAX_LIFETIME_ACTIVATIONS) {
            issue("TOO_MANY_ACTIVATIONS", "automations", "Lifetime activations exceed 512")
        }
        val maximumConcurrentTimers = configuration.automations.sumOf(::maximumConcurrentTimerCount)
        if (maximumConcurrentTimers > MAX_CONCURRENT_TIMERS) {
            issue("TOO_MANY_TIMERS", "automations", "Automation state may require more than 512 concurrent timers")
        }
    }

    private fun validateResource(resource: DeclaredResource, path: String) {
        if (resource.profileDigests.size !in 1..MAX_PROFILES_PER_RESOURCE) {
            issue("INVALID_PROFILE_COUNT", "$path.profile_digests", "A resource needs 1–64 profiles")
        }
        val profileIds = resource.profileDigests.keys.toList()
        if (profileIds != profileIds.sorted() || profileIds.distinct().size != profileIds.size) {
            issue("UNSORTED_OR_DUPLICATE_PROFILES", "$path.profile_digests", "Profiles must be sorted and unique")
        }
        resource.profileDigests.forEach { (profileId, digest) ->
            if (!ID.matches(profileId)) issue("INVALID_ID", "$path.profile_digests.$profileId", "Invalid profile ID")
            if (!SHA256.matches(digest)) issue("INVALID_PROFILE_DIGEST", "$path.profile_digests.$profileId", "Invalid profile digest")
        }
    }

    private fun validateAutomation(automation: AutomationDefinition, path: String) {
        if (!ID.matches(automation.id)) issue("INVALID_ID", "$path.id", "Invalid automation ID")
        when (automation) {
            is OccurrenceAutomation -> validateOccurrence(automation, path)
            is ResourceBindingAutomation -> validateBinding(automation, path)
        }
    }

    private fun validateOccurrence(automation: OccurrenceAutomation, path: String) {
        if (automation.availabilitySeconds !in 1..MAX_DURATION_SECONDS.toInt()) {
            issue("INVALID_AVAILABILITY", "$path.availability_seconds", "Availability is outside Protocol bounds")
        }
        if (automation.maximumActivations !in 1..MAX_LIFETIME_ACTIVATIONS) {
            issue("INVALID_MAXIMUM_ACTIVATIONS", "$path.maximum_activations", "Maximum activations must be 1–512")
        }
        automation.cooldown?.let { cooldown ->
            if (cooldown.durationSeconds !in 1..MAX_DURATION_SECONDS.toInt()) {
                issue("INVALID_COOLDOWN", "$path.cooldown.duration_seconds", "Cooldown is outside Protocol bounds")
            }
        }
        if (configuration.interventions.none { it.id == automation.interventionId }) {
            issue("UNKNOWN_INTERVENTION", "$path.intervention_id", "Unknown intervention")
        }
        validateTrigger(automation.trigger, "$path.trigger")
        automation.guard?.let { validateCondition(it, "$path.guard", 1, NodeCounter()) }
        val conditionNodes = triggerConditionNodeCount(automation.trigger) +
            (automation.guard?.let(::conditionNodeCount) ?: 0)
        if (conditionNodes > MAX_CONDITION_NODES) {
            issue("TOO_MANY_CONDITION_NODES", path, "Automation contains more than 64 condition nodes")
        }
    }

    private fun validateBinding(automation: ResourceBindingAutomation, path: String) {
        val resource = resourcesByKey[automation.resource]
        if (resource == null) issue("UNKNOWN_RESOURCE", "$path.resource", "Unknown resource")
        if (automation.cases.size > MAX_CASES) issue("TOO_MANY_CASES", "$path.cases", "At most 16 cases are allowed")
        val conditionCounter = NodeCounter()
        automation.cases.forEachIndexed { index, case ->
            validateCondition(case.condition, "$path.cases[$index].when", 1, conditionCounter)
            validateProfileReference(resource, case.profileId, "$path.cases[$index].profile_id")
        }
        validateProfileReference(resource, automation.defaultProfileId, "$path.default_profile_id")
        if (conditionCounter.value > MAX_CONDITION_NODES) {
            issue("TOO_MANY_CONDITION_NODES", "$path.cases", "Automation contains more than 64 condition nodes")
        }
    }

    private fun validateProfileReference(resource: DeclaredResource?, profileId: String?, path: String) {
        if (profileId != null && resource?.profileDigests?.containsKey(profileId) != true) {
            issue("UNKNOWN_PROFILE", path, "Unknown resource profile")
        }
    }

    private fun validateTrigger(trigger: Trigger, path: String) {
        when (trigger) {
            is Trigger.EventMatch -> validateMatcher(
                trigger.selector,
                "$path.selector",
                trigger.evaluationClock,
                EventConditionKind.EVENT_MATCH,
            )
            is Trigger.Sequence -> {
                if (trigger.steps.size !in 2..MAX_SEQUENCE_STEPS) {
                    issue("INVALID_SEQUENCE_STEPS", "$path.steps", "Sequence needs 2–16 steps")
                }
                if (trigger.withinSeconds !in 1..MAX_WINDOW_SECONDS) {
                    issue("INVALID_WINDOW", "$path.within_seconds", "Sequence window is outside Protocol bounds")
                }
                trigger.steps.forEachIndexed { index, matcher ->
                    validateMatcher(
                        matcher,
                        "$path.steps[$index]",
                        trigger.evaluationClock,
                        EventConditionKind.SEQUENCE_STEP,
                    )
                }
                validateRetainedBound(trigger.steps, trigger.withinSeconds, "$path.steps")
            }
            is Trigger.WindowThreshold -> validateWindowThreshold(
                trigger.selector,
                trigger.windowSeconds,
                trigger.evaluationClock,
                trigger.aggregate,
                trigger.comparison,
                path,
            )
            is Trigger.ConditionRisingEdge -> validateCondition(trigger.condition, "$path.condition", 1, NodeCounter())
            is Trigger.Schedule -> validateSchedule(trigger.schedule, "$path.schedule")
        }
    }

    private fun validateCondition(condition: StateCondition, path: String, depth: Int, nodes: NodeCounter) {
        nodes.value++
        if (depth > MAX_CONDITION_DEPTH) issue("CONDITION_TOO_DEEP", path, "Condition nesting exceeds 8")
        if (nodes.value > MAX_CONDITION_NODES) issue("TOO_MANY_CONDITION_NODES", path, "Automation contains more than 64 condition nodes")
        when (condition) {
            StateCondition.StudySessionActive -> Unit
            is StateCondition.EventLatch -> {
                validateMatcherList(condition.setWhen, "$path.set_when", EventConditionKind.EVENT_MATCH)
                validateMatcherList(condition.resetWhen, "$path.reset_when", EventConditionKind.EVENT_MATCH)
            }
            is StateCondition.KeyedPresence -> {
                validateMatcherList(
                    condition.enterWhen,
                    "$path.enter_when",
                    EventConditionKind.KEYED_PRESENCE_ENTER,
                )
                validateMatcherList(
                    condition.exitWhen,
                    "$path.exit_when",
                    EventConditionKind.KEYED_PRESENCE_EXIT,
                )
                val matchers = condition.enterWhen + condition.exitWhen
                val eventContracts = matchers.mapNotNull { matcher -> registry.contract(matcher.event) }
                val keyContracts = eventContracts.mapNotNull { contract -> contract.fields[condition.keyField] }
                if (keyContracts.size != matchers.size || keyContracts.map { it.type }.distinct().size != 1 ||
                    keyContracts.any { !it.required || !it.keyedPresenceKey }
                ) {
                    issue(
                        "INVALID_PRESENCE_KEY",
                        "$path.key_field",
                        "Presence key must be registry-authorized, required, and have one type on every matcher",
                    )
                }
                val enterContracts = condition.enterWhen.mapNotNull { matcher -> registry.contract(matcher.event)?.presence }
                val exitContracts = condition.exitWhen.mapNotNull { matcher -> registry.contract(matcher.event)?.presence }
                val presenceContracts = enterContracts + exitContracts
                if (
                    eventContracts.size != matchers.size ||
                    enterContracts.size != condition.enterWhen.size ||
                    exitContracts.size != condition.exitWhen.size ||
                    enterContracts.any { it.role != EventPresenceRole.ENTER } ||
                    exitContracts.any { it.role != EventPresenceRole.EXIT } ||
                    presenceContracts.map { it.groupId }.distinct().size != 1 ||
                    presenceContracts.any { it.keyFields != setOf(condition.keyField) }
                ) {
                    issue(
                        "INVALID_PRESENCE_CONTRACT",
                        path,
                        "Presence matchers must use one registry group with exact ENTER/EXIT roles and key fields",
                    )
                }
            }
            is StateCondition.HeldFor -> {
                validateDuration(condition.durationSeconds, "$path.duration_seconds")
                validateCondition(condition.condition, "$path.condition", depth + 1, nodes)
            }
            is StateCondition.ElapsedAtLeast -> validateDuration(condition.durationSeconds, "$path.duration_seconds")
            is StateCondition.WindowThreshold -> validateWindowThreshold(
                condition.selector,
                condition.windowSeconds,
                condition.evaluationClock,
                condition.aggregate,
                condition.comparison,
                path,
            )
            is StateCondition.All -> {
                validateConditionGroup(condition.conditions, path)
                condition.conditions.forEachIndexed { index, child ->
                    validateCondition(child, "$path.conditions[$index]", depth + 1, nodes)
                }
            }
            is StateCondition.Any -> {
                validateConditionGroup(condition.conditions, path)
                condition.conditions.forEachIndexed { index, child ->
                    validateCondition(child, "$path.conditions[$index]", depth + 1, nodes)
                }
            }
            is StateCondition.Not -> validateCondition(condition.condition, "$path.condition", depth + 1, nodes)
        }
    }

    private fun validateConditionGroup(conditions: List<StateCondition>, path: String) {
        if (conditions.size !in 2..MAX_GROUP_CHILDREN) {
            issue("INVALID_CONDITION_GROUP", "$path.conditions", "all/any needs 2–8 children")
        }
    }

    private fun validateDuration(seconds: Int, path: String) {
        if (seconds !in 1..configuration.studyDurationSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            issue("INVALID_CONDITION_DURATION", path, "Duration must fit within the study")
        }
    }

    private fun validateMatcherList(
        matchers: List<EventMatcher>,
        path: String,
        conditionKind: EventConditionKind,
    ) {
        if (matchers.size !in 1..MAX_MATCHERS_PER_LIST) issue("INVALID_MATCHER_COUNT", path, "Matcher list needs 1–8 entries")
        matchers.forEachIndexed { index, matcher ->
            validateMatcher(matcher, "$path[$index]", null, conditionKind)
        }
    }

    private fun validateMatcher(
        matcher: EventMatcher,
        path: String,
        evaluationClock: EvaluationClock?,
        conditionKind: EventConditionKind,
    ) {
        val contract = registry.contract(matcher.event)
        if (contract == null) {
            issue("UNKNOWN_EVENT", path, "Unknown event identity")
            return
        }
        contracts[matcher.event] = contract
        if (contract.key != matcher.event) issue("REGISTRY_KEY_MISMATCH", path, "Registry returned a different event identity")
        if (contract.triggerScope != TriggerScope.RESEARCHER) {
            issue("NON_TRIGGERABLE_EVENT", path, "Runtime/audit events cannot trigger research automations")
        }
        if (conditionKind !in contract.conditionKinds) {
            issue("UNSUPPORTED_CONDITION_KIND", path, "Event does not support this condition kind")
        }
        evaluationClock?.let { clock ->
            val supported = EventClockSupport.valueOf(clock.name)
            if (supported !in contract.clockSupport) issue("UNSUPPORTED_CLOCK", path, "Event does not support the selected clock")
        }
        if (matcher.predicates.size > MAX_PREDICATES) issue("TOO_MANY_PREDICATES", "$path.predicates", "At most 16 predicates are allowed")
        if (matcher.predicates.map { it.field }.distinct().size != matcher.predicates.size) {
            issue("DUPLICATE_PREDICATE_FIELD", "$path.predicates", "A matcher may compare a field once")
        }
        matcher.predicates.forEachIndexed { index, predicate ->
            validatePredicate(predicate, contract, "$path.predicates[$index]")
        }
    }

    private fun validatePredicate(predicate: FieldPredicate, event: EventTypeContract, path: String) {
        val field = event.fields[predicate.field]
        if (field == null) {
            issue("UNKNOWN_FIELD", "$path.field", "Unknown event field")
            return
        }
        if (predicate.operator !in field.operators) issue("UNSUPPORTED_OPERATOR", "$path.operator", "Operator is not allowed for this field")
        if (predicate.operator == FieldOperator.IN) {
            val values = predicate.values
            if (predicate.value != null || values == null || values.size !in 1..MAX_IN_VALUES) {
                issue("INVALID_IN_OPERANDS", path, "in requires 1–64 values and no value")
                return
            }
            if (values != values.sorted() || values.distinct().size != values.size) {
                issue("UNSORTED_OR_DUPLICATE_IN_VALUES", "$path.values", "in values must be sorted and unique")
            }
            values.forEachIndexed { index, value -> decodeField(field, value, "$path.values[$index]") }
        } else {
            val value = predicate.value
            if (predicate.values != null || value == null) {
                issue("INVALID_PREDICATE_OPERAND", path, "Non-in operator requires exactly one value")
                return
            }
            decodeField(field, value, "$path.value")
        }
    }

    private fun decodeField(field: FieldContract, value: String, path: String) {
        try {
            TypedFieldDecoder.decodePredicateLiteral(field, value)
        } catch (error: IllegalArgumentException) {
            issue("INVALID_FIELD_VALUE", path, error.message ?: "Invalid canonical field value")
        }
    }

    private fun validateWindowThreshold(
        selector: EventMatcher,
        windowSeconds: Int,
        evaluationClock: EvaluationClock,
        aggregate: Aggregate,
        comparison: NumericComparison,
        path: String,
    ) {
        val conditionKind = when (aggregate) {
            Aggregate.Count -> EventConditionKind.WINDOW_COUNT
            is Aggregate.Sum -> EventConditionKind.WINDOW_SUM
        }
        validateMatcher(selector, "$path.selector", evaluationClock, conditionKind)
        if (windowSeconds !in 1..MAX_WINDOW_SECONDS) issue("INVALID_WINDOW", "$path.window_seconds", "Window is outside Protocol bounds")
        val contract = registry.contract(selector.event)
        when (aggregate) {
            Aggregate.Count -> validateIntegerComparison(comparison, "$path.comparison")
            is Aggregate.Sum -> {
                val field = contract?.fields?.get(aggregate.field)
                if (field?.type != ScalarType.INTEGER || !field.required || !field.windowSumAllowed) {
                    issue(
                        "INVALID_SUM_FIELD",
                        "$path.aggregate.field",
                        "Protocol v1 sums only required integer fields explicitly authorized by the registry",
                    )
                }
                validateIntegerComparison(comparison, "$path.comparison")
            }
        }
        validateRetainedBound(listOf(selector), windowSeconds, "$path.selector")
    }

    private fun validateIntegerComparison(comparison: NumericComparison, path: String) {
        if (comparison.operator !in NUMERIC_COMPARISONS) issue("INVALID_NUMERIC_COMPARISON", "$path.operator", "Invalid numeric comparison")
        val parsed = comparison.value.toBigIntegerOrNull()
        if (parsed == null || parsed.toString() != comparison.value) issue("INVALID_NUMERIC_THRESHOLD", "$path.value", "Threshold must be a canonical integer")
    }

    private fun validateRetainedBound(matchers: List<EventMatcher>, windowSeconds: Int, path: String) {
        if (windowSeconds !in 1..MAX_WINDOW_SECONDS) return
        var entries = 0L
        matchers.forEach { matcher ->
            val bound = registry.contract(matcher.event)?.rateBound
            if (bound == null) {
                issue("UNBOUNDED_SOURCE", path, "Sequence/window source needs an enforced rate bound")
            } else {
                entries = addSaturated(entries, bound.maximumEntries(windowSeconds))
            }
        }
        if (entries > MAX_RETAINED_ENTRIES) issue("UNBOUNDED_AUTOMATION_STATE", path, "Worst-case retained entries exceed 4,096")
    }

    private fun validateSchedule(schedule: AutomationSchedule, path: String) {
        when (schedule) {
            is AutomationSchedule.OneTime -> {
                if (schedule.offsetMinutes < 0 || schedule.offsetMinutes.toLong() * 60 >= configuration.studyDurationSeconds) {
                    issue("INVALID_ONE_TIME_OFFSET", "$path.offset_minutes", "One-time offset must be inside the study")
                }
            }
            is AutomationSchedule.Interval -> {
                if (schedule.startOffsetMinutes < 0 || schedule.startOffsetMinutes.toLong() * 60 >= configuration.studyDurationSeconds) {
                    issue("INVALID_INTERVAL_START", "$path.start_offset_minutes", "Interval start must be inside the study")
                }
                if (schedule.intervalMinutes !in 1..525_600) {
                    issue("INVALID_INTERVAL", "$path.interval_minutes", "Interval is outside Protocol bounds")
                }
            }
            is AutomationSchedule.DailyLocal -> if (!LOCAL_TIME.matches(schedule.localTime)) {
                issue("INVALID_LOCAL_TIME", "$path.local_time", "Expected zero-padded HH:mm")
            }
            is AutomationSchedule.RandomWindow -> validateRandomWindow(schedule, path)
        }
    }

    private fun validateRandomWindow(schedule: AutomationSchedule.RandomWindow, path: String) {
        if (schedule.localWindows.size !in 1..8) issue("INVALID_RANDOM_WINDOW_COUNT", "$path.local_windows", "Random schedule needs 1–8 windows")
        val parsed = schedule.localWindows.mapIndexed { index, window ->
            val start = parseLocalMinute(window.startLocalTime, "$path.local_windows[$index].start_local_time")
            val end = parseLocalMinute(window.endLocalTime, "$path.local_windows[$index].end_local_time")
            if (start != null && end != null && start >= end) issue("INVALID_RANDOM_WINDOW", "$path.local_windows[$index]", "Window must not be overnight or empty")
            start to end
        }
        parsed.zipWithNext().forEachIndexed { index, (first, second) ->
            if (first.second != null && second.first != null && first.second!! > second.first!!) {
                issue("UNSORTED_OR_OVERLAPPING_WINDOWS", "$path.local_windows[${index + 1}]", "Windows must be sorted and non-overlapping")
            }
        }
        if (schedule.occurrencesPerWindow !in 1..8) issue("INVALID_OCCURRENCES_PER_WINDOW", "$path.occurrences_per_window", "Expected 1–8")
        if (schedule.maximumOccurrencesPerDay !in 1..64) issue("INVALID_DAILY_CAP", "$path.maximum_occurrences_per_day", "Expected 1–64")
        if (schedule.maximumOccurrencesTotal !in 1..512) issue("INVALID_TOTAL_CAP", "$path.maximum_occurrences_total", "Expected 1–512")
        if (schedule.minimumSeparationMinutes !in 1..1_440) issue("INVALID_MINIMUM_SEPARATION", "$path.minimum_separation_minutes", "Expected 1–1,440")
        if (schedule.maximumOccurrencesPerDay > schedule.localWindows.size * schedule.occurrencesPerWindow) {
            issue("DAILY_CAP_EXCEEDS_CAPACITY", "$path.maximum_occurrences_per_day", "Daily cap exceeds signed slots")
        }
        parsed.forEachIndexed { index, (start, end) ->
            if (start != null && end != null) {
                val requiredWidth = 1L + (schedule.occurrencesPerWindow - 1L) * schedule.minimumSeparationMinutes
                if (end - start < requiredWidth) issue("WINDOW_TOO_NARROW", "$path.local_windows[$index]", "Window cannot preserve ordinal separation")
            }
        }
        if (parsed.isNotEmpty() && parsed.all { it.first != null && it.second != null }) {
            parsed.indices.forEach { index ->
                val currentEnd = parsed[index].second!!
                val nextStart = parsed[(index + 1) % parsed.size].first!! + if (index == parsed.lastIndex) 1_440 else 0
                if (nextStart - (currentEnd - 1) < schedule.minimumSeparationMinutes) {
                    issue("WINDOWS_TOO_CLOSE", "$path.local_windows", "Adjacent windows violate minimum separation")
                }
            }
        }
    }

    private fun parseLocalMinute(value: String, path: String): Int? {
        if (!LOCAL_TIME.matches(value)) {
            issue("INVALID_LOCAL_TIME", path, "Expected zero-padded HH:mm")
            return null
        }
        return value.substring(0, 2).toInt() * 60 + value.substring(3).toInt()
    }

    private fun validateReferencesAndGraph() {
        val occurrences = configuration.automations.filterIsInstance<OccurrenceAutomation>()
        val usedInterventions = occurrences.map { it.interventionId }.toSet()
        configuration.interventions.forEachIndexed { index, intervention ->
            if (intervention.id !in usedInterventions) issue("UNUSED_INTERVENTION", "interventions[$index]", "Intervention is not referenced")
        }

        val bindings = configuration.automations.filterIsInstance<ResourceBindingAutomation>()
        val groupedOwners = bindings.groupBy { it.resource }
        configuration.resources.forEachIndexed { index, resource ->
            val owners = groupedOwners[resource.key].orEmpty()
            if (owners.size != 1) issue("INVALID_RESOURCE_OWNER_COUNT", "resources[$index]", "Every resource needs exactly one binding owner")
            if (resource.required && owners.singleOrNull()?.let(::alwaysActive) != true) {
                issue("REQUIRED_RESOURCE_CAN_BE_INACTIVE", "resources[$index]", "Required resource must select a profile throughout active session")
            }
        }

        contracts.values.filter { it.sourceKind == EventSourceKind.COLLECTOR }.map { it.key.sourceId }.toSet().forEach { sourceId ->
            val sourceResource = configuration.resources.singleOrNull {
                it.key.kind == cool.jacoblin.particeps.core.resource.ResourceKind.COLLECTOR && it.key.id == sourceId.value
            }
            if (sourceResource == null || !sourceResource.required) {
                issue("TRIGGER_SOURCE_NOT_REQUIRED", "automations", "Collector trigger source $sourceId must be a required resource")
            } else {
                val owner = groupedOwners[sourceResource.key]?.singleOrNull()
                if (owner == null || !alwaysActive(owner)) {
                    issue("TRIGGER_SOURCE_NOT_LIVE", "automations", "Collector trigger source $sourceId must remain active")
                }
            }
        }

        val graph = bindings.associate { binding ->
            binding.resource to bindingDependencies(binding)
        }
        detectCycles(graph)
    }

    private fun alwaysActive(binding: ResourceBindingAutomation): Boolean {
        binding.cases.forEach { case ->
            if (case.profileId == null) return false
            // Resource cases use first-true-case semantics. During the active portion of a study,
            // this condition is guaranteed true, so later cases and the default are unreachable.
            if (case.condition == StateCondition.StudySessionActive) return true
        }
        return binding.defaultProfileId != null
    }

    private fun bindingDependencies(binding: ResourceBindingAutomation): Set<cool.jacoblin.particeps.core.resource.ResourceKey> =
        binding.cases.flatMap { conditionMatchers(it.condition) }
            .mapNotNull { matcher ->
                val contract = registry.contract(matcher.event)
                if (contract?.sourceKind != EventSourceKind.COLLECTOR) null else configuration.resources.singleOrNull {
                    it.key.kind == cool.jacoblin.particeps.core.resource.ResourceKind.COLLECTOR &&
                        it.key.id == matcher.event.sourceId.value
                }?.key
            }
            .toSet()

    private fun detectCycles(graph: Map<cool.jacoblin.particeps.core.resource.ResourceKey, Set<cool.jacoblin.particeps.core.resource.ResourceKey>>) {
        val visiting = mutableSetOf<cool.jacoblin.particeps.core.resource.ResourceKey>()
        val visited = mutableSetOf<cool.jacoblin.particeps.core.resource.ResourceKey>()
        fun visit(node: cool.jacoblin.particeps.core.resource.ResourceKey) {
            if (node in visiting) {
                issue("RESOURCE_DEPENDENCY_CYCLE", "automations", "Resource dependency cycle includes ${node.id}")
                return
            }
            if (!visited.add(node)) return
            visiting += node
            graph[node].orEmpty().sorted().forEach(::visit)
            visiting -= node
        }
        graph.keys.sorted().forEach(::visit)
    }

    private fun conditionMatchers(condition: StateCondition): List<EventMatcher> = when (condition) {
        StateCondition.StudySessionActive, is StateCondition.ElapsedAtLeast -> emptyList()
        is StateCondition.EventLatch -> condition.setWhen + condition.resetWhen
        is StateCondition.KeyedPresence -> condition.enterWhen + condition.exitWhen
        is StateCondition.HeldFor -> conditionMatchers(condition.condition)
        is StateCondition.WindowThreshold -> listOf(condition.selector)
        is StateCondition.All -> condition.conditions.flatMap(::conditionMatchers)
        is StateCondition.Any -> condition.conditions.flatMap(::conditionMatchers)
        is StateCondition.Not -> conditionMatchers(condition.condition)
    }

    private fun validateSortedUniqueStrings(strings: List<String>, path: String) {
        if (strings != strings.sorted() || strings.distinct().size != strings.size) {
            issue("UNSORTED_OR_DUPLICATE_IDS", path, "Entries must be sorted and unique")
        }
    }

    private fun triggerConditionNodeCount(trigger: Trigger): Int = when (trigger) {
        is Trigger.ConditionRisingEdge -> conditionNodeCount(trigger.condition)
        else -> 0
    }

    private fun conditionNodeCount(condition: StateCondition): Int = 1 + when (condition) {
        is StateCondition.HeldFor -> conditionNodeCount(condition.condition)
        is StateCondition.All -> condition.conditions.sumOf(::conditionNodeCount)
        is StateCondition.Any -> condition.conditions.sumOf(::conditionNodeCount)
        is StateCondition.Not -> conditionNodeCount(condition.condition)
        else -> 0
    }

    private fun maximumConcurrentTimerCount(automation: AutomationDefinition): Int = when (automation) {
        is OccurrenceAutomation -> triggerTimerCount(automation.trigger) + (automation.guard?.let(::conditionTimerCount) ?: 0)
        is ResourceBindingAutomation -> automation.cases.sumOf { conditionTimerCount(it.condition) }
    }

    private fun triggerTimerCount(trigger: Trigger): Int = when (trigger) {
        is Trigger.Schedule, is Trigger.WindowThreshold -> 1
        is Trigger.ConditionRisingEdge -> conditionTimerCount(trigger.condition)
        is Trigger.EventMatch, is Trigger.Sequence -> 0
    }

    private fun conditionTimerCount(condition: StateCondition): Int = when (condition) {
        StateCondition.StudySessionActive, is StateCondition.EventLatch, is StateCondition.KeyedPresence -> 0
        is StateCondition.HeldFor -> 1 + conditionTimerCount(condition.condition)
        is StateCondition.ElapsedAtLeast, is StateCondition.WindowThreshold -> 1
        is StateCondition.All -> condition.conditions.sumOf(::conditionTimerCount)
        is StateCondition.Any -> condition.conditions.sumOf(::conditionTimerCount)
        is StateCondition.Not -> conditionTimerCount(condition.condition)
    }

    private fun issue(code: String, path: String, message: String) {
        issues += ValidationIssue(code, path, message)
    }

    private class NodeCounter(var value: Int = 0)

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val LOCAL_TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        val NUMERIC_COMPARISONS = setOf(
            FieldOperator.EQ,
            FieldOperator.NE,
            FieldOperator.LT,
            FieldOperator.LTE,
            FieldOperator.GT,
            FieldOperator.GTE,
        )
        const val MAX_DURATION_SECONDS = 31_536_000L
        const val MAX_RESOURCES = 64
        const val MAX_PROFILES_PER_RESOURCE = 64
        const val MAX_INTERVENTIONS = 128
        const val MAX_AUTOMATIONS = 128
        const val MAX_LIFETIME_ACTIVATIONS = 512
        const val MAX_CASES = 16
        const val MAX_SEQUENCE_STEPS = 16
        const val MAX_WINDOW_SECONDS = 604_800
        const val MAX_CONDITION_DEPTH = 8
        const val MAX_CONDITION_NODES = 64
        const val MAX_GROUP_CHILDREN = 8
        const val MAX_MATCHERS_PER_LIST = 8
        const val MAX_PREDICATES = 16
        const val MAX_IN_VALUES = 64
        const val MAX_RETAINED_ENTRIES = 4_096L
        const val MAX_CONCURRENT_TIMERS = 512
    }
}

private fun addSaturated(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
