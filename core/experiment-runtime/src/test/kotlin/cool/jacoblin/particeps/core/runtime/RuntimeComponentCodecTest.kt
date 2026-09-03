package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ResourceGeneration
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import cool.jacoblin.particeps.core.resource.Sha256Digest
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeComponentCodecTest {
    @Test
    fun openedSurveyActionRoundTripsWithoutLosingItsCausalTimes() {
        val requested = ResearchTime(1_800_000_000_000, 1_000_000, "boot-one")
        val opened = ResearchTime(1_800_000_001_000, 1_001_000_000, "boot-one")
        val action = DurableActionInvocation(
            actionId = "a".repeat(64),
            automationId = "survey-automation",
            interventionId = "survey-intervention",
            causalSequence = 42,
            logicalDeadlineUtcMillis = 1_800_000_000_500,
            expiresAtUtcMillis = 1_800_000_300_500,
            conditionSha256 = "b".repeat(64),
            generation = 1uL,
            requestedAt = requested,
            openedAt = opened,
            state = RuntimeActionState.OPENED,
            failureReason = null,
        )

        assertEquals(action, RuntimeComponentCodec.decodeAction(RuntimeComponentCodec.encodeAction(action)))
    }

    @Test
    fun uploadAcknowledgementRoundTripsAsOneExactCommitRange() {
        val acknowledgement = DurableUploadAcknowledgement(
            bundleId = "123e4567-e89b-42d3-a456-426614174099",
            firstCommit = 5,
            throughCommit = 12,
            bundleSha256 = "c".repeat(64),
            acknowledgedAt = ResearchTime(1_800_000_000_000, 1_000_000, "boot-one"),
        )

        assertEquals(
            acknowledgement,
            RuntimeComponentCodec.decodeUploadAcknowledgement(
                RuntimeComponentCodec.encodeUploadAcknowledgement(acknowledgement),
            ),
        )
    }

    @Test
    fun resourceCleanupRoundTripsItsAttemptedSignedIdentity() {
        val cleanup = DurableResourceCleanup(
            key = ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1"),
            generation = ResourceGeneration(7uL),
            profileId = "slow-network",
            expectedProfileSha256 = Sha256Digest("d".repeat(64)),
        )

        assertEquals(
            cleanup,
            RuntimeComponentCodec.decodeResourceCleanup(
                RuntimeComponentCodec.encodeResourceCleanup(cleanup),
            ),
        )
    }
}
