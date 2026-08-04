package cool.linc.androiddatacollector

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectorSummaryTest {
    @Test
    fun durationUnitNeverTruncatesOffLadderConfigurationValues() {
        assertEquals(ExactDuration.Microseconds(1_500_500), exactDuration(1_500_500))
        assertEquals(ExactDuration.Milliseconds(1_500), exactDuration(1_500_000))
        assertEquals(ExactDuration.Seconds(90), exactDuration(90_000_000))
        assertEquals(ExactDuration.Minutes(2), exactDuration(120_000_000))
    }
}
