package cool.linc.androiddatacollector.core.storage

import cool.linc.androiddatacollector.core.model.RecordedEvent
import cool.linc.androiddatacollector.core.model.StudyMetadata

internal data class AppendRecoveryResult(
    val metadata: StudyMetadata,
    val rewriteMetadata: Boolean,
)

/** Resolves the one-event write-ahead transaction against the durable event tail. */
internal object AppendTransactionRecovery {
    fun recover(
        main: StudyMetadata,
        transaction: StudyMetadata?,
        durableLastSequence: Long,
        durableTail: RecordedEvent?,
    ): AppendRecoveryResult {
        require(durableLastSequence >= 0) { "Invalid durable event boundary" }
        validateLastEvents(main)
        if (transaction == null) {
            require(main.eventCount == durableLastSequence) {
                "Durable event tail has no matching append transaction"
            }
            require(durableTail == null) { "Unexpected recovery tail without a transaction" }
            return AppendRecoveryResult(main, rewriteMetadata = false)
        }

        validateLastEvents(transaction)
        if (transaction.eventCount == main.eventCount) {
            validateImmutableIdentity(main, transaction)
            require(durableLastSequence == main.eventCount) { "Committed transaction boundary mismatch" }
            require(durableTail == null) { "Same-boundary transaction must not carry a recovery tail" }
            return AppendRecoveryResult(main, rewriteMetadata = false)
        }

        require(transaction.eventCount == main.eventCount + 1) {
            "Append transaction is not a one-event successor"
        }
        validateStableMetadata(main, transaction)
        val appended = transaction.lastEvents.values.singleOrNull {
            it.sequenceNumber == transaction.eventCount
        } ?: error("Append transaction does not identify exactly one newest event")
        require(transaction.lastEvents == main.lastEvents + (appended.collectorId to appended)) {
            "Append transaction rewrites unrelated latest events"
        }

        return when (durableLastSequence) {
            main.eventCount -> {
                require(durableTail == null) { "Non-durable transaction has an event tail" }
                AppendRecoveryResult(main, rewriteMetadata = false)
            }
            transaction.eventCount -> {
                require(durableTail == appended) { "Append transaction does not match the durable event tail" }
                AppendRecoveryResult(transaction, rewriteMetadata = true)
            }
            else -> error("Durable event tail is outside the append transaction boundary")
        }
    }

    private fun validateLastEvents(metadata: StudyMetadata) {
        require(metadata.lastEvents.all { (collectorId, event) -> collectorId == event.collectorId }) {
            "Latest-event collector key mismatch"
        }
        if (metadata.eventCount == 0L) {
            require(metadata.lastEvents.isEmpty()) { "Empty metadata has latest events" }
        } else {
            require(metadata.lastEvents.values.count { it.sequenceNumber == metadata.eventCount } == 1) {
                "Metadata does not identify its newest event"
            }
        }
    }

    private fun validateStableMetadata(main: StudyMetadata, transaction: StudyMetadata) {
        validateImmutableIdentity(main, transaction)
        require(transaction.state == main.state) { "Append transaction study state changed" }
        require(transaction.transitions == main.transitions) { "Append transaction transitions changed" }
        require(transaction.uploadedThroughSequence == main.uploadedThroughSequence) {
            "Append transaction upload watermark changed"
        }
        require(transaction.retainedFromSequence == main.retainedFromSequence) {
            "Append transaction retained floor changed"
        }
    }

    private fun validateImmutableIdentity(main: StudyMetadata, transaction: StudyMetadata) {
        require(transaction.experimentId == main.experimentId) { "Append transaction experiment changed" }
        require(transaction.configurationId == main.configurationId) { "Append transaction configuration changed" }
        require(transaction.participantInstanceId == main.participantInstanceId) {
            "Append transaction participant changed"
        }
        require(transaction.assignedParticipantId == main.assignedParticipantId) {
            "Append transaction assignment changed"
        }
    }

}
