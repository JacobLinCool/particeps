package cool.jacoblin.particeps.platform

import android.app.NotificationManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import cool.jacoblin.particeps.ActionOutboxWorker
import cool.jacoblin.particeps.CollectionService
import cool.jacoblin.particeps.DailyStatusWorker
import cool.jacoblin.particeps.RuntimeTimerWorker
import cool.jacoblin.particeps.UploadWorker
import cool.jacoblin.particeps.core.application.CollectorActuatorDecorator
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.application.StudyUploadCoordinator
import cool.jacoblin.particeps.core.application.StudyUploadPlan
import cool.jacoblin.particeps.core.application.StudyUploadScheduler
import cool.jacoblin.particeps.core.application.UploadReconciliation
import cool.jacoblin.particeps.core.automation.DurableTimer
import cool.jacoblin.particeps.core.automation.TimerTarget
import cool.jacoblin.particeps.core.collector.ResearchClocks
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.export.ExportReceipt
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.resource.ApplyReceipt
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import cool.jacoblin.particeps.core.resource.FlushReceipt
import cool.jacoblin.particeps.core.resource.PrepareReceipt
import cool.jacoblin.particeps.core.resource.ReleaseReceipt
import cool.jacoblin.particeps.core.resource.ResumeReceipt
import cool.jacoblin.particeps.core.resource.ResourceHealth
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceTerminalFailureListener
import cool.jacoblin.particeps.core.resource.StatefulResourceActuator
import cool.jacoblin.particeps.core.resource.SuspendReceipt
import cool.jacoblin.particeps.core.resource.VerifyReceipt
import cool.jacoblin.particeps.core.runtime.ActionOutboxNotifier
import cool.jacoblin.particeps.core.runtime.ExperimentRuntime
import cool.jacoblin.particeps.core.runtime.TimerWakeupAdapter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Starts the acknowledged collector foreground service before the first collector prepares and
 * stops it only after the final collector releases. The service itself is process liveness, not a
 * signed resource and never appears in the resource vector.
 */
class AndroidCollectorForegroundServiceDecorator(
    context: Context,
) : CollectorActuatorDecorator {
    private val host = RefCountedCollectorForegroundService(
        AndroidCollectorForegroundServiceController(context.applicationContext),
    )

    override fun decorate(
        study: StudyConfiguration,
        declaration: CollectorResourceConfiguration,
        delegate: StatefulResourceActuator,
    ): StatefulResourceActuator = ForegroundServiceCollectorActuator(
        delegate = delegate,
        host = host,
        studyTitle = study.title,
        usesLocation = declaration.id == "location.v1",
    )
}

internal interface CollectorForegroundServiceController {
    suspend fun start(studyTitle: String, usesLocation: Boolean)
    fun stop()
}

private class AndroidCollectorForegroundServiceController(
    private val context: Context,
) : CollectorForegroundServiceController {
    override suspend fun start(studyTitle: String, usesLocation: Boolean) =
        CollectionService.start(context, studyTitle, usesLocation)

    override fun stop() = CollectionService.stop(context)
}

internal interface CollectorForegroundServiceHost {
    suspend fun acquire(key: ResourceKey, studyTitle: String, usesLocation: Boolean)
    suspend fun release(key: ResourceKey)
}

internal class RefCountedCollectorForegroundService(
    private val controller: CollectorForegroundServiceController,
) : CollectorForegroundServiceHost {
    private val mutex = Mutex()
    private val owners = linkedMapOf<ResourceKey, Boolean>()
    private var title: String? = null
    private var failedAcquisition: FailedAcquisition? = null

    override suspend fun acquire(key: ResourceKey, studyTitle: String, usesLocation: Boolean) = mutex.withLock {
        check(failedAcquisition == null) { "A failed collector foreground transition is awaiting containment" }
        if (owners[key] == usesLocation) return@withLock
        require(title == null || title == studyTitle) { "Collector foreground service crossed studies" }
        val next = owners + (key to usesLocation)
        try {
            controller.start(studyTitle, next.values.any { it })
        } catch (failure: Throwable) {
            if (owners.isNotEmpty()) {
                try {
                    controller.start(checkNotNull(title), owners.values.any { it })
                } catch (restorationFailure: Throwable) {
                    if (restorationFailure !== failure) failure.addSuppressed(restorationFailure)
                    failedAcquisition = FailedAcquisition(key, failure)
                }
            }
            throw failure
        }
        owners[key] = usesLocation
        title = studyTitle
    }

    override suspend fun release(key: ResourceKey) = mutex.withLock {
        failedAcquisition?.takeIf { it.key == key }?.let { failed ->
            failedAcquisition = null
            throw failed.failure
        }
        val releasedLocationOwner = owners[key] ?: return@withLock
        val remaining = owners - key
        if (remaining.isEmpty()) {
            controller.stop()
            owners.remove(key)
            title = null
        } else if (releasedLocationOwner && remaining.values.none { it }) {
            // Profile bindings may switch location off while other collectors keep running.
            // Re-acknowledge the same service with the exact remaining FGS type set instead of
            // retaining location privilege after its resource has been released.
            controller.start(checkNotNull(title), usesLocation = false)
            owners.remove(key)
        } else {
            owners.remove(key)
        }
    }

    internal suspend fun ownersForTest(): Map<ResourceKey, Boolean> = mutex.withLock { owners.toMap() }

    private data class FailedAcquisition(val key: ResourceKey, val failure: Throwable)
}

