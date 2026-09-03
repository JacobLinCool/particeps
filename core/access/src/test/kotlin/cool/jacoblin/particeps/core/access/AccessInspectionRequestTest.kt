package cool.jacoblin.particeps.core.access

import cool.jacoblin.particeps.core.collector.AccessInspectionRequest
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.LocationAccessProfile
import cool.jacoblin.particeps.core.collector.NotificationAccessFeature
import cool.jacoblin.particeps.core.definition.LocationV1PriorityValue
import cool.jacoblin.particeps.core.definition.LocationV1ProfileConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccessInspectionRequestTest {
    @Test
    fun locationProfilePreservesEverySignedLocationRequestField() {
        val configuration = LocationV1ProfileConfiguration(
            intervalMillis = 12_345,
            minimumIntervalMillis = 2_345,
            maximumBatchDelayMillis = 67_890,
            minimumDisplacementMillimeters = 4_321,
            priority = LocationV1PriorityValue.HIGH_ACCURACY,
        )

        assertEquals(
            LocationAccessProfile(
                intervalMillis = 12_345,
                minimumIntervalMillis = 2_345,
                maximumBatchDelayMillis = 67_890,
                minimumDisplacementMillimeters = 4_321,
                priority = LocationV1PriorityValue.HIGH_ACCURACY,
            ),
            LocationAccessProfile.from(configuration),
        )
    }

    @Test
    fun locationServicesRequiresAnExactProfileAndRejectsUnownedProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            AccessInspectionRequest(
                requirements = setOf(required(AccessKind.LOCATION_SERVICES)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessInspectionRequest(
                requirements = emptySet(),
                locationProfile = locationProfile(),
            )
        }
    }

    @Test
    fun notificationFeaturesAreClosedAndInterventionsRemainStudySpecific() {
        val baseFeatures = setOf(
            NotificationAccessFeature.COLLECTION,
            NotificationAccessFeature.DAILY_STATUS,
            NotificationAccessFeature.RECOVERY,
        )
        assertEquals(
            baseFeatures,
            AccessInspectionRequest(
                requirements = setOf(required(AccessKind.NOTIFICATIONS)),
                notificationFeatures = baseFeatures,
            ).notificationFeatures,
        )
        assertEquals(
            NotificationAccessFeature.entries.toSet(),
            AccessInspectionRequest(
                requirements = setOf(required(AccessKind.NOTIFICATIONS)),
                notificationFeatures = NotificationAccessFeature.entries.toSet(),
            ).notificationFeatures,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AccessInspectionRequest(
                requirements = setOf(required(AccessKind.NOTIFICATIONS)),
                notificationFeatures = setOf(NotificationAccessFeature.COLLECTION),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessInspectionRequest(
                requirements = emptySet(),
                notificationFeatures = baseFeatures,
            )
        }
    }

    @Test
    fun duplicateKindsWithConflictingRequirednessAreRejectedBeforePlatformInspection() {
        assertThrows(IllegalArgumentException::class.java) {
            AccessInspectionRequest(
                requirements = setOf(
                    AccessRequirement(AccessKind.USAGE_ACCESS, required = true),
                    AccessRequirement(AccessKind.USAGE_ACCESS, required = false),
                ),
            )
        }
    }

    private fun required(kind: AccessKind) = AccessRequirement(kind, required = true)

    private fun locationProfile() = LocationAccessProfile(
        intervalMillis = 10_000,
        minimumIntervalMillis = 5_000,
        maximumBatchDelayMillis = 30_000,
        minimumDisplacementMillimeters = 1_000,
        priority = LocationV1PriorityValue.BALANCED,
    )
}
