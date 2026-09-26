package cool.jacoblin.particeps.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDocumentSuccessorTest {
    private val current = RuntimeDocument.initial(
        experimentId = "experiment-one",
        configurationId = "configuration-one",
        configurationSha256 = "a".repeat(64),
        activityTokenKeyBase64Url = "A".repeat(43),
        participantInstanceId = "123e4567-e89b-42d3-a456-426614174001",
    ).copy(
        components = sortedMapOf(
            key(RuntimeComponentKind.AUTOMATION_CHECKPOINT, "main") to "checkpoint-1",
            key(RuntimeComponentKind.TIMER, "kept") to "timer-kept",
            key(RuntimeComponentKind.TIMER, "retired") to "timer-retired",
            key(RuntimeComponentKind.RESOURCE, "collector:battery_state.v1") to "resource-1",
        ),
    )
    private val commit = EngineCommit(
        commitSequence = 1,
        previousCommitSha256 = GENESIS_DIGEST,
        inputKind = EngineInputKind.SOURCE_OBSERVATION,
        consumedPendingInputSha256 = null,
        sourceObservations = emptyList(),
        events = emptyList(),
        mutations = listOf(
            RuntimeMutation(key(RuntimeComponentKind.AUTOMATION_CHECKPOINT, "main"), RuntimeMutationOperation.UPSERT, "checkpoint-2"),
            RuntimeMutation(key(RuntimeComponentKind.TIMER, "retired"), RuntimeMutationOperation.REMOVE, null),
            RuntimeMutation(key(RuntimeComponentKind.TIMER, "scheduled"), RuntimeMutationOperation.UPSERT, "timer-new"),
            RuntimeMutation(key(RuntimeComponentKind.TIMER, "absent"), RuntimeMutationOperation.REMOVE, null),
        ),
        committedAt = ResearchTime(1_000, 2_000, "boot-a"),
        successorProjection = current.projection().copy(
            state = ExperimentState.CONFIG_VERIFIED,
            revision = 1,
            nextCommitSequence = 2,
            lifetimeDataEventCount = 3,
            evaluatedThroughCommit = 1,
        ),
        resultingCheckpointSha256 = "1".repeat(64),
        commitSha256 = GENESIS_DIGEST,
    ).withComputedDigest()
    private val exact = current.advance(commit)

    @Test
    fun theExactSuccessorIsAcceptedWhateverItsComponentMapType() {
        assertAgrees(exact, expected = true)
        assertAgrees(exact.copy(components = HashMap(exact.components)), expected = true)
        assertAgrees(exact.copy(components = LinkedHashMap(java.util.TreeMap(exact.components).descendingMap())), expected = true)
    }

    @Test
    fun everyDifferenceFromTheAdvancedDocumentIsRejected() {
        val components = exact.components
        listOf(
            exact.copy(components = components + (key(RuntimeComponentKind.TIMER, "extra") to "timer-extra")),
            exact.copy(components = components - key(RuntimeComponentKind.TIMER, "kept")),
            exact.copy(components = components + (key(RuntimeComponentKind.TIMER, "kept") to "timer-changed")),
            exact.copy(components = components + (key(RuntimeComponentKind.TIMER, "retired") to "timer-retired")),
            exact.copy(components = components + (key(RuntimeComponentKind.TIMER, "scheduled") to "timer-other")),
            exact.copy(components = components - key(RuntimeComponentKind.TIMER, "scheduled")),
            exact.copy(components = components + (key(RuntimeComponentKind.AUTOMATION_CHECKPOINT, "main") to "checkpoint-1")),
            exact.copy(
                components = (components - key(RuntimeComponentKind.TIMER, "kept")) +
                    (key(RuntimeComponentKind.TIMER, "retired") to "timer-retired"),
            ),
            exact.copy(
                components = (components - key(RuntimeComponentKind.TIMER, "kept")) +
                    (key(RuntimeComponentKind.TIMER, "extra") to "timer-kept"),
            ),
            exact.copy(state = ExperimentState.CONSENT_PENDING),
            exact.copy(lifetimeDataEventCount = 4),
            exact.copy(lastCommitSha256 = "f".repeat(64)),
            exact.copy(participantInstanceId = "123e4567-e89b-42d3-a456-426614174002"),
            exact.copy(assignedParticipantId = "participant-1"),
            exact.copy(activityTokenKeyBase64Url = "B".repeat(43)),
            exact.copy(configurationSha256 = "b".repeat(64)),
            exact.copy(sourceCheckpoints = mapOf(EventSourceId("battery_state.v1") to SourceCheckpoint(EventSourceId("battery_state.v1"), 1, 1, null, null))),
        ).forEach { candidate -> assertAgrees(candidate, expected = false) }
    }

    @Test
    fun aCommitThatDoesNotFollowTheRuntimeIsRefusedLikeAdvance() {
        val unrelated = current.copy(lastCommitSha256 = "e".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { unrelated.advance(commit) }
        assertThrows(IllegalArgumentException::class.java) { unrelated.advancesTo(commit, exact) }
    }

    private fun assertAgrees(candidate: RuntimeDocument, expected: Boolean) {
        assertEquals(expected, candidate == current.advance(commit))
        if (expected) assertTrue(current.advancesTo(commit, candidate)) else assertFalse(current.advancesTo(commit, candidate))
    }

    private fun key(kind: RuntimeComponentKind, id: String) = RuntimeComponentKey(kind, id)
}
