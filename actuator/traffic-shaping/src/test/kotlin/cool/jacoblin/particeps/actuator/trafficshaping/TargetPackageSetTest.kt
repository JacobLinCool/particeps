package cool.jacoblin.particeps.actuator.trafficshaping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetPackageSetTest {
    @Test
    fun acceptsSortedStudyPackages() {
        assertEquals(
            listOf("com.example.alpha", "com.example.beta"),
            TargetPackageSet.of(listOf("com.example.alpha", "com.example.beta")).packages,
        )
    }

    @Test
    fun rejectsUnsortedDuplicateAndParticepsPackages() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetPackageSet.of(listOf("com.example.beta", "com.example.alpha"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetPackageSet.of(listOf("com.example.alpha", "com.example.alpha"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetPackageSet.of(listOf("cool.jacoblin.particeps"))
        }
    }

    @Test
    fun rejectsAnUnselectedPackageSharingAnyTargetUid() {
        val target = TargetPackageIdentity(
            packageName = "com.example.target",
            uid = 10_001,
            versionCode = 1,
            lastUpdateTimeMillis = 1,
            sameUidPackages = listOf("com.example.peer", "com.example.target"),
        )

        assertThrows(TargetPackageValidationException::class.java) {
            requireExactUidCoverage(listOf(target), setOf("com.example.target"))
        }
    }

    @Test
    fun acceptsAUidWhoseEveryPackageIsExplicitlySelected() {
        val selected = setOf("com.example.alpha", "com.example.beta")
        val identities = selected.sorted().map { packageName ->
            TargetPackageIdentity(
                packageName = packageName,
                uid = 10_001,
                versionCode = 1,
                lastUpdateTimeMillis = 1,
                sameUidPackages = selected.sorted(),
            )
        }

        requireExactUidCoverage(identities, selected)
    }
}
