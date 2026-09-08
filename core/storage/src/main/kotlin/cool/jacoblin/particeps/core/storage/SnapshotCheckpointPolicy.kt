package cool.jacoblin.particeps.core.storage

/** Recovery-cache budget, advanced only by acknowledged log appends; it creates no wakeups. */
internal class SnapshotCheckpointPolicy(
    private val maximumCommits: Int = 64,
    private val maximumFrameBytes: Long = 1024L * 1024,
) {
    private var commits = 0
    private var frameBytes = 0L
    private var checkpointDue = false

    init {
        require(maximumCommits > 0 && maximumFrameBytes > 0)
    }

    fun recordAppend(bytes: Int, force: Boolean): Boolean {
        require(bytes > 0)
        // Saturate after a failed cache write so every subsequent append retries the checkpoint.
        if (commits < maximumCommits) commits++
        frameBytes += minOf(bytes.toLong(), maximumFrameBytes - frameBytes)
        checkpointDue = checkpointDue || force || commits >= maximumCommits || frameBytes >= maximumFrameBytes
        return checkpointDue
    }

    /** Reset only after the snapshot replacement has been durably acknowledged. */
    fun checkpointAcknowledged() {
        commits = 0
        frameBytes = 0L
        checkpointDue = false
    }
}
