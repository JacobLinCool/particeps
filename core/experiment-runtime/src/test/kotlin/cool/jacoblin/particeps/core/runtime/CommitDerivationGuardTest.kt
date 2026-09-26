package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpoint
import cool.jacoblin.particeps.core.automation.AutomationCheckpointCodec
import cool.jacoblin.particeps.core.automation.AutomationCompiler
import cool.jacoblin.particeps.core.automation.CompilationResult
import cool.jacoblin.particeps.core.automation.DeterministicIds
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerProductionResult
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.definition.Aggregate
import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.Cooldown
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
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineCommitIntegrity
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyReadSnapshot
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.resource.ApplyReceipt
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.FlushReceipt
import cool.jacoblin.particeps.core.resource.PrepareReceipt
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.ReleaseReceipt
import cool.jacoblin.particeps.core.resource.ResourceHealth
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailureListener
import cool.jacoblin.particeps.core.resource.ResumeReceipt
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.SuspendReceipt
import cool.jacoblin.particeps.core.resource.VerifyReceipt
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cached commit path against a plain derivation of the same v1 bytes. The runtime keeps
 * content digests on commit and pending-input values, builds the checkpoint digest preimage in one
 * pass with cached timer components, precomputes reducer state keys and zones, and a store checks a
 * successor without rebuilding it. A deterministic study here commits through all of that, and every
 * commit, pending input, checkpoint and successor must equal what this file derives from scratch with
 * `DataOutputStream`, sorted copies and fresh values, so a stale or misplaced cache fails here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitDerivationGuardTest {
    @Test
    fun everyCommitEqualsItsStraightforwardDerivation() = runTest {
        val fixture = fixture(backgroundScope)
        val runtime = fixture.runtime
        assertTrue(runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(RuntimeCommandResult.Success, runtime.markConfigurationVerified())
        assertEquals(RuntimeCommandResult.Success, runtime.beginConsentReview())
        assertEquals(RuntimeCommandResult.Success, runtime.acceptConsent())
        assertEquals(RuntimeCommandResult.Success, runtime.markReady())
        assertEquals(RuntimeCommandResult.Success, runtime.start())

        fixture.emitGyro(1)
        fixture.emitGyro(1)
        fixture.emitGyro(25)
        fixture.emitBattery(50)
        // Sets the latch (a resource barrier through the pending-input slot), starts the sequence,
        // and fires the guarded event match whose guard nests All, Not and ElapsedAtLeast.
        fixture.emitBattery(42)
        runCurrent()
        fixture.emitGyro(3)
        // Completes the sequence and resets the latch through a second barrier.
        fixture.emitBattery(43)
        runCurrent()
        fixture.emitBattery(44)
        fixture.emitGyro(1)
        assertTrue(runtime.pendingActions().isNotEmpty())

        // The evening window opens on its durable calendar timer and raises its rising edge.
        val evening = fixture.wakeups.scheduled.last { timer ->
            (timer.target as? TimerTarget.CalendarUtc)?.utcMillis == EVENING_OPENS_UTC_MILLIS
        }
        fixture.clocks.advanceToWallMillis(EVENING_OPENS_UTC_MILLIS)
        assertEquals(RuntimeCommandResult.Success, runtime.onTimerDue(evening.id, evening.generation))
        runCurrent()
        fixture.emitGyro(2)
        val committed = fixture.store.commits.last().commitSequence
        assertEquals(
            RuntimeCommandResult.Success,
            runtime.acknowledgeUpload("123e4567-e89b-42d3-a456-426614174099", 1, committed, "b".repeat(64)),
        )
        assertEquals(RuntimeCommandResult.Success, runtime.pause())
        assertEquals(RuntimeCommandResult.Success, runtime.resume())
        runCurrent()
        fixture.emitGyro(4)
        assertEquals(RuntimeCommandResult.Success, runtime.complete())
        assertEquals(ExperimentState.COMPLETED, runtime.snapshot.value.state)

        val commits = fixture.store.commits
        assertTrue("The scenario must exercise the pending-input path", fixture.store.pendingInputs.size >= 2)
        assertTrue(commits.any { it.consumedPendingInputSha256 != null })
        assertTrue(commits.flatMap(EngineCommit::mutations).any { it.key.kind == RuntimeComponentKind.ACTION_INVOCATION })
        assertTrue(commits.flatMap(EngineCommit::mutations).any { it.key.kind == RuntimeComponentKind.TIMER })

        var document = fixture.store.initial
        var previousDigest = document.lastCommitSha256
        commits.forEachIndexed { index, commit ->
            val label = "commit ${commit.commitSequence}"
            assertEquals(label, document.nextCommitSequence, commit.commitSequence)
            assertEquals(label, previousDigest, commit.previousCommitSha256)
            assertEquals(label, plainCommitDigest(commit), commit.commitSha256)
            assertEquals(label, commit.commitSha256, EngineCommitIntegrity.calculate(commit.copy()))

            val successor = plainAdvance(document, commit)
            assertEquals(label, successor, fixture.store.successors[index])
            assertEquals(label, successor, document.advance(commit))

            val encodedCheckpoint = successor.components
                .filterKeys { it.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT && it.id.startsWith("main") }
                .toSortedMap()
                .values
                .joinToString(separator = "")
            val checkpoint = AutomationCheckpointCodec.decode(encodedCheckpoint)
            assertEquals(label, encodedCheckpoint, AutomationCheckpointCodec.encode(checkpoint.copy()))
            assertEquals(label, plainCheckpointDigest(checkpoint), commit.resultingCheckpointSha256)
            assertEquals(label, commit.resultingCheckpointSha256, checkpoint.copy().digest())

            document = successor
            previousDigest = commit.commitSha256
        }
        assertEquals(fixture.store.runtime, document)
        fixture.store.pendingInputs.forEach { input ->
            assertEquals(plainPendingDigest(input), input.encodedSha256)
            assertEquals(input.encodedSha256, EngineCommitIntegrity.calculate(input.copy()))
        }
    }

    /** Applies a commit with a fresh hash map, independent of the runtime's sorted-copy advance. */
    private fun plainAdvance(document: RuntimeDocument, commit: EngineCommit): RuntimeDocument {
        val components = HashMap(document.components)
        commit.mutations.forEach { mutation ->
            when (mutation.operation) {
                RuntimeMutationOperation.UPSERT -> components[mutation.key] = requireNotNull(mutation.canonicalValue)
                RuntimeMutationOperation.REMOVE -> components.remove(mutation.key)
            }
        }
        val projection = commit.successorProjection
        return document.copy(
            state = projection.state,
            revision = projection.revision,
            nextCommitSequence = projection.nextCommitSequence,
            nextObservationSequence = projection.nextObservationSequence,
            nextEventSequence = projection.nextEventSequence,
            lastCommitSha256 = commit.commitSha256,
            sourceCheckpoints = projection.sourceCheckpoints,
            clockCheckpoint = projection.clockCheckpoint,
            activeConditionEpoch = projection.activeConditionEpoch,
            components = components,
            lifetimeDataEventCount = projection.lifetimeDataEventCount,
            uploadedThroughCommit = projection.uploadedThroughCommit,
            evaluatedThroughCommit = projection.evaluatedThroughCommit,
            retainedFromCommit = projection.retainedFromCommit,
        )
    }

    private fun plainCommitDigest(commit: EngineCommit): String = plainDigest {
        text(EngineCommitIntegrity.FORMAT)
        writeLong(commit.commitSequence)
        text(commit.previousCommitSha256)
        text(commit.inputKind.name)
        optional(commit.consumedPendingInputSha256) { text(it) }
        writeInt(commit.sourceObservations.size)
        commit.sourceObservations.forEach { observation ->
            writeLong(observation.observationSequence)
            text(observation.sourceId.value)
            writeInt(observation.schemaVersion)
            writeLong(observation.resourceGeneration)
            text(observation.admissionKind.name)
            writeLong(observation.producerOrdinal)
            text(observation.conditionEpochId.value)
            writeInt(observation.eventCount)
            optional(observation.firstEventSequence) { writeLong(it) }
            optional(observation.lastEventSequence) { writeLong(it) }
            optional(observation.coverage) { coverage(it) }
            text(observation.encodedSha256)
        }
        writeInt(commit.events.size)
        commit.events.forEach { event -> recordedEvent(event) }
        writeInt(commit.mutations.size)
        commit.mutations.forEach { mutation ->
            text(mutation.key.kind.name)
            text(mutation.key.id)
            text(mutation.operation.name)
            optional(mutation.canonicalValue) { text(it) }
        }
        time(commit.committedAt)
        projection(commit.successorProjection)
        text(commit.resultingCheckpointSha256)
    }

    private fun plainPendingDigest(input: PendingEngineInput): String = plainDigest {
        text(EngineCommitIntegrity.PENDING_FORMAT)
        text(input.conditionEpochId.value)
        writeInt(input.submissions.size)
        input.submissions.forEach { submission ->
            text(submission.sourceId.value)
            writeInt(submission.schemaVersion)
            writeLong(submission.resourceGeneration)
            writeLong(submission.producerOrdinal)
            text(submission.admissionKind.name)
            writeInt(submission.events.size)
            submission.events.forEach { draft ->
                type(draft.type)
                time(draft.observedTime)
                fields(draft.fields)
            }
            optional(submission.coverage) { coverage(it) }
        }
        time(input.stagedAt)
    }

    private fun plainDigest(write: DataOutputStream.() -> Unit): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.write() }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun <T> DataOutputStream.optional(value: T?, write: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) write(value)
    }

    private fun DataOutputStream.type(value: EventTypeKey) {
        text(value.sourceId.value)
        writeInt(value.schemaVersion)
        text(value.eventType)
    }

    private fun DataOutputStream.time(value: ResearchTime) {
        writeLong(value.wallTimeUtcMillis)
        writeLong(value.elapsedRealtimeNanos)
        text(value.bootSessionId)
    }

    private fun DataOutputStream.fields(values: Map<String, String>) {
        val sorted = values.toSortedMap()
        writeInt(sorted.size)
        sorted.forEach { (key, value) ->
            text(key)
            text(value)
        }
    }

    private fun DataOutputStream.coverage(value: SourceCoverage) {
        text(value.clockBasis.name)
        text(value.startInclusive)
        text(value.endExclusive)
    }

    private fun DataOutputStream.recordedEvent(value: RecordedEvent) {
        writeLong(value.sequenceNumber)
        type(value.type)
        time(value.observedTime)
        optional(value.conditionEpochId) { text(it.value) }
        fields(value.fields)
    }

    private fun DataOutputStream.projection(value: RuntimeProjection) {
        text(value.state.name)
        writeLong(value.revision)
        writeLong(value.nextCommitSequence)
        writeLong(value.nextObservationSequence)
        writeLong(value.nextEventSequence)
        val checkpoints = value.sourceCheckpoints.toSortedMap()
        writeInt(checkpoints.size)
        checkpoints.forEach { (sourceId, checkpoint) ->
            text(sourceId.value)
            text(checkpoint.sourceId.value)
            writeLong(checkpoint.resourceGeneration)
            writeLong(checkpoint.nextProducerOrdinal)
            optional(checkpoint.coverage) { coverage(it) }
            optional(checkpoint.cursor) { text(it) }
        }
        optional(value.clockCheckpoint) { clock ->
            writeLong(clock.calendarElapsedNanos)
            writeLong(clock.activeRunningElapsedNanos)
            time(clock.anchor)
            writeLong(clock.deadlineUtcMillis)
            writeBoolean(clock.deadlineUtcTrusted)
            text(clock.zoneId)
        }
        optional(value.activeConditionEpoch) { epoch ->
            text(epoch.id.value)
            text(epoch.configurationSha256)
            text(epoch.appliedResourceVectorSha256)
            time(epoch.activatedAt)
        }
        writeLong(value.lifetimeDataEventCount)
        writeLong(value.uploadedThroughCommit)
        writeLong(value.evaluatedThroughCommit)
        writeLong(value.retainedFromCommit)
    }

    /** `DeterministicIds.digest` over one string per checkpoint fact, each built with templates. */
    private fun plainCheckpointDigest(checkpoint: AutomationCheckpoint): String {
        fun escape(value: String) = value.replace("%", "%25").replace("\u0000", "%00")
            .replace(":", "%3a").replace("=", "%3d")
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
        checkpoint.priorConditionValues.toSortedMap().forEach { (key, value) ->
            components += "prior:${escape(key)}=$value"
        }
        checkpoint.windows.toSortedMap().forEach { (key, values) ->
            values.forEach { entry ->
                components += "window:${escape(key)}:${entry.sequenceNumber}:${entry.timeNanos}:" +
                    "${escape(entry.bootSessionId)}:${entry.numericValue}"
            }
        }
        checkpoint.sequences.toSortedMap().forEach { (key, values) ->
            values.forEach { partial ->
                components += "sequence:${escape(key)}:${partial.nextStep}:${partial.firstSequenceNumber}:" +
                    "${partial.lastSequenceNumber}:${partial.firstTimeNanos}:${escape(partial.bootSessionId)}"
            }
        }
        checkpoint.activationCounts.toSortedMap().forEach { (key, value) ->
            components += "activation:${escape(key)}=$value"
        }
        checkpoint.cooldownMarks.toSortedMap().forEach { (key, value) ->
            components += "cooldown:${escape(key)}:${value.activeElapsedNanos}:${value.calendarElapsedNanos}"
        }
        checkpoint.desiredResources.toSortedMap().forEach { (key, value) ->
            components += "resource:${key.kind.name}:${escape(key.id)}:${value.generation}:" +
                escape(value.profileId.orEmpty())
        }
        checkpoint.timers.toSortedMap().forEach { (_, timer) -> components += plainTimerComponent(timer, ::escape) }
        checkpoint.timerGenerations.toSortedMap().forEach { (key, value) ->
            components += "timer-generation:${escape(key)}:$value"
        }
        checkpoint.materializedTimers.toSortedMap().forEach { (key, values) ->
            values.forEach { timer ->
                components += "materialized:${escape(key)}:${escape(timer.producerKey)}:" +
                    "${timer.selectedUtcMillis}:${timer.terminal}"
            }
        }
        return DeterministicIds.digest("particeps-automation-checkpoint-v1", components)
    }

    private fun plainTimerComponent(timer: DurableTimer, escape: (String) -> String): String {
        val target = when (val value = timer.target) {
            is TimerTarget.CalendarUtc -> "calendar:${value.utcMillis}"
            is TimerTarget.ActiveElapsed -> "active:${value.elapsedNanos}"
            is TimerTarget.SameBootMonotonic -> "monotonic:${escape(value.bootSessionId)}:${value.elapsedRealtimeNanos}"
        }
        return "timer:${timer.id}:${escape(timer.automationId)}:${timer.generation}:${timer.causalSequence}:" +
            "${escape(timer.producerKey)}:$target:${timer.logicalDeadlineUtcMillis ?: ""}:${timer.expiresAtUtcMillis ?: ""}"
    }

    private class Fixture(
        val runtime: ExperimentRuntime,
        val store: RecordingStore,
        val clocks: GuardClocks,
        val wakeups: RecordingWakeups,
        private val actuators: Map<EventSourceId, GuardActuator>,
    ) {
        private val ordinals = mutableMapOf<EventSourceId, Pair<Long, Long>>()

        suspend fun emitGyro(events: Int) = emit(GYRO_EVENT, List(events) { gyroFields(it) })

        suspend fun emitBattery(percentage: Int) = emit(
            BATTERY_EVENT,
            listOf(
                mapOf(
                    "charging_source" to "NONE",
                    "charging_state" to "DISCHARGING",
                    "percentage" to percentage.toString(),
                    "power_save_enabled" to "false",
                ),
            ),
        )

        private fun gyroFields(index: Int) = mapOf(
            "accuracy" to "3",
            "x_radians_per_second" to "0.0${index % 10}25",
            "y_radians_per_second" to "-1.5e-3",
            "z_radians_per_second" to "0.5",
        )

        private suspend fun emit(type: EventTypeKey, fields: List<Map<String, String>>) {
            val source = type.sourceId
            val generation = requireNotNull(requireNotNull(actuators[source]).lastDesired).generation.value.toLong()
            val (priorGeneration, priorOrdinal) = ordinals[source] ?: (generation to -1L)
            val ordinal = if (priorGeneration == generation) priorOrdinal + 1 else 0L
            ordinals[source] = generation to ordinal
            val drafts = fields.map { values ->
                val now = clocks.now()
                EventDraft(
                    type,
                    now,
                    if (type == GYRO_EVENT) {
                        values + ("source_elapsed_realtime_nanos" to now.elapsedRealtimeNanos.toString())
                    } else {
                        values
                    },
                )
            }
            val result = runtime.emitBatch(
                requireNotNull(runtime.captureToken()),
                SourceEventBatch(source, 1, generation, ordinal, drafts),
            )
            assertTrue("$type was not accepted: $result", result is EmitBatchResult.Accepted)
        }
    }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val continuous = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val baseline = SignedResourceProfile("baseline", "{\"id\":\"baseline\"}".toByteArray())
        val slow = SignedResourceProfile("slow", "{\"id\":\"slow\"}".toByteArray())
        val battery = ResourceKey(ResourceKind.COLLECTOR, BATTERY_SOURCE.value)
        val gyro = ResourceKey(ResourceKind.COLLECTOR, GYRO_SOURCE.value)
        val traffic = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1")
        fun percentage(operator: FieldOperator, value: Int) =
            EventMatcher(BATTERY_EVENT, listOf(FieldPredicate("percentage", operator, value = value.toString())))
        fun occurrence(id: String, trigger: Trigger, guard: StateCondition? = null, cooldown: Cooldown? = null) =
            OccurrenceAutomation(id, trigger, guard, "prompt", 3_600, cooldown, maximumActivations = 4)
        val input = AutomationCompilerInput(
            configurationSha256 = CONFIG_DIGEST,
            studyDurationSeconds = DURATION_SECONDS,
            resources = listOf(
                DeclaredResource(traffic, true, mapOf("baseline" to baseline.expectedSha256.value, "slow" to slow.expectedSha256.value)),
                DeclaredResource(battery, true, mapOf("continuous" to continuous.expectedSha256.value)),
                DeclaredResource(gyro, true, mapOf("continuous" to continuous.expectedSha256.value)),
            ),
            interventions = listOf(InterventionDefinition("prompt", required = false)),
            automations = listOf(
                ResourceBindingAutomation(
                    "battery-binding", battery,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")), "continuous",
                ),
                occurrence(
                    "battery-sequence",
                    Trigger.Sequence(
                        listOf(percentage(FieldOperator.EQ, 42), percentage(FieldOperator.EQ, 43)),
                        600,
                        EvaluationClock.OBSERVED_RESEARCH_TIME,
                    ),
                ),
                occurrence(
                    "battery-spike",
                    Trigger.EventMatch(percentage(FieldOperator.EQ, 42), EvaluationClock.OBSERVED_RESEARCH_TIME),
                    guard = StateCondition.All(
                        listOf(
                            StateCondition.StudySessionActive,
                            StateCondition.Not(StateCondition.ElapsedAtLeast(86_400, DurationClock.ACTIVE_RUNNING_TIME)),
                        ),
                    ),
                    cooldown = Cooldown(60, DurationClock.ACTIVE_RUNNING_TIME),
                ),
                occurrence(
                    "battery-window",
                    Trigger.WindowThreshold(
                        percentage(FieldOperator.GTE, 0),
                        600,
                        EvaluationClock.OBSERVED_RESEARCH_TIME,
                        Aggregate.Sum("percentage"),
                        NumericComparison(FieldOperator.GTE, "170"),
                    ),
                ),
                occurrence(
                    "evening-edge",
                    Trigger.ConditionRisingEdge(StateCondition.StudyLocalWindow(1, 7, "18:00", "19:00")),
                ),
                ResourceBindingAutomation(
                    "gyro-binding", gyro,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")), "continuous",
                ),
                ResourceBindingAutomation(
                    "traffic-binding", traffic,
                    listOf(
                        ResourceConditionCase(
                            StateCondition.All(
                                listOf(
                                    StateCondition.EventLatch(
                                        setWhen = listOf(percentage(FieldOperator.EQ, 42)),
                                        resetWhen = listOf(percentage(FieldOperator.EQ, 43)),
                                    ),
                                    StateCondition.StudyLocalWindow(1, 7, "08:00", "23:00"),
                                ),
                            ),
                            "slow",
                        ),
                    ),
                    "baseline",
                ),
            ),
        )
        val program = when (val compiled = AutomationCompiler(GeneratedEventContractRegistry).compile(input)) {
            is CompilationResult.Success -> compiled.program
            is CompilationResult.Failure -> error("Compilation failed: ${compiled.issues}")
        }
        val actuators = mapOf(BATTERY_SOURCE to GuardActuator(battery), GYRO_SOURCE to GuardActuator(gyro))
        val clocks = GuardClocks(Instant.parse("2026-09-07T01:00:00Z").toEpochMilli())
        val store = RecordingStore()
        val wakeups = RecordingWakeups()
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, DURATION_SECONDS),
            store = store,
            program = program,
            surveyInterventionIds = setOf("prompt"),
            resourceHosts = listOf(
                RuntimeResourceHost(traffic, true, mapOf("baseline" to baseline, "slow" to slow), GuardActuator(traffic)),
                RuntimeResourceHost(battery, true, mapOf("continuous" to continuous), actuators.getValue(BATTERY_SOURCE)),
                RuntimeResourceHost(gyro, true, mapOf("continuous" to continuous), actuators.getValue(GYRO_SOURCE)),
            ),
            clocks = clocks,
            scope = scope,
            zoneId = { "Asia/Taipei" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            timerWakeups = wakeups,
            entropy = GuardEntropy(),
        )
        return Fixture(runtime, store, clocks, wakeups, actuators)
    }

    /** Checks each append as the encrypted store does and keeps every commit, successor and input. */
    private class RecordingStore : StudyStore {
        lateinit var initial: RuntimeDocument
        var runtime: RuntimeDocument? = null
        private var pending: PendingEngineInput? = null
        val commits = mutableListOf<EngineCommit>()
        val successors = mutableListOf<RuntimeDocument>()
        val pendingInputs = mutableListOf<PendingEngineInput>()

        override suspend fun loadRuntime(observeRetained: (EngineCommit) -> Unit): RuntimeDocument? = runtime
        override suspend fun initialize(runtime: RuntimeDocument) {
            check(this.runtime == null)
            initial = runtime
            this.runtime = runtime
        }
        override suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument) {
            check(pending == null && commit.consumedPendingInputSha256 == null)
            append(commit, successor)
        }
        override suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument) {
            check(commit.consumedPendingInputSha256 == requireNotNull(pending).encodedSha256)
            append(commit, successor)
            pending = null
        }
        override suspend fun stagePendingInput(input: PendingEngineInput) {
            check(pending == null)
            EngineCommitIntegrity.verify(input)
            pending = input
            pendingInputs += input
        }
        override suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput) {
            check(pending?.encodedSha256 == expectedSha256)
            EngineCommitIntegrity.verify(input)
            pending = input
            pendingInputs += input
        }
        override suspend fun loadPendingInput(): PendingEngineInput? = pending
        override suspend fun <T> withReadSnapshot(block: suspend (StudyReadSnapshot) -> T): T =
            error("Unused by this scenario")
        override suspend fun storageUsage() = StorageUsage(0, 1)
        override suspend fun evictThrough(runtime: RuntimeDocument, targetBytes: Long): RuntimeDocument = runtime
        override suspend fun clear() = error("Unused by this scenario")

        private fun append(commit: EngineCommit, successor: RuntimeDocument) {
            val current = requireNotNull(runtime)
            EngineCommitIntegrity.verify(commit)
            val exact = current.advancesTo(commit, successor)
            assertEquals(successor == current.advance(commit), exact)
            check(exact) { "Runtime is not the exact commit successor" }
            check(successor.projection() == commit.successorProjection)
            commits += commit
            successors += successor
            runtime = successor
        }
    }

    private class GuardClocks(private val wallBaseMillis: Long) : ResearchClocks {
        private var nanos = 1_000_000_000L

        override fun now(): ResearchTime =
            ResearchTime(wallBaseMillis + nanos / 1_000_000L, nanos, "boot-guard").also { nanos += 1_000_000L }

        override fun trustedUtcMillis(): Long = now().wallTimeUtcMillis

        fun advanceToWallMillis(target: Long) {
            val current = wallBaseMillis + nanos / 1_000_000L
            require(target >= current)
            nanos += (target - current) * 1_000_000L
        }
    }

    private class GuardEntropy : RuntimeEntropySource {
        private var epoch = 0
        override fun next(kind: RuntimeEntropyKind): String = when (kind) {
            RuntimeEntropyKind.PARTICIPANT_INSTANCE_UUID -> "123e4567-e89b-42d3-a456-426614174001"
            RuntimeEntropyKind.ACTIVITY_TOKEN_KEY -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            RuntimeEntropyKind.CONDITION_EPOCH_UUID -> "123e4567-e89b-42d3-a456-4266141740%02d".format(10 + epoch++)
        }
    }

    private class RecordingWakeups : TimerWakeupAdapter {
        val scheduled = mutableListOf<DurableTimer>()
        override suspend fun schedule(timer: DurableTimer) {
            scheduled += timer
        }
        override suspend fun retire(timerId: String, generation: ULong) = Unit
    }

    private class GuardActuator(override val key: ResourceKey) : StatefulResourceActuator {
        override val supportsHotProfileSwap = true
        var lastDesired: DesiredResourceState? = null
        private var health = inactive()

        override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) = Unit

        override suspend fun prepare(desired: DesiredResourceState, requestId: String): PrepareReceipt {
            health = healthOf(desired, ResourceHealthStatus.PREPARED, applied = false)
            return PrepareReceipt(key, desired.generation, desired.profile?.id, desired.profile?.expectedSha256, null, requestId)
        }

        override suspend fun suspendAt(desired: DesiredResourceState, boundary: ResearchTime): SuspendReceipt {
            health = healthOf(desired, ResourceHealthStatus.SUSPENDED, applied = true)
            return SuspendReceipt(
                key, desired.generation, desired.profile?.id, desired.profile?.expectedSha256,
                desired.profile?.expectedSha256, boundary,
            )
        }

        override suspend fun flushThrough(desired: DesiredResourceState, boundary: ResearchTime, cursor: String?) =
            FlushReceipt(
                key, desired.generation, desired.profile?.id, desired.profile?.expectedSha256,
                desired.profile?.expectedSha256, boundary, cursor, complete = true,
            )

        override suspend fun apply(desired: DesiredResourceState): ApplyReceipt {
            lastDesired = desired
            health = healthOf(desired, ResourceHealthStatus.APPLIED, applied = true)
            return ApplyReceipt(
                key, desired.generation, desired.profile?.id, desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
            )
        }

        override suspend fun verify(desired: DesiredResourceState) = VerifyReceipt(
            key, desired.generation, desired.profile?.id, desired.profile?.expectedSha256,
            desired.profile?.expectedSha256, healthy = true, failureReason = null,
        )

        override suspend fun resume(desired: DesiredResourceState): ResumeReceipt {
            health = healthOf(desired, ResourceHealthStatus.APPLIED, applied = true)
            return ResumeReceipt(
                key, desired.generation, desired.profile?.id, desired.profile?.expectedSha256,
                desired.profile?.expectedSha256, resumed = true, failureReason = null,
            )
        }

        override suspend fun onAdmissionOpened(desired: DesiredResourceState): ResourceHealth = health

        override suspend fun release(desired: DesiredResourceState): ReleaseReceipt {
            lastDesired = null
            health = inactive()
            return ReleaseReceipt(
                key, desired.generation, desired.profile?.id, desired.profile?.expectedSha256,
                desired.profile?.expectedSha256, ReleaseEvidence.APPLIED, released = true,
            )
        }

        override fun health(): ResourceHealth = health

        private fun inactive() = ResourceHealth(key, ResourceHealthStatus.INACTIVE, null, null, null, null, null)

        private fun healthOf(desired: DesiredResourceState, status: ResourceHealthStatus, applied: Boolean) =
            ResourceHealth(
                key = desired.key,
                status = status,
                generation = desired.generation,
                profileId = desired.profile?.id,
                expectedProfileSha256 = desired.profile?.expectedSha256,
                appliedProfileSha256 = desired.profile?.expectedSha256.takeIf { applied },
                failureReason = null,
            )
    }

    private companion object {
        const val CONFIG_DIGEST = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val DURATION_SECONDS = 7L * 24 * 3_600
        val EVENING_OPENS_UTC_MILLIS = Instant.parse("2026-09-07T10:00:00Z").toEpochMilli()
        val BATTERY_SOURCE = EventSourceId("battery_state.v1")
        val BATTERY_EVENT = EventTypeKey(BATTERY_SOURCE, 1, "BATTERY_STATE")
        val GYRO_SOURCE = EventSourceId("gyroscope.v1")
        val GYRO_EVENT = EventTypeKey(GYRO_SOURCE, 1, "GYROSCOPE_SAMPLE")
    }
}
