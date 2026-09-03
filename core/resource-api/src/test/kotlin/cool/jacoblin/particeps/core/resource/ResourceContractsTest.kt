package cool.jacoblin.particeps.core.resource

import cool.jacoblin.particeps.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResourceContractsTest {
    private val key = ResourceKey(ResourceKind.COLLECTOR, "usage_events.v1")
    private val profile = SignedResourceProfile("trigger-grade", "{\"poll_interval_seconds\":15}".toByteArray())
    private val otherProfile = SignedResourceProfile("continuous", "{\"poll_interval_seconds\":60}".toByteArray())

    @Test
    fun generationIsPositiveAndFailsOnOverflow() {
        assertEquals(ResourceGeneration(2uL), ResourceGeneration(1uL).next())
        assertThrows(IllegalArgumentException::class.java) { ResourceGeneration(0uL) }
        assertThrows(IllegalStateException::class.java) { ResourceGeneration(ULong.MAX_VALUE).next() }
    }

    @Test
    fun signedProfileDefensivelyComparesByteContentAndDigest() {
        val equal = SignedResourceProfile("trigger-grade", profile.canonicalBytes.copyOf())
        assertEquals(profile, equal)
        assertEquals(profile.hashCode(), equal.hashCode())
        assertThrows(IllegalArgumentException::class.java) {
            SignedResourceProfile("trigger-grade", byteArrayOf(1), profile.expectedSha256)
        }
        val leaked = profile.canonicalBytes
        leaked.fill(0)
        assertNotEquals(0, profile.canonicalBytes.first().toInt())
    }

    @Test
    fun allOperationReceiptsBindExactDesiredAndAppliedEvidence() {
        val desired = DesiredResourceState(key, ResourceGeneration(4uL), required = true, profile)
        val boundary = ResearchTime(1_000, 2_000, "boot-one")
        PrepareReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            null,
            "request-4",
        ).requireMatches(desired, "request-4")
        SuspendReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
            boundary,
        ).requireMatches(desired, boundary)
        FlushReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
            boundary,
            "cursor",
            complete = true,
        ).requireMatches(desired, boundary)
        ApplyReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
        ).requireMatches(desired)
        VerifyReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
            healthy = true,
            failureReason = null,
        ).requireMatches(desired)
        ResumeReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
            resumed = true,
            failureReason = null,
        ).requireMatches(desired)
        ReleaseReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
            ReleaseEvidence.APPLIED,
            released = true,
        ).requireReleased(desired, inactiveHealth())
    }

    @Test
    fun hostileReceiptsWithRightGenerationButWrongProfileOrDigestFailClosed() {
        val desired = DesiredResourceState(key, ResourceGeneration(4uL), required = true, profile)
        val boundary = ResearchTime(1_000, 2_000, "boot-one")

        assertThrows(IllegalArgumentException::class.java) {
            ApplyReceipt(
                key,
                ResourceGeneration(3uL),
                profile.id,
                profile.expectedSha256,
                profile.expectedSha256,
            ).requireMatches(desired)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApplyReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                otherProfile.expectedSha256,
            ).requireMatches(desired)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrepareReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                null,
                "other-request",
            ).requireMatches(desired, "request-4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrepareReceipt(
                key,
                desired.generation,
                otherProfile.id,
                otherProfile.expectedSha256,
                null,
                "request-4",
            ).requireMatches(desired, "request-4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrepareReceipt(
                key,
                desired.generation,
                profile.id,
                otherProfile.expectedSha256,
                null,
                "request-4",
            ).requireMatches(desired, "request-4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SuspendReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                Sha256Digest.of("different".toByteArray()),
                boundary,
            ).requireMatches(desired, boundary)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SuspendReceipt(
                key,
                desired.generation,
                otherProfile.id,
                otherProfile.expectedSha256,
                otherProfile.expectedSha256,
                boundary,
            ).requireMatches(desired, boundary)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlushReceipt(
                key,
                desired.generation,
                otherProfile.id,
                otherProfile.expectedSha256,
                otherProfile.expectedSha256,
                boundary,
                null,
                complete = true,
            ).requireMatches(desired, boundary)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlushReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                otherProfile.expectedSha256,
                boundary,
                null,
                complete = true,
            ).requireMatches(desired, boundary)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseReceipt(
                key,
                desired.generation,
                profile.id,
                Sha256Digest.of("forged-expected".toByteArray()),
                profile.expectedSha256,
                ReleaseEvidence.APPLIED,
                released = true,
            ).requireReleased(desired, inactiveHealth())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseReceipt(
                key,
                desired.generation,
                otherProfile.id,
                otherProfile.expectedSha256,
                otherProfile.expectedSha256,
                ReleaseEvidence.APPLIED,
                released = true,
            ).requireReleased(desired, inactiveHealth())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                otherProfile.expectedSha256,
                ReleaseEvidence.APPLIED,
                released = true,
            ).requireReleased(desired, inactiveHealth())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseReceipt(
                key,
                desired.generation,
                profile.id,
                profile.expectedSha256,
                profile.expectedSha256,
                ReleaseEvidence.APPLIED,
                released = true,
            ).requireReleased(
                desired,
                ResourceHealth(
                    key,
                    ResourceHealthStatus.APPLIED,
                    desired.generation,
                    profile.id,
                    profile.expectedSha256,
                    profile.expectedSha256,
                    null,
                ),
            )
        }
    }

    @Test
    fun receiptShapesEnforceExactInactiveNullSemantics() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseReceipt(
                key,
                ResourceGeneration(1uL),
                null,
                null,
                profile.expectedSha256,
                ReleaseEvidence.INACTIVE,
                released = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrepareReceipt(
                key,
                ResourceGeneration(1uL),
                profile.id,
                null,
                null,
                "request",
            )
        }
    }

    @Test
    fun cleanupReleaseDistinguishesNeverAppliedFromVerifiedAppliedState() {
        val desired = DesiredResourceState(key, ResourceGeneration(1uL), required = true, profile)
        val cleanup = ReleaseReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            null,
            ReleaseEvidence.NOT_APPLIED,
            released = true,
        )

        cleanup.requireCleanupReleased(desired, inactiveHealth())
        assertThrows(IllegalArgumentException::class.java) {
            cleanup.requireReleased(desired, inactiveHealth())
        }
    }

    @Test
    fun attemptedCleanupBindsDesiredIdentityButMayReportTheMismatchedDigestItRemoved() {
        val desired = DesiredResourceState(key, ResourceGeneration(2uL), required = true, profile)
        val mismatchedAppliedCleanup = ReleaseReceipt(
            key,
            desired.generation,
            profile.id,
            profile.expectedSha256,
            otherProfile.expectedSha256,
            ReleaseEvidence.APPLIED,
            released = true,
        )

        mismatchedAppliedCleanup.requireCleanupReleased(desired, inactiveHealth())
        assertThrows(IllegalArgumentException::class.java) {
            mismatchedAppliedCleanup.requireReleased(desired, inactiveHealth())
        }
        listOf(
            mismatchedAppliedCleanup.copy(
                key = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1"),
            ),
            mismatchedAppliedCleanup.copy(generation = ResourceGeneration(3uL)),
            mismatchedAppliedCleanup.copy(
                profileId = otherProfile.id,
                expectedProfileSha256 = otherProfile.expectedSha256,
            ),
            mismatchedAppliedCleanup.copy(expectedProfileSha256 = otherProfile.expectedSha256),
        ).forEach { hostile ->
            assertThrows(IllegalArgumentException::class.java) {
                hostile.requireCleanupReleased(desired, inactiveHealth())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            mismatchedAppliedCleanup.requireCleanupReleased(
                desired,
                ResourceHealth(
                    key,
                    ResourceHealthStatus.APPLIED,
                    desired.generation,
                    profile.id,
                    profile.expectedSha256,
                    profile.expectedSha256,
                    null,
                ),
            )
        }
    }

    @Test
    fun appliedVectorUsesExactSortedCanonicalShape() {
        val applied = AppliedResourceState(
            key,
            ResourceGeneration(3uL),
            "trigger-grade",
            profile.expectedSha256,
            AppliedResourceStatus.APPLIED,
            null,
        )
        val vector = AppliedResourceVector(listOf(applied))
        assertEquals(
            "{\"resources\":[{\"applied_profile_sha256\":\"${profile.expectedSha256}\"," +
                "\"desired_generation\":\"3\",\"failure_reason\":null," +
                "\"id\":\"usage_events.v1\",\"kind\":\"collector\"," +
                "\"profile_id\":\"trigger-grade\",\"status\":\"APPLIED\"}]}",
            vector.canonicalJson(),
        )
        assertEquals(Sha256Digest.of(vector.canonicalJson().toByteArray()), vector.conditionDigest)
        assertEquals(vector, AppliedResourceVector(listOf(applied)))
        assertEquals(vector.hashCode(), AppliedResourceVector(listOf(applied)).hashCode())

        val other = AppliedResourceVector(
            listOf(applied.copy(desiredGeneration = ResourceGeneration(4uL))),
        )
        assertNotEquals(vector.conditionDigest, other.conditionDigest)
    }

    @Test
    fun appliedStatusInvariantsAreClosedWorld() {
        AppliedResourceState(key, ResourceGeneration(1uL), null, null, AppliedResourceStatus.INACTIVE, null)
        AppliedResourceState(
            key,
            ResourceGeneration(1uL),
            "trigger-grade",
            null,
            AppliedResourceStatus.OPTIONAL_FAILED,
            "ACCESS_REVOKED",
        )
        assertThrows(IllegalArgumentException::class.java) {
            AppliedResourceState(
                key,
                ResourceGeneration(1uL),
                "trigger-grade",
                null,
                AppliedResourceStatus.APPLIED,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppliedResourceVector(
                listOf(
                    AppliedResourceState(key, ResourceGeneration(1uL), null, null, AppliedResourceStatus.INACTIVE, null),
                    AppliedResourceState(
                        ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1"),
                        ResourceGeneration(1uL),
                        null,
                        null,
                        AppliedResourceStatus.INACTIVE,
                        null,
                    ),
                ),
            )
        }
    }

    @Test
    fun resourceHealthHasExactEvidenceForEveryState() {
        inactiveHealth().requireInactiveMatches(key)
        ResourceHealth(
            key,
            ResourceHealthStatus.PREPARED,
            ResourceGeneration(1uL),
            profile.id,
            profile.expectedSha256,
            null,
            null,
        )
        ResourceHealth(
            key,
            ResourceHealthStatus.APPLIED,
            ResourceGeneration(1uL),
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
            null,
        )
        ResourceHealth(
            key,
            ResourceHealthStatus.SUSPENDED,
            ResourceGeneration(1uL),
            profile.id,
            profile.expectedSha256,
            profile.expectedSha256,
            null,
        )
        val failed = ResourceHealth(
            key,
            ResourceHealthStatus.FAILED,
            ResourceGeneration(1uL),
            profile.id,
            profile.expectedSha256,
            null,
            "NATIVE_FAILURE",
        )
        assertNull(failed.appliedProfileSha256)

        assertThrows(IllegalArgumentException::class.java) {
            ResourceHealth(
                key,
                ResourceHealthStatus.APPLIED,
                ResourceGeneration(1uL),
                profile.id,
                profile.expectedSha256,
                null,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceHealth(key, ResourceHealthStatus.FAILED, null, null, null, null, "not-typed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            inactiveHealth().requireInactiveMatches(
                ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceHealth(
                key,
                ResourceHealthStatus.APPLIED,
                ResourceGeneration(1uL),
                profile.id,
                profile.expectedSha256,
                profile.expectedSha256,
                null,
            ).requireInactiveMatches(key)
        }
    }

    private fun inactiveHealth() = ResourceHealth(
        key,
        ResourceHealthStatus.INACTIVE,
        null,
        null,
        null,
        null,
        null,
    )
}
