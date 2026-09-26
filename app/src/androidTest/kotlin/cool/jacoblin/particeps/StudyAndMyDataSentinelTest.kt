package cool.jacoblin.particeps

import android.text.format.Formatter
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.application.ParticipantExportSummary as CoreExportSummary
import cool.jacoblin.particeps.core.application.ParticipantRuntimeStatus
import cool.jacoblin.particeps.core.application.StudyAccessStatus
import cool.jacoblin.particeps.core.application.StudySessionSnapshot
import cool.jacoblin.particeps.core.application.participantStudySummary
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.model.ExperimentState
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders a signed-configuration fixture through the real participant projection and asserts on
 * the Compose semantics tree, which is also what accessibility services read.
 *
 * Hidden fields carry sentinels: the word "sentinel" in every identifier and in researcher text
 * that has no participant surface here (surveys, notification copy), plus distinctive numbers for
 * traffic caps, numeric sensor and polling parameters, schedule times, availability, and the
 * storage quota. The configuration ID is one of them, since it differs between study arms. Hidden
 * fields whose valid values cannot be told apart from ordinary UI text (a window's study days, the
 * activation cap, issue and expiry times, the minimum client version, boolean profile settings)
 * carry ordinary values and are not checked here. Platform-floor fields carry recognisable floor
 * values.
 */
