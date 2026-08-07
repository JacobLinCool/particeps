package cool.jacoblin.particeps

import android.Manifest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.collector.CollectorStatus
import cool.jacoblin.particeps.core.model.ExperimentState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fullParticipantFlowRunsModularCollectorsAndHonorsPause() {
        val session = (composeRule.activity.application as CollectorApplication).session
        composeRule.waitUntil(TIMEOUT_MILLIS) { session.snapshot.value.initialized }
        composeRule.onNodeWithTag(UiTags.IMPORT_DEMO).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            val snapshot = session.snapshot.value
            snapshot.runtime.metadata?.state == ExperimentState.IMPORTED || snapshot.incidentCode != null
        }
        val imported = session.snapshot.value
        assertEquals(
            "Demo import failed: ${imported.incidentCode}",
            ExperimentState.IMPORTED,
            imported.runtime.metadata?.state,
        )
        // Setup shows a position rather than a state name, so the assertion is that the first
        // step's control is the one on screen.
        composeRule.onNodeWithTag(UiTags.REVIEW).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.metadata?.state == ExperimentState.CONSENT_PENDING
        }
        // CONSENT_PENDING renders as two pages: what is collected, then what is being agreed to.
        composeRule.onNodeWithTag(UiTags.CONTINUE).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.CONSENT_CHECKBOX).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.PREPARE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.metadata?.state == ExperimentState.ACCESS_SETUP
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        composeRule.activityRule.scenario.onActivity { session.refreshAccess() }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.access.none { it.requirement.required && !it.granted }
        }
        composeRule.onNodeWithTag(UiTags.ACCESS_COMPLETE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.metadata?.state == ExperimentState.READY
        }

        composeRule.onNodeWithTag(UiTags.START).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.metadata?.state == ExperimentState.RUNNING &&
                session.snapshot.value.runtime.collectorHealth["accelerometer.v1"]?.status == CollectorStatus.ACTIVE
        }
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            val ids = session.snapshot.value.runtime.metadata?.lastEvents.orEmpty().keys
            "app_lifecycle.v1" in ids && "accelerometer.v1" in ids && "network_state.v1" in ids
        }

        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.metadata?.state == ExperimentState.PAUSED &&
                session.snapshot.value.runtime.collectorHealth["accelerometer.v1"]?.status == CollectorStatus.PAUSED
        }
        val countAtPause = session.snapshot.value.runtime.metadata?.eventCount ?: 0
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        assertEquals(countAtPause, session.snapshot.value.runtime.metadata?.eventCount)

        composeRule.onNodeWithTag(UiTags.RESUME).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.metadata?.state == ExperimentState.RUNNING
        }
        composeRule.onNodeWithTag(UiTags.FINISH).performScrollTo().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_confirm)).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.metadata?.state == ExperimentState.COMPLETED
        }

        assertTrue(countAtPause > 0)
        composeRule.onNodeWithTag(UiTags.STATE)
            .assertTextEquals(composeRule.activity.getString(R.string.state_completed))
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo()
        runBlocking { session.deleteLocalData() }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
