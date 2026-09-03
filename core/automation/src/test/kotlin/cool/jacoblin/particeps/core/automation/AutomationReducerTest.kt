package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.*
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AutomationReducerTest {
    private val resumed = EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_RESUMED")
    private val paused = EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_PAUSED")
    private val floatSample = EventTypeKey(EventSourceId("accelerometer.v1"), 1, "ACCELEROMETER_SAMPLE")
    private val accelerometerResource = ResourceKey(ResourceKind.COLLECTOR, "accelerometer.v1")
    private val usageResource = ResourceKey(ResourceKind.COLLECTOR, "usage_events.v1")
    private val trafficResource = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1")
    private val contracts = listOf(contract(resumed), contract(paused), contract(floatSample))
    private val reducer = AutomationReducer()

    @Test
    fun eventMatchCreatesOneDeterministicActionAfterRunning() {
        val trigger = Trigger.EventMatch(
            EventMatcher(
                resumed,
                listOf(FieldPredicate("package_name", FieldOperator.EQ, value = "com.example.target")),
            ),
            EvaluationClock.PRIMARY_SOURCE_TIME,
        )
        val program = occurrenceProgram(trigger)
        val started = start(program)
        val result = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(eventInput(3, resumed, 1_000, 1_000, "component-a")),
        )

        assertEquals(1, result.actionRequests.size)
        val request = result.actionRequests.single()
        assertEquals("event:3", request.causalIdentity)
        assertEquals("prompt-after-use", request.automationId)
        assertEquals(
            DeterministicIds.actionId(
                CONFIG_DIGEST,
                "prompt-after-use",
                "check-in",
                "event_match",
                "event:3",
                "",
            ),
            request.actionId,
        )
        assertEquals(3, result.checkpoint.evaluatedThroughSequence)
    }

    @Test
    fun missingFieldMakesNePredicateFalse() {
        val program = occurrenceProgram(
            Trigger.EventMatch(
                EventMatcher(
                    resumed,
                    listOf(FieldPredicate("package_name", FieldOperator.NE, value = "com.example.target")),
                ),
                EvaluationClock.OBSERVED_RESEARCH_TIME,
            ),
        )
        val started = start(program)
        val event = AutomationEvent(
            3,
            resumed,
            researchTime(1_000),
            researchTime(1_000),
            mapOf("activity_component_token" to "component-a"),
        )
        val result = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(ReducerInput.Event(3, clock(1_000, activeSeconds = 1), event)),
        )
        assertTrue(result.actionRequests.isEmpty())
    }

    @Test
    fun protocolDecimalFloatEventsMatchCanonicalInPredicateNumerically() {
        val program = occurrenceProgram(
            Trigger.EventMatch(
                EventMatcher(
                    floatSample,
                    listOf(
                        FieldPredicate(
                            "reading",
                            FieldOperator.IN,
                            values = listOf("0.001", "0.5", "1.0", "1000.0"),
                        ),
                    ),
                ),
                EvaluationClock.OBSERVED_RESEARCH_TIME,
            ),
        )
        val started = start(program)
        val wireValues = listOf("+1", "01", ".5", "1.", "1e-3", "1E+3")
        val result = reducer.reduceBatch(
            program,
            started.checkpoint,
            wireValues.mapIndexed { index, wire ->
                val sequence = index.toLong() + 3
                val millis = index.toLong() + 1
                ReducerInput.Event(
                    sequence,
                    clock(millis),
                    AutomationEvent(
                        sequence,
                        floatSample,
                        researchTime(millis),
                        null,
                        mapOf("reading" to wire),
                    ),
                )
            },
        )

        assertEquals(wireValues.size, result.actionRequests.size)
        assertEquals(wireValues.size, result.checkpoint.activationCounts.getValue("prompt-after-use"))
    }

    @Test
    fun overlappingSequenceProducesEveryDistinctCausalRange() {
        val program = occurrenceProgram(
            Trigger.Sequence(
                listOf(EventMatcher(resumed), EventMatcher(paused)),
                withinSeconds = 300,
                evaluationClock = EvaluationClock.PRIMARY_SOURCE_TIME,
            ),
        )
        val started = start(program)
        val result = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(
                eventInput(3, resumed, 1_000, 1_000, "one"),
                eventInput(4, resumed, 2_000, 2_000, "two"),
                eventInput(5, paused, 3_000, 3_000, "one"),
            ),
        )
        assertEquals(listOf("range:3:5", "range:4:5"), result.actionRequests.map { it.causalIdentity })
        assertEquals(2, result.checkpoint.activationCounts.getValue("prompt-after-use"))
    }

    @Test
    fun heldPresenceSchedulesActiveTimerAndSwitchesOnlyWhenDue() {
        val condition = StateCondition.HeldFor(
            StateCondition.KeyedPresence(
                enterWhen = listOf(
                    EventMatcher(
                        resumed,
                        listOf(FieldPredicate("package_name", FieldOperator.EQ, value = "com.example.target")),
                    ),
                ),
                exitWhen = listOf(
                    EventMatcher(
                        paused,
                        listOf(FieldPredicate("package_name", FieldOperator.EQ, value = "com.example.target")),
                    ),
                ),
                keyField = "activity_component_token",
            ),
            durationSeconds = 180,
            clock = DurationClock.ACTIVE_RUNNING_TIME,
        )
        val program = trafficProgram(condition)
        val started = start(program)
        assertEquals("baseline", started.checkpoint.desiredResources.getValue(trafficResource).profileId)

        val entered = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(eventInput(3, resumed, 1_000, 0, "component-a")),
        )
        assertTrue(entered.resourceChanges.isEmpty())
        val heldTimer = entered.timerIntents.filterIsInstance<TimerIntent.Schedule>().single().timer
        assertTrue(heldTimer.target is TimerTarget.ActiveElapsed)
        assertEquals(180_000_000_000L, (heldTimer.target as TimerTarget.ActiveElapsed).elapsedNanos)

        val due = reducer.reduceBatch(
            program,
            entered.checkpoint,
            listOf(
                ReducerInput.TimerDue(
                    4,
                    clock(181_000, activeSeconds = 180),
                    heldTimer.id,
                    heldTimer.automationId,
                    heldTimer.generation,
                    heldTimer.causalSequence,
                    heldTimer.target,
                    ResearchTime(0, 180_000_000_000L, "active-running-time"),
                ),
            ),
        )
        assertEquals("slow-network", due.resourceChanges.getValue(trafficResource).profileId)

        val left = reducer.reduceBatch(
            program,
            due.checkpoint,
            listOf(eventInput(5, paused, 182_000, 181_000, "component-a", activeSeconds = 181)),
        )
        assertEquals("baseline", left.resourceChanges.getValue(trafficResource).profileId)
    }

    @Test
    fun delayedPresenceBatchEndsAtBaselineWithoutTransientResourceChange() {
        val presence = StateCondition.KeyedPresence(
            enterWhen = listOf(EventMatcher(resumed)),
            exitWhen = listOf(EventMatcher(paused)),
            keyField = "activity_component_token",
        )
        val program = trafficProgram(presence)
        val started = start(program)
        val before = started.checkpoint.desiredResources.getValue(trafficResource)
        val delayedBatch = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(
                eventInput(3, resumed, 10_000, 1_000, "component-a"),
                eventInput(4, paused, 10_000, 9_000, "component-a"),
            ),
        )
        assertTrue(delayedBatch.resourceChanges.isEmpty())
        assertEquals(before, delayedBatch.checkpoint.desiredResources.getValue(trafficResource))
        assertTrue(delayedBatch.checkpoint.presenceKeys.values.all(Set<String>::isEmpty))
    }

    @Test
    fun pauseAndQualityGapResetSessionScopedStateAndRestoreBaseline() {
        val presence = StateCondition.KeyedPresence(
            listOf(EventMatcher(resumed)),
            listOf(EventMatcher(paused)),
            "activity_component_token",
        )
        val program = trafficProgram(presence)
        val started = start(program)
        val entered = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(eventInput(3, resumed, 1_000, 1_000, "component-a")),
        )
        assertEquals("slow-network", entered.resourceChanges.getValue(trafficResource).profileId)

        val gap = reducer.reduceBatch(
            program,
            entered.checkpoint,
            listOf(ReducerInput.QualityGap(4, clock(2_000, activeSeconds = 2), EventSourceId("usage_events.v1"))),
        )
        assertEquals("baseline", gap.resourceChanges.getValue(trafficResource).profileId)
        assertTrue(gap.checkpoint.presenceKeys.isEmpty())

        val pausing = reducer.reduceBatch(
            program,
            gap.checkpoint,
            listOf(ReducerInput.Lifecycle(5, clock(3_000, activeSeconds = 2), StudySessionState.PAUSING)),
        )
        assertEquals(StudySessionState.PAUSING, pausing.checkpoint.lifecycle)
        assertEquals(null, pausing.checkpoint.desiredResources.getValue(trafficResource).profileId)
    }

    @Test
    fun clockDiscontinuityRetiresOnlyCalendarTimersAndTheirMaterialization() {
        val program = trafficProgram(StateCondition.StudySessionActive)
        val started = start(program)
        val calendar = testTimer(
            id = "1".repeat(64),
            producerKey = "calendar",
            target = TimerTarget.CalendarUtc(20_000),
        )
        val active = testTimer(
            id = "2".repeat(64),
            producerKey = "active",
            target = TimerTarget.ActiveElapsed(20_000_000_000),
        )
        val monotonic = testTimer(
            id = "3".repeat(64),
            producerKey = "monotonic",
            target = TimerTarget.SameBootMonotonic("boot-test", 20_000_000_000),
        )
        val checkpoint = started.checkpoint.copy(
            timers = listOf(calendar, active, monotonic).associateBy(DurableTimer::id),
            materializedTimers = mapOf(
                "timer-automation" to listOf(
                    MaterializedTimerSummary("calendar", 20_000, false),
                    MaterializedTimerSummary("active", 20_000, false),
                    MaterializedTimerSummary("monotonic", 20_000, false),
                ),
            ),
        )

        val result = reducer.reduceBatch(
            program,
            checkpoint,
            listOf(
                ReducerInput.ClockDiscontinuity(
                    checkpoint.evaluatedThroughSequence + 1,
                    clock(10_000, activeSeconds = 10),
                    emptySet(),
                ),
            ),
        )

        assertEquals(setOf(active.id, monotonic.id), result.checkpoint.timers.keys)
        assertEquals(
            listOf(TimerIntent.Retire(calendar.id, calendar.generation)),
            result.timerIntents,
        )
        assertEquals(
            listOf("active", "monotonic"),
            result.checkpoint.materializedTimers.getValue("timer-automation").map { it.producerKey },
        )
    }

    @Test
    fun clockDiscontinuityResetsSessionStateAndRestartsNamedRetrospectiveResource() {
        val presence = StateCondition.KeyedPresence(
            listOf(EventMatcher(resumed)),
            listOf(EventMatcher(paused)),
            "activity_component_token",
        )
        val program = trafficProgram(presence)
        val started = start(program)
        val entered = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(eventInput(3, resumed, 1_000, 1_000, "component-a")),
        )
        val previousUsage = entered.checkpoint.desiredResources.getValue(usageResource)

        val result = reducer.reduceBatch(
            program,
            entered.checkpoint,
            listOf(
                ReducerInput.ClockDiscontinuity(
                    4,
                    clock(2_000, activeSeconds = 2),
                    setOf(usageResource),
                ),
            ),
        )

        assertTrue(result.checkpoint.presenceKeys.isEmpty())
        assertEquals("baseline", result.checkpoint.desiredResources.getValue(trafficResource).profileId)
        assertEquals(previousUsage.generation.next(), result.checkpoint.desiredResources.getValue(usageResource).generation)
        assertEquals("live", result.checkpoint.desiredResources.getValue(usageResource).profileId)
    }

    @Test
    fun windowThresholdFiresOnRisingEdgesAndRearmsAfterFalse() {
        val program = occurrenceProgram(
            Trigger.WindowThreshold(
                selector = EventMatcher(resumed),
                windowSeconds = 10,
                evaluationClock = EvaluationClock.PRIMARY_SOURCE_TIME,
                aggregate = Aggregate.Count,
                comparison = NumericComparison(FieldOperator.GTE, "2"),
            ),
        )
        val started = start(program)
        val firstRise = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(
                eventInput(3, resumed, 1_000, 1_000, "one"),
                eventInput(4, resumed, 2_000, 2_000, "two"),
            ),
        )
        assertEquals(1, firstRise.actionRequests.size)
        assertEquals("range:3:4", firstRise.actionRequests.single().causalIdentity)

        val secondRise = reducer.reduceBatch(
            program,
            firstRise.checkpoint,
            listOf(
                eventInput(5, resumed, 20_000, 20_000, "three", activeSeconds = 20),
                eventInput(6, resumed, 21_000, 21_000, "four", activeSeconds = 21),
            ),
        )
        assertEquals(1, secondRise.actionRequests.size)
        assertEquals("range:5:6", secondRise.actionRequests.single().causalIdentity)
    }

    @Test
    fun windowThresholdExpiresAtItsHalfOpenBoundary() {
        val program = occurrenceProgram(
            Trigger.WindowThreshold(
                selector = EventMatcher(resumed),
                windowSeconds = 10,
                evaluationClock = EvaluationClock.PRIMARY_SOURCE_TIME,
                aggregate = Aggregate.Count,
                comparison = NumericComparison(FieldOperator.GTE, "2"),
            ),
        )
        val started = start(program)
        val risen = reducer.reduceBatch(
            program,
            started.checkpoint,
            listOf(
                eventInput(3, resumed, 1_000, 1_000, "one"),
                eventInput(4, resumed, 2_000, 2_000, "two"),
            ),
        )
        val expiry = risen.timerIntents.filterIsInstance<TimerIntent.Schedule>().single().timer
        assertEquals(TimerTarget.SameBootMonotonic("boot-1", 11_000_000_000L), expiry.target)

        val expired = reducer.reduceBatch(
            program,
            risen.checkpoint,
            listOf(
                ReducerInput.TimerDue(
                    5,
                    clock(11_000, activeSeconds = 11),
                    expiry.id,
                    expiry.automationId,
                    expiry.generation,
                    expiry.causalSequence,
                    expiry.target,
                    ResearchTime(0, 11_000_000_000L, "boot-1"),
                ),
            ),
        )
        assertEquals(1, expired.checkpoint.windows.values.single().size)
        assertFalse(expired.checkpoint.priorConditionValues.getValue("occurrence:prompt-after-use:trigger:window-edge"))
    }

    @Test
    fun optionalBindingMaySelectInactiveWithoutFallingThroughToDefault() {
        val optionalTraffic = DeclaredResource(
            trafficResource,
            required = false,
            linkedMapOf("baseline" to DIGEST_B),
        )
        val program = compile(
            AutomationCompilerInput(
                CONFIG_DIGEST,
                3_600,
                listOf(optionalTraffic),
                emptyList(),
                listOf(
                    ResourceBindingAutomation(
                        "bind-traffic",
                        trafficResource,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, null)),
                        "baseline",
                    ),
                ),
            ),
        )
        val started = start(program)
        assertEquals(null, started.checkpoint.desiredResources.getValue(trafficResource).profileId)
    }

    @Test
    fun unreferencedAdmittedEventsAreDeterministicNoOps() {
        val program = trafficProgram(StateCondition.StudySessionActive)
        val started = start(program)
        val other = EventTypeKey(EventSourceId("network_usage.v1"), 1, "NETWORK_SNAPSHOT")
        val input = ReducerInput.Event(
            3,
            clock(1_000, activeSeconds = 1),
            AutomationEvent(3, other, researchTime(1_000), null, emptyMap()),
        )
        val result = reducer.reduceBatch(program, started.checkpoint, listOf(input))
        assertTrue(result.actionRequests.isEmpty())
        assertTrue(result.resourceChanges.isEmpty())
        assertEquals(3, result.checkpoint.evaluatedThroughSequence)
    }

    @Test
    fun timerDueBeforeDurableTargetIsRejected() {
        val condition = StateCondition.HeldFor(
            StateCondition.KeyedPresence(
                listOf(EventMatcher(resumed)),
                listOf(EventMatcher(paused)),
                "activity_component_token",
            ),
            180,
            DurationClock.ACTIVE_RUNNING_TIME,
        )
        val program = trafficProgram(condition)
        val entered = reducer.reduceBatch(
            program,
            start(program).checkpoint,
            listOf(eventInput(3, resumed, 1_000, 0, "component-a")),
        )
        val timer = entered.timerIntents.filterIsInstance<TimerIntent.Schedule>().single().timer
        assertThrows(IllegalArgumentException::class.java) {
            reducer.reduceBatch(
                program,
                entered.checkpoint,
                listOf(
                    ReducerInput.TimerDue(
                        4,
                        clock(2_000, activeSeconds = 1),
                        timer.id,
                        timer.automationId,
                        timer.generation,
                        timer.causalSequence,
                        timer.target,
                        ResearchTime(0, 180_000_000_000L, "active-running-time"),
                    ),
                ),
            )
        }
    }

    @Test
    fun reducerIsInvariantToBatchPartitioningAcrossGeneratedTrace() {
        val program = occurrenceProgram(
            Trigger.EventMatch(EventMatcher(resumed), EvaluationClock.PRIMARY_SOURCE_TIME),
        )
        val initial = start(program).checkpoint
        val random = Random(0x5041525449434550L)
        val inputs = (3L..102L).map { sequence ->
            val eventType = if (random.nextBoolean()) resumed else paused
            val millis = (sequence - 2L) * 1_000L
            eventInput(sequence, eventType, millis, millis, "component-${sequence % 7}")
        }
        val batched = reducer.reduceBatch(program, initial, inputs)

        var checkpoint = initial
        val actionIds = mutableListOf<String>()
        val audits = mutableListOf<AutomationAudit>()
        inputs.forEach { input ->
            val result = reducer.reduceBatch(program, checkpoint, listOf(input))
            checkpoint = result.checkpoint
            actionIds += result.actionRequests.map(ActionRequest::actionId)
            audits += result.audits
        }
        assertEquals(batched.checkpoint.digest(), checkpoint.digest())
        assertEquals(batched.actionRequests.map(ActionRequest::actionId), actionIds)
        assertEquals(batched.audits, audits)
    }

    @Test
    fun checkpointDigestIsMapOrderIndependentAndStateSensitive() {
        val first = AutomationCheckpoint(
            activationCounts = linkedMapOf("z-rule" to 2, "a-rule" to 1),
            latchValues = linkedMapOf("z" to true, "a" to false),
        )
        val second = AutomationCheckpoint(
            activationCounts = linkedMapOf("a-rule" to 1, "z-rule" to 2),
            latchValues = linkedMapOf("a" to false, "z" to true),
        )
        assertEquals(first.digest(), second.digest())
        assertNotEquals(first.digest(), second.copy(evaluatedThroughSequence = 1).digest())
    }

    private fun start(program: CompiledAutomationProgram): ReductionResult = reducer.reduceBatch(
        program,
        AutomationCheckpoint(),
        listOf(
            ReducerInput.Lifecycle(1, clock(0), StudySessionState.ACTIVATING),
            ReducerInput.Lifecycle(2, clock(0), StudySessionState.RUNNING),
        ),
    )

    private fun occurrenceProgram(trigger: Trigger): CompiledAutomationProgram {
        val usesAccelerometer = trigger is Trigger.EventMatch && trigger.selector.event == floatSample
        val configuration = AutomationCompilerInput(
            CONFIG_DIGEST,
            3_600,
            buildList {
                if (usesAccelerometer) {
                    add(DeclaredResource(accelerometerResource, required = true, linkedMapOf("live" to DIGEST_A)))
                }
                add(usageDeclaration())
            },
            listOf(InterventionDefinition("check-in", required = true)),
            buildList {
                if (usesAccelerometer) {
                    add(
                        ResourceBindingAutomation(
                            "bind-accelerometer",
                            accelerometerResource,
                            listOf(ResourceConditionCase(StateCondition.StudySessionActive, "live")),
                            "live",
                        ),
                    )
                }
                add(
                    ResourceBindingAutomation(
                        "bind-usage",
                        usageResource,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "live")),
                        "live",
                    ),
                )
                add(
                    OccurrenceAutomation(
                        "prompt-after-use",
                        trigger,
                        null,
                        "check-in",
                        900,
                        null,
                        10,
                    ),
                )
            },
        )
        return compile(configuration)
    }

    private fun trafficProgram(condition: StateCondition): CompiledAutomationProgram {
        val configuration = AutomationCompilerInput(
            CONFIG_DIGEST,
            3_600,
            listOf(
                DeclaredResource(
                    trafficResource,
                    required = true,
                    linkedMapOf("baseline" to DIGEST_B, "slow-network" to DIGEST_C),
                ),
                usageDeclaration(),
            ),
            emptyList(),
            listOf(
                ResourceBindingAutomation(
                    "bind-traffic",
                    trafficResource,
                    listOf(ResourceConditionCase(condition, "slow-network")),
                    "baseline",
                ),
                ResourceBindingAutomation(
                    "bind-usage",
                    usageResource,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "live")),
                    "live",
                ),
            ),
        )
        return compile(configuration)
    }

    private fun usageDeclaration() = DeclaredResource(
        usageResource,
        required = true,
        linkedMapOf("live" to DIGEST_A),
    )

    private fun compile(configuration: AutomationCompilerInput): CompiledAutomationProgram {
        val result = AutomationCompiler(EventContractRegistry { key -> contracts.singleOrNull { it.key == key } })
            .compile(configuration)
        assertTrue(result is CompilationResult.Success)
        return (result as CompilationResult.Success).program
    }

    private fun eventInput(
        sequence: Long,
        type: EventTypeKey,
        observedMillis: Long,
        sourceMillis: Long,
        token: String,
        activeSeconds: Long = sourceMillis / 1_000,
    ): ReducerInput.Event {
        val event = AutomationEvent(
            sequence,
            type,
            researchTime(observedMillis),
            researchTime(sourceMillis),
            mapOf(
                "package_name" to "com.example.target",
                "activity_component_token" to token,
            ),
        )
        return ReducerInput.Event(sequence, clock(observedMillis, activeSeconds), event)
    }

    private fun testTimer(
        id: String,
        producerKey: String,
        target: TimerTarget,
    ) = DurableTimer(
        id = id,
        automationId = "timer-automation",
        generation = 1uL,
        causalSequence = 1,
        producerKey = producerKey,
        target = target,
        logicalDeadlineUtcMillis = null,
        expiresAtUtcMillis = null,
    )

    private fun clock(wallMillis: Long, activeSeconds: Long = 0) = ReducerClock(
        researchTime(wallMillis),
        activeSeconds * 1_000_000_000L,
        wallMillis * 1_000_000L,
        "UTC",
    )

    private fun researchTime(millis: Long) = ResearchTime(millis, millis * 1_000_000L, "boot-1")

    private fun contract(key: EventTypeKey) = EventTypeContract(
        key,
        EventSourceKind.COLLECTOR,
        if (key == floatSample) {
            mapOf(
                "reading" to FieldContract(
                    ScalarType.FLOAT,
                    FieldOperator.entries.toSet(),
                ),
            )
        } else {
            mapOf(
                "package_name" to FieldContract(
                    ScalarType.STRING,
                    setOf(FieldOperator.EQ, FieldOperator.NE, FieldOperator.IN),
                ),
                "activity_component_token" to FieldContract(
                    ScalarType.STRING,
                    setOf(FieldOperator.EQ, FieldOperator.NE, FieldOperator.IN),
                    keyedPresenceKey = true,
                ),
            )
        },
        TriggerScope.RESEARCHER,
        DeliveryMode.RETROSPECTIVE,
        setOf(EventClockSupport.OBSERVED_RESEARCH_TIME, EventClockSupport.PRIMARY_SOURCE_TIME),
        when (key) {
            resumed -> setOf(
                EventConditionKind.EVENT_MATCH,
                EventConditionKind.KEYED_PRESENCE_ENTER,
                EventConditionKind.SEQUENCE_STEP,
                EventConditionKind.WINDOW_COUNT,
            )
            paused -> setOf(
                EventConditionKind.EVENT_MATCH,
                EventConditionKind.KEYED_PRESENCE_EXIT,
                EventConditionKind.SEQUENCE_STEP,
                EventConditionKind.WINDOW_COUNT,
            )
            else -> setOf(
                EventConditionKind.EVENT_MATCH,
                EventConditionKind.SEQUENCE_STEP,
                EventConditionKind.WINDOW_COUNT,
            )
        },
        when (key) {
            resumed -> EventPresenceContract(
                "usage_activity_foreground",
                EventPresenceRole.ENTER,
                setOf("activity_component_token"),
            )
            paused -> EventPresenceContract(
                "usage_activity_foreground",
                EventPresenceRole.EXIT,
                setOf("activity_component_token"),
            )
            else -> null
        },
        EventRateBound(4, 15),
    )

    private companion object {
        const val CONFIG_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        const val DIGEST_A = "1111111111111111111111111111111111111111111111111111111111111111"
        const val DIGEST_B = "2222222222222222222222222222222222222222222222222222222222222222"
        const val DIGEST_C = "3333333333333333333333333333333333333333333333333333333333333333"
    }
}
