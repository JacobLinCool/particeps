package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCompiler
import cool.jacoblin.particeps.core.automation.CompilationResult
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
import cool.jacoblin.particeps.core.automation.ScalarType
import cool.jacoblin.particeps.core.automation.TimerProductionResult
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.automation.TriggerScope
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.AdmissionToken
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
import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.DeclaredResource
import cool.jacoblin.particeps.core.definition.DurationClock
import cool.jacoblin.particeps.core.definition.EvaluationClock
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.FieldPredicate
import cool.jacoblin.particeps.core.definition.InterventionDefinition
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
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceClockBasis
import cool.jacoblin.particeps.core.model.StorageUsage
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentRuntimeTest {
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

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        store: InMemoryStudyStore = InMemoryStudyStore(),
        withTrafficAudit: Boolean = false,
        durationSeconds: Long = 3_600,
        clock: FakeClocks? = null,
        interventionRequired: Boolean = false,
        actionNotifier: RecordingActionNotifier = RecordingActionNotifier(),
    ): Fixture {
        val runtimeClock = clock ?: store.runtime?.clockCheckpoint?.anchor?.let(FakeClocks::continuingAfter)
            ?: FakeClocks()
        val batteryProfile = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val baseline = SignedResourceProfile("baseline", "{\"id\":\"baseline\"}".toByteArray())
        val slow = SignedResourceProfile("slow", "{\"id\":\"slow\"}".toByteArray())
        val batteryKey = ResourceKey(ResourceKind.COLLECTOR, BATTERY_SOURCE.value)
        val trafficKey = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1")
        val compilerInput = AutomationCompilerInput(
            configurationSha256 = CONFIG_DIGEST,
            studyDurationSeconds = durationSeconds,
            resources = listOf(
                DeclaredResource(
                    trafficKey,
                    true,
                    mapOf("baseline" to baseline.expectedSha256.value, "slow" to slow.expectedSha256.value),
                ),
                DeclaredResource(batteryKey, true, mapOf("continuous" to batteryProfile.expectedSha256.value)),
            ),
            interventions = listOf(InterventionDefinition("prompt", required = interventionRequired)),
            automations = listOf(
                ResourceBindingAutomation(
                    "battery-binding",
                    batteryKey,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                    "continuous",
                ),
                OccurrenceAutomation(
                    "notify-battery",
                    Trigger.EventMatch(
                        EventMatcher(
                            BATTERY_EVENT,
                            listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "42")),
                        ),
                        EvaluationClock.OBSERVED_RESEARCH_TIME,
                    ),
                    guard = null,
                    interventionId = "prompt",
                    availabilitySeconds = 300,
                    cooldown = null,
                    maximumActivations = 1,
                ),
                ResourceBindingAutomation(
                    "traffic-binding",
                    trafficKey,
                    listOf(
                        ResourceConditionCase(
                            StateCondition.EventLatch(
                                setWhen = listOf(
                                    EventMatcher(
                                        BATTERY_EVENT,
                                        listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "42")),
                                    ),
                                ),
                                resetWhen = listOf(
                                    EventMatcher(
                                        BATTERY_EVENT,
                                        listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "43")),
                                    ),
                                ),
                            ),
                            "slow",
                        ),
                    ),
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
            conditionKinds = setOf(EventConditionKind.EVENT_MATCH),
            presence = null,
            rateBound = EventRateBound(60, 60),
        )
        val compilation = AutomationCompiler(EventContractRegistry { key -> eventContract.takeIf { it.key == key } })
            .compile(compilerInput)
        val program = (compilation as? CompilationResult.Success)?.program
            ?: error("Compilation failed: ${(compilation as CompilationResult.Failure).issues}")
        val battery = FakeActuator(batteryKey)
        val traffic = FakeActuator(trafficKey)
        val entropy = DeterministicEntropy()
        val trafficAudit = FakeTrafficAuditSource(trafficKey).takeIf { withTrafficAudit }
        val timerWakeups = RecordingTimerWakeups()
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("experiment-one", "configuration-one", CONFIG_DIGEST, durationSeconds),
            store = store,
            program = program,
            surveyInterventionIds = setOf("prompt"),
            resourceHosts = listOf(
                RuntimeResourceHost(batteryKey, true, mapOf("continuous" to batteryProfile), battery),
                RuntimeResourceHost(
                    trafficKey,
                    true,
                    mapOf("baseline" to baseline, "slow" to slow),
                    traffic,
                    trafficAudit,
                ),
            ),
            clocks = runtimeClock,
            scope = scope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            timerWakeups = timerWakeups,
            actionNotifier = actionNotifier,
            entropy = entropy,
        )
        return Fixture(runtime, store, battery, traffic, runtimeClock, timerWakeups, actionNotifier)
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

    private fun batteryBatch(now: ResearchTime) = SourceEventBatch(
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
                    "percentage" to "42",
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

        override suspend fun schedule(timer: DurableTimer) {
            scheduled += timer
        }

        override suspend fun retire(timerId: String, generation: ULong) {
            retired += timerId
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
    ) : SerializedCallbackCollector(context, queueCapacity = 4) {
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

        override suspend fun loadRuntime(): RuntimeDocument? = runtime
        override suspend fun initialize(runtime: RuntimeDocument) {
            check(this.runtime == null)
            this.runtime = runtime
        }
        override suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument) {
            commits += commit
            runtime = successor
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
            check(commit.consumedPendingInputSha256 == pending?.encodedSha256)
            commits += commit
            runtime = successor
            pending = null
        }
        override suspend fun readCommits(
            fromCommitInclusive: Long,
            throughCommitInclusive: Long,
            consume: (EngineCommit) -> Unit,
        ) {
            commits.filter { it.commitSequence in fromCommitInclusive..throughCommitInclusive }.forEach(consume)
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
        val USAGE_SOURCE = EventSourceId("usage_events.v1")
        val BATTERY_EVENT = EventTypeKey(BATTERY_SOURCE, 1, "BATTERY_STATE")
        const val CONFIG_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
