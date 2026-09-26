package cool.jacoblin.particeps.core.model

import java.security.MessageDigest

/** Canonical, language-neutral preimages for the authenticated runtime log. */
object EngineCommitIntegrity {
    const val FORMAT = "particeps-engine-commit-v1"
    const val PENDING_FORMAT = "particeps-pending-input-v1"

    /** The digest of [commit]'s content, hashed at most once per commit value. */
    fun calculate(commit: EngineCommit): String =
        commit.contentSha256 ?: calculateContent(commit).also { commit.contentSha256 = it }

    /** The digest of [input]'s content, hashed at most once per pending-input value. */
    fun calculate(input: PendingEngineInput): String =
        input.contentSha256 ?: calculateContent(input).also { input.contentSha256 = it }

    private fun calculateContent(commit: EngineCommit): String = digest {
        string(FORMAT)
        long(commit.commitSequence)
        string(commit.previousCommitSha256)
        enum(commit.inputKind)
        nullable(commit.consumedPendingInputSha256) { string(it) }
        list(commit.sourceObservations) { observation(it) }
        list(commit.events) { event(it) }
        list(commit.mutations) { mutation(it) }
        time(commit.committedAt)
        projection(commit.successorProjection)
        string(commit.resultingCheckpointSha256)
    }

    private fun calculateContent(input: PendingEngineInput): String = digest {
        string(PENDING_FORMAT)
        string(input.conditionEpochId.value)
        list(input.submissions) { submission ->
            sourceId(submission.sourceId)
            int(submission.schemaVersion)
            long(submission.resourceGeneration)
            long(submission.producerOrdinal)
            enum(submission.admissionKind)
            list(submission.events) { draft(it) }
            nullable(submission.coverage) { coverage(it) }
        }
        time(input.stagedAt)
    }

    fun verify(commit: EngineCommit) {
        require(commit.commitSha256 == calculate(commit)) { "Engine commit digest mismatch" }
    }

    fun verify(input: PendingEngineInput) {
        require(input.encodedSha256 == calculate(input)) { "Pending input digest mismatch" }
    }

    private fun digest(block: CanonicalWriter.() -> Unit): String {
        val writer = CanonicalWriter(MessageDigest.getInstance("SHA-256"))
        writer.block()
        return writer.finish().toLowerHex()
    }

    /**
     * Writes the preimage in `DataOutputStream` big-endian layout straight into the digest through
     * a small buffer, so a large commit is neither copied into a growing array nor held in memory.
     */
    private class CanonicalWriter(private val sha256: MessageDigest) {
        private val buffer = ByteArray(BUFFER_BYTES)
        private var buffered = 0

        fun int(value: Int) {
            reserve(Int.SIZE_BYTES)
            buffer[buffered] = (value ushr 24).toByte()
            buffer[buffered + 1] = (value ushr 16).toByte()
            buffer[buffered + 2] = (value ushr 8).toByte()
            buffer[buffered + 3] = value.toByte()
            buffered += Int.SIZE_BYTES
        }

        fun long(value: Long) {
            int((value ushr 32).toInt())
            int(value.toInt())
        }

