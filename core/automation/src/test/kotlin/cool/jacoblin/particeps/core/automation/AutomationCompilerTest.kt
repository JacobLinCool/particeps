package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.*
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCompilerTest {
    private val usageEvent = EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_RESUMED")
    private val usageContract = EventTypeContract(
        key = usageEvent,
        sourceKind = EventSourceKind.COLLECTOR,
        fields = mapOf(
            "package_name" to FieldContract(
                type = ScalarType.STRING,
                operators = setOf(FieldOperator.EQ, FieldOperator.NE, FieldOperator.IN),
            ),
            "duration_ms" to FieldContract(
                type = ScalarType.INTEGER,
                operators = setOf(
                    FieldOperator.EQ,
                    FieldOperator.NE,
                    FieldOperator.LT,
                    FieldOperator.LTE,
                    FieldOperator.GT,
                    FieldOperator.GTE,
                ),
                minimumInteger = BigInteger.ZERO,
                windowSumAllowed = true,
            ),
        ),
        triggerScope = TriggerScope.RESEARCHER,
        deliveryMode = DeliveryMode.RETROSPECTIVE,
        clockSupport = setOf(
            EventClockSupport.OBSERVED_RESEARCH_TIME,
            EventClockSupport.PRIMARY_SOURCE_TIME,
        ),
        conditionKinds = setOf(
            EventConditionKind.EVENT_MATCH,
            EventConditionKind.WINDOW_COUNT,
            EventConditionKind.WINDOW_SUM,
        ),
        presence = null,
        rateBound = EventRateBound(4, 15),
    )

    @Test
    fun compilesClosedWorldOccurrenceAndRequiredLiveSource() {
        val result = compiler(usageContract).compile(validConfiguration())
        assertTrue(result is CompilationResult.Success)
        val program = (result as CompilationResult.Success).program
        assertEquals(listOf("prompt-after-use"), program.occurrenceAutomations.map { it.id })
        assertEquals(listOf("bind-usage"), program.resourceBindings.map { it.id })
    }

    @Test
    fun studySessionActiveCaseKeepsRequiredResourceLiveWithoutDefault() {
        val base = validConfiguration()
        val binding = base.automations.filterIsInstance<ResourceBindingAutomation>().single()
        val activeOnly = base.copy(
            automations = base.automations.map { automation ->
                if (automation == binding) binding.copy(defaultProfileId = null) else automation
            },
        )
        assertTrue(compiler(usageContract).compile(activeOnly) is CompilationResult.Success)

        val nullablePredecessor = activeOnly.copy(
            automations = activeOnly.automations.map { automation ->
                if (automation != binding.copy(defaultProfileId = null)) {
                    automation
                } else {
                    binding.copy(
                        cases = listOf(
                            ResourceConditionCase(
                                StateCondition.ElapsedAtLeast(1, DurationClock.ACTIVE_RUNNING_TIME),
                                null,
                            ),
                            ResourceConditionCase(StateCondition.StudySessionActive, "live"),
                        ),
                        defaultProfileId = null,
                    )
                }
            },
        )
        assertTrue(
            "REQUIRED_RESOURCE_CAN_BE_INACTIVE" in
                failureCodes(compiler(usageContract).compile(nullablePredecessor)),
        )
    }

    @Test
    fun rejectsMissingFieldSemanticsAndUnsortedInValuesBeforeRuntime() {
        val invalid = validConfiguration().copy(
            automations = listOf(
                validConfiguration().automations.first(),
                occurrence(
                    Trigger.EventMatch(
                        EventMatcher(
                            usageEvent,
                            listOf(
                                FieldPredicate(
                                    "package_name",
                                    FieldOperator.IN,
                                    values = listOf("z.example", "a.example"),
                                ),
                            ),
                        ),
                        EvaluationClock.OBSERVED_RESEARCH_TIME,
                    ),
                ),
            ),
        )
        val codes = failureCodes(compiler(usageContract).compile(invalid))
        assertTrue("UNSORTED_OR_DUPLICATE_IN_VALUES" in codes)
    }

    @Test
    fun rejectsRuntimeEventsAndUnboundedWindowState() {
        val runtimeOnly = usageContract.copy(
            fields = usageContract.fields.mapValues { (_, field) ->
                field.copy(operators = emptySet(), keyedPresenceKey = false, windowSumAllowed = false)
            },
            triggerScope = TriggerScope.RUNTIME_ONLY,
            conditionKinds = emptySet(),
            rateBound = null,
        )
        val invalid = validConfiguration().copy(
            automations = listOf(
                validConfiguration().automations.first(),
                occurrence(
                    Trigger.WindowThreshold(
                        EventMatcher(usageEvent),
                        300,
                        EvaluationClock.PRIMARY_SOURCE_TIME,
                        Aggregate.Count,
                        NumericComparison(FieldOperator.GTE, "3"),
                    ),
                ),
            ),
        )
        val codes = failureCodes(compiler(runtimeOnly).compile(invalid))
        assertTrue("NON_TRIGGERABLE_EVENT" in codes)
        assertTrue("UNBOUNDED_SOURCE" in codes)
    }

    @Test
    fun rejectsCollectorResourceDependencyCycles() {
        val firstKey = EventTypeKey(EventSourceId("source_a.v1"), 1, "CHANGED")
        val secondKey = EventTypeKey(EventSourceId("source_b.v1"), 1, "CHANGED")
        val contract = { key: EventTypeKey ->
            EventTypeContract(
                key,
                EventSourceKind.COLLECTOR,
                emptyMap(),
                TriggerScope.RESEARCHER,
                DeliveryMode.LIVE,
                setOf(EventClockSupport.OBSERVED_RESEARCH_TIME),
                setOf(EventConditionKind.EVENT_MATCH),
                null,
                EventRateBound(1, 1),
            )
        }
        val firstResource = DeclaredResource(
            ResourceKey(ResourceKind.COLLECTOR, "source_a.v1"),
            required = true,
            linkedMapOf("live" to DIGEST_A),
        )
        val secondResource = DeclaredResource(
            ResourceKey(ResourceKind.COLLECTOR, "source_b.v1"),
            required = true,
            linkedMapOf("live" to DIGEST_B),
        )
        val configuration = AutomationCompilerInput(
            CONFIG_DIGEST,
            3_600,
            listOf(firstResource, secondResource),
            emptyList(),
            listOf(
                ResourceBindingAutomation(
                    "bind-source-a",
                    firstResource.key,
                    listOf(ResourceConditionCase(StateCondition.EventLatch(listOf(EventMatcher(secondKey)), listOf(EventMatcher(secondKey))), "live")),
                    "live",
                ),
                ResourceBindingAutomation(
                    "bind-source-b",
                    secondResource.key,
                    listOf(ResourceConditionCase(StateCondition.EventLatch(listOf(EventMatcher(firstKey)), listOf(EventMatcher(firstKey))), "live")),
                    "live",
                ),
            ),
        )
        val codes = failureCodes(compiler(contract(firstKey), contract(secondKey)).compile(configuration))
        assertTrue("RESOURCE_DEPENDENCY_CYCLE" in codes)
    }

    @Test
    fun validatesRandomWindowCapacityAndWraparoundSeparation() {
        val configuration = AutomationCompilerInput(
            CONFIG_DIGEST,
            86_400,
            emptyList(),
            listOf(InterventionDefinition("check-in", required = true)),
            listOf(
                occurrence(
                    Trigger.Schedule(
                        AutomationSchedule.RandomWindow(
                            localWindows = listOf(
                                LocalTimeWindow("09:00", "09:10"),
                                LocalTimeWindow("09:10", "09:20"),
                            ),
                            occurrencesPerWindow = 2,
                            maximumOccurrencesPerDay = 4,
                            maximumOccurrencesTotal = 4,
                            minimumSeparationMinutes = 10,
                        ),
                    ),
                ),
            ),
        )
        val codes = failureCodes(compiler().compile(configuration))
        assertTrue("WINDOW_TOO_NARROW" in codes)
        assertTrue("WINDOWS_TOO_CLOSE" in codes)
    }

    @Test
    fun exactIntegerWindowSumSupportsValuesBeyondLong() {
        val configuration = validConfiguration().copy(
            automations = listOf(
                validConfiguration().automations.first(),
                occurrence(
                    Trigger.WindowThreshold(
                        EventMatcher(usageEvent),
                        300,
                        EvaluationClock.PRIMARY_SOURCE_TIME,
                        Aggregate.Sum("duration_ms"),
                        NumericComparison(FieldOperator.GTE, "18446744073709551616"),
                    ),
                ),
            ),
        )
        assertTrue(compiler(usageContract).compile(configuration) is CompilationResult.Success)

        val forbidden = usageContract.copy(
            fields = usageContract.fields + (
                "duration_ms" to requireNotNull(usageContract.fields["duration_ms"]).copy(windowSumAllowed = false)
                ),
        )
        val codes = failureCodes(compiler(forbidden).compile(configuration))
        assertTrue("INVALID_SUM_FIELD" in codes)
    }

    @Test
    fun acceptsSixtyFourNamedProfilesAndRejectsSixtyFive() {
        fun configuration(profileCount: Int): AutomationCompilerInput {
            val profiles = (0 until profileCount).associateTo(linkedMapOf()) { index ->
                "profile-${index.toString().padStart(2, '0')}" to DIGEST_A
            }
            val resource = DeclaredResource(
                ResourceKey(ResourceKind.COLLECTOR, "usage_events.v1"),
                required = true,
                profiles,
            )
            return AutomationCompilerInput(
                CONFIG_DIGEST,
                3_600,
                listOf(resource),
                emptyList(),
                listOf(
                    ResourceBindingAutomation(
                        "bind-usage",
                        resource.key,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "profile-00")),
                        "profile-00",
                    ),
                ),
            )
        }

        assertTrue(compiler().compile(configuration(64)) is CompilationResult.Success)
        val codes = failureCodes(compiler().compile(configuration(65)))
        assertTrue("INVALID_PROFILE_COUNT" in codes)
    }

    private fun validConfiguration(): AutomationCompilerInput {
        val resource = DeclaredResource(
            ResourceKey(ResourceKind.COLLECTOR, "usage_events.v1"),
            required = true,
            linkedMapOf("live" to DIGEST_A),
        )
        return AutomationCompilerInput(
            CONFIG_DIGEST,
            3_600,
            listOf(resource),
            listOf(InterventionDefinition("check-in", required = true)),
            listOf(
                ResourceBindingAutomation(
                    "bind-usage",
                    resource.key,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "live")),
                    "live",
                ),
                occurrence(
                    Trigger.EventMatch(
                        EventMatcher(
                            usageEvent,
                            listOf(FieldPredicate("package_name", FieldOperator.EQ, value = "com.example.target")),
                        ),
                        EvaluationClock.PRIMARY_SOURCE_TIME,
                    ),
                ),
            ),
        )
    }

    private fun occurrence(trigger: Trigger) = OccurrenceAutomation(
        id = "prompt-after-use",
        trigger = trigger,
        guard = null,
        interventionId = "check-in",
        availabilitySeconds = 900,
        cooldown = null,
        maximumActivations = 10,
    )

    private fun compiler(vararg contracts: EventTypeContract) = AutomationCompiler(
        EventContractRegistry { key -> contracts.singleOrNull { it.key == key } },
    )

    private fun failureCodes(result: CompilationResult): Set<String> {
        assertTrue(result is CompilationResult.Failure)
        return (result as CompilationResult.Failure).issues.mapTo(mutableSetOf(), ValidationIssue::code)
    }

    private companion object {
        const val CONFIG_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        const val DIGEST_A = "1111111111111111111111111111111111111111111111111111111111111111"
        const val DIGEST_B = "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