internal class ForegroundServiceCollectorActuator(
    private val delegate: StatefulResourceActuator,
    private val host: CollectorForegroundServiceHost,
    private val studyTitle: String,
    private val usesLocation: Boolean,
) : StatefulResourceActuator {
    override val key: ResourceKey = delegate.key
    override val supportsHotProfileSwap: Boolean = delegate.supportsHotProfileSwap

    override fun setTerminalFailureListener(listener: ResourceTerminalFailureListener?) =
        delegate.setTerminalFailureListener(listener)

    override suspend fun prepare(desired: DesiredResourceState, requestId: String): PrepareReceipt {
        // Establish the desired-bound PREPARED state first. If Android rejects an FGS type
        // transition, the runtime can then call release(desired) and obtain exact NOT_APPLIED
        // cleanup evidence instead of mistaking an optional collector for an uncontained resource.
        val receipt = delegate.prepare(desired, requestId)
        return try {
            host.acquire(key, studyTitle, usesLocation)
            receipt
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            throw failure
        }
    }

    override suspend fun suspendAt(
        desired: DesiredResourceState,
        boundary: ResearchTime,
    ): SuspendReceipt = delegate.suspendAt(desired, boundary)

    override suspend fun flushThrough(
        desired: DesiredResourceState,
        boundary: ResearchTime,
        cursor: String?,
    ): FlushReceipt = delegate.flushThrough(desired, boundary, cursor)

    override suspend fun apply(desired: DesiredResourceState): ApplyReceipt = delegate.apply(desired)

    override suspend fun verify(desired: DesiredResourceState): VerifyReceipt = delegate.verify(desired)

    override suspend fun resume(desired: DesiredResourceState): ResumeReceipt = delegate.resume(desired)

    override suspend fun onAdmissionOpened(desired: DesiredResourceState): ResourceHealth =
        delegate.onAdmissionOpened(desired)

    override suspend fun release(desired: DesiredResourceState): ReleaseReceipt {
        var receipt: ReleaseReceipt? = null
        var failure: Throwable? = null
        try {
            receipt = delegate.release(desired)
        } catch (caught: Throwable) {
            caught.rethrowCancellation()
            failure = caught
        }
        try {
            host.release(key)
        } catch (caught: Throwable) {
            caught.rethrowCancellation()
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        failure?.let { throw it }
        return checkNotNull(receipt)
    }

    override fun health(): ResourceHealth = delegate.health()
}

/** WorkManager is only a wakeup adapter; the runtime commit chain remains timer truth. */
class AndroidTimerWakeupAdapter(
    context: Context,
    private val clocks: ResearchClocks,
) : TimerWakeupAdapter {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    @Volatile private var runtime: ExperimentRuntime? = null

    fun bindRuntime(runtime: ExperimentRuntime) {
        check(this.runtime == null) { "Timer adapter is already bound" }
        this.runtime = runtime
    }

    override suspend fun schedule(timer: DurableTimer) {
        val now = clocks.now()
        val delayMillis = timer.delayMillis(now, runtime?.snapshot?.value?.activeRunningElapsedNanos ?: 0L)
        val request = OneTimeWorkRequestBuilder<RuntimeTimerWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, TIMER_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(RuntimeTimerWorker.input(timer.id, timer.generation))
            .build()
        awaitWorkPersistence(
            workManager.enqueueUniqueWork(
                timerWorkName(timer.id, timer.generation),
                ExistingWorkPolicy.REPLACE,
                request,
            ),
        )
    }

    override suspend fun retire(timerId: String, generation: ULong) {
        awaitWorkPersistence(workManager.cancelUniqueWork(timerWorkName(timerId, generation)))
    }

    /** Re-arms durable timers after process recovery and whenever active time resumes. */
    suspend fun reconcile(session: StudySessionManager) {
        session.pendingTimers().forEach { schedule(it) }
    }

    private fun DurableTimer.delayMillis(now: ResearchTime, activeElapsedNanos: Long): Long = when (val due = target) {
        is TimerTarget.CalendarUtc -> (due.utcMillis - now.wallTimeUtcMillis).coerceAtLeast(0L)
        is TimerTarget.ActiveElapsed ->
            (due.elapsedNanos - activeElapsedNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
        is TimerTarget.SameBootMonotonic -> if (due.bootSessionId == now.bootSessionId) {
            (due.elapsedRealtimeNanos - now.elapsedRealtimeNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
        } else {
            0L
        }
    }

}

/** A committed action ID is the sole WorkManager input and idempotency key. */
class AndroidActionOutboxNotifier(context: Context) : ActionOutboxNotifier {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val notifications = applicationContext.getSystemService(NotificationManager::class.java)
    private val visibleActions = SerializedActionDisplayGate()

    override suspend fun onActionReady(actionId: String) {
        val request = OneTimeWorkRequestBuilder<ActionOutboxWorker>()
            .setInputData(Data.Builder().putString(ActionOutboxWorker.KEY_ACTION_ID, actionId).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, ACTION_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        visibleActions.activate(actionId) {
            awaitWorkPersistence(
                workManager.enqueueUniqueWork(actionWorkName(actionId), ExistingWorkPolicy.KEEP, request),
            )
        }
    }

    override suspend fun onActionsInactive(actionIds: List<String>) {
        require(actionIds == actionIds.sorted().distinct()) { "Inactive action identities must be sorted and unique" }
        var firstFailure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            try {
                operation()
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                val prior = firstFailure
                if (prior == null) firstFailure = failure else prior.addSuppressed(failure)
            }
        }

        visibleActions.retract(actionIds) { actionId ->
            attempt { notifications.cancel(actionId, 0) }
        }
        // Cancellation is intentionally issued without awaiting completion. A required action may
        // be reporting its own failure on the current worker; awaiting cancellation of that same
        // worker would deadlock the runtime's fail-closed transition. The display gate is closed
        // first so a worker surviving the cancellation cannot make an inactive action visible.
        actionIds.forEach { actionId ->
            attempt { workManager.cancelUniqueWork(actionWorkName(actionId)) }
            attempt { workManager.cancelUniqueWork(actionExpiryWorkName(actionId)) }
        }
        firstFailure?.let { throw it }
    }

    /** Serializes display against pause/terminal retraction without entering the runtime lock. */
    internal suspend fun displayIfRunning(
        actionId: String,
        isRunning: () -> Boolean,
        display: suspend () -> Unit,
    ): Boolean = visibleActions.displayIfActive(actionId, isRunning, display)

    /** Prevents an expiry worker racing a previously claimed worker into a late display. */
    internal suspend fun retractVisible(actionId: String) {
        visibleActions.retract(listOf(actionId)) { notifications.cancel(it, 0) }
    }
}

/** One ordering boundary between a worker's visible effect and lifecycle retraction. */
internal class SerializedActionDisplayGate {
    private val mutex = Mutex()
    private val inactiveActionIds = mutableSetOf<String>()

    suspend fun activate(actionId: String, schedule: suspend () -> Unit) = mutex.withLock {
        inactiveActionIds.remove(actionId)
        try {
            schedule()
        } catch (failure: Throwable) {
            inactiveActionIds += actionId
            throw failure
        }
    }

    suspend fun displayIfActive(
        actionId: String,
        isRunning: () -> Boolean,
        display: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (actionId in inactiveActionIds || !isRunning()) false else {
            display()
            true
        }
    }

    suspend fun retract(actionIds: List<String>, retract: (String) -> Unit) = mutex.withLock {
        inactiveActionIds += actionIds
        actionIds.forEach(retract)
    }
}

/**
 * Owns the one commit-based upload stage and its WorkManager chain. It stores no signed endpoint in
 * WorkManager input; the verified plan is rebound by StudySessionManager on every process start.
 */
class AndroidStudyUploadPlatform(
    context: Context,
    private val uploader: OkHttpStudyUploader,
) : StudyUploadCoordinator, StudyUploadScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val mutex = Mutex()
    private val plans = mutableMapOf<String, StudyUploadPlan>()

    override suspend fun reconcile(context: UploadReconciliation) = mutex.withLock {
        plans[context.plan.experimentId] = context.plan
        uploader.reconcile(context.plan, context.uploadedThroughCommit)
        Unit
    }

    override suspend fun acknowledge(bundleId: UUID) {
        uploader.acknowledge(bundleId)
    }

    override suspend fun prepareDeletion(experimentId: String) {
        uploader.prepareDeletion()
        cancel(experimentId)
    }

    override suspend fun clear(experimentId: String) = mutex.withLock {
        uploader.clear()
        plans.remove(experimentId)
        Unit
    }

    override suspend fun clearAll() = mutex.withLock {
        uploader.clear()
        plans.clear()
    }

    override suspend fun ensureScheduled(plan: StudyUploadPlan) = mutex.withLock {
        plans[plan.experimentId] = plan
        awaitWorkPersistence(
            workManager.enqueueUniqueWork(
                uploadWorkName(plan.experimentId),
                ExistingWorkPolicy.KEEP,
                uploadRequest(plan),
            ),
        )
    }

    suspend fun scheduleSuccessor(plan: StudyUploadPlan) = mutex.withLock {
        // A worker may finish concurrently with participant deletion or a replacement signed
        // configuration. Only the plan that is still current may extend its unique chain.
        if (plans[plan.experimentId] != plan) return@withLock
        awaitWorkPersistence(
            workManager.enqueueUniqueWork(
                uploadWorkName(plan.experimentId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                uploadRequest(plan),
            ),
        )
    }

    suspend fun uploadOnce(session: StudySessionManager, experimentId: String): UploadAttempt {
        // Do not hold the platform state lock while entering StudySessionManager. A successful
        // runtime acknowledgement calls back into ensureScheduled(), and Kotlin Mutex is
        // deliberately non-reentrant. The uploader has its own serialization/cancellation lock;
        // this lock protects only the currently verified plan map.
        val plan = mutex.withLock { plans[experimentId] } ?: return UploadAttempt.Stale
        val staged = uploader.recover(plan, session.snapshot.value.runtime.uploadedThroughCommit)
            ?: uploader.stage(session, plan)
            ?: return UploadAttempt.NothingToUpload(plan)
        val receipt = uploader.send(plan, staged)
        return when (session.acknowledgeAutomaticUpload(receipt)) {
            StudyCommandResult.Success -> {
                UploadAttempt.Uploaded(plan, receipt)
            }
            else -> UploadAttempt.Retry
        }
    }

    override suspend fun cancel(experimentId: String) = mutex.withLock {
        awaitWorkPersistence(workManager.cancelUniqueWork(uploadWorkName(experimentId)))
        plans.remove(experimentId)
        Unit
    }

    override suspend fun cancelAll() = mutex.withLock {
        awaitWorkPersistence(workManager.cancelAllWorkByTag(UPLOAD_TAG))
        plans.clear()
    }

    private fun uploadRequest(plan: StudyUploadPlan) = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInitialDelay(plan.intervalMinutes.toLong(), TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(if (plan.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
        .setInputData(Data.Builder().putString(UploadWorker.KEY_EXPERIMENT_ID, plan.experimentId).build())
        .addTag(UPLOAD_TAG)
        .build()
}

sealed interface UploadAttempt {
    data object Stale : UploadAttempt
    data object Retry : UploadAttempt
    data class NothingToUpload(val plan: StudyUploadPlan) : UploadAttempt
    data class Uploaded(val plan: StudyUploadPlan, val receipt: ExportReceipt) : UploadAttempt
}

/** Process-global participant reminder. The worker decides from its whitelisted snapshot. */
suspend fun ensureDailyStatusWork(context: Context) {
    val request = PeriodicWorkRequestBuilder<DailyStatusWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(1, TimeUnit.DAYS)
        .build()
    awaitWorkPersistence(
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            DAILY_STATUS_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        ),
    )
}

fun retractDailyStatusNotification(context: Context) {
    context.getSystemService(NotificationManager::class.java)?.cancel(DailyStatusWorker.NOTIFICATION_TAG, 0)
}

/** WorkManager mutation acknowledgement is bounded so startup and containment cannot hang. */
internal suspend fun awaitWorkPersistence(operation: Operation) {
    try {
        withTimeout(WORK_ACKNOWLEDGEMENT_TIMEOUT_MILLIS) { operation.await() }
    } catch (timeout: TimeoutCancellationException) {
        throw IllegalStateException("WorkManager did not acknowledge a scheduled mutation", timeout)
    }
}

private fun timerWorkName(timerId: String, generation: ULong) = "runtime-timer:$timerId:$generation"
internal fun actionWorkName(actionId: String) = "runtime-action:$actionId"
internal fun actionExpiryWorkName(actionId: String) = "runtime-action-expiry:$actionId"
private fun uploadWorkName(experimentId: String) = "runtime-upload:$experimentId"

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

private const val DAILY_STATUS_WORK_NAME = "participant-daily-status-v1"
private const val UPLOAD_TAG = "engine-commit-upload-v1"
private const val WORK_ACKNOWLEDGEMENT_TIMEOUT_MILLIS = 30_000L
private const val ACTION_RETRY_DELAY_SECONDS = 10L
private const val TIMER_RETRY_DELAY_SECONDS = 10L
private const val UPLOAD_RETRY_DELAY_SECONDS = 60L
private const val NANOS_PER_MILLISECOND = 1_000_000L
