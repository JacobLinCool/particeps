package cool.jacoblin.particeps.core.access

import cool.jacoblin.particeps.core.collector.NotificationAccessFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationChannelSelectionTest {
    @Test
    fun studiesWithoutInterventionsInspectOnlyCollectionAndDailyChannels() {
        assertEquals(
            setOf("collection", "daily"),
            notificationChannelIds(
                features = setOf(
                    NotificationAccessFeature.COLLECTION,
                    NotificationAccessFeature.DAILY_STATUS,
                ),
                channels = channels(),
            ),
        )
    }

    @Test
    fun interventionStudiesAddOnlyTheClosedInterventionChannel() {
        assertEquals(
            setOf("collection", "daily", "interventions"),
            notificationChannelIds(
                features = NotificationAccessFeature.entries.toSet(),
                channels = channels(),
            ),
        )
    }

    @Test
    fun appOwnedChannelMapMustBeExhaustiveNonBlankAndOneToOne() {
        assertEquals(channels(), validatedNotificationChannelIds(channels()))
        assertThrows(IllegalArgumentException::class.java) {
            validatedNotificationChannelIds(
                channels() - NotificationAccessFeature.INTERVENTIONS,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedNotificationChannelIds(
                channels() + (NotificationAccessFeature.INTERVENTIONS to ""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedNotificationChannelIds(
                channels() + (NotificationAccessFeature.INTERVENTIONS to "daily"),
            )
        }
    }

    private fun channels() = mapOf(
        NotificationAccessFeature.COLLECTION to "collection",
        NotificationAccessFeature.DAILY_STATUS to "daily",
        NotificationAccessFeature.INTERVENTIONS to "interventions",
    )
}
