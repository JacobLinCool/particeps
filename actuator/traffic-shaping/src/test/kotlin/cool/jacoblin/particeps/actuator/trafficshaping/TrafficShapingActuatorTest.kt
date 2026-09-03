package cool.jacoblin.particeps.actuator.trafficshaping

import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.ResourceAuditEvidence
import cool.jacoblin.particeps.core.resource.ResourceAuditRemovalReason
import cool.jacoblin.particeps.core.resource.ResourceAuditRequest
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.core.resource.ReleaseEvidence
import cool.jacoblin.particeps.core.resource.requireCleanupReleased
import cool.jacoblin.particeps.core.resource.requireReleased
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrafficShapingActuatorTest {
    @Test
    fun verifiedProfileFollowsPrepareApplyVerifyResumeContract() = runTest {
        val platform = FakePlatform()
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val desired = desired(1uL, BASELINE)

        assertEquals("request-1", actuator.prepare(desired, "request-1").requestId)
        assertEquals(BASELINE.expectedSha256, actuator.apply(desired).appliedProfileSha256)
        val verified = actuator.verify(desired)
        assertTrue(verified.healthy)
        assertEquals(BASELINE.expectedSha256, verified.appliedProfileSha256)
        assertTrue(actuator.resume(desired).resumed)
        assertEquals(
            listOf("prepare", "apply:baseline", "verify", "resume"),
            platform.operations,
        )
    }

    @Test
    fun lowerGenerationIsRejectedWithoutCallingPlatform() = runTest {
        val platform = FakePlatform()
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val newest = desired(2uL, BASELINE)
        actuator.prepare(newest, "request-2")
        actuator.apply(newest)

        assertSuspendThrows<IllegalArgumentException> {
            actuator.prepare(desired(1uL, SLOW), "stale")
        }
        assertEquals(1, platform.operations.count { it.startsWith("apply:") })
    }

    @Test
    fun profileMismatchFailsClosedAndNotifiesRuntime() = runTest {
        val platform = FakePlatform().apply { appliedDigest = ZERO_DIGEST }
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val terminal = AtomicReference<String>()
        actuator.setTerminalFailureListener { failure -> terminal.set(failure.reason) }
        val desired = desired(1uL, BASELINE)
        actuator.prepare(desired, "request-3")

        val failure = assertSuspendThrows<TrafficShapingActuatorException> {
            actuator.apply(desired)
        }
        assertEquals(TrafficShapingFailureReason.PROFILE_MISMATCH, failure.reason)
        assertEquals(TrafficShapingFailureReason.PROFILE_MISMATCH, terminal.get())
        assertEquals(ResourceHealthStatus.FAILED, actuator.health().status)

        val cleanup = actuator.release(desired)
        assertEquals(ReleaseEvidence.APPLIED, cleanup.evidence)
        assertEquals(ZERO_DIGEST, cleanup.appliedProfileSha256)
        cleanup.requireCleanupReleased(desired, actuator.health())
    }

    @Test
    fun activationDeadlineFailsClosed() = runTest {
        val platform = FakePlatform().apply { applyDelayMillis = 10_001 }
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val terminal = AtomicReference<String>()
        actuator.setTerminalFailureListener { failure -> terminal.set(failure.reason) }
        val desired = desired(1uL, BASELINE)
        actuator.prepare(desired, "request-4")

        val failure = assertSuspendThrows<TrafficShapingActuatorException> {
            actuator.apply(desired)
        }
        assertEquals(TrafficShapingFailureReason.ACTIVATION_TIMEOUT, failure.reason)
        assertEquals(TrafficShapingFailureReason.ACTIVATION_TIMEOUT, terminal.get())
    }

    @Test
    fun releaseBindsAppliedStateAndProvesExactInactiveHealth() = runTest {
        val platform = FakePlatform()
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val desired = desired(1uL, BASELINE)
        actuator.prepare(desired, "request-5")
        actuator.apply(desired)
        actuator.verify(desired)

        val receipt = actuator.release(desired)
        assertEquals(BASELINE.id, receipt.profileId)
        assertEquals(BASELINE.expectedSha256, receipt.appliedProfileSha256)
        assertEquals(ReleaseEvidence.APPLIED, receipt.evidence)
        assertTrue(receipt.released)
        assertEquals(ResourceHealthStatus.INACTIVE, actuator.health().status)
        assertTrue("release" in platform.operations)
        assertEquals(receipt, actuator.release(desired))
        assertEquals(1, platform.operations.count { it == "release" })
    }

    @Test
    fun failedHotSwapCleansUpWithoutMaskingOriginalApplyFailure() = runTest {
        val platform = FakePlatform()
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val baseline = desired(1uL, BASELINE)
        actuator.prepare(baseline, "initial")
        actuator.apply(baseline)
        actuator.verify(baseline)
        actuator.suspendAt(baseline, BOUNDARY)

        val replacement = desired(2uL, SLOW)
        actuator.prepare(replacement, "replacement")
        platform.applyFailure = IllegalStateException("native apply failed")
        val original = runCatching { actuator.apply(replacement) }.exceptionOrNull()
        assertEquals("native apply failed", original?.message)

        val cleanup = actuator.release(replacement)
        assertEquals(ReleaseEvidence.NOT_APPLIED, cleanup.evidence)
        cleanup.requireCleanupReleased(replacement, actuator.health())
    }

    @Test
    fun reprepareOfVerifiedBindingRetainsStrictAppliedReleaseEvidence() = runTest {
        val platform = FakePlatform()
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val desired = desired(1uL, BASELINE)
        actuator.prepare(desired, "initial")
        actuator.apply(desired)
        actuator.verify(desired)

        actuator.prepare(desired, "barrier-reprepare")
        val release = actuator.release(desired)

        assertEquals(ReleaseEvidence.APPLIED, release.evidence)
        release.requireReleased(desired, actuator.health())
        assertEquals(1, platform.operations.count { it == "release" })
    }

    @Test
    fun flushReturnsOnlyAggregateNativeGenerationCursor() = runTest {
        val platform = FakePlatform().apply {
            snapshot = TrafficShapingCounterSnapshot(
                9,
                VPN_GENERATION_ID,
                BASELINE.expectedSha256,
                100,
                2,
                200,
                3,
                4,
                5,
            )
        }
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val desired = desired(1uL, BASELINE)
        actuator.prepare(desired, "flush")
        actuator.apply(desired)
        actuator.verify(desired)
        val receipt = actuator.flushThrough(desired, BOUNDARY, null)
        assertEquals("native-generation:9", receipt.cursor)
        assertTrue(receipt.complete)
        assertNotNull(actuator.snapshot())
    }

    @Test
    fun terminalCallbackIsDeliveredOnceAndRequiresReleaseBeforeReactivation() = runTest {
        val platform = FakePlatform()
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val callbacks = AtomicInteger()
        actuator.setTerminalFailureListener { callbacks.incrementAndGet() }
        platform.fail(TrafficShapingFailureReason.TUN_CLOSED)
        platform.fail(TrafficShapingFailureReason.OWNED_VPN_LOST)

        assertEquals(1, callbacks.get())
        assertEquals(ResourceHealthStatus.FAILED, actuator.health().status)
        val failure = assertSuspendThrows<TrafficShapingActuatorException> {
            actuator.prepare(desired(1uL, BASELINE), "blocked-until-release")
        }
        assertEquals(TrafficShapingFailureReason.TUN_CLOSED, failure.reason)

        val inactive = DesiredResourceState(
            actuator.key,
            ResourceGeneration(1uL),
            required = true,
            profile = null,
        )
        actuator.release(inactive)
        val newDesired = desired(1uL, BASELINE)
        actuator.prepare(newDesired, "new-activation")
        assertEquals(ResourceHealthStatus.PREPARED, actuator.health().status)
    }

    @Test
    fun auditDraftsBindVerifiedVpnProfileCountersAndEpochWithoutTrafficDetails() = runTest {
        val platform = FakePlatform().apply { appliedDigest = SLOW.expectedSha256 }
        val actuator = TrafficShapingActuator(platform, TARGETS) { 0L }
        val desired = desired(7uL, SLOW)
        actuator.prepare(desired, "audit-request")
        actuator.apply(desired)
        actuator.verify(desired)
        platform.snapshot = TrafficShapingCounterSnapshot(
            nativeGeneration = 11,
            vpnGenerationId = VPN_GENERATION_ID,
            profileSha256 = SLOW.expectedSha256,
            uplinkBytes = 1_000,
            uplinkPackets = 10,
            downlinkBytes = 2_000,
            downlinkPackets = 20,
            uplinkThrottledNanos = 30,
            downlinkThrottledNanos = 40,
        )
        val evidence = ResourceAuditEvidence(
            actuator.key,
            desired.generation,
            SLOW.id,
            SLOW.expectedSha256,
        )
        val epochId = ConditionEpochId("123e4567-e89b-42d3-a456-426614174091")

        val applied = actuator.audit(
            ResourceAuditRequest.EpochActivated(
                evidence,
                epochId,
                BOUNDARY,
                BOUNDARY,
                Sha256Digest("a".repeat(64)),
            ),
        ).events.single()
        assertEquals("traffic_shaping.v1", applied.type.sourceId.value)
        assertEquals("TRAFFIC_SHAPING_PROFILE_APPLIED", applied.type.eventType)
        assertEquals("256", applied.fields["uplink_kbps"])
        assertEquals("1024", applied.fields["downlink_kbps"])
        assertEquals(VPN_GENERATION_ID, applied.fields["vpn_generation_id"])
        assertEquals(
            Sha256Digest.of("[\"com.example.target\"]".toByteArray()).value,
            applied.fields["target_package_list_sha256"],
        )

        val periodic = actuator.audit(
            ResourceAuditRequest.Periodic(evidence, epochId, BOUNDARY, BOUNDARY),
        ).events.single()
        assertEquals("TRAFFIC_SHAPING_SNAPSHOT", periodic.type.eventType)
        assertEquals("PERIODIC", periodic.fields["snapshot_reason"])
        assertEquals("1000", periodic.fields["uplink_bytes"])
        assertTrue(periodic.fields.keys.none { it.contains("destination") || it.contains("dns") })

        platform.fail(TrafficShapingFailureReason.TUN_CLOSED)
        assertEquals(ResourceHealthStatus.FAILED, actuator.health().status)
        val boundary = actuator.audit(
            ResourceAuditRequest.EpochBoundary(
                evidence,
                epochId,
                BOUNDARY,
                BOUNDARY,
                ResourceAuditRemovalReason.PROFILE_REPLACED,
            ),
        ).events
        assertEquals(
            listOf("TRAFFIC_SHAPING_SNAPSHOT", "TRAFFIC_SHAPING_PROFILE_REMOVED"),
            boundary.map { it.type.eventType },
        )
        assertEquals("EPOCH_BOUNDARY", boundary.first().fields["snapshot_reason"])
        assertEquals("PROFILE_REPLACED", boundary.last().fields["removal_reason"])
    }

    private class FakePlatform : TrafficShapingPlatform {
        val operations = mutableListOf<String>()
        var appliedDigest = BASELINE.expectedSha256
        var applyDelayMillis = 0L
        var applyFailure: Throwable? = null
        var snapshot: TrafficShapingCounterSnapshot? = null
        private var terminal: ((String) -> Unit)? = null

        override fun setTerminalFailureListener(listener: ((String) -> Unit)?) {
            terminal = listener
        }

        override suspend fun prepare(requestId: String) {
            operations += "prepare"
        }

        override suspend fun suspendForwarding() {
            operations += "suspend"
        }

        override suspend fun apply(profile: SignedResourceProfile): Sha256Digest {
            operations += "apply:${profile.id}"
            delay(applyDelayMillis)
            applyFailure?.let { throw it }
            return appliedDigest
        }

        override suspend fun verify(
            expectedProfileSha256: Sha256Digest,
        ): TrafficShapingPlatformProof {
            operations += "verify"
            return TrafficShapingPlatformProof(
                ownerNetworkVerified = true,
                tunOpen = true,
                nativeHealthy = true,
                protectorInstalled = true,
                packagesValid = true,
                vpnGenerationId = VPN_GENERATION_ID,
                appliedProfileSha256 = expectedProfileSha256,
            )
        }

        override suspend fun resumeForwarding() {
            operations += "resume"
        }

        override suspend fun release() {
            operations += "release"
        }

        override fun snapshot(): TrafficShapingCounterSnapshot? = snapshot

        fun fail(reason: String) {
            terminal?.invoke(reason)
        }
    }

    companion object {
        private val BASELINE_BYTES =
            "{\"downlink_kbps\":null,\"id\":\"baseline\",\"uplink_kbps\":null}"
                .toByteArray()
        private val BASELINE = SignedResourceProfile("baseline", BASELINE_BYTES)
        private val SLOW = SignedResourceProfile(
            "slow-network",
            "{\"downlink_kbps\":1024,\"id\":\"slow-network\",\"uplink_kbps\":256}"
                .toByteArray(),
        )
        private val ZERO_DIGEST = Sha256Digest("0".repeat(64))
        private val BOUNDARY = ResearchTime(1_000, 2_000, "boot-test")
        private const val VPN_GENERATION_ID = "123e4567-e89b-42d3-a456-426614174090"
        private val TARGETS = TargetPackageSet.of(listOf("com.example.target"))

        private fun desired(generation: ULong, profile: SignedResourceProfile) =
            DesiredResourceState(
                ResourceKey(ResourceKind.ACTUATOR, TrafficShapingActuator.RESOURCE_ID),
                ResourceGeneration(generation),
                required = true,
                profile = profile,
            )

    }
}

private suspend inline fun <reified T : Throwable> assertSuspendThrows(
    noinline block: suspend () -> Unit,
): T {
    try {
        block()
        fail("Expected ${T::class.java.simpleName}")
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw failure
    }
    error("unreachable")
}
