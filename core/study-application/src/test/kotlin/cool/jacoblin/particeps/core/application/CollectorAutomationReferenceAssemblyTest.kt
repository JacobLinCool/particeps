package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorRegistry
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.definition.AccelerometerV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.Aggregate
import cool.jacoblin.particeps.core.definition.BatteryStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.EvaluationClock
import cool.jacoblin.particeps.core.definition.EventMatcher
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.FieldOperator
import cool.jacoblin.particeps.core.definition.FieldPredicate
import cool.jacoblin.particeps.core.definition.GyroscopeV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.NamedCollectorProfile
import cool.jacoblin.particeps.core.definition.NumericComparison
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.ResourceConditionCase
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.PendingEngineInput
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.StorageUsage
import cool.jacoblin.particeps.core.model.StudyReadSnapshot
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.runtime.RuntimeCommandResult
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectorAutomationReferenceAssemblyTest {
    @Test
    fun onlyTheSensorTheSignedAutomationReferencesRequiresPromptCommits() = runTest {
        val gyroscopeSample = EventTypeKey(EventSourceId(GyroscopeV1ProfileConfiguration.SOURCE_ID), 1, "GYROSCOPE_SAMPLE")
        val latch = StateCondition.EventLatch(
            setWhen = listOf(
                EventMatcher(gyroscopeSample, listOf(FieldPredicate("x_radians_per_second", FieldOperator.GT, value = "3.0"))),
            ),
            resetWhen = listOf(
                EventMatcher(gyroscopeSample, listOf(FieldPredicate("x_radians_per_second", FieldOperator.LT, value = "0.5"))),
            ),
        )

        // The accelerometer's binding latches on gyroscope samples; nothing references the accelerometer.
        assertEquals(mapOf(GYROSCOPE to true, ACCELEROMETER to false), promptCommitFlags(latch, withBattery = false))
    }

    @Test
    fun everySourceOfAProgramWithWindowStateRequiresPromptCommits() = runTest {
        val lowBattery = StateCondition.WindowThreshold(
            EventMatcher(
                EventTypeKey(EventSourceId(BatteryStateV1ProfileConfiguration.SOURCE_ID), 1, "BATTERY_STATE"),
                listOf(FieldPredicate("percentage", FieldOperator.LT, value = "20")),
            ),
            600,
            EvaluationClock.OBSERVED_RESEARCH_TIME,
            Aggregate.Count,
            NumericComparison(FieldOperator.GTE, "3"),
        )

        // No matcher names either sensor, but the battery window requires every event in time order.
        assertEquals(
            mapOf(GYROSCOPE to true, ACCELEROMETER to true, BATTERY to true),
            promptCommitFlags(lowBattery, withBattery = true),
        )
    }

    /** Assembles and starts a study whose accelerometer binding also has [condition]. */
    private suspend fun TestScope.promptCommitFlags(condition: StateCondition, withBattery: Boolean): Map<String, Boolean> {
        val plugins = listOfNotNull(
            RecordingPlugin(AccelerometerV1ProfileConfiguration.SOURCE_ID),
            RecordingPlugin(BatteryStateV1ProfileConfiguration.SOURCE_ID).takeIf { withBattery },
            RecordingPlugin(GyroscopeV1ProfileConfiguration.SOURCE_ID),
        )
        val assembly = EventDrivenRuntimeAssemblyFactory(
            collectorRegistry = CollectorRegistry(plugins),
            clocks = IncrementingClocks(),
            scope = backgroundScope,
            zoneId = { "UTC" },
        ).create(verified(configuration(condition, withBattery)), MemoryStore())
        val runtime = assembly.runtime

        runtime.initialize()
        assertEquals(RuntimeCommandResult.Success, runtime.markConfigurationVerified())
        assertEquals(RuntimeCommandResult.Success, runtime.beginConsentReview())
        assertEquals(RuntimeCommandResult.Success, runtime.acceptConsent())
        assertEquals(RuntimeCommandResult.Success, runtime.markReady())
        assertEquals(RuntimeCommandResult.Success, runtime.start())
        assembly.close()
        return plugins.associate { plugin ->
            plugin.descriptor.id to plugin.contexts.map(CollectorContext::requiresPromptCommits).single()
        }
    }

    private fun configuration(accelerometerCondition: StateCondition, withBattery: Boolean): StudyConfiguration {
        return StudyConfiguration(
            schemaVersion = 1,
            experimentId = "sensor-study",
            configurationId = "sensor-config",
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = StudyConfiguration.ANDROID_PLATFORM,
            minimumClientVersion = 1,
            title = "Sensor study",
            researcherName = "Researcher",
            researcherContact = "researcher@example.invalid",
            purpose = "Verify which collectors automation references.",
            durationHours = 24,
            consentDocumentVersion = "v1",
            consentSummary = "Consent.",
            assignedParticipantId = "participant-1",
            collectors = listOfNotNull(
                CollectorResourceConfiguration(
                    AccelerometerV1ProfileConfiguration.SOURCE_ID,
                    required = true,
                    profiles = listOf(
                        NamedCollectorProfile(
                            "continuous",
                            AccelerometerV1ProfileConfiguration(maximumReportLatencyUs = 0, samplingPeriodUs = 20_000),
                        ),
                    ),
                ),
                CollectorResourceConfiguration(
                    BatteryStateV1ProfileConfiguration.SOURCE_ID,
                    required = true,
                    profiles = listOf(NamedCollectorProfile("continuous", BatteryStateV1ProfileConfiguration())),
                ).takeIf { withBattery },
                CollectorResourceConfiguration(
                    GyroscopeV1ProfileConfiguration.SOURCE_ID,
                    required = true,
                    profiles = listOf(
                        NamedCollectorProfile(
                            "continuous",
                            GyroscopeV1ProfileConfiguration(maximumReportLatencyUs = 0, samplingPeriodUs = 20_000),
                        ),
                    ),
                ),
            ),
            surveys = emptyList(),
            interventions = emptyList(),
            automations = listOfNotNull(
                ResourceBindingAutomation(
                    "bind-accelerometer",
                    ResourceKey(ResourceKind.COLLECTOR, AccelerometerV1ProfileConfiguration.SOURCE_ID),
                    listOf(
                        ResourceConditionCase(StateCondition.StudySessionActive, "continuous"),
                        ResourceConditionCase(accelerometerCondition, "continuous"),
                    ),
                    "continuous",
                ),
                ResourceBindingAutomation(
                    "bind-battery",
                    ResourceKey(ResourceKind.COLLECTOR, BatteryStateV1ProfileConfiguration.SOURCE_ID),
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                    "continuous",
                ).takeIf { withBattery },
                ResourceBindingAutomation(
                    "bind-gyroscope",
                    ResourceKey(ResourceKind.COLLECTOR, GyroscopeV1ProfileConfiguration.SOURCE_ID),
                    listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                    "continuous",
                ),
            ),
            trafficShaping = TrafficShapingConfiguration.Disabled,
            maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
            signer = SignerIdentity("signer-key", ProtocolBase64Url.encode(ByteArray(32) { 1 })),
            export = ExportConfiguration("export-key", ProtocolBase64Url.encode(ByteArray(32) { 2 })),
            upload = null,
        )
    }

    private fun verified(configuration: StudyConfiguration): VerifiedConfiguration {
        val canonical = StudyConfigurationCodec.encode(configuration)
        return VerifiedConfiguration(
            configuration = configuration,
            canonicalConfigurationBytes = canonical,
            signerKeyId = configuration.signer.keyId,
            signature = ByteArray(64) { 3 },
            configurationSha256 = MessageDigest.getInstance("SHA-256")
                .digest(canonical)
                .joinToString(separator = "") { "%02x".format(it) },
            signerAnchored = true,
        )
    }

    private companion object {
        const val ACCELEROMETER = AccelerometerV1ProfileConfiguration.SOURCE_ID
        const val BATTERY = BatteryStateV1ProfileConfiguration.SOURCE_ID
        const val GYROSCOPE = GyroscopeV1ProfileConfiguration.SOURCE_ID
    }

    private class RecordingPlugin(sourceId: String) : CollectorPlugin {
        val contexts = mutableListOf<CollectorContext>()
        override val descriptor = CollectorDescriptor(
            id = sourceId,
            displayName = sourceId,
            sourceContract = requireNotNull(ProtocolEventSourceRegistry[sourceId]),
            accessKinds = emptySet(),
        )

        override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
            contexts += context
            return IdleCollector()
        }
    }

    private class IdleCollector : Collector {
        private val state = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override val health: StateFlow<CollectorHealth> = state
        override val requiresStop: Boolean get() = state.value.status != CollectorStatus.STOPPED

        override suspend fun start() {
            state.value = CollectorHealth(CollectorStatus.ACTIVE)
        }

        override suspend fun pause() {
            state.value = CollectorHealth(CollectorStatus.PAUSED)
        }

        override suspend fun resume() {
            state.value = CollectorHealth(CollectorStatus.ACTIVE)
        }

        override suspend fun stop() {
            state.value = CollectorHealth(CollectorStatus.STOPPED)
        }
    }

    private class IncrementingClocks : ResearchClocks {
        private var tick = 0L

        override fun now(): ResearchTime {
            tick++
            return ResearchTime(1_800_000_000_000L + tick, 1_000_000_000L + tick * 1_000_000L, "boot-one")
        }

        override fun trustedUtcMillis(): Long = now().wallTimeUtcMillis
    }

    private class MemoryStore : StudyStore {
        private var runtime: RuntimeDocument? = null
        private var pending: PendingEngineInput? = null

        override suspend fun loadRuntime(observeRetained: (EngineCommit) -> Unit): RuntimeDocument? = runtime
        override suspend fun initialize(runtime: RuntimeDocument) {
            this.runtime = runtime
        }
        override suspend fun appendCommit(commit: EngineCommit, successor: RuntimeDocument) {
            runtime = successor
        }
        override suspend fun stagePendingInput(input: PendingEngineInput) {
            pending = input
        }
        override suspend fun replacePendingInput(expectedSha256: String, input: PendingEngineInput) {
            pending = input
        }
        override suspend fun loadPendingInput(): PendingEngineInput? = pending
        override suspend fun appendCommitConsumingPending(commit: EngineCommit, successor: RuntimeDocument) {
            runtime = successor
            pending = null
        }
        override suspend fun <T> withReadSnapshot(block: suspend (StudyReadSnapshot) -> T): T = error("unused")
        override suspend fun storageUsage() = StorageUsage(0, 1)
        override suspend fun evictThrough(runtime: RuntimeDocument, targetBytes: Long): RuntimeDocument = runtime
        override suspend fun clear() = Unit
    }
}
