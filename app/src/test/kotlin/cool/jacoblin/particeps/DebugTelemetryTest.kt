package cool.jacoblin.particeps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugTelemetryTest {
    @Test
    fun errorsAreReportedByTypeAndFramesNeverByMessage() {
        val secret = "/data/user/0/cool.jacoblin.particeps/files/participant-5f0c3a1e"
        val failure = IllegalStateException(secret, IllegalArgumentException("study experiment-id"))
        val properties = DebugTelemetry.errorProperties(failure)
        assertEquals("java.lang.IllegalStateException", properties["error_type"])
        assertEquals("java.lang.IllegalArgumentException", properties["cause_type"])
        properties.values.forEach { value ->
            assertFalse(value.toString().contains("participant-5f0c3a1e"))
            assertFalse(value.toString().contains("experiment-id"))
        }
    }

    @Test
    fun sizesAndCountsAreCoarse() {
        assertEquals("0-50MB", DebugTelemetry.sizeBucket(12_345_678))
        assertEquals("50-100MB", DebugTelemetry.sizeBucket(73_000_000))
        assertEquals("10k-100k", DebugTelemetry.countBucket(88_801))
        assertEquals("0", DebugTelemetry.countBucket(0))
    }
}
