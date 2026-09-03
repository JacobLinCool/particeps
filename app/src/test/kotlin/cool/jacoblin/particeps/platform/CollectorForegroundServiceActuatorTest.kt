package cool.jacoblin.particeps.platform

import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ApplyReceipt
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.FlushReceipt
import cool.jacoblin.particeps.core.resource.PrepareReceipt
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.ReleaseReceipt
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
import cool.jacoblin.particeps.core.resource.requireCleanupReleased
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorForegroundServiceActuatorTest {
    @Test
    fun rejectedForegroundAcquireLeavesExactNotAppliedCleanupEvidence() = runBlocking {
        val desired = desired(COLLECTOR_A)
        val delegate = PreparedStateActuator(desired.key)
        val rejection = SecurityException("fixture rejection")
        val host = object : CollectorForegroundServiceHost {
            var releaseCalls = 0

            override suspend fun acquire(key: ResourceKey, studyTitle: String, usesLocation: Boolean) {
                assertEquals(desired.key, key)
                assertTrue("FGS acquisition ran before the desired state was prepared", delegate.isPrepared)
                throw rejection
            }

            override suspend fun release(key: ResourceKey) {
                assertEquals(desired.key, key)
                releaseCalls += 1
            }
        }
        val actuator = ForegroundServiceCollectorActuator(
            delegate = delegate,
            host = host,
            studyTitle = "Fixture study",
            usesLocation = true,
        )

        val caught = runCatching { actuator.prepare(desired, REQUEST_ID) }.exceptionOrNull()
        assertSame(rejection, caught)
        val release = actuator.release(desired)

        assertEquals(ReleaseEvidence.NOT_APPLIED, release.evidence)
        assertEquals(1, host.releaseCalls)
        release.requireCleanupReleased(desired, actuator.health())
    }

    @Test
    fun failedLocationUpgradeRestoresPriorTypeAndKeepsOwnersUnchanged() = runBlocking {
        val controller = RecordingController(failLocationUpgrade = true, failRestoration = false)
        val host = RefCountedCollectorForegroundService(controller)

        host.acquire(COLLECTOR_A, "Fixture study", usesLocation = false)
        val rejection = runCatching {
            host.acquire(COLLECTOR_B, "Fixture study", usesLocation = true)
        }.exceptionOrNull()

        assertTrue(rejection is SecurityException)
        assertEquals(listOf(false, true, false), controller.starts)
        assertEquals(mapOf(COLLECTOR_A to false), host.ownersForTest())

        // The rejected key owns no lease, while the pre-existing owner still releases normally.
        host.release(COLLECTOR_B)
        host.release(COLLECTOR_A)
        assertEquals(1, controller.stopCalls)
        assertTrue(host.ownersForTest().isEmpty())
    }

    @Test
    fun failedLocationUpgradeAndFailedRestorationRejectsContainment() = runBlocking {
        val controller = RecordingController(failLocationUpgrade = true, failRestoration = true)
        val host = RefCountedCollectorForegroundService(controller)

        host.acquire(COLLECTOR_A, "Fixture study", usesLocation = false)
        val rejection = runCatching {
            host.acquire(COLLECTOR_B, "Fixture study", usesLocation = true)
        }.exceptionOrNull()
        assertTrue(rejection is SecurityException)
        assertEquals(1, requireNotNull(rejection).suppressed.size)
        assertEquals(mapOf(COLLECTOR_A to false), host.ownersForTest())

        // Runtime cleanup of the rejected desired identity must also fail, promoting an optional
        // resource failure to the global fail-closed path instead of claiming the old FGS survived.
        assertSame(rejection, runCatching { host.release(COLLECTOR_B) }.exceptionOrNull())
        host.release(COLLECTOR_A)
        assertEquals(1, controller.stopCalls)
    }

    private class RecordingController(
        private val failLocationUpgrade: Boolean,
        private val failRestoration: Boolean,
    ) : CollectorForegroundServiceController {
        val starts = mutableListOf<Boolean>()
        var stopCalls = 0

        override suspend fun start(studyTitle: String, usesLocation: Boolean) {
            starts += usesLocation
            if (usesLocation && failLocationUpgrade) throw SecurityException("location rejected")
            if (!usesLocation && failRestoration && starts.size > 1) {
                throw IllegalStateException("restoration rejected")
            }
        }

        override fun stop() {
            stopCalls += 1
        }
    }

    private class PreparedStateActuator(
        override val key: ResourceKey,
    ) : StatefulResourceActuator {
        override val supportsHotProfileSwap = false
        private var prepared: DesiredResourceState? = null
        val isPrepared: Boolean get() = prepared != null

        override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) = Unit

        override suspend fun prepare(desired: DesiredResourceState, requestId: String): PrepareReceipt {
            require(desired.key == key && prepared == null)
            prepared = desired
            return PrepareReceipt(
                key,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                null,
                requestId,
            )
        }

        override suspend fun release(desired: DesiredResourceState): ReleaseReceipt {
            require(prepared == desired)
            prepared = null
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

        override fun health(): ResourceHealth = if (prepared == null) {
            ResourceHealth(key, ResourceHealthStatus.INACTIVE, null, null, null, null, null)
        } else {
            val desired = checkNotNull(prepared)
            ResourceHealth(
                key,
                ResourceHealthStatus.PREPARED,
                desired.generation,
                desired.profile?.id,
                desired.profile?.expectedSha256,
                null,
                null,
            )
        }

        override suspend fun suspendAt(desired: DesiredResourceState, boundary: ResearchTime): SuspendReceipt =
            error("not used")

        override suspend fun flushThrough(
            desired: DesiredResourceState,
            boundary: ResearchTime,
            cursor: String?,
        ): FlushReceipt = error("not used")

        override suspend fun apply(desired: DesiredResourceState): ApplyReceipt = error("not used")

        override suspend fun verify(desired: DesiredResourceState): VerifyReceipt = error("not used")

        override suspend fun resume(desired: DesiredResourceState): ResumeReceipt = error("not used")
    }

    private fun desired(key: ResourceKey): DesiredResourceState = DesiredResourceState(
        key = key,
        generation = ResourceGeneration(1uL),
        required = false,
        profile = SignedResourceProfile("continuous", "fixture-profile".toByteArray()),
    )

    private companion object {
        val COLLECTOR_A = ResourceKey(ResourceKind.COLLECTOR, "collector-a.v1")
        val COLLECTOR_B = ResourceKey(ResourceKind.COLLECTOR, "collector-b.v1")
        const val REQUEST_ID = "fixture-request"
    }
}
