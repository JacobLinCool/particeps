package particeps.conformance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.AutomationCheckpointCodec
import cool.jacoblin.particeps.core.automation.AutomationCompiler
import cool.jacoblin.particeps.core.automation.AutomationEvent
import cool.jacoblin.particeps.core.automation.AutomationReducer
import cool.jacoblin.particeps.core.automation.CompilationResult
import cool.jacoblin.particeps.core.automation.DeliveryMode
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.EventClockSupport
import cool.jacoblin.particeps.core.automation.EventConditionKind
import cool.jacoblin.particeps.core.automation.EventContractRegistry
import cool.jacoblin.particeps.core.automation.EventPresenceContract
import cool.jacoblin.particeps.core.automation.EventPresenceRole
import cool.jacoblin.particeps.core.automation.EventRateBound
import cool.jacoblin.particeps.core.automation.EventSourceKind
import cool.jacoblin.particeps.core.automation.EventTypeContract
import cool.jacoblin.particeps.core.automation.FieldContract
import cool.jacoblin.particeps.core.automation.ReducerClock
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.ScalarType
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.automation.TriggerScope
import cool.jacoblin.particeps.core.automation.toAutomationCompilerInput
import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.AutomationDefinition
import cool.jacoblin.particeps.core.definition.AutomationSchedule
import cool.jacoblin.particeps.core.definition.DeclaredResource
import cool.jacoblin.particeps.core.definition.DurationClock
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.EvaluationClock
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.RegistryDeliveryKind
import cool.jacoblin.particeps.core.collector.RegistryFieldWireType
import cool.jacoblin.particeps.core.collector.RegistryRateKind
import cool.jacoblin.particeps.core.collector.RegistrySourceKind
import cool.jacoblin.particeps.core.collector.RegistryTriggerScope
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.InterventionDefinition
import cool.jacoblin.particeps.core.definition.LocalTimeWindow
import cool.jacoblin.particeps.core.definition.OccurrenceAutomation
import cool.jacoblin.particeps.core.definition.ProtocolCanonicalJson
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.ResourceConditionCase
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.definition.Trigger
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationReducerConformanceTest {
    private val corpus: JsonObject by lazy {
        val root = requireNotNull(System.getProperty("particeps.repository.root"))
        JsonParser.parseString(File(root, "protocol/v1/automation-reducer-vectors.json").readText()).asJsonObject
    }
    private val reducer = AutomationReducer()

    @Test
    fun everyInputMatchesTheSharedCheckpointAndOutputCorpus() {
        assertEquals("particeps-automation-reducer-v1", corpus.string("format"))
        assertEquals(ProtocolEventSourceRegistry.REGISTRY_SHA256, corpus.string("registry_sha256"))
        assertEquals(
            "Each scenario step is one indivisible SourceObservation or EngineCommit reducer batch.",
            corpus.objectAt("batch_semantics").string("step_boundary"),
        )
        corpus.array("scenarios").forEach { scenarioElement ->
            val scenario = scenarioElement.asJsonObject
            val digest = scenario.string("configuration_sha256")
            val configurationBytes = ProtocolCanonicalJson.encode(scenario.objectAt("configuration"))
            val configuration = StudyConfigurationCodec.decode(configurationBytes)
            val compilation = AutomationCompiler(GeneratedRegistry).compile(configuration.toAutomationCompilerInput(digest))
            assertTrue("${scenario.string("id")} failed to compile: $compilation", compilation is CompilationResult.Success)
            val program = (compilation as CompilationResult.Success).program
            var checkpoint = AutomationCheckpoint()
            val inputs = scenario.array("steps").map { stepElement -> parseInput(stepElement.asJsonObject.objectAt("input")) }
            scenario.array("steps").forEachIndexed { index, stepElement ->
                val expected = stepElement.asJsonObject.objectAt("expected")
                val result = reducer.reduceBatch(program, checkpoint, listOf(inputs[index]))
                checkpoint = result.checkpoint
                assertEquals("${scenario.string("id")} checkpoint ${index + 1}", expected.string("checkpoint"), AutomationCheckpointCodec.encode(checkpoint))
                assertEquals("${scenario.string("id")} digest ${index + 1}", expected.string("checkpoint_sha256"), checkpoint.digest())
                assertEquals(checkpoint, AutomationCheckpointCodec.decode(expected.string("checkpoint")))
                assertActions(expected.array("actions"), result.actionRequests)
                assertTimerIntents(expected.array("timer_intents"), result.timerIntents)
                assertProduction(expected.array("timer_production_requests"), result.timerProductionRequests)
                assertResources(expected.array("resource_changes"), result.resourceChanges)
                assertAudits(expected.array("audits"), result.audits)
            }
            assertEquals(scenario.string("final_checkpoint_sha256"), checkpoint.digest())
            scenario.array("stream_partition_ranges").forEach { rangeElement ->
                val range = rangeElement.asJsonObject
                val first = range.int("first_step") - 1
                val last = range.int("last_step")
                var boundary = AutomationCheckpoint()
                inputs.take(first).forEach { input ->
                    boundary = reducer.reduceBatch(program, boundary, listOf(input)).checkpoint
                }
                var expected = boundary
                inputs.subList(first, last).forEach { input ->
                    expected = reducer.reduceBatch(program, expected, listOf(input)).checkpoint
                }
                (first + 1 until last).forEach { split ->
                    var actual = boundary
                    listOf(inputs.subList(first, split), inputs.subList(split, last)).forEach { transportChunk ->
                        transportChunk.forEach { atomicInput ->
                            actual = reducer.reduceBatch(program, actual, listOf(atomicInput)).checkpoint
                        }
                    }
                    assertEquals(
                        "${scenario.string("id")} transport partition after atomic step $split",
                        expected.digest(),
                        actual.digest(),
                    )
                }
            }
        }
    }

    @Test
    fun sharedAtomicBatchDoesNotAllocateAnUnappliedResourceGeneration() {
        assertEquals(1, corpus.array("atomic_batch_cases").size())
        val vector = corpus.array("atomic_batch_cases").single().asJsonObject
        assertEquals("UNCHANGED", vector.string("expected_desired_resource_relation"))
        val scenario = corpus.array("scenarios").map(JsonElement::getAsJsonObject)
            .single { it.string("id") == vector.string("scenario_id") }
        val configuration = StudyConfigurationCodec.decode(
            ProtocolCanonicalJson.encode(scenario.objectAt("configuration")),
        )
        val compilation = AutomationCompiler(GeneratedRegistry).compile(
            configuration.toAutomationCompilerInput(scenario.string("configuration_sha256")),
        ) as CompilationResult.Success
        val scenarioInputs = scenario.array("steps").map { parseInput(it.asJsonObject.objectAt("input")) }
        var checkpoint = AutomationCheckpoint()
        scenarioInputs.take(vector.int("base_checkpoint_after_step")).forEach { input ->
            checkpoint = reducer.reduceBatch(compilation.program, checkpoint, listOf(input)).checkpoint
        }
        val resource = vector.objectAt("resource")
        val key = ResourceKey(ResourceKind.valueOf(resource.string("kind")), resource.string("id"))
        val before = checkpoint.desiredResources[key]
        val batch = vector.array("inputs").map { recipeElement ->
            val recipe = recipeElement.asJsonObject
            val sequence = recipe.long("sequence_number")
            val source = scenarioInputs[recipe.int("source_step") - 1] as? ReducerInput.Event
                ?: error("Atomic batch recipes must reference event inputs")
            source.copy(sequenceNumber = sequence, event = source.event.copy(sequenceNumber = sequence))
        }
        val result = reducer.reduceBatch(compilation.program, checkpoint, batch)
        assertEquals(vector.array("expected_resource_changes").size(), result.resourceChanges.size)
        assertEquals(before, result.checkpoint.desiredResources[key])
    }

    @Test
    fun sharedCooldownPropertyUsesActiveRunningTime() {
        assertEquals(1, corpus.array("reducer_property_cases").size())
        val vector = corpus.array("reducer_property_cases").single().asJsonObject
        assertEquals("SET_OCC_EVENT_MAXIMUM_ACTIVATIONS_2", vector.string("mutation"))
        val scenario = corpus.array("scenarios").map(JsonElement::getAsJsonObject)
            .single { it.string("id") == vector.string("scenario_id") }
        val decoded = StudyConfigurationCodec.decode(
            ProtocolCanonicalJson.encode(scenario.objectAt("configuration")),
        )
        val configuration = decoded.copy(
            automations = decoded.automations.map { automation ->
                if (automation.id == vector.string("expected_automation_id")) {
                    (automation as OccurrenceAutomation).copy(maximumActivations = 2)
                } else {
                    automation
                }
            },
        )
        val compilation = AutomationCompiler(GeneratedRegistry).compile(
            configuration.toAutomationCompilerInput("0".repeat(64)),
        ) as CompilationResult.Success
        var checkpoint = AutomationCheckpoint()
        var actionCount = 0
        var suppression: cool.jacoblin.particeps.core.automation.AutomationAudit? = null
        scenario.array("steps").take(vector.int("expected_suppression_step")).forEachIndexed { index, step ->
            val result = reducer.reduceBatch(
                compilation.program,
                checkpoint,
                listOf(parseInput(step.asJsonObject.objectAt("input"))),
            )
            checkpoint = result.checkpoint
            if (index + 1 == vector.int("expected_action_step")) actionCount = result.actionRequests.size
            if (index + 1 == vector.int("expected_suppression_step")) {
                suppression = result.audits.single { it.automationId == vector.string("expected_automation_id") }
            }
        }
        assertEquals(vector.int("expected_action_count_at_step"), actionCount)
        assertEquals(vector.string("expected_suppression_reason"), suppression?.suppressionReason?.name)
    }

    @Test
    fun sharedCompilerHostilesAreRejected() {
        val expectedIds = setOf(
            "combined-trigger-guard-condition-node-overflow",
            "global-concurrent-timer-overflow",
            "lifecycle-audit-event-match",
            "presence-condition-kind-mismatch",
            "presence-group-mismatch",
            "presence-key-mismatch",
            "presence-role-inversion",
            "random-window-daily-capacity-overflow",
            "random-window-adjacent-separation-violation",
            "random-window-cyclic-separation-violation",
            "utf16-astral-title-overflow",
            "sixty-five-stateful-resources",
        )
        val actualIds = corpus.array("compiler_hostile_cases").map { it.asJsonObject.string("id") }.toSet()
        assertEquals(expectedIds, actualIds)
        corpus.array("compiler_hostile_cases").forEach { element ->
            val vector = element.asJsonObject
            val mutation = vector.string("mutation")
            if (mutation == "SET_61_ASTRAL_TITLE") {
                val scenario = corpus.array("scenarios").map(JsonElement::getAsJsonObject)
                    .single { it.string("id") == vector.string("base_scenario_id") }
                val configuration = scenario.objectAt("configuration").deepCopy()
                configuration.addProperty("title", "😀".repeat(61))
                assertTrue(
                    vector.string("id"),
                    runCatching { StudyConfigurationCodec.decode(ProtocolCanonicalJson.encode(configuration)) }.isFailure,
                )
            } else {
                val (input, expectedCode) = hostileCompilerInput(mutation)
                val registry = if (mutation == "ALTER_EXIT_PRESENCE_GROUP_CONTRACT") {
                    val paused = EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_PAUSED")
                    EventContractRegistry { key ->
                        GeneratedRegistry.contract(key)?.let { contract ->
                            if (key != paused) contract else contract.copy(
                                presence = requireNotNull(contract.presence).copy(
                                    groupId = "different_presence_group",
                                ),
                            )
                        }
                    }
                } else {
                    GeneratedRegistry
                }
                val result = AutomationCompiler(registry).compile(input)
                assertTrue("${vector.string("id")} unexpectedly compiled", result is CompilationResult.Failure)
                val codes = (result as CompilationResult.Failure).issues.mapTo(mutableSetOf()) { it.code }
                assertTrue("${vector.string("id")} did not report $expectedCode: $codes", expectedCode in codes)
            }
        }
    }

    @Test
    fun sharedScheduleMaterializationHostilesAreRejected() {
        val scenarios = corpus.array("scenarios").associate { element ->
            val value = element.asJsonObject
            value.string("id") to value
        }
        corpus.array("reducer_hostile_cases").forEach { element ->
            val vector = element.asJsonObject
            val scenario = requireNotNull(scenarios[vector.string("scenario_id")])
            val digest = scenario.string("configuration_sha256")
            val configuration = StudyConfigurationCodec.decode(
                ProtocolCanonicalJson.encode(scenario.objectAt("configuration")),
            )
            val compilation = AutomationCompiler(GeneratedRegistry).compile(
                configuration.toAutomationCompilerInput(digest),
            ) as CompilationResult.Success
            val inputs = scenario.array("steps").map { parseInput(it.asJsonObject.objectAt("input")) }
            val stepIndex = vector.int("step") - 1
            var checkpoint = AutomationCheckpoint()
            inputs.take(stepIndex).forEach { input ->
                checkpoint = reducer.reduceBatch(compilation.program, checkpoint, listOf(input)).checkpoint
            }
            val hostile = mutateTimerMaterialization(inputs[stepIndex], vector.string("mutation"))
            assertTrue(
                vector.string("id"),
                runCatching { reducer.reduceBatch(compilation.program, checkpoint, listOf(hostile)) }.isFailure,
            )
        }
    }

    @Test
    fun clockDiscontinuityCarriesTheDurableZoneChange() {
        val scenario = corpus.array("scenarios").map(JsonElement::getAsJsonObject)
            .single { it.string("id") == "conditions-resources-and-resets" }
        val steps = scenario.array("steps").map(JsonElement::getAsJsonObject)
        val index = steps.indexOfFirst { it.objectAt("input").string("type") == "CLOCK_DISCONTINUITY" }
        assertTrue(index > 0)
        val previousZone = steps[index - 1].objectAt("input").objectAt("clock").string("zone_id")
        val changedZone = steps[index].objectAt("input").objectAt("clock").string("zone_id")
        val nextZone = steps[index + 1].objectAt("input").objectAt("clock").string("zone_id")
        assertTrue(previousZone != changedZone)
        assertEquals(changedZone, nextZone)
    }

    private fun parseInput(root: JsonObject): ReducerInput {
        val sequence = root.long("sequence_number")
        val clock = parseClock(root.objectAt("clock"))
        return when (root.string("type")) {
            "EVENT" -> {
                val value = root.objectAt("event")
                ReducerInput.Event(
                    sequence,
                    clock,
                    AutomationEvent(
                        value.long("sequence_number"),
                        EventTypeKey(
                            EventSourceId(value.string("source_id")),
                            value.int("schema_version"),
                            value.string("event_type"),
                        ),
                        parseTime(value.objectAt("observed_time")),
                        value.get("primary_source_time").takeUnless(JsonElement::isJsonNull)?.asJsonObject?.let(::parseTime),
                        value.objectAt("fields").entrySet().associate { (key, field) -> key to field.asString },
                    ),
                )
            }
            "LIFECYCLE" -> ReducerInput.Lifecycle(sequence, clock, StudySessionState.valueOf(root.string("state")))
            "TIMER_DUE" -> ReducerInput.TimerDue(
                sequence,
                clock,
                root.string("timer_id"),
                root.string("automation_id"),
                root.string("generation").toULong(),
                root.long("causal_sequence"),
                parseTarget(root.objectAt("target")),
                parseTime(root.objectAt("logical_due")),
            )
            "TIMER_MATERIALIZED" -> ReducerInput.TimerMaterialized(sequence, clock, parseTimer(root.objectAt("timer")))
            "QUALITY_GAP" -> ReducerInput.QualityGap(sequence, clock, EventSourceId(root.string("source_id")))
            "CLOCK_DISCONTINUITY" -> ReducerInput.ClockDiscontinuity(
                sequence,
                clock,
                root.array("restart_resources").map { resource ->
                    val key = resource.asJsonObject
                    ResourceKey(ResourceKind.valueOf(key.string("kind")), key.string("id"))
                }.toSet(),
            )
            else -> error("Unknown reducer input")
        }
    }

    private fun parseClock(root: JsonObject): ReducerClock = ReducerClock(
        parseTime(root.objectAt("now")),
        root.string("active_elapsed_nanos").toLong(),
        root.string("calendar_elapsed_nanos").toLong(),
        root.string("zone_id"),
    )

    private fun parseTime(root: JsonObject): ResearchTime = ResearchTime(
        root.long("wall_time_utc_millis"),
        root.string("elapsed_realtime_nanos").toLong(),
        root.string("boot_session_id"),
    )

    private fun parseTarget(root: JsonObject): TimerTarget = when (root.string("type")) {
        "CALENDAR_UTC" -> TimerTarget.CalendarUtc(root.long("utc_millis"))
        "ACTIVE_ELAPSED" -> TimerTarget.ActiveElapsed(root.string("elapsed_nanos").toLong())
        "SAME_BOOT_MONOTONIC" -> TimerTarget.SameBootMonotonic(
            root.string("boot_session_id"),
            root.string("elapsed_realtime_nanos").toLong(),
        )
        else -> error("Unknown timer target")
    }

    private fun parseTimer(root: JsonObject): DurableTimer = DurableTimer(
        root.string("id"),
        root.string("automation_id"),
        root.string("generation").toULong(),
        root.long("causal_sequence"),
        root.string("producer_key"),
        parseTarget(root.objectAt("target")),
        root.nullableLong("logical_deadline_utc_millis"),
        root.nullableLong("expires_at_utc_millis"),
    )

    private fun mutateTimerMaterialization(input: ReducerInput, mutation: String): ReducerInput.TimerMaterialized {
        val materialized = input as? ReducerInput.TimerMaterialized
            ?: error("Hostile materialization must reference TIMER_MATERIALIZED")
        val timer = materialized.timer
        return when {
            mutation.startsWith("SHIFT_TIMER_TARGET_UTC_BY_") -> {
                val delta = mutation.removePrefix("SHIFT_TIMER_TARGET_UTC_BY_").toLong()
                val target = timer.target as? TimerTarget.CalendarUtc
                    ?: error("Hostile target shift requires a calendar timer")
                materialized.copy(timer = timer.copy(target = target.copy(utcMillis = target.utcMillis + delta)))
            }
            mutation == "INCREMENT_TIMER_GENERATION" ->
                materialized.copy(timer = timer.copy(generation = timer.generation + 1uL))
            else -> error("Unknown reducer hostile mutation: $mutation")
        }
    }

    private fun hostileCompilerInput(mutation: String): Pair<AutomationCompilerInput, String> {
        fun occurrence(
            id: String,
            trigger: Trigger,
            guard: StateCondition? = null,
        ): OccurrenceAutomation = OccurrenceAutomation(
            id,
            trigger,
            guard,
            "$id-intervention",
            60,
            null,
            1,
        )

        fun input(automations: List<OccurrenceAutomation>): AutomationCompilerInput = AutomationCompilerInput(
            "0".repeat(64),
            86_400,
            emptyList(),
            automations.map { InterventionDefinition(it.interventionId, false) }.sortedBy { it.id },
            automations.sortedBy(AutomationDefinition::id),
        )

        fun presenceInput(mutation: String): AutomationCompilerInput {
            val resumed = EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_RESUMED")
            val paused = EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_PAUSED")
            val nonPresence = EventTypeKey(EventSourceId("usage_events.v1"), 1, "DEVICE_STARTUP")
            val enterEvent = when (mutation) {
                "SWAP_PRESENCE_ENTER_EXIT" -> paused
                "USE_NON_PRESENCE_ENTER_EVENT" -> nonPresence
                else -> resumed
            }
            val exitEvent = if (mutation == "SWAP_PRESENCE_ENTER_EXIT") resumed else paused
            val keyField = if (mutation == "SET_PRESENCE_KEY_TO_PACKAGE_NAME") {
                "package_name"
            } else {
                "activity_component_token"
            }
            val resource = DeclaredResource(
                ResourceKey(ResourceKind.COLLECTOR, "usage_events.v1"),
                true,
                linkedMapOf("continuous" to "1".repeat(64)),
            )
            val occurrence = occurrence(
                "hostile-presence",
                Trigger.ConditionRisingEdge(
                    StateCondition.KeyedPresence(
                        listOf(EventMatcher(enterEvent)),
                        listOf(EventMatcher(exitEvent)),
                        keyField,
                    ),
                ),
            )
            return AutomationCompilerInput(
                "0".repeat(64),
                86_400,
                listOf(resource),
                listOf(InterventionDefinition(occurrence.interventionId, false)),
                listOf(
                    ResourceBindingAutomation(
                        "bind-usage",
                        resource.key,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                        "continuous",
                    ),
                    occurrence,
                ).sortedBy(AutomationDefinition::id),
            )
        }

        return when (mutation) {
            "ADD_DENSE_TRIGGER_AND_GUARD" -> {
                fun dense(): StateCondition = StateCondition.All(
                    List(8) { StateCondition.Any(List(4) { StateCondition.StudySessionActive }) },
                )
                input(listOf(occurrence("hostile-dense", Trigger.ConditionRisingEdge(dense()), dense()))) to
                    "TOO_MANY_CONDITION_NODES"
            }
            "ADD_513_CONCURRENT_TIMERS" -> {
                val automations = List(57) { index ->
                    occurrence(
                        "hostile-timer-${index.toString().padStart(2, '0')}",
                        Trigger.Schedule(AutomationSchedule.OneTime(0, DurationClock.ACTIVE_RUNNING_TIME)),
                        StateCondition.All(
                            List(8) {
                                StateCondition.HeldFor(
                                    StateCondition.StudySessionActive,
                                    1,
                                    DurationClock.ACTIVE_RUNNING_TIME,
                                )
                            },
                        ),
                    )
                }
                input(automations) to "TOO_MANY_TIMERS"
            }
            "USE_AUDIT_ONLY_STUDY_RUNNING_EVENT" -> input(listOf(occurrence(
                "hostile-lifecycle-feedback",
                Trigger.EventMatch(
                    EventMatcher(
                        EventTypeKey(EventSourceId("study_runtime.v1"), 1, "STUDY_RUNNING"),
                    ),
                    EvaluationClock.OBSERVED_RESEARCH_TIME,
                ),
            ))) to "NON_TRIGGERABLE_EVENT"
            "USE_NON_PRESENCE_ENTER_EVENT",
            "SWAP_PRESENCE_ENTER_EXIT",
            -> presenceInput(mutation) to "UNSUPPORTED_CONDITION_KIND"
            "SET_PRESENCE_KEY_TO_PACKAGE_NAME" -> presenceInput(mutation) to "INVALID_PRESENCE_KEY"
            "ALTER_EXIT_PRESENCE_GROUP_CONTRACT" -> presenceInput(mutation) to "INVALID_PRESENCE_CONTRACT"
            "ADD_RANDOM_DAILY_CAPACITY_OVERFLOW" -> input(listOf(occurrence(
                "hostile-random",
                Trigger.Schedule(AutomationSchedule.RandomWindow(
                    listOf(LocalTimeWindow("08:00", "09:00")), 1, 2, 10, 1,
                )),
            ))) to "DAILY_CAP_EXCEEDS_CAPACITY"
            "ADD_RANDOM_ADJACENT_SEPARATION_VIOLATION" -> input(listOf(occurrence(
                "hostile-random",
                Trigger.Schedule(AutomationSchedule.RandomWindow(
                    listOf(LocalTimeWindow("08:00", "09:00"), LocalTimeWindow("09:30", "10:30")),
                    1,
                    2,
                    10,
                    32,
                )),
            ))) to "WINDOWS_TOO_CLOSE"
            "ADD_RANDOM_CYCLIC_SEPARATION_VIOLATION" -> input(listOf(occurrence(
                "hostile-random",
                Trigger.Schedule(AutomationSchedule.RandomWindow(
                    listOf(LocalTimeWindow("00:30", "01:30"), LocalTimeWindow("23:00", "23:30")),
                    1,
                    2,
                    10,
                    62,
                )),
            ))) to "WINDOWS_TOO_CLOSE"
            "DECLARE_64_COLLECTORS_AND_TRAFFIC" -> AutomationCompilerInput(
                "0".repeat(64),
                86_400,
                (List(64) { index ->
                    DeclaredResource(
                        ResourceKey(ResourceKind.COLLECTOR, "synthetic_${index.toString().padStart(2, '0')}.v1"),
                        false,
                        linkedMapOf("continuous" to "1".repeat(64)),
                    )
                } + DeclaredResource(
                    ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1"),
                    true,
                    linkedMapOf("slow" to "2".repeat(64)),
                )).sortedBy(DeclaredResource::key),
                emptyList(),
                emptyList(),
            ) to "TOO_MANY_RESOURCES"
            else -> error("Unknown compiler hostile mutation: $mutation")
        }
    }

    private fun assertActions(expected: JsonArray, actual: List<cool.jacoblin.particeps.core.automation.ActionRequest>) {
        assertEquals(expected.size(), actual.size)
        expected.zip(actual).forEach { (element, value) ->
            val item = element.asJsonObject
            assertEquals(item.string("action_id"), value.actionId)
            assertEquals(item.string("automation_id"), value.automationId)
            assertEquals(item.string("intervention_id"), value.interventionId)
            assertEquals(item.string("causal_identity"), value.causalIdentity)
            assertEquals(item.nullableLong("logical_deadline_utc_millis"), value.logicalDeadlineUtcMillis)
            assertEquals(item.long("expires_at_utc_millis"), value.expiresAtUtcMillis)
        }
    }

    private fun assertTimerIntents(expected: JsonArray, actual: List<TimerIntent>) {
        assertEquals(expected.size(), actual.size)
        expected.zip(actual).forEach { (element, value) ->
            val item = element.asJsonObject
            when (value) {
                is TimerIntent.Schedule -> {
                    assertEquals("SCHEDULE", item.string("type"))
                    assertEquals(parseTimer(item.objectAt("timer")), value.timer)
                }
                is TimerIntent.Retire -> {
                    assertEquals("RETIRE", item.string("type"))
                    assertEquals(item.string("timer_id"), value.timerId)
                    assertEquals(item.string("generation").toULong(), value.generation)
                }
            }
        }
    }

    private fun assertProduction(
        expected: JsonArray,
        actual: List<cool.jacoblin.particeps.core.automation.TimerProductionRequest>,
    ) {
        assertEquals(expected.size(), actual.size)
        expected.zip(actual).forEach { (element, value) ->
            val item = element.asJsonObject
            assertEquals(item.string("automation_id"), value.automation.id)
            assertEquals(item.string("schedule_type"), value.schedule.wireType())
            assertEquals(item.long("causal_sequence"), value.causalSequence)
            assertEquals(item.string("current_generation").toULong(), value.currentGeneration)
            assertEquals(item.nullableString("pending_timer_id"), value.pendingTimer?.id)
            assertEquals(item.array("materialized_producer_keys").map(JsonElement::getAsString), value.materialized.map { it.producerKey })
        }
    }

    private fun assertResources(
        expected: JsonArray,
        actual: Map<cool.jacoblin.particeps.core.resource.ResourceKey, cool.jacoblin.particeps.core.automation.DesiredProfile>,
    ) {
        val sorted = actual.toSortedMap().entries.toList()
        assertEquals(expected.size(), sorted.size)
        expected.zip(sorted).forEach { (element, entry) ->
            val item = element.asJsonObject
            assertEquals(item.string("kind"), entry.key.kind.name)
            assertEquals(item.string("id"), entry.key.id)
            assertEquals(item.string("generation").toULong(), entry.value.generation.value)
            assertEquals(item.nullableString("profile_id"), entry.value.profileId)
        }
    }

    private fun assertAudits(expected: JsonArray, actual: List<cool.jacoblin.particeps.core.automation.AutomationAudit>) {
        assertEquals(expected.size(), actual.size)
        expected.zip(actual).forEach { (element, value) ->
            val item = element.asJsonObject
            assertEquals(item.string("automation_id"), value.automationId)
            assertEquals(item.get("matched").asBoolean, value.matched)
            assertEquals(item.nullableString("suppression_reason"), value.suppressionReason?.name)
            assertEquals(item.string("causal_identity"), value.causalIdentity)
        }
    }

    private fun cool.jacoblin.particeps.core.definition.AutomationSchedule.wireType(): String = when (this) {
        is cool.jacoblin.particeps.core.definition.AutomationSchedule.OneTime -> "one_time"
        is cool.jacoblin.particeps.core.definition.AutomationSchedule.Interval -> "interval"
        is cool.jacoblin.particeps.core.definition.AutomationSchedule.DailyLocal -> "daily_local"
        is cool.jacoblin.particeps.core.definition.AutomationSchedule.RandomWindow -> "random_window"
    }

    private fun JsonObject.objectAt(name: String): JsonObject = getAsJsonObject(name)
    private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name)
    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.int(name: String): Int = get(name).asInt
    private fun JsonObject.long(name: String): Long = get(name).asLong
    private fun JsonObject.nullableLong(name: String): Long? = get(name).takeUnless(JsonElement::isJsonNull)?.asLong
    private fun JsonObject.nullableString(name: String): String? = get(name).takeUnless(JsonElement::isJsonNull)?.asString
}

