package cool.jacoblin.particeps

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryNotificationAndroidTest {
    @Test
    fun missingChannelDoesNotAcknowledgeDeliveryAndRestoringItAllowsNotification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<CollectorApplication>()
        val session = withTimeout(40_000L) { context.awaitReady().session }
        session.clearStudyDataForTest()
        val manager = context.getSystemService(NotificationManager::class.java)
        val reporter = AndroidRecoveryReporter(context)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        try {
            reporter.clear()
            manager.deleteNotificationChannel(ParticepsNotificationChannels.RECOVERY)
            assertFalse(reporter.collectionPaused())

            ParticepsNotificationChannels.ensureCreated(context)
            assertTrue(reporter.collectionPaused())
            withTimeout(5_000) {
                while (manager.activeNotifications.none { it.tag == AndroidRecoveryReporter.NOTIFICATION_TAG }) {
                    delay(25)
                }
            }
            // Reconciliation is idempotent while the same notice is already present.
            assertTrue(reporter.collectionPaused())
            reporter.clear()
            withTimeout(5_000) {
                while (manager.activeNotifications.any { it.tag == AndroidRecoveryReporter.NOTIFICATION_TAG }) {
                    delay(25)
                }
            }
        } finally {
            ParticepsNotificationChannels.ensureCreated(context)
            reporter.clear()
            session.clearStudyDataForTest()
        }
    }
}
