package cool.jacoblin.particeps.collector.keyboardime

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.KeyboardTouchV1ProfileConfiguration
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.SerializedCallbackCollector
import cool.jacoblin.particeps.core.collector.SourceRegistrationResult
import cool.jacoblin.particeps.core.collector.SourceTeardownResult

class KeyboardTouchCollectorPlugin : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id = KeyboardTouchV1ProfileConfiguration.SOURCE_ID,
        displayName = "Research keyboard touch",
        accessKinds = setOf(
            AccessKind.RESEARCH_KEYBOARD_ENABLED,
            AccessKind.RESEARCH_KEYBOARD_SELECTED,
        ),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[KeyboardTouchV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(
        configuration: CollectorProfileConfiguration,
        context: CollectorContext,
    ): Collector = KeyboardTouchCollector(
        configuration as? KeyboardTouchV1ProfileConfiguration
            ?: throw IllegalArgumentException("Invalid keyboard-touch configuration"),
        context,
    )
}

private class KeyboardTouchCollector(
    private val configuration: KeyboardTouchV1ProfileConfiguration,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY) {
    override suspend fun registerSource(): SourceRegistrationResult {
        ImeObservationBridge.install(configuration.trajectorySamplingHz.toInt(), ::capture)
        return SourceRegistrationResult.Registered
    }

    override suspend fun unregisterSource(): SourceTeardownResult {
        ImeObservationBridge.uninstall()
        return SourceTeardownResult.Released
    }

    private fun capture(observation: ImeTouchObservation) {
        capture {
            EventDraft(
                type = EventTypeKey(
                    EventSourceId(KeyboardTouchV1ProfileConfiguration.SOURCE_ID),
                    1,
                    "KEYBOARD_TOUCH",
                ),
                observedTime = context.clocks.now(),
                fields = mapOf(
                    "action" to observation.action,
                    "event_uptime_millis" to observation.eventUptimeMillis.toString(),
                    "down_uptime_millis" to observation.downUptimeMillis.toString(),
                    "pointer_id" to observation.pointerId.toString(),
                    "relative_x" to observation.relativeX.toString(),
                    "relative_y" to observation.relativeY.toString(),
                    "pressure" to observation.pressure.toString(),
                    "size" to observation.size.toString(),
                    "orientation_radians" to observation.orientationRadians.toString(),
                    "tool_type" to observation.toolType.toString(),
                    "key_category" to observation.keyCategory,
                    "geometry_version" to GEOMETRY_VERSION,
                ),
            )
        }
    }

    private companion object {
        const val CHANNEL_CAPACITY = 2_048
        const val GEOMETRY_VERSION = "qwerty-v1"
    }
}
