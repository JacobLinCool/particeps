package cool.linc.particeps.core.definition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkUsageConfigurationTest {
    @Test
    fun acceptsFiveMinutePollInterval() {
        val configuration = NetworkUsageConfiguration(
            required = false,
            transports = setOf(NetworkTransport.WIFI),
            pollIntervalMinutes = 5,
        )

        assertEquals(5, configuration.pollIntervalMinutes)
    }

    @Test
    fun rejectsPollIntervalOutsideItsRange() {
        // A minute is the floor: fast enough to pilot a study without waiting, and the platform's
        // own accounting granularity is what limits usefulness below that, not the schema.
        listOf(0, 1_441).forEach { interval ->
            assertThrows(IllegalArgumentException::class.java) {
                NetworkUsageConfiguration(
                    required = false,
                    transports = setOf(NetworkTransport.WIFI),
                    pollIntervalMinutes = interval,
                )
            }
        }
        NetworkUsageConfiguration(false, setOf(NetworkTransport.WIFI), pollIntervalMinutes = 1)
    }
}
