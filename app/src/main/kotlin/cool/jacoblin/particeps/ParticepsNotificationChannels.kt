package cool.jacoblin.particeps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import cool.jacoblin.particeps.core.collector.NotificationAccessFeature

internal object ParticepsNotificationChannels {
    const val COLLECTION = "active-research-collection"
    const val DAILY_STATUS = "research-daily-status-v1"
    const val INTERVENTIONS = "research-interventions-v1"
    const val RECOVERY = "research-recovery-v1"

    val idsByFeature: Map<NotificationAccessFeature, String> = mapOf(
        NotificationAccessFeature.COLLECTION to COLLECTION,
        NotificationAccessFeature.DAILY_STATUS to DAILY_STATUS,
        NotificationAccessFeature.RECOVERY to RECOVERY,
        NotificationAccessFeature.INTERVENTIONS to INTERVENTIONS,
    )

    /**
     * Lock-screen visibility is not set here. Android replaces the value an app gives its own
     * channel with the no-override default, so each notification sets its own: recovery
     * notifications are secret, and intervention notifications are private with a neutral public
     * version, which Android shows only when the participant's lock screen hides sensitive content.
     */
    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    COLLECTION,
                    context.getString(R.string.collection_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.collection_channel_description)
                    setShowBadge(false)
                },
                NotificationChannel(
                    DAILY_STATUS,
                    context.getString(R.string.daily_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.daily_channel_description)
                },
                NotificationChannel(
                    INTERVENTIONS,
                    context.getString(R.string.intervention_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.intervention_channel_description)
                },
                NotificationChannel(
                    RECOVERY,
                    context.getString(R.string.recovery_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.recovery_channel_description)
                },
            ),
        )
    }
}
