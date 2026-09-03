package cool.jacoblin.particeps.core.resource

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import org.junit.Assert.assertThrows
import org.junit.Test

class PeriodicResourceAuditContractTest {
    @Test
    fun receiptRequiresOneBoundedAtomicDraftSet() {
        val evidence = ResourceAuditEvidence(
            ResourceKey(ResourceKind.ACTUATOR, "traffic-shaping.v1"),
            ResourceGeneration(1uL),
            "baseline",
            Sha256Digest("a".repeat(64)),
        )
        val draft = EventDraft(
            EventTypeKey(EventSourceId("traffic_shaping.v1"), 1, "TRAFFIC_SHAPING_SNAPSHOT"),
            ResearchTime(1, 2, "boot-test"),
            mapOf("condition_epoch_id" to "123e4567-e89b-42d3-a456-426614174091"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ResourceAuditReceipt(evidence, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceAuditReceipt(evidence, List(5) { draft })
        }
    }
}
