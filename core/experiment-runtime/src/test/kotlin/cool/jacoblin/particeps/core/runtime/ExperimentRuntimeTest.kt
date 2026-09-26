package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCheckpointCodec
import cool.jacoblin.particeps.core.automation.AutomationCompiler
import cool.jacoblin.particeps.core.automation.AutomationReducer
import cool.jacoblin.particeps.core.automation.CompilationResult
import cool.jacoblin.particeps.core.automation.CompiledAutomationProgram
import cool.jacoblin.particeps.core.automation.DeliveryMode
import cool.jacoblin.particeps.core.automation.DeterministicIds
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.EventClockSupport
import cool.jacoblin.particeps.core.automation.EventConditionKind
import cool.jacoblin.particeps.core.automation.EventContractRegistry
import cool.jacoblin.particeps.core.automation.EventRateBound
import cool.jacoblin.particeps.core.automation.EventSourceKind
import cool.jacoblin.particeps.core.automation.EventTypeContract
import cool.jacoblin.particeps.core.automation.FieldContract
import cool.jacoblin.particeps.core.automation.ReducerInput
import cool.jacoblin.particeps.core.automation.ScalarType
import cool.jacoblin.particeps.core.automation.StudySessionState
import cool.jacoblin.particeps.core.automation.TimerIntent
import cool.jacoblin.particeps.core.automation.TimerProductionResult
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.automation.TriggerScope
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.CallbackCommitWindow
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.SerializedCallbackCollector
import cool.jacoblin.particeps.core.collector.SourceRegistrationResult
import cool.jacoblin.particeps.core.collector.SourceTeardownResult
import cool.jacoblin.particeps.core.collector.StudyScopedTokenEncoder
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.definition.Aggregate
import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
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
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.PendingSourceSubmission
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyReadSnapshot
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.withComputedDigest
import cool.jacoblin.particeps.core.resource.ApplyReceipt
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.FlushReceipt
import cool.jacoblin.particeps.core.resource.PrepareReceipt
import cool.jacoblin.particeps.core.resource.PeriodicResourceAuditSource
import cool.jacoblin.particeps.core.resource.ReleaseReceipt
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.ResourceAuditReceipt
import cool.jacoblin.particeps.core.resource.ResourceAuditRequest
import cool.jacoblin.particeps.core.resource.ResourceGeneration
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
import java.io.IOException
import java.math.BigInteger
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentRuntimeTest {
    @Test
    fun requiredScheduledCollectorStopsOutsideItsWindowAndStartsAgainTheNextDay() = runTest {
        val fixture = scheduledCollectorFixture(backgroundScope)
        val runtime = fixture.runtime
        runtime.initialize()
        completeSetup(runtime)
        assertEquals(RuntimeCommandResult.Success, runtime.start())
        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.state)
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.actuator.health().status)
        assertEquals(0, fixture.actuator.resumeCount)

        val firstOpen = scheduledTimerAt(runtime, "2026-09-07T12:00:00Z")
        fixture.clocks.advanceToWallMillis(Instant.parse("2026-09-07T12:00:00Z").toEpochMilli())
        assertEquals(RuntimeCommandResult.Success, runtime.onTimerDue(firstOpen.id, firstOpen.generation))
        assertEquals(ResourceHealthStatus.APPLIED, fixture.actuator.health().status)
        assertEquals(1, fixture.actuator.resumeCount)
        val firstGeneration = requireNotNull(fixture.actuator.lastDesired).generation
        assertTrue(emitScheduledGyro(fixture) is EmitBatchResult.Accepted)

        val firstClose = scheduledTimerAt(runtime, "2026-09-07T17:00:00Z")
        fixture.clocks.advanceToWallMillis(Instant.parse("2026-09-07T17:00:00Z").toEpochMilli())
        assertEquals(RuntimeCommandResult.Success, runtime.onTimerDue(firstClose.id, firstClose.generation))
        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.state)
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.actuator.health().status)
        assertEquals(1, fixture.actuator.releaseCount)
        assertNull(fixture.actuator.lastDesired)

        // Delayed duplicate wakeups must not reapply an inactive collector overnight.
        fixture.clocks.advanceToWallMillis(Instant.parse("2026-09-08T11:59:00Z").toEpochMilli())
        assertEquals(
            RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION),
            runtime.onTimerDue(firstOpen.id, firstOpen.generation),
        )
        assertEquals(
            RuntimeCommandResult.Rejected(RuntimeCommandRejection.STALE_GENERATION),
            runtime.onTimerDue(firstClose.id, firstClose.generation),
        )
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.actuator.health().status)
        assertEquals(1, fixture.actuator.resumeCount)

        val nextOpen = scheduledTimerAt(runtime, "2026-09-08T12:00:00Z")
        fixture.clocks.advanceToWallMillis(Instant.parse("2026-09-08T12:00:00Z").toEpochMilli())
        assertEquals(RuntimeCommandResult.Success, runtime.onTimerDue(nextOpen.id, nextOpen.generation))
        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.state)
        assertEquals(ResourceHealthStatus.APPLIED, fixture.actuator.health().status)
        assertEquals(2, fixture.actuator.resumeCount)
        assertTrue(requireNotNull(fixture.actuator.lastDesired).generation > firstGeneration)
        assertTrue(emitScheduledGyro(fixture) is EmitBatchResult.Accepted)
        assertEquals(2, runtime.snapshot.value.lifetimeDataEventCount)

        fixture.actuator.failTerminal("SENSOR_UNAVAILABLE")
        runCurrent()
        assertEquals(ExperimentState.PAUSED, runtime.snapshot.value.state)
        assertNull(runtime.captureToken())
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.actuator.health().status)
        runtime.close()
    }

    @Test
    fun requiredScheduledCollectorActivationFailureStillFailsClosed() = runTest {
        val fixture = scheduledCollectorFixture(backgroundScope)
        val runtime = fixture.runtime
        runtime.initialize()
        completeSetup(runtime)
        runtime.start()
        fixture.actuator.failNextVerification = true
        val opening = scheduledTimerAt(runtime, "2026-09-07T12:00:00Z")
        fixture.clocks.advanceToWallMillis(Instant.parse("2026-09-07T12:00:00Z").toEpochMilli())

        assertTrue(runtime.onTimerDue(opening.id, opening.generation) is RuntimeCommandResult.FailedClosed)
        assertEquals(ExperimentState.PAUSED, runtime.snapshot.value.state)
        assertNull(runtime.captureToken())
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.actuator.health().status)
        assertEquals(0, fixture.actuator.resumeCount)
        runtime.close()
    }

    @Test
    fun startCreatesVerifiedEpochAndOnlyThenOpensAdmission() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.battery.admissionProbe = { fixture.runtime.captureToken() }

        assertTrue(fixture.runtime.initialize() is RuntimeInitializationResult.Ready)
        completeSetup(fixture.runtime)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.start())

        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertTrue(fixture.runtime.snapshot.value.admissionOpen)
        assertEquals(2, fixture.battery.resumeCount + fixture.traffic.resumeCount)
        assertTrue(fixture.battery.tokensDuringResume.all { it == null })
        assertTrue(fixture.battery.tokensAfterAdmissionOpened.all { it != null })
        assertTrue(fixture.store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "CONDITION_EPOCH_ACTIVATED"
        })
        assertTrue(fixture.store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "STUDY_RUNNING"
        })
    }

    @Test
    fun localNetworkPermissionFailureIsAuditedAsVpnPermissionRevocation() = runTest {
        val fixture = fixture(backgroundScope, withTrafficAudit = true)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()

        fixture.traffic.failTerminal("LOCAL_NETWORK_PERMISSION_REQUIRED")
        runCurrent()

        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        val removal = fixture.store.commits.flatMap(EngineCommit::events).single {
            it.type.eventType == "TRAFFIC_SHAPING_PROFILE_REMOVED"
        }
        assertEquals("VPN_PERMISSION_REVOKED", removal.fields["removal_reason"])
        assertTrue(fixture.battery.releaseCount > 0)
        assertTrue(fixture.traffic.releaseCount > 0)
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.battery.health().status)
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.traffic.health().status)
    }

    @Test
    fun failedSecondResourceApplyContainsEverySideEffectAndPersistsCleanupUntilRecovery() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        fixture.battery.failNextVerification = true
        fixture.battery.invalidReleaseAttempts = 2

        assertTrue(
            fixture.runtime.emitBatch(
                requireNotNull(fixture.runtime.captureToken()),
                batteryBatch(fixture.clock.now()),
            ) is EmitBatchResult.Accepted,
        )
        runCurrent()

        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        assertNull(fixture.store.pending)
        assertEquals(ResourceHealthStatus.APPLIED, fixture.battery.health().status)
        assertEquals(ResourceHealthStatus.INACTIVE, fixture.traffic.health().status)
        assertTrue(
            requireNotNull(fixture.store.runtime).components.keys.any {
                it.kind == cool.jacoblin.particeps.core.model.RuntimeComponentKind.RESOURCE_CLEANUP
            },
        )
        fixture.runtime.close()

        val recovered = fixture(backgroundScope, fixture.store)
        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.PAUSED, recovered.runtime.snapshot.value.state)
        assertTrue(
            requireNotNull(fixture.store.runtime).components.keys.none {
                it.kind == cool.jacoblin.particeps.core.model.RuntimeComponentKind.RESOURCE_CLEANUP
            },
        )
        assertTrue(resourceStates(fixture.store).all { it.status == cool.jacoblin.particeps.core.resource.AppliedResourceStatus.INACTIVE })
    }

    @Test
    fun crashAfterPausedCommitRecoversAppliedResourcesBeforeAnyResume() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val releaseEntered = CompletableDeferred<Unit>()
        val neverReturn = CompletableDeferred<Unit>()
        fixture.battery.releaseHook = {
            releaseEntered.complete(Unit)
            neverReturn.await()
        }

        fixture.traffic.failTerminal("NATIVE_ENGINE_FAILED")
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { releaseEntered.await() }
        }
        assertEquals(ExperimentState.PAUSED, fixture.store.runtime?.state)
        assertTrue(resourceStates(fixture.store).any { it.status == cool.jacoblin.particeps.core.resource.AppliedResourceStatus.APPLIED })
        fixture.runtime.close()
        runCurrent()

        val recovered = fixture(backgroundScope, fixture.store)
        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.PAUSED, recovered.runtime.snapshot.value.state)
        assertTrue(resourceStates(fixture.store).all { it.status == cool.jacoblin.particeps.core.resource.AppliedResourceStatus.INACTIVE })
        assertEquals(EngineInputKind.RESOURCE_RESULT, fixture.store.commits.last().inputKind)
    }

    @Test
    fun periodicResourceAuditIsEpochScopedDurableAndFinalizedBeforeDeactivation() = runTest {
        val fixture = fixture(backgroundScope, withTrafficAudit = true)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.start())

        val epochId = requireNotNull(fixture.runtime.snapshot.value.conditionEpochId)
        val activationEvents = fixture.store.commits.last().events
        assertTrue(
            activationEvents.indexOfFirst { it.type.eventType == "CONDITION_EPOCH_ACTIVATED" } <
                activationEvents.indexOfFirst { it.type.eventType == "TRAFFIC_SHAPING_PROFILE_APPLIED" },
        )
        val firstTimer = fixture.runtime.pendingTimers().single { it.producerKey.startsWith("resource-audit:") }
        assertTrue(firstTimer.producerKey.startsWith("resource-audit:"))
        assertEquals(
            listOf(firstTimer.id),
            fixture.timerWakeups.scheduled.filter { it.producerKey.startsWith("resource-audit:") }.map(DurableTimer::id),
        )

        fixture.clock.advanceMillis(60_001)
        assertEquals(
            RuntimeCommandResult.Success,
            fixture.runtime.onTimerDue(firstTimer.id, firstTimer.generation),
        )
        assertEquals(
            listOf(
                "TIMER_DUE",
                "TRAFFIC_SHAPING_SNAPSHOT",
                "TIMER_RETIRED",
                "TIMER_SCHEDULED",
            ),
            fixture.store.commits.last().events.map { it.type.eventType },
        )
        assertTrue(fixture.store.commits.last().events.all { it.conditionEpochId == epochId })
        val successor = fixture.runtime.pendingTimers().single { it.producerKey.startsWith("resource-audit:") }
        assertNotEquals(firstTimer.id, successor.id)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.onTimerDue(firstTimer.id, firstTimer.generation))

        assertEquals(RuntimeCommandResult.Success, fixture.runtime.pause())
        val boundaryEvents = fixture.store.commits.dropLast(1).last().events
        val snapshotIndex = boundaryEvents.indexOfFirst {
            it.type.eventType == "TRAFFIC_SHAPING_SNAPSHOT" && it.fields["snapshot_reason"] == "EPOCH_BOUNDARY"
        }
        val removedIndex = boundaryEvents.indexOfFirst { it.type.eventType == "TRAFFIC_SHAPING_PROFILE_REMOVED" }
        val epochEndedIndex = boundaryEvents.indexOfFirst { it.type.eventType == "CONDITION_EPOCH_DEACTIVATED" }
        assertTrue(snapshotIndex in 0 until removedIndex)
        assertTrue(removedIndex in 0 until epochEndedIndex)
        assertTrue(boundaryEvents.all { it.conditionEpochId == epochId })
        assertTrue(fixture.runtime.pendingTimers().none { it.producerKey.startsWith("resource-audit:") })
        assertTrue(successor.id in fixture.timerWakeups.retired)
        assertEquals(
            RuntimeCommandResult.Success,
            fixture.runtime.onTimerDue(successor.id, successor.generation),
        )
    }

    @Test
    fun causalBatchIsDurablyStagedThenRotatesTheWholeResourceEpoch() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val oldEpoch = fixture.runtime.snapshot.value.conditionEpochId
        val token = requireNotNull(fixture.runtime.captureToken())

        val result = fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now()))
        runCurrent()

        assertTrue(result is EmitBatchResult.Accepted)
        assertNotEquals(oldEpoch, fixture.runtime.snapshot.value.conditionEpochId)
        assertNull(fixture.store.pending)
        assertTrue(fixture.store.commits.any { it.consumedPendingInputSha256 != null })
        assertEquals("slow", fixture.traffic.lastDesired?.profile?.id)
        assertTrue(fixture.battery.suspendCount > 0)
        assertTrue(fixture.traffic.suspendCount > 0)
        assertEquals(
            EmitBatchResult.RejectedByAdmissionGate,
            fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now())),
        )
        val causalCommit = fixture.store.commits.single { it.consumedPendingInputSha256 != null }
        assertEquals(oldEpoch, causalCommit.sourceObservations.single().conditionEpochId)
        assertEquals(oldEpoch, causalCommit.events.first { it.type.eventType == "BATTERY_STATE" }.conditionEpochId)
    }

    @Test
    fun triggerInsideAMergedCallbackBatchIsStagedFromTheTriggerWithSequentialEpochAttribution() = runTest {
        val percentages = listOf(50, 42, 51)

        // Merged: the real collector submits three already-queued callbacks as one observation.
        val merged = fixture(backgroundScope)
        merged.runtime.initialize()
        completeSetup(merged.runtime)
        merged.runtime.start()
        val mergedOldEpoch = requireNotNull(merged.runtime.snapshot.value.conditionEpochId)
        var stagedEvents = 0
        merged.store.afterPendingStaged = {
            stagedEvents = requireNotNull(merged.store.pending).submissions.sumOf { it.events.size }
        }
        val collector = RuntimeCallbackCollector(
            CollectorContext(
                scope = backgroundScope,
                eventSink = merged.runtime,
                clocks = merged.clock,
                sourceContract = requireNotNull(ProtocolEventSourceRegistry[BATTERY_SOURCE.value]),
                resourceGeneration = 1,
                tokenEncoder = StudyScopedTokenEncoder { _, _ -> "0".repeat(64) },
                requiresPromptCommits = true,
            ),
            consumerDispatcher = StandardTestDispatcher(testScheduler),
        )
        collector.start()
        collector.onAdmissionOpened()
        percentages.forEach(collector::trigger)
        runCurrent()

        // The callback captured before the trigger commits first, as it did when offered alone;
        // the trigger and the callback queued behind it are staged together from the trigger.
        assertEquals(2, stagedEvents)
        assertNull(merged.store.pending)
        val leading = merged.store.commits.single { commit ->
            commit.consumedPendingInputSha256 == null && commit.events.any { it.type == BATTERY_EVENT }
        }
        assertEquals(1, leading.sourceObservations.single().eventCount)
        assertEquals(0L, leading.sourceObservations.single().producerOrdinal)
        assertEquals(
            listOf("50"),
            leading.events.filter { it.type == BATTERY_EVENT }.map { it.fields.getValue("percentage") },
        )
        val mergedBarrier = merged.store.commits.single { it.consumedPendingInputSha256 != null }
        assertTrue(mergedBarrier.commitSequence > leading.commitSequence)
        val causal = mergedBarrier.sourceObservations.single()
        assertEquals(2, causal.eventCount)
        assertEquals(1L, causal.producerOrdinal)
        assertEquals(mergedOldEpoch, causal.conditionEpochId)
        assertEquals(
            listOf("42", "51"),
            mergedBarrier.events.filter { it.type == BATTERY_EVENT }.map { it.fields.getValue("percentage") },
        )
        collector.stop()

        // Sequential: the per-callback offers the consumer made before merging.
        val sequential = fixture(backgroundScope)
        sequential.runtime.initialize()
        completeSetup(sequential.runtime)
        sequential.runtime.start()
        val sequentialOldEpoch = requireNotNull(sequential.runtime.snapshot.value.conditionEpochId)
        val token = requireNotNull(sequential.runtime.captureToken())
        // Every callback was observed while queued, before the consumer reached the trigger.
        val queued = percentages.mapIndexed { ordinal, percentage ->
            batteryBatch(sequential.clock.now(), percentage).copy(producerOrdinal = ordinal.toLong())
        }
        queued.forEach { batch ->
            assertTrue(sequential.runtime.emitBatch(token, batch) is EmitBatchResult.Accepted)
        }
        runCurrent()

        assertNull(sequential.store.pending)
        assertEquals(mergedOldEpoch, sequentialOldEpoch)
        fun Fixture.attribution() = store.commits
            .flatMap { it.events }
            .filter { it.type == BATTERY_EVENT }
            .associate { it.fields.getValue("percentage") to it.conditionEpochId }
        assertEquals(percentages.associate { it.toString() to mergedOldEpoch }, merged.attribution())
        assertEquals(merged.attribution(), sequential.attribution())
        assertEquals(sequential.runtime.snapshot.value.conditionEpochId, merged.runtime.snapshot.value.conditionEpochId)
        assertNotEquals(mergedOldEpoch, merged.runtime.snapshot.value.conditionEpochId)
        assertEquals("slow", merged.traffic.lastDesired?.profile?.id)
        assertEquals("slow", sequential.traffic.lastDesired?.profile?.id)
    }

    @Test
    fun setAndResetInsideOneMergedBatchReconcileOnceWithoutRotatingTheEpoch() = runTest {
        // Merged: the latch sets and resets inside one observation, so the final state is unchanged.
        val merged = fixture(backgroundScope)
        merged.runtime.initialize()
        completeSetup(merged.runtime)
        merged.runtime.start()
        val oldEpoch = requireNotNull(merged.runtime.snapshot.value.conditionEpochId)
        val mergedToken = requireNotNull(merged.runtime.captureToken())
        val first = batteryBatch(merged.clock.now(), 42).events.single()
        val second = batteryBatch(merged.clock.now(), 43).events.single()
        val batch = batteryBatch(merged.clock.now()).copy(events = listOf(first, second))

        assertTrue(merged.runtime.emitBatch(mergedToken, batch) is EmitBatchResult.Accepted)
        runCurrent()

        assertFalse(merged.store.pendingStaged.isCompleted)
        assertEquals(oldEpoch, merged.runtime.snapshot.value.conditionEpochId)
        assertEquals("baseline", merged.traffic.lastDesired?.profile?.id)
        val commit = merged.store.commits.single { commit -> commit.events.any { it.type == BATTERY_EVENT } }
        assertEquals(2, commit.sourceObservations.single().eventCount)
        assertTrue(commit.events.filter { it.type == BATTERY_EVENT }.all { it.conditionEpochId == oldEpoch })

        // Per-callback offers: the reset is buffered behind the staged set and reduced before it.
        val sequential = fixture(backgroundScope)
        sequential.runtime.initialize()
        completeSetup(sequential.runtime)
        sequential.runtime.start()
        val token = requireNotNull(sequential.runtime.captureToken())
        val queued = listOf(42, 43).mapIndexed { ordinal, percentage ->
            batteryBatch(sequential.clock.now(), percentage).copy(producerOrdinal = ordinal.toLong())
        }
        queued.forEach { assertTrue(sequential.runtime.emitBatch(token, it) is EmitBatchResult.Accepted) }
        runCurrent()

        assertNotEquals(oldEpoch, sequential.runtime.snapshot.value.conditionEpochId)
        assertEquals("slow", sequential.traffic.lastDesired?.profile?.id)
    }

    @Test
    fun callbacksMergedBeforeATriggerAreReducedBeforeInputTheBarrierDrains() = runTest {
        // One rising edge of the latch notifies. A reset merged ahead of the set it precedes must
        // not be reduced after a set that the barrier drains, or the latch would rise twice.
        val fixture = fixture(backgroundScope, notifyTrigger = Trigger.ConditionRisingEdge(PERCENTAGE_LATCH))
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val oldEpoch = requireNotNull(fixture.runtime.snapshot.value.conditionEpochId)
        val token = requireNotNull(fixture.runtime.captureToken())
        val reset = batteryBatch(fixture.clock.now(), 43).events.single()
        val set = batteryBatch(fixture.clock.now(), 42).events.single()
        // Observed before the barrier's boundary but submitted only while the barrier drains.
        val drained = batteryBatch(fixture.clock.now(), 42).copy(producerOrdinal = 2)

        val merged = fixture.runtime.emitBatch(
            token,
            batteryBatch(fixture.clock.now()).copy(events = listOf(reset, set)),
        )

        // Only the reset is recorded; nothing is staged until the collector offers the rest.
        assertEquals(1, (merged as EmitBatchResult.Accepted).recordedEvents)
        assertNull(fixture.store.pending)
        val leading = fixture.store.commits.last()
        assertEquals(EngineInputKind.SOURCE_OBSERVATION, leading.inputKind)
        assertEquals(
            listOf("43"),
            leading.events.filter { it.type == BATTERY_EVENT }.map { it.fields.getValue("percentage") },
        )
        val rest = batteryBatch(fixture.clock.now()).copy(producerOrdinal = 1, events = listOf(set))
        assertEquals(1, (fixture.runtime.emitBatch(token, rest) as EmitBatchResult.Accepted).recordedEvents)
        assertNotNull(fixture.store.pending)
        // A set submitted while the barrier drains is reduced before the staged trigger.
        assertTrue(fixture.runtime.emitBatch(token, drained) is EmitBatchResult.Accepted)
        runCurrent()

        assertNull(fixture.store.pending)
        assertNotEquals(oldEpoch, fixture.runtime.snapshot.value.conditionEpochId)
        assertEquals("slow", fixture.traffic.lastDesired?.profile?.id)
        val barrier = fixture.store.commits.single { it.consumedPendingInputSha256 != null }
        assertEquals(listOf(1L, 2L), barrier.sourceObservations.map { it.producerOrdinal })
        val audits = fixture.store.commits.flatMap { it.events }
            .filter { it.type.eventType in setOf("AUTOMATION_MATCHED", "AUTOMATION_SUPPRESSED") }
        assertEquals(listOf("AUTOMATION_MATCHED"), audits.map { it.type.eventType })
        assertEquals(1, fixture.store.commits.flatMap { it.events }.count { it.type.eventType == "ACTION_REQUESTED" })
    }

    @Test
    fun mergedWindowSlidesRetireEachTimerGenerationOnceAndWakeOnlyTheLast() = runTest {
        // A one-second count window never reaches its threshold, so every sample only slides it.
        val window = StateCondition.WindowThreshold(
            EventMatcher(BATTERY_EVENT),
            windowSeconds = 1,
            EvaluationClock.OBSERVED_RESEARCH_TIME,
            Aggregate.Count,
            NumericComparison(FieldOperator.GTE, "100"),
        )
        val fixture = fixture(backgroundScope, trafficCondition = window)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val token = requireNotNull(fixture.runtime.captureToken())
        fun Fixture.windowTimerEvents(commit: EngineCommit) = commit.events.filter {
            it.type.sourceId.value == "timer.v1" && it.fields.getValue("producer_key").startsWith("condition:")
        }

        assertTrue(fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now(), 50)) is EmitBatchResult.Accepted)
        val armed = fixture.windowTimerEvents(fixture.store.commits.last()).single()
        assertEquals("TIMER_SCHEDULED", armed.type.eventType)
        val timerId = armed.fields.getValue("timer_id")
        val armedGeneration = armed.fields.getValue("generation").toULong()
        val scheduledBefore = fixture.timerWakeups.scheduled.size
        val retiredBefore = fixture.timerWakeups.retiredGenerations.size

        // Three samples queued together, each more than a window after the one before it.
        val samples = (1..3).map { index ->
            fixture.clock.advanceMillis(1_100)
            batteryBatch(fixture.clock.now(), 50 + index).events.single()
        }
        val merged = batteryBatch(fixture.clock.now()).copy(producerOrdinal = 1, events = samples)
        assertEquals(3, (fixture.runtime.emitBatch(token, merged) as EmitBatchResult.Accepted).recordedEvents)
        runCurrent()

        // The reducer replaced the timer three times; the commit records only the net change.
        val commit = fixture.store.commits.last()
        assertEquals(3, commit.sourceObservations.single().eventCount)
        val timerEvents = fixture.windowTimerEvents(commit)
        assertEquals(listOf("TIMER_RETIRED", "TIMER_SCHEDULED"), timerEvents.map { it.type.eventType })
        assertTrue(timerEvents.all { it.fields.getValue("timer_id") == timerId })
        assertEquals(armedGeneration.toString(), timerEvents[0].fields.getValue("generation"))
        assertEquals("CANCELLED", timerEvents[0].fields.getValue("retirement_reason"))
        val finalGeneration = armedGeneration + 3uL
        assertEquals(finalGeneration.toString(), timerEvents[1].fields.getValue("generation"))
        val durable = commit.mutations.filter { it.key.kind == RuntimeComponentKind.TIMER }
        assertEquals(listOf(timerId), durable.map { it.key.id })
        // WorkManager retires the armed generation and wakes only the final one.
        assertEquals(listOf(timerId to armedGeneration), fixture.timerWakeups.retiredGenerations.drop(retiredBefore))
        assertEquals(
            listOf(timerId to finalGeneration),
            fixture.timerWakeups.scheduled.drop(scheduledBefore).map { it.id to it.generation },
        )
    }

    @Test
    fun startRecordsEveryTimerIntentOfItsSingleInputReductionInTheReducersOrder() = runTest {
        // Three study-local windows each arm a condition timer when the study starts.
        val windows = StateCondition.All(
            listOf(
                StateCondition.StudyLocalWindow(1, 5, "12:00", "17:00"),
                StateCondition.StudyLocalWindow(1, 5, "18:00", "20:00"),
                StateCondition.StudyLocalWindow(2, 5, "08:00", "09:00"),
            ),
        )
        val fixture = fixture(backgroundScope, trafficCondition = windows)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        val beforeStart = requireNotNull(fixture.store.runtime)
        val committedBeforeStart = fixture.store.commits.size
        fixture.runtime.start()

        val activating = fixture.store.commits[committedBeforeStart]
        assertTrue(activating.events.any { it.type.eventType == "STUDY_STARTED" })
        val checkpoint = AutomationCheckpointCodec.decode(
            beforeStart.components
                .filterKeys { it.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT && it.id.startsWith("main") }
                .toSortedMap()
                .values
                .joinToString(separator = ""),
        )
        val reduction = AutomationReducer().reduceBatch(
            fixture.program,
            checkpoint,
            listOf(
                ReducerInput.Lifecycle(
                    checkpoint.evaluatedThroughSequence + 1,
                    reducerClock(requireNotNull(activating.successorProjection.clockCheckpoint)),
                    StudySessionState.ACTIVATING,
                ),
            ),
        )
        assertEquals(activating.resultingCheckpointSha256, reduction.checkpoint.digest())
        // One event per intent, as RC13 recorded: each schedule, and each retirement of a prior timer.
        val perIntent = reduction.timerIntents.mapNotNull { intent ->
            when (intent) {
                is TimerIntent.Schedule -> Triple("TIMER_SCHEDULED", intent.timer.id, intent.timer.generation)
                is TimerIntent.Retire -> checkpoint.timers[intent.timerId]?.let {
                    Triple("TIMER_RETIRED", it.id, it.generation)
                }
            }
        }
        assertEquals(3, perIntent.size)
        val recorded = activating.events
            .filter { it.type.sourceId.value == "timer.v1" && it.fields.getValue("producer_key").startsWith("condition:") }
            .map { Triple(it.type.eventType, it.fields.getValue("timer_id"), it.fields.getValue("generation").toULong()) }
        assertEquals(perIntent, recorded)
        assertEquals(
            perIntent.map { it.second to it.third },
            fixture.timerWakeups.scheduled.filter { it.producerKey.startsWith("condition:") }.map { it.id to it.generation },
        )
    }

    @Test
    fun mergedBatchThatArmsAndCancelsAWindowTimerRecordsAndWakesNothingForIt() = runTest {
        // Only a 50 % sample enters the window, so a later sample of another value empties it.
        val window = StateCondition.WindowThreshold(
            EventMatcher(BATTERY_EVENT, listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "50"))),
            windowSeconds = 1,
            EvaluationClock.OBSERVED_RESEARCH_TIME,
            Aggregate.Count,
            NumericComparison(FieldOperator.GTE, "100"),
        )
        val fixture = fixture(backgroundScope, trafficCondition = window)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val token = requireNotNull(fixture.runtime.captureToken())
        val scheduledBefore = fixture.timerWakeups.scheduled.size
        val retiredBefore = fixture.timerWakeups.retiredGenerations.size

        val entering = batteryBatch(fixture.clock.now(), 50).events.single()
        fixture.clock.advanceMillis(1_100)
        val leaving = batteryBatch(fixture.clock.now(), 51).events.single()
        val merged = batteryBatch(fixture.clock.now()).copy(events = listOf(entering, leaving))
        assertEquals(2, (fixture.runtime.emitBatch(token, merged) as EmitBatchResult.Accepted).recordedEvents)
        runCurrent()

        // The reducer armed the window timer and retired it within the batch.
        val commit = fixture.store.commits.last()
        assertEquals(2, commit.sourceObservations.single().eventCount)
        assertTrue(
            commit.events.none {
                it.type.sourceId.value == "timer.v1" && it.fields.getValue("producer_key").startsWith("condition:")
            },
        )
        assertTrue(commit.mutations.none { it.key.kind == RuntimeComponentKind.TIMER })
        assertEquals(scheduledBefore, fixture.timerWakeups.scheduled.size)
        assertEquals(retiredBefore, fixture.timerWakeups.retiredGenerations.size)
    }

    @Test
    fun recoveryFromRunningRetiresEachWindowTimerOnceAndWakesNoIntermediateGeneration() = runTest {
        // Recovery reduces a quality gap, whose reset re-arms the window timer while the study
        // still runs, and then PAUSING, which retires that re-armed generation in the same commit.
        val window = StateCondition.StudyLocalWindow(1, 5, "12:00", "17:00")
        val store = InMemoryStudyStore()
        val first = fixture(backgroundScope, store, trafficCondition = window)
        first.runtime.initialize()
        completeSetup(first.runtime)
        first.runtime.start()
        val armed = first.runtime.pendingTimers().single { it.producerKey.startsWith("condition:") }
        val beforeRecovery = requireNotNull(store.runtime)
        first.runtime.close()

        val recovered = fixture(backgroundScope, store, trafficCondition = window)
        val result = recovered.runtime.initialize()

        assertTrue(result is RuntimeInitializationResult.Ready && result.recoveredFailClosed)
        val recovery = store.commits.single { it.inputKind == EngineInputKind.RECOVERY }
        val checkpoint = AutomationCheckpointCodec.decode(
            beforeRecovery.components
                .filterKeys { it.kind == RuntimeComponentKind.AUTOMATION_CHECKPOINT && it.id.startsWith("main") }
                .toSortedMap()
                .values
                .joinToString(separator = ""),
        )
        val clock = reducerClock(requireNotNull(recovery.successorProjection.clockCheckpoint))
        val reduction = AutomationReducer().reduceBatch(
            recovered.program,
            checkpoint,
            listOf(
                ReducerInput.QualityGap(checkpoint.evaluatedThroughSequence + 1, clock, EventSourceId("study_runtime.v1")),
                ReducerInput.Lifecycle(checkpoint.evaluatedThroughSequence + 2, clock, StudySessionState.PAUSING),
                ReducerInput.Lifecycle(checkpoint.evaluatedThroughSequence + 3, clock, StudySessionState.PAUSED),
            ),
        )
        assertEquals(recovery.resultingCheckpointSha256, reduction.checkpoint.digest())
        val rearm = reduction.timerIntents.filterIsInstance<TimerIntent.Schedule>().single()
        assertEquals(armed.id, rearm.timer.id)
        assertEquals(armed.generation + 1uL, rearm.timer.generation)
        assertEquals(
            listOf(TimerIntent.Retire(armed.id, armed.generation), TimerIntent.Retire(armed.id, rearm.timer.generation), rearm),
            reduction.timerIntents,
        )

        // The commit records only the retirement of the timer it began with, and wakes nothing new.
        val timerEvents = recovery.events.filter {
            it.type.sourceId.value == "timer.v1" && it.fields.getValue("producer_key").startsWith("condition:")
        }
        assertEquals(listOf("TIMER_RETIRED"), timerEvents.map { it.type.eventType })
        assertEquals(armed.id, timerEvents.single().fields.getValue("timer_id"))
        assertEquals(armed.generation.toString(), timerEvents.single().fields.getValue("generation"))
        assertEquals("QUALITY_GAP_RESET", timerEvents.single().fields.getValue("retirement_reason"))
        assertTrue(recovery.mutations.none { it.key.kind == RuntimeComponentKind.TIMER && it.operation == RuntimeMutationOperation.UPSERT })
        assertEquals(
            listOf(armed.id to armed.generation),
            recovered.timerWakeups.retiredGenerations.filter { it.first == armed.id },
        )
        assertTrue(recovered.timerWakeups.scheduled.none { it.id == armed.id })
    }

    @Test
    fun stagedCausalCallbackUnwindsBeforeBarrierDrainsItsQueuedLiveEvent() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val oldEpoch = requireNotNull(fixture.runtime.snapshot.value.conditionEpochId)
        val blockingSink = BlockingFirstEventSink(fixture.runtime)
        val callback = RuntimeCallbackCollector(
            CollectorContext(
                scope = backgroundScope,
                eventSink = blockingSink,
                clocks = fixture.clock,
                sourceContract = requireNotNull(ProtocolEventSourceRegistry[BATTERY_SOURCE.value]),
                resourceGeneration = 1,
                tokenEncoder = StudyScopedTokenEncoder { _, _ -> "0".repeat(64) },
                requiresPromptCommits = true,
            ),
        )
        val barrierAdmissionOpened = CompletableDeferred<Unit>()
        callback.start()
        callback.onAdmissionOpened()
        fixture.battery.suspendHook = { callback.pause() }
        fixture.battery.resumeHook = { callback.resume() }
        fixture.battery.admissionOpenedHook = {
            callback.onAdmissionOpened()
            barrierAdmissionOpened.complete(Unit)
        }

        callback.trigger(42)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { blockingSink.firstSubmissionEntered.await() }
        }
        callback.trigger(44)
        blockingSink.releaseFirstSubmission.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { fixture.store.pendingStaged.await() }
        }
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { barrierAdmissionOpened.await() }
        }
        runCurrent()

        assertNotEquals(oldEpoch, fixture.runtime.snapshot.value.conditionEpochId)
        assertNull(fixture.store.pending)
        val barrierCommit = fixture.store.commits.single { it.consumedPendingInputSha256 != null }
        assertEquals(listOf(0L, 1L), barrierCommit.sourceObservations.map { it.producerOrdinal })
        assertTrue(barrierCommit.sourceObservations.all { it.coverage == null })
        assertTrue(
            requireNotNull(barrierCommit.sourceObservations[0].firstEventSequence) >
                requireNotNull(barrierCommit.sourceObservations[1].firstEventSequence),
        )
        assertEquals(
            listOf("44", "42"),
            barrierCommit.events.filter { it.type == BATTERY_EVENT }.map { it.fields.getValue("percentage") },
        )

        fixture.battery.suspendHook = null
        fixture.battery.resumeHook = null
        fixture.battery.admissionOpenedHook = null
        callback.stop()
    }

    @Test
    fun terminalFailureAfterDurableStageReturnsAcceptedAndRecoversOffTheEmitter() = runTest {
        val store = InMemoryStudyStore()
        val fixture = fixture(backgroundScope, store)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val token = requireNotNull(fixture.runtime.captureToken())
        val continueAfterStage = CompletableDeferred<Unit>()
        store.afterPendingStaged = { continueAfterStage.await() }

        val emission = async {
            fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now()))
        }
        runCurrent()
        assertTrue(store.pendingStaged.isCompleted)
        fixture.traffic.failTerminal("NATIVE_ENGINE_FAILED")
        continueAfterStage.complete(Unit)
        runCurrent()

        assertTrue(emission.await() is EmitBatchResult.Accepted)
        advanceUntilIdle()
        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        assertNull(store.pending)
        val recovery = store.commits.single { it.consumedPendingInputSha256 != null }
        assertTrue(recovery.events.any { it.type == BATTERY_EVENT })
    }

    @Test
    fun qualityGapCommitsBeforeIndependentBarrierResetsTheResource() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val first = batteryBatch(fixture.clock.now()).copy(
            coverage = SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "0", "100"),
        )
        assertTrue(
            fixture.runtime.emitBatch(
                requireNotNull(fixture.runtime.captureToken()),
                first,
            ) is EmitBatchResult.Accepted,
        )
        runCurrent()
        assertEquals("slow", fixture.traffic.lastDesired?.profile?.id)

        val discontinuous = batteryBatch(fixture.clock.now()).copy(
            producerOrdinal = 1,
            coverage = SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "200", "300"),
        )
        val result = fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            discontinuous,
        )
        assertEquals(
            EmitBatchResult.SourceQualityGap(
                cool.jacoblin.particeps.core.collector.SourceQualityGapReason.RETROSPECTIVE_COVERAGE_GAP,
            ),
            result,
        )
        runCurrent()

        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertEquals("baseline", fixture.traffic.lastDesired?.profile?.id)
        assertTrue(fixture.store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "SOURCE_QUALITY_GAP"
        })
        assertEquals(
            1,
            fixture.store.commits.flatMap(EngineCommit::events).count { it.type == BATTERY_EVENT },
        )
    }

    @Test
    fun occurrenceActionUsesDurableDeterministicOutboxAcrossClaims() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val token = requireNotNull(fixture.runtime.captureToken())
        fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now()))
        runCurrent()

        val ready = fixture.runtime.pendingActions().single()
        val firstClaim = fixture.runtime.claimAction(ready.actionId)
        val reconciliationClaim = fixture.runtime.claimAction(ready.actionId)

        assertEquals(ready.actionId, firstClaim?.actionId)
        assertEquals(
            DeterministicIds.actionId(
                CONFIG_DIGEST,
                "notify-battery",
                "prompt",
                "event_match",
                "event:3",
                "",
            ),
            ready.actionId,
        )
        assertEquals(firstClaim, reconciliationClaim)
        assertEquals(RuntimeActionState.CLAIMED, reconciliationClaim?.state)
        assertEquals(
            RuntimeCommandResult.Success,
            fixture.runtime.recordActionResult(ready.actionId, succeeded = true),
        )
        assertTrue(fixture.runtime.pendingActions().isEmpty())
        assertTrue(fixture.store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "ACTION_SUCCEEDED"
        })
    }

    @Test
    fun claimQueuedBehindAStagedSourceBarrierWaitsForItAndClaimsOnce() = runTest {
        val store = InMemoryStudyStore()
        val fixture = fixture(backgroundScope, store)
        val (ready, token) = requestPromptThroughFirstBarrier(fixture)
        val readyAttempts = fixture.actionNotifier.readyAttempts.toList()
        val releaseStage = CompletableDeferred<Unit>()
        store.afterPendingStaged = { releaseStage.await() }

        // 43 resets the latch. Its emitter holds the runtime mutex while the input is staged, so the
        // claim queues on the mutex before the second barrier is enqueued.
        val emission = async {
            fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now(), 43).copy(producerOrdinal = 1))
        }
        runCurrent()
        assertNotNull(store.pending)
        val claim = async { fixture.runtime.claimAction(ready.actionId) }
        runCurrent()
        assertFalse(claim.isCompleted)
        releaseStage.complete(Unit)
        advanceUntilIdle()

        assertTrue(emission.await() is EmitBatchResult.Accepted)
        val claimed = claim.await()
        assertEquals(ready.copy(state = RuntimeActionState.CLAIMED), claimed)
        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertNull(store.pending)
        val consuming = store.commits.indexOfLast { it.consumedPendingInputSha256 != null }
        assertEquals(
            listOf(EngineInputKind.RESOURCE_RESULT, EngineInputKind.ACTION_RESULT),
            store.commits.drop(consuming + 1).map(EngineCommit::inputKind),
        )
        assertTrue(store.commits.flatMap(EngineCommit::events).none { it.type.eventType == "STUDY_SAFETY_PAUSED" })
        val revision = fixture.runtime.snapshot.value.revision
        assertEquals(claimed, fixture.runtime.claimAction(ready.actionId))
        assertEquals(revision, fixture.runtime.snapshot.value.revision)
        assertEquals(readyAttempts, fixture.actionNotifier.readyAttempts)
        assertEquals(
            RuntimeCommandResult.Success,
            fixture.runtime.recordActionResult(ready.actionId, succeeded = true),
        )
    }

    @Test
    fun claimQueuedBehindAStagedBarrierThatFailsClosedReturnsNull() = runTest {
        val store = InMemoryStudyStore()
        val fixture = fixture(backgroundScope, store)
        val (ready, token) = requestPromptThroughFirstBarrier(fixture)
        val releaseStage = CompletableDeferred<Unit>()
        store.afterPendingStaged = { releaseStage.await() }

        val emission = async {
            fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now(), 43).copy(producerOrdinal = 1))
        }
        runCurrent()
        val claim = async { fixture.runtime.claimAction(ready.actionId) }
        runCurrent()
        // The second barrier's apply fails verification, so the barrier safety-pauses the study.
        fixture.traffic.failNextVerification = true
        releaseStage.complete(Unit)
        advanceUntilIdle()

        assertTrue(emission.await() is EmitBatchResult.Accepted)
        assertNull(claim.await())
        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        val paused = store.commits.flatMap(EngineCommit::events).last { it.type.eventType == "STUDY_SAFETY_PAUSED" }
        assertEquals(
            SafetyPauseReason.REQUIRED_RESOURCE_FAILURE.transitionReason.name,
            paused.fields["transition_reason"],
        )
        assertTrue(store.commits.none { it.inputKind == EngineInputKind.ACTION_RESULT })
        assertEquals(RuntimeActionState.READY, fixture.runtime.pendingActions().single().state)
        assertTrue(ready.actionId in fixture.actionNotifier.inactiveCalls.last())
    }

    @Test
    fun claimCancelledWhileWaitingForABarrierLeavesAdmissionAndLifecycleUntouched() = runTest {
        val store = InMemoryStudyStore()
        val fixture = fixture(backgroundScope, store)
        val (ready, token) = requestPromptThroughFirstBarrier(fixture)
        // Observed before the second barrier's boundary, and submitted only while it drains.
        val late = batteryBatch(fixture.clock.now(), 44).copy(producerOrdinal = 2)
        val suspendEntered = CompletableDeferred<Unit>()
        val releaseSuspend = CompletableDeferred<Unit>()
        fixture.traffic.suspendHook = {
            suspendEntered.complete(Unit)
            releaseSuspend.await()
        }

        // The barrier consumer holds the runtime mutex while it waits inside suspendAt.
        assertTrue(
            fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now(), 43).copy(producerOrdinal = 1))
                is EmitBatchResult.Accepted,
        )
        runCurrent()
        assertTrue(suspendEntered.isCompleted)
        val claim = async { fixture.runtime.claimAction(ready.actionId) }
        runCurrent()
        claim.cancelAndJoin()

        // A cancelled wait closed nothing, so the drain still takes the pre-boundary input.
        assertTrue(fixture.runtime.emitBatch(token, late) is EmitBatchResult.Accepted)
        releaseSuspend.complete(Unit)
        advanceUntilIdle()
        fixture.traffic.suspendHook = null

        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertTrue(fixture.runtime.snapshot.value.admissionOpen)
        val events = store.commits.flatMap(EngineCommit::events)
        assertTrue(events.none { it.type.eventType == "STUDY_SAFETY_PAUSED" })
        assertTrue(store.commits.none { it.inputKind == EngineInputKind.ACTION_RESULT })
        assertEquals(RuntimeActionState.READY, fixture.runtime.pendingActions().single().state)
        // The drained input is reduced before the staged trigger it was observed before.
        val barrier = store.commits.last { it.consumedPendingInputSha256 != null }
        assertEquals(
            listOf("44", "43"),
            barrier.events.filter { it.type == BATTERY_EVENT }.map { it.fields.getValue("percentage") },
        )
    }

    @Test
    fun claimCancelledDuringItsAppendWaitsForItAndIsNotContained() = runTest {
        val store = InMemoryStudyStore()
        val fixture = fixture(backgroundScope, store)
        val (ready, _) = requestPromptThroughFirstBarrier(fixture)
        val suspendsBefore = fixture.traffic.suspendCount
        val appendEntered = CompletableDeferred<Unit>()
        val appendMayFinish = CompletableDeferred<Unit>()
        store.beforeAppendCommit = { commit ->
            if (commit.inputKind == EngineInputKind.ACTION_RESULT) {
                appendEntered.complete(Unit)
                appendMayFinish.await()
            }
        }

        var outcome: Result<DurableActionInvocation?>? = null
        val claim = launch { outcome = runCatching { fixture.runtime.claimAction(ready.actionId) } }
        runCurrent()
        assertTrue(appendEntered.isCompleted)
        claim.cancel()
        runCurrent()
        // The append is not cancellable, so the claim waits for it instead of leaving memory behind.
        assertFalse(claim.isCompleted)
        appendMayFinish.complete(Unit)
        claim.join()
        store.beforeAppendCommit = {}

        assertClaimLandedWithoutContainment(fixture, store, ready, suspendsBefore, checkNotNull(outcome))
    }

    @Test
    fun claimCancelledAfterItsAppendIsDurableLeavesTheRuntimeOnTheStoresChain() = runTest {
        val store = InMemoryStudyStore()
        val fixture = fixture(backgroundScope, store)
        val (ready, _) = requestPromptThroughFirstBarrier(fixture)
        val suspendsBefore = fixture.traffic.suspendCount
        lateinit var claim: Job
        // As EncryptedExperimentStore's withContext(Dispatchers.IO) does: the frame is durable, and
        // a cancellation that arrived meanwhile is reported on return.
        store.afterAppendCommit = { commit ->
            if (commit.inputKind == EngineInputKind.ACTION_RESULT) {
                claim.cancel()
                currentCoroutineContext().ensureActive()
            }
        }

        var outcome: Result<DurableActionInvocation?>? = null
        claim = launch { outcome = runCatching { fixture.runtime.claimAction(ready.actionId) } }
        runCurrent()
        claim.join()
        store.afterAppendCommit = {}

        assertClaimLandedWithoutContainment(fixture, store, ready, suspendsBefore, checkNotNull(outcome))
    }

    /**
     * A cancelled claim either returned its claim or propagated the cancellation. Either way the
     * claim is durable exactly once, memory agrees with the store, nothing was contained, and a
     * retried claim and a later pause commit on the store's chain.
     */
    private suspend fun assertClaimLandedWithoutContainment(
        fixture: Fixture,
        store: InMemoryStudyStore,
        ready: DurableActionInvocation,
        suspendsBefore: Int,
        outcome: Result<DurableActionInvocation?>,
    ) {
        val claimed = ready.copy(state = RuntimeActionState.CLAIMED)
        assertTrue("$outcome", outcome.exceptionOrNull() is CancellationException || outcome.getOrNull() == claimed)
        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertTrue(fixture.runtime.snapshot.value.admissionOpen)
        assertNotNull(fixture.runtime.captureToken())
        assertEquals(suspendsBefore, fixture.traffic.suspendCount)
        assertTrue(store.commits.flatMap(EngineCommit::events).none { it.type.eventType == "STUDY_SAFETY_PAUSED" })
        assertEquals(1, store.commits.count { it.inputKind == EngineInputKind.ACTION_RESULT })
        assertEquals(store.commits.last().commitSequence, fixture.runtime.snapshot.value.revision)

        val appended = store.commits.size
        assertEquals(claimed, fixture.runtime.claimAction(ready.actionId))
        assertEquals(appended, store.commits.size)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.pause())
        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        assertEquals((1L..store.commits.size).toList(), store.commits.map(EngineCommit::commitSequence))
    }

    /**
     * Starts the study and emits 42, which sets the latch through a first resource barrier whose
     * commit requests the prompt. Returns the READY prompt and a token for the rotated epoch.
     */
    private suspend fun TestScope.requestPromptThroughFirstBarrier(
        fixture: Fixture,
    ): Pair<DurableActionInvocation, AdmissionToken> {
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val first = requireNotNull(fixture.runtime.captureToken())
        assertTrue(fixture.runtime.emitBatch(first, batteryBatch(fixture.clock.now(), 42)) is EmitBatchResult.Accepted)
        runCurrent()
        assertEquals("slow", fixture.traffic.lastDesired?.profile?.id)
        val ready = fixture.runtime.pendingActions().single()
        assertEquals(RuntimeActionState.READY, ready.state)
        return ready to requireNotNull(fixture.runtime.captureToken())
    }

    @Test
    fun optionalOutboxSchedulingFailureCommitsNeutralFailureAndKeepsRunning() = runTest {
        val notifier = RecordingActionNotifier(failReady = true)
        val fixture = fixture(backgroundScope, actionNotifier = notifier)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()

        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()

        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertTrue(fixture.runtime.pendingActions().isEmpty())
        assertEquals(1, notifier.readyAttempts.size)
        val failed = fixture.store.commits.flatMap(EngineCommit::events).single {
            it.type.eventType == "ACTION_FAILED"
        }
        assertEquals(ActionExecutionFailure.RECONCILIATION_FAILED.name, failed.fields["failure_reason"])
    }

    @Test
    fun requiredOutboxSchedulingFailureCommitsFailureBeforeWorkSchedulingSafetyPause() = runTest {
        val notifier = RecordingActionNotifier(failReady = true)
        val fixture = fixture(
            backgroundScope,
            interventionRequired = true,
            actionNotifier = notifier,
        )
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()

        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()

        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        val events = fixture.store.commits.flatMap(EngineCommit::events)
        val failedIndex = events.indexOfFirst { it.type.eventType == "ACTION_FAILED" }
        val pausedIndex = events.indexOfFirst { it.type.eventType == "STUDY_SAFETY_PAUSED" }
        assertTrue(failedIndex >= 0 && pausedIndex > failedIndex)
        assertEquals(
            ActionExecutionFailure.REQUIRED_ACTION_FAILED.name,
            events[failedIndex].fields["failure_reason"],
        )
        assertEquals("WORK_SCHEDULING_FAILURE", events[pausedIndex].fields["transition_reason"])
    }

    @Test
    fun requiredDeliveryFailureIsNormalizedByRuntimeThenFailsClosed() = runTest {
        val fixture = fixture(backgroundScope, interventionRequired = true)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()
        val action = fixture.runtime.pendingActions().single()

        assertEquals(
            RuntimeCommandResult.FailedClosed(cool.jacoblin.particeps.core.model.SafetyPauseReason.WORK_SCHEDULING_FAILURE),
            fixture.runtime.recordActionResult(
                action.actionId,
                succeeded = false,
                failure = ActionExecutionFailure.DELIVERY_FAILED,
            ),
        )
        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        val failed = fixture.store.commits.flatMap(EngineCommit::events).single {
            it.type.eventType == "ACTION_FAILED"
        }
        assertEquals(ActionExecutionFailure.REQUIRED_ACTION_FAILED.name, failed.fields["failure_reason"])
    }

    @Test
    fun pauseAndTerminalRetractButRetainActionAndResumeRearmsIt() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()
        val action = fixture.runtime.pendingActions().single()
        assertEquals(listOf(action.actionId), fixture.actionNotifier.readyAttempts)

        assertEquals(RuntimeCommandResult.Success, fixture.runtime.pause())
        assertEquals(listOf(listOf(action.actionId)), fixture.actionNotifier.inactiveCalls)
        assertEquals(action.actionId, fixture.runtime.pendingActions().single().actionId)
        assertNull(fixture.runtime.claimAction(action.actionId))

        assertEquals(RuntimeCommandResult.Success, fixture.runtime.resume())
        assertEquals(listOf(action.actionId, action.actionId), fixture.actionNotifier.readyAttempts)
        assertEquals(action.actionId, fixture.runtime.pendingActions().single().actionId)

        assertEquals(RuntimeCommandResult.Success, fixture.runtime.pause())
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.complete())
        assertEquals(ExperimentState.COMPLETED, fixture.runtime.snapshot.value.state)
        assertEquals(action.actionId, fixture.runtime.pendingActions().single().actionId)
        assertEquals(4, fixture.actionNotifier.inactiveCalls.size)
    }

    @Test
    fun claimAtExactAvailabilityDeadlineExpiresWithoutDisplay() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()
        val action = fixture.runtime.pendingActions().single()
        fixture.clock.advanceToWallMillis(action.expiresAtUtcMillis)

        assertNull(fixture.runtime.claimAction(action.actionId))
        assertTrue(fixture.runtime.pendingActions().isEmpty())
        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertEquals(
            listOf("SURVEY_EXPIRED", "ACTION_FAILED"),
            fixture.store.commits.last().events.map { it.type.eventType },
        )
        assertEquals(
            ActionExecutionFailure.EXPIRED.name,
            fixture.store.commits.last().events.last().fields["failure_reason"],
        )
    }

    @Test
    fun surveyThatExpiresWhilePausedIsRetiredBeforeResumeCanRearmIt() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()
        val action = fixture.runtime.pendingActions().single()
        assertEquals(listOf(action.actionId), fixture.actionNotifier.readyAttempts)

        assertEquals(RuntimeCommandResult.Success, fixture.runtime.pause())
        fixture.clock.advanceToWallMillis(action.expiresAtUtcMillis)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.resume())

        assertTrue(fixture.runtime.pendingActions().isEmpty())
        assertEquals(listOf(action.actionId), fixture.actionNotifier.readyAttempts)
        assertEquals(
            listOf("SURVEY_EXPIRED", "ACTION_FAILED"),
            fixture.store.commits.last().events.map { it.type.eventType },
        )
        assertEquals(
            ActionExecutionFailure.EXPIRED.name,
            fixture.store.commits.last().events.last().fields["failure_reason"],
        )
    }

    @Test
    fun surveyOpenDismissAndSubmitAreOneDurableActionLifecycle() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()
        val action = fixture.runtime.pendingActions().single()

        assertEquals(RuntimeCommandResult.Success, fixture.runtime.openSurvey(action.actionId, "prompt"))
        val opened = fixture.runtime.pendingActions().single()
        assertEquals(RuntimeActionState.OPENED, opened.state)
        assertNotNull(opened.openedAt)
        assertTrue(fixture.store.commits.last().events.any { it.type.eventType == "SURVEY_OPENED" })

        val revisionBeforeDismiss = fixture.runtime.snapshot.value.revision
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.dismissSurvey(action.actionId, "prompt"))
        assertEquals(revisionBeforeDismiss, fixture.runtime.snapshot.value.revision)
        assertEquals(RuntimeActionState.OPENED, fixture.runtime.pendingActions().single().state)

        assertEquals(
            RuntimeCommandResult.Success,
            fixture.runtime.submitSurvey(action.actionId, "prompt", "check-in", "{}"),
        )
        assertTrue(fixture.runtime.pendingActions().isEmpty())
        assertEquals(
            listOf("SURVEY_SUBMITTED", "ACTION_SUCCEEDED"),
            fixture.store.commits.last().events.map { it.type.eventType },
        )
        assertEquals(
            RuntimeCommandResult.Rejected(RuntimeCommandRejection.ACTION_ALREADY_TERMINAL),
            fixture.runtime.openSurvey(action.actionId, "prompt"),
        )
    }

    @Test
    fun surveyExpirationIsDurableAndRequiresTheAvailabilityDeadline() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            batteryBatch(fixture.clock.now()),
        )
        runCurrent()
        val action = fixture.runtime.pendingActions().single()

        assertEquals(
            RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE),
            fixture.runtime.expireSurvey(action.actionId, "prompt"),
        )
        fixture.clock.advanceMillis(301_000)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.expireSurvey(action.actionId, "prompt"))
        assertTrue(fixture.runtime.pendingActions().isEmpty())
        assertEquals(
            listOf("SURVEY_EXPIRED", "ACTION_FAILED"),
            fixture.store.commits.last().events.map { it.type.eventType },
        )
    }

    @Test
    fun uploadAcknowledgementAtomicallyAdvancesTheWatermarkAndReplaysIdempotently() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        val throughCommit = fixture.runtime.snapshot.value.revision
        val bundleId = "123e4567-e89b-42d3-a456-426614174099"
        val bundleDigest = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        assertEquals(
            RuntimeCommandResult.Success,
            fixture.runtime.acknowledgeUpload(bundleId, 1, throughCommit, bundleDigest),
        )
        assertEquals(throughCommit, fixture.runtime.snapshot.value.uploadedThroughCommit)
        assertEquals(EngineInputKind.UPLOAD_ACKNOWLEDGEMENT, fixture.store.commits.last().inputKind)
        val acknowledgedRevision = fixture.runtime.snapshot.value.revision

        assertEquals(
            RuntimeCommandResult.Success,
            fixture.runtime.acknowledgeUpload(bundleId, 1, throughCommit, bundleDigest),
        )
        assertEquals(acknowledgedRevision, fixture.runtime.snapshot.value.revision)
        fixture.runtime.close()

        val recovered = fixture(backgroundScope, fixture.store)
        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(throughCommit, recovered.runtime.snapshot.value.uploadedThroughCommit)
        assertEquals(
            RuntimeCommandResult.Success,
            recovered.runtime.acknowledgeUpload(bundleId, 1, throughCommit, bundleDigest),
        )
        assertEquals(acknowledgedRevision, recovered.runtime.snapshot.value.revision)
        assertEquals(
            RuntimeCommandResult.Rejected(RuntimeCommandRejection.UPLOAD_RECEIPT_MISMATCH),
            recovered.runtime.acknowledgeUpload(
                "123e4567-e89b-42d3-a456-426614174098",
                1,
                throughCommit,
                bundleDigest,
            ),
        )
    }

    @Test
    fun stateEntryTimeIgnoresLaterSameStateCommitsAndSurvivesRestartAndReboot() = runTest {
        val store = InMemoryStudyStore()
        val first = fixture(backgroundScope, store)
        first.runtime.initialize()
        completeSetup(first.runtime)
        first.runtime.start()
        first.clock.advanceMillis(60_000)
        assertEquals(RuntimeCommandResult.Success, first.runtime.pause())
        val pauseCommit = store.commits.first { it.successorProjection.state == ExperimentState.PAUSED }
        val pausedAt = pauseCommit.committedAt.wallTimeUtcMillis
        val pausedAtCalendar = requireNotNull(pauseCommit.successorProjection.clockCheckpoint).calendarElapsedNanos
        assertEquals(pausedAt, first.runtime.snapshot.value.stateEnteredAtUtcMillis)
        assertEquals(pausedAtCalendar, first.runtime.snapshot.value.stateEnteredCalendarElapsedNanos)

        first.clock.advanceMillis(600_000)
        assertEquals(
            RuntimeCommandResult.Success,
            first.runtime.acknowledgeUpload(
                "123e4567-e89b-42d3-a456-426614174099",
                1,
                first.runtime.snapshot.value.revision,
                "b".repeat(64),
            ),
        )
        assertEquals(EngineInputKind.UPLOAD_ACKNOWLEDGEMENT, store.commits.last().inputKind)
        assertTrue(requireNotNull(first.runtime.snapshot.value.clockAnchorWallTimeUtcMillis) >= pausedAt + 600_000)
        assertTrue(first.runtime.snapshot.value.calendarElapsedNanos > pausedAtCalendar)
        assertEquals(pausedAt, first.runtime.snapshot.value.stateEnteredAtUtcMillis)
        assertEquals(pausedAtCalendar, first.runtime.snapshot.value.stateEnteredCalendarElapsedNanos)
        first.runtime.close()

        val restarted = fixture(backgroundScope, store)
        assertTrue(restarted.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.PAUSED, restarted.runtime.snapshot.value.state)
        assertEquals(pausedAt, restarted.runtime.snapshot.value.stateEnteredAtUtcMillis)
        assertEquals(pausedAtCalendar, restarted.runtime.snapshot.value.stateEnteredCalendarElapsedNanos)
        restarted.runtime.close()

        val anchorWall = requireNotNull(store.runtime?.clockCheckpoint).anchor.wallTimeUtcMillis
        val rebooted = fixture(
            backgroundScope,
            store,
            clock = FakeClocks("boot-after-reboot", trustedUtcAvailable = true, wallBaseMillis = anchorWall + 60_000),
        )
        assertTrue(rebooted.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(EngineInputKind.RECOVERY, store.commits.last().inputKind)
        assertEquals(ExperimentState.PAUSED, store.commits.last().successorProjection.state)
        assertEquals(pausedAt, rebooted.runtime.snapshot.value.stateEnteredAtUtcMillis)
        assertEquals(pausedAtCalendar, rebooted.runtime.snapshot.value.stateEnteredCalendarElapsedNanos)

        rebooted.clock.advanceMillis(1_000)
        assertEquals(RuntimeCommandResult.Success, rebooted.runtime.complete())
        val completion = store.commits.first { it.successorProjection.state == ExperimentState.COMPLETED }
        val completedAt = completion.committedAt.wallTimeUtcMillis
        val completedAtCalendar = requireNotNull(completion.successorProjection.clockCheckpoint).calendarElapsedNanos
        assertTrue(completedAt > pausedAt)
        assertTrue(completedAtCalendar > pausedAtCalendar)
        assertEquals(completedAt, rebooted.runtime.snapshot.value.stateEnteredAtUtcMillis)
        assertEquals(completedAtCalendar, rebooted.runtime.snapshot.value.stateEnteredCalendarElapsedNanos)
        rebooted.runtime.close()

        val reopened = fixture(backgroundScope, store)
        assertTrue(reopened.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(completedAt, reopened.runtime.snapshot.value.stateEnteredAtUtcMillis)
        assertEquals(completedAtCalendar, reopened.runtime.snapshot.value.stateEnteredCalendarElapsedNanos)
    }

    @Test
    fun stateEntryBelowTheRetainedFloorIsUnknownRatherThanGuessed() = runTest {
        val store = InMemoryStudyStore()
        val first = fixture(backgroundScope, store)
        first.runtime.initialize()
        completeSetup(first.runtime)
        first.runtime.start()
        first.runtime.pause()
        assertEquals(
            RuntimeCommandResult.Success,
            first.runtime.acknowledgeUpload(
                "123e4567-e89b-42d3-a456-426614174099",
                1,
                first.runtime.snapshot.value.revision,
                "b".repeat(64),
            ),
        )
        first.runtime.close()
        val acknowledged = requireNotNull(store.runtime)
        store.runtime = acknowledged.copy(retainedFromCommit = acknowledged.revision)

        val restarted = fixture(backgroundScope, store)
        assertTrue(restarted.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.PAUSED, restarted.runtime.snapshot.value.state)
        assertNull(restarted.runtime.snapshot.value.stateEnteredAtUtcMillis)
        assertNull(restarted.runtime.snapshot.value.stateEnteredCalendarElapsedNanos)
    }

    @Test
    fun clockDiscontinuityCommitsQualityGapWithoutPausingOrResettingActiveClock() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val activeBefore = fixture.runtime.snapshot.value.activeRunningElapsedNanos

        fixture.clock.advanceMillis(10_000)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.onClockDiscontinuity())

        assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
        assertTrue(fixture.runtime.snapshot.value.activeRunningElapsedNanos > activeBefore)
        assertTrue(fixture.store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "SOURCE_QUALITY_GAP" && it.fields["reason"] == "WALL_CLOCK_CHANGED"
        })
        assertEquals(true, fixture.store.runtime?.clockCheckpoint?.deadlineUtcTrusted)
    }

    @Test
    fun signedDurationClosesAdmissionAtTheExactDeadlineAndLateWakeCompletes() = runTest {
        val fixture = fixture(backgroundScope, durationSeconds = 1)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val token = requireNotNull(fixture.runtime.captureToken())
        val timer = fixture.runtime.pendingTimers().single { it.producerKey == "study-deadline" }
        val target = timer.target as TimerTarget.SameBootMonotonic

        fixture.clock.advanceToElapsedNanos(target.elapsedRealtimeNanos)

        assertNull(fixture.runtime.captureToken())
        assertEquals(
            EmitBatchResult.RejectedByAdmissionGate,
            fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now())),
        )
        fixture.clock.advanceMillis(5_000)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.onTimerDue(timer.id, timer.generation))
        assertEquals(ExperimentState.COMPLETED, fixture.runtime.snapshot.value.state)
        assertNull(fixture.runtime.captureToken())
        assertTrue(fixture.runtime.pendingTimers().none { it.producerKey == "study-deadline" })
        assertTrue(fixture.store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "TIMER_DUE" && it.fields["producer_key"] == "study-deadline"
        })
    }

    @Test
    fun pausedRebootWithTrustedUtcRecordsGapAndCanResumeWithoutBackfill() = runTest {
        val store = InMemoryStudyStore()
        val first = fixture(backgroundScope, store)
        first.runtime.initialize()
        completeSetup(first.runtime)
        first.runtime.start()
        first.runtime.pause()
        first.runtime.close()
        val rebootedClock = FakeClocks("boot-after-reboot", trustedUtcAvailable = true)
        val recovered = fixture(backgroundScope, store, clock = rebootedClock)

        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertTrue(store.commits.last().events.any {
            it.type.eventType == "SOURCE_QUALITY_GAP" && it.fields["reason"] == "PROCESS_RECOVERY"
        })
        assertEquals(RuntimeCommandResult.Success, recovered.runtime.resume())
        assertEquals(ExperimentState.RUNNING, recovered.runtime.snapshot.value.state)
        assertTrue(store.runtime?.sourceCheckpoints?.isEmpty() == true)
    }

    @Test
    fun pausedRebootWithoutTrustedUtcDeniesResumeButAllowsCompleteAndWithdraw() = runTest {
        suspend fun pausedStore(): InMemoryStudyStore {
            val store = InMemoryStudyStore()
            val first = fixture(backgroundScope, store)
            first.runtime.initialize()
            completeSetup(first.runtime)
            first.runtime.start()
            first.runtime.pause()
            first.runtime.close()
            return store
        }

        val completeStore = pausedStore()
        val completeRuntime = fixture(
            backgroundScope,
            completeStore,
            clock = FakeClocks("boot-complete", trustedUtcAvailable = false),
        ).runtime
        completeRuntime.initialize()
        assertEquals(
            RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE),
            completeRuntime.resume(),
        )
        assertEquals(RuntimeCommandResult.Success, completeRuntime.complete())
        assertEquals(ExperimentState.COMPLETED, completeRuntime.snapshot.value.state)
        completeRuntime.close()

        val withdrawStore = pausedStore()
        val withdrawRuntime = fixture(
            backgroundScope,
            withdrawStore,
            clock = FakeClocks("boot-withdraw", trustedUtcAvailable = false),
        ).runtime
        withdrawRuntime.initialize()
        assertEquals(RuntimeCommandResult.Success, withdrawRuntime.withdraw())
        assertEquals(ExperimentState.WITHDRAWN, withdrawRuntime.snapshot.value.state)
    }

    @Test
    fun runningRebootWithoutTrustedUtcPreservesReliableAnchorDropsBacklogAndDeniesResume() = runTest {
        suspend fun runningStoreWithRetrospectiveCursor(): Pair<InMemoryStudyStore, String> {
            val first = retrospectiveFixture(backgroundScope)
            first.runtime.initialize()
            completeSetup(first.runtime)
            first.runtime.start()
            val oldBoot = requireNotNull(first.store.runtime?.clockCheckpoint).anchor.bootSessionId
            assertTrue(
                first.runtime.advanceCoverage(
                    requireNotNull(first.runtime.captureToken()),
                    CoverageAdvance(
                        USAGE_SOURCE,
                        1,
                        1,
                        0,
                        SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "0", "100"),
                    ),
                ) is EmitBatchResult.Accepted,
            )
            assertNotNull(first.store.runtime?.sourceCheckpoints?.get(USAGE_SOURCE))
            first.runtime.close()
            return first.store to oldBoot
        }

        val (completeStore, oldBoot) = runningStoreWithRetrospectiveCursor()
        val complete = retrospectiveFixture(
            backgroundScope,
            store = completeStore,
            clock = FakeClocks("untrusted-recovery", trustedUtcAvailable = false),
        )
        assertTrue(complete.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.PAUSED, complete.runtime.snapshot.value.state)
        assertEquals(oldBoot, completeStore.runtime?.clockCheckpoint?.anchor?.bootSessionId)
        assertNull(completeStore.runtime?.sourceCheckpoints?.get(USAGE_SOURCE))
        assertEquals(
            RuntimeCommandResult.Rejected(RuntimeCommandRejection.INVALID_STATE),
            complete.runtime.resume(),
        )
        assertEquals(RuntimeCommandResult.Success, complete.runtime.complete())
        assertEquals(ExperimentState.COMPLETED, complete.runtime.snapshot.value.state)
        complete.runtime.close()

        val withdrawStore = runningStoreWithRetrospectiveCursor().first
        val withdraw = retrospectiveFixture(
            backgroundScope,
            store = withdrawStore,
            clock = FakeClocks("untrusted-withdraw", trustedUtcAvailable = false),
        )
        assertTrue(withdraw.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(RuntimeCommandResult.Success, withdraw.runtime.withdraw())
        assertEquals(ExperimentState.WITHDRAWN, withdraw.runtime.snapshot.value.state)
    }

    @Test
    fun runningRebootWithTrustedUtcReanchorsAndCanResumeWithoutBackfill() = runTest {
        val first = retrospectiveFixture(backgroundScope)
        first.runtime.initialize()
        completeSetup(first.runtime)
        first.runtime.start()
        assertTrue(
            first.runtime.advanceCoverage(
                requireNotNull(first.runtime.captureToken()),
                CoverageAdvance(
                    USAGE_SOURCE,
                    1,
                    1,
                    0,
                    SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "0", "100"),
                ),
            ) is EmitBatchResult.Accepted,
        )
        val store = first.store
        first.runtime.close()
        val recovered = retrospectiveFixture(
            backgroundScope,
            store = store,
            clock = FakeClocks("trusted-recovery", trustedUtcAvailable = true),
        )

        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.PAUSED, recovered.runtime.snapshot.value.state)
        assertEquals("trusted-recovery", store.runtime?.clockCheckpoint?.anchor?.bootSessionId)
        assertNull(store.runtime?.sourceCheckpoints?.get(USAGE_SOURCE))
        assertEquals(RuntimeCommandResult.Success, recovered.runtime.resume())
        assertEquals(ExperimentState.RUNNING, recovered.runtime.snapshot.value.state)
    }

    @Test
    fun trustedRunningRecoveryPastSignedDurationCompletesWithoutOpeningAdmission() = runTest {
        val first = fixture(backgroundScope, durationSeconds = 1)
        first.runtime.initialize()
        completeSetup(first.runtime)
        first.runtime.start()
        val store = first.store
        first.runtime.close()
        val recovered = fixture(
            backgroundScope,
            store,
            durationSeconds = 1,
            clock = FakeClocks(
                bootSessionId = "trusted-late-recovery",
                trustedUtcAvailable = true,
                wallBaseMillis = 1_700_000_100_000L,
            ),
        )

        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.COMPLETED, recovered.runtime.snapshot.value.state)
        assertNull(recovered.runtime.captureToken())
        assertTrue(store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "TIMER_DUE" && it.fields["producer_key"] == "study-deadline"
        })
    }

    @Test
    fun retrospectiveBarrierCommitsTheExactFlushCursorWithZeroEventCoverage() = runTest {
        val store = InMemoryStudyStore()
        val profile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val key = ResourceKey(ResourceKind.COLLECTOR, USAGE_SOURCE.value)
        val program = AutomationCompiler(EventContractRegistry { null }).compile(
            AutomationCompilerInput(
                configurationSha256 = CONFIG_DIGEST,
                studyDurationSeconds = 3_600,
                resources = listOf(
                    DeclaredResource(key, true, mapOf("continuous" to profile.expectedSha256.value)),
                ),
                interventions = emptyList(),
                automations = listOf(
                    ResourceBindingAutomation(
                        "usage-binding",
                        key,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                        "continuous",
                    ),
                ),
            ),
        ).let { result ->
            (result as? CompilationResult.Success)?.program
                ?: error("Compilation failed: ${(result as CompilationResult.Failure).issues}")
        }
        val actuator = RetrospectiveActuator(key, USAGE_SOURCE)
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, 3_600),
            store = store,
            program = program,
            surveyInterventionIds = emptySet(),
            resourceHosts = listOf(RuntimeResourceHost(key, true, mapOf("continuous" to profile), actuator)),
            clocks = FakeClocks(),
            scope = backgroundScope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        )
        actuator.sink = runtime
        runtime.initialize()
        completeSetup(runtime)
        runtime.start()

        assertEquals(RuntimeCommandResult.Success, runtime.pause())

        val checkpoint = requireNotNull(store.runtime).sourceCheckpoints.getValue(USAGE_SOURCE)
        assertEquals("0", checkpoint.coverage?.startInclusive)
        val observation = store.commits.flatMap(EngineCommit::sourceObservations).single()
        assertEquals(observation.coverage?.endExclusive, checkpoint.coverage?.endExclusive)
        assertEquals(checkpoint.coverage?.endExclusive, checkpoint.cursor)
        assertEquals(0, observation.eventCount)
        assertEquals(cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH, observation.admissionKind)
    }

    @Test
    fun wallClockGapDropsRetrospectiveCursorWithoutFlushingBacklogAndRotatesEpoch() = runTest {
        val (runtime, store, actuator, clock) = retrospectiveFixture(backgroundScope)
        runtime.initialize()
        completeSetup(runtime)
        runtime.start()
        val oldEpoch = requireNotNull(runtime.snapshot.value.conditionEpochId)
        val oldToken = requireNotNull(runtime.captureToken())
        assertTrue(
            runtime.advanceCoverage(
                oldToken,
                CoverageAdvance(
                    USAGE_SOURCE,
                    1,
                    1,
                    0,
                    SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "0", "100"),
                ),
            ) is EmitBatchResult.Accepted,
        )
        assertNotNull(store.runtime?.sourceCheckpoints?.get(USAGE_SOURCE))

        clock.advanceMillis(10_000)
        assertEquals(RuntimeCommandResult.Success, runtime.onClockDiscontinuity())

        assertEquals(0, actuator.flushCalls)
        assertNull(store.runtime?.sourceCheckpoints?.get(USAGE_SOURCE))
        assertNotEquals(oldEpoch, runtime.snapshot.value.conditionEpochId)
        assertEquals(2uL, resourceStates(store).single().desiredGeneration.value)
        assertEquals(
            EmitBatchResult.RejectedByAdmissionGate,
            runtime.advanceCoverage(
                oldToken,
                CoverageAdvance(
                    USAGE_SOURCE,
                    1,
                    1,
                    1,
                    SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "100", "200"),
                ),
            ),
        )
    }

    @Test
    fun elapsedWallClockGapCompletesWithoutFlushingRetrospectiveBacklog() = runTest {
        val (runtime, store, actuator, clock) = retrospectiveFixture(backgroundScope, durationSeconds = 1)
        runtime.initialize()
        completeSetup(runtime)
        runtime.start()
        val token = requireNotNull(runtime.captureToken())
        assertTrue(
            runtime.advanceCoverage(
                token,
                CoverageAdvance(
                    USAGE_SOURCE,
                    1,
                    1,
                    0,
                    SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "0", "100"),
                ),
            ) is EmitBatchResult.Accepted,
        )

        clock.advanceMillis(10_000)
        assertEquals(RuntimeCommandResult.Success, runtime.onClockDiscontinuity())

        assertEquals(ExperimentState.COMPLETED, runtime.snapshot.value.state)
        assertEquals(0, actuator.flushCalls)
        assertNull(store.runtime?.sourceCheckpoints?.get(USAGE_SOURCE))
        assertNull(runtime.captureToken())
        assertTrue(store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "SOURCE_QUALITY_GAP" && it.fields["reason"] == "WALL_CLOCK_CHANGED"
        })
        assertTrue(store.commits.flatMap(EngineCommit::events).any {
            it.type.eventType == "TIMER_DUE" && it.fields["producer_key"] == "study-deadline"
        })
    }

    @Test
    fun retrospectiveInFlightPollPrecedesItsExactBoundaryFlushWithoutOrdinalGap() = runTest {
        val store = InMemoryStudyStore()
        val profile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val key = ResourceKey(ResourceKind.COLLECTOR, USAGE_SOURCE.value)
        val program = AutomationCompiler(EventContractRegistry { null }).compile(
            AutomationCompilerInput(
                configurationSha256 = CONFIG_DIGEST,
                studyDurationSeconds = 3_600,
                resources = listOf(
                    DeclaredResource(key, true, mapOf("continuous" to profile.expectedSha256.value)),
                ),
                interventions = emptyList(),
                automations = listOf(
                    ResourceBindingAutomation(
                        "usage-binding",
                        key,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                        "continuous",
                    ),
                ),
            ),
        ).let { result ->
            (result as? CompilationResult.Success)?.program
                ?: error("Compilation failed: ${(result as CompilationResult.Failure).issues}")
        }
        val actuator = RetrospectiveActuator(key, USAGE_SOURCE).apply {
            emitInFlightPollOnSuspend = true
        }
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, 3_600),
            store = store,
            program = program,
            surveyInterventionIds = emptySet(),
            resourceHosts = listOf(RuntimeResourceHost(key, true, mapOf("continuous" to profile), actuator)),
            clocks = FakeClocks(),
            scope = backgroundScope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        )
        actuator.sink = runtime
        runtime.initialize()
        completeSetup(runtime)
        runtime.start()

        assertEquals(RuntimeCommandResult.Success, runtime.pause())

        val observations = store.commits.flatMap(EngineCommit::sourceObservations)
        assertEquals(listOf(0L, 1L), observations.map { it.producerOrdinal })
        assertEquals(
            listOf(
                cool.jacoblin.particeps.core.model.ObservationAdmissionKind.NORMAL,
                cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH,
            ),
            observations.map { it.admissionKind },
        )
        assertEquals(observations[0].coverage?.endExclusive, observations[1].coverage?.startInclusive)
        assertEquals(observations[1].coverage?.endExclusive, store.runtime?.sourceCheckpoints?.get(USAGE_SOURCE)?.cursor)
    }

    @Test
    fun durablyAcceptedBoundaryFlushIsRecoveredWithItsCoverageAfterCrash() = runTest {
        val store = InMemoryStudyStore()
        val profile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val key = ResourceKey(ResourceKind.COLLECTOR, USAGE_SOURCE.value)
        val program = AutomationCompiler(EventContractRegistry { null }).compile(
            AutomationCompilerInput(
                configurationSha256 = CONFIG_DIGEST,
                studyDurationSeconds = 3_600,
                resources = listOf(DeclaredResource(key, true, mapOf("continuous" to profile.expectedSha256.value))),
                interventions = emptyList(),
                automations = listOf(
                    ResourceBindingAutomation(
                        "usage-binding",
                        key,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                        "continuous",
                    ),
                ),
            ),
        ).let { result ->
            (result as? CompilationResult.Success)?.program
                ?: error("Compilation failed: ${(result as CompilationResult.Failure).issues}")
        }
        fun runtime(actuator: RetrospectiveActuator, clock: FakeClocks) = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, 3_600),
            store = store,
            program = program,
            surveyInterventionIds = emptySet(),
            resourceHosts = listOf(RuntimeResourceHost(key, true, mapOf("continuous" to profile), actuator)),
            clocks = clock,
            scope = backgroundScope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        ).also { actuator.sink = it }
        val firstClock = FakeClocks()
        val first = runtime(RetrospectiveActuator(key, USAGE_SOURCE), firstClock)
        first.initialize()
        completeSetup(first)
        first.start()
        val epoch = requireNotNull(first.snapshot.value.conditionEpochId)
        val boundary = firstClock.now()
        val pending = PendingEngineInput(
            conditionEpochId = epoch,
            submissions = listOf(
                PendingSourceSubmission(
                    sourceId = USAGE_SOURCE,
                    schemaVersion = 1,
                    resourceGeneration = 1,
                    producerOrdinal = 0,
                    admissionKind = cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH,
                    events = emptyList(),
                    coverage = SourceCoverage(
                        SourceClockBasis.SOURCE_WALL_TIME,
                        "0",
                        boundary.wallTimeUtcMillis.toString(),
                    ),
                ),
            ),
            stagedAt = boundary,
            encodedSha256 = ZERO_DIGEST,
        ).withComputedDigest()
        store.stagePendingInput(pending)
        first.close()

        val recoveryClock = FakeClocks.continuingAfter(
            requireNotNull(store.runtime?.clockCheckpoint).anchor,
        )
        val recovered = runtime(RetrospectiveActuator(key, USAGE_SOURCE), recoveryClock)
        assertTrue(recovered.initialize() is RuntimeInitializationResult.Ready)
        val commit = store.commits.single { it.consumedPendingInputSha256 == pending.encodedSha256 }
        val observation = commit.sourceObservations.single()
        assertEquals(cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH, observation.admissionKind)
        assertEquals(pending.submissions.single().coverage, observation.coverage)
        assertEquals(0, observation.eventCount)
    }

    @Test
    fun timerDrivenBarrierReducesNonEmptyRetrospectiveFlushAndTimerCausalInputTogether() = runTest {
        val store = InMemoryStudyStore()
        val usageProfile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val baseline = SignedResourceProfile("baseline", "{\"id\":\"baseline\"}".toByteArray())
        val slow = SignedResourceProfile("slow", "{\"id\":\"slow\"}".toByteArray())
        val usageKey = ResourceKey(ResourceKind.COLLECTOR, USAGE_SOURCE.value)
        val trafficKey = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1")
        val program = AutomationCompiler(EventContractRegistry { null }).compile(
            AutomationCompilerInput(
                configurationSha256 = CONFIG_DIGEST,
                studyDurationSeconds = 3_600,
                resources = listOf(
                    DeclaredResource(
                        trafficKey,
                        true,
                        mapOf("baseline" to baseline.expectedSha256.value, "slow" to slow.expectedSha256.value),
                    ),
                    DeclaredResource(usageKey, true, mapOf("continuous" to usageProfile.expectedSha256.value)),
                ),
                interventions = emptyList(),
                automations = listOf(
                    ResourceBindingAutomation(
                        "traffic-binding",
                        trafficKey,
                        listOf(
                            ResourceConditionCase(
                                StateCondition.ElapsedAtLeast(1, DurationClock.ACTIVE_RUNNING_TIME),
                                "slow",
                            ),
                        ),
                        "baseline",
                    ),
                    ResourceBindingAutomation(
                        "usage-binding",
                        usageKey,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                        "continuous",
                    ),
                ),
            ),
        ).let { result ->
            (result as? CompilationResult.Success)?.program
                ?: error("Compilation failed: ${(result as CompilationResult.Failure).issues}")
        }
        val usage = RetrospectiveActuator(usageKey, USAGE_SOURCE).apply {
            emitEventOnFlush = true
        }
        val traffic = FakeActuator(trafficKey)
        val clock = FakeClocks()
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, 3_600),
            store = store,
            program = program,
            surveyInterventionIds = emptySet(),
            resourceHosts = listOf(
                RuntimeResourceHost(usageKey, true, mapOf("continuous" to usageProfile), usage),
                RuntimeResourceHost(trafficKey, true, mapOf("baseline" to baseline, "slow" to slow), traffic),
            ),
            clocks = clock,
            scope = backgroundScope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        )
        usage.sink = runtime
        runtime.initialize()
        completeSetup(runtime)
        runtime.start()
        val timer = runtime.pendingTimers().single { it.producerKey != "study-deadline" }
        clock.advanceMillis(1_100)

        assertEquals(RuntimeCommandResult.Success, runtime.onTimerDue(timer.id, timer.generation))

        assertEquals("slow", traffic.lastDesired?.profile?.id)
        val barrierCommit = store.commits.last { commit ->
            commit.inputKind == EngineInputKind.TIMER_WAKE &&
                commit.sourceObservations.any { it.sourceId == USAGE_SOURCE }
        }
        assertEquals(
            cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH,
            barrierCommit.sourceObservations.single().admissionKind,
        )
        val usageIndex = barrierCommit.events.indexOfFirst { it.type.eventType == "ACTIVITY_RESUMED" }
        val timerIndex = barrierCommit.events.indexOfFirst { it.type.eventType == "TIMER_DUE" }
        assertTrue(usageIndex >= 0 && timerIndex > usageIndex)
    }

    @Test
    fun sameSourceBarrierKeepsCausalOrdinalFirstButReducesExactFlushBeforeCausalEvent() = runTest {
        val store = InMemoryStudyStore()
        val usageProfile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val baseline = SignedResourceProfile("baseline", "{\"id\":\"baseline\"}".toByteArray())
        val slow = SignedResourceProfile("slow", "{\"id\":\"slow\"}".toByteArray())
        val usageKey = ResourceKey(ResourceKind.COLLECTOR, USAGE_SOURCE.value)
        val trafficKey = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1")
        val pausedEvent = EventTypeKey(USAGE_SOURCE, 1, "ACTIVITY_PAUSED")
        val resumedEvent = EventTypeKey(USAGE_SOURCE, 1, "ACTIVITY_RESUMED")
        val program = AutomationCompiler(GeneratedEventContractRegistry).compile(
            AutomationCompilerInput(
                configurationSha256 = CONFIG_DIGEST,
                studyDurationSeconds = 3_600,
                resources = listOf(
                    DeclaredResource(
                        trafficKey,
                        true,
                        mapOf("baseline" to baseline.expectedSha256.value, "slow" to slow.expectedSha256.value),
                    ),
                    DeclaredResource(usageKey, true, mapOf("continuous" to usageProfile.expectedSha256.value)),
                ),
                interventions = emptyList(),
                automations = listOf(
                    ResourceBindingAutomation(
                        "traffic-binding",
                        trafficKey,
                        listOf(
                            ResourceConditionCase(
                                StateCondition.EventLatch(
                                    setWhen = listOf(EventMatcher(pausedEvent)),
                                    resetWhen = listOf(EventMatcher(resumedEvent)),
                                ),
                                "slow",
                            ),
                        ),
                        "baseline",
                    ),
                    ResourceBindingAutomation(
                        "usage-binding",
                        usageKey,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                        "continuous",
                    ),
                ),
            ),
        ).let { result ->
            (result as? CompilationResult.Success)?.program
                ?: error("Compilation failed: ${(result as CompilationResult.Failure).issues}")
        }
        val usage = RetrospectiveActuator(usageKey, USAGE_SOURCE).apply { emitEventOnFlush = true }
        val traffic = FakeActuator(trafficKey)
        val clock = FakeClocks()
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, 3_600),
            store = store,
            program = program,
            surveyInterventionIds = emptySet(),
            resourceHosts = listOf(
                RuntimeResourceHost(usageKey, true, mapOf("continuous" to usageProfile), usage),
                RuntimeResourceHost(trafficKey, true, mapOf("baseline" to baseline, "slow" to slow), traffic),
            ),
            clocks = clock,
            scope = backgroundScope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        ).also { usage.sink = it }
        runtime.initialize()
        completeSetup(runtime)
        runtime.start()

        assertTrue(usage.emitActivity("ACTIVITY_PAUSED", clock.now()) is EmitBatchResult.Accepted)
        runCurrent()

        val barrierCommit = store.commits.single { it.consumedPendingInputSha256 != null }
        assertEquals(
            listOf(USAGE_SOURCE, USAGE_SOURCE),
            barrierCommit.sourceObservations.map { it.sourceId },
        )
        assertEquals(
            listOf(
                cool.jacoblin.particeps.core.model.ObservationAdmissionKind.NORMAL,
                cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH,
            ),
            barrierCommit.sourceObservations.map { it.admissionKind },
        )
        assertEquals(listOf(0L, 1L), barrierCommit.sourceObservations.map { it.producerOrdinal })
        assertTrue(
            requireNotNull(barrierCommit.sourceObservations[0].firstEventSequence) >
                requireNotNull(barrierCommit.sourceObservations[1].firstEventSequence),
        )
        assertEquals(
            listOf("ACTIVITY_RESUMED", "ACTIVITY_PAUSED"),
            barrierCommit.events
                .filter { it.type.sourceId == USAGE_SOURCE }
                .map { it.type.eventType },
        )
        assertEquals("slow", traffic.lastDesired?.profile?.id)
    }

    @Test
    fun pendingSlotAndDurableRunningStateRecoverOnlyAsSafetyPaused() = runTest {
        val store = InMemoryStudyStore()
        val first = fixture(backgroundScope, store, withTrafficAudit = true)
        first.runtime.initialize()
        completeSetup(first.runtime)
        first.runtime.start()
        val epoch = requireNotNull(first.runtime.snapshot.value.conditionEpochId)
        val pending = PendingEngineInput(
            conditionEpochId = epoch,
            submissions = listOf(
                PendingSourceSubmission(
                    sourceId = BATTERY_SOURCE,
                    schemaVersion = 1,
                    resourceGeneration = 1,
                    producerOrdinal = 0,
                    admissionKind = cool.jacoblin.particeps.core.model.ObservationAdmissionKind.NORMAL,
                    events = batteryBatch(first.clock.now()).events,
                    coverage = null,
                ),
            ),
            stagedAt = first.clock.now(),
            encodedSha256 = ZERO_DIGEST,
        ).withComputedDigest()
        store.stagePendingInput(pending)
        first.runtime.close()

        val recovered = fixture(backgroundScope, store, withTrafficAudit = true)
        val result = recovered.runtime.initialize()

        assertTrue(result is RuntimeInitializationResult.Ready && result.recoveredFailClosed)
        assertEquals(ExperimentState.PAUSED, recovered.runtime.snapshot.value.state)
        assertNull(store.pending)
        val recoveryCommit = store.commits.single { it.consumedPendingInputSha256 != null }
        assertTrue(recoveryCommit.events.any { it.type.eventType == "STUDY_SAFETY_PAUSED" })
        assertTrue(recoveryCommit.events.any { it.type.eventType == "SOURCE_QUALITY_GAP" })
        assertTrue(recoveryCommit.events.any { it.type.eventType == "TIMER_RETIRED" })
        assertEquals(EngineInputKind.RESOURCE_RESULT, store.commits.last().inputKind)
        assertEquals(
            listOf("study-deadline"),
            recovered.runtime.pendingTimers().map(DurableTimer::producerKey),
        )
    }

    @Test
    fun acceptedPreDrainBatchIsInTheDurablePendingBundleBeforeBarrierCommit() = runTest {
        val store = InMemoryStudyStore().apply { failPendingConsumption = true }
        val fixture = fixture(backgroundScope, store)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val token = requireNotNull(fixture.runtime.captureToken())
        val suspendEntered = CompletableDeferred<Unit>()
        val continueSuspend = CompletableDeferred<Unit>()
        fixture.traffic.suspendHook = {
            suspendEntered.complete(Unit)
            continueSuspend.await()
        }
        val causal = batteryBatch(fixture.clock.now())
        val queued = batteryBatch(fixture.clock.now()).copy(
            producerOrdinal = 1,
            events = batteryBatch(fixture.clock.now()).events.map { event ->
                event.copy(fields = event.fields + ("percentage" to "44"))
            },
        )

        assertTrue(fixture.runtime.emitBatch(token, causal) is EmitBatchResult.Accepted)
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { suspendEntered.await() }
        }
        assertTrue(fixture.runtime.emitBatch(token, queued) is EmitBatchResult.Accepted)
        assertEquals(listOf(0L, 1L), requireNotNull(store.pending).submissions.map { it.producerOrdinal })
        continueSuspend.complete(Unit)
        runCurrent()
        assertNotNull(store.pending)
        fixture.runtime.close()

        store.failPendingConsumption = false
        val recovered = fixture(backgroundScope, store)
        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        val recoveryCommit = store.commits.single { it.consumedPendingInputSha256 != null }
        assertEquals(listOf(0L, 1L), recoveryCommit.sourceObservations.map { it.producerOrdinal })
        assertTrue(
            requireNotNull(recoveryCommit.sourceObservations[0].firstEventSequence) >
                requireNotNull(recoveryCommit.sourceObservations[1].firstEventSequence),
        )
        assertEquals(
            listOf("44", "42"),
            recoveryCommit.events.filter { it.type == BATTERY_EVENT }.map { it.fields.getValue("percentage") },
        )
    }

    @Test
    fun pendingBundleAllowsMaximumCausalBatchFollowedByExactZeroEventFlush() {
        val now = ResearchTime(1_700_000_000_000L, 1_000_000_000L, "boot-test")
        val event = batteryBatch(now).events.single()
        val pending = PendingEngineInput(
            conditionEpochId = ConditionEpochId("123e4567-e89b-42d3-a456-426614174010"),
            submissions = listOf(
                PendingSourceSubmission(
                    BATTERY_SOURCE,
                    1,
                    1,
                    0,
                    cool.jacoblin.particeps.core.model.ObservationAdmissionKind.NORMAL,
                    List(4_096) { event },
                    null,
                ),
                PendingSourceSubmission(
                    USAGE_SOURCE,
                    1,
                    1,
                    0,
                    cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH,
                    emptyList(),
                    SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, "0", now.wallTimeUtcMillis.toString()),
                ),
            ),
            stagedAt = now,
            encodedSha256 = ZERO_DIGEST,
        ).withComputedDigest()

        assertEquals(4_096, pending.submissions.sumOf { it.events.size })
        assertEquals(
            cool.jacoblin.particeps.core.model.ObservationAdmissionKind.BARRIER_FLUSH,
            pending.submissions.last().admissionKind,
        )
    }

    private suspend fun completeSetup(runtime: ExperimentRuntime) {
        assertEquals(RuntimeCommandResult.Success, runtime.markConfigurationVerified())
        assertEquals(RuntimeCommandResult.Success, runtime.beginConsentReview())
        assertEquals(RuntimeCommandResult.Success, runtime.acceptConsent())
        assertEquals(RuntimeCommandResult.Success, runtime.markReady())
    }

    private suspend fun scheduledTimerAt(runtime: ExperimentRuntime, isoInstant: String): DurableTimer {
        val target = Instant.parse(isoInstant).toEpochMilli()
        return runtime.pendingTimers().single { (it.target as? TimerTarget.CalendarUtc)?.utcMillis == target }
    }

    private suspend fun emitScheduledGyro(fixture: ScheduledCollectorFixture): EmitBatchResult {
        val now = fixture.clocks.now()
        return fixture.runtime.emitBatch(
            requireNotNull(fixture.runtime.captureToken()),
            SourceEventBatch(
                sourceId = EventSourceId("gyroscope.v1"), schemaVersion = 1,
                resourceGeneration = requireNotNull(fixture.actuator.lastDesired).generation.value.toLong(),
                producerOrdinal = 0,
                events = listOf(
                    EventDraft(
                        EventTypeKey(EventSourceId("gyroscope.v1"), 1, "GYROSCOPE_SAMPLE"), now,
                        mapOf(
                            "source_elapsed_realtime_nanos" to now.elapsedRealtimeNanos.toString(),
                            "x_radians_per_second" to "0.0", "y_radians_per_second" to "0.0",
                            "z_radians_per_second" to "0.0", "accuracy" to "3",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun scheduledCollectorFixture(scope: kotlinx.coroutines.CoroutineScope): ScheduledCollectorFixture {
        val key = ResourceKey(ResourceKind.COLLECTOR, "gyroscope.v1")
        val profile = SignedResourceProfile(
            "daytime", "{\"maximum_report_latency_us\":0,\"sampling_period_us\":1000000}".toByteArray(),
        )
        val compilation = AutomationCompiler(EventContractRegistry { null }).compile(
            AutomationCompilerInput(
                CONFIG_DIGEST, 120 * 3_600,
                listOf(DeclaredResource(key, true, mapOf(profile.id to profile.expectedSha256.value))),
                emptyList(),
                listOf(
                    ResourceBindingAutomation(
                        "gyro-schedule", key,
                        listOf(ResourceConditionCase(StateCondition.StudyLocalWindow(1, 5, "12:00", "17:00"), profile.id)),
                        defaultProfileId = null,
                    ),
                ),
            ),
        )
        val program = (compilation as? CompilationResult.Success)?.program
            ?: error("Compilation failed: ${(compilation as CompilationResult.Failure).issues}")
        val clocks = FakeClocks(wallBaseMillis = Instant.parse("2026-09-07T11:59:00Z").toEpochMilli() - 1_000)
        val actuator = FakeActuator(key)
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, 120 * 3_600),
            store = InMemoryStudyStore(), program = program, surveyInterventionIds = emptySet(),
            resourceHosts = listOf(RuntimeResourceHost(key, true, mapOf(profile.id to profile), actuator)),
            clocks = clocks, scope = scope, zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        )
        return ScheduledCollectorFixture(runtime, actuator, clocks)
    }

    private data class ScheduledCollectorFixture(
        val runtime: ExperimentRuntime,
        val actuator: FakeActuator,
        val clocks: FakeClocks,
    )

    @Test
    fun windowedSensorSamplesKeepTheConditionEpochOfTheirCaptureAcrossABarrier() = runTest {
        /** Each sample's epoch, and the samples the barrier commit carried as pre-drain input. */
        suspend fun attribution(window: CallbackCommitWindow?): Pair<Map<String, ConditionEpochId?>, List<String>> {
            val fixture = fixture(backgroundScope, withGyroscope = true)
            fixture.runtime.initialize()
            completeSetup(fixture.runtime)
            fixture.runtime.start()
            val gyroscope = gyroscopeCollector(fixture, window)
            gyroscope.sample(1f)
            gyroscope.sample(2f)
            runCurrent()
            // A battery trigger rotates the epoch through the global barrier while the batch is open.
            val token = requireNotNull(fixture.runtime.captureToken())
            assertTrue(fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now())) is EmitBatchResult.Accepted)
            runCurrent()
            assertEquals("slow", fixture.traffic.lastDesired?.profile?.id)
            gyroscope.sample(3f)
            runCurrent()
            detach(fixture)
            gyroscope.stop()
            fun EngineCommit.samples() = events.filter { it.type == GYROSCOPE_EVENT }
                .map { it.fields.getValue("x_radians_per_second") }
            return fixture.store.commits.flatMap { it.events }
                .filter { it.type == GYROSCOPE_EVENT }
                .associate { it.fields.getValue("x_radians_per_second") to it.conditionEpochId } to
                fixture.store.commits.single { it.consumedPendingInputSha256 != null }.samples()
        }

        val (windowed, windowedDrained) = attribution(CallbackCommitWindow(5.seconds, testScheduler.timeSource))
        val (unwindowed, unwindowedDrained) = attribution(window = null)

        // Unwindowed, the first two samples commit before the trigger; windowed, they are still
        // open when the barrier begins and are drained into it. Either way they keep the old epoch.
        assertEquals(emptyList<String>(), unwindowedDrained)
        assertEquals(listOf("1.0", "2.0"), windowedDrained)
        assertEquals(setOf("1.0", "2.0", "3.0"), windowed.keys)
        assertEquals(unwindowed, windowed)
        assertEquals(windowed["1.0"], windowed["2.0"])
        assertNotEquals(windowed["2.0"], windowed["3.0"])
    }

    @Test
    fun windowedSensorSamplesAreNeverAdmittedAfterAForceClose() = runTest {
        val fixture = fixture(backgroundScope, withGyroscope = true)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val results = mutableListOf<EmitBatchResult>()
        val gyroscope = gyroscopeCollector(fixture, CallbackCommitWindow(5.seconds, testScheduler.timeSource), results)
        gyroscope.sample(1f)
        gyroscope.sample(2f)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(results.isEmpty())

        // A safety pause closes admission before it suspends collectors; the open batch is refused.
        fixture.runtime.safetyPause(SafetyPauseReason.REQUIRED_RESOURCE_FAILURE)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        assertTrue(results.isNotEmpty() && results.all { it == EmitBatchResult.RejectedByAdmissionGate })
        assertTrue(fixture.store.commits.flatMap { it.events }.none { it.type == GYROSCOPE_EVENT })
        assertEquals(CollectorStatus.PAUSED, gyroscope.health.value.status)
        detach(fixture)
        gyroscope.stop()
    }

    @Test
    fun wallClockChangeRefusesTheOpenWindowAndKeepsRunning() = runTest {
        /**
         * Each recorded sample, labelled "old" or "new" by the epoch it belongs to, the gyroscope's
         * offer results, and the sources the recorded quality gaps name.
         */
        suspend fun acrossAClockChange(
            window: CallbackCommitWindow?,
        ): Triple<Map<String, String>, List<EmitBatchResult>, List<String?>> {
            val fixture = fixture(backgroundScope, withGyroscope = true)
            fixture.runtime.initialize()
            completeSetup(fixture.runtime)
            fixture.runtime.start()
            val oldEpoch = requireNotNull(fixture.runtime.snapshot.value.conditionEpochId)
            val results = mutableListOf<EmitBatchResult>()
            val gyroscope = gyroscopeCollector(fixture, window, results)
            gyroscope.sample(1f)
            gyroscope.sample(2f)
            advanceTimeBy(1_000)
            runCurrent()

            // A running TIME_SET closes admission before it suspends collectors, as a safety pause does.
            fixture.clock.advanceMillis(1_000)
            assertEquals(RuntimeCommandResult.Success, fixture.runtime.onClockDiscontinuity())
            runCurrent()
            assertEquals(ExperimentState.RUNNING, fixture.runtime.snapshot.value.state)
            val newEpoch = requireNotNull(fixture.runtime.snapshot.value.conditionEpochId)
            assertNotEquals(oldEpoch, newEpoch)
            assertEquals(CollectorStatus.ACTIVE, gyroscope.health.value.status)
            gyroscope.sample(3f)
            advanceTimeBy(5_000)
            runCurrent()
            detach(fixture)
            gyroscope.stop()
            val events = fixture.store.commits.flatMap { it.events }
            val epochLabels = mapOf(oldEpoch to "old", newEpoch to "new")
            return Triple(
                events.filter { it.type == GYROSCOPE_EVENT }.associate {
                    it.fields.getValue("x_radians_per_second") to epochLabels.getValue(requireNotNull(it.conditionEpochId))
                },
                results,
                events.filter { it.type.eventType == "SOURCE_QUALITY_GAP" }.map { it.fields["source_id"] },
            )
        }

        val (unwindowed, unwindowedResults, unwindowedGaps) = acrossAClockChange(window = null)
        val (windowed, windowedResults, windowedGaps) = acrossAClockChange(
            CallbackCommitWindow(5.seconds, testScheduler.timeSource),
        )

        // Committed at capture, the first two samples keep the old epoch. Windowed, they are still
        // open when admission closes, so the rotation's pause refuses them and they are dropped.
        assertEquals(mapOf("1.0" to "old", "2.0" to "old", "3.0" to "new"), unwindowed)
        assertTrue(unwindowedResults.all { it is EmitBatchResult.Accepted })
        assertEquals(mapOf("3.0" to "new"), windowed)
        assertEquals(
            listOf(EmitBatchResult.RejectedByAdmissionGate, EmitBatchResult.RejectedByAdmissionGate),
            windowedResults.take(2),
        )
        assertTrue(windowedResults.drop(2).single() is EmitBatchResult.Accepted)
        // Only the clock's own gap is recorded; none names the sensor whose samples were dropped.
        assertEquals(listOf("timer.v1"), unwindowedGaps)
        assertEquals(listOf("timer.v1"), windowedGaps)
    }

    @Test
    fun wallClockChangeFirstSeenAfterTheDeadlineRefusesTheOpenWindow() = runTest {
        val fixture = fixture(backgroundScope, withGyroscope = true, durationSeconds = 1)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val deadline = fixture.runtime.pendingTimers().single { it.producerKey == "study-deadline" }
        val results = mutableListOf<EmitBatchResult>()
        val gyroscope = gyroscopeCollector(fixture, CallbackCommitWindow(5.seconds, testScheduler.timeSource), results)
        gyroscope.sample(1f)
        gyroscope.sample(2f)
        runCurrent()

        // Unlike the deadline stop's drain, a clock change first seen after the deadline completes
        // from durable input only: the samples captured before the deadline are refused.
        fixture.clock.advanceToElapsedNanos((deadline.target as TimerTarget.SameBootMonotonic).elapsedRealtimeNanos)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.onClockDiscontinuity())
        runCurrent()

        assertEquals(ExperimentState.COMPLETED, fixture.runtime.snapshot.value.state)
        assertEquals(listOf(EmitBatchResult.RejectedByAdmissionGate, EmitBatchResult.RejectedByAdmissionGate), results)
        assertTrue(fixture.store.commits.flatMap { it.events }.none { it.type == GYROSCOPE_EVENT })
        detach(fixture)
        gyroscope.stop()
    }

    @Test
    fun sequenceOrWindowStateOverAnotherSourceTurnsTheSensorsWindowOff() = runTest {
        // A battery window count keeps each battery event's observed time, and the reducer requires
        // every later event, from any source, to be no older than the newest entry.
        val batteryWindow = Trigger.WindowThreshold(
            EventMatcher(BATTERY_EVENT),
            60,
            EvaluationClock.OBSERVED_RESEARCH_TIME,
            Aggregate.Count,
            NumericComparison(FieldOperator.GTE, "100"),
        )

        /** The gyroscope's health and recorded samples after a battery event commits inside its window. */
        suspend fun afterABatteryEvent(requiresPromptCommits: (CompiledAutomationProgram) -> Boolean): Pair<CollectorHealth, List<String>> {
            val fixture = fixture(backgroundScope, withGyroscope = true, notifyTrigger = batteryWindow)
            fixture.runtime.initialize()
            completeSetup(fixture.runtime)
            fixture.runtime.start()
            val gyroscope = gyroscopeCollector(
                fixture,
                CallbackCommitWindow(5.seconds, testScheduler.timeSource),
                requiresPromptCommits = requiresPromptCommits(fixture.program),
            )
            gyroscope.sample(1f)
            runCurrent()
            advanceTimeBy(1_000)
            // A battery event that changes no resource commits at once, after the sample's capture.
            val token = requireNotNull(fixture.runtime.captureToken())
            val battery = fixture.runtime.emitBatch(token, batteryBatch(fixture.clock.now(), percentage = 50))
            assertTrue(battery is EmitBatchResult.Accepted)
            advanceTimeBy(5_000)
            runCurrent()
            val health = gyroscope.health.value
            val samples = fixture.store.commits.flatMap { it.events }
                .filter { it.type == GYROSCOPE_EVENT }
                .map { it.fields.getValue("x_radians_per_second") }
            detach(fixture)
            gyroscope.stop()
            return health to samples
        }

        // The assembly's rule: no matcher names the gyroscope, but the window orders every event.
        assertEquals(
            CollectorHealth(CollectorStatus.ACTIVE) to listOf("1.0"),
            afterABatteryEvent { it.referencesSource(GYROSCOPE_SOURCE) || it.retainsEventTimeOrderedState },
        )
        // Matcher references alone would keep the window: its batch then lands behind the battery
        // entry, the reducer refuses it, and the collector fails with the sample lost.
        assertEquals(
            CollectorHealth(CollectorStatus.FAILED, "STORAGE_WRITE_FAILED") to emptyList<String>(),
            afterABatteryEvent { it.referencesSource(GYROSCOPE_SOURCE) },
        )
    }

    @Test
    fun deadlineCompletionCommitsTheOpenWindowBeforeTheEpochEnds() = runTest {
        val fixture = fixture(backgroundScope, withGyroscope = true, durationSeconds = 1)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val epoch = requireNotNull(fixture.runtime.snapshot.value.conditionEpochId)
        val deadline = fixture.runtime.pendingTimers().single { it.producerKey == "study-deadline" }
        val gyroscope = gyroscopeCollector(fixture, CallbackCommitWindow(5.seconds, testScheduler.timeSource))
        gyroscope.sample(1f)
        gyroscope.sample(2f)
        runCurrent()

        fixture.clock.advanceToElapsedNanos((deadline.target as TimerTarget.SameBootMonotonic).elapsedRealtimeNanos)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.onTimerDue(deadline.id, deadline.generation))
        runCurrent()

        assertEquals(ExperimentState.COMPLETED, fixture.runtime.snapshot.value.state)
        assertDrainedIntoEpochEnd(fixture, epoch)
        detach(fixture)
        gyroscope.stop()
    }

    @Test
    fun windowClosingAfterTheDeadlineIsHeldForTheDeadlineStopsDrain() = runTest {
        val fixture = fixture(backgroundScope, withGyroscope = true, durationSeconds = 1)
        fixture.runtime.initialize()
        completeSetup(fixture.runtime)
        fixture.runtime.start()
        val epoch = requireNotNull(fixture.runtime.snapshot.value.conditionEpochId)
        val deadline = fixture.runtime.pendingTimers().single { it.producerKey == "study-deadline" }
        val results = mutableListOf<EmitBatchResult>()
        val gyroscope = gyroscopeCollector(fixture, CallbackCommitWindow(5.seconds, testScheduler.timeSource), results)
        gyroscope.sample(1f)
        gyroscope.sample(2f)
        runCurrent()

        // The window closes after the deadline but before its timer runs: the open epoch refuses.
        fixture.clock.advanceToElapsedNanos((deadline.target as TimerTarget.SameBootMonotonic).elapsedRealtimeNanos)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(EmitBatchResult.RejectedByAdmissionGate, EmitBatchResult.RejectedByAdmissionGate), results)
        assertTrue(fixture.store.commits.flatMap { it.events }.none { it.type == GYROSCOPE_EVENT })

        // A late deadline wake drains what was observed before the deadline, held batch included.
        fixture.clock.advanceMillis(5_000)
        assertEquals(RuntimeCommandResult.Success, fixture.runtime.onTimerDue(deadline.id, deadline.generation))
        runCurrent()

        assertEquals(ExperimentState.COMPLETED, fixture.runtime.snapshot.value.state)
        assertDrainedIntoEpochEnd(fixture, epoch)
        detach(fixture)
        gyroscope.stop()
    }

    @Test
    fun lateTimeBasedTransitionIsSurfacedByAWindowedCommitOnlyWhenItCommits() = runTest {
        // Traffic slows after 10 s of running time. Its durable timer is never delivered here, as
        // when WorkManager runs late, so only a later commit can surface the transition.
        suspend fun slowedAt(window: CallbackCommitWindow?): List<String?> {
            val fixture = fixture(
                backgroundScope,
                withGyroscope = true,
                trafficCondition = StateCondition.ElapsedAtLeast(10, DurationClock.ACTIVE_RUNNING_TIME),
            )
            fixture.runtime.initialize()
            completeSetup(fixture.runtime)
            fixture.runtime.start()
            val gyroscope = gyroscopeCollector(fixture, window)
            fixture.clock.advanceMillis(11_000)
            gyroscope.sample(1f)
            runCurrent()
            val beforeWindow = fixture.traffic.lastDesired?.profile?.id
            advanceTimeBy(5_000)
            runCurrent()
            val afterWindow = fixture.traffic.lastDesired?.profile?.id
            detach(fixture)
            gyroscope.stop()
            return listOf(beforeWindow, afterWindow)
        }

        assertEquals(listOf("slow", "slow"), slowedAt(window = null))
        assertEquals(listOf("baseline", "slow"), slowedAt(CallbackCommitWindow(5.seconds, testScheduler.timeSource)))
    }

    /** The samples 1.0 and 2.0 are one pre-drain observation of the commit that ends [epoch]. */
    private fun assertDrainedIntoEpochEnd(fixture: Fixture, epoch: ConditionEpochId) {
        val epochEnd = fixture.store.commits.single { commit ->
            commit.events.any { it.type.eventType == "CONDITION_EPOCH_DEACTIVATED" }
        }
        val samples = epochEnd.events.filter { it.type == GYROSCOPE_EVENT }
        assertEquals(listOf("1.0", "2.0"), samples.map { it.fields.getValue("x_radians_per_second") })
        assertTrue(samples.all { it.conditionEpochId == epoch })
        val observation = epochEnd.sourceObservations.single { it.sourceId == GYROSCOPE_SOURCE }
        assertEquals(0L, observation.producerOrdinal)
        assertEquals(2, observation.eventCount)
        assertTrue(fixture.store.commits.flatMap { it.events }.count { it.type == GYROSCOPE_EVENT } == 2)
    }

    /** Starts a gyroscope collector, unreferenced by default, whose resource barriers pause and resume it. */
    private suspend fun TestScope.gyroscopeCollector(
        fixture: Fixture,
        window: CallbackCommitWindow?,
        results: MutableList<EmitBatchResult> = mutableListOf(),
        requiresPromptCommits: Boolean = false,
    ): RuntimeGyroscopeCollector {
        val sink = object : EventSink by fixture.runtime {
            override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult =
                fixture.runtime.emitBatch(token, batch).also(results::add)
        }
        val collector = RuntimeGyroscopeCollector(
            CollectorContext(
                scope = backgroundScope,
                eventSink = sink,
                clocks = fixture.clock,
                sourceContract = requireNotNull(ProtocolEventSourceRegistry[GYROSCOPE_SOURCE.value]),
                resourceGeneration = 1,
                tokenEncoder = StudyScopedTokenEncoder { _, _ -> "0".repeat(64) },
                requiresPromptCommits = requiresPromptCommits,
            ),
            StandardTestDispatcher(testScheduler),
            window,
        )
        collector.start()
        collector.onAdmissionOpened()
        val actuator = requireNotNull(fixture.gyroscope)
        actuator.suspendHook = { collector.pause() }
        actuator.resumeHook = { collector.resume() }
        actuator.admissionOpenedHook = { collector.onAdmissionOpened() }
        return collector
    }

    private fun detach(fixture: Fixture) {
        val actuator = requireNotNull(fixture.gyroscope)
        actuator.suspendHook = null
        actuator.resumeHook = null
        actuator.admissionOpenedHook = null
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        store: InMemoryStudyStore = InMemoryStudyStore(),
        withTrafficAudit: Boolean = false,
        durationSeconds: Long = 3_600,
        clock: FakeClocks? = null,
        interventionRequired: Boolean = false,
        actionNotifier: RecordingActionNotifier = RecordingActionNotifier(),
        trafficCondition: StateCondition = PERCENTAGE_LATCH,
        notifyTrigger: Trigger = Trigger.EventMatch(
            EventMatcher(BATTERY_EVENT, listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "42"))),
            EvaluationClock.OBSERVED_RESEARCH_TIME,
        ),
        /** Adds a continuously collected gyroscope that no automation references. */
        withGyroscope: Boolean = false,
    ): Fixture {
        val runtimeClock = clock ?: store.runtime?.clockCheckpoint?.anchor?.let(FakeClocks::continuingAfter)
            ?: FakeClocks()
        val batteryProfile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val baseline = SignedResourceProfile("baseline", "{\"id\":\"baseline\"}".toByteArray())
        val slow = SignedResourceProfile("slow", "{\"id\":\"slow\"}".toByteArray())
        val batteryKey = ResourceKey(ResourceKind.COLLECTOR, BATTERY_SOURCE.value)
        val trafficKey = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1")
        val gyroscopeKey = ResourceKey(ResourceKind.COLLECTOR, GYROSCOPE_SOURCE.value)
        val compilerInput = AutomationCompilerInput(
            configurationSha256 = CONFIG_DIGEST,
            studyDurationSeconds = durationSeconds,
            resources = listOfNotNull(
                DeclaredResource(
                    trafficKey,
                    true,
                    mapOf("baseline" to baseline.expectedSha256.value, "slow" to slow.expectedSha256.value),
                ),
                DeclaredResource(batteryKey, true, mapOf("continuous" to batteryProfile.expectedSha256.value)),
                DeclaredResource(gyroscopeKey, true, mapOf("continuous" to batteryProfile.expectedSha256.value))
                    .takeIf { withGyroscope },
            ),
            interventions = listOf(InterventionDefinition("prompt", required = interventionRequired)),
            automations = listOfNotNull(
                ResourceBindingAutomation(
                    "battery-binding",
                    batteryKey,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                    "continuous",
                ),
                ResourceBindingAutomation(
                    "gyroscope-binding",
                    gyroscopeKey,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                    "continuous",
                ).takeIf { withGyroscope },
                OccurrenceAutomation(
                    "notify-battery",
                    notifyTrigger,
                    guard = null,
                    interventionId = "prompt",
                    availabilitySeconds = 300,
                    cooldown = null,
                    maximumActivations = 1,
                ),
                ResourceBindingAutomation(
                    "traffic-binding",
                    trafficKey,
                    listOf(ResourceConditionCase(trafficCondition, "slow")),
                    "baseline",
                ),
            ),
        )
        val eventContract = EventTypeContract(
            key = BATTERY_EVENT,
            sourceKind = EventSourceKind.COLLECTOR,
            fields = mapOf(
                "percentage" to FieldContract(
                    ScalarType.INTEGER,
                    FieldOperator.entries.toSet(),
                    minimumInteger = BigInteger.ZERO,
                    maximumInteger = BigInteger.valueOf(100),
                ),
            ),
            triggerScope = TriggerScope.RESEARCHER,
            deliveryMode = DeliveryMode.LIVE,
            clockSupport = setOf(EventClockSupport.OBSERVED_RESEARCH_TIME),
            conditionKinds = setOf(EventConditionKind.EVENT_MATCH, EventConditionKind.WINDOW_COUNT),
            presence = null,
            rateBound = EventRateBound(60, 60),
        )
        val compilation = AutomationCompiler(EventContractRegistry { key -> eventContract.takeIf { it.key == key } })
            .compile(compilerInput)
        val program = (compilation as? CompilationResult.Success)?.program
            ?: error("Compilation failed: ${(compilation as CompilationResult.Failure).issues}")
        val battery = FakeActuator(batteryKey)
        val traffic = FakeActuator(trafficKey)
        val gyroscope = FakeActuator(gyroscopeKey).takeIf { withGyroscope }
        val entropy = DeterministicEntropy()
        val trafficAudit = FakeTrafficAuditSource(trafficKey).takeIf { withTrafficAudit }
        val timerWakeups = RecordingTimerWakeups()
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, durationSeconds),
            store = store,
            program = program,
            surveyInterventionIds = setOf("prompt"),
            resourceHosts = listOfNotNull(
                RuntimeResourceHost(batteryKey, true, mapOf("continuous" to batteryProfile), battery),
                RuntimeResourceHost(
                    trafficKey,
                    true,
                    mapOf("baseline" to baseline, "slow" to slow),
                    traffic,
                    trafficAudit,
                ),
                gyroscope?.let { RuntimeResourceHost(gyroscopeKey, true, mapOf("continuous" to batteryProfile), it) },
            ),
            clocks = runtimeClock,
            scope = scope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            timerWakeups = timerWakeups,
            actionNotifier = actionNotifier,
            entropy = entropy,
        )
        return Fixture(runtime, store, battery, traffic, runtimeClock, timerWakeups, actionNotifier, program, gyroscope)
    }

    private fun retrospectiveFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        durationSeconds: Long = 3_600,
        store: InMemoryStudyStore = InMemoryStudyStore(),
        clock: FakeClocks = FakeClocks(),
    ): RetrospectiveFixture {
        val profile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val key = ResourceKey(ResourceKind.COLLECTOR, USAGE_SOURCE.value)
        val compilation = AutomationCompiler(EventContractRegistry { null }).compile(
            AutomationCompilerInput(
                configurationSha256 = CONFIG_DIGEST,
                studyDurationSeconds = durationSeconds,
                resources = listOf(
                    DeclaredResource(key, true, mapOf("continuous" to profile.expectedSha256.value)),
                ),
                interventions = emptyList(),
                automations = listOf(
                    ResourceBindingAutomation(
                        "usage-binding",
                        key,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                        "continuous",
                    ),
                ),
            ),
        )
        val program = (compilation as? CompilationResult.Success)?.program
            ?: error("Compilation failed: ${(compilation as CompilationResult.Failure).issues}")
        val actuator = RetrospectiveActuator(key, USAGE_SOURCE)
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity(
                "experiment-one",
                "configuration-one",
                CONFIG_DIGEST,
                durationSeconds,
            ),
            store = store,
            program = program,
            surveyInterventionIds = emptySet(),
            resourceHosts = listOf(
                RuntimeResourceHost(key, true, mapOf("continuous" to profile), actuator),
            ),
            clocks = clock,
            scope = scope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        ).also { actuator.sink = it }
        return RetrospectiveFixture(runtime, store, actuator, clock)
    }

    private fun batteryBatch(now: ResearchTime, percentage: Int = 42) = SourceEventBatch(
        sourceId = BATTERY_SOURCE,
        schemaVersion = 1,
        resourceGeneration = 1,
        producerOrdinal = 0,
        events = listOf(
            EventDraft(
                BATTERY_EVENT,
                now,
                mapOf(
                    "charging_source" to "NONE",
                    "charging_state" to "DISCHARGING",
                    "percentage" to percentage.toString(),
                    "power_save_enabled" to "false",
                ),
            ),
        ),
    )

    private data class Fixture(
        val runtime: ExperimentRuntime,
        val store: InMemoryStudyStore,
        val battery: FakeActuator,
        val traffic: FakeActuator,
        val clock: FakeClocks,
        val timerWakeups: RecordingTimerWakeups,
        val actionNotifier: RecordingActionNotifier,
        val program: CompiledAutomationProgram,
        val gyroscope: FakeActuator? = null,
    )

    private data class RetrospectiveFixture(
        val runtime: ExperimentRuntime,
        val store: InMemoryStudyStore,
        val actuator: RetrospectiveActuator,
        val clock: FakeClocks,
    )

    private class RecordingTimerWakeups : TimerWakeupAdapter {
        val scheduled = mutableListOf<DurableTimer>()
        val retired = mutableListOf<String>()
        val retiredGenerations = mutableListOf<Pair<String, ULong>>()

        override suspend fun schedule(timer: DurableTimer) {
            scheduled += timer
        }

        override suspend fun retire(timerId: String, generation: ULong) {
            retired += timerId
            retiredGenerations += timerId to generation
        }
    }

    private class RecordingActionNotifier(
        private val failReady: Boolean = false,
    ) : ActionOutboxNotifier {
        val readyAttempts = mutableListOf<String>()
        val inactiveCalls = mutableListOf<List<String>>()

        override suspend fun onActionReady(actionId: String) {
            readyAttempts += actionId
            if (failReady) throw IOException("fixture outbox rejection")
        }

        override suspend fun onActionsInactive(actionIds: List<String>) {
            inactiveCalls += actionIds
        }
    }

    private class FakeTrafficAuditSource(override val key: ResourceKey) : PeriodicResourceAuditSource {
        override val sourceId = EventSourceId("traffic_shaping.v1")
        override val schemaVersion = 1
        override val intervalSeconds = 60L

        override suspend fun audit(request: ResourceAuditRequest): ResourceAuditReceipt {
            val common = mapOf(
                "condition_epoch_id" to request.conditionEpochId.value,
                "profile_id" to request.evidence.profileId,
                "resource_generation" to request.evidence.generation.toString(),
                "vpn_generation_id" to "123e4567-e89b-42d3-a456-426614174090",
            )
            val events = when (request) {
                is ResourceAuditRequest.EpochActivated -> listOf(
                    EventDraft(
                        EventTypeKey(sourceId, schemaVersion, "TRAFFIC_SHAPING_PROFILE_APPLIED"),
                        request.observedAt,
                        common + mapOf(
                            "activation_research_time" to request.activatedAt.json(),
                            "applied_profile_sha256" to request.evidence.appliedProfileSha256.value,
                            "signed_configuration_sha256" to request.signedConfigurationSha256.value,
                            "target_package_list_sha256" to "b".repeat(64),
                            "verification_completed_research_time" to request.observedAt.json(),
                        ),
                    ),
                )
                is ResourceAuditRequest.Periodic -> listOf(
                    snapshot(request, common, "PERIODIC", request.logicalDeadline),
                )
                is ResourceAuditRequest.EpochBoundary -> listOf(
                    snapshot(request, common, "EPOCH_BOUNDARY", request.boundary),
                    EventDraft(
                        EventTypeKey(sourceId, schemaVersion, "TRAFFIC_SHAPING_PROFILE_REMOVED"),
                        request.observedAt,
                        common + counters() + mapOf(
                            "boundary_research_time" to request.boundary.json(),
                            "removal_reason" to request.reason.name,
                        ),
                    ),
                )
            }
            return ResourceAuditReceipt(request.evidence, events)
        }

        private fun snapshot(
            request: ResourceAuditRequest,
            common: Map<String, String>,
            reason: String,
            logicalDeadline: ResearchTime,
        ) = EventDraft(
            EventTypeKey(sourceId, schemaVersion, "TRAFFIC_SHAPING_SNAPSHOT"),
            request.observedAt,
            common + counters() + mapOf(
                "logical_deadline_research_time" to logicalDeadline.json(),
                "observation_research_time" to request.observedAt.json(),
                "snapshot_reason" to reason,
            ),
        )

        private fun counters() = mapOf(
            "downlink_bytes" to "200",
            "downlink_packets" to "2",
            "downlink_throttled_nanoseconds" to "20",
            "uplink_bytes" to "100",
            "uplink_packets" to "1",
            "uplink_throttled_nanoseconds" to "10",
        )

        private fun ResearchTime.json() =
            "{\"boot_session_id\":\"$bootSessionId\",\"monotonic_time_nanos\":\"$elapsedRealtimeNanos\"," +
                "\"wall_time_utc_millis\":\"$wallTimeUtcMillis\"}"
    }

    private class FakeClocks(
        private var bootSessionId: String = "boot-test",
        private var trustedUtcAvailable: Boolean = true,
        private var nanos: Long = 1_000_000_000L,
        private var wallBaseMillis: Long = 1_700_000_000_000L,
    ) : ResearchClocks {
        override fun now(): ResearchTime = ResearchTime(
            wallBaseMillis + nanos / 1_000_000,
            nanos,
            bootSessionId,
        )
            .also { nanos += 1_000_000 }
        override fun trustedUtcMillis(): Long? = now().wallTimeUtcMillis.takeIf { trustedUtcAvailable }

        fun advanceMillis(millis: Long) {
            require(millis >= 0)
            nanos = Math.addExact(nanos, Math.multiplyExact(millis, 1_000_000L))
        }

        fun advanceToWallMillis(target: Long) {
            val current = wallBaseMillis + nanos / 1_000_000L
            require(target >= current)
            advanceMillis(target - current)
        }

        fun advanceToElapsedNanos(target: Long) {
            require(target >= nanos)
            nanos = target
        }

        fun reboot(newBootSessionId: String, trustedUtc: Boolean) {
            wallBaseMillis = now().wallTimeUtcMillis
            nanos = 1_000_000_000L
            bootSessionId = newBootSessionId
            trustedUtcAvailable = trustedUtc
        }

        companion object {
            fun continuingAfter(anchor: ResearchTime) = FakeClocks(
                bootSessionId = anchor.bootSessionId,
                trustedUtcAvailable = true,
                nanos = Math.addExact(anchor.elapsedRealtimeNanos, 1_000_000L),
                wallBaseMillis = anchor.wallTimeUtcMillis - anchor.elapsedRealtimeNanos / 1_000_000L,
            )
        }
    }

    private class DeterministicEntropy : RuntimeEntropySource {
        private var epochOrdinal = 0
        override fun next(kind: RuntimeEntropyKind): String = when (kind) {
            RuntimeEntropyKind.PARTICIPANT_INSTANCE_UUID -> "123e4567-e89b-42d3-a456-426614174001"
            RuntimeEntropyKind.ACTIVITY_TOKEN_KEY -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            RuntimeEntropyKind.CONDITION_EPOCH_UUID -> when (epochOrdinal++) {
                0 -> "123e4567-e89b-42d3-a456-426614174010"
                1 -> "123e4567-e89b-42d3-a456-426614174011"
                else -> "123e4567-e89b-42d3-a456-426614174012"
            }
        }
    }

    private class FakeActuator(override val key: ResourceKey) : StatefulResourceActuator {
        override val supportsHotProfileSwap = true
        var lastDesired: DesiredResourceState? = null
        var resumeCount = 0
        var suspendCount = 0
        var releaseCount = 0
        var failNextVerification = false
        var invalidReleaseAttempts = 0
        private var listener: ResourceTerminalFailureListener? = null
        private var health = inactiveHealth(key)
        var admissionProbe: (() -> AdmissionToken?)? = null
        val tokensDuringResume = mutableListOf<AdmissionToken?>()
        val tokensAfterAdmissionOpened = mutableListOf<AdmissionToken?>()
        var suspendHook: (suspend () -> Unit)? = null
        var resumeHook: (suspend () -> Unit)? = null
        var admissionOpenedHook: (suspend () -> Unit)? = null
        var releaseHook: (suspend () -> Unit)? = null

        override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) {
            this.listener = listener
        }

        override suspend fun prepare(desired: DesiredResourceState, requestId: String): PrepareReceipt {
            health = desiredHealth(desired, ResourceHealthStatus.PREPARED, applied = false)
            return PrepareReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                null,
                requestId,
            )
        }

        override suspend fun suspendAt(desired: DesiredResourceState, boundary: ResearchTime): SuspendReceipt {
            suspendCount++
            health = desiredHealth(desired, ResourceHealthStatus.SUSPENDED, applied = true)
            suspendHook?.invoke()
            return SuspendReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                boundary,
            )
        }

        override suspend fun flushThrough(
            desired: DesiredResourceState,
            boundary: ResearchTime,
            cursor: String?,
        ): FlushReceipt = FlushReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            desired.profile?.expectedSha256,
            boundary,
            cursor,
            complete = true,
        )

        override suspend fun apply(desired: DesiredResourceState): ApplyReceipt {
            lastDesired = desired
            health = desiredHealth(desired, ResourceHealthStatus.APPLIED, applied = true)
            return ApplyReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
            )
        }

        override suspend fun verify(desired: DesiredResourceState): VerifyReceipt {
            if (failNextVerification) {
                failNextVerification = false
                return VerifyReceipt(
                    key,
                    desired.generation,
                    desired.profile?.id,
                    desired.profile?.expectedSha256,
                    desired.profile?.expectedSha256,
                    healthy = false,
                    failureReason = "FORGED_VERIFY",
                )
            }
            return VerifyReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                healthy = true,
                failureReason = null,
            )
        }

        override suspend fun resume(desired: DesiredResourceState): ResumeReceipt {
            resumeCount++
            require(lastDesired == desired)
            tokensDuringResume += admissionProbe?.invoke()
            health = desiredHealth(desired, ResourceHealthStatus.APPLIED, applied = true)
            resumeHook?.invoke()
            return ResumeReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                resumed = true,
                failureReason = null,
            )
        }

        override suspend fun onAdmissionOpened(desired: DesiredResourceState): ResourceHealth {
            require(lastDesired == desired)
            tokensAfterAdmissionOpened += admissionProbe?.invoke()
            admissionOpenedHook?.invoke()
            return health
        }

        fun failTerminal(reason: String) {
            listener?.onTerminalFailure(
                cool.jacoblin.particeps.core.resource.ResourceTerminalFailure(
                    key = key,
                    generation = requireNotNull(lastDesired).generation,
                    reason = reason,
                ),
            )
        }

        override suspend fun release(desired: DesiredResourceState): ReleaseReceipt {
            releaseCount++
            releaseHook?.invoke()
            if (invalidReleaseAttempts > 0) {
                invalidReleaseAttempts--
                return ReleaseReceipt(
                    key,
                    desired.generation,
                    desired.profile?.id,
                    desired.profile?.expectedSha256,
                    desired.profile?.expectedSha256,
                    ReleaseEvidence.APPLIED,
                    released = false,
                )
            }
            lastDesired = null
            health = inactiveHealth(key)
            return ReleaseReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                ReleaseEvidence.APPLIED,
                released = true,
            )
        }

        override fun health(): ResourceHealth = health
    }

    private class BlockingFirstEventSink(
        private val delegate: EventSink,
    ) : EventSink {
        val firstSubmissionEntered = CompletableDeferred<Unit>()
        val releaseFirstSubmission = CompletableDeferred<Unit>()
        private var submissionCount = 0

        override fun captureToken(): AdmissionToken? = delegate.captureToken()

        override fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken? =
            delegate.captureBarrierFlushToken(boundary)

        override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch): EmitBatchResult {
            if (submissionCount++ == 0) {
                firstSubmissionEntered.complete(Unit)
                releaseFirstSubmission.await()
            }
            return delegate.emitBatch(token, batch)
        }

        override suspend fun advanceCoverage(
            token: AdmissionToken,
            advance: CoverageAdvance,
        ): EmitBatchResult = delegate.advanceCoverage(token, advance)
    }

    private class RuntimeCallbackCollector(
        context: CollectorContext,
        consumerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : SerializedCallbackCollector(context, queueCapacity = 4, consumerDispatcher) {
        fun trigger(percentage: Int) = capture {
            EventDraft(
                BATTERY_EVENT,
                context.clocks.now(),
                mapOf(
                    "charging_source" to "NONE",
                    "charging_state" to "DISCHARGING",
                    "percentage" to percentage.toString(),
                    "power_save_enabled" to "false",
                ),
            )
        }

        override suspend fun registerSource() = SourceRegistrationResult.Registered

        override suspend fun unregisterSource() = SourceTeardownResult.Released
    }

    /** A gyroscope-shaped sampled source; [sample] records its x rate as the sample's label. */
    private class RuntimeGyroscopeCollector(
        context: CollectorContext,
        consumerDispatcher: CoroutineDispatcher,
        commitWindow: CallbackCommitWindow?,
    ) : SerializedCallbackCollector(context, queueCapacity = 64, consumerDispatcher, commitWindow) {
        fun sample(x: Float) = capture {
            val observed = context.clocks.now()
            EventDraft(
                GYROSCOPE_EVENT,
                observed,
                mapOf(
                    "accuracy" to "3",
                    "source_elapsed_realtime_nanos" to observed.elapsedRealtimeNanos.toString(),
                    "x_radians_per_second" to x.toString(),
                    "y_radians_per_second" to "0.0",
                    "z_radians_per_second" to "0.0",
                ),
            )
        }

        override suspend fun registerSource() = SourceRegistrationResult.Registered

        override suspend fun unregisterSource() = SourceTeardownResult.Released
    }

    private class RetrospectiveActuator(
        override val key: ResourceKey,
        private val sourceId: EventSourceId,
    ) : StatefulResourceActuator {
        override val supportsHotProfileSwap = false
        lateinit var sink: cool.jacoblin.particeps.core.collector.EventSink
        private var desired: DesiredResourceState? = null
        private var producerOrdinal = 0L
        private var admissionToken: AdmissionToken? = null
        private var localCursor = "0"
        var emitInFlightPollOnSuspend = false
        var emitEventOnFlush = false
        var flushCalls = 0

        suspend fun emitActivity(eventType: String, now: ResearchTime): EmitBatchResult {
            val active = requireNotNull(desired)
            val coverage = SourceCoverage(
                SourceClockBasis.SOURCE_WALL_TIME,
                localCursor,
                now.wallTimeUtcMillis.toString(),
            )
            val result = sink.emitBatch(
                requireNotNull(admissionToken),
                SourceEventBatch(
                    sourceId = sourceId,
                    schemaVersion = 1,
                    resourceGeneration = active.generation.value.toLong(),
                    producerOrdinal = producerOrdinal,
                    events = listOf(
                        EventDraft(
                            EventTypeKey(sourceId, 1, eventType),
                            now,
                            mapOf(
                                "activity_component_token" to "0".repeat(64),
                                "package_name" to "com.example.target",
                                "source_time_utc_millis" to now.wallTimeUtcMillis.toString(),
                            ),
                        ),
                    ),
                    coverage = coverage,
                ),
            )
            if (result is EmitBatchResult.Accepted) {
                producerOrdinal = Math.addExact(producerOrdinal, 1L)
                localCursor = coverage.endExclusive
            }
            return result
        }

        override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) = Unit
        override suspend fun prepare(desired: DesiredResourceState, requestId: String) = PrepareReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            null,
            requestId,
        )
        override suspend fun suspendAt(desired: DesiredResourceState, boundary: ResearchTime): SuspendReceipt {
            if (emitInFlightPollOnSuspend) {
                val ordinal = producerOrdinal
                val end = Math.subtractExact(boundary.wallTimeUtcMillis, 1L).toString()
                val result = sink.advanceCoverage(
                    requireNotNull(admissionToken),
                    CoverageAdvance(
                        sourceId = sourceId,
                        schemaVersion = 1,
                        resourceGeneration = desired.generation.value.toLong(),
                        producerOrdinal = ordinal,
                        coverage = SourceCoverage(SourceClockBasis.SOURCE_WALL_TIME, localCursor, end),
                    ),
                )
                require(result is EmitBatchResult.Accepted)
                producerOrdinal = Math.addExact(ordinal, 1L)
                localCursor = end
            }
            return SuspendReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                boundary,
            )
        }

        override suspend fun flushThrough(
            desired: DesiredResourceState,
            boundary: ResearchTime,
            cursor: String?,
        ): FlushReceipt {
            flushCalls += 1
            val ordinal = producerOrdinal
            val token = requireNotNull(sink.captureBarrierFlushToken(boundary))
            val coverage = SourceCoverage(
                SourceClockBasis.SOURCE_WALL_TIME,
                localCursor,
                boundary.wallTimeUtcMillis.toString(),
            )
            val result = if (emitEventOnFlush) {
                sink.emitBatch(
                    token,
                    SourceEventBatch(
                        sourceId = sourceId,
                        schemaVersion = 1,
                        resourceGeneration = desired.generation.value.toLong(),
                        producerOrdinal = ordinal,
                        events = listOf(
                            EventDraft(
                                EventTypeKey(sourceId, 1, "ACTIVITY_RESUMED"),
                                boundary,
                                mapOf(
                                    "activity_component_token" to "0".repeat(64),
                                    "package_name" to "com.example.target",
                                    "source_time_utc_millis" to boundary.wallTimeUtcMillis.toString(),
                                ),
                            ),
                        ),
                        coverage = coverage,
                    ),
                )
            } else {
                sink.advanceCoverage(
                    token,
                    CoverageAdvance(
                        sourceId = sourceId,
                        schemaVersion = 1,
                        resourceGeneration = desired.generation.value.toLong(),
                        producerOrdinal = ordinal,
                        coverage = coverage,
                    ),
                )
            }
            require(result is EmitBatchResult.Accepted)
            producerOrdinal = Math.addExact(producerOrdinal, 1L)
            localCursor = boundary.wallTimeUtcMillis.toString()
            return FlushReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                boundary,
                boundary.wallTimeUtcMillis.toString(),
                complete = true,
            )
        }

        override suspend fun apply(desired: DesiredResourceState): ApplyReceipt {
            this.desired = desired
            return ApplyReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
            )
        }

        override suspend fun verify(desired: DesiredResourceState) = VerifyReceipt(
            key,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            desired.profile?.expectedSha256,
            healthy = true,
            failureReason = null,
        )

        override suspend fun resume(desired: DesiredResourceState): ResumeReceipt {
            return ResumeReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                resumed = true,
                failureReason = null,
            )
        }

        override suspend fun onAdmissionOpened(desired: DesiredResourceState): ResourceHealth {
            admissionToken = sink.captureToken()
            return health()
        }

        override suspend fun release(desired: DesiredResourceState): ReleaseReceipt {
            this.desired = null
            return ReleaseReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                desired.profile?.expectedSha256,
                ReleaseEvidence.APPLIED,
                released = true,
            )
        }

        override fun health() = if (desired == null) {
            inactiveHealth(key)
        } else {
            desiredHealth(requireNotNull(desired), ResourceHealthStatus.APPLIED, applied = true)
        }
    }

    private class InMemoryStudyStore : StudyStore {
        var runtime: RuntimeDocument? = null
        var pending: PendingEngineInput? = null
        val commits = mutableListOf<EngineCommit>()
        val pendingStaged = CompletableDeferred<Unit>()
        var failPendingConsumption = false
        var afterPendingStaged: suspend () -> Unit = {}
        var beforeAppendCommit: suspend (EngineCommit) -> Unit = {}
        var afterAppendCommit: suspend (EngineCommit) -> Unit = {}

        override suspend fun loadRuntime(observeRetained: (EngineCommit) -> Unit): RuntimeDocument? =
            runtime?.also { current ->
                commits.filter { it.commitSequence >= current.retainedFromCommit }.forEach(observeRetained)
            }
        override suspend fun initialize(runtime: RuntimeDocument) {
            check(this.runtime == null)
            this.runtime = runtime
        }
        override suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument) {
            beforeAppendCommit(commit)
            require(pending == null) { "Only the containment path may append while input is staged" }
            commits += commit
            runtime = successor
            afterAppendCommit(commit)
        }
        override suspend fun stagePendingInput(input: PendingEngineInput) {
            check(pending == null)
            pending = input
            pendingStaged.complete(Unit)
            afterPendingStaged()
        }
        override suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput) {
            check(pending?.encodedSha256 == expectedSha256)
            check(input.submissions.size == requireNotNull(pending).submissions.size + 1)
            pending = input
        }
        override suspend fun loadPendingInput(): PendingEngineInput? = pending
        override suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument) {
            if (failPendingConsumption) throw IOException("simulated process death before pending consume")
            val input = requireNotNull(pending) { "No pending input is staged" }
            require(commit.consumedPendingInputSha256 == input.encodedSha256) { "Commit does not consume the staged input" }
            commits += commit
            runtime = successor
            pending = null
        }
        override suspend fun <T> withReadSnapshot(block: suspend (StudyReadSnapshot) -> T): T {
            val capturedRuntime = requireNotNull(runtime)
            val capturedCommits = commits.toList()
            return block(object : StudyReadSnapshot {
                override val runtime = capturedRuntime
                override suspend fun readCommits(
                    fromCommitInclusive: Long,
                    throughCommitInclusive: Long,
                    consume: (EngineCommit) -> Boolean,
                ) {
                    for (commit in capturedCommits) {
                        if (commit.commitSequence in fromCommitInclusive..throughCommitInclusive && !consume(commit)) break
                    }
                }
            })
        }
        override suspend fun storageUsage() = StorageUsage(0, 1)
        override suspend fun evictThrough(runtime: RuntimeDocument, targetBytes: Long): RuntimeDocument = runtime
        override suspend fun clear() {
            runtime = null
            pending = null
            commits.clear()
        }
    }

    private companion object {
        fun resourceStates(store: InMemoryStudyStore) = requireNotNull(store.runtime).components
            .filterKeys { it.kind == cool.jacoblin.particeps.core.model.RuntimeComponentKind.RESOURCE }
            .values
            .map(RuntimeComponentCodec::decodeResource)

        fun inactiveHealth(key: ResourceKey) = ResourceHealth(
            key = key,
            status = ResourceHealthStatus.INACTIVE,
            generation = null,
            profileId = null,
            expectedProfileSha256 = null,
            appliedProfileSha256 = null,
            failureReason = null,
        )

        fun desiredHealth(
            desired: DesiredResourceState,
            status: ResourceHealthStatus,
            applied: Boolean,
        ) = ResourceHealth(
            key = desired.key,
            status = status,
            generation = desired.generation,
            profileId = desired.profile?.id,
            expectedProfileSha256 = desired.profile?.expectedSha256,
            appliedProfileSha256 = desired.profile?.expectedSha256.takeIf { applied },
            failureReason = null,
        )

        val BATTERY_SOURCE = EventSourceId("battery_state.v1")
        val GYROSCOPE_SOURCE = EventSourceId("gyroscope.v1")
        val GYROSCOPE_EVENT = EventTypeKey(GYROSCOPE_SOURCE, 1, "GYROSCOPE_SAMPLE")
        val USAGE_SOURCE = EventSourceId("usage_events.v1")
        val BATTERY_EVENT = EventTypeKey(BATTERY_SOURCE, 1, "BATTERY_STATE")
        /** Set by a 42% battery event and reset by a 43% one; the fixture binds "slow" to it. */
        val PERCENTAGE_LATCH = StateCondition.EventLatch(
            setWhen = listOf(
                EventMatcher(BATTERY_EVENT, listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "42"))),
            ),
            resetWhen = listOf(
                EventMatcher(BATTERY_EVENT, listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "43"))),
            ),
        )
        const val CONFIG_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