@RunWith(AndroidJUnit4::class)
class StudyAndMyDataSentinelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun runningScreenAndStudyAndMyDataShowTheFloorAndNothingElseFromTheConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val verified = verifiedFixture()
        val model = StudySessionSnapshot(
            initialized = true,
            study = participantStudySummary(verified),
            runtime = runningStatus(),
            access = listOf(
                StudyAccessStatus(AccessKind.NOTIFICATIONS, true, AccessResolution.Satisfied, null),
                StudyAccessStatus(AccessKind.USAGE_ACCESS, true, AccessResolution.Satisfied, null),
                StudyAccessStatus(AccessKind.GYROSCOPE_HARDWARE, true, AccessResolution.Satisfied, null),
            ),
            lastExport = CoreExportSummary(commitCount = 12, eventCount = 1_200, byteCount = 2_500_000),
        ).toParticipantUiModel()
        composeRule.setContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = model,
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions(),
            )
        }
        val categoryNames = listOf(
            R.string.collector_gyroscope_name,
            R.string.collector_network_state_name,
            R.string.collector_screen_state_name,
            R.string.collector_usage_events_name,
            R.string.collector_vpn_state_name,
        ).map(context::getString)

        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo()
        val running = semanticsDump()
        assertNoSentinel(running, "running screen")
        assertShows(running, listOf(FLOOR_TITLE, context.getString(R.string.my_data_entry)) + categoryNames)

        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_SCREEN).assertExists()
        composeRule.waitUntil {
            semanticsDump().contains(Formatter.formatShortFileSize(context, STORED_BYTES))
        }
        val screen = semanticsDump()
        assertNoSentinel(screen, "Study and my data")
        assertShows(
            screen,
            listOf(
                FLOOR_TITLE,
                FLOOR_PURPOSE,
                FLOOR_RESEARCHER,
                FLOOR_CONTACT,
                FLOOR_CONSENT,
                FLOOR_CONSENT_VERSION,
                INSTANCE_ID,
                verified.configuration.signer.fingerprint,
                context.getString(R.string.consent_identity_assigned_code, FLOOR_ASSIGNED_ID),
                context.getString(R.string.consent_upload_destination, FLOOR_UPLOAD_HOST),
                context.getString(R.string.traffic_shaping_disclosure),
                context.getString(R.string.collector_usage_events_records),
                context.getString(R.string.participation_day_value, 2, 5),
                context.getString(R.string.data_access_line, context.getString(R.string.access_usage_access)),
                context.getString(R.string.rights_withdraw_upload),
                context.getString(R.string.rights_delete_upload),
                Formatter.formatShortFileSize(context, STORED_BYTES),
            ) + categoryNames,
        )
    }

    private fun semanticsDump(): String =
        composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)

    private fun assertNoSentinel(dump: String, surface: String) {
        val lowercase = dump.lowercase()
        assertFalse("$surface shows a hidden configuration field:\n$dump", "sentinel" in lowercase)
        HIDDEN_VALUES.forEach { value ->
            assertFalse("$surface shows hidden value $value:\n$dump", value.lowercase() in lowercase)
        }
    }

    private fun assertShows(dump: String, expected: List<String>) {
        expected.forEach { assertTrue("Missing floor information: $it\n$dump", it in dump) }
    }

    private fun verifiedFixture(): VerifiedConfiguration {
        val canonical = StudyConfigurationCodec.canonicalize(SENTINEL_STUDY.toByteArray())
        val configuration = StudyConfigurationCodec.decode(canonical)
        return VerifiedConfiguration(
            configuration = configuration,
            canonicalConfigurationBytes = canonical,
            signerKeyId = configuration.signer.keyId,
            signature = ByteArray(64),
            configurationSha256 = MessageDigest.getInstance("SHA-256")
                .digest(canonical)
                .joinToString("") { "%02x".format(it) },
            signerAnchored = false,
        )
    }

    /** Day 2 of 5: started 26 whole hours ago, 20 of them collecting, trusted deadline. */
    private fun runningStatus(): ParticipantRuntimeStatus {
        val hour = 3_600_000L
        val measuredAt = System.currentTimeMillis() / hour * hour
        val startedAt = measuredAt - 26 * hour
        return ParticipantRuntimeStatus(
            state = ExperimentState.RUNNING,
            participantInstanceId = INSTANCE_ID,
            lifetimeDataEventCount = 1_234,
            durableThroughCommit = 40,
            uploadedThroughCommit = 30,
            retainedFromCommit = 1,
            startedAtUtcMillis = startedAt,
            deadlineUtcMillis = startedAt + 120 * hour,
            deadlineUtcTrusted = true,
            activeRunningElapsedMillis = 20 * hour,
            calendarElapsedMillis = 26 * hour,
            elapsedMeasuredAtUtcMillis = measuredAt,
            stateEnteredAtUtcMillis = startedAt + 6 * hour,
        )
    }

    private fun actions() = StudyUiActions(
        scan = {},
        import = {},
        demo = null,
        review = {},
        acceptConsent = {},
        completeAccess = {},
        requestAccess = {},
        start = {},
        pause = {},
        resume = {},
        complete = {},
        withdraw = {},
        decline = {},
        export = {},
        cancelExport = {},
        delete = {},
        retryRecovery = {},
        resetAndRestart = {},
        readLocalStorageBytes = { STORED_BYTES },
    )

    private companion object {
        const val FLOOR_TITLE = "Floor study title"
        const val FLOOR_PURPOSE = "Floor purpose of the study."
        const val FLOOR_RESEARCHER = "Floor Researcher"
        const val FLOOR_CONTACT = "floor-contact@example.invalid"
        const val FLOOR_CONSENT = "Floor consent summary."
        const val FLOOR_CONSENT_VERSION = "floor-consent-7"
        const val FLOOR_ASSIGNED_ID = "P-FLOOR-42"
        const val FLOOR_UPLOAD_HOST = "upload.example.invalid"
        const val INSTANCE_ID = "5f0c3a1e-9d2b-4c7a-8e61-2b4d6f8a0c13"
        const val STORED_BYTES = 3_145_728L
        const val HPKE_PUBLIC_KEY = "ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q"
        const val SIGNER_PUBLIC_KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"

        /** Numeric and time values from hidden fields, with and without digit grouping. */
        val HIDDEN_VALUES = listOf(
            "73519", "73,519", "86423", "86,423",
            "987654", "987,654", "4567890", "4,567,890",
            "4219", "4,219", "21611", "21,611",
            "7777777777", "7,777,777,777",
            "13:37", "21:43", "09:47", "11:29",
            HPKE_PUBLIC_KEY, SIGNER_PUBLIC_KEY,
        )

        val SENTINEL_STUDY = """
        {
          "schema_version": 1,
          "experiment_id": "sentinel-hidden-experiment",
          "configuration_id": "sentinel-hidden-experiment-3k9q2a",
          "assigned_participant_id": "$FLOOR_ASSIGNED_ID",
          "issued_at": "2026-01-01T00:00:00Z",
          "expires_at": "2030-01-01T00:00:00Z",
          "platform": "android",
          "minimum_client_version": "1",
          "title": "$FLOOR_TITLE",
          "researcher": { "name": "$FLOOR_RESEARCHER", "contact": "$FLOOR_CONTACT" },
          "purpose": "$FLOOR_PURPOSE",
          "duration_hours": 120,
          "consent": { "document_version": "$FLOOR_CONSENT_VERSION", "summary": "$FLOOR_CONSENT" },
          "collectors": [
            { "id": "gyroscope.v1", "required": true, "profiles": [
              { "id": "sentinel-profile-gyroscope",
                "config": { "sampling_period_us": 987654, "maximum_report_latency_us": 4567890 } }
            ] },
            { "id": "network_state.v1", "required": true, "profiles": [
              { "id": "sentinel-profile-network-state", "config": { "include_bandwidth_estimates": true } }
            ] },
            { "id": "screen_state.v1", "required": false, "profiles": [
              { "id": "sentinel-profile-screen-state", "config": {} }
            ] },
            { "id": "usage_events.v1", "required": true, "profiles": [
              { "id": "sentinel-profile-usage-events", "config": { "poll_interval_seconds": 4219 } }
            ] },
            { "id": "vpn_state.v1", "required": true, "profiles": [
              { "id": "sentinel-profile-vpn-state", "config": {} }
            ] }
          ],
          "surveys": [
            {
              "id": "sentinel-survey",
              "title": { "default": "SENTINEL survey title", "translations": {} },
              "description": { "default": "SENTINEL survey description", "translations": {} },
              "questions": [
                {
                  "type": "single_choice",
                  "id": "sentinel-question",
                  "prompt": { "default": "SENTINEL question prompt", "translations": {} },
                  "required": true,
                  "options": [
                    { "id": "sentinel-option-a", "label": { "default": "SENTINEL option A", "translations": {} } },
                    { "id": "sentinel-option-b", "label": { "default": "SENTINEL option B", "translations": {} } }
                  ]
                }
              ]
            }
          ],
          "interventions": [
            {
              "id": "sentinel-intervention",
              "required": true,
              "action": {
                "type": "survey",
                "notification_title": "SENTINEL notification title",
                "notification_message": "SENTINEL notification message",
                "survey_id": "sentinel-survey"
              }
            }
          ],
          "automations": [
            { "type": "resource_binding", "id": "sentinel-automation-bind-gyroscope",
              "resource": { "kind": "collector", "id": "gyroscope.v1" },
              "cases": [ { "condition": { "type": "study_session_active" },
                "profile_id": "sentinel-profile-gyroscope" } ],
              "default_profile_id": "sentinel-profile-gyroscope" },
            { "type": "resource_binding", "id": "sentinel-automation-bind-network-state",
              "resource": { "kind": "collector", "id": "network_state.v1" },
              "cases": [ { "condition": { "type": "study_session_active" },
                "profile_id": "sentinel-profile-network-state" } ],
              "default_profile_id": "sentinel-profile-network-state" },
            { "type": "resource_binding", "id": "sentinel-automation-bind-screen-state",
              "resource": { "kind": "collector", "id": "screen_state.v1" },
              "cases": [ { "condition": { "type": "study_session_active" },
                "profile_id": "sentinel-profile-screen-state" } ],
              "default_profile_id": "sentinel-profile-screen-state" },
            { "type": "resource_binding", "id": "sentinel-automation-bind-traffic",
              "resource": { "kind": "actuator", "id": "traffic-shaping.v1" },
              "cases": [ { "condition": { "type": "study_local_window", "first_day": 2, "last_day": 4,
                "start_local_time": "13:37", "end_local_time": "21:43" },
                "profile_id": "sentinel-throttle-profile" } ],
              "default_profile_id": "sentinel-baseline-profile" },
            { "type": "resource_binding", "id": "sentinel-automation-bind-usage-events",
              "resource": { "kind": "collector", "id": "usage_events.v1" },
              "cases": [ { "condition": { "type": "study_session_active" },
                "profile_id": "sentinel-profile-usage-events" } ],
              "default_profile_id": "sentinel-profile-usage-events" },
            { "type": "resource_binding", "id": "sentinel-automation-bind-vpn-state",
              "resource": { "kind": "collector", "id": "vpn_state.v1" },
              "cases": [ { "condition": { "type": "study_session_active" },
                "profile_id": "sentinel-profile-vpn-state" } ],
              "default_profile_id": "sentinel-profile-vpn-state" },
            { "type": "occurrence", "id": "sentinel-automation-occurrence",
              "trigger": { "type": "condition_rising_edge", "condition": { "type": "study_local_window",
                "first_day": 3, "last_day": 3, "start_local_time": "09:47", "end_local_time": "11:29" } },
              "guard": null, "intervention_id": "sentinel-intervention", "availability_seconds": 21611,
              "cooldown": null, "maximum_activations": 1 }
          ],
          "traffic_shaping": {
            "target_packages": ["com.sentinel.hidden.target"],
            "profiles": [
              { "id": "sentinel-baseline-profile", "uplink_kbps": null, "downlink_kbps": null },
              { "id": "sentinel-throttle-profile", "uplink_kbps": 73519, "downlink_kbps": 86423 }
            ]
          },
          "storage": { "maximum_local_bytes": 7777777777 },
          "signer": { "key_id": "sentinel-signer-key", "public_key": "$SIGNER_PUBLIC_KEY" },
          "export": { "researcher_key_id": "sentinel-export-key", "hpke_public_key": "$HPKE_PUBLIC_KEY" },
          "upload": {
            "endpoint": "https://$FLOOR_UPLOAD_HOST/sentinel-upload-path",
            "interval_minutes": 90,
            "allow_metered": false
          }
        }
        """.trimIndent()
    }
}
