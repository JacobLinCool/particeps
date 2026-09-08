package cool.jacoblin.particeps.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCheckpointPolicyTest {
    @Test
    fun countBoundRetriesUntilAWriteIsAcknowledged() {
        val policy = SnapshotCheckpointPolicy(maximumCommits = 3, maximumFrameBytes = 100)
        assertFalse(policy.recordAppend(1, force = false))
        assertFalse(policy.recordAppend(1, force = false))
        assertTrue(policy.recordAppend(1, force = false))
        assertTrue(policy.recordAppend(1, force = false))
        policy.checkpointAcknowledged()
        assertFalse(policy.recordAppend(1, force = false))
    }

    @Test
    fun byteBudgetAndLifecycleForceCheckpointBeforeCountBound() {
        val policy = SnapshotCheckpointPolicy(maximumCommits = 100, maximumFrameBytes = 10)
        assertFalse(policy.recordAppend(9, force = false))
        assertTrue(policy.recordAppend(1, force = false))
        policy.checkpointAcknowledged()
        assertTrue(policy.recordAppend(1, force = true))
        policy.checkpointAcknowledged()
        assertFalse(policy.recordAppend(1, force = false))
    }

    @Test
    fun failedForcedCheckpointRetriesBeforeReachingEitherBudget() {
        val policy = SnapshotCheckpointPolicy(maximumCommits = 100, maximumFrameBytes = 100)
        assertTrue(policy.recordAppend(1, force = true))
        assertTrue(policy.recordAppend(1, force = false))
        policy.checkpointAcknowledged()
        assertFalse(policy.recordAppend(1, force = false))
    }
}
