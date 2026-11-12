package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.CollectorTier
import cool.jacoblin.particeps.core.definition.GyroscopeV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.NamedCollectorProfile
import cool.jacoblin.particeps.core.definition.ScreenStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.UsageEventsV1ProfileConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test

class ParticipantCadenceTest {
    @Test
    fun cadenceSpansEverySignedProfileWithoutNamingTheOneInForce() {
        val gyroscope = CollectorResourceConfiguration(
            id = GyroscopeV1ProfileConfiguration.SOURCE_ID,
            tier = CollectorTier.OPTIONAL,
            profiles = listOf(
                NamedCollectorProfile("fast", GyroscopeV1ProfileConfiguration(0, 20_000)),
                NamedCollectorProfile("slow", GyroscopeV1ProfileConfiguration(0, 1_000_000)),
            ),
        )
        assertEquals(ParticipantCadence.Every(20, 1_000), participantCadence(gyroscope))

        val usage = CollectorResourceConfiguration(
            id = UsageEventsV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(NamedCollectorProfile("hourly", UsageEventsV1ProfileConfiguration(3_600))),
        )
        assertEquals(ParticipantCadence.Every(3_600_000, 3_600_000), participantCadence(usage))

        val screen = CollectorResourceConfiguration(
            id = ScreenStateV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(NamedCollectorProfile("always", ScreenStateV1ProfileConfiguration())),
        )
        assertEquals(ParticipantCadence.WhenChanged, participantCadence(screen))
    }
}
