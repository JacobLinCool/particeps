package cool.jacoblin.particeps.core.runtime

import cool.jacoblin.particeps.core.automation.DeliveryMode
import cool.jacoblin.particeps.core.automation.EventConditionKind
import cool.jacoblin.particeps.core.automation.EventPresenceRole
import cool.jacoblin.particeps.core.automation.ScalarType
import cool.jacoblin.particeps.core.automation.TriggerScope
import cool.jacoblin.particeps.core.automation.TypedFieldDecoder
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedEventContractRegistryTest {
    @Test
    fun lifecycleOutputsRemainAuditOnlyAcrossTheGeneratedAutomationBridge() {
        setOf("STUDY_STARTED", "STUDY_RESUMED", "STUDY_RUNNING").forEach { eventType ->
            val contract = requireNotNull(
                GeneratedEventContractRegistry.contract(
                    EventTypeKey(EventSourceId("study_runtime.v1"), 1, eventType),
                ),
            )
            assertEquals(eventType, TriggerScope.AUDIT_ONLY, contract.triggerScope)
            assertEquals(eventType, emptySet<EventConditionKind>(), contract.conditionKinds)
        }
    }

    @Test
    fun projectsGeneratedCollectorContractWithoutASecondSchema() {
        val contract = requireNotNull(
            GeneratedEventContractRegistry.contract(
                EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_RESUMED"),
            ),
        )

        assertEquals(TriggerScope.RESEARCHER, contract.triggerScope)
        assertEquals(DeliveryMode.RETROSPECTIVE, contract.deliveryMode)
        assertEquals(ScalarType.STRING, contract.fields.getValue("package_name").type)
        assertEquals(ScalarType.SHA256, contract.fields.getValue("activity_component_token").type)
        assertTrue(contract.fields.getValue("activity_component_token").keyedPresenceKey)
        assertEquals(
            setOf(
                EventConditionKind.EVENT_MATCH,
                EventConditionKind.KEYED_PRESENCE_ENTER,
                EventConditionKind.SEQUENCE_STEP,
                EventConditionKind.WINDOW_COUNT,
            ),
            contract.conditionKinds,
        )
        assertEquals("usage_activity_foreground", contract.presence?.groupId)
        assertEquals(EventPresenceRole.ENTER, contract.presence?.role)
        assertEquals(setOf("activity_component_token"), contract.presence?.keyFields)
        assertNull(contract.rateBound)
    }

    @Test
    fun rejectsUnknownSchemaAndProjectsHardRateBound() {
        assertNull(
            GeneratedEventContractRegistry.contract(
                EventTypeKey(EventSourceId("ambient_light.v1"), 2, "AMBIENT_LIGHT_SAMPLE"),
            ),
        )
        val contract = requireNotNull(
            GeneratedEventContractRegistry.contract(
                EventTypeKey(EventSourceId("ambient_light.v1"), 1, "AMBIENT_LIGHT_SAMPLE"),
            ),
        )
        assertEquals(18_000, contract.rateBound?.maximumEvents)
        assertEquals(3_600, contract.rateBound?.periodSeconds)
    }

    @Test
    fun preservesStringIntegerAndFloatWireBoundsAcrossTheGeneratedBridge() {
        val usage = requireNotNull(
            GeneratedEventContractRegistry.contract(
                EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_RESUMED"),
            ),
        )
        val packageName = usage.fields.getValue("package_name")
        TypedFieldDecoder.decodePredicateLiteral(packageName, "a".repeat(255))
        assertThrows(IllegalArgumentException::class.java) {
            TypedFieldDecoder.decodePredicateLiteral(packageName, "a".repeat(256))
        }

        val accelerometer = requireNotNull(
            GeneratedEventContractRegistry.contract(
                EventTypeKey(EventSourceId("accelerometer.v1"), 1, "ACCELEROMETER_SAMPLE"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TypedFieldDecoder.decodeEventWire(accelerometer.fields.getValue("accuracy"), "2147483648")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedFieldDecoder.decodeEventWire(
                accelerometer.fields.getValue("source_elapsed_realtime_nanos"),
                "-1",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedFieldDecoder.decodePredicateLiteral(
                accelerometer.fields.getValue("x_meters_per_second_squared"),
                "1.0E100",
            )
        }
    }
}
