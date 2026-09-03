package cool.jacoblin.particeps

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingAndroidPrerequisites
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingForegroundNotification
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.platform.SharedStudyForegroundNotification
import cool.jacoblin.particeps.platform.retractDailyStatusNotification
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class CollectionService : Service() {
    private var accessMonitor: Job? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val startIntent = intent?.takeIf {
            it.action == ACTION_START &&
                it.hasExtra(EXTRA_LOCATION) &&
                it.hasExtra(EXTRA_REQUEST_ID)
        }
        if (startIntent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val title = startIntent.getStringExtra(EXTRA_STUDY_TITLE)?.takeIf(String::isNotBlank)
        if (title == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val location = startIntent.getBooleanExtra(EXTRA_LOCATION, false)
        val requestId = startIntent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (flags and START_FLAG_REDELIVERY != 0) {
            try {
                acquireForegroundNotification(
                    foregroundNotification(this, title, restoring = true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } catch (_: SecurityException) {
                stopSelf(startId)
                return START_NOT_STICKY
            } catch (_: IllegalArgumentException) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            verifyRedeliveredService(startId)
            return START_REDELIVER_INTENT
        }
        if (!CollectionServiceStartCoordinator.isPending(requestId)) {
            // The caller was cancelled after asking Android to start the service. Satisfy the
            // platform's foreground deadline with a neutral notification, then retire the orphan.
            try {
                acquireForegroundNotification(
                    foregroundNotification(this, title, restoring = true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                SharedStudyForegroundNotification.release(this)
            } catch (_: SecurityException) {
                // There is no live caller and no safe collection state to recover.
            } catch (_: IllegalArgumentException) {
                // Manifest/type mismatch is a build defect; the orphan still must be stopped.
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            acquireForegroundNotification(foregroundNotification(this, title, restoring = false), types)
            if (!CollectionServiceStartCoordinator.succeed(requestId)) {
                SharedStudyForegroundNotification.release(this)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            startAccessMonitor()
        } catch (failure: SecurityException) {
            CollectionServiceStartCoordinator.fail(requestId, failure)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (failure: IllegalArgumentException) {
            CollectionServiceStartCoordinator.fail(requestId, failure)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (failure: Throwable) {
            CollectionServiceStartCoordinator.fail(requestId, failure)
            stopSelf(startId)
            throw failure
        }
        // The foreground-service type and participant-visible study title are security-relevant;
        // ask Android to redeliver this exact app-authored intent after a process restart.
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        accessMonitor?.cancel()
        accessMonitor = null
        SharedStudyForegroundNotification.release(this)
        super.onDestroy()
    }

    private fun acquireForegroundNotification(notification: Notification, foregroundServiceType: Int) {
        SharedStudyForegroundNotification.acquire(
            owner = this,
            id = NOTIFICATION_ID,
            notification = notification,
            foregroundServiceType = foregroundServiceType,
            starter = { id, value, type -> startForeground(id, value, type) },
            stopper = { mode -> stopForeground(mode) },
        )
    }

    /**
     * Android special access, notification channels, keyboard selection, and the device location
     * toggle do not share one reliable change callback. Re-inspecting the closed access plan from
     * the running foreground service keeps enforcement independent of the Activity lifecycle.
     */
    private fun startAccessMonitor() {
        if (accessMonitor?.isActive == true) return
        val collectorApplication = application as CollectorApplication
        accessMonitor = collectorApplication.applicationScope.launch {
            while (isActive) {
                delay(ACCESS_RECONCILIATION_INTERVAL_MILLIS)
                collectorApplication.session.reconcileAccess()
                val snapshot = collectorApplication.session.snapshot.value
                if (
                    snapshot.runtime.state == ExperimentState.RUNNING &&
                    snapshot.study?.mayAdjustAppTransferSpeed == true &&
                    (
                        !TrafficShapingAndroidPrerequisites.hasLocalNetworkPermission(this@CollectionService) ||
                            TrafficShapingAndroidPrerequisites.vpnConsentIntent(this@CollectionService) != null
                        )
                ) {
                    collectorApplication.session.safetyPauseForPlatformAccessLoss()
                }
            }
        }
    }

    /**
     * A redelivered intent belongs to a prior process and therefore has no live caller waiting for
     * its request ID. Keep only a neutral restoration notification until the newly constructed
     * session has revalidated durable state and current access, then issue a fresh acknowledged
     * start with the exact foreground-service types or stop this stale service.
     */
    private fun verifyRedeliveredService(startId: Int) {
        val collectorApplication = application as CollectorApplication
        accessMonitor?.cancel()
        accessMonitor = collectorApplication.applicationScope.launch {
            collectorApplication.session.snapshot.first { it.initialized }
            val running = collectorApplication.session.snapshot.value.runtime.state == ExperimentState.RUNNING
            if (running) {
                accessMonitor = null
                startAccessMonitor()
            } else {
                stopSelfResult(startId)
            }
        }
    }

    companion object {
        private const val ACTION_START = "cool.jacoblin.particeps.START_COLLECTION"
        private const val EXTRA_STUDY_TITLE = "study_title"
        private const val EXTRA_LOCATION = "location"
        private const val EXTRA_REQUEST_ID = "request_id"
        const val NOTIFICATION_ID = 72
        private const val ACCESS_RECONCILIATION_INTERVAL_MILLIS = 25_000L
        private const val START_CONFIRMATION_TIMEOUT_MILLIS = 5_000L

        suspend fun start(
            context: Context,
            studyTitle: String,
            location: Boolean,
        ) {
            val pending = CollectionServiceStartCoordinator.register()
            try {
                context.startForegroundService(
                    Intent(context, CollectionService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_STUDY_TITLE, studyTitle)
                        .putExtra(EXTRA_LOCATION, location)
                        .putExtra(EXTRA_REQUEST_ID, pending.requestId),
                )
                withTimeout(START_CONFIRMATION_TIMEOUT_MILLIS) { pending.confirmation.await() }
                retractDailyStatusNotification(context)
            } catch (failure: TimeoutCancellationException) {
                stop(context)
                throw IllegalStateException("Foreground service did not acknowledge startup", failure)
            } catch (failure: CancellationException) {
                stop(context)
                throw failure
            } finally {
                CollectionServiceStartCoordinator.remove(pending.requestId)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CollectionService::class.java))
            retractDailyStatusNotification(context)
        }

        fun foregroundNotification(
            context: Context,
            studyTitle: String,
            restoring: Boolean,
        ): Notification {
            require(studyTitle.isNotBlank()) { "Study title is required for host identity" }
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, ParticepsNotificationChannels.COLLECTION)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(
                    context.getString(
                        if (restoring) {
                            R.string.collection_recovery_notification_title
                        } else {
                            R.string.collection_notification_title
                        },
                    ),
                )
                .setContentText(
                    if (restoring) {
                        context.getString(R.string.collection_recovery_notification_text)
                    } else {
                        context.getString(R.string.collection_notification_text)
                    },
                )
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }

        /**
         * The collector host and local VPN intentionally occupy one Android foreground slot. This
         * factory is the sole source of both the fixed ID and the neutral participant-facing copy,
         * preventing either host from accidentally creating a treatment-revealing second notice.
         */
        fun trafficShapingForegroundNotification(
            context: Context,
            studyTitle: String,
        ): TrafficShapingForegroundNotification = TrafficShapingForegroundNotification(
            id = NOTIFICATION_ID,
            notification = foregroundNotification(context, studyTitle, restoring = false),
            lease = SharedStudyForegroundNotification,
        )
    }
}

private object CollectionServiceStartCoordinator {
    data class Pending(
        val requestId: String,
        val confirmation: CompletableDeferred<Unit>,
    )

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun register(): Pending {
        val requestId = UUID.randomUUID().toString()
        val confirmation = CompletableDeferred<Unit>()
        check(pending.putIfAbsent(requestId, confirmation) == null)
        return Pending(requestId, confirmation)
    }

    fun isPending(requestId: String): Boolean = pending.containsKey(requestId)

    fun succeed(requestId: String): Boolean = pending[requestId]?.complete(Unit) == true

    fun fail(requestId: String, failure: Throwable) {
        pending[requestId]?.completeExceptionally(failure)
    }

    fun remove(requestId: String) {
        pending.remove(requestId)?.cancel()
    }
}
