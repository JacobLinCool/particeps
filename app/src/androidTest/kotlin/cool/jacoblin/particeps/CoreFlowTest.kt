package cool.jacoblin.particeps

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.model.ExperimentState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearStudyBeforeTest() = runBlocking { session().clearStudyDataForTest() }

    @After
    fun clearStudyAfterTest() = runBlocking { session().clearStudyDataForTest() }

    @Test
    fun fullParticipantFlowRunsModularCollectorsAndHonorsPause() {
        val session = session()
        waitUntilExactlyOneNode(hasTestTag(UiTags.IMPORT_DEMO))
        composeRule.onNodeWithTag(UiTags.IMPORT_DEMO).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            val snapshot = session.snapshot.value
            snapshot.runtime.state == ExperimentState.CONFIG_VERIFIED ||
                snapshot.recoveryStatus == cool.jacoblin.particeps.core.application.StudyRecoveryStatus.ACTION_REQUIRED
        }
        val imported = session.snapshot.value
        assertEquals(
            "Demo import failed closed during configuration verification",
            ExperimentState.CONFIG_VERIFIED,
            imported.runtime.state,
        )
        // Setup shows a position rather than a state name, so the assertion is that the first
        // step's control is the one on screen.
        composeRule.onNodeWithTag(UiTags.REVIEW).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.CONSENT_PENDING
        }
        // CONSENT_PENDING renders as two pages: what is collected, then what is being agreed to.
        composeRule.onNodeWithTag(UiTags.CONTINUE).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.CONSENT_CHECKBOX).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.PREPARE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.ACCESS_SETUP
        }

        val notificationAccess = session.snapshot.value.access
            .single { it.kind == AccessKind.NOTIFICATIONS }
        assertTrue(notificationAccess.required)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        runBlocking { session.reconcileAccess() }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.access.none { it.required && !it.granted }
        }
        composeRule.onNodeWithTag(UiTags.ACCESS_COMPLETE).performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.READY
        }

        val commitsBeforeStart = session.snapshot.value.runtime.durableThroughCommit
        composeRule.onNodeWithTag(UiTags.START).performScrollTo().performClick()
        try {
            composeRule.waitUntil(TIMEOUT_MILLIS) {
                session.snapshot.value.runtime.state == ExperimentState.RUNNING
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val snapshot = session.snapshot.value
            throw AssertionError(
                "Study start did not reach its durable running state; " +
                    "state=${snapshot.runtime.state}, " +
                    "commits_before=$commitsBeforeStart, " +
                    "commits_after=${snapshot.runtime.durableThroughCommit}, " +
                    "recovery=${snapshot.recoveryStatus}",
                failure,
            )
        }
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        try {
            composeRule.waitUntil(TIMEOUT_MILLIS) {
                session.snapshot.value.runtime.lifetimeDataEventCount > 0
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val snapshot = session.snapshot.value
            throw AssertionError(
                "Participant-visible event count did not advance; " +
                    "state=${snapshot.runtime.state}, commits=${snapshot.runtime.durableThroughCommit}",
                failure,
            )
        }

        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo()
        val pauseWidth = composeRule.onNodeWithTag(UiTags.PAUSE).fetchSemanticsNode().boundsInRoot.width
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo()
        val fullRowWidth = composeRule.onNodeWithTag(UiTags.EXPORT).fetchSemanticsNode().boundsInRoot.width
        assertEquals("Pause should occupy a full control row", fullRowWidth, pauseWidth, 1f)
        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.PAUSED
        }
        val countAtPause = session.snapshot.value.runtime.lifetimeDataEventCount
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        assertEquals(countAtPause, session.snapshot.value.runtime.lifetimeDataEventCount)

        // Revoking a runtime permission kills the target process by design, which would also kill
        // this in-process instrumentation test. Removing one required app-owned channel exercises
        // the same closed notification-access gate without invalidating the test harness.
        setRequiredNotificationChannelAvailable(instrumentation, available = false)
        try {
            runBlocking { session.reconcileAccess() }
            composeRule.waitUntil(TIMEOUT_MILLIS) {
                session.snapshot.value.access.any {
                    it.kind == AccessKind.NOTIFICATIONS && !it.granted
                }
            }
            composeRule.onNodeWithTag(UiTags.accessAction(AccessKind.NOTIFICATIONS)).assertExists()
            composeRule.onNodeWithTag(UiTags.ACCESS_COMPLETE).assertDoesNotExist()
            composeRule.onNodeWithTag(UiTags.STATE)
                .assertTextEquals(composeRule.activity.getString(R.string.state_paused))
        } finally {
            setRequiredNotificationChannelAvailable(instrumentation, available = true)
        }
        runBlocking { session.reconcileAccess() }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.access.none { it.required && !it.granted }
        }

        composeRule.onNodeWithTag(UiTags.RESUME).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.RUNNING
        }
        val commitsBeforeWithdraw = session.snapshot.value.runtime.durableThroughCommit
        waitUntilExactlyOneNode(hasTestTag(UiTags.WITHDRAW) and isEnabled())
        composeRule.onNodeWithTag(UiTags.WITHDRAW).performScrollTo().performClick()
        val confirm = composeRule.activity.getString(R.string.action_confirm)
        waitUntilExactlyOneNode(hasText(confirm))
        composeRule.onNodeWithText(confirm).performClick()
        try {
            composeRule.waitUntil(TIMEOUT_MILLIS) {
                session.snapshot.value.runtime.state == ExperimentState.WITHDRAWN
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val snapshot = session.snapshot.value
            throw AssertionError(
                "Withdraw did not reach its durable terminal state; " +
                    "state=${snapshot.runtime.state}, commits_before=$commitsBeforeWithdraw, " +
                    "commits_after=${snapshot.runtime.durableThroughCommit}",
                failure,
            )
        }

        assertTrue(countAtPause > 0)
        composeRule.onNodeWithTag(UiTags.STATE)
            .assertTextEquals(composeRule.activity.getString(R.string.state_withdrawn))
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo()
        runBlocking { session.deleteLocalData() }
    }

    private fun session() = (composeRule.activity.application as CollectorApplication).session

    private fun waitUntilExactlyOneNode(matcher: SemanticsMatcher) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
    }

    private fun setRequiredNotificationChannelAvailable(
        instrumentation: android.app.Instrumentation,
        available: Boolean,
    ) {
        val context = instrumentation.targetContext
        if (available) {
            ParticepsNotificationChannels.ensureCreated(context)
        } else {
            context.getSystemService(NotificationManager::class.java)
                .deleteNotificationChannel(ParticepsNotificationChannels.DAILY_STATUS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 40_000L
    }
}
