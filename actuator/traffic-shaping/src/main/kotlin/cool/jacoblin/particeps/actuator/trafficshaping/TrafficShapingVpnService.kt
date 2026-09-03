package cool.jacoblin.particeps.actuator.trafficshaping

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import cool.jacoblin.particeps.nativebinding.trafficshaping.Engine
import cool.jacoblin.particeps.nativebinding.trafficshaping.Protector
import cool.jacoblin.particeps.nativebinding.trafficshaping.TerminalListener
import cool.jacoblin.particeps.nativebinding.trafficshaping.Trafficshaping
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TrafficShapingVpnService : VpnService() {
    private val stateLock = Any()
    private val terminalDelivered = AtomicBoolean(false)
    private val serviceLifecycle = TrafficShapingServiceLifecycle()
    private var targetPackages: TargetPackageSet? = null
    private var packageVerifier: TargetPackageVerifier? = null
    private var packageSnapshot: TargetPackageSnapshot? = null
    private var packageReceiverRegistered = false
    private var localNetworkAppOp: String? = null
    private var appOpsListenerRegistered = false
    private var ownershipGeneration = 0L
    private var vpnGenerationId: String? = null
    private var ownershipMonitor: VpnOwnershipMonitor? = null
    private var nativeEngine: Engine? = null
    private var lastCounterSnapshot: TrafficShapingCounterSnapshot? = null
    private var currentProfileSha256: Sha256Digest? = null
    private var protectorInstalled = false
    private var runtimeTerminalFailureListener: ((String) -> Unit)? = null
    private var terminalReason: String? = null
    private var foregroundNotificationLease: SharedForegroundNotificationLease? = null

    private val protector = object : Protector {
        override fun protect(socketFD: Long): Boolean {
            if (socketFD !in 0..Int.MAX_VALUE.toLong()) return false
            return try {
                this@TrafficShapingVpnService.protect(socketFD.toInt())
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    private val nativeTerminalListener = object : TerminalListener {
        override fun onTerminalFailure(code: String) {
            deliverTerminalFailure(code)
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.data?.schemeSpecificPart ?: return
            // Re-evaluate the signed targets for every package mutation. A newly installed,
            // unselected package can acquire a target's shared UID even though its name could not
            // have appeared in the activation snapshot.
            if (!packagesCurrent()) {
                deliverTerminalFailure(TrafficShapingFailureReason.TARGET_PACKAGE_CHANGED)
            }
        }
    }

    private val appOpsChangeListener = AppOpsManager.OnOpChangedListener { op, changedPackage ->
        val watchedOp = synchronized(stateLock) { localNetworkAppOp }
        if (op == watchedOp && changedPackage == packageName) {
            deliverTerminalFailure(TrafficShapingFailureReason.LOCAL_NETWORK_PERMISSION_REQUIRED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent
            ?.takeIf { it.action == ACTION_START }
            ?.getStringExtra(EXTRA_REQUEST_ID)
        val request = requestId?.let(TrafficShapingServiceStartBroker::find)
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            request.foregroundNotification.lease.acquire(
                owner = this,
                id = request.foregroundNotification.id,
                notification = request.foregroundNotification.notification,
                foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                starter = { id, notification, type -> startForeground(id, notification, type) },
                stopper = { mode -> stopForeground(mode) },
            )
            synchronized(stateLock) {
                foregroundNotificationLease = request.foregroundNotification.lease
            }
            initializeSession(request)
            if (!TrafficShapingServiceStartBroker.markStarted(request.requestId, request, this)) {
                cleanupSession(removeNotification = true)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            request.service.complete(this)
        } catch (failure: Throwable) {
            request.service.completeExceptionally(failure)
            cleanupSession(removeNotification = true)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        deliverTerminalFailure(TrafficShapingFailureReason.VPN_REVOKED)
        cleanupSession(removeNotification = true)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (serviceLifecycle.isUnexpectedDestroy()) {
            // Android can destroy the started VpnService while the app process and coordinator
            // remain alive. The terminal callback must close runtime admission synchronously
            // before cleanup tears down the TUN, listener, and native health evidence.
            deliverTerminalFailure(TrafficShapingFailureReason.FOREGROUND_SERVICE_FAILED)
        }
        cleanupSession(removeNotification = true)
        super.onDestroy()
    }

    internal fun setRuntimeTerminalFailureListener(listener: ((String) -> Unit)?) {
        synchronized(stateLock) { runtimeTerminalFailureListener = listener }
    }

    internal fun requireUsableSession() {
        synchronized(stateLock) { terminalReason }?.let { reason ->
            throw TrafficShapingActuatorException(reason)
        }
    }

    internal fun requireSameTargets(packages: List<String>) {
        check(synchronized(stateLock) { targetPackages?.packages } == packages) {
            "A live VPN service cannot change its package allowlist"
        }
    }

    internal fun requirePackagesCurrent() {
        if (!packagesCurrent()) {
            deliverTerminalFailure(TrafficShapingFailureReason.TARGET_PACKAGE_CHANGED)
            throw TrafficShapingActuatorException(TrafficShapingFailureReason.TARGET_PACKAGE_CHANGED)
        }
    }

    internal fun suspendForwarding() {
        val engine = synchronized(stateLock) { nativeEngine } ?: return
        try {
            engine.suspend()
        } catch (failure: Exception) {
            deliverTerminalFailure(TrafficShapingFailureReason.NATIVE_ENGINE_FAILED)
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.NATIVE_ENGINE_FAILED,
                failure,
            )
        }
    }

    internal fun applyProfile(profile: SignedResourceProfile): Sha256Digest {
        requirePackagesCurrent()
        var created = false
        val engine = synchronized(stateLock) { nativeEngine } ?: run {
            created = true
            establishNativeEngine()
        }
        try {
            check(engine.isSuspended) { "Native profile apply requires a suspended engine" }
            val receipt = engine.applyProfile(profile.canonicalBytes)
            val applied = Sha256Digest(receipt.digest)
            if (receipt.profileID != profile.id || applied != profile.expectedSha256) {
                deliverTerminalFailure(TrafficShapingFailureReason.PROFILE_MISMATCH)
                throw TrafficShapingActuatorException(TrafficShapingFailureReason.PROFILE_MISMATCH)
            }
            if (created) engine.start()
            synchronized(stateLock) { currentProfileSha256 = applied }
            return applied
        } catch (failure: TrafficShapingActuatorException) {
            if (created) cleanupNativeEngine()
            throw failure
        } catch (failure: Exception) {
            if (created) cleanupNativeEngine()
            deliverTerminalFailure(TrafficShapingFailureReason.NATIVE_ENGINE_FAILED)
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.NATIVE_ENGINE_FAILED,
                failure,
            )
        }
    }

    internal suspend fun verifyProfile(
        expectedProfileSha256: Sha256Digest,
    ): TrafficShapingPlatformProof {
        val monitor = synchronized(stateLock) { ownershipMonitor }
        monitor?.awaitOwnedNetwork()
        val engine = synchronized(stateLock) { nativeEngine }
        val packagesValid = packagesCurrent()
        val digest = engine?.appliedProfileDigest
            ?.takeIf(String::isNotEmpty)
            ?.let { runCatching { Sha256Digest(it) }.getOrNull() }
        val proof = TrafficShapingPlatformProof(
            ownerNetworkVerified = monitor?.isOwnedNetworkPresent() == true,
            tunOpen = engine?.hasOpenTun() == true,
            nativeHealthy = engine?.isHealthy == true,
            protectorInstalled = synchronized(stateLock) { protectorInstalled },
            packagesValid = packagesValid,
            vpnGenerationId = synchronized(stateLock) { vpnGenerationId },
            appliedProfileSha256 = digest,
        )
        proof.failureReason(expectedProfileSha256)?.let(::deliverTerminalFailure)
        return proof
    }

    internal suspend fun resumeForwarding() {
        val expected = synchronized(stateLock) { currentProfileSha256 }
            ?: throw TrafficShapingActuatorException(TrafficShapingFailureReason.PROFILE_MISMATCH)
        val proof = verifyProfile(expected)
        proof.failureReason(expected)?.let { reason ->
            throw TrafficShapingActuatorException(reason)
        }
        try {
            synchronized(stateLock) { nativeEngine }
                ?.resume()
                ?: throw TrafficShapingActuatorException(
                    TrafficShapingFailureReason.NATIVE_ENGINE_FAILED,
                )
        } catch (failure: TrafficShapingActuatorException) {
            throw failure
        } catch (failure: Exception) {
            deliverTerminalFailure(TrafficShapingFailureReason.NATIVE_ENGINE_FAILED)
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.NATIVE_ENGINE_FAILED,
                failure,
            )
        }
    }

    internal fun counterSnapshot(): TrafficShapingCounterSnapshot? {
        val engine = synchronized(stateLock) { nativeEngine }
            ?: return synchronized(stateLock) { lastCounterSnapshot }
        val snapshot = engine.snapshot() ?: return null
        val digest = runCatching { Sha256Digest(snapshot.profileDigest) }.getOrNull() ?: return null
        return TrafficShapingCounterSnapshot(
            nativeGeneration = snapshot.generation,
            vpnGenerationId = synchronized(stateLock) { vpnGenerationId } ?: return null,
            profileSha256 = digest,
            uplinkBytes = snapshot.uplinkBytes,
            uplinkPackets = snapshot.uplinkPackets,
            downlinkBytes = snapshot.downlinkBytes,
            downlinkPackets = snapshot.downlinkPackets,
            uplinkThrottledNanos = snapshot.uplinkThrottledNanos,
            downlinkThrottledNanos = snapshot.downlinkThrottledNanos,
        ).also { counters -> synchronized(stateLock) { lastCounterSnapshot = counters } }
    }

    internal fun releaseSession() {
        cleanupSession(removeNotification = true)
        stopSelf()
    }

    private fun initializeSession(request: TrafficShapingServiceStartRequest) {
        Trafficshaping.touch()
        val targets = TargetPackageSet.of(request.targetPackages)
        val verifier = TargetPackageVerifier(packageManager, targets)
        val snapshot = try {
            verifier.capture()
        } catch (failure: PackageManager.NameNotFoundException) {
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.TARGET_PACKAGE_INVALID,
                failure,
            )
        } catch (failure: TargetPackageValidationException) {
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.TARGET_PACKAGE_INVALID,
                failure,
            )
        }
        synchronized(stateLock) {
            check(targetPackages == null) { "Traffic-shaping VPN service is already initialized" }
            serviceLifecycle.activate()
            targetPackages = targets
            packageVerifier = verifier
            packageSnapshot = snapshot
            runtimeTerminalFailureListener = request.terminalFailureListener
            terminalReason = null
            lastCounterSnapshot = null
            terminalDelivered.set(false)
        }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        synchronized(stateLock) { packageReceiverRegistered = true }
        if (Build.VERSION.SDK_INT >= 37) {
            if (!TrafficShapingAndroidPrerequisites.hasLocalNetworkPermission(this)) {
                throw TrafficShapingActuatorException(
                    TrafficShapingFailureReason.LOCAL_NETWORK_PERMISSION_REQUIRED,
                )
            }
            val appOp = AppOpsManager.permissionToOp(Manifest.permission.ACCESS_LOCAL_NETWORK)
                ?: throw TrafficShapingActuatorException(
                    TrafficShapingFailureReason.LOCAL_NETWORK_PERMISSION_REQUIRED,
                )
            synchronized(stateLock) { localNetworkAppOp = appOp }
            getSystemService(AppOpsManager::class.java).startWatchingMode(
                appOp,
                packageName,
                appOpsChangeListener,
            )
            synchronized(stateLock) { appOpsListenerRegistered = true }
            if (!TrafficShapingAndroidPrerequisites.hasLocalNetworkPermission(this)) {
                throw TrafficShapingActuatorException(
                    TrafficShapingFailureReason.LOCAL_NETWORK_PERMISSION_REQUIRED,
                )
            }
        }
    }

    private fun establishNativeEngine(): Engine {
        val targets = synchronized(stateLock) { targetPackages }
            ?: throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.FOREGROUND_SERVICE_FAILED,
            )
        val descriptor = try {
            Builder()
                .setMtu(PROTOCOL_MTU)
                .addAddress(TUN_IPV4_ADDRESS, 32)
                .addAddress(TUN_IPV6_ADDRESS, 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setBlocking(true)
                .setMetered(false)
                .apply {
                    targets.packages.forEach(::addAllowedApplication)
                }
                .establish()
                ?: throw TrafficShapingActuatorException(
                    TrafficShapingFailureReason.TUN_ESTABLISH_FAILED,
                )
        } catch (failure: PackageManager.NameNotFoundException) {
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.TARGET_PACKAGE_CHANGED,
                failure,
            )
        } catch (failure: SecurityException) {
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.TUN_ESTABLISH_FAILED,
                failure,
            )
        }

        val detachedFd = try {
            descriptor.detachFd()
        } catch (failure: Throwable) {
            descriptor.close()
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.TUN_ESTABLISH_FAILED,
                failure,
            )
        }
        val engine = try {
            // The binding takes ownership immediately and closes the detached descriptor on every
            // success or failure path. Kotlin must never adopt or close it after this call.
            Trafficshaping.createEngine(
                detachedFd.toLong(),
                PROTOCOL_MTU.toLong(),
                protector,
                nativeTerminalListener,
            )
        } catch (failure: Exception) {
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.NATIVE_ENGINE_FAILED,
                failure,
            )
        }

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val generationId = UUID.randomUUID().toString()
        val monitor = synchronized(stateLock) {
            ownershipGeneration += 1
            VpnOwnershipMonitor(
                connectivityManager,
                ownershipGeneration,
            ) {
                deliverTerminalFailure(TrafficShapingFailureReason.OWNED_VPN_LOST)
            }.also {
                nativeEngine = engine
                vpnGenerationId = generationId
                protectorInstalled = true
                ownershipMonitor = it
            }
        }
        try {
            // The callback is deliberately fresh and registered only after Builder.establish().
            monitor.start()
        } catch (failure: Throwable) {
            cleanupNativeEngine()
            throw TrafficShapingActuatorException(
                TrafficShapingFailureReason.OWNED_VPN_NOT_CONFIRMED,
                failure,
            )
        }
        return engine
    }

    private fun packagesCurrent(): Boolean {
        val verifier: TargetPackageVerifier
        val snapshot: TargetPackageSnapshot
        synchronized(stateLock) {
            verifier = packageVerifier ?: return false
            snapshot = packageSnapshot ?: return false
        }
        return verifier.isCurrent(snapshot)
    }

    private fun cleanupNativeEngine() {
        val engine: Engine?
        val monitor: VpnOwnershipMonitor?
        synchronized(stateLock) {
            engine = nativeEngine
            monitor = ownershipMonitor
            nativeEngine = null
            vpnGenerationId = null
            ownershipMonitor = null
            currentProfileSha256 = null
            protectorInstalled = false
        }
        runCatching { monitor?.close() }
        runCatching { engine?.stop() }
    }

    private fun cleanupSession(removeNotification: Boolean) {
        if (!serviceLifecycle.beginRelease()) return
        cleanupNativeEngine()
        val unregister = synchronized(stateLock) {
            val wasRegistered = packageReceiverRegistered
            packageReceiverRegistered = false
            wasRegistered
        }
        if (unregister) runCatching { unregisterReceiver(packageChangeReceiver) }
        val unregisterAppOpsListener = synchronized(stateLock) {
            val wasRegistered = appOpsListenerRegistered
            appOpsListenerRegistered = false
            localNetworkAppOp = null
            wasRegistered
        }
        if (unregisterAppOpsListener) {
            runCatching {
                getSystemService(AppOpsManager::class.java).stopWatchingMode(appOpsChangeListener)
            }
        }
        synchronized(stateLock) {
            targetPackages = null
            packageVerifier = null
            packageSnapshot = null
            runtimeTerminalFailureListener = null
        }
        val foregroundLease = synchronized(stateLock) {
            foregroundNotificationLease.also { foregroundNotificationLease = null }
        }
        if (removeNotification) foregroundLease?.release(this)
    }

    private fun deliverTerminalFailure(reason: String) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        runCatching { counterSnapshot() }
        val listener = synchronized(stateLock) {
            terminalReason = reason
            runtimeTerminalFailureListener
        }
        listener?.invoke(reason)
    }

    companion object {
        internal const val ACTION_START =
            "cool.jacoblin.particeps.actuator.trafficshaping.START"
        internal const val EXTRA_REQUEST_ID = "request_id"
        internal const val PROTOCOL_MTU = 1500
        internal const val TUN_IPV4_ADDRESS = "10.111.222.1"
        internal const val TUN_IPV6_ADDRESS = "fd00:7061:7274::1"
    }
}

/**
 * Linearizes intentional release against Android-driven destruction without relying on Service
 * callback ordering. A destroy is terminal only after a study session was initialized and before
 * its explicit cleanup linearization point.
 */
internal class TrafficShapingServiceLifecycle {
    private var active = false
    private var releasing = false

    @Synchronized
    fun activate() {
        check(!active && !releasing) { "VPN service lifecycle cannot be reactivated" }
        active = true
    }

    @Synchronized
    fun beginRelease(): Boolean {
        if (releasing) return false
        releasing = true
        return true
    }

    @Synchronized
    fun isUnexpectedDestroy(): Boolean = active && !releasing
}
