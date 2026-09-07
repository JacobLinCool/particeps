package cool.jacoblin.particeps.collector.screenstate

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import cool.jacoblin.particeps.core.collector.*
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.ScreenStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScreenStateCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext
    override val descriptor = CollectorDescriptor(
        id = ScreenStateV1ProfileConfiguration.SOURCE_ID,
        displayName = "Screen power and lock state",
        accessKinds = emptySet(),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[ScreenStateV1ProfileConfiguration.SOURCE_ID]),
    )
    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
        require(configuration is ScreenStateV1ProfileConfiguration)
        return ScreenStateCollector(applicationContext, context)
    }
}

private class ScreenStateCollector(
    private val androidContext: Context,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, 256) {
    private val displays = androidContext.getSystemService(DisplayManager::class.java)
    private val power = androidContext.getSystemService(PowerManager::class.java)
    private val keyguard = androidContext.getSystemService(KeyguardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = changed(displayId)
        override fun onDisplayRemoved(displayId: Int) = changed(displayId)
        override fun onDisplayChanged(displayId: Int) = changed(displayId)
        private fun changed(id: Int) { if (active && id == Display.DEFAULT_DISPLAY) snapshot() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (active) snapshot() }
    }

    override suspend fun registerSource(): SourceRegistrationResult = withContext(Dispatchers.Main.immediate) {
        var displayRegistered = false
        var receiverRegistered = false
        registerSourceWithRollback(
            register = {
                active = true
                displays.registerDisplayListener(listener, handler)
                displayRegistered = true
                androidContext.registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }, Context.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            },
            rollback = {
                active = false
                completeSourceTeardown(
                    { if (displayRegistered) displays.unregisterDisplayListener(listener) },
                    { if (receiverRegistered) androidContext.unregisterReceiver(receiver) },
                )
            },
        )
    }
    override suspend fun onSourceAdmitted() = withContext(Dispatchers.Main.immediate) { snapshot() }
    override suspend fun unregisterSource(): SourceTeardownResult = withContext(Dispatchers.Main.immediate) {
        active = false
        completeSourceTeardown(
            { displays.unregisterDisplayListener(listener) },
            { androidContext.unregisterReceiver(receiver) },
        )
        SourceTeardownResult.Released
    }
    private fun snapshot() {
        capture {
            EventDraft(
                EventTypeKey(EventSourceId(ScreenStateV1ProfileConfiguration.SOURCE_ID), 1, "SCREEN_STATE"),
                context.clocks.now(),
                mapOf(
                    "display_state" to displayStateName(displays.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN),
                    "interactive" to power.isInteractive.toString(),
                    "keyguard_locked" to keyguard.isKeyguardLocked.toString(),
                ),
            )
        }
    }
}

internal fun displayStateName(state: Int): String = when (state) {
    Display.STATE_OFF -> "OFF"
    Display.STATE_ON -> "ON"
    Display.STATE_DOZE -> "DOZE"
    Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
    Display.STATE_VR -> "VR"
    Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
    else -> "UNKNOWN"
}
