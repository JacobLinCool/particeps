package cool.jacoblin.particeps.core.collector

import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.ConditionEpochId
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolEventSizeTest {
    @Test
    fun countsTheExactCanonicalProtocolEventBytes() {
        val event = EventDraft(
            type = EventTypeKey(EventSourceId("test.v1"), 1, "TEST_EVENT"),
            observedTime = ResearchTime(12, 34, "boot-one"),
            fields = mapOf("note" to "line\n雪"),
        )
        val epoch = ConditionEpochId("550e8400-e29b-41d4-a716-446655440000")
        val expected = (
            "{\"condition_epoch_id\":\"550e8400-e29b-41d4-a716-446655440000\"," +
                "\"event_type\":\"TEST_EVENT\",\"fields\":{\"note\":\"line\\n雪\"}," +
                "\"observed_time\":{\"boot_session_id\":\"boot-one\"," +
                "\"monotonic_time_nanos\":\"34\",\"wall_time_utc_millis\":\"12\"}," +
                "\"schema_version\":1,\"sequence_number\":\"99\",\"source_id\":\"test.v1\"}"
            ).toByteArray(Charsets.UTF_8).size

        assertEquals(expected, event.protocolEncodedBytes(99, epoch))
    }

    @Test
    fun refusesTextThatCannotBeRepresentedAsUnicodeJcs() {
        val event = EventDraft(
            type = EventTypeKey(EventSourceId("test.v1"), 1, "TEST_EVENT"),
            observedTime = ResearchTime(12, 34, "boot-one"),
            fields = mapOf("note" to "\uD800"),
        )

        assertThrows(IllegalArgumentException::class.java) { event.protocolEncodedBytes(1, null) }
    }
}
