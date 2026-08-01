package cool.linc.androiddatacollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val application = context.applicationContext as CollectorApplication
        application.applicationScope.launch {
            try {
                application.session.snapshot.first { it.initialized }
            } finally {
                pending.finish()
            }
        }
    }
}
