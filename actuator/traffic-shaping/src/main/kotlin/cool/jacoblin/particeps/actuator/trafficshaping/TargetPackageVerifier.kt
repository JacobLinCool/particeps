package cool.jacoblin.particeps.actuator.trafficshaping

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

internal data class TargetPackageIdentity(
    val packageName: String,
    val uid: Int,
    val versionCode: Long,
    val lastUpdateTimeMillis: Long,
    val sameUidPackages: List<String>,
)

internal data class TargetPackageSnapshot(
    val identities: List<TargetPackageIdentity>,
)

internal class TargetPackageValidationException(message: String) : Exception(message)

internal class TargetPackageVerifier(
    private val packageManager: PackageManager,
    private val targets: TargetPackageSet,
) {
    fun capture(): TargetPackageSnapshot {
        val identities = targets.packages.map(::readIdentity)
        requireExactUidCoverage(identities, targets.packages.toSet())
        return TargetPackageSnapshot(identities)
    }

    fun isCurrent(snapshot: TargetPackageSnapshot): Boolean = runCatching {
        capture() == snapshot
    }.getOrDefault(false)

    private fun readIdentity(packageName: String): TargetPackageIdentity {
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
        val applicationInfo = packageInfo.requireApplicationInfo(packageName)
        return TargetPackageIdentity(
            packageName = packageName,
            uid = applicationInfo.uid,
            versionCode = packageInfo.longVersionCode,
            lastUpdateTimeMillis = packageInfo.lastUpdateTime,
            sameUidPackages = packageManager.getPackagesForUid(applicationInfo.uid)
                ?.sorted()
                ?.distinct()
                ?: listOf(packageName),
        )
    }
}

internal fun requireExactUidCoverage(
    identities: List<TargetPackageIdentity>,
    selectedPackages: Set<String>,
) {
    val unselectedPeers = identities
        .flatMap(TargetPackageIdentity::sameUidPackages)
        .filterNot(selectedPackages::contains)
        .distinct()
        .sorted()
    if (unselectedPeers.isNotEmpty()) {
        throw TargetPackageValidationException(
            "A target package shares its UID with an unselected package",
        )
    }
}

private fun PackageInfo.requireApplicationInfo(packageName: String): ApplicationInfo =
    applicationInfo ?: throw PackageManager.NameNotFoundException(packageName)
