package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.NumericComparison
import cool.jacoblin.particeps.core.definition.OccurrenceAutomation
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.Trigger
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.resource.ResourceKey
import java.math.BigInteger
import java.time.LocalTime
import java.time.ZoneId
import java.util.IdentityHashMap

/** The deterministic identity of the condition timer owned by one reducer state path. */
internal class ConditionTimerIdentity(val automationId: String, val producerKey: String, val timerId: String)

internal class CompiledPredicate(
    val field: String,
    val contract: FieldContract,
    val operator: FieldOperator,
    val literals: List<TypedFieldValue>,
)

internal class CompiledMatcher(val predicates: List<CompiledPredicate>)

/**
 * The persisted reducer state keys below an automation's root key. [ReducerPlan] and
 * [AutomationReducer] both build every nested condition path here, so the plan resolves exactly the
 * paths the reducer evaluates.
 */
internal object ReducerStatePaths {
    fun bindingCase(bindingId: String, caseIndex: Int) = "binding:$bindingId:case:$caseIndex"
    fun heldChild(path: String) = "$path:child"
    fun member(path: String, index: Int) = "$path:$index"
    fun negated(path: String) = "$path:not"
}

/** The reducer state keys of one occurrence automation; they are persisted checkpoint keys. */
internal class OccurrencePaths(automationId: String) {
    val root = "occurrence:$automationId"
    val guard = "$root:guard"
    val triggerWindow = "$root:trigger:window"
    val triggerWindowEdge = "$root:trigger:window-edge"
    val triggerCondition = "$root:trigger:condition"
    val triggerConditionEdge = "$root:trigger:condition-edge"
    val triggerSequence = "$root:trigger:sequence"
}

/**
 * Static reducer configuration, resolved once when a program compiles: the state keys of every
 * automation, condition-timer identities (two SHA-256 digests each), parsed study-local times,
 * decoded predicate literals, and numeric thresholds. Reduction reads them instead of re-deriving
 * them from signed configuration for every input. The plan is authoritative: the compiler has
 * validated every value it resolves, so resolution cannot fail, and a lookup the plan does not hold
 * is an engine failure rather than a slower derivation of the same value.
 */
