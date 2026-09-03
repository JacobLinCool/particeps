package cool.jacoblin.particeps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cool.jacoblin.particeps.core.application.StudyRecoveryStatus
import kotlinx.coroutines.flow.first
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
                val snapshot = application.session.snapshot.first { it.initialized }
                if (snapshot.recoveryStatus == StudyRecoveryStatus.ACTION_REQUIRED || snapshot.study == null) {
                    return@launch
                }
                if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
                    application.session.onClockDiscontinuity()
                }
                application.session.reconcileActionOutbox()
                application.currentTimerAdapter?.reconcile(application.session)
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
