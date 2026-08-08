package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.CollectorAccessRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyAccessPolicyTest {
    private val policy = StudyAccessPolicy()

    @Test
    fun notificationsAreRequiredEvenWhenNoCollectorNeedsAccess() {
        val plan = policy.plan(emptyList())
        val notifications = plan.single()

        assertEquals(AccessKind.NOTIFICATIONS, notifications.requirement.kind)
        assertTrue(notifications.requirement.required)
        assertEquals(
            setOf(
                StudyAccessOwner.Feature(
                    StudyAccessFeature.STUDY_NOTIFICATIONS,
                    required = true,
                ),
            ),
            notifications.owners,
        )
    }

    @Test
    fun sharedAccessIsDeduplicatedAndPreservesEveryCollectorOwner() {
        val plan = policy.plan(
            listOf(
                CollectorAccessRequirement(
                    "network_usage.v1",
                    AccessRequirement(AccessKind.USAGE_ACCESS, required = true),
                ),
                CollectorAccessRequirement(
                    "usage_events.v1",
                    AccessRequirement(AccessKind.USAGE_ACCESS, required = false),
                ),
            ),
        )
        val usageAccess = plan.single { it.requirement.kind == AccessKind.USAGE_ACCESS }

        assertTrue(usageAccess.requirement.required)
        assertEquals(
            setOf(
                StudyAccessOwner.Collector("network_usage.v1", required = true),
                StudyAccessOwner.Collector("usage_events.v1", required = false),
            ),
            usageAccess.owners,
        )
    }
}