internal class ReducerPlan(
    private val input: AutomationCompilerInput,
    occurrences: List<OccurrenceAutomation>,
    bindings: List<ResourceBindingAutomation>,
    private val contracts: Map<EventTypeKey, EventTypeContract>,
) {
    val bindingsByResource: List<ResourceBindingAutomation> = bindings.sortedBy { it.resource }
    val declaredResources: Set<ResourceKey> = input.resources.mapTo(hashSetOf()) { it.key }

    /** Aligned with the program's occurrence automations. */
    val occurrencePaths: List<OccurrencePaths> = occurrences.map { OccurrencePaths(it.id) }
    private val casePathsById: Map<String, List<String>> = bindings.associate { binding ->
        binding.id to List(binding.cases.size) { index -> ReducerStatePaths.bindingCase(binding.id, index) }
    }
    @Volatile private var resolvedZone: ResolvedZone? = null
    private val localWindows = IdentityHashMap<StateCondition.StudyLocalWindow, LocalWindowCache>()
    private val conditionTimers = HashMap<String, ConditionTimerIdentity>()
    private val localTimes = HashMap<String, LocalTime>()
    private val thresholds = HashMap<String, BigInteger>()
    // Keyed by the program's own matcher instances, which reduction passes back, so a lookup does
    // not hash the matcher's predicates for every event.
    private val matchers = IdentityHashMap<EventMatcher, CompiledMatcher>()

    init {
        // Paths mirror AutomationReducer's state keys exactly; they are persisted checkpoint keys.
        occurrences.forEachIndexed { index, automation ->
            val paths = occurrencePaths[index]
            automation.guard?.let { visit(it, paths.guard, automation.id) }
            when (val trigger = automation.trigger) {
                is Trigger.EventMatch -> compile(trigger.selector)
                is Trigger.Sequence -> trigger.steps.forEach(::compile)
                is Trigger.WindowThreshold -> {
                    registerTimer(paths.triggerWindow, automation.id)
                    compile(trigger.selector)
                    registerThreshold(trigger.comparison)
                }
                is Trigger.ConditionRisingEdge -> visit(trigger.condition, paths.triggerCondition, automation.id)
                is Trigger.Schedule -> Unit
            }
        }
        bindings.forEach { binding ->
            binding.cases.forEachIndexed { index, case ->
                visit(case.condition, casePaths(binding)[index], binding.id)
            }
        }
    }

    /** The state key of each case of [binding], in case order. */
    fun casePaths(binding: ResourceBindingAutomation): List<String> =
        planned(casePathsById[binding.id]?.takeIf { it.size == binding.cases.size }) { "binding ${binding.id}" }

    /**
     * The zone for [zoneId], resolved again only when the ID changes: every input of a study carries
     * the participant's one study zone, so a commit need not look the zone rules up again.
     */
    fun zone(zoneId: String): ZoneId = resolvedZone?.takeIf { it.id == zoneId }?.zone
        ?: ZoneId.of(zoneId).also { resolvedZone = ResolvedZone(zoneId, it) }

    /**
     * [studyLocalWindow] for a compiled window condition, read from its dates' windows resolved once
     * per study start and zone.
     */
    fun studyLocalWindow(
        condition: StateCondition.StudyLocalWindow,
        studyStartUtcMillis: Long?,
        nowUtcMillis: Long,
        zoneId: String,
    ): StudyLocalWindowState {
        if (studyStartUtcMillis == null) return StudyLocalWindowState(false, null)
        val zone = zone(zoneId)
        val startTime = localTime(condition.startLocalTime)
        val endTime = localTime(condition.endLocalTime)
        val cache = planned(localWindows[condition]) { "study-local window" }
        val table = cache.table?.takeIf { it.studyStartUtcMillis == studyStartUtcMillis && it.zone == zone }
            ?: StudyLocalWindowTable(condition.firstDay, condition.lastDay, startTime, endTime, studyStartUtcMillis, zone)
                .also { cache.table = it }
        return table.state(nowUtcMillis)
    }

    fun conditionTimer(path: String, automationId: String): ConditionTimerIdentity =
        planned(conditionTimers[path]?.takeIf { it.automationId == automationId }) { "condition timer $path" }

    fun conditionProducerKey(path: String): String =
        planned(conditionTimers[path]) { "condition timer $path" }.producerKey

    fun localTime(value: String): LocalTime = planned(localTimes[value]) { "local time $value" }

    fun threshold(comparison: NumericComparison): BigInteger =
        planned(thresholds[comparison.value]) { "threshold ${comparison.value}" }

    /** The matcher with its field contracts and literals resolved. */
    fun matcher(matcher: EventMatcher): CompiledMatcher = planned(matchers[matcher]) { "matcher ${matcher.event}" }

    private fun visit(condition: StateCondition, path: String, automationId: String) {
        when (condition) {
            StateCondition.StudySessionActive -> Unit
            is StateCondition.EventLatch -> (condition.setWhen + condition.resetWhen).forEach(::compile)
            is StateCondition.KeyedPresence -> (condition.enterWhen + condition.exitWhen).forEach(::compile)
            is StateCondition.HeldFor -> {
                registerTimer(path, automationId)
                visit(condition.condition, ReducerStatePaths.heldChild(path), automationId)
            }
            is StateCondition.StudyLocalWindow -> {
                registerTimer(path, automationId)
                registerLocalTime(condition.startLocalTime)
                registerLocalTime(condition.endLocalTime)
                localWindows.getOrPut(condition, ::LocalWindowCache)
            }
            is StateCondition.ElapsedAtLeast -> registerTimer(path, automationId)
            is StateCondition.WindowThreshold -> {
                registerTimer(path, automationId)
                compile(condition.selector)
                registerThreshold(condition.comparison)
            }
            is StateCondition.All -> condition.conditions.forEachIndexed { index, child ->
                visit(child, ReducerStatePaths.member(path, index), automationId)
            }
            is StateCondition.Any -> condition.conditions.forEachIndexed { index, child ->
                visit(child, ReducerStatePaths.member(path, index), automationId)
            }
            is StateCondition.Not -> visit(condition.condition, ReducerStatePaths.negated(path), automationId)
        }
    }

    private fun registerTimer(path: String, automationId: String) {
        conditionTimers[path] = deriveConditionTimer(path, automationId)
    }

    private fun registerLocalTime(value: String) {
        localTimes[value] = LocalTime.parse(value)
    }

    private fun registerThreshold(comparison: NumericComparison) {
        thresholds[comparison.value] = comparison.value.toBigInteger()
    }

    private fun compile(matcher: EventMatcher) {
        if (matcher in matchers) return
        val contract = checkNotNull(contracts[matcher.event]) { "Compiled matcher has no contract" }
        val predicates = matcher.predicates.map { predicate ->
            val field = checkNotNull(contract.fields[predicate.field]) { "Compiled predicate has no field contract" }
            val literals = if (predicate.operator == FieldOperator.IN) {
                predicate.values.orEmpty().map { TypedFieldDecoder.decodePredicateLiteral(field, it) }
            } else {
                listOf(TypedFieldDecoder.decodePredicateLiteral(field, requireNotNull(predicate.value)))
            }
            CompiledPredicate(predicate.field, field, predicate.operator, literals)
        }
        matchers[matcher] = CompiledMatcher(predicates)
    }

    private fun deriveConditionTimer(path: String, automationId: String): ConditionTimerIdentity {
        val producerKey = deriveConditionProducerKey(path)
        return ConditionTimerIdentity(
            automationId = automationId,
            producerKey = producerKey,
            timerId = DeterministicIds.timerId(input.configurationSha256, automationId, producerKey),
        )
    }

    /** An entry the plan must hold for a compiled program; a missing one is an engine failure. */
    private inline fun <T : Any> planned(value: T?, what: () -> String): T =
        value ?: throw IllegalStateException("AUTOMATION_ENGINE_FAILURE: reducer plan has no ${what()}")
}

private class ResolvedZone(val id: String, val zone: ZoneId)

/** The resolved windows of one compiled window condition, for the study start and zone last seen. */
private class LocalWindowCache {
    @Volatile var table: StudyLocalWindowTable? = null
}

internal fun deriveConditionProducerKey(path: String): String =
    "condition:" + DeterministicIds.digest("particeps-condition-timer-key-v1", listOf(path)).take(40)
