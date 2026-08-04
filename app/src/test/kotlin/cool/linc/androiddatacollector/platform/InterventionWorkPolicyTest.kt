package cool.linc.androiddatacollector.platform

import cool.linc.androiddatacollector.core.definition.NotificationAction
import cool.linc.androiddatacollector.core.model.InterventionOccurrence
import cool.linc.androiddatacollector.core.model.OccurrenceState
import cool.linc.androiddatacollector.core.model.ResearchTime
import cool.linc.androiddatacollector.core.runtime.OccurrenceClaimResult
import cool.linc.androiddatacollector.core.runtime.OccurrenceDispatch
import cool.linc.androiddatacollector.core.runtime.OccurrenceExpiryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InterventionWorkPolicyTest {
    @Test
    fun deliveryAndExpiryUseIndependentUniqueNamesAndCancellationTags() {
        val experimentId = "experiment-one"
        val occurrenceId = "a".repeat(64)

        assertNotEquals(
            InterventionWorkIdentity.deliveryName(experimentId, occurrenceId),
            InterventionWorkIdentity.expiryName(experimentId, occurrenceId),
        )
        assertNotEquals(
            InterventionWorkIdentity.deliveryTag(experimentId),
            InterventionWorkIdentity.expiryTag(experimentId),
        )
        assertEquals(
            "${InterventionWorkIdentity.deliveryName(experimentId, occurrenceId)}-expiry",
            InterventionWorkIdentity.expiryName(experimentId, occurrenceId),
        )
    }

    @Test
    fun earlyWorkersRetryWhileDueOrTerminalResultsComplete() {
        val dispatch = OccurrenceDispatch(
            InterventionOccurrence(
                occurrenceId = "b".repeat(64),
                interventionId = "notice-one",
                triggerId = "after-minute",
                scheduleKey = "relative:1",
                scheduledFor = ResearchTime(1_000, 1_000, "boot-test"),
                expiresAtUtcMillis = 2_000,
                state = OccurrenceState.POSTING,
            ),
            NotificationAction("Check in", "Open the study."),
        )

        assertEquals(
            DeliveryWorkerDirective.DELIVER,
            deliveryWorkerDirective(OccurrenceClaimResult.Due(dispatch)),
        )
        assertEquals(
            DeliveryWorkerDirective.RETRY,
            deliveryWorkerDirective(OccurrenceClaimResult.NotDue(1)),
        )
        assertEquals(
            DeliveryWorkerDirective.RECOVER_SUCCESSOR,
            deliveryWorkerDirective(OccurrenceClaimResult.Expired),
        )
        assertEquals(
            ExpiryWorkerDirective.RETRY,
            expiryWorkerDirective(OccurrenceExpiryResult.NotDue(1)),
        )
        assertEquals(
            ExpiryWorkerDirective.COMPLETE_AND_RECOVER,
            expiryWorkerDirective(OccurrenceExpiryResult.Expired),
        )
        assertEquals(
            ExpiryWorkerDirective.COMPLETE_AND_RECOVER,
            expiryWorkerDirective(OccurrenceExpiryResult.Terminal),
        )
        assertEquals(
            ExpiryWorkerDirective.COMPLETE,
            expiryWorkerDirective(OccurrenceExpiryResult.InactiveStudy),
        )
    }

    @Test
    fun notificationFinalizationCancelsOnRejectedOrFailedDurableCommit() = runBlocking {
        var cancellations = 0
        finalizePostedNotification(finalize = { true }, cancel = { cancellations += 1 })
        assertEquals(0, cancellations)

        finalizePostedNotification(finalize = { false }, cancel = { cancellations += 1 })
        assertEquals(1, cancellations)

        val expected = IllegalStateException("storage failed")
        var caught: Throwable? = null
        try {
            finalizePostedNotification(
                finalize = { throw expected },
                cancel = { cancellations += 1 },
            )
        } catch (failure: Throwable) {
            caught = failure
        }
        assertSame(expected, caught)
        assertEquals(2, cancellations)
    }

    @Test
    fun failedDeliveryAttemptReleasesTheCoordinatorBeforeTheNextAttemptRuns() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        coroutineScope {
            val first = async {
                runCatching {
                    InterventionDeliveryCoordinator.run {
                        order += "first-entered"
                        firstEntered.complete(Unit)
                        releaseFailure.await()
                        throw IllegalStateException("durable finalize failed")
                    }
                }
            }
            firstEntered.await()
            val second = async {
                InterventionDeliveryCoordinator.run { order += "second-entered" }
            }
            order += "second-waiting"
            releaseFailure.complete(Unit)
            first.await()
            second.await()
        }

        assertEquals(listOf("first-entered", "second-waiting", "second-entered"), order)
    }

    @Test
    fun stalePostingRecoveryCannotInterleaveNotificationFinalization() = runBlocking {
        val notificationPosted = CompletableDeferred<Unit>()
        val allowFinalization = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        coroutineScope {
            val delivery = async {
                InterventionDeliveryCoordinator.run {
                    order += "notified"
                    notificationPosted.complete(Unit)
                    allowFinalization.await()
                    order += "finalized"
                }
            }
            notificationPosted.await()
            val recovery = async {
                InterventionDeliveryCoordinator.recoverStalePosting {
                    order += "recovered"
                }
            }
            order += "recovery-waiting"
            allowFinalization.complete(Unit)
            delivery.await()
            recovery.await()
        }

        assertEquals(
            listOf("notified", "recovery-waiting", "finalized", "recovered"),
            order,
        )
    }
}
