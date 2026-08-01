package cool.linc.androiddatacollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class CollectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active research collection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when a consented research study is collecting data"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val title = intent?.getStringExtra(EXTRA_STUDY_TITLE) ?: "Research study"
        val location = intent?.getBooleanExtra(EXTRA_LOCATION, false) == true
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        startForeground(NOTIFICATION_ID, notification(title), types)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(studyTitle: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Research collection active")
            .setContentText(studyTitle)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val ACTION_START = "cool.linc.androiddatacollector.START_COLLECTION"
        private const val EXTRA_STUDY_TITLE = "study_title"
        private const val EXTRA_LOCATION = "location"
        private const val CHANNEL_ID = "active-research-collection"
        private const val NOTIFICATION_ID = 72

        fun start(
            context: Context,
            studyTitle: String,
            location: Boolean,
        ) {
            context.startForegroundService(
                Intent(context, CollectionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_STUDY_TITLE, studyTitle)
                    .putExtra(EXTRA_LOCATION, location),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CollectionService::class.java))
        }
    }
}
