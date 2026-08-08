package cool.jacoblin.particeps.collector.keyboardime

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.definition.KeyboardTouchConfiguration
import cool.jacoblin.particeps.core.collector.PrivacyClass
import cool.jacoblin.particeps.core.collector.ProtocolEventContracts
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.SerializedCallbackCollector
import cool.jacoblin.particeps.core.collector.SourceRegistrationResult
import cool.jacoblin.particeps.core.collector.SourceTeardownResult

class KeyboardTouchCollectorPlugin : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id = KeyboardTouchConfiguration.ID,
        displayName = "Research keyboard touch",
        privacyClass = PrivacyClass.RESTRICTED,
        accessKinds = setOf(
            AccessKind.RESEARCH_KEYBOARD_ENABLED,
            AccessKind.RESEARCH_KEYBOARD_SELECTED,
        ),
        eventContract = requireNotNull(ProtocolEventContracts[KeyboardTouchConfiguration.ID]),
    )

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
    override suspend fun registerSource(): SourceRegistrationResult {
        ImeObservationBridge.install(configuration.trajectorySamplingHz, ::capture)
        return SourceRegistrationResult.Registered
    }

    override suspend fun unregisterSource(): SourceTeardownResult {
        ImeObservationBridge.uninstall()
        return SourceTeardownResult.Released
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
