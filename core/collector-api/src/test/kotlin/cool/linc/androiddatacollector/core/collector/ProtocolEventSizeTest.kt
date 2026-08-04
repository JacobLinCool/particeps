package cool.linc.androiddatacollector.core.collector

import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.model.ResearchTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolEventSizeTest {
    @Test
    fun countsTheExactCanonicalProtocolEventBytes() {
        val event = EventDraft(
            collectorId = "test.v1",
            payloadSchemaVersion = 1,
            observedTime = ResearchTime(12, 34, "boot-one"),
            payloadType = "TEST_EVENT",
            fields = mapOf("note" to "line\n雪"),
        )
        val expected = (
            "{\"collector_id\":\"test.v1\",\"fields\":{\"note\":\"line\\n雪\"}," +
                "\"observed_time\":{\"boot_session_id\":\"boot-one\"," +
                "\"monotonic_time_nanos\":\"34\",\"wall_time_utc_millis\":\"12\"}," +
                "\"payload_schema_version\":1,\"payload_type\":\"TEST_EVENT\"," +
                "\"sequence_number\":\"99\"}"
            ).toByteArray(Charsets.UTF_8).size

        assertEquals(expected, event.protocolEncodedBytes(99))
    }

    @Test
    fun refusesTextThatCannotBeRepresentedAsUnicodeJcs() {
        val event = EventDraft(
            collectorId = "test.v1",
            payloadSchemaVersion = 1,
            observedTime = ResearchTime(12, 34, "boot-one"),
            payloadType = "TEST_EVENT",
            fields = mapOf("note" to "\uD800"),
        )

        assertThrows(IllegalArgumentException::class.java) { event.protocolEncodedBytes(1) }
    }
}
