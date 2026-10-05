package cool.jacoblin.particeps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cool.jacoblin.particeps.core.application.StudyRecoveryStatus
import kotlinx.coroutines.launch

/**
 * Wakes durable adapters after boot or a civil-clock change. It never reconstructs state from the
 * signed configuration and never resumes a study; recovery truth comes from the commit chain.
 */
class ScheduledWorkRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECOVERY_ACTIONS) return
        val pending = goAsync()
        val application = context.applicationContext as CollectorApplication
        application.applicationScope.launch {
            try {
                val graph = try {
                    application.awaitReady()
                } catch (_: ApplicationStartupException) {
                    Log.e("ParticepsStartup", "Scheduled recovery stopped because application initialization failed")
                    return@launch
                }
                val session = graph.session
                val snapshot = session.snapshot.value
                if (snapshot.recoveryStatus == StudyRecoveryStatus.ACTION_REQUIRED || snapshot.study == null) {
                    return@launch
                }
                if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
                    session.onClockDiscontinuity()
                }
                session.reconcileActionOutbox()
                graph.reconcileTimerWakeups()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val RECOVERY_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
