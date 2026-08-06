package cool.linc.particeps.collector.keyboardime

import android.view.MotionEvent
import cool.linc.particeps.core.collector.SourceCallbackBoundary

internal data class ImeTouchObservation(
    val action: String,
    val eventUptimeMillis: Long,
    val downUptimeMillis: Long,
    val pointerId: Int,
    val relativeX: Float,
    val relativeY: Float,
    val pressure: Float,
    val size: Float,
    val orientationRadians: Float,
    val toolType: Int,
    val keyCategory: String,
)

internal object ImeObservationBridge {
    private val callbackBoundary = SourceCallbackBoundary()
    private var listener: ((ImeTouchObservation) -> Unit)? = null
    private var minimumMoveIntervalMillis = Long.MAX_VALUE
    private var lastMoveUptimeMillis = Long.MIN_VALUE

    fun install(
        samplingHz: Int,
        listener: (ImeTouchObservation) -> Unit,
    ) = callbackBoundary.activate {
        check(this.listener == null) { "IME observation listener is already installed" }
        minimumMoveIntervalMillis = (1_000L / samplingHz).coerceAtLeast(1L)
        lastMoveUptimeMillis = Long.MIN_VALUE
        this.listener = listener
    }

    fun uninstall() = callbackBoundary.deactivate {
        listener = null
        minimumMoveIntervalMillis = Long.MAX_VALUE
        lastMoveUptimeMillis = Long.MIN_VALUE
    }

    fun publish(
        event: MotionEvent,
        pointerIndex: Int,
        relativeX: Float,
        relativeY: Float,
        keyCategory: String,
    ) {
        val action = event.actionMasked.toActionName() ?: return
        publishObservation(
            ImeTouchObservation(
                action = action,
                eventUptimeMillis = event.eventTime,
                downUptimeMillis = event.downTime,
                pointerId = event.getPointerId(pointerIndex),
                relativeX = relativeX.coerceIn(0f, 1f),
                relativeY = relativeY.coerceIn(0f, 1f),
                pressure = event.getPressure(pointerIndex),
                size = event.getSize(pointerIndex),
                orientationRadians = event.getOrientation(pointerIndex),
                toolType = event.getToolType(pointerIndex),
                keyCategory = keyCategory,
            ),
        )
    }

    internal fun publishObservation(observation: ImeTouchObservation) {
        callbackBoundary.runIfActive {
            val destination = checkNotNull(listener) { "Active IME callback has no listener" }
            if (observation.action == "MOVE") {
                if (
                    lastMoveUptimeMillis != Long.MIN_VALUE &&
                    observation.eventUptimeMillis - lastMoveUptimeMillis < minimumMoveIntervalMillis
                ) {
                    return@runIfActive
                }
                lastMoveUptimeMillis = observation.eventUptimeMillis
            }
            destination(observation)
        }
    }

    private fun Int.toActionName(): String? = when (this) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> null
    }
}