        fun boolean(value: Boolean) {
            reserve(1)
            buffer[buffered++] = if (value) 1 else 0
        }

        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_CANONICAL_STRING_BYTES) { "Canonical string is too large" }
            int(bytes.size)
            if (bytes.size > buffer.size - buffered) {
                drain()
                if (bytes.size >= buffer.size) {
                    sha256.update(bytes)
                    return
                }
            }
            bytes.copyInto(buffer, buffered)
            buffered += bytes.size
        }

        fun finish(): ByteArray {
            drain()
            return sha256.digest()
        }

        private fun reserve(bytes: Int) {
            if (buffer.size - buffered < bytes) drain()
        }

        private fun drain() {
            sha256.update(buffer, 0, buffered)
            buffered = 0
        }

        fun <T : Enum<T>> enum(value: T) = string(value.name)

        fun <T> list(values: List<T>, encode: CanonicalWriter.(T) -> Unit) {
            int(values.size)
            values.forEach { encode(it) }
        }

        fun <T> nullable(value: T?, encode: CanonicalWriter.(T) -> Unit) {
            boolean(value != null)
            if (value != null) encode(value)
        }

        fun sourceId(value: EventSourceId) = string(value.value)

        fun type(value: EventTypeKey) {
            sourceId(value.sourceId)
            int(value.schemaVersion)
            string(value.eventType)
        }

        fun time(value: ResearchTime) {
            long(value.wallTimeUtcMillis)
            long(value.elapsedRealtimeNanos)
            string(value.bootSessionId)
        }

        fun fields(values: Map<String, String>) {
            val sorted = values.inKeyOrder()
            int(sorted.size)
            sorted.forEach { (key, value) ->
                string(key)
                string(value)
            }
        }

        fun coverage(value: SourceCoverage) {
            enum(value.clockBasis)
            string(value.startInclusive)
            string(value.endExclusive)
        }

        fun observation(value: SourceObservation) {
            long(value.observationSequence)
            sourceId(value.sourceId)
            int(value.schemaVersion)
            long(value.resourceGeneration)
            enum(value.admissionKind)
            long(value.producerOrdinal)
            string(value.conditionEpochId.value)
            int(value.eventCount)
            nullable(value.firstEventSequence) { long(it) }
            nullable(value.lastEventSequence) { long(it) }
            nullable(value.coverage) { coverage(it) }
            string(value.encodedSha256)
        }

        fun event(value: RecordedEvent) {
            long(value.sequenceNumber)
            type(value.type)
            time(value.observedTime)
            nullable(value.conditionEpochId) { string(it.value) }
            fields(value.fields)
        }

        fun draft(value: EventDraft) {
            type(value.type)
            time(value.observedTime)
            fields(value.fields)
        }

        fun mutation(value: RuntimeMutation) {
            enum(value.key.kind)
            string(value.key.id)
            enum(value.operation)
            nullable(value.canonicalValue) { string(it) }
        }

        fun sourceCheckpoint(value: SourceCheckpoint) {
            sourceId(value.sourceId)
            long(value.resourceGeneration)
            long(value.nextProducerOrdinal)
            nullable(value.coverage) { coverage(it) }
            nullable(value.cursor) { string(it) }
        }

        fun clock(value: StudyClockCheckpoint) {
            long(value.calendarElapsedNanos)
            long(value.activeRunningElapsedNanos)
            time(value.anchor)
            long(value.deadlineUtcMillis)
            boolean(value.deadlineUtcTrusted)
            string(value.zoneId)
        }

        fun epoch(value: ConditionEpoch) {
            string(value.id.value)
            string(value.configurationSha256)
            string(value.appliedResourceVectorSha256)
            time(value.activatedAt)
        }

        fun projection(value: RuntimeProjection) {
            enum(value.state)
            long(value.revision)
            long(value.nextCommitSequence)
            long(value.nextObservationSequence)
            long(value.nextEventSequence)
            val checkpoints = value.sourceCheckpoints.toSortedMap()
            int(checkpoints.size)
            checkpoints.forEach { (key, checkpoint) ->
                sourceId(key)
                sourceCheckpoint(checkpoint)
            }
            nullable(value.clockCheckpoint) { clock(it) }
            nullable(value.activeConditionEpoch) { epoch(it) }
            long(value.lifetimeDataEventCount)
            long(value.uploadedThroughCommit)
            long(value.evaluatedThroughCommit)
            long(value.retainedFromCommit)
        }
    }

    private const val MAX_CANONICAL_STRING_BYTES = 8 * 1024 * 1024
    private const val BUFFER_BYTES = 8 * 1024
}

/**
 * The digest preimage excludes the digest field itself, so the copy carries exactly the digest of
 * its own content, and keeps that digest as its computed content digest. Stores still verify every
 * commit and pending input they are asked to persist; for this copy that is a comparison.
 */
fun EngineCommit.withComputedDigest(): EngineCommit {
    val digest = EngineCommitIntegrity.calculate(this)
    return copy(commitSha256 = digest).also { it.contentSha256 = digest }
}

fun PendingEngineInput.withComputedDigest(): PendingEngineInput {
    val digest = EngineCommitIntegrity.calculate(this)
    return copy(encodedSha256 = digest).also { it.contentSha256 = digest }
}
