package cool.jacoblin.particeps

import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingCounterSnapshot
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.resource.AppliedResourceState
import cool.jacoblin.particeps.core.resource.AppliedResourceStatus
import cool.jacoblin.particeps.core.resource.AppliedResourceVector
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceHealth
import cool.jacoblin.particeps.core.resource.ResourceHealthStatus
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.runtime.RuntimeSnapshot
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostHarnessAppliedProfileTest {
    @Test
    fun debugReflectionContractMatchesTheActualRuntimeAndSession() {
        HostHarnessAppliedProfileReader.requireReflectionContract()
    }

    @Test
    fun onlyVerifiedAppliedEpochExposesTheProfileAndCommittedStudyClock() {
        val result = project()

        assertEquals("VERIFIED", result.status)
        assertEquals("RUNNING", result.state)
        assertEquals("cap-512", result.profileId)
        assertEquals(PROFILE_SHA, result.appliedProfileSha256)
        assertEquals(BigInteger("2"), result.resourceGeneration)
        assertEquals(EPOCH_ID.value, result.conditionEpochId)
        assertEquals(vector.conditionDigest.value, result.appliedResourceVectorSha256)
        assertEquals(7L, result.revision)
        assertEquals(31_234L, result.activeRunningElapsedMillis)
    }

    @Test
    fun closedLiveGateDoesNotReusePreviouslyPublishedOpenGate() {
        val result = project(admissionOpen = false)

        assertPending(result)
        assertFalse(result.admissionOpen)
    }

    @Test
    fun unpublishedAdmissionDoesNotPromoteAnAppliedButNotYetResumedEpoch() {
        assertPending(project(snapshot = snapshot.copy(admissionOpen = false)))
    }

    @Test
    fun pausedStateDoesNotExposePreviouslyAppliedProfile() {
        assertPending(project(
            document = document.copy(state = ExperimentState.PAUSED),
            snapshot = snapshot.copy(state = ExperimentState.PAUSED),
        ))
    }

    @Test
    fun transitionWithoutCommittedEpochDoesNotExposeOldAppliedProfile() {
        val result = project(
            document = document.copy(activeConditionEpoch = null),
            snapshot = snapshot.copy(conditionEpochId = null, appliedResourceVectorSha256 = null),
        )
        assertPending(result)
        assertNull(result.conditionEpochId)
        assertNull(result.appliedResourceVectorSha256)
    }

    @Test
    fun aDifferentProfileCannotBeReportedUnderTheOldEpochDigest() {
        val changed = AppliedResourceVector(listOf(traffic.copy(
            profileId = "cap-4096",
            appliedProfileSha256 = Sha256Digest("c".repeat(64)),
        )))
        assertThrows(IllegalStateException::class.java) { project(applied = changed) }
    }

    @Test
    fun vectorValidationIncludesEveryResourceNotOnlyTrafficShaping() {
        val changed = AppliedResourceVector(listOf(traffic, collector.copy(
            desiredGeneration = ResourceGeneration(3uL),
        )).sortedBy(AppliedResourceState::key))
        assertThrows(IllegalStateException::class.java) { project(applied = changed) }
    }

    @Test
    fun mixedRevisionOrClockCannotBePresentedAsOneVerifiedObservation() {
        assertThrows(IllegalStateException::class.java) {
            project(snapshot = snapshot.copy(revision = 8))
        }
        assertThrows(IllegalStateException::class.java) {
            project(snapshot = snapshot.copy(activeRunningElapsedNanos = ELAPSED + 1))
        }
    }

    @Test
    fun epochFromAnotherSignedConfigurationIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            project(document = document.copy(activeConditionEpoch = epoch.copy(configurationSha256 = "d".repeat(64))))
        }
    }

    @Test
    fun attemptedCleanupCannotBePromotedToVerifiedEvenIfEpochDigestMatches() {
        assertThrows(IllegalStateException::class.java) {
            project(document = document.copy(components = mapOf(
                RuntimeComponentKey(RuntimeComponentKind.RESOURCE_CLEANUP, "actuator:traffic-shaping.v1") to "attempted",
            )))
        }
    }

    @Test
    fun committedInactiveTrafficIsPendingWithNoProfileEvidence() {
        val inactive = AppliedResourceVector(listOf(traffic.copy(
            profileId = null,
            appliedProfileSha256 = null,
            status = AppliedResourceStatus.INACTIVE,
        ), collector).sortedBy(AppliedResourceState::key))
        val inactiveEpoch = epoch.copy(appliedResourceVectorSha256 = inactive.conditionDigest.value)
        assertPending(project(
            document = document.copy(activeConditionEpoch = inactiveEpoch),
            snapshot = snapshot.copy(appliedResourceVectorSha256 = inactive.conditionDigest.value),
            applied = inactive,
        ))
    }

    @Test
    fun generationPreservesTheEntireUnsignedRuntimeDomain() {
        val maximum = AppliedResourceVector(listOf(
            traffic.copy(desiredGeneration = ResourceGeneration(ULong.MAX_VALUE)), collector,
        ).sortedBy(AppliedResourceState::key))
        val maximumEpoch = epoch.copy(appliedResourceVectorSha256 = maximum.conditionDigest.value)
        val result = project(
            document = document.copy(activeConditionEpoch = maximumEpoch),
            snapshot = snapshot.copy(appliedResourceVectorSha256 = maximum.conditionDigest.value),
            applied = maximum,
        )
        assertEquals(BigInteger("18446744073709551615"), result.resourceGeneration)
    }

    @Test
    fun liveCountersRetainTheirActualGenerationAndMonotonicReadInterval() {
        val result = nativeSample()

        assertEquals(counters, result.counters)
        assertEquals(100L, result.sampleStartedElapsedRealtimeNanos)
        assertEquals(120L, result.sampleCompletedElapsedRealtimeNanos)
    }

    @Test
    fun missingNativeSnapshotIsRejectedInsteadOfInventingZeroTraffic() {
        assertThrows(IllegalStateException::class.java) { nativeSample(counters = null) }
    }

    @Test
    fun nativeCountersCannotBeAttributedToAnotherCommittedProfile() {
        assertThrows(IllegalStateException::class.java) {
            nativeSample(counters = counters.copy(profileSha256 = Sha256Digest("c".repeat(64))))
        }
    }

    @Test
    fun cachedCountersFromAnUnverifiedOrReplacedVpnAreRejected() {
        assertThrows(IllegalStateException::class.java) { nativeSample(vpnBefore = null) }
        assertThrows(IllegalStateException::class.java) { nativeSample(vpnAfter = OTHER_VPN) }
        assertThrows(IllegalStateException::class.java) {
            nativeSample(counters = counters.copy(vpnGenerationId = OTHER_VPN))
        }
    }

    @Test
    fun terminalFailureBeforeOrDuringSnapshotRejectsRetainedCounters() {
        val failed = health.copy(
            status = ResourceHealthStatus.FAILED,
            appliedProfileSha256 = null,
            failureReason = "TUN_IO_FAILURE",
        )
        assertThrows(IllegalStateException::class.java) { nativeSample(healthBefore = failed) }
        assertThrows(IllegalStateException::class.java) { nativeSample(healthAfter = failed) }
    }

    @Test
    fun nativeHealthMustStillMatchTheCommittedResourceGeneration() {
        assertThrows(IllegalStateException::class.java) {
            nativeSample(healthAfter = health.copy(generation = ResourceGeneration(3uL)))
        }
        assertThrows(IllegalStateException::class.java) {
            nativeSample(healthAfter = health.copy(profileId = "other-profile"))
        }
    }

    @Test
    fun pendingStateOrAdmissionClosedDuringSnapshotCannotReturnLiveCounters() {
        assertThrows(IllegalStateException::class.java) {
            nativeSample(profile = project(admissionOpen = false))
        }
        assertThrows(IllegalStateException::class.java) { nativeSample(admissionOpenAfterSample = false) }
    }

    @Test
    fun invertedOrNegativeMonotonicReadIntervalsAreRejected() {
        assertThrows(IllegalStateException::class.java) { nativeSample(startedAt = -1) }
        assertThrows(IllegalStateException::class.java) { nativeSample(completedAt = 99) }
    }

    private fun nativeSample(
        profile: HostHarnessAppliedProfile = project(),
        counters: TrafficShapingCounterSnapshot? = this.counters,
        healthBefore: ResourceHealth = health,
        healthAfter: ResourceHealth = health,
        vpnBefore: String? = VPN,
        vpnAfter: String? = VPN,
        admissionOpenAfterSample: Boolean = true,
        startedAt: Long = 100,
        completedAt: Long = 120,
    ) = HostHarnessNativeCounters.fromVerified(
        profile, counters, healthBefore, healthAfter, vpnBefore, vpnAfter,
        admissionOpenAfterSample, startedAt, completedAt,
    )

    private fun project(
        document: RuntimeDocument = this.document,
        snapshot: RuntimeSnapshot = this.snapshot,
        applied: AppliedResourceVector = vector,
        admissionOpen: Boolean = true,
    ) = HostHarnessAppliedProfile.fromCommitted(document, snapshot, applied, admissionOpen)

    private fun assertPending(result: HostHarnessAppliedProfile) {
        assertEquals("PENDING", result.status)
        assertNull(result.profileId)
        assertNull(result.appliedProfileSha256)
        assertNull(result.resourceGeneration)
    }

    private val traffic = AppliedResourceState(
        ResourceKey(ResourceKind.ACTUATOR, TrafficShapingActuator.RESOURCE_ID),
        ResourceGeneration(2uL), "cap-512", Sha256Digest(PROFILE_SHA), AppliedResourceStatus.APPLIED, null,
    )
    private val collector = AppliedResourceState(
        ResourceKey(ResourceKind.COLLECTOR, "battery_state.v1"),
        ResourceGeneration(1uL), "default", Sha256Digest("b".repeat(64)), AppliedResourceStatus.APPLIED, null,
    )
    private val health = ResourceHealth(
        traffic.key, ResourceHealthStatus.APPLIED, traffic.desiredGeneration, traffic.profileId,
        traffic.appliedProfileSha256, traffic.appliedProfileSha256, null,
    )
    private val counters = TrafficShapingCounterSnapshot(
        nativeGeneration = 3,
        vpnGenerationId = VPN,
        profileSha256 = Sha256Digest(PROFILE_SHA),
        uplinkBytes = 72_000,
        uplinkPackets = 48,
        downlinkBytes = 2_880,
        downlinkPackets = 48,
        uplinkThrottledNanos = 1_000_000_000,
        downlinkThrottledNanos = 0,
    )
    private val vector = AppliedResourceVector(listOf(traffic, collector).sortedBy(AppliedResourceState::key))
    private val epoch = ConditionEpoch(EPOCH_ID, CONFIG_SHA, vector.conditionDigest.value, NOW)
    private val document = RuntimeDocument.initial(
        "host-study", "host-config", CONFIG_SHA, "A".repeat(43),
    ).copy(
        state = ExperimentState.RUNNING,
        revision = 7,
        nextCommitSequence = 8,
        clockCheckpoint = StudyClockCheckpoint(ELAPSED, ELAPSED, NOW, 2_000_000, true, "UTC"),
        activeConditionEpoch = epoch,
    )
    private val snapshot = RuntimeSnapshot(
        initialized = true,
        state = ExperimentState.RUNNING,
        revision = 7,
        conditionEpochId = EPOCH_ID,
        appliedResourceVectorSha256 = vector.conditionDigest.value,
        admissionOpen = true,
        activeRunningElapsedNanos = ELAPSED,
    )

    private companion object {
        val CONFIG_SHA = "a".repeat(64)
        val PROFILE_SHA = "e".repeat(64)
        val EPOCH_ID = ConditionEpochId("123e4567-e89b-42d3-a456-426614174000")
        val NOW = ResearchTime(1_000_000, 60_000_000_000, "test-boot")
        const val ELAPSED = 31_234_567_890L
        const val VPN = "123e4567-e89b-42d3-a456-426614174001"
        const val OTHER_VPN = "123e4567-e89b-42d3-a456-426614174002"
    }
}
