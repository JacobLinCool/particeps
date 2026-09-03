package cool.jacoblin.particeps.actuator.trafficshaping

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile
import java.util.UUID

data class TrafficShapingForegroundNotification(
    val id: Int,
    val notification: Notification,
    val lease: SharedForegroundNotificationLease,
) {
    init { require(id > 0) { "Foreground notification ID must be positive" } }
}

fun interface ForegroundServiceStarter {
    fun start(id: Int, notification: Notification, foregroundServiceType: Int)
}

fun interface ForegroundServiceStopper {
    fun stop(mode: Int)
}

/**
 * Process-local ownership for one Android foreground notification shared by multiple services.
 * Implementations must serialize calls and keep the notification attached until the last owner
 * releases it. A departing non-final owner uses [Service.STOP_FOREGROUND_DETACH]; the final owner
 * uses [Service.STOP_FOREGROUND_REMOVE].
 */
interface SharedForegroundNotificationLease {
    fun acquire(
        owner: Any,
        id: Int,
        notification: Notification,
        foregroundServiceType: Int,
        starter: ForegroundServiceStarter,
        stopper: ForegroundServiceStopper,
    )

    fun release(owner: Any)
}

/** Supplied by the app so the VPN and collection hosts can project one neutral notification. */
fun interface TrafficShapingNotificationFactory {
    fun create(context: Context): TrafficShapingForegroundNotification
}

data class TrafficShapingCounterSnapshot(
    val nativeGeneration: Long,
    val vpnGenerationId: String,
    val profileSha256: Sha256Digest,
    val uplinkBytes: Long,
    val uplinkPackets: Long,
    val downlinkBytes: Long,
    val downlinkPackets: Long,
    val uplinkThrottledNanos: Long,
    val downlinkThrottledNanos: Long,
) {
    init {
        require(nativeGeneration > 0) { "Native generation must be positive" }
        requireVpnGenerationId(vpnGenerationId)
        require(
            listOf(
                uplinkBytes,
                uplinkPackets,
                downlinkBytes,
                downlinkPackets,
                uplinkThrottledNanos,
                downlinkThrottledNanos,
            ).all { it >= 0 },
        ) { "Traffic counters must be non-negative" }
    }
}

data class TrafficShapingPlatformProof(
    val ownerNetworkVerified: Boolean,
    val tunOpen: Boolean,
    val nativeHealthy: Boolean,
    val protectorInstalled: Boolean,
    val packagesValid: Boolean,
    val vpnGenerationId: String?,
    val appliedProfileSha256: Sha256Digest?,
) {
    fun failureReason(expected: Sha256Digest): String? = when {
        !ownerNetworkVerified -> TrafficShapingFailureReason.OWNED_VPN_NOT_CONFIRMED
        !tunOpen -> TrafficShapingFailureReason.TUN_CLOSED
        !nativeHealthy -> TrafficShapingFailureReason.NATIVE_ENGINE_FAILED
        !protectorInstalled -> TrafficShapingFailureReason.SOCKET_PROTECTOR_MISSING
        !packagesValid -> TrafficShapingFailureReason.TARGET_PACKAGE_CHANGED
        vpnGenerationId == null || !isVpnGenerationId(vpnGenerationId) ->
            TrafficShapingFailureReason.OWNED_VPN_NOT_CONFIRMED
        appliedProfileSha256 != expected -> TrafficShapingFailureReason.PROFILE_MISMATCH
        else -> null
    }
}

private fun requireVpnGenerationId(value: String) {
    require(isVpnGenerationId(value)) { "VPN generation must be an RFC 4122 UUIDv4" }
}

private fun isVpnGenerationId(value: String): Boolean {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    return parsed != null && parsed.version() == 4 && parsed.variant() == 2
}

object TrafficShapingFailureReason {
    const val ACTIVATION_TIMEOUT = "ACTIVATION_TIMEOUT"
    const val FOREGROUND_SERVICE_FAILED = "FOREGROUND_SERVICE_FAILED"
    const val LOCAL_NETWORK_PERMISSION_REQUIRED = "LOCAL_NETWORK_PERMISSION_REQUIRED"
    const val NATIVE_ENGINE_FAILED = "NATIVE_ENGINE_FAILED"
    const val OWNED_VPN_LOST = "OWNED_VPN_LOST"
    const val OWNED_VPN_NOT_CONFIRMED = "OWNED_VPN_NOT_CONFIRMED"
    const val PROFILE_MISMATCH = "PROFILE_MISMATCH"
    const val SOCKET_PROTECTOR_MISSING = "SOCKET_PROTECTOR_MISSING"
    const val STALE_GENERATION = "STALE_GENERATION"
    const val TARGET_PACKAGE_CHANGED = "TARGET_PACKAGE_CHANGED"
    const val TARGET_PACKAGE_INVALID = "TARGET_PACKAGE_INVALID"
    const val TUN_CLOSED = "TUN_CLOSED"
    const val TUN_ESTABLISH_FAILED = "TUN_ESTABLISH_FAILED"
    const val VPN_CONSENT_REQUIRED = "VPN_CONSENT_REQUIRED"
    const val VPN_REVOKED = "VPN_REVOKED"
}

class TrafficShapingActuatorException(
    val reason: String,
    cause: Throwable? = null,
) : IllegalStateException(reason, cause)

object TrafficShapingAndroidPrerequisites {
    /** A non-null result must be launched by the participant-facing consent coordinator. */
    fun vpnConsentIntent(context: Context): Intent? = VpnService.prepare(context.applicationContext)

    fun hasLocalNetworkPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
}

internal interface TrafficShapingPlatform {
    fun setTerminalFailureListener(listener: ((String) -> Unit)?)
    suspend fun prepare(requestId: String)
    suspend fun suspendForwarding()
    suspend fun apply(profile: SignedResourceProfile): Sha256Digest
    suspend fun verify(expectedProfileSha256: Sha256Digest): TrafficShapingPlatformProof
    suspend fun resumeForwarding()
    suspend fun release()
    fun snapshot(): TrafficShapingCounterSnapshot?
}

internal class TargetPackageSet private constructor(
    val packages: List<String>,
) {
    companion object {
        private const val MAXIMUM_PACKAGES = 64
        private const val PARTICEPS_APPLICATION_ID = "cool.jacoblin.particeps"
        private val ANDROID_APPLICATION_ID = Regex(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+",
        )

        fun of(packages: List<String>): TargetPackageSet {
            require(packages.size in 1..MAXIMUM_PACKAGES) { "Invalid target package count" }
            require(packages == packages.sorted().distinct()) {
                "Target packages must be sorted and unique"
            }
            require(packages.all(ANDROID_APPLICATION_ID::matches)) {
                "Invalid Android application ID"
            }
            require(PARTICEPS_APPLICATION_ID !in packages) {
                "Particeps cannot be a target package"
            }
            return TargetPackageSet(packages.toList())
        }
    }
}
