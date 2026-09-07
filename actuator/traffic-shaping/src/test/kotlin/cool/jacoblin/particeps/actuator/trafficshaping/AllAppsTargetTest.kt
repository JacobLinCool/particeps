package cool.jacoblin.particeps.actuator.trafficshaping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AllAppsTargetTest {
    @Test
    fun scopeRequiresExplicitSelectionAndHashesTheSignedValue() {
        val all = TargetPackageSet.of(emptyList(), allApps = true)
        assertTrue(all.allApps)
        assertEquals("\"all\"", all.canonicalTargetBytes().toString(Charsets.UTF_8))
        val selected = TargetPackageSet.of(listOf("com.example.app"))
        assertEquals("[\"com.example.app\"]", selected.canonicalTargetBytes().toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { TargetPackageSet.of(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TargetPackageSet.of(listOf("com.example.app"), allApps = true) }
    }
}
