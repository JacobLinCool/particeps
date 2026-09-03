package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AdmissionToken
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorFlushResult
import cool.jacoblin.particeps.core.collector.CollectorHealth
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.collector.CoverageAdvance
import cool.jacoblin.particeps.core.collector.EmitBatchResult
import cool.jacoblin.particeps.core.collector.EventSink
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.collector.SourceEventBatch
import cool.jacoblin.particeps.core.definition.BatteryStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.NamedCollectorProfile
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.requireReleased
import cool.jacoblin.particeps.core.resource.requireCleanupReleased
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectorResourceActuatorTest {
    @Test
    fun preservesAnUnchangedGenerationAndRotatesAnActuallyChangedProfile() = runTest {
        val declaration = CollectorResourceConfiguration(
            id = BatteryStateV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(
                NamedCollectorProfile("continuous", BatteryStateV1ProfileConfiguration()),
                NamedCollectorProfile("reduced", BatteryStateV1ProfileConfiguration()),
            ),
        )
        val plugin = FakeCollectorPlugin()
        val actuator = actuator(declaration, plugin)
        val first = desired(declaration, "continuous", 1uL)

        actuator.prepare(first, "initial")
        actuator.apply(first)
        assertTrue(actuator.verify(first).healthy)
        assertTrue(actuator.resume(first).resumed)
        assertEquals(ResourceHealthStatus.APPLIED, actuator.onAdmissionOpened(first).status)
        assertEquals(listOf("start", "admission"), plugin.instances.single().calls)

        val boundary = time(2)
        actuator.suspendAt(first, boundary)
        val flush = actuator.flushThrough(first, boundary, "cursor-before")
        actuator.prepare(first, "global-barrier")
        actuator.apply(first)
        actuator.verify(first)
        actuator.resume(first)
        actuator.onAdmissionOpened(first)

        assertEquals(1, plugin.instances.size)
        assertEquals("cursor-after", flush.cursor)
        assertEquals(
            listOf("start", "admission", "pause", "flush:cursor-before", "resume", "admission"),
            plugin.instances.single().calls,
        )

        actuator.suspendAt(first, time(3))
        val second = desired(declaration, "reduced", 2uL)
        actuator.prepare(second, "profile-change")
        actuator.apply(second)
        assertTrue(actuator.verify(second).healthy)
        actuator.resume(second)
        actuator.onAdmissionOpened(second)

        assertEquals(2, plugin.instances.size)
        assertEquals("stop", plugin.instances.first().calls.last())
        assertEquals(listOf("start", "admission"), plugin.instances.last().calls)
        val released = actuator.release(second)
        assertEquals(ResourceGeneration(2uL), released.generation)
        assertEquals(released, actuator.release(second))
        assertEquals(1, plugin.instances.last().calls.count { it == "stop" })
        assertEquals(ResourceHealthStatus.INACTIVE, actuator.health().status)
    }

    @Test
    fun terminalCollectorHealthBecomesOneGenericResourceFailure() = runTest {
        val declaration = CollectorResourceConfiguration(
            id = BatteryStateV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(NamedCollectorProfile("continuous", BatteryStateV1ProfileConfiguration())),
        )
        val plugin = FakeCollectorPlugin()
        val actuator = actuator(declaration, plugin)
        val failures = mutableListOf<String>()
        actuator.setTerminalFailureListener { failures += it.reason }
        val desired = desired(declaration, "continuous", 1uL)
        actuator.prepare(desired, "initial")
        actuator.apply(desired)
        actuator.verify(desired)
        actuator.resume(desired)
        actuator.onAdmissionOpened(desired)
        runCurrent()

        plugin.instances.single().healthState.value = CollectorHealth(CollectorStatus.FAILED, "PRIVATE_DETAIL")
        runCurrent()
        plugin.instances.single().healthState.value = CollectorHealth(CollectorStatus.FAILED, "ANOTHER_DETAIL")
        runCurrent()

        assertEquals(listOf("COLLECTOR_FAILED"), failures)
        assertEquals("COLLECTOR_FAILED", actuator.health().failureReason)
        actuator.release(desired)
    }

    @Test
    fun failedStartCanBeReleasedWithoutMaskingOriginalFailure() = runTest {
        val declaration = CollectorResourceConfiguration(
            id = BatteryStateV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(NamedCollectorProfile("continuous", BatteryStateV1ProfileConfiguration())),
        )
        val plugin = FakeCollectorPlugin(failStart = true)
        val actuator = actuator(declaration, plugin)
        val desired = desired(declaration, "continuous", 1uL)
        actuator.prepare(desired, "failed-start")

        val original = runCatching { actuator.apply(desired) }.exceptionOrNull()
        assertEquals("collector start failed", original?.message)

        val cleanup = actuator.release(desired)
        assertEquals(ReleaseEvidence.NOT_APPLIED, cleanup.evidence)
        assertNull(cleanup.appliedProfileSha256)
        cleanup.requireCleanupReleased(desired, actuator.health())
        assertEquals(listOf("start", "stop"), plugin.instances.single().calls)
    }

    @Test
    fun failedStartRetainsOriginalFailureWhenImmediateStopAlsoFails() = runTest {
        val declaration = CollectorResourceConfiguration(
            id = BatteryStateV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(NamedCollectorProfile("continuous", BatteryStateV1ProfileConfiguration())),
        )
        val plugin = FakeCollectorPlugin(failStart = true, failStop = true)
        val actuator = actuator(declaration, plugin)
        val desired = desired(declaration, "continuous", 1uL)
        actuator.prepare(desired, "failed-start-and-stop")

        val original = runCatching { actuator.apply(desired) }.exceptionOrNull()
        assertEquals("collector start failed", original?.message)
        assertEquals(listOf("collector stop failed"), original?.suppressed?.map { it.message })
        assertEquals(ResourceHealthStatus.FAILED, actuator.health().status)

        plugin.instances.single().failStop = false
        val cleanup = actuator.release(desired)
        assertEquals(ReleaseEvidence.NOT_APPLIED, cleanup.evidence)
        cleanup.requireCleanupReleased(desired, actuator.health())
        assertEquals(listOf("start", "stop", "stop"), plugin.instances.single().calls)
    }

    @Test
    fun reprepareOfVerifiedBindingCannotDowngradeReleaseEvidence() = runTest {
        val declaration = CollectorResourceConfiguration(
            id = BatteryStateV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(NamedCollectorProfile("continuous", BatteryStateV1ProfileConfiguration())),
        )
        val plugin = FakeCollectorPlugin()
        val actuator = actuator(declaration, plugin)
        val desired = desired(declaration, "continuous", 1uL)
        actuator.prepare(desired, "initial")
        actuator.apply(desired)
        actuator.verify(desired)

        actuator.prepare(desired, "barrier-reprepare")
        val release = actuator.release(desired)

        assertEquals(ReleaseEvidence.APPLIED, release.evidence)
        release.requireReleased(desired, actuator.health())
        assertEquals(1, plugin.instances.single().calls.count { it == "stop" })
    }

    @Test
    fun studyTokenEncoderIsDeterministicDomainSeparatedAndDoesNotExposeItsKey() {
        val encoder = BindableStudyScopedTokenEncoder()
        encoder.bindBase64Url(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }))

        val first = encoder.encode("usage-events.activity-component.v1", "ExampleActivity")
        val repeated = encoder.encode("usage-events.activity-component.v1", "ExampleActivity")
        val otherDomain = encoder.encode("usage-events.other.v1", "ExampleActivity")

        assertEquals(64, first.length)
        assertEquals(first, repeated)
        assertNotEquals(first, otherDomain)
        encoder.clear()
        assertTrue(runCatching { encoder.encode("usage-events.activity-component.v1", "ExampleActivity") }.isFailure)
    }

    private fun TestScope.actuator(
        declaration: CollectorResourceConfiguration,
        plugin: FakeCollectorPlugin,
    ) = CollectorResourceActuator(
        declaration = declaration,
        plugin = plugin,
        scope = this,
        eventSink = ClosedSink,
        clocks = object : ResearchClocks {
            override fun now(): ResearchTime = time(1)
        },
        tokenEncoder = BindableStudyScopedTokenEncoder().apply {
            bindBase64Url(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 }))
        },
    )

    private fun desired(
        declaration: CollectorResourceConfiguration,
        profileId: String,
        generation: ULong,
    ) = DesiredResourceState(
        key = declaration.resourceKey,
        generation = ResourceGeneration(generation),
        required = declaration.required,
        profile = declaration.profiles.single { it.id == profileId }.asSignedProfile(),
    )

    private class FakeCollectorPlugin(
        private val failStart: Boolean = false,
        private val failStop: Boolean = false,
    ) : CollectorPlugin {
        val instances = mutableListOf<FakeCollector>()
        override val descriptor = CollectorDescriptor(
            id = BatteryStateV1ProfileConfiguration.SOURCE_ID,
            displayName = "Battery state",
            sourceContract = requireNotNull(
                ProtocolEventSourceRegistry[BatteryStateV1ProfileConfiguration.SOURCE_ID],
            ),
            accessKinds = emptySet(),
        )

        override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
            require(configuration is BatteryStateV1ProfileConfiguration)
            return FakeCollector(failStart, failStop).also(instances::add)
        }
    }

    private class FakeCollector(
        private val failStart: Boolean,
        var failStop: Boolean,
    ) : Collector {
        val calls = mutableListOf<String>()
        val healthState = MutableStateFlow(CollectorHealth(CollectorStatus.STOPPED))
        override val health: StateFlow<CollectorHealth> = healthState
        override val requiresStop: Boolean get() = health.value.status != CollectorStatus.STOPPED

        override suspend fun start() {
            calls += "start"
            healthState.value = CollectorHealth(CollectorStatus.ACTIVE)
            if (failStart) throw IllegalStateException("collector start failed")
        }

        override suspend fun onAdmissionOpened() {
            calls += "admission"
        }

        override suspend fun pause() {
            calls += "pause"
            healthState.value = CollectorHealth(CollectorStatus.PAUSED)
        }

        override suspend fun resume() {
            calls += "resume"
            healthState.value = CollectorHealth(CollectorStatus.ACTIVE)
        }

        override suspend fun flushThrough(boundary: ResearchTime, cursor: String?): CollectorFlushResult {
            calls += "flush:$cursor"
            return CollectorFlushResult.Complete(boundary, "cursor-after")
        }

        override suspend fun stop() {
            calls += "stop"
            if (failStop) throw IllegalStateException("collector stop failed")
            healthState.value = CollectorHealth(CollectorStatus.STOPPED)
        }
    }

    private object ClosedSink : EventSink {
        override fun captureToken(): AdmissionToken? = null

        override fun captureBarrierFlushToken(boundary: ResearchTime): AdmissionToken? = null
        override suspend fun emitBatch(token: AdmissionToken, batch: SourceEventBatch) =
            EmitBatchResult.RejectedByAdmissionGate
        override suspend fun advanceCoverage(token: AdmissionToken, advance: CoverageAdvance) =
            EmitBatchResult.RejectedByAdmissionGate
    }

    private companion object {
        fun time(index: Long) = ResearchTime(
            wallTimeUtcMillis = 1_800_000_000_000L + index,
            elapsedRealtimeNanos = index * 1_000_000L,
            bootSessionId = "boot-one",
        )
    }
}
