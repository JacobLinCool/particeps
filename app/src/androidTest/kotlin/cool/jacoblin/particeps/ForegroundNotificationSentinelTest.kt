package cool.jacoblin.particeps

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.definition.NotificationAction
import cool.jacoblin.particeps.core.definition.SurveyAction
import cool.jacoblin.particeps.platform.SerializedSharedForegroundNotificationLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundNotificationSentinelTest {
    @Test
    fun participantNotificationsNeverEchoResearcherAuthoredStudyTitle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensitiveTitle = "TREATMENT slow-instagram-after-three-minutes"
        val notifications = listOf(
            CollectionService.foregroundNotification(context, sensitiveTitle, restoring = false),
            CollectionService.trafficShapingForegroundNotification(context, sensitiveTitle).notification,
            dailyStatusNotification(context, cool.jacoblin.particeps.core.model.ExperimentState.RUNNING),
            dailyStatusNotification(context, cool.jacoblin.particeps.core.model.ExperimentState.PAUSED),
        )

        notifications.forEach { notification ->
            val visibleCopy = listOf(
                notification.extras.getCharSequence("android.title"),
                notification.extras.getCharSequence("android.text"),
                notification.extras.getCharSequence("android.bigText"),
            ).joinToString(separator = "\n")
            assertFalse("Participant notification leaked signed study text", sensitiveTitle in visibleCopy)
        }
    }

    @Test
    fun interventionLockScreenVersionNeverShowsResearcherAuthoredText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensitiveTitle = "TREATMENT slow-instagram-after-three-minutes"
        val sensitiveMessage = "You are in the throttled arm; rate your Instagram session."
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val actions = listOf(
            NotificationAction(sensitiveTitle, sensitiveMessage),
            SurveyAction(sensitiveTitle, sensitiveMessage, "session-rating"),
        )

        actions.forEach { action ->
            val notification = interventionNotification(context, action, contentIntent)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(sensitiveTitle, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            val publicVersion = checkNotNull(notification.publicVersion) { "Lock-screen version is required" }
            val lockScreenCopy = listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT,
            ).mapNotNull { publicVersion.extras.getCharSequence(it)?.toString() } +
                listOfNotNull(publicVersion.tickerText?.toString())
            lockScreenCopy.forEach { copy ->
                assertFalse("Lock screen leaked the activity title", sensitiveTitle in copy)
                assertFalse("Lock screen leaked the activity message", sensitiveMessage in copy)
            }
            assertEquals(
                listOf(context.getString(R.string.app_name), context.getString(R.string.intervention_public_text)),
                lockScreenCopy,
            )
            assertEquals(ParticepsNotificationChannels.INTERVENTIONS, notification.channelId)
            assertEquals(ParticepsNotificationChannels.INTERVENTIONS, publicVersion.channelId)
        }
    }

    @Test
    fun collectorAndVpnUseOneNeutralForegroundNotificationIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val shared = CollectionService.trafficShapingForegroundNotification(
            context = context,
            studyTitle = "Participant study",
        )

        assertEquals(CollectionService.NOTIFICATION_ID, shared.id)
        assertEquals(ParticepsNotificationChannels.COLLECTION, shared.notification.channelId)
        assertEquals(
            CollectionService.foregroundNotification(context, "Participant study", restoring = false)
                .extras
                .getCharSequence("android.title"),
            shared.notification.extras.getCharSequence("android.title"),
        )
        assertEquals(
            CollectionService.foregroundNotification(context, "Participant study", restoring = false)
                .extras
                .getCharSequence("android.text"),
            shared.notification.extras.getCharSequence("android.text"),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun mergedManifestDeclaresExactlyOneVpnService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services
            .orEmpty()
            .filter { it.permission == Manifest.permission.BIND_VPN_SERVICE }

        assertEquals(1, services.size)
        assertEquals(
            "cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingVpnService",
            services.single().name,
        )
        assertEquals(false, services.single().exported)
    }

    @Test
    fun installedManifestRetainsOwnNotificationsWithoutCrossAppNotificationAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS).toLong(),
            ),
        )

        assertTrue(Manifest.permission.POST_NOTIFICATIONS in packageInfo.requestedPermissions.orEmpty())
        assertFalse(
            "The installed APK must not expose a notification listener service",
            packageInfo.services.orEmpty().any {
                it.permission == Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE
            },
        )
        assertFalse(
            Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE in packageInfo.requestedPermissions.orEmpty(),
        )
    }

    @Test
    fun eitherForegroundOwnerCanReleaseFirstWithoutRemovingTheSharedNotification() {
        verifyReleaseOrder(firstOwnerToRelease = 0)
        verifyReleaseOrder(firstOwnerToRelease = 1)
    }

    private fun verifyReleaseOrder(firstOwnerToRelease: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = CollectionService.foregroundNotification(
            context,
            "Participant study",
            restoring = false,
        )
        val lease = SerializedSharedForegroundNotificationLease()
        val owners = listOf(Any(), Any())
        val operations = owners.indices.associateWith { mutableListOf<String>() }

        owners.forEachIndexed { index, owner ->
            lease.acquire(
                owner = owner,
                id = CollectionService.NOTIFICATION_ID,
                notification = notification,
                foregroundServiceType = index + 1,
                starter = { id, _, type -> operations.getValue(index) += "start:$id:$type" },
                stopper = { mode -> operations.getValue(index) += "stop:$mode" },
            )
        }

        val remainingOwner = 1 - firstOwnerToRelease
        lease.release(owners[firstOwnerToRelease])
        assertEquals(
            "The departing owner must detach, not cancel, the shared notification",
            "stop:${Service.STOP_FOREGROUND_DETACH}",
            operations.getValue(firstOwnerToRelease).last(),
        )
        assertEquals(
            "The remaining owner must reassert the shared foreground notification before detach",
            "start:${CollectionService.NOTIFICATION_ID}:${remainingOwner + 1}",
            operations.getValue(remainingOwner).last(),
        )
        assertEquals(1, lease.ownerCountForTest())

        lease.release(owners[remainingOwner])
        assertEquals(
            "Only the final owner may remove the shared notification",
            "stop:${Service.STOP_FOREGROUND_REMOVE}",
            operations.getValue(remainingOwner).last(),
        )
        assertEquals(0, lease.ownerCountForTest())
    }
}
