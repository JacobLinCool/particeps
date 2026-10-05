package cool.jacoblin.particeps

import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.ExperimentState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the actual Android JSON implementation consumed by the Python host verifier. */
@RunWith(AndroidJUnit4::class)
class HostHarnessSafetyPauseProofAndroidTest {
    @Test
    fun safetyPauseWirePreservesBothObservationsExactLongsAndLocalizedNotificationText() {
        val body = "資料收集已暫停。\n請開啟「Particeps」查看 \"研究\"。"
        val first = HostHarnessSafetyPauseObservation(
            HostHarnessSafetyPauseState(true, ExperimentState.PAUSED, Long.MAX_VALUE),
            Long.MAX_VALUE - 1_000,
            AndroidRecoveryReporter.NOTIFICATION_TAG,
            body,
            body,
        )
        val second = first.copy(elapsedRealtimeMillis = Long.MAX_VALUE)
        val encoded = HostHarnessSafetyPauseProof.from(first, second).toJson()
        val parsed = JSONObject(encoded)

        assertEquals(setOf("schema_version", "status", "observations"), parsed.keys().asSequence().toSet())
        assertEquals(1, parsed.getInt("schema_version"))
        assertEquals("VERIFIED_SAFETY_PAUSED", parsed.getString("status"))
        val observations = parsed.getJSONArray("observations")
        assertEquals(2, observations.length())
        for ((index, expected) in listOf(first, second).withIndex()) {
            val actual = observations.getJSONObject(index)
            assertEquals(setOf(
                "initialized", "state", "lifetime_data_event_count", "elapsed_realtime_millis",
                "notification_tag", "notification_body", "expected_notification_body",
            ), actual.keys().asSequence().toSet())
            assertEquals(true, actual.get("initialized"))
            assertEquals("PAUSED", actual.getString("state"))
            assertEquals(Long.MAX_VALUE, actual.getLong("lifetime_data_event_count"))
            assertEquals(expected.elapsedRealtimeMillis, actual.getLong("elapsed_realtime_millis"))
            assertEquals(AndroidRecoveryReporter.NOTIFICATION_TAG, actual.getString("notification_tag"))
            assertEquals(body, actual.getString("notification_body"))
            assertEquals(body, actual.getString("expected_notification_body"))
            for ((key, value) in mapOf(
                "lifetime_data_event_count" to Long.MAX_VALUE,
                "elapsed_realtime_millis" to expected.elapsedRealtimeMillis,
            )) {
                assertTrue(encoded, Regex("\"$key\":$value(?=[,}])").containsMatchIn(actual.toString()))
            }
        }
    }
}
