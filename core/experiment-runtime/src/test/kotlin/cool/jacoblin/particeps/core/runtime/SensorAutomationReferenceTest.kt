package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCompiler
import cool.jacoblin.particeps.core.automation.CompilationResult
import cool.jacoblin.particeps.core.automation.CompiledAutomationProgram
import cool.jacoblin.particeps.core.definition.Aggregate
import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.AutomationDefinition
import cool.jacoblin.particeps.core.definition.DeclaredResource
import cool.jacoblin.particeps.core.definition.DurationClock
import cool.jacoblin.particeps.core.definition.EvaluationClock
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.FieldPredicate
import cool.jacoblin.particeps.core.definition.InterventionDefinition
import cool.jacoblin.particeps.core.definition.NumericComparison
import cool.jacoblin.particeps.core.definition.OccurrenceAutomation
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.ResourceConditionCase
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.Trigger
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated registry lets research automation reference the continuous motion sensors, so a
 * study's compiled program, not the registry, decides whether their collectors may batch commits.
 */
class SensorAutomationReferenceTest {
    @Test
    fun eventMatchTriggerOnTheGyroscopeReferencesOnlyThatSource() {
        val program = compile(
            occurrence(Trigger.EventMatch(FAST_ROTATION, EvaluationClock.OBSERVED_RESEARCH_TIME), guard = null),
        )

        assertTrue(program.referencesSource(GYROSCOPE))
        assertFalse(program.referencesSource(ACCELEROMETER))
    }

    @Test
    fun guardAndResourceBindingLatchesReferenceTheirSources() {
        val latch = StateCondition.EventLatch(setWhen = listOf(FAST_ROTATION), resetWhen = listOf(SLOW_ROTATION))
        val guarded = compile(
            occurrence(
                Trigger.EventMatch(EventMatcher(ACCELEROMETER_SAMPLE), EvaluationClock.OBSERVED_RESEARCH_TIME),
                guard = latch,
            ),
        )
        assertTrue(guarded.referencesSource(GYROSCOPE))
        assertTrue(guarded.referencesSource(ACCELEROMETER))

        val bound = compile(accelerometerBinding(latch))
        assertTrue(bound.referencesSource(GYROSCOPE))
        assertFalse(bound.referencesSource(ACCELEROMETER))
    }

    @Test
    fun studyThatOnlyCollectsTheSensorsReferencesNeither() {
        val program = compile()

        assertFalse(program.referencesSource(GYROSCOPE))
        assertFalse(program.referencesSource(ACCELEROMETER))
    }

    @Test
    fun sequencesAndWindowsOverTheSensorsAreRejectedForTheirUnboundedRate() {
        val sequence = Trigger.Sequence(listOf(FAST_ROTATION, SLOW_ROTATION), 10, EvaluationClock.OBSERVED_RESEARCH_TIME)
        val window = Trigger.WindowThreshold(
            FAST_ROTATION,
            10,
            EvaluationClock.OBSERVED_RESEARCH_TIME,
            Aggregate.Count,
            NumericComparison(FieldOperator.GTE, "3"),
        )

        listOf(sequence, window).forEach { trigger ->
            val result = AutomationCompiler(GeneratedEventContractRegistry).compile(input(occurrence(trigger, guard = null)))
            val issues = (result as CompilationResult.Failure).issues
            assertEquals(listOf("UNBOUNDED_SOURCE"), issues.map { it.code }.distinct())
        }
    }

    @Test
    fun onlySequenceAndWindowStateOrdersEveryEventByItsTime() {
        val latch = StateCondition.EventLatch(setWhen = listOf(FAST_ROTATION), resetWhen = listOf(SLOW_ROTATION))
        listOf(
            compile(),
            compile(occurrence(Trigger.EventMatch(FAST_ROTATION, EvaluationClock.OBSERVED_RESEARCH_TIME), guard = latch)),
            compile(accelerometerBinding(StateCondition.HeldFor(latch, 60, DurationClock.ACTIVE_RUNNING_TIME))),
            compile(
                accelerometerBinding(StateCondition.StudyLocalWindow(1, 366, "12:00", "17:00")),
                withBattery = true,
            ),
        ).forEach { program -> assertFalse(program.retainsEventTimeOrderedState) }

        val lowBatteryWindow = StateCondition.WindowThreshold(
            LOW_BATTERY,
            600,
            EvaluationClock.OBSERVED_RESEARCH_TIME,
            Aggregate.Count,
            NumericComparison(FieldOperator.GTE, "3"),
        )
        val ordered = listOf(
            compile(
                occurrence(
                    Trigger.Sequence(listOf(LOW_BATTERY, LOW_BATTERY), 600, EvaluationClock.OBSERVED_RESEARCH_TIME),
                    guard = null,
                ),
                withBattery = true,
            ),
            compile(
                occurrence(
                    Trigger.WindowThreshold(
                        LOW_BATTERY,
                        600,
                        EvaluationClock.OBSERVED_RESEARCH_TIME,
                        Aggregate.Count,
                        NumericComparison(FieldOperator.GTE, "3"),
                    ),
                    guard = null,
                ),
                withBattery = true,
            ),
            compile(
                occurrence(
                    Trigger.EventMatch(FAST_ROTATION, EvaluationClock.OBSERVED_RESEARCH_TIME),
                    guard = StateCondition.Not(lowBatteryWindow),
                ),
                withBattery = true,
            ),
            compile(
                accelerometerBinding(
                    StateCondition.All(
                        listOf(
                            StateCondition.StudySessionActive,
                            StateCondition.HeldFor(lowBatteryWindow, 60, DurationClock.ACTIVE_RUNNING_TIME),
                        ),
                    ),
                ),
                withBattery = true,
            ),
        )
        ordered.forEach { program ->
            assertTrue(program.retainsEventTimeOrderedState)
            // No matcher names the accelerometer, but the window or sequence reads its samples' times.
            assertFalse(program.referencesSource(ACCELEROMETER))
        }
    }

