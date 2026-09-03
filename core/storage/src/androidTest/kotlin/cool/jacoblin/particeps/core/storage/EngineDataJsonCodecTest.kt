package cool.jacoblin.particeps.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineInputKind
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.GENESIS_DIGEST
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeComponentKey
import cool.jacoblin.particeps.core.model.RuntimeComponentKind
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeMutationOperation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.withComputedDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineDataJsonCodecTest {
    @Test
    fun exactRuntimeAndCommitShapesRoundTrip() {
        val runtime = initialRuntime()
        val commit = EngineCommit(
            commitSequence = 1,
            previousCommitSha256 = GENESIS_DIGEST,
            inputKind = EngineInputKind.LIFECYCLE_COMMAND,
            consumedPendingInputSha256 = null,
            sourceObservations = emptyList(),
            events = emptyList(),
            mutations = listOf(
                RuntimeMutation(
                    RuntimeComponentKey(RuntimeComponentKind.RESOURCE_AUDIT_TIMER, "a".repeat(64)),
                    RuntimeMutationOperation.UPSERT,
                    "durable-timer-v1:AA",
                ),
            ),
            committedAt = TIME,
            successorProjection = RuntimeProjection(
                state = ExperimentState.CONFIG_VERIFIED,
                revision = 1,
                nextCommitSequence = 2,
                nextObservationSequence = 1,
                nextEventSequence = 1,
                sourceCheckpoints = emptyMap(),
                clockCheckpoint = CLOCK,
                activeConditionEpoch = null,
                lifetimeDataEventCount = 0,
                uploadedThroughCommit = 0,
                evaluatedThroughCommit = 1,
                retainedFromCommit = 1,
            ),
            resultingCheckpointSha256 = "1".repeat(64),
            commitSha256 = GENESIS_DIGEST,
        ).withComputedDigest()

        assertEquals(runtime, EngineDataJsonCodec.decodeRuntime(EngineDataJsonCodec.encodeRuntime(runtime)))
        assertEquals(commit, EngineDataJsonCodec.decodeCommit(EngineDataJsonCodec.encodeCommit(commit)))

        val missingZone = JSONObject(EngineDataJsonCodec.encodeCommit(commit).toString(Charsets.UTF_8))
        missingZone.getJSONObject("successor_projection").getJSONObject("clock_checkpoint").remove("zone_id")
        assertThrows(IllegalArgumentException::class.java) {
            EngineDataJsonCodec.decodeCommit(missingZone.toString().toByteArray())
        }

        val invalidZone = JSONObject(EngineDataJsonCodec.encodeCommit(commit).toString(Charsets.UTF_8))
        invalidZone.getJSONObject("successor_projection").getJSONObject("clock_checkpoint")
            .put("zone_id", "GMT+08:00")
        assertThrows(IllegalArgumentException::class.java) {
            EngineDataJsonCodec.decodeCommit(invalidZone.toString().toByteArray())
        }

        val retiredRandomSelectionComponent = JSONObject(
            EngineDataJsonCodec.encodeCommit(commit).toString(Charsets.UTF_8),
        )
        retiredRandomSelectionComponent.getJSONArray("mutations").getJSONObject(0)
            .put("component_kind", "RANDOM_SELECTION")
        assertThrows(IllegalArgumentException::class.java) {
            EngineDataJsonCodec.decodeCommit(retiredRandomSelectionComponent.toString().toByteArray())
        }
    }

    @Test
    fun retiredOrUnknownShapeIsRejected() {
        val encoded = JSONObject(EngineDataJsonCodec.encodeRuntime(initialRuntime()).toString(Charsets.UTF_8))
            .put("collector_id", "legacy.v1")
            .toString()
            .toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            EngineDataJsonCodec.decodeRuntime(encoded)
        }
    }

    private fun initialRuntime() = RuntimeDocument.initial(
        experimentId = "study-001",
        configurationId = "config-001",
        configurationSha256 = "a".repeat(64),
        activityTokenKeyBase64Url = "A".repeat(43),
        participantInstanceId = "018f3ca4-7a82-4f47-8b5c-a4415b9b2290",
    )

    private companion object {
        val TIME = ResearchTime(1_000, 2_000, "boot-a")
        val CLOCK = StudyClockCheckpoint(2_000, 1_000, TIME, 9_000, true, "UTC")
    }
}
