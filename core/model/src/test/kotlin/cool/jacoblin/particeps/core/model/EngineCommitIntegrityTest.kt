package cool.jacoblin.particeps.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineCommitIntegrityTest {
    @Test
    fun randomSelectionIsAnEngineInputAndNotADurableComponent() {
        assertEquals(
            listOf(
                RuntimeComponentKind.AUTOMATION_CHECKPOINT,
                RuntimeComponentKind.TIMER,
                RuntimeComponentKind.STUDY_DEADLINE_TIMER,
                RuntimeComponentKind.RESOURCE_AUDIT_TIMER,
                RuntimeComponentKind.ACTION_INVOCATION,
                RuntimeComponentKind.UPLOAD_ACKNOWLEDGEMENT,
                RuntimeComponentKind.RESOURCE,
                RuntimeComponentKind.RESOURCE_CLEANUP,
            ),
            RuntimeComponentKind.entries,
        )
        assertEquals(EngineInputKind.RANDOM_SELECTION, EngineInputKind.valueOf("RANDOM_SELECTION"))
    }

    @Test
    fun digestIsIndependentOfMapInsertionOrderAndBindsEveryMutation() {
        val left = commit(
            mapOf(
                EventSourceId("usage_events.v1") to checkpoint("usage_events.v1", "b"),
                EventSourceId("network_usage.v1") to checkpoint("network_usage.v1", "a"),
            ),
        )
        val right = commit(
            linkedMapOf(
                EventSourceId("network_usage.v1") to checkpoint("network_usage.v1", "a"),
                EventSourceId("usage_events.v1") to checkpoint("usage_events.v1", "b"),
            ),
        )

        assertEquals(EngineCommitIntegrity.calculate(left), EngineCommitIntegrity.calculate(right))
        assertNotEquals(
            EngineCommitIntegrity.calculate(left),
            EngineCommitIntegrity.calculate(
                left.copy(mutations = left.mutations.map { it.copy(canonicalValue = "{\"state\":2}") }),
            ),
        )
    }

    @Test
    fun verifyRejectsACommitWhoseAuthenticatedContentChanged() {
        val sealed = commit(emptyMap()).withComputedDigest()
        EngineCommitIntegrity.verify(sealed)

        assertThrows(IllegalArgumentException::class.java) {
            EngineCommitIntegrity.verify(sealed.copy(inputKind = EngineInputKind.TIMER_WAKE))
        }
    }

    @Test
    fun onlyTheSealedCopyReusesItsComputedDigest() {
        val draft = commit(emptyMap())
        val sealed = draft.withComputedDigest()
        EngineCommitIntegrity.verify(sealed)

        // Any other value is hashed again: a changed digest or a changed field is still refused.
        assertThrows(IllegalArgumentException::class.java) {
            EngineCommitIntegrity.verify(sealed.copy(commitSha256 = "f".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineCommitIntegrity.verify(draft.copy(commitSha256 = "f".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineCommitIntegrity.verify(sealed.copy(committedAt = ResearchTime(1_001, 2_000, "boot-a")))
        }
        assertEquals(sealed.commitSha256, EngineCommitIntegrity.calculate(sealed.copy()))
        assertEquals(sealed, draft.copy(commitSha256 = sealed.commitSha256))
    }

    @Test
    fun pendingInputReusesOnlyItsOwnComputedDigest() {
        val draft = PendingEngineInput(
            conditionEpochId = ConditionEpochId("123e4567-e89b-42d3-a456-426614174010"),
            submissions = listOf(
                PendingSourceSubmission(
                    sourceId = EventSourceId("battery_state.v1"),
                    schemaVersion = 1,
                    resourceGeneration = 1,
                    producerOrdinal = 0,
                    admissionKind = ObservationAdmissionKind.NORMAL,
                    events = listOf(
                        EventDraft(
                            EventTypeKey(EventSourceId("battery_state.v1"), 1, "BATTERY_STATE"),
                            ResearchTime(1_000, 2_000, "boot-a"),
                            mapOf("percentage" to "42"),
                        ),
                    ),
                    coverage = null,
                ),
            ),
            stagedAt = ResearchTime(1_000, 2_000, "boot-a"),
            encodedSha256 = GENESIS_DIGEST,
        )
        val sealed = draft.withComputedDigest()
        EngineCommitIntegrity.verify(sealed)

        assertThrows(IllegalArgumentException::class.java) {
            EngineCommitIntegrity.verify(sealed.copy(encodedSha256 = "f".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineCommitIntegrity.verify(sealed.copy(stagedAt = ResearchTime(1_001, 2_000, "boot-a")))
        }
        assertEquals(sealed.encodedSha256, EngineCommitIntegrity.calculate(sealed.copy()))
    }

    @Test
    fun clockZoneIsCanonicalAndBoundIntoCommitIntegrity() {
        assertThrows(IllegalArgumentException::class.java) {
            StudyClockCheckpoint(2_000, 1_000, ResearchTime(1_000, 2_000, "boot-a"), 9_000, true, "GMT+08:00")
        }
        val utc = commit(emptyMap(), "UTC")
        val taipei = commit(emptyMap(), "Asia/Taipei")
        assertNotEquals(EngineCommitIntegrity.calculate(utc), EngineCommitIntegrity.calculate(taipei))
    }

    private fun commit(
        checkpoints: Map<EventSourceId, SourceCheckpoint>,
        zoneId: String = "UTC",
    ) = EngineCommit(
        commitSequence = 1,
        previousCommitSha256 = GENESIS_DIGEST,
        inputKind = EngineInputKind.LIFECYCLE_COMMAND,
        consumedPendingInputSha256 = null,
        sourceObservations = emptyList(),
        events = emptyList(),
        mutations = listOf(
            RuntimeMutation(
                RuntimeComponentKey(RuntimeComponentKind.AUTOMATION_CHECKPOINT, "automation"),
                RuntimeMutationOperation.UPSERT,
                "{\"state\":1}",
            ),
        ),
        committedAt = ResearchTime(1_000, 2_000, "boot-a"),
        successorProjection = RuntimeProjection(
            state = ExperimentState.CONFIG_VERIFIED,
            revision = 1,
            nextCommitSequence = 2,
            nextObservationSequence = 1,
            nextEventSequence = 1,
            sourceCheckpoints = checkpoints,
            clockCheckpoint = StudyClockCheckpoint(
                calendarElapsedNanos = 2_000,
                activeRunningElapsedNanos = 1_000,
                anchor = ResearchTime(1_000, 2_000, "boot-a"),
                deadlineUtcMillis = 9_000,
                deadlineUtcTrusted = true,
                zoneId = zoneId,
            ),
            activeConditionEpoch = null,
            lifetimeDataEventCount = 0,
            uploadedThroughCommit = 0,
            evaluatedThroughCommit = 1,
            retainedFromCommit = 1,
        ),
        resultingCheckpointSha256 = "1".repeat(64),
        commitSha256 = GENESIS_DIGEST,
    )

    private fun checkpoint(sourceId: String, cursor: String) = SourceCheckpoint(
        sourceId = EventSourceId(sourceId),
        resourceGeneration = 1,
        nextProducerOrdinal = 1,
        coverage = null,
        cursor = cursor,
    )
}
