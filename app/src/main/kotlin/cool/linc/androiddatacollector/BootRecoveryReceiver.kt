package cool.linc.androiddatacollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cool.linc.androiddatacollector.platform.InterventionDeliveryCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECOVERY_ACTIONS) return
        val pending = goAsync()
        val application = context.applicationContext as CollectorApplication
        application.applicationScope.launch {
            try {
                application.session.snapshot.first { it.initialized }
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    InterventionDeliveryCoordinator.recoverStalePosting {
                        application.session.rescheduleInterventions(recoverStalePosting = true)
                    }
                } else {
                    application.session.rescheduleInterventions()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val RECOVERY_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}
