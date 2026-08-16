package cool.jacoblin.particeps.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.model.ExperimentStateMachine
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyMetadata
import cool.jacoblin.particeps.core.model.TransitionReason
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyDataJsonCodecTest {
    @Test
    fun currentV2RoundTripsWithItsRequiredClockCheckpoint() {
        val metadata = runningMetadata()

        val decoded = StudyDataJsonCodec.decodeMetadataDocument(StudyDataJsonCodec.encodeMetadata(metadata))

        assertEquals(metadata, decoded.metadata)
        assertFalse(decoded.migratedFromV1)
    }

    @Test
    fun exactCurrentV1LayoutIsTheOnlyClocklessStartedMigrationInput() {
        val metadata = runningMetadata()
        val v1 = JSONObject(StudyDataJsonCodec.encodeMetadata(metadata).toString(Charsets.UTF_8)).apply {
            remove("layout_version")
            remove("clock_checkpoint")
        }.toString().toByteArray(Charsets.UTF_8)

        val decoded = StudyDataJsonCodec.decodeMetadataDocument(v1)

        assertTrue(decoded.migratedFromV1)
        assertEquals(metadata.copy(clockCheckpoint = null), decoded.metadata)
    }

    @Test
    fun v2StartedMetadataCannotEraseItsClockCheckpoint() {
        val invalid = runningMetadata().copy(clockCheckpoint = null)

        assertThrows(IllegalArgumentException::class.java) {
            StudyDataJsonCodec.encodeMetadata(invalid)
        }
    }

    @Test
    fun lifecycleReasonMustBeValidForItsSourceState() {
        val json = JSONObject(StudyDataJsonCodec.encodeMetadata(runningMetadata()).toString(Charsets.UTF_8))
        json.getJSONArray("transitions").getJSONObject(4).put("reason", "PARTICIPANT_RESUMED")

        assertThrows(IllegalArgumentException::class.java) {
            StudyDataJsonCodec.decodeMetadata(json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun durableEventMustSatisfyTheFullDraftSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordedEvent(
                sequenceNumber = 1,
                collectorId = "x",
                payloadSchemaVersion = 0,
                observedTime = ResearchTime(1, 1, "boot"),
                payloadType = "bad",
                fields = emptyMap(),
            )
        }
    }

    private fun runningMetadata(): StudyMetadata {
        val machine = ExperimentStateMachine()
        var metadata = StudyMetadata.initial("codec-study", "codec-config")
        var tick = 0L
        fun advance(state: ExperimentState, reason: TransitionReason) {
            tick += 1
            metadata = machine.transition(
                metadata,
                state,
                reason,
                ResearchTime(1_000 + tick, 2_000 + tick, "boot"),
            )
        }
        advance(ExperimentState.CONFIG_VERIFIED, TransitionReason.CONFIGURATION_SIGNATURE_VERIFIED)
        advance(ExperimentState.CONSENT_PENDING, TransitionReason.CONSENT_REVIEW_OPENED)
        advance(ExperimentState.ACCESS_SETUP, TransitionReason.CONSENT_ACCEPTED)
        advance(ExperimentState.READY, TransitionReason.ACCESS_PREFLIGHT_PASSED)
        advance(ExperimentState.RUNNING, TransitionReason.PARTICIPANT_STARTED)
        val start = metadata.transitions.last().time
        return metadata.copy(
            clockCheckpoint = StudyClockCheckpoint(
                studyElapsedNanos = 0,
                activeCollectionElapsedNanos = 0,
                anchor = start,
                deadlineUtcMillis = start.wallTimeUtcMillis + 60_000,
                deadlineUtcTrusted = true,
            ),
        )
    }
}
