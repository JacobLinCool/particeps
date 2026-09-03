package cool.jacoblin.particeps

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.platform.SerializedSharedForegroundNotificationLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
