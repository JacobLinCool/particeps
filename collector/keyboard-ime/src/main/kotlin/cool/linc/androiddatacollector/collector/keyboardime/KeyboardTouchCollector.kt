package cool.linc.androiddatacollector.collector.keyboardime

import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.KeyboardTouchConfiguration
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.SerializedCallbackCollector

class KeyboardTouchCollectorPlugin : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id = KeyboardTouchConfiguration.ID,
        payloadSchemaVersion = 1,
        displayName = "Research keyboard touch",
        privacyClass = PrivacyClass.RESTRICTED,
        maximumEncodedEventBytes = 4_096,
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? KeyboardTouchConfiguration
            ?: throw IllegalArgumentException("Invalid keyboard-touch configuration")
        return setOf(
            AccessRequirement(AccessKind.RESEARCH_KEYBOARD_ENABLED, typed.required),
            AccessRequirement(AccessKind.RESEARCH_KEYBOARD_SELECTED, typed.required),
        )
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector = KeyboardTouchCollector(
        configuration as? KeyboardTouchConfiguration
            ?: throw IllegalArgumentException("Invalid keyboard-touch configuration"),
        context,
    )
}

private class KeyboardTouchCollector(
    private val configuration: KeyboardTouchConfiguration,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY) {
    override suspend fun registerSource() {
        ImeObservationBridge.install(configuration.trajectorySamplingHz, ::capture)
    }

    override suspend fun unregisterSource() {
        ImeObservationBridge.uninstall()
    }

    private fun capture(observation: ImeTouchObservation) {
        capture {
            EventDraft(
                collectorId = KeyboardTouchConfiguration.ID,
                payloadSchemaVersion = 1,
                observedTime = context.clocks.now(),
                payloadType = "KEYBOARD_TOUCH",
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
