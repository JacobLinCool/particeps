package cool.jacoblin.particeps.core.storage

import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentTransition
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason

internal data class AppendRecoveryResult(
    val metadata: StudyMetadata,
    val rewriteMetadata: Boolean,
    val failureResolutionRequired: Boolean,
)

/**
 * Resolves a pre-armed one-event transaction against the durable event tail.
 *
 * The transaction is deliberately the fail-closed successor: it already contains the proposed
 * event/metadata side effects and a final `RUNNING -> PAUSED / STORAGE_FAILURE` transition. A
 * process death anywhere after this journal is acknowledged therefore recovers PAUSED, whether the
 * event was absent, truncated, or fully durable. Only retiring the journal after the main successor
 * commit lets a later process regard that append as an ordinary RUNNING commit.
 */
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
            return AppendRecoveryResult(
                metadata = main,
                rewriteMetadata = false,
                failureResolutionRequired = false,
            )
        }

        val failureTransition = validateFailureTransaction(transaction)
        val successor = transaction.copy(
            state = ExperimentState.RUNNING,
            transitions = transaction.transitions.dropLast(1),
        )
        validateLastEvents(successor)
        val appended = successor.lastEvents.values.singleOrNull {
            it.sequenceNumber == successor.eventCount
        } ?: error("Append transaction does not identify exactly one newest event")

        return when (main.eventCount) {
            successor.eventCount - 1 -> recoverAcrossEventBoundary(
                main = main,
                successor = successor,
                failClosedSuccessor = transaction,
                failureTransition = failureTransition,
                appended = appended,
                durableLastSequence = durableLastSequence,
                durableTail = durableTail,
            )
            successor.eventCount -> recoverAtCommittedBoundary(
                main = main,
                successor = successor,
                failClosedSuccessor = transaction,
                appended = appended,
                durableLastSequence = durableLastSequence,
                durableTail = durableTail,
            )
            else -> error("Append transaction is outside the main metadata boundary")
        }
    }

    private fun recoverAcrossEventBoundary(
        main: StudyMetadata,
        successor: StudyMetadata,
        failClosedSuccessor: StudyMetadata,
        failureTransition: ExperimentTransition,
        appended: RecordedEvent,
        durableLastSequence: Long,
        durableTail: RecordedEvent?,
    ): AppendRecoveryResult {
        val runningMain = runningBoundaryBeforePause(main)
        validateStableMetadata(runningMain, successor)
        require(successor.lastEvents == runningMain.lastEvents + (appended.collectorId to appended)) {
            "Append transaction rewrites unrelated latest events"
        }

        val recovered = when (durableLastSequence) {
            runningMain.eventCount -> {
                require(durableTail == null) { "Non-durable transaction has an event tail" }
                if (main.state == ExperimentState.PAUSED) {
                    main
                } else {
                    main.copy(
                        state = ExperimentState.PAUSED,
                        transitions = main.transitions + failureTransition,
                    )
                }
            }
            successor.eventCount -> {
                require(durableTail == appended) { "Append transaction does not match the durable event tail" }
                if (main.state == ExperimentState.PAUSED) {
                    successor.copy(
                        state = ExperimentState.PAUSED,
                        transitions = main.transitions,
                    )
                } else {
                    failClosedSuccessor
                }
            }
            else -> error("Durable event tail is outside the append transaction boundary")
        }
        return AppendRecoveryResult(
            metadata = recovered,
            rewriteMetadata = recovered != main,
            failureResolutionRequired = true,
        )
    }

    private fun recoverAtCommittedBoundary(
        main: StudyMetadata,
        successor: StudyMetadata,
        failClosedSuccessor: StudyMetadata,
        appended: RecordedEvent,
        durableLastSequence: Long,
        durableTail: RecordedEvent?,
    ): AppendRecoveryResult {
        validateImmutableIdentity(main, successor)
        require(main.nextSequenceNumber == successor.nextSequenceNumber) {
            "Committed append transaction next sequence changed"
        }
        require(main.lastEvents == successor.lastEvents) {
            "Committed append transaction latest events changed"
        }
        require(durableLastSequence == successor.eventCount) { "Committed transaction boundary mismatch" }
        require(durableTail == appended) { "Committed transaction does not match the durable event tail" }

        val unresolvedFailure = main == successor || main == failClosedSuccessor
        val recovered = if (unresolvedFailure) {
            failClosedSuccessor
        } else {
            main // A later durable mutation proves the append returned and the journal is stale.
        }
        return AppendRecoveryResult(
            metadata = recovered,
            rewriteMetadata = recovered != main,
            failureResolutionRequired = unresolvedFailure,
        )
    }

    private fun validateFailureTransaction(transaction: StudyMetadata): ExperimentTransition {
        validateLastEvents(transaction)
        require(transaction.state == ExperimentState.PAUSED) { "Append transaction is not fail-closed" }
        val transition = transaction.transitions.lastOrNull()
            ?: error("Append transaction has no fail-closed transition")
        require(
            transition.from == ExperimentState.RUNNING &&
                transition.to == ExperimentState.PAUSED &&
                transition.reason == TransitionReason.STORAGE_FAILURE,
        ) { "Append transaction has an invalid fail-closed transition" }
        return transition
    }

    private fun runningBoundaryBeforePause(metadata: StudyMetadata): StudyMetadata = when (metadata.state) {
        ExperimentState.RUNNING -> metadata
        ExperimentState.PAUSED -> {
            val transition = metadata.transitions.lastOrNull()
                ?: error("Paused main metadata has no transition")
            require(transition.from == ExperimentState.RUNNING && transition.to == ExperimentState.PAUSED) {
                "Paused main metadata is not the append failure boundary"
            }
            metadata.copy(
                state = ExperimentState.RUNNING,
                transitions = metadata.transitions.dropLast(1),
            )
        }
        else -> error("Append transaction main metadata is not active")
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
