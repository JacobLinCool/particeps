package cool.jacoblin.particeps

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.protocol.JoinLink
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrScanActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val contract = QrScanContract()
    private val encodedJoin = JoinLink(
        URI("https://research.example.edu/studies/config.partcfg"),
        "a".repeat(64),
        "B".repeat(32),
    ).encode()

    @Test
    fun scannerContractTargetsOnlyTheInternalActivity() {
        val intent = contract.createIntent(context, Unit)
        val component = requireNotNull(intent.component)
        assertEquals(context.packageName, component.packageName)
        assertEquals(QrScanActivity::class.java.name, component.className)
        val activityInfo = context.packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        assertFalse(activityInfo.exported)
        assertNull(intent.data)
    }

    @Test
    fun canceledMissingAndMalformedResultsNeverBecomeJoinRequests() {
        val valid = Intent().putExtra(QrScanActivity.RESULT_JOIN_LINK, encodedJoin)
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, valid))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent()))
        for (invalid in listOf("https://example.com/", "$encodedJoin&unknown=value", "not a study")) {
            assertNull(contract.parseResult(Activity.RESULT_OK, Intent().putExtra(QrScanActivity.RESULT_JOIN_LINK, invalid)))
        }
    }

    @Test
    fun successfulResultPreservesTheCanonicalJoinIdentity() {
        assertEquals(
            encodedJoin,
            contract.parseResult(Activity.RESULT_OK, Intent().putExtra(QrScanActivity.RESULT_JOIN_LINK, encodedJoin)),
        )
    }

    @Test
    fun scannerReleasesTheRealCameraOnBackgroundAndCancel() {
        val manager = requireNotNull(context.getSystemService(CameraManager::class.java))
        val cameraIds = manager.cameraIdList.toSet()
        assumeTrue("Camera hardware is optional for file-based participation", cameraIds.isNotEmpty())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        CameraAvailability(manager).use { availability ->
            await("initial camera availability") { cameraIds.all(availability::hasObservation) }
            val initiallyAvailable = cameraIds.filterTo(mutableSetOf(), availability::isAvailable)
            assertTrue("The camera test requires an idle camera", initiallyAvailable.isNotEmpty())
            ActivityScenario.launchActivityForResult(QrScanActivity::class.java).use { scenario ->
                await("scanner to acquire a camera") { initiallyAvailable.any { !availability.isAvailable(it) } }
                val usedCamera = initiallyAvailable.first { !availability.isAvailable(it) }

                scenario.moveToState(Lifecycle.State.CREATED)
                await("background scanner to release its camera") { availability.isAvailable(usedCamera) }

                scenario.moveToState(Lifecycle.State.RESUMED)
                await("resumed scanner to acquire its camera") { !availability.isAvailable(usedCamera) }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                val result = scenario.result
                assertEquals(Activity.RESULT_CANCELED, result.resultCode)
                assertNull(contract.parseResult(result.resultCode, result.resultData))
                await("canceled scanner to release its camera") { availability.isAvailable(usedCamera) }
            }
        }
    }

    private class CameraAvailability(private val manager: CameraManager) : AutoCloseable {
        private val states = ConcurrentHashMap<String, Boolean>()
        private val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) { states[cameraId] = true }
            override fun onCameraUnavailable(cameraId: String) { states[cameraId] = false }
        }

        init {
            manager.registerAvailabilityCallback(InstrumentationRegistry.getInstrumentation().targetContext.mainExecutor, callback)
        }

        fun hasObservation(cameraId: String): Boolean = states.containsKey(cameraId)
        fun isAvailable(cameraId: String): Boolean = states[cameraId] == true
        override fun close() { manager.unregisterAvailabilityCallback(callback) }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Timed out waiting for $description", condition())
    }
}
