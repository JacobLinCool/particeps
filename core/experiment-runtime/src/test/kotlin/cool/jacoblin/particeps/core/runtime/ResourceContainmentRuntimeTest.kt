package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.AutomationCompiler
import cool.jacoblin.particeps.core.automation.CompilationResult
import cool.jacoblin.particeps.core.automation.DeliveryMode
import cool.jacoblin.particeps.core.automation.EventClockSupport
import cool.jacoblin.particeps.core.automation.EventConditionKind
import cool.jacoblin.particeps.core.automation.EventContractRegistry
import cool.jacoblin.particeps.core.automation.EventRateBound
import cool.jacoblin.particeps.core.automation.EventSourceKind
import cool.jacoblin.particeps.core.automation.EventTypeContract
import cool.jacoblin.particeps.core.automation.FieldContract
import cool.jacoblin.particeps.core.automation.ScalarType
import cool.jacoblin.particeps.core.automation.TimerProductionResult
import cool.jacoblin.particeps.core.automation.TriggerScope
import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.DeclaredResource
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.FieldPredicate
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.ResourceConditionCase
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
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
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.SuspendReceipt
import cool.jacoblin.particeps.core.resource.VerifyReceipt
import java.math.BigInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceContainmentRuntimeTest {
    @Test
    fun forgedSecondApplyReceiptCleansAttemptedHostsInReverseThenReleasesUntouchedOldHost() = runTest {
        val releaseLog = mutableListOf<String>()
        val fixture = fixture(this, releaseLog = releaseLog)
        start(fixture.runtime)
        val oldToken = requireNotNull(fixture.runtime.captureToken())
        fixture.second.forgeNextApplyReceipt = true

        assertTrue(fixture.runtime.emitBatch(oldToken, triggerBatch(fixture.clock.now())) is EmitBatchResult.Accepted)
        runCurrent()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, fixture.runtime.snapshot.value.state)
        assertFalse(fixture.runtime.snapshot.value.admissionOpen)
        assertNull(fixture.runtime.captureToken())
        assertEquals(
            listOf(
                "actuator:traffic-b.v1:slow",
                "actuator:traffic-a.v1:slow",
                "collector:battery_state.v1:continuous",
            ),
            releaseLog,
        )
        assertTrue(fixture.actuators.all { it.health().status == ResourceHealthStatus.INACTIVE })
        assertTrue(cleanupComponents(fixture.store).isEmpty())
        assertTrue(resourceStates(fixture.store).all { it.status == AppliedResourceStatus.INACTIVE })

        val safetyCommit = fixture.store.commits.single { it.inputKind == EngineInputKind.SAFETY_FAILURE }
        val pausedResources = safetyCommit.mutations
            .filter { it.key.kind == RuntimeComponentKind.RESOURCE }
            .map { RuntimeComponentCodec.decodeResource(requireNotNull(it.canonicalValue)) }
            .associateBy(AppliedResourceState::key)
        assertEquals(AppliedResourceStatus.INACTIVE, pausedResources.getValue(FIRST_KEY).status)
        assertEquals(AppliedResourceStatus.INACTIVE, pausedResources.getValue(SECOND_KEY).status)
        assertEquals(AppliedResourceStatus.APPLIED, pausedResources.getValue(SOURCE_KEY).status)
        assertEquals(EngineInputKind.RESOURCE_RESULT, fixture.store.commits.last().inputKind)
    }

    @Test
    fun invalidLiveCleanupReceiptKeepsTrustedAppliedTruthUntilRecoveryCompletesSecondCommit() = runTest {
        val store = MemoryStore()
        val releaseLog = mutableListOf<String>()
        val first = fixture(this, store, releaseLog)
        start(first.runtime)
        val oldToken = requireNotNull(first.runtime.captureToken())
        first.second.forgeNextApplyReceipt = true
        first.second.forgeEveryReleaseReceipt = true

        assertTrue(first.runtime.emitBatch(oldToken, triggerBatch(first.clock.now())) is EmitBatchResult.Accepted)
        runCurrent()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, first.runtime.snapshot.value.state)
        assertFalse(first.runtime.snapshot.value.admissionOpen)
        assertEquals(EngineInputKind.SAFETY_FAILURE, store.commits.last().inputKind)
        assertEquals(setOf(SECOND_KEY), cleanupComponents(store).map(DurableResourceCleanup::key).toSet())
        val paused = resourceStates(store).associateBy(AppliedResourceState::key)
        assertEquals(AppliedResourceStatus.INACTIVE, paused.getValue(FIRST_KEY).status)
        assertEquals(AppliedResourceStatus.APPLIED, paused.getValue(SECOND_KEY).status)
        assertEquals(AppliedResourceStatus.APPLIED, paused.getValue(SOURCE_KEY).status)
        assertEquals(ResourceHealthStatus.INACTIVE, first.second.health().status)
        assertEquals(2, releaseLog.count { it == "actuator:traffic-b.v1:slow" })
        assertEquals(1, releaseLog.count { it == "collector:battery_state.v1:continuous" })
        assertFalse(store.commits.dropWhile { it.inputKind != EngineInputKind.SAFETY_FAILURE }
            .drop(1).any { it.inputKind == EngineInputKind.RESOURCE_RESULT })
        first.runtime.close()

        val recovered = fixture(this, store, mutableListOf())
        val initialized = recovered.runtime.initialize()

        assertTrue(initialized is RuntimeInitializationResult.Ready)
        assertEquals(ExperimentState.PAUSED, recovered.runtime.snapshot.value.state)
        assertTrue(cleanupComponents(store).isEmpty())
        assertTrue(resourceStates(store).all { it.status == AppliedResourceStatus.INACTIVE })
        assertEquals(EngineInputKind.RESOURCE_RESULT, store.commits.last().inputKind)
    }

    @Test
    fun sameIdentityReprepareCannotUseNotAppliedCleanupEvidence() = runTest {
        val store = MemoryStore()
        val first = fixture(this, store, secondChangesOnTrigger = false)
        start(first.runtime)
        val oldToken = requireNotNull(first.runtime.captureToken())
        first.second.forgeNextApplyReceipt = true
        first.second.notAppliedReleaseAttempts = 2

        assertTrue(first.runtime.emitBatch(oldToken, triggerBatch(first.clock.now())) is EmitBatchResult.Accepted)
        runCurrent()
        runCurrent()

        assertEquals(ExperimentState.PAUSED, first.runtime.snapshot.value.state)
        assertEquals(setOf(SECOND_KEY), cleanupComponents(store).map(DurableResourceCleanup::key).toSet())
        val paused = resourceStates(store).associateBy(AppliedResourceState::key)
        assertEquals(AppliedResourceStatus.APPLIED, paused.getValue(SECOND_KEY).status)
        assertEquals(ResourceHealthStatus.INACTIVE, first.second.health().status)
        assertFalse(store.commits.dropWhile { it.inputKind != EngineInputKind.SAFETY_FAILURE }
            .drop(1).any { it.inputKind == EngineInputKind.RESOURCE_RESULT })
        first.runtime.close()

        val recovered = fixture(this, store, secondChangesOnTrigger = false)
        assertTrue(recovered.runtime.initialize() is RuntimeInitializationResult.Ready)
        assertTrue(cleanupComponents(store).isEmpty())
        assertTrue(resourceStates(store).all { it.status == AppliedResourceStatus.INACTIVE })
        assertEquals(EngineInputKind.RESOURCE_RESULT, store.commits.last().inputKind)
    }

    private suspend fun start(runtime: ExperimentRuntime) {
        assertTrue(runtime.initialize() is RuntimeInitializationResult.Ready)
        assertEquals(RuntimeCommandResult.Success, runtime.markConfigurationVerified())
        assertEquals(RuntimeCommandResult.Success, runtime.beginConsentReview())
        assertEquals(RuntimeCommandResult.Success, runtime.acceptConsent())
        assertEquals(RuntimeCommandResult.Success, runtime.markReady())
        assertEquals(RuntimeCommandResult.Success, runtime.start())
    }

    private fun fixture(
        scope: TestScope,
        store: MemoryStore = MemoryStore(),
        releaseLog: MutableList<String> = mutableListOf(),
        secondChangesOnTrigger: Boolean = true,
    ): Fixture {
        val first = HostileActuator(FIRST_KEY, releaseLog)
        val second = HostileActuator(SECOND_KEY, releaseLog)
        val source = HostileActuator(SOURCE_KEY, releaseLog)
        val clock = FakeClock(
            store.runtime?.clockCheckpoint?.anchor?.elapsedRealtimeNanos?.let(Math::incrementExact)
                ?: 1_000_000_000L,
        )
        val program = compileProgram(secondChangesOnTrigger)
        val runtime = ExperimentRuntime(
            study = RuntimeStudyIdentity("receipt-study", "receipt-config", CONFIG_DIGEST, 3_600),
            store = store,
            program = program,
            surveyInterventionIds = emptySet(),
            resourceHosts = listOf(
                RuntimeResourceHost(FIRST_KEY, true, PROFILES, first),
                RuntimeResourceHost(SECOND_KEY, true, PROFILES, second),
                RuntimeResourceHost(SOURCE_KEY, true, mapOf(CONTINUOUS.id to CONTINUOUS), source),
            ),
            clocks = clock,
            scope = scope.backgroundScope,
            zoneId = { "UTC" },
            timerProducer = RuntimeTimerProducer { TimerProductionResult.Deferred },
            entropy = DeterministicEntropy(),
        )
        return Fixture(runtime, store, first, second, source, clock)
    }

    private fun compileProgram(secondChangesOnTrigger: Boolean) = AutomationCompiler(EventContractRegistry { key ->
        EVENT_CONTRACT.takeIf { it.key == key }
    }).compile(
        AutomationCompilerInput(
            configurationSha256 = CONFIG_DIGEST,
            studyDurationSeconds = 3_600,
            resources = listOf(
                DeclaredResource(
                    FIRST_KEY,
                    true,
                    PROFILES.mapValues { it.value.expectedSha256.value },
                ),
                DeclaredResource(
                    SECOND_KEY,
                    true,
                    PROFILES.mapValues { it.value.expectedSha256.value },
                ),
                DeclaredResource(
                    SOURCE_KEY,
                    true,
                    mapOf(CONTINUOUS.id to CONTINUOUS.expectedSha256.value),
                ),
            ),
            interventions = emptyList(),
            automations = listOf(
                resourceBinding("first-binding", FIRST_KEY),
                if (secondChangesOnTrigger) {
                    resourceBinding("second-binding", SECOND_KEY)
                } else {
                    ResourceBindingAutomation(
                        "second-binding",
                        SECOND_KEY,
                        listOf(ResourceConditionCase(StateCondition.StudySessionActive, BASELINE.id)),
                        BASELINE.id,
                    )
                },
                ResourceBindingAutomation(
                    "source-binding",
                    SOURCE_KEY,
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, CONTINUOUS.id)),
                    CONTINUOUS.id,
                ),
            ),
        ),
    ).let { result ->
        (result as? CompilationResult.Success)?.program
            ?: error("Compilation failed: ${(result as CompilationResult.Failure).issues}")
    }

    private fun resourceBinding(id: String, key: ResourceKey) = ResourceBindingAutomation(
        id,
        key,
        listOf(
            ResourceConditionCase(
                StateCondition.EventLatch(
                    setWhen = listOf(
                        EventMatcher(
                            EVENT_TYPE,
                            listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "42")),
                        ),
                    ),
                    resetWhen = listOf(
                        EventMatcher(
                            EVENT_TYPE,
                            listOf(FieldPredicate("percentage", FieldOperator.EQ, value = "43")),
                        ),
                    ),
                ),
                SLOW.id,
            ),
        ),
        BASELINE.id,
    )

    private fun triggerBatch(now: ResearchTime) = SourceEventBatch(
        sourceId = SOURCE_ID,
        schemaVersion = 1,
        resourceGeneration = 1,
        producerOrdinal = 0,
        events = listOf(
            EventDraft(
                EVENT_TYPE,
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

    private fun cleanupComponents(store: MemoryStore): List<DurableResourceCleanup> =
        requireNotNull(store.runtime).components
            .filterKeys { it.kind == RuntimeComponentKind.RESOURCE_CLEANUP }
            .values.map(RuntimeComponentCodec::decodeResourceCleanup)

    private fun resourceStates(store: MemoryStore): List<AppliedResourceState> =
        requireNotNull(store.runtime).components
            .filterKeys { it.kind == RuntimeComponentKind.RESOURCE }
            .values.map(RuntimeComponentCodec::decodeResource)

    private data class Fixture(
        val runtime: ExperimentRuntime,
        val store: MemoryStore,
        val first: HostileActuator,
        val second: HostileActuator,
        val source: HostileActuator,
        val clock: FakeClock,
    ) {
        val actuators: List<HostileActuator> get() = listOf(first, second, source)
    }

    private class HostileActuator(
        override val key: ResourceKey,
        private val releaseLog: MutableList<String>,
    ) : StatefulResourceActuator {
        override val supportsHotProfileSwap = true
        var forgeNextApplyReceipt = false
        var forgeEveryReleaseReceipt = false
        var notAppliedReleaseAttempts = 0
        private var current: DesiredResourceState? = null
        private var prepared: DesiredResourceState? = null
        private var resourceHealth = inactiveHealth(key)

        override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) = Unit

        override suspend fun prepare(desired: DesiredResourceState, requestId: String): PrepareReceipt {
            prepared = desired
            resourceHealth = health(desired, ResourceHealthStatus.PREPARED, applied = false)
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
            require(current == desired)
            resourceHealth = health(desired, ResourceHealthStatus.SUSPENDED, applied = true)
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
        ) = FlushReceipt(
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
            require(prepared == desired)
            current = desired
            prepared = null
            resourceHealth = health(desired, ResourceHealthStatus.APPLIED, applied = true)
            val forged = forgeNextApplyReceipt
            forgeNextApplyReceipt = false
            return ApplyReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                if (forged) FORGED_DIGEST else desired.profile?.expectedSha256,
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
            require(current == desired)
            resourceHealth = health(desired, ResourceHealthStatus.APPLIED, applied = true)
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
            require(current == desired)
            return resourceHealth
        }

        override suspend fun release(desired: DesiredResourceState): ReleaseReceipt {
            releaseLog += "${key.kind.name.lowercase()}:${key.id}:${desired.profile?.id ?: "inactive"}"
            current = null
            prepared = null
            resourceHealth = inactiveHealth(key)
            if (notAppliedReleaseAttempts > 0) {
                notAppliedReleaseAttempts--
                return ReleaseReceipt(
                    key,
                    desired.generation,
                    desired.profile?.id,
                    desired.profile?.expectedSha256,
                    null,
                    ReleaseEvidence.NOT_APPLIED,
                    released = true,
                )
            }
            val digest = if (forgeEveryReleaseReceipt) FORGED_DIGEST else desired.profile?.expectedSha256
            return ReleaseReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                digest,
                digest,
                if (desired.profile == null) ReleaseEvidence.INACTIVE else ReleaseEvidence.APPLIED,
                released = true,
            )
        }

        override fun health(): ResourceHealth = resourceHealth
    }

    private class FakeClock(initialElapsedRealtimeNanos: Long = 1_000_000_000L) : ResearchClocks {
        private var elapsed = initialElapsedRealtimeNanos
        override fun now(): ResearchTime = ResearchTime(
            wallTimeUtcMillis = 1_800_000_000_000L + elapsed / 1_000_000,
            elapsedRealtimeNanos = elapsed,
            bootSessionId = "boot-containment",
        ).also { elapsed += 1_000_000L }
    }

    private class DeterministicEntropy : RuntimeEntropySource {
        private var epoch = 0
        override fun next(kind: RuntimeEntropyKind): String = when (kind) {
            RuntimeEntropyKind.PARTICIPANT_INSTANCE_UUID -> "123e4567-e89b-42d3-a456-426614174001"
            RuntimeEntropyKind.ACTIVITY_TOKEN_KEY -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            RuntimeEntropyKind.CONDITION_EPOCH_UUID -> if (epoch++ == 0) {
                "123e4567-e89b-42d3-a456-426614174010"
            } else {
                "123e4567-e89b-42d3-a456-426614174011"
            }
        }
    }

    private class MemoryStore : StudyStore {
        var runtime: RuntimeDocument? = null
        var pending: PendingEngineInput? = null
        val commits = mutableListOf<EngineCommit>()

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
        }

        override suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput) {
            check(pending?.encodedSha256 == expectedSha256)
            pending = input
        }

        override suspend fun loadPendingInput(): PendingEngineInput? = pending

        override suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument) {
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
        val SOURCE_ID = EventSourceId("battery_state.v1")
        val EVENT_TYPE = EventTypeKey(SOURCE_ID, 1, "BATTERY_STATE")
        val FIRST_KEY = ResourceKey(ResourceKind.ACTUATOR, "traffic-a.v1")
        val SECOND_KEY = ResourceKey(ResourceKind.ACTUATOR, "traffic-b.v1")
        val SOURCE_KEY = ResourceKey(ResourceKind.COLLECTOR, SOURCE_ID.value)
        val BASELINE = SignedResourceProfile("baseline", "{\"id\":\"baseline\"}".toByteArray())
        val SLOW = SignedResourceProfile("slow", "{\"id\":\"slow\"}".toByteArray())
        val CONTINUOUS = SignedResourceProfile("continuous", "{\"mode\":\"continuous\"}".toByteArray())
        val PROFILES = mapOf(BASELINE.id to BASELINE, SLOW.id to SLOW)
        val FORGED_DIGEST = Sha256Digest("f".repeat(64))
        const val CONFIG_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val EVENT_CONTRACT = EventTypeContract(
            key = EVENT_TYPE,
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

        fun inactiveHealth(key: ResourceKey) = ResourceHealth(
            key,
            ResourceHealthStatus.INACTIVE,
            null,
            null,
            null,
            null,
            null,
        )

        fun health(
            desired: DesiredResourceState,
            status: ResourceHealthStatus,
            applied: Boolean,
        ) = ResourceHealth(
            desired.key,
            status,
            desired.generation,
            desired.profile?.id,
            desired.profile?.expectedSha256,
            desired.profile?.expectedSha256.takeIf { applied },
            null,
        )
    }
}