    private fun compile(vararg extra: AutomationDefinition, withBattery: Boolean = false): CompiledAutomationProgram =
        when (val result = AutomationCompiler(GeneratedEventContractRegistry).compile(input(*extra, withBattery = withBattery))) {
            is CompilationResult.Success -> result.program
            is CompilationResult.Failure -> error("Compilation failed: ${result.issues}")
        }

    private fun input(vararg extra: AutomationDefinition, withBattery: Boolean = false): AutomationCompilerInput {
        val occurrences = extra.filterIsInstance<OccurrenceAutomation>()
        val accelerometer = extra.filterIsInstance<ResourceBindingAutomation>().singleOrNull()
            ?: continuousBinding("accelerometer-binding", ACCELEROMETER_KEY)
        return AutomationCompilerInput(
            configurationSha256 = "a".repeat(64),
            studyDurationSeconds = 3_600,
            resources = listOfNotNull(
                DeclaredResource(ACCELEROMETER_KEY, true, mapOf("continuous" to "b".repeat(64))),
                DeclaredResource(BATTERY_KEY, true, mapOf("continuous" to "d".repeat(64))).takeIf { withBattery },
                DeclaredResource(GYROSCOPE_KEY, true, mapOf("continuous" to "c".repeat(64))),
            ),
            interventions = if (occurrences.isEmpty()) emptyList() else listOf(InterventionDefinition("prompt", required = false)),
            automations = (
                occurrences + accelerometer + continuousBinding("gyroscope-binding", GYROSCOPE_KEY) +
                    listOfNotNull(continuousBinding("battery-binding", BATTERY_KEY).takeIf { withBattery })
                ).sortedBy { it.id },
        )
    }

    private fun occurrence(trigger: Trigger, guard: StateCondition?) = OccurrenceAutomation(
        id = "notify-motion",
        trigger = trigger,
        guard = guard,
        interventionId = "prompt",
        availabilitySeconds = 300,
        cooldown = null,
        maximumActivations = 1,
    )

    private fun accelerometerBinding(condition: StateCondition) = ResourceBindingAutomation(
        "accelerometer-binding",
        ACCELEROMETER_KEY,
        listOf(
            ResourceConditionCase(StateCondition.StudySessionActive, "continuous"),
            ResourceConditionCase(condition, "continuous"),
        ),
        "continuous",
    )

    private fun continuousBinding(id: String, key: ResourceKey) = ResourceBindingAutomation(
        id,
        key,
        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
        "continuous",
    )

    private companion object {
        val GYROSCOPE = EventSourceId("gyroscope.v1")
        val ACCELEROMETER = EventSourceId("accelerometer.v1")
        val GYROSCOPE_KEY = ResourceKey(ResourceKind.COLLECTOR, GYROSCOPE.value)
        val ACCELEROMETER_KEY = ResourceKey(ResourceKind.COLLECTOR, ACCELEROMETER.value)
        val GYROSCOPE_SAMPLE = EventTypeKey(GYROSCOPE, 1, "GYROSCOPE_SAMPLE")
        val ACCELEROMETER_SAMPLE = EventTypeKey(ACCELEROMETER, 1, "ACCELEROMETER_SAMPLE")
        val BATTERY_KEY = ResourceKey(ResourceKind.COLLECTOR, "battery_state.v1")
        val LOW_BATTERY = EventMatcher(
            EventTypeKey(EventSourceId("battery_state.v1"), 1, "BATTERY_STATE"),
            listOf(FieldPredicate("percentage", FieldOperator.LT, value = "20")),
        )
        val FAST_ROTATION = EventMatcher(
            GYROSCOPE_SAMPLE,
            listOf(FieldPredicate("x_radians_per_second", FieldOperator.GT, value = "3.0")),
        )
        val SLOW_ROTATION = EventMatcher(
            GYROSCOPE_SAMPLE,
            listOf(FieldPredicate("x_radians_per_second", FieldOperator.LT, value = "0.5")),
        )
    }
}
