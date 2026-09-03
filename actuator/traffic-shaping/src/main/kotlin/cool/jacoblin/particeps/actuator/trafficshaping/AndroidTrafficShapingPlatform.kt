package cool.jacoblin.particeps.actuator.trafficshaping

import android.content.Context
import android.content.Intent
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

internal class AndroidTrafficShapingPlatform(
    context: Context,
    private val targets: TargetPackageSet,
    private val notificationFactory: TrafficShapingNotificationFactory,
) : TrafficShapingPlatform {
    private val applicationContext = context.applicationContext

    @Volatile
    private var terminalFailureListener: ((String) -> Unit)? = null

    @Volatile
    private var service: TrafficShapingVpnService? = null

    override fun setTerminalFailureListener(listener: ((String) -> Unit)?) {
        terminalFailureListener = listener
        service?.setRuntimeTerminalFailureListener(listener)
    }

    override suspend fun prepare(requestId: String) {
        if (TrafficShapingAndroidPrerequisites.vpnConsentIntent(applicationContext) != null) {
            throw TrafficShapingActuatorException(TrafficShapingFailureReason.VPN_CONSENT_REQUIRED)
        }
        if (!TrafficShapingAndroidPrerequisites.hasLocalNetworkPermission(applicationContext)) {
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.LOCAL_NETWORK_PERMISSION_REQUIRED,
            )
        }

        service?.let { running ->
            running.requireUsableSession()
            running.requireSameTargets(targets.packages)
            running.setRuntimeTerminalFailureListener(terminalFailureListener)
            running.requirePackagesCurrent()
            return
        }

        val pending = TrafficShapingServiceStartBroker.register(
            requestId = requestId,
            targetPackages = targets.packages,
            foregroundNotification = notificationFactory.create(applicationContext),
            terminalFailureListener = terminalFailureListener,
        )
        try {
            applicationContext.startForegroundService(
                Intent(applicationContext, TrafficShapingVpnService::class.java)
                    .setAction(TrafficShapingVpnService.ACTION_START)
                    .putExtra(TrafficShapingVpnService.EXTRA_REQUEST_ID, requestId),
            )
            val started = pending.service.await()
            check(TrafficShapingServiceStartBroker.complete(requestId, pending)) {
                "Traffic-shaping service request was cancelled during startup"
            }
            service = started
        } catch (failure: Throwable) {
            TrafficShapingServiceStartBroker.cancel(requestId, failure)
            throw if (failure is TrafficShapingActuatorException) {
                failure
            } else {
                TrafficShapingActuatorException(
                    TrafficShapingFailureReason.FOREGROUND_SERVICE_FAILED,
                    failure,
                )
            }
        }
    }

    override suspend fun suspendForwarding() {
        requireService().suspendForwarding()
    }

    override suspend fun apply(profile: SignedResourceProfile): Sha256Digest =
        requireService().applyProfile(profile)

    override suspend fun verify(expectedProfileSha256: Sha256Digest): TrafficShapingPlatformProof =
        requireService().verifyProfile(expectedProfileSha256)

    override suspend fun resumeForwarding() {
        requireService().resumeForwarding()
    }

    override suspend fun release() {
        service?.releaseSession()
        service = null
    }

    override fun snapshot(): TrafficShapingCounterSnapshot? = service?.counterSnapshot()

    private fun requireService(): TrafficShapingVpnService = service
        ?: throw TrafficShapingActuatorException(TrafficShapingFailureReason.FOREGROUND_SERVICE_FAILED)
}

internal data class TrafficShapingServiceStartRequest(
    val requestId: String,
    val targetPackages: List<String>,
    val foregroundNotification: TrafficShapingForegroundNotification,
    val terminalFailureListener: ((String) -> Unit)?,
    val service: CompletableDeferred<TrafficShapingVpnService>,
) {
    @Volatile
    var startedService: TrafficShapingVpnService? = null
}

internal object TrafficShapingServiceStartBroker {
    private val pending = ConcurrentHashMap<String, TrafficShapingServiceStartRequest>()

    fun register(
        requestId: String,
        targetPackages: List<String>,
        foregroundNotification: TrafficShapingForegroundNotification,
        terminalFailureListener: ((String) -> Unit)?,
    ): TrafficShapingServiceStartRequest {
        val request = TrafficShapingServiceStartRequest(
            requestId,
            targetPackages.toList(),
            foregroundNotification,
            terminalFailureListener,
            CompletableDeferred(),
        )
        check(pending.putIfAbsent(requestId, request) == null) {
            "Duplicate traffic-shaping service request"
        }
        return request
    }

    fun find(requestId: String): TrafficShapingServiceStartRequest? = pending[requestId]

    fun markStarted(
        requestId: String,
        request: TrafficShapingServiceStartRequest,
        service: TrafficShapingVpnService,
    ): Boolean {
        if (pending[requestId] !== request) return false
        request.startedService = service
        return pending[requestId] === request
    }

    fun complete(requestId: String, request: TrafficShapingServiceStartRequest): Boolean =
        pending.remove(requestId, request)

    fun cancel(requestId: String, failure: Throwable) {
        pending.remove(requestId)?.let { request ->
            request.service.completeExceptionally(failure)
            request.startedService?.releaseSession()
        }
    }
}
