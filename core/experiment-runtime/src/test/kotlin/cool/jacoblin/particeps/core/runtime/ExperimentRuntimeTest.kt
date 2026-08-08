package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.ExperimentTransition
import cool.jacoblin.particeps.core.model.InterventionOccurrence
import cool.jacoblin.particeps.core.model.OccurrenceState
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.SafetyPauseReason
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.model.StudyStoreMutationFailedClosed
import cool.jacoblin.particeps.core.model.TransitionReason
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorEventContract
import cool.jacoblin.particeps.core.collector.EventFieldContract
import cool.jacoblin.particeps.core.collector.EventFieldType
import cool.jacoblin.particeps.core.collector.EventPayloadContract
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.EmitResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.definition.AppLifecycleConfiguration
import cool.jacoblin.particeps.core.definition.BatteryStateConfiguration
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.ChoiceOption
import cool.jacoblin.particeps.core.definition.InterventionConfiguration
import cool.jacoblin.particeps.core.definition.InterventionTrigger
import cool.jacoblin.particeps.core.definition.LocalizedText
import cool.jacoblin.particeps.core.definition.MultipleChoiceQuestion
import cool.jacoblin.particeps.core.definition.OneTimeSchedule
import cool.jacoblin.particeps.core.definition.RelativeClock
import cool.jacoblin.particeps.core.definition.ScaleQuestion
import cool.jacoblin.particeps.core.definition.ShortTextQuestion
import cool.jacoblin.particeps.core.definition.SingleChoiceQuestion
import cool.jacoblin.particeps.core.collector.PrivacyClass
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.core.definition.SurveyDefinition
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentRuntimeTest {
    @Test
    fun requiredMissingHardwareBlocksEnrollment() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(
            clocks,
            setOf(AccessKind.GYROSCOPE_HARDWARE),
        )
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )

        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.completeAccessSetup(emptySet()))
        assertEquals(ExperimentState.ACCESS_SETUP, runtime.snapshot.value.metadata?.state)
        assertEquals(0, plugin.collector.startCount)
    }

    @Test
    fun optionalMissingHardwareBlocksOnlyItsCollectorAndStartsWhenAccessAppears() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(
            clocks,
            setOf(AccessKind.GYROSCOPE_HARDWARE),
        )
        val runtime = ExperimentRuntime(
            configuration = configuration(
                collectors = listOf(AppLifecycleConfiguration(required = false)),
            ),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )

        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
        assertEquals(CommandResult.Success, runtime.start(emptySet()))
        assertEquals(0, plugin.collector.startCount)
        assertEquals(
            CollectorHealth(CollectorStatus.BLOCKED_ACCESS, "ACCESS_UNAVAILABLE"),
            runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID],
        )

        assertEquals(CommandResult.Success, runtime.pause())
        assertEquals(
            CommandResult.Success,
            runtime.resume(setOf(AccessKind.GYROSCOPE_HARDWARE)),
        )
        assertEquals(1, plugin.collector.startCount)
        assertEquals(CollectorStatus.ACTIVE, plugin.collector.health.value.status)
    }

    @Test
    fun optionalAccessLossClosesAdmissionBeforeAFailedPauseWhileTheSourceKeepsEmitting() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val accessKind = AccessKind.GYROSCOPE_HARDWARE
        val plugin = FakeCollectorPlugin(clocks, setOf(accessKind))
        val runtime = ExperimentRuntime(
            configuration = configuration(
                collectors = listOf(AppLifecycleConfiguration(required = false)),
            ),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime, setOf(accessKind))
        val emissionsDuringFailedPause = mutableListOf<EmitResult>()
        plugin.collector.beforePause = {
            repeat(16) {
                emissionsDuringFailedPause += plugin.emit("ACTIVITY_STOPPED")
            }
        }
        plugin.collector.failNextPauseWithOwnedResources = true

        assertEquals(CommandResult.Success, runtime.reconcileCollectorAccess(emptySet()))

        assertEquals(1, plugin.collector.pauseCount)
        assertTrue(plugin.collector.requiresStop)
        assertEquals(
            List(16) { EmitResult.RejectedByAdmissionGate },
            emissionsDuringFailedPause,
        )
        repeat(16) {
            assertEquals(EmitResult.RejectedByAdmissionGate, plugin.emit("ACTIVITY_STOPPED"))
        }
        assertEquals(0L, runtime.snapshot.value.metadata?.eventCount)
        assertEquals(
            CollectorHealth(CollectorStatus.FAILED, "COLLECTOR_PAUSE_FAILED"),
            runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID],
        )
    }

    @Test
    fun optionalAccessLossTearsDownAFailedCollectorWhoseSourceIsStillLive() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val accessKind = AccessKind.GYROSCOPE_HARDWARE
        val plugin = FakeCollectorPlugin(clocks, setOf(accessKind))
        val runtime = ExperimentRuntime(
            configuration = configuration(
                collectors = listOf(AppLifecycleConfiguration(required = false)),
            ),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime, setOf(accessKind))
        plugin.collector.reportFailedWithOwnedResources()
        assertTrue(plugin.collector.requiresStop)
        assertTrue(plugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)

        assertEquals(CommandResult.Success, runtime.reconcileCollectorAccess(emptySet()))

        assertEquals(1, plugin.collector.pauseCount)
        assertEquals(EmitResult.RejectedByAdmissionGate, plugin.emit("ACTIVITY_STOPPED"))
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)
        assertEquals(
            CollectorHealth(CollectorStatus.BLOCKED_ACCESS, "ACCESS_UNAVAILABLE"),
            runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID],
        )
    }

    @Test
    fun optionalAccessRestorationKeepsAdmissionClosedUntilResumeSucceeds() = runTest {
        val clocks = FakeClocks()
        val accessKind = AccessKind.GYROSCOPE_HARDWARE
        val plugin = FakeCollectorPlugin(clocks, setOf(accessKind))
        val runtime = ExperimentRuntime(
            configuration = configuration(
                collectors = listOf(AppLifecycleConfiguration(required = false)),
            ),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime, setOf(accessKind))
        assertEquals(CommandResult.Success, runtime.reconcileCollectorAccess(emptySet()))
        plugin.collector.failNextResumeWithOwnedResources = true

        assertEquals(
            CommandResult.Success,
            runtime.reconcileCollectorAccess(setOf(accessKind)),
        )
        assertEquals(EmitResult.RejectedByAdmissionGate, plugin.emit("ACTIVITY_RESUMED"))
        assertEquals(
            CollectorHealth(CollectorStatus.FAILED, "COLLECTOR_RESUME_FAILED"),
            runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID],
        )

        assertEquals(
            CommandResult.Success,
            runtime.reconcileCollectorAccess(setOf(accessKind)),
        )
        assertEquals(2, plugin.collector.resumeCount)
        assertTrue(plugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)
    }

    @Test
    fun collectorAdmissionIsIndependentAndTokensCannotCrossBoundaries() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val accessKind = AccessKind.GYROSCOPE_HARDWARE
        val lifecyclePlugin = FakeCollectorPlugin(clocks, setOf(accessKind))
        val batteryPlugin = FakeCollectorPlugin(
            clocks = clocks,
            collectorId = BatteryStateConfiguration.ID,
        )
        val runtime = ExperimentRuntime(
            configuration = configuration(
                collectors = listOf(
                    AppLifecycleConfiguration(required = false),
                    BatteryStateConfiguration(required = false),
                ),
            ),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(lifecyclePlugin, batteryPlugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime, setOf(accessKind))
        val lifecycleToken = checkNotNull(lifecyclePlugin.captureToken())
        val forgedGlobalToken = object : AdmissionToken {}
        assertEquals(CommandResult.Success, runtime.reconcileCollectorAccess(emptySet()))

        assertFalse(runtime as Any is EventSink)
        assertEquals(1, lifecyclePlugin.collector.pauseCount)
        assertEquals(0, batteryPlugin.collector.pauseCount)
        assertEquals(
            EmitResult.RejectedByAdmissionGate,
            batteryPlugin.emitWithToken(lifecycleToken, "ACTIVITY_RESUMED"),
        )
        assertEquals(
            EmitResult.RejectedByAdmissionGate,
            lifecyclePlugin.emitWithToken(forgedGlobalToken, "ACTIVITY_RESUMED"),
        )
        assertEquals(EmitResult.RejectedByAdmissionGate, lifecyclePlugin.emit("ACTIVITY_RESUMED"))
        assertTrue(batteryPlugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)
    }

    @Test
    fun participantCommandsGateAndPersistCollectorEvents() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )

        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(ExperimentState.IMPORTED, runtime.snapshot.value.metadata?.state)
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(ExperimentState.CONSENT_PENDING, runtime.snapshot.value.metadata?.state)
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
        assertEquals(ExperimentState.READY, runtime.snapshot.value.metadata?.state)

        assertEquals(CommandResult.Success, runtime.start(emptySet()))
        assertEquals(1, plugin.collector.startCount)
        assertEquals(CollectorStatus.ACTIVE, plugin.collector.health.value.status)
        assertTrue(plugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)

        assertEquals(CommandResult.Success, runtime.pause())
        assertEquals(ExperimentState.PAUSED, runtime.snapshot.value.metadata?.state)
        assertEquals(1, plugin.collector.pauseCount)
        assertEquals(EmitResult.RejectedByAdmissionGate, plugin.emit("ACTIVITY_STOPPED"))
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)

        assertEquals(CommandResult.Success, runtime.resume(emptySet()))
        assertEquals(1, plugin.collector.resumeCount)
        assertTrue(plugin.emit("ACTIVITY_STARTED") is EmitResult.Accepted)
        assertEquals(listOf(1L, 2L), store.events.map { it.sequenceNumber })

        assertEquals(CommandResult.Success, runtime.finishEarly())
        assertEquals(ExperimentState.COMPLETED, runtime.snapshot.value.metadata?.state)
        assertEquals(1, plugin.collector.stopCount)
        assertEquals(CollectorStatus.STOPPED, plugin.collector.health.value.status)
        assertEquals(8, runtime.snapshot.value.metadata?.transitions?.size)
        assertTrue(store.saveCount >= 11)
        assertNull(runtime.snapshot.value.incidentCode)
    }

    @Test
    fun collectorReceivesAdmissionOpenedOnlyAfterItsGateCanIssueTokens() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        assertEquals(CommandResult.Success, runtime.initialize())
        plugin.collector.afterAdmissionOpened = {
            assertTrue(plugin.emit("ACTIVITY_STARTED") is EmitResult.Accepted)
        }

        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
        assertEquals(CommandResult.Success, runtime.start(emptySet()))

        assertEquals(1, plugin.collector.admissionOpenedCount)
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)

        assertEquals(CommandResult.Success, runtime.pause())
        assertEquals(CommandResult.Success, runtime.resume(emptySet()))
        assertEquals(2, plugin.collector.admissionOpenedCount)
        assertEquals(2L, runtime.snapshot.value.metadata?.eventCount)
    }

    @Test
    fun admissionOpenedFailureClosesTheGateAndReportsCollectorActivationFailure() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        assertEquals(CommandResult.Success, runtime.initialize())
        plugin.collector.afterAdmissionOpened = { error("Initial snapshot failed") }

        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
        assertEquals(CommandResult.Success, runtime.start(emptySet()))

        assertEquals(
            CollectorHealth(CollectorStatus.FAILED, "COLLECTOR_START_FAILED"),
            runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID],
        )
        assertNull(plugin.captureToken())
        assertEquals(0L, runtime.snapshot.value.metadata?.eventCount)

        assertEquals(CommandResult.Success, runtime.pause())
        plugin.collector.afterAdmissionOpened = {
            assertTrue(plugin.emit("ACTIVITY_STARTED") is EmitResult.Accepted)
        }
        assertEquals(CommandResult.Success, runtime.resume(emptySet()))
        assertEquals(CollectorStatus.ACTIVE, runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID]?.status)
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)
    }

    @Test
    fun collectorAdmissionStopsAtTheExactSignedDurationBoundary() = runTest {
        val clocks = ControlledClocks(ResearchTime(10_000, 1_000, "boot-test"))
        val store = InMemoryStudyStore()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        val startedAt = runtime.snapshot.value.metadata?.transitions
            ?.single { it.reason == TransitionReason.PARTICIPANT_STARTED }
            ?.time ?: error("Missing participant-start boundary")
        val deadlineElapsedNanos = startedAt.elapsedRealtimeNanos + NANOS_PER_HOUR

        clocks.current = ResearchTime(
            wallTimeUtcMillis = startedAt.wallTimeUtcMillis + 3_599_999,
            elapsedRealtimeNanos = deadlineElapsedNanos - 1,
            bootSessionId = startedAt.bootSessionId,
        )
        assertTrue(plugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)

        clocks.current = clocks.current.copy(
            wallTimeUtcMillis = startedAt.wallTimeUtcMillis + 3_600_000,
            elapsedRealtimeNanos = deadlineElapsedNanos,
        )
        assertEquals(EmitResult.RejectedByAdmissionGate, plugin.emit("ACTIVITY_STOPPED"))
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)
        assertEquals(listOf(1L), store.events.map { it.sequenceNumber })
        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
    }

    @Test
    fun occurrenceMutationsCannotCrossTheExactSignedDurationBoundary() = runTest {
        val clocks = ControlledClocks(ResearchTime(10_000, 1_000, "boot-test"))
        val runtime = ExperimentRuntime(
            configuration = configuration(
                surveys = listOf(survey()),
                interventions = listOf(surveyIntervention()),
            ),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        val startedAt = runtime.snapshot.value.metadata?.transitions
            ?.single { it.reason == TransitionReason.PARTICIPANT_STARTED }
            ?.time ?: error("Missing participant-start boundary")
        val deadlineElapsedNanos = startedAt.elapsedRealtimeNanos + NANOS_PER_HOUR
        clocks.current = ResearchTime(
            wallTimeUtcMillis = startedAt.wallTimeUtcMillis + 3_599_999,
            elapsedRealtimeNanos = deadlineElapsedNanos - 1,
            bootSessionId = startedAt.bootSessionId,
        )
        val occurrence = surveyOccurrence(
            prefix = "e",
            scheduledAtUtcMillis = 1,
            expiresAtUtcMillis = Long.MAX_VALUE,
        )
        runtime.ensureOccurrence(occurrence)
        assertTrue(runtime.claimOccurrenceIfDue(occurrence.occurrenceId) is OccurrenceClaimResult.Due)
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)

        clocks.current = clocks.current.copy(
            wallTimeUtcMillis = startedAt.wallTimeUtcMillis + 3_600_000,
            elapsedRealtimeNanos = deadlineElapsedNanos,
        )
        val second = surveyOccurrence(
            prefix = "f",
            scheduledAtUtcMillis = 1,
            expiresAtUtcMillis = Long.MAX_VALUE,
        )
        assertTrue(runCatching { runtime.ensureOccurrence(second) }.isFailure)
        assertEquals(OccurrenceClaimResult.InactiveStudy, runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(OccurrenceExpiryResult.InactiveStudy, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertFalse(runtime.markNotificationPosted(occurrence.occurrenceId))
        assertNull(runtime.openOccurrence(occurrence.occurrenceId))
        assertEquals(
            SurveySubmissionResult.INVALID,
            runtime.submitSurvey(occurrence.occurrenceId, validSurveyAnswers()),
        )
        assertEquals(1L, runtime.snapshot.value.metadata?.eventCount)
        assertEquals(
            OccurrenceState.POSTING,
            runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )
        assertFalse(runtime.snapshot.value.metadata?.occurrences.orEmpty().containsKey(second.occurrenceId))
    }

    @Test
    fun initializationRecoversRunningStateAndRestartsCollectors() = runTest {
        val store = InMemoryStudyStore(
            StudyMetadata.initial(EXPERIMENT_ID, CONFIGURATION_ID).copy(
                state = ExperimentState.RUNNING,
                transitions = listOf(
                    ExperimentTransition(
                        from = ExperimentState.READY,
                        to = ExperimentState.RUNNING,
                        reason = TransitionReason.PARTICIPANT_STARTED,
                        time = ResearchTime(0, 0, "boot-test"),
                    ),
                ),
            ),
        )
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )

        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(CommandResult.Success, runtime.activateRecoveredRunning(emptySet()))

        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
        assertEquals(1, plugin.collector.startCount)
        assertTrue(plugin.emit("ACTIVITY_RESUMED") is EmitResult.Accepted)
    }

    @Test
    fun failedInitialStartRetainsCollectorOwnershipUntilShutdownReleasesIt() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        assertEquals(CommandResult.Success, runtime.initialize())
        plugin.collector.failNextStartWithOwnedResources = true
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))

        assertEquals(CommandResult.Success, runtime.start(emptySet()))
        assertTrue(plugin.collector.requiresStop)
        assertEquals(CollectorStatus.FAILED, runtime.snapshot.value.collectorHealth[AppLifecycleConfiguration.ID]?.status)

        runtime.shutdown()
        assertEquals(1, plugin.collector.stopCount)
        assertFalse(plugin.collector.requiresStop)
    }

    @Test
    fun failedTerminalStopRemainsOwnedAndShutdownRetriesIt() = runTest {
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        plugin.collector.failNextStopWithOwnedResources = true

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.finishEarly())
        assertEquals(1, plugin.collector.stopCount)
        assertTrue(plugin.collector.requiresStop)
        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
        assertEquals(
            SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
            runtime.snapshot.value.pendingSafetyPauseReason,
        )

        runtime.shutdown()
        assertEquals(2, plugin.collector.stopCount)
        assertFalse(plugin.collector.requiresStop)
    }

    @Test
    fun collectorEventContractIsEnforcedBeforePersistence() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)

        assertEquals(EmitResult.ContractViolation, plugin.emit("ACTIVITY_RESUMED", schemaVersion = 2))
        assertEquals(EmitResult.ContractViolation, plugin.emit("ACTIVITY_RESUMED", collectorId = "other.v1"))
        assertEquals(
            EmitResult.ContractViolation,
            plugin.emit("ACTIVITY_RESUMED", fields = mapOf("source" to "x".repeat(2_000))),
        )
        assertTrue(store.events.isEmpty())
        assertEquals(0L, runtime.snapshot.value.metadata?.eventCount)
    }

    @Test
    fun storageAppendFailureClosesEveryAdmissionBoundaryAndPublishesTypedRequest() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val lifecycle = FakeCollectorPlugin(clocks)
        val battery = FakeCollectorPlugin(clocks, collectorId = BatteryStateConfiguration.ID)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(
                collectors = listOf(
                    AppLifecycleConfiguration(required = true),
                    BatteryStateConfiguration(required = true),
                ),
            ),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(lifecycle, battery)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        val staleBatteryToken = checkNotNull(battery.captureToken())
        store.appendFailure = IllegalStateException("storage unavailable")

        assertEquals(EmitResult.StorageFailure, lifecycle.emit("ACTIVITY_RESUMED"))

        assertEquals(SafetyPauseReason.STORAGE_FAILURE, runtime.snapshot.value.pendingSafetyPauseReason)
        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
        assertNull(lifecycle.captureToken())
        assertNull(battery.captureToken())
        assertEquals(
            EmitResult.RejectedByAdmissionGate,
            battery.emitWithToken(staleBatteryToken, "ACTIVITY_RESUMED"),
        )
        assertEquals(0L, runtime.snapshot.value.metadata?.eventCount)
        assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), witness.persistedReasons)
    }

    @Test
    fun occurrenceAppendFailureUsesTheSameClosedTypedStorageBoundary() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val collector = FakeCollectorPlugin(clocks)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(
                surveys = listOf(survey()),
                interventions = listOf(surveyIntervention()),
            ),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(collector)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        val occurrence = surveyOccurrence("d", scheduledAtUtcMillis = 1, expiresAtUtcMillis = 100_000)
        runtime.ensureOccurrence(occurrence)
        assertTrue(runtime.claimOccurrenceIfDue(occurrence.occurrenceId) is OccurrenceClaimResult.Due)
        val staleToken = checkNotNull(collector.captureToken())
        val storageFailure = IllegalStateException("occurrence storage unavailable")
        store.appendFailure = storageFailure

        assertEquals(storageFailure, runCatching {
            runtime.markNotificationPosted(occurrence.occurrenceId)
        }.exceptionOrNull())

        assertEquals(SafetyPauseReason.STORAGE_FAILURE, runtime.snapshot.value.pendingSafetyPauseReason)
        assertNull(collector.captureToken())
        assertEquals(
            EmitResult.RejectedByAdmissionGate,
            collector.emitWithToken(staleToken, "ACTIVITY_RESUMED"),
        )
        assertEquals(OccurrenceState.POSTING, runtime.snapshot.value.metadata
            ?.occurrences?.get(occurrence.occurrenceId)?.state)
        assertEquals(OccurrenceClaimResult.InactiveStudy, runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(OccurrenceExpiryResult.InactiveStudy, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertFalse(runtime.markNotificationPosted(occurrence.occurrenceId))
        assertNull(runtime.openOccurrence(occurrence.occurrenceId))
        assertEquals(
            SurveySubmissionResult.INVALID,
            runtime.submitSurvey(occurrence.occurrenceId, validSurveyAnswers()),
        )
        assertTrue(runCatching { runtime.ensureOccurrence(occurrence) }.isFailure)
        assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), witness.persistedReasons)
    }

    @Test
    fun durablyRecoveredPostedOccurrenceReportsFinalizedWhileStudyFailsClosed() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(
                surveys = listOf(survey()),
                interventions = listOf(surveyIntervention()),
            ),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        val occurrence = surveyOccurrence(
            "e",
            scheduledAtUtcMillis = 1,
            expiresAtUtcMillis = 100_000,
        )
        runtime.ensureOccurrence(occurrence)
        assertTrue(runtime.claimOccurrenceIfDue(occurrence.occurrenceId) is OccurrenceClaimResult.Due)
        store.recoverNextAppendFailClosed = true

        assertTrue(runtime.markNotificationPosted(occurrence.occurrenceId))

        val snapshot = runtime.snapshot.value
        assertEquals(ExperimentState.PAUSED, snapshot.metadata?.state)
        assertEquals(SafetyPauseReason.STORAGE_FAILURE, snapshot.pendingSafetyPauseReason)
        assertEquals(
            OccurrenceState.NOTIFICATION_POSTED,
            snapshot.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )
        assertEquals("NOTIFICATION_POSTED", store.events.last().payloadType)
        assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), witness.persistedReasons)
    }

    @Test
    fun storageFailureWinsDeterministicallyWhenParticipantPauseWaitsForTheAppend() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val collector = FakeCollectorPlugin(clocks)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(collector)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        store.appendEntered = CompletableDeferred()
        store.releaseAppend = CompletableDeferred()
        store.appendFailure = IllegalStateException("storage unavailable")

        val emission = async { collector.emit("ACTIVITY_RESUMED") }
        store.appendEntered?.await()
        val participantPause = async { runtime.pause() }
        runCurrent()
        assertFalse(participantPause.isCompleted)

        store.releaseAppend?.complete(Unit)
        assertEquals(EmitResult.StorageFailure, emission.await())
        assertEquals(CommandResult.Success, participantPause.await())

        assertEquals(ExperimentState.PAUSED, runtime.snapshot.value.metadata?.state)
        assertEquals(
            TransitionReason.STORAGE_FAILURE,
            runtime.snapshot.value.metadata?.transitions?.last()?.reason,
        )
        assertEquals(SafetyPauseReason.STORAGE_FAILURE, runtime.snapshot.value.pendingSafetyPauseReason)
        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.resume(emptySet()))
        assertNull(collector.captureToken())
        assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), witness.persistedReasons)
    }

    @Test
    fun firstPublishedSafetyReasonWinsAgainstAConcurrentStorageFailure() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val collector = FakeCollectorPlugin(clocks)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(collector)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        store.appendEntered = CompletableDeferred()
        store.releaseAppend = CompletableDeferred()
        store.appendFailure = IllegalStateException("storage unavailable")

        val emission = async { collector.emit("ACTIVITY_RESUMED") }
        store.appendEntered?.await()
        assertEquals(
            SafetyPauseReason.REQUIRED_ACCESS_MISSING,
            runtime.closeAdmissionForSafetyFailure(SafetyPauseReason.REQUIRED_ACCESS_MISSING),
        )
        val safetyPause = async {
            runtime.pauseForSafetyFailure(SafetyPauseReason.REQUIRED_ACCESS_MISSING)
        }
        runCurrent()
        assertFalse(safetyPause.isCompleted)

        store.releaseAppend?.complete(Unit)
        assertEquals(EmitResult.StorageFailure, emission.await())
        assertEquals(CommandResult.Success, safetyPause.await())

        assertEquals(SafetyPauseReason.REQUIRED_ACCESS_MISSING, runtime.snapshot.value.pendingSafetyPauseReason)
        assertEquals(
            TransitionReason.REQUIRED_ACCESS_MISSING,
            runtime.snapshot.value.metadata?.transitions?.last()?.reason,
        )
        assertEquals(listOf(SafetyPauseReason.REQUIRED_ACCESS_MISSING), witness.persistedReasons)
    }

    @Test
    fun firstPublishedReasonReplacesSyntheticRecoveredStorageTransition() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val collector = FakeCollectorPlugin(clocks)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(collector)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        store.appendEntered = CompletableDeferred()
        store.releaseAppend = CompletableDeferred()
        store.recoverNextAppendFailClosed = true

        val emission = async { collector.emit("ACTIVITY_RESUMED") }
        store.appendEntered?.await()
        assertEquals(
            SafetyPauseReason.REQUIRED_ACCESS_MISSING,
            runtime.closeAdmissionForSafetyFailure(SafetyPauseReason.REQUIRED_ACCESS_MISSING),
        )
        val safetyPause = async {
            runtime.pauseForSafetyFailure(SafetyPauseReason.REQUIRED_ACCESS_MISSING)
        }
        runCurrent()
        assertFalse(safetyPause.isCompleted)

        store.releaseAppend?.complete(Unit)
        assertEquals(EmitResult.StorageFailure, emission.await())
        assertEquals(CommandResult.Success, safetyPause.await())

        assertEquals(ExperimentState.PAUSED, runtime.snapshot.value.metadata?.state)
        assertEquals(
            TransitionReason.REQUIRED_ACCESS_MISSING,
            runtime.snapshot.value.metadata?.transitions?.last()?.reason,
        )
        assertEquals(SafetyPauseReason.REQUIRED_ACCESS_MISSING, runtime.snapshot.value.pendingSafetyPauseReason)
        assertEquals(listOf(SafetyPauseReason.REQUIRED_ACCESS_MISSING), witness.persistedReasons)
    }

    @Test
    fun terminalCommandsWaitForAdmittedWritesAndAbortOnStorageFailure() = runTest {
        val commands = listOf<Pair<String, suspend ExperimentRuntime.() -> CommandResult>>(
            "finish" to { finishEarly() },
            "duration" to { completeAfterDuration() },
            "withdraw" to { withdraw() },
        )
        commands.forEach { (name, command) ->
            val store = InMemoryStudyStore()
            val clocks = FakeClocks()
            val collector = FakeCollectorPlugin(clocks)
            val witness = RecordingSafetyPauseWitness()
            val runtime = ExperimentRuntime(
                configuration = configuration(),
                store = store,
                collectorRegistry = CollectorRegistry(listOf(collector)),
                clocks = clocks,
                scope = backgroundScope,
                safetyPauseWitness = witness,
            )
            start(runtime)
            store.appendEntered = CompletableDeferred()
            store.releaseAppend = CompletableDeferred()
            store.appendFailure = IllegalStateException("$name storage unavailable")

            val emission = async { collector.emit("ACTIVITY_RESUMED") }
            store.appendEntered?.await()
            val terminal = async { runtime.command() }
            runCurrent()
            assertFalse("$name committed before its admitted write drained", terminal.isCompleted)

            store.releaseAppend?.complete(Unit)
            assertEquals(EmitResult.StorageFailure, emission.await())
            assertEquals(CommandResult.Failed("COMMAND_REJECTED"), terminal.await())

            assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
            assertEquals(SafetyPauseReason.STORAGE_FAILURE, runtime.snapshot.value.pendingSafetyPauseReason)
            assertTrue(runtime.snapshot.value.metadata?.transitions.orEmpty().none {
                it.to in setOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN)
            })
            assertNull(collector.captureToken())
            assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), witness.persistedReasons)
        }
    }

    @Test
    fun cancelledAdmittedWriteCannotStrandTheTerminalDrainBarrier() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val collector = FakeCollectorPlugin(clocks)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(collector)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        store.appendEntered = CompletableDeferred()
        store.releaseAppend = CompletableDeferred()

        val emission = async { collector.emit("ACTIVITY_RESUMED") }
        store.appendEntered?.await()
        val finish = async { runtime.finishEarly() }
        runCurrent()
        assertFalse(finish.isCompleted)

        emission.cancelAndJoin()
        runCurrent()

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), finish.await())
        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
        assertEquals(SafetyPauseReason.STORAGE_FAILURE, runtime.snapshot.value.pendingSafetyPauseReason)
        assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), witness.persistedReasons)
    }

    @Test
    fun terminalCollectorStopFailureUsesTypedTeardownPauseAndCanBeRetried() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        plugin.collector.failNextStopWithOwnedResources = true

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.finishEarly())

        assertEquals(ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
        assertEquals(
            SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
            runtime.snapshot.value.pendingSafetyPauseReason,
        )
        assertNull(plugin.captureToken())
        assertEquals(
            listOf(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
            witness.persistedReasons,
        )

        assertEquals(
            CommandResult.Success,
            runtime.retrySafetyPause(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
        )
        assertTrue(runtime.acknowledgeSafetyPauseRequest(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE))
        assertEquals(CommandResult.Success, runtime.finishEarly())
        assertEquals(ExperimentState.COMPLETED, runtime.snapshot.value.metadata?.state)
        assertEquals(2, plugin.collector.stopCount)
    }

    @Test
    fun terminalTeardownFailureFromPausedPreservesTheEarlierParticipantPause() = runTest {
        val terminalCommands = listOf<Pair<String, suspend (ExperimentRuntime) -> CommandResult>>(
            "finish" to { runtime -> runtime.finishEarly() },
            "withdraw" to { runtime -> runtime.withdraw() },
        )
        terminalCommands.forEach { (name, terminalCommand) ->
            val store = InMemoryStudyStore()
            val clocks = FakeClocks()
            val plugin = FakeCollectorPlugin(clocks)
            val witness = RecordingSafetyPauseWitness()
            val runtime = ExperimentRuntime(
                configuration = configuration(),
                store = store,
                collectorRegistry = CollectorRegistry(listOf(plugin)),
                clocks = clocks,
                scope = backgroundScope,
                safetyPauseWitness = witness,
            )
            start(runtime)
            assertEquals(name, CommandResult.Success, runtime.pause())
            val participantPause = requireNotNull(runtime.snapshot.value.metadata).transitions.last()
            plugin.collector.failNextStopWithOwnedResources = true

            assertEquals(name, CommandResult.Failed("COMMAND_REJECTED"), terminalCommand(runtime))

            val paused = requireNotNull(runtime.snapshot.value.metadata)
            assertEquals(name, ExperimentState.PAUSED, paused.state)
            assertEquals(name, participantPause, paused.transitions.last())
            assertEquals(
                name,
                SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                runtime.snapshot.value.pendingSafetyPauseReason,
            )
            assertEquals(
                name,
                listOf(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
                witness.persistedReasons,
            )
            assertEquals(
                name,
                CommandResult.Success,
                runtime.retrySafetyPause(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
            )
            assertTrue(
                name,
                runtime.acknowledgeSafetyPauseRequest(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
            )
            assertEquals(name, participantPause, runtime.snapshot.value.metadata?.transitions?.last())
        }
    }

    @Test
    fun cancellingAParticipantOrTerminalDrainPersistsATypedTeardownWitness() = runTest {
        data class CancelledDrain(
            val name: String,
            val blocksPause: Boolean,
            val command: suspend (ExperimentRuntime) -> CommandResult,
        )
        val commands = listOf(
            CancelledDrain("pause", true) { runtime -> runtime.pause() },
            CancelledDrain("finish", false) { runtime -> runtime.finishEarly() },
            CancelledDrain("duration", false) { runtime -> runtime.completeAfterDuration() },
            CancelledDrain("withdraw", false) { runtime -> runtime.withdraw() },
        )
        commands.forEach { command ->
            val clocks = FakeClocks()
            val plugin = FakeCollectorPlugin(clocks)
            val witness = RecordingSafetyPauseWitness()
            val runtime = ExperimentRuntime(
                configuration = configuration(),
                store = InMemoryStudyStore(),
                collectorRegistry = CollectorRegistry(listOf(plugin)),
                clocks = clocks,
                scope = backgroundScope,
                safetyPauseWitness = witness,
            )
            start(runtime)
            val teardownEntered = CompletableDeferred<Unit>()
            val blockUntilCancelled: suspend () -> Unit = {
                teardownEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            if (command.blocksPause) {
                plugin.collector.beforePause = blockUntilCancelled
            } else {
                plugin.collector.beforeStop = blockUntilCancelled
            }

            val drain = async { command.command(runtime) }
            teardownEntered.await()
            drain.cancelAndJoin()

            assertEquals(command.name, ExperimentState.RUNNING, runtime.snapshot.value.metadata?.state)
            assertEquals(
                command.name,
                SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
                runtime.snapshot.value.pendingSafetyPauseReason,
            )
            assertEquals(
                command.name,
                listOf(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
                witness.persistedReasons,
            )
            assertNull(command.name, plugin.captureToken())

            plugin.collector.beforePause = {}
            plugin.collector.beforeStop = {}
            assertEquals(
                command.name,
                CommandResult.Success,
                runtime.retrySafetyPause(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
            )
            assertTrue(
                command.name,
                runtime.acknowledgeSafetyPauseRequest(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
            )
            assertEquals(command.name, ExperimentState.PAUSED, runtime.snapshot.value.metadata?.state)
        }
    }

    @Test
    fun unacknowledgedTransitionToRunningIsWitnessedAndRollsBackTheDurableFile() = runTest {
        val startingStore = InMemoryStudyStore()
        val startingWitness = RecordingSafetyPauseWitness()
        val starting = ExperimentRuntime(
            configuration = configuration(),
            store = startingStore,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(FakeClocks()))),
            clocks = FakeClocks(),
            scope = backgroundScope,
            safetyPauseWitness = startingWitness,
        )
        assertEquals(CommandResult.Success, starting.initialize())
        assertEquals(CommandResult.Success, starting.reviewStudy())
        assertEquals(CommandResult.Success, starting.acceptConsent())
        assertEquals(CommandResult.Success, starting.completeAccessSetup(emptySet()))
        startingStore.saveAfterCommitFailure = IllegalStateException("directory fsync was not acknowledged")

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), starting.start(emptySet()))
        assertEquals(ExperimentState.READY, starting.snapshot.value.metadata?.state)
        assertEquals(ExperimentState.RUNNING, startingStore.metadata?.state)
        assertEquals(SafetyPauseReason.STORAGE_FAILURE, starting.snapshot.value.pendingSafetyPauseReason)
        assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), startingWitness.persistedReasons)

        assertEquals(CommandResult.Success, starting.retrySafetyPause(SafetyPauseReason.STORAGE_FAILURE))
        assertEquals(ExperimentState.READY, startingStore.metadata?.state)
        assertTrue(starting.acknowledgeSafetyPauseRequest(SafetyPauseReason.STORAGE_FAILURE))

        val resumingStore = InMemoryStudyStore()
        val resumingWitness = RecordingSafetyPauseWitness()
        val resuming = ExperimentRuntime(
            configuration = configuration(),
            store = resumingStore,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(FakeClocks()))),
            clocks = FakeClocks(),
            scope = backgroundScope,
            safetyPauseWitness = resumingWitness,
        )
        start(resuming)
        assertEquals(CommandResult.Success, resuming.pause())
        val participantPause = resuming.snapshot.value.metadata?.transitions?.last()
        resumingStore.saveAfterCommitFailure = IllegalStateException("resume commit was not acknowledged")

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), resuming.resume(emptySet()))
        assertEquals(ExperimentState.PAUSED, resuming.snapshot.value.metadata?.state)
        assertEquals(ExperimentState.RUNNING, resumingStore.metadata?.state)
        assertEquals(SafetyPauseReason.STORAGE_FAILURE, resuming.snapshot.value.pendingSafetyPauseReason)

        assertEquals(CommandResult.Success, resuming.retrySafetyPause(SafetyPauseReason.STORAGE_FAILURE))
        assertEquals(ExperimentState.PAUSED, resumingStore.metadata?.state)
        assertEquals(participantPause, resumingStore.metadata?.transitions?.last())
        assertTrue(resuming.acknowledgeSafetyPauseRequest(SafetyPauseReason.STORAGE_FAILURE))
    }

    @Test
    fun cancellationIsNeverMisclassifiedAsAnIllegalCommand() = runTest {
        val store = InMemoryStudyStore()
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(FakeClocks()))),
            clocks = FakeClocks(),
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        assertEquals(CommandResult.Success, runtime.initialize())
        assertEquals(CommandResult.Success, runtime.reviewStudy())
        assertEquals(CommandResult.Success, runtime.acceptConsent())
        assertEquals(CommandResult.Success, runtime.completeAccessSetup(emptySet()))
        val cancellation = CancellationException("caller cancelled the start")
        store.saveAfterCommitFailure = cancellation

        val actual = runCatching { runtime.start(emptySet()) }.exceptionOrNull()

        assertTrue(actual === cancellation)
        assertEquals(ExperimentState.READY, runtime.snapshot.value.metadata?.state)
        assertEquals(ExperimentState.RUNNING, store.metadata?.state)
        assertEquals(SafetyPauseReason.STORAGE_FAILURE, runtime.snapshot.value.pendingSafetyPauseReason)
        assertEquals(listOf(SafetyPauseReason.STORAGE_FAILURE), witness.persistedReasons)
    }

    @Test
    fun participantPauseFailureUsesTypedTeardownPauseAndCannotReportSuccess() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val plugin = FakeCollectorPlugin(clocks)
        val witness = RecordingSafetyPauseWitness()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(plugin)),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = witness,
        )
        start(runtime)
        plugin.collector.failNextPauseWithOwnedResources = true

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.pause())

        assertEquals(ExperimentState.PAUSED, runtime.snapshot.value.metadata?.state)
        assertEquals(
            TransitionReason.COLLECTION_TEARDOWN_FAILURE,
            runtime.snapshot.value.metadata?.transitions?.last()?.reason,
        )
        assertEquals(
            SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE,
            runtime.snapshot.value.pendingSafetyPauseReason,
        )
        assertNull(plugin.captureToken())
        assertEquals(
            listOf(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
            witness.persistedReasons,
        )

        assertEquals(
            CommandResult.Success,
            runtime.retrySafetyPause(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE),
        )
        assertTrue(runtime.acknowledgeSafetyPauseRequest(SafetyPauseReason.COLLECTION_TEARDOWN_FAILURE))
        assertEquals(CommandResult.Success, runtime.resume(emptySet()))
        assertEquals(2, plugin.collector.pauseCount)
        assertEquals(1, plugin.collector.resumeCount)
    }

    @Test
    fun surveySubmissionValidatesEveryQuestionTypeAndCommitsExactlyOnce() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        val occurrence = InterventionOccurrence(
            occurrenceId = "a".repeat(64),
            interventionId = "survey-notice",
            triggerId = "after-minute",
            scheduleKey = "relative:1",
            scheduledFor = ResearchTime(1_000, 1_000, "boot-test"),
            expiresAtUtcMillis = 60_000,
            state = OccurrenceState.SCHEDULED,
        )
        runtime.ensureOccurrence(occurrence)
        val claim = runtime.claimOccurrenceIfDue(occurrence.occurrenceId) as OccurrenceClaimResult.Due
        assertTrue(claim.dispatch.action is SurveyAction)
        runtime.markNotificationPosted(occurrence.occurrenceId)
        assertEquals(OccurrenceState.OPENED, runtime.openOccurrence(occurrence.occurrenceId)?.occurrence?.state)

        val incomplete = mapOf("mood-scale" to SurveyAnswer.Integer(3))
        assertEquals(SurveySubmissionResult.INVALID, runtime.submitSurvey(occurrence.occurrenceId, incomplete))
        val answers = mapOf(
            "daily-note" to SurveyAnswer.Text("felt focused"),
            "mood-scale" to SurveyAnswer.Integer(4),
            "primary-place" to SurveyAnswer.Choices(listOf("place-home")),
            "symptoms" to SurveyAnswer.Choices(listOf("symptom-tired", "symptom-headache")),
        )
        val concurrent = listOf(
            async { runtime.submitSurvey(occurrence.occurrenceId, answers) },
            async { runtime.submitSurvey(occurrence.occurrenceId, answers) },
        ).awaitAll()
        assertEquals(1, concurrent.count { it == SurveySubmissionResult.ACCEPTED })
        assertEquals(1, concurrent.count { it == SurveySubmissionResult.ALREADY_SUBMITTED })

        val submitted = requireNotNull(runtime.surveySubmissionEvent(occurrence.occurrenceId))
        assertEquals("SURVEY_SUBMITTED", submitted.payloadType)
        assertEquals("daily-survey", submitted.fields["survey_id"])
        val encoded = requireNotNull(submitted.fields["answers_json"])
        assertTrue(encoded.contains("\"daily-note\":\"felt focused\""))
        assertTrue(encoded.contains("\"primary-place\":[\"place-home\"]"))
        assertTrue(encoded.contains("\"symptoms\":[\"symptom-tired\",\"symptom-headache\"]"))
        assertTrue(!encoded.contains("Home"))
        assertEquals(4L, store.events.count().toLong())
    }

    @Test
    fun lateSurveyOccurrenceExpiresWithoutOpeningOrSubmission() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        val occurrence = InterventionOccurrence(
            occurrenceId = "b".repeat(64),
            interventionId = "survey-notice",
            triggerId = "after-minute",
            scheduleKey = "relative:1",
            scheduledFor = ResearchTime(1, 1, "boot-test"),
            expiresAtUtcMillis = 2_500,
            state = OccurrenceState.SCHEDULED,
        )
        runtime.ensureOccurrence(occurrence)
        assertEquals(OccurrenceClaimResult.Expired, runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertNull(runtime.openOccurrence(occurrence.occurrenceId))
        assertEquals(OccurrenceState.EXPIRED, runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state)
        assertEquals(listOf("INTERVENTION_SCHEDULED", "SURVEY_EXPIRED"), store.events.map { it.payloadType })
    }

    @Test
    fun dedicatedExpiryCheckLeavesEarlyScheduledWorkUntouchedAndExpiresOnce() = runTest {
        val store = InMemoryStudyStore()
        val clocks = MutableClocks(1_000)
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        val occurrence = surveyOccurrence("c", expiresAtUtcMillis = 5_000)
        runtime.ensureOccurrence(occurrence)

        clocks.wallTimeUtcMillis = 4_250
        assertEquals(OccurrenceExpiryResult.NotDue(750), runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(
            OccurrenceState.SCHEDULED,
            runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )

        clocks.wallTimeUtcMillis = 5_000
        assertEquals(OccurrenceExpiryResult.Expired, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(OccurrenceExpiryResult.Terminal, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(OccurrenceClaimResult.Terminal, runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(
            1,
            store.events.count { it.payloadType == "SURVEY_EXPIRED" && it.fields["occurrence_id"] == occurrence.occurrenceId },
        )
    }

    @Test
    fun deliveryClaimWaitsForItsWallInstantAndARecoveredPostingClaimIsIdempotent() = runTest {
        val clocks = MutableClocks(1_000)
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = InMemoryStudyStore(),
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        val occurrence = surveyOccurrence("9", scheduledAtUtcMillis = 3_000, expiresAtUtcMillis = 5_000)
        runtime.ensureOccurrence(occurrence)

        clocks.wallTimeUtcMillis = 2_500
        assertEquals(OccurrenceClaimResult.NotDue(500), runtime.claimOccurrenceIfDue(occurrence.occurrenceId))
        assertEquals(
            OccurrenceState.SCHEDULED,
            runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )

        clocks.wallTimeUtcMillis = 3_000
        val first = runtime.claimOccurrenceIfDue(occurrence.occurrenceId) as OccurrenceClaimResult.Due
        val recovered = runtime.claimOccurrenceIfDue(occurrence.occurrenceId) as OccurrenceClaimResult.Due
        assertEquals(OccurrenceState.POSTING, first.dispatch.occurrence.state)
        assertEquals(first, recovered)
        assertTrue(runtime.markNotificationPosted(occurrence.occurrenceId))
        assertTrue(runtime.markNotificationPosted(occurrence.occurrenceId))

        clocks.wallTimeUtcMillis = 5_000
        assertFalse(runtime.markNotificationPosted(occurrence.occurrenceId))
        assertEquals(
            OccurrenceState.EXPIRED,
            runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
        )
    }

    @Test
    fun dedicatedExpiryCheckExpiresPostingPostedAndOpenedSurveyStates() = runTest {
        val store = InMemoryStudyStore()
        val clocks = MutableClocks(1_000)
        val runtime = ExperimentRuntime(
            configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        start(runtime)
        val occurrences = listOf("d", "e", "f").mapIndexed { index, prefix ->
            surveyOccurrence(prefix, expiresAtUtcMillis = 5_000 + index * 1_000L).also {
                runtime.ensureOccurrence(it)
            }
        }
        runtime.claimOccurrenceIfDue(occurrences[0].occurrenceId)
        runtime.claimOccurrenceIfDue(occurrences[1].occurrenceId)
        runtime.markNotificationPosted(occurrences[1].occurrenceId)
        runtime.claimOccurrenceIfDue(occurrences[2].occurrenceId)
        runtime.markNotificationPosted(occurrences[2].occurrenceId)
        runtime.openOccurrence(occurrences[2].occurrenceId)

        clocks.wallTimeUtcMillis = 10_000
        occurrences.forEach { occurrence ->
            assertEquals(OccurrenceExpiryResult.Expired, runtime.expireOccurrenceIfDue(occurrence.occurrenceId))
            assertEquals(
                OccurrenceState.EXPIRED,
                runtime.snapshot.value.metadata?.occurrences?.get(occurrence.occurrenceId)?.state,
            )
        }
        assertEquals(3, store.events.count { it.payloadType == "SURVEY_EXPIRED" })
        assertEquals(OccurrenceExpiryResult.Missing, runtime.expireOccurrenceIfDue("0".repeat(64)))
    }

    @Test
    fun pausedFinishedAndWithdrawnStudiesRejectEveryInterventionMutation() = runTest {
        val lifecycleCases = listOf<Pair<String, suspend (ExperimentRuntime) -> CommandResult>>(
            "pause" to { it.pause() },
            "finish" to { it.finishEarly() },
            "withdraw" to { it.withdraw() },
        )
        lifecycleCases.forEach { (name, transition) ->
            val store = InMemoryStudyStore()
            val clocks = MutableClocks(1_000)
            val runtime = ExperimentRuntime(
                configuration = configuration(surveys = listOf(survey()), interventions = listOf(surveyIntervention())),
                store = store,
                collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
                clocks = clocks,
                scope = backgroundScope,
                safetyPauseWitness = RecordingSafetyPauseWitness(),
            )
            start(runtime)
            val posted = surveyOccurrence("1", expiresAtUtcMillis = 60_000)
            val opened = surveyOccurrence("2", expiresAtUtcMillis = 60_000)
            val scheduled = surveyOccurrence("3", expiresAtUtcMillis = 60_000)
            val posting = surveyOccurrence("4", expiresAtUtcMillis = 60_000)
            listOf(posted, opened, scheduled, posting).forEach { runtime.ensureOccurrence(it) }
            runtime.claimOccurrenceIfDue(posted.occurrenceId)
            assertTrue(runtime.markNotificationPosted(posted.occurrenceId))
            runtime.claimOccurrenceIfDue(opened.occurrenceId)
            assertTrue(runtime.markNotificationPosted(opened.occurrenceId))
            assertTrue(runtime.openOccurrence(opened.occurrenceId)?.action is SurveyAction)
            runtime.claimOccurrenceIfDue(posting.occurrenceId)
            assertEquals(CommandResult.Success, transition(runtime))
            val eventCount = store.events.size

            assertEquals(OccurrenceClaimResult.InactiveStudy, runtime.claimOccurrenceIfDue(scheduled.occurrenceId))
            assertEquals(OccurrenceExpiryResult.InactiveStudy, runtime.expireOccurrenceIfDue(posted.occurrenceId))
            assertFalse(runtime.markNotificationPosted(posting.occurrenceId))
            assertNull(runtime.openOccurrence(posted.occurrenceId))
            assertEquals(
                SurveySubmissionResult.INVALID,
                runtime.submitSurvey(opened.occurrenceId, validSurveyAnswers()),
            )
            assertEquals("$name must not append intervention events", eventCount, store.events.size)
        }
    }

    @Test
    fun illegalCommandFailsWithoutMutatingDurableState() = runTest {
        val store = InMemoryStudyStore()
        val clocks = FakeClocks()
        val runtime = ExperimentRuntime(
            configuration = configuration(),
            store = store,
            collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
            clocks = clocks,
            scope = backgroundScope,
            safetyPauseWitness = RecordingSafetyPauseWitness(),
        )
        runtime.initialize()

        assertEquals(CommandResult.Failed("COMMAND_REJECTED"), runtime.start(emptySet()))

        assertEquals(ExperimentState.IMPORTED, runtime.snapshot.value.metadata?.state)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun exportSnapshotIsRepeatableInEveryDataBearingStateAndNeverMutatesState() = runTest {
        val exportable = listOf(
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN,
        )
        exportable.forEach { state ->
            val store = InMemoryStudyStore(
                StudyMetadata.initial(EXPERIMENT_ID, CONFIGURATION_ID).copy(state = state),
            )
            val clocks = FakeClocks()
            val runtime = ExperimentRuntime(
                configuration = configuration(),
                store = store,
                collectorRegistry = CollectorRegistry(listOf(FakeCollectorPlugin(clocks))),
                clocks = clocks,
                scope = backgroundScope,
                safetyPauseWitness = RecordingSafetyPauseWitness(),
            )
            assertEquals(CommandResult.Success, runtime.initialize())

            assertEquals(state, runtime.metadataForExport().state)
            assertEquals(state, runtime.metadataForExport().state)
            assertEquals(state, runtime.snapshot.value.metadata?.state)
        }
    }

    private class InMemoryStudyStore(
        initial: StudyMetadata? = null,
    ) : StudyStore {
        var metadata: StudyMetadata? = initial
        val events = mutableListOf<RecordedEvent>()
        var saveCount = 0
        var usedBytes = 0L
        var quotaBytes = 16_777_216L
        var appendFailure: Exception? = null
        var recoverNextAppendFailClosed = false
        var pendingRecoveredAppend: StudyMetadata? = null
        var saveAfterCommitFailure: Throwable? = null
        var appendEntered: CompletableDeferred<Unit>? = null
        var releaseAppend: CompletableDeferred<Unit>? = null
        val evictionTargets = mutableListOf<Long>()

        override suspend fun storageUsage() = StorageUsage(usedBytes, quotaBytes)

        override suspend fun evictThrough(metadata: StudyMetadata, targetBytes: Long): StudyMetadata {
            evictionTargets += targetBytes
            // Mirrors the real store: only delivered events go, the floor lands on the first
            // survivor, and the newest event survives regardless, standing in for the segment
            // still being appended to. Nothing is held back for a collector's most recent event —
            // lastEvents is persisted in the metadata rather than rebuilt from surviving frames.
            val newest = events.maxOfOrNull { it.sequenceNumber } ?: Long.MAX_VALUE
            val floor = minOf(metadata.uploadedThroughSequence + 1, newest)
            if (floor <= metadata.retainedFromSequence) return metadata
            events.removeAll { it.sequenceNumber < floor }
            usedBytes = 0
            return metadata.copy(retainedFromSequence = floor).also { this.metadata = it }
        }

        override suspend fun loadMetadata(): StudyMetadata? = metadata

        override suspend fun initialize(metadata: StudyMetadata) {
            check(this.metadata == null)
            this.metadata = metadata
            saveCount += 1
        }

        override suspend fun saveMetadata(metadata: StudyMetadata) {
            this.metadata = metadata
            saveCount += 1
            saveAfterCommitFailure?.let { failure ->
                saveAfterCommitFailure = null
                throw failure
            }
        }

        override suspend fun appendEvent(event: RecordedEvent) {
            val current = requireNotNull(metadata)
            require(event.sequenceNumber == current.nextSequenceNumber)
            events += event
            metadata = current.copy(
                eventCount = event.sequenceNumber,
                nextSequenceNumber = event.sequenceNumber + 1,
                lastEvents = current.lastEvents + (event.collectorId to event),
            )
            saveCount += 1
        }

        override suspend fun appendEventAtomically(
            event: RecordedEvent,
            metadata: StudyMetadata,
            failureTime: ResearchTime,
        ) {
            appendEntered?.complete(Unit)
            releaseAppend?.await()
            appendFailure?.let { throw it }
            require(event.sequenceNumber == requireNotNull(this.metadata).nextSequenceNumber)
            if (recoverNextAppendFailClosed) {
                recoverNextAppendFailClosed = false
                events += event
                val recovered = ExperimentStateMachine().transition(
                    metadata,
                    ExperimentState.PAUSED,
                    TransitionReason.STORAGE_FAILURE,
                    failureTime,
                )
                this.metadata = recovered
                pendingRecoveredAppend = recovered
                throw StudyStoreMutationFailedClosed(
                    recovered,
                    IOException("injected acknowledged append failure"),
                )
            }
            events += event
            this.metadata = metadata
            saveCount += 1
        }

        override suspend fun resolvePendingAppendFailure(reason: TransitionReason): StudyMetadata? {
            val pending = pendingRecoveredAppend ?: return null
            val transition = requireNotNull(pending.transitions.lastOrNull())
            val resolved = pending.copy(
                transitions = pending.transitions.dropLast(1) + transition.copy(reason = reason),
            )
            metadata = resolved
            pendingRecoveredAppend = null
            return resolved
        }

        override suspend fun readEvents(
            fromSequenceInclusive: Long,
            upToSequenceInclusive: Long,
            consume: (RecordedEvent) -> Unit,
        ) {
            events.asSequence()
                .takeWhile { it.sequenceNumber <= upToSequenceInclusive }
                .filter { it.sequenceNumber >= fromSequenceInclusive }
                .forEach(consume)
        }

        override suspend fun clear() {
            metadata = null
            events.clear()
        }
    }

    private class RecordingSafetyPauseWitness : SafetyPauseWitness {
        val persistedReasons = mutableListOf<SafetyPauseReason>()
        var failure: Throwable? = null

        override suspend fun persist(reason: SafetyPauseReason) {
            failure?.let { throw it }
            persistedReasons += reason
        }
    }

    private class FakeClocks : ResearchClocks {
        private var tick = 1L

        override fun now(): ResearchTime = ResearchTime(
            wallTimeUtcMillis = tick * 1_000,
            elapsedRealtimeNanos = tick++ * 1_000,
            bootSessionId = "boot-test",
        )
    }

    private class ControlledClocks(
        var current: ResearchTime,
    ) : ResearchClocks {
        override fun now(): ResearchTime = current
    }

    private class MutableClocks(
        var wallTimeUtcMillis: Long,
    ) : ResearchClocks {
        private var elapsedRealtimeNanos = 0L

        override fun now(): ResearchTime = ResearchTime(
            wallTimeUtcMillis = wallTimeUtcMillis,
            elapsedRealtimeNanos = ++elapsedRealtimeNanos,
            bootSessionId = "boot-test",
        )
    }

    private class FakeCollectorPlugin(
        private val clocks: ResearchClocks,
        accessKinds: Set<AccessKind> = emptySet(),
        collectorId: String = AppLifecycleConfiguration.ID,
    ) : CollectorPlugin {
        override val descriptor = CollectorDescriptor(
            id = collectorId,
            displayName = "Fake collector",
            privacyClass = PrivacyClass.SENSITIVE,
            eventContract = CollectorEventContract(
                payloadSchemaVersion = 1,
                maximumEncodedEventBytes = 512,
                payloads = listOf("ACTIVITY_RESUMED", "ACTIVITY_STOPPED", "ACTIVITY_STARTED")
                    .associateWith {
                        EventPayloadContract(
                            mapOf("source" to EventFieldContract(EventFieldType.STRING, required = true)),
                        )
                    },
            ),
            accessKinds = accessKinds,
        )
        lateinit var context: CollectorContext
        lateinit var collector: FakeCollector

        override fun create(
            configuration: CollectorConfiguration,
            context: CollectorContext,
        ): Collector {
            require(configuration.id == descriptor.id)
            this.context = context
            collector = FakeCollector()
            return collector
        }

        fun captureToken(): AdmissionToken? = context.eventSink.captureToken()

        suspend fun emit(
            type: String,
            collectorId: String = descriptor.id,
            schemaVersion: Int = descriptor.payloadSchemaVersion,
            fields: Map<String, String> = mapOf("source" to "test"),
        ): EmitResult {
            val token = captureToken() ?: return EmitResult.RejectedByAdmissionGate
            return emitWithToken(token, type, collectorId, schemaVersion, fields)
        }

        suspend fun emitWithToken(
            token: AdmissionToken,
            type: String,
            collectorId: String = descriptor.id,
            schemaVersion: Int = descriptor.payloadSchemaVersion,
            fields: Map<String, String> = mapOf("source" to "test"),
        ): EmitResult {
            return context.eventSink.emit(
                token,
                EventDraft(
                    collectorId = collectorId,
                    payloadSchemaVersion = schemaVersion,
                    observedTime = clocks.now(),
                    payloadType = type,
                    fields = fields,
                ),
            )
        }
    }

    private class FakeCollector : Collector {
        private val mutableHealth = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override val health: StateFlow<CollectorHealth> = mutableHealth
        override var requiresStop = false
            private set
        var startCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        var admissionOpenedCount = 0
        var failNextStartWithOwnedResources = false
        var failNextPauseWithOwnedResources = false
        var failNextResumeWithOwnedResources = false
        var failNextStopWithOwnedResources = false
        var beforePause: suspend () -> Unit = {}
        var beforeStop: suspend () -> Unit = {}
        var afterAdmissionOpened: suspend () -> Unit = {}

        override suspend fun start() {
            startCount += 1
            requiresStop = true
            if (failNextStartWithOwnedResources) {
                failNextStartWithOwnedResources = false
                mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "SOURCE_REGISTRATION_FAILED")
                error("Start left collector resources requiring cleanup")
            }
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        }

        override suspend fun onAdmissionOpened() {
            admissionOpenedCount += 1
            afterAdmissionOpened()
        }

        override suspend fun pause() {
            pauseCount += 1
            beforePause()
            if (failNextPauseWithOwnedResources) {
                failNextPauseWithOwnedResources = false
                error("Pause left collector resources requiring cleanup")
            }
            mutableHealth.value = CollectorHealth(CollectorStatus.PAUSED)
        }

        override suspend fun resume() {
            resumeCount += 1
            if (failNextResumeWithOwnedResources) {
                failNextResumeWithOwnedResources = false
                error("Resume did not establish a usable source")
            }
            mutableHealth.value = CollectorHealth(CollectorStatus.ACTIVE)
        }

        override suspend fun stop() {
            stopCount += 1
            beforeStop()
            if (failNextStopWithOwnedResources) {
                failNextStopWithOwnedResources = false
                mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "SOURCE_UNREGISTRATION_FAILED")
                error("Stop left collector resources requiring cleanup")
            }
            requiresStop = false
            mutableHealth.value = CollectorHealth(CollectorStatus.STOPPED)
        }

        fun reportFailedWithOwnedResources() {
            check(requiresStop) { "Collector must own a live source before reporting failure" }
            mutableHealth.value = CollectorHealth(CollectorStatus.FAILED, "SOURCE_FAILED")
        }
    }

    private companion object {
        const val EXPERIMENT_ID = "runtime-test"
        const val CONFIGURATION_ID = "runtime-config"
        const val NANOS_PER_HOUR = 60L * 60L * 1_000_000_000L

        fun configuration(
            collectors: List<CollectorConfiguration> = listOf(AppLifecycleConfiguration(required = true)),
            surveys: List<SurveyDefinition> = emptyList(),
            interventions: List<InterventionConfiguration> = emptyList(),
        ) = StudyConfiguration(
            schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
            experimentId = EXPERIMENT_ID,
            configurationId = CONFIGURATION_ID,
            assignedParticipantId = null,
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = StudyConfiguration.ANDROID_PLATFORM,
            minimumClientVersion = 1,
            title = "Runtime test",
            researcherName = "Test researcher",
            researcherContact = "test@example.invalid",
            purpose = "Runtime test purpose",
            durationHours = 1,
            consentDocumentVersion = "test-1",
            consentSummary = "Test consent",
            collectors = collectors,
            surveys = surveys,
            interventions = interventions,
            maximumLocalBytes = 16_777_216,
            signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
            export = ExportConfiguration(
                researcherKeyId = "test-key",
                hpkePublicKey = RAW_PUBLIC_KEY,
            ),
            upload = null,
        )

        suspend fun start(
            runtime: ExperimentRuntime,
            availableAccess: Set<AccessKind> = emptySet(),
        ) {
            assertEquals(CommandResult.Success, runtime.initialize())
            assertEquals(CommandResult.Success, runtime.reviewStudy())
            assertEquals(CommandResult.Success, runtime.acceptConsent())
            assertEquals(CommandResult.Success, runtime.completeAccessSetup(availableAccess))
            assertEquals(CommandResult.Success, runtime.start(availableAccess))
        }

        fun survey() = SurveyDefinition(
            "daily-survey",
            LocalizedText("Daily survey", mapOf("zh-TW" to "每日問卷")),
            LocalizedText("Answer four questions."),
            listOf(
                ShortTextQuestion("daily-note", LocalizedText("How was today?"), false, 40),
                ScaleQuestion("mood-scale", LocalizedText("Mood"), true, 1, 5, LocalizedText("Low"), LocalizedText("High")),
                SingleChoiceQuestion(
                    "primary-place",
                    LocalizedText("Where were you?"),
                    true,
                    listOf(ChoiceOption("place-home", LocalizedText("Home")), ChoiceOption("place-work", LocalizedText("Work"))),
                ),
                MultipleChoiceQuestion(
                    "symptoms",
                    LocalizedText("Symptoms"),
                    true,
                    listOf(
                        ChoiceOption("symptom-tired", LocalizedText("Tired")),
                        ChoiceOption("symptom-headache", LocalizedText("Headache")),
                        ChoiceOption("symptom-none", LocalizedText("None")),
                    ),
                    1,
                    2,
                ),
            ),
        )

        fun surveyIntervention() = InterventionConfiguration(
            "survey-notice",
            SurveyAction("Daily survey", "Your survey is ready.", "daily-survey"),
            listOf(InterventionTrigger("after-minute", OneTimeSchedule(1, RelativeClock.CALENDAR_TIME), 60)),
        )

        fun validSurveyAnswers(): Map<String, SurveyAnswer> = mapOf(
            "daily-note" to SurveyAnswer.Text("complete"),
            "mood-scale" to SurveyAnswer.Integer(4),
            "primary-place" to SurveyAnswer.Choices(listOf("place-home")),
            "symptoms" to SurveyAnswer.Choices(listOf("symptom-none")),
        )

        fun surveyOccurrence(
            prefix: String,
            scheduledAtUtcMillis: Long = 100,
            expiresAtUtcMillis: Long,
        ) = InterventionOccurrence(
            occurrenceId = prefix.repeat(64),
            interventionId = "survey-notice",
            triggerId = "after-minute",
            scheduleKey = "relative:$prefix",
            scheduledFor = ResearchTime(scheduledAtUtcMillis, scheduledAtUtcMillis, "boot-test"),
            expiresAtUtcMillis = expiresAtUtcMillis,
            state = OccurrenceState.SCHEDULED,
        )
    }
}

private const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
