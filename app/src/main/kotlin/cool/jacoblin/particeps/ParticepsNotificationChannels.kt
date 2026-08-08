package cool.jacoblin.particeps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import cool.jacoblin.particeps.core.collector.NotificationAccessFeature

internal object ParticepsNotificationChannels {
    const val COLLECTION = "active-research-collection"
    const val DAILY_STATUS = "research-daily-status-v1"
    const val INTERVENTIONS = "research-interventions-v1"

    val idsByFeature: Map<NotificationAccessFeature, String> = mapOf(
        NotificationAccessFeature.COLLECTION to COLLECTION,
        NotificationAccessFeature.DAILY_STATUS to DAILY_STATUS,
        NotificationAccessFeature.INTERVENTIONS to INTERVENTIONS,
    )

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
            ),
        )
    }
}