private object GeneratedRegistry : EventContractRegistry {
    override fun contract(key: EventTypeKey): EventTypeContract? {
        val source = ProtocolEventSourceRegistry[key.sourceId.value] ?: return null
        if (source.schemaVersion != key.schemaVersion) return null
        val event = source.events[key.eventType] ?: return null
        return EventTypeContract(
            key,
            when (source.sourceKind) {
                RegistrySourceKind.COLLECTOR -> EventSourceKind.COLLECTOR
                RegistrySourceKind.SYSTEM -> EventSourceKind.SYSTEM
            },
            event.fields.mapValues { (_, field) ->
                val scalar = when (field.wireType) {
                    RegistryFieldWireType.BOOLEAN -> ScalarType.BOOLEAN
                    RegistryFieldWireType.ENUM -> ScalarType.ENUM
                    RegistryFieldWireType.FLOAT32, RegistryFieldWireType.FLOAT64 -> ScalarType.FLOAT
                    RegistryFieldWireType.INT32, RegistryFieldWireType.INT64_DECIMAL,
                    RegistryFieldWireType.UINT64_DECIMAL,
                    -> ScalarType.INTEGER
                    RegistryFieldWireType.SHA256_HEX -> ScalarType.SHA256
                    RegistryFieldWireType.UUID -> ScalarType.UUID
                    RegistryFieldWireType.JSON_STRING, RegistryFieldWireType.STRING -> ScalarType.STRING
                }
                FieldContract(
                    scalar,
                    field.operators.mapTo(linkedSetOf()) { FieldOperator.valueOf(it.name) },
                    field.required,
                    field.keyedPresenceKey,
                    field.windowSum,
                    field.enumValues,
                    field.minimum.takeIf { scalar == ScalarType.INTEGER },
                    field.maximum.takeIf { scalar == ScalarType.INTEGER },
                    field.minimum?.toDouble().takeIf { scalar == ScalarType.FLOAT },
                    field.maximum?.toDouble().takeIf { scalar == ScalarType.FLOAT },
                    event.maximumEncodedEventBytes.coerceAtMost(60 * 1_024),
                )
            },
            when (event.triggerScope) {
                RegistryTriggerScope.RESEARCHER -> TriggerScope.RESEARCHER
                RegistryTriggerScope.RUNTIME_ONLY -> TriggerScope.RUNTIME_ONLY
                RegistryTriggerScope.AUDIT_ONLY -> TriggerScope.AUDIT_ONLY
            },
            if (event.deliveryKind == RegistryDeliveryKind.POLL) DeliveryMode.RETROSPECTIVE else DeliveryMode.LIVE,
            event.automationTimeInputs.mapTo(linkedSetOf(), EventClockSupport::valueOf),
            event.conditionKinds.mapTo(linkedSetOf()) { EventConditionKind.valueOf(it.name) },
            event.presence?.let { presence ->
                EventPresenceContract(
                    presence.groupId,
                    EventPresenceRole.valueOf(presence.role),
                    presence.keyFields.toSet(),
                )
            },
            if (event.rateKind == RegistryRateKind.HARD) {
                EventRateBound(requireNotNull(event.maximumEventsPerPeriod), requireNotNull(event.ratePeriodSeconds))
            } else {
                null
            },
        )
    }
}
