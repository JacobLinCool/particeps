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
 * them from signed configuration for every input. Each lookup falls back to the original
 * derivation, so the plan changes cost, never output.
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
        binding.id to List(binding.cases.size) { index -> "binding:${binding.id}:case:$index" }
    }
    @Volatile private var resolvedZone: ResolvedZone? = null
    private val localWindows = IdentityHashMap<StateCondition.StudyLocalWindow, LocalWindowCache>()
    private val conditionTimers = HashMap<String, ConditionTimerIdentity>()
    private val localTimes = HashMap<String, LocalTime>()
    private val thresholds = HashMap<String, BigInteger>()
    // Keyed by the program's own matcher instances, which reduction passes back, so a lookup does
    // not hash the matcher's predicates for every event; any other instance decodes per event.
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
        casePathsById[binding.id]?.takeIf { it.size == binding.cases.size }
            ?: List(binding.cases.size) { index -> "binding:${binding.id}:case:$index" }

    /**
     * The zone for [zoneId], resolved again only when the ID changes: every input of a study carries
     * the participant's one study zone, so a commit need not look the zone rules up again.
     */
    fun zone(zoneId: String): ZoneId = resolvedZone?.takeIf { it.id == zoneId }?.zone
        ?: ZoneId.of(zoneId).also { resolvedZone = ResolvedZone(zoneId, it) }

    /**
     * [studyLocalWindow] for a compiled window condition, read from its dates' windows resolved once
     * per study start and zone; any other condition instance is evaluated directly.
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
        val cache = localWindows[condition] ?: return studyLocalWindow(
            condition.firstDay, condition.lastDay, startTime, endTime, studyStartUtcMillis, nowUtcMillis, zone,
        )
        val table = cache.table?.takeIf { it.studyStartUtcMillis == studyStartUtcMillis && it.zone == zone }
            ?: StudyLocalWindowTable(condition.firstDay, condition.lastDay, startTime, endTime, studyStartUtcMillis, zone)
                .also { cache.table = it }
        return table.state(nowUtcMillis)
    }

    fun conditionTimer(path: String, automationId: String): ConditionTimerIdentity =
        conditionTimers[path]?.takeIf { it.automationId == automationId }
            ?: deriveConditionTimer(path, automationId)

    fun conditionProducerKey(path: String): String =
        conditionTimers[path]?.producerKey ?: deriveConditionProducerKey(path)

    fun localTime(value: String): LocalTime = localTimes[value] ?: LocalTime.parse(value)

    fun threshold(comparison: NumericComparison): BigInteger =
        thresholds[comparison.value] ?: comparison.value.toBigInteger()

    /** The matcher with its field contracts and literals resolved, or null to decode per event. */
    fun matcher(matcher: EventMatcher): CompiledMatcher? = matchers[matcher]

    /** Every precomputed condition producer key, for tests that prove the plan is complete. */
    fun conditionProducerKeys(): Set<String> = conditionTimers.values.mapTo(hashSetOf()) { it.producerKey }

    private fun visit(condition: StateCondition, path: String, automationId: String) {
        when (condition) {
            StateCondition.StudySessionActive -> Unit
            is StateCondition.EventLatch -> (condition.setWhen + condition.resetWhen).forEach(::compile)
            is StateCondition.KeyedPresence -> (condition.enterWhen + condition.exitWhen).forEach(::compile)
            is StateCondition.HeldFor -> {
                registerTimer(path, automationId)
                visit(condition.condition, "$path:child", automationId)
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
                visit(child, "$path:$index", automationId)
            }
            is StateCondition.Any -> condition.conditions.forEachIndexed { index, child ->
                visit(child, "$path:$index", automationId)
            }
            is StateCondition.Not -> visit(condition.condition, "$path:not", automationId)
        }
    }

    private fun registerTimer(path: String, automationId: String) {
        resolved { deriveConditionTimer(path, automationId) }?.let { conditionTimers[path] = it }
    }

    private fun registerLocalTime(value: String) {
        resolved { LocalTime.parse(value) }?.let { localTimes[value] = it }
    }

    private fun registerThreshold(comparison: NumericComparison) {
        resolved { comparison.value.toBigInteger() }?.let { thresholds[comparison.value] = it }
    }

    private fun compile(matcher: EventMatcher) {
        if (matcher in matchers) return
        val contract = contracts[matcher.event] ?: return
        val predicates = matcher.predicates.map { predicate ->
            val field = contract.fields[predicate.field] ?: return
            val literals = resolved {
                if (predicate.operator == FieldOperator.IN) {
                    predicate.values.orEmpty().map { TypedFieldDecoder.decodePredicateLiteral(field, it) }
                } else {
                    listOf(TypedFieldDecoder.decodePredicateLiteral(field, requireNotNull(predicate.value)))
                }
            } ?: return
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

    /**
     * A compiled program has already validated every value here, so resolution cannot fail. If it
     * ever did, the entry is left out and the reducer derives it per input exactly as before.
     */
    private inline fun <T> resolved(block: () -> T): T? = try {
        block()
    } catch (_: RuntimeException) {
        null
    }
}

private class ResolvedZone(val id: String, val zone: ZoneId)

/** The resolved windows of one compiled window condition, for the study start and zone last seen. */
private class LocalWindowCache {
    @Volatile var table: StudyLocalWindowTable? = null
}

internal fun deriveConditionProducerKey(path: String): String =
    "condition:" + DeterministicIds.digest("particeps-condition-timer-key-v1", listOf(path)).take(40)
