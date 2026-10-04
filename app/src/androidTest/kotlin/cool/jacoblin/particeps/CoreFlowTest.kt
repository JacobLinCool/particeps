package cool.jacoblin.particeps

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.application.StudyCommandResult
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.model.ExperimentState
import java.text.NumberFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
        waitUntilExactlyOneNode(hasTestTag(UiTags.START), useUnmergedTree = true)
        composeRule.onNodeWithTag(UiTags.START, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
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

        // Study and my data measures the real store once and returns to unchanged controls.
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_SCREEN).assertExists()
        composeRule.onNodeWithTag(UiTags.PAUSE).assertDoesNotExist()
        val checking = composeRule.activity.getString(R.string.participation_storage_checking)
        val unavailable = composeRule.activity.getString(R.string.participation_storage_unavailable)
        waitUntilExactlyOneNode(hasTestTag(UiTags.PARTICIPATION_STORAGE) and !hasText(checking))
        composeRule.onNodeWithTag(UiTags.PARTICIPATION_STORAGE).assert(!hasText(unavailable))
        // A study this young stores well under one 50 MB step, and only the step is shown.
        val firstStep = composeRule.activity.getString(
            R.string.size_below_step,
            NumberFormat.getIntegerInstance().format(ParticipantSizeBucket.STEP_MEGABYTES),
        )
        composeRule.onNodeWithTag(UiTags.PARTICIPATION_STORAGE).assert(hasText(firstStep))
        Espresso.pressBack()
        waitUntilExactlyOneNode(hasTestTag(UiTags.PAUSE))
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_SCREEN).assertDoesNotExist()
        assertEquals(ExperimentState.RUNNING, session.snapshot.value.runtime.state)

        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo()
        val pauseWidth = composeRule.onNodeWithTag(UiTags.PAUSE).fetchSemanticsNode().boundsInRoot.width
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo()
        val fullRowWidth = composeRule.onNodeWithTag(UiTags.EXPORT).fetchSemanticsNode().boundsInRoot.width
        assertEquals("Pause should occupy a full control row", fullRowWidth, pauseWidth, 1f)
        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.PAUSED
        }
        assertPausedNotificationPosted()
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
        assertPausedNotificationCleared()
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

    @Test
    fun decliningBeforeStartRemovesTheStudyAndReturnsToImport() {
        val session = session()
        waitUntilExactlyOneNode(hasTestTag(UiTags.IMPORT_DEMO))
        composeRule.onNodeWithTag(UiTags.IMPORT_DEMO).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.CONFIG_VERIFIED
        }
        composeRule.onNodeWithTag(UiTags.REVIEW).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.CONSENT_PENDING
        }

        composeRule.onNodeWithTag(UiTags.WITHDRAW).assertDoesNotExist()
        waitUntilExactlyOneNode(hasTestTag(UiTags.DECLINE) and isEnabled())
        composeRule.onNodeWithTag(UiTags.DECLINE).performScrollTo().performClick()
        val confirm = composeRule.activity.getString(R.string.action_confirm)
        waitUntilExactlyOneNode(hasText(confirm))
        composeRule.onNodeWithText(confirm).performClick()

        composeRule.waitUntil(TIMEOUT_MILLIS) { session.snapshot.value.study == null }
        assertTrue(session.snapshot.value.initialized)
        waitUntilExactlyOneNode(hasTestTag(UiTags.SCAN_STUDY_QR))
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.message_study_removed)).assertExists()
    }

    /**
     * Once the screen has been stopped for longer than the sharing timeout, commits are not
     * projected at all, and a lifecycle change made meanwhile is what the participant sees on
     * return, with no stale controls and no error from acting on the old state.
     */
    @Test
    fun aStudyChangedWhileTheScreenWasStoppedIsCurrentWhenItReturns() {
        val session = session()
        waitUntilExactlyOneNode(hasTestTag(UiTags.IMPORT_DEMO))
        composeRule.onNodeWithTag(UiTags.IMPORT_DEMO).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            session.snapshot.value.runtime.state == ExperimentState.CONFIG_VERIFIED
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        runBlocking {
            assertEquals(StudyCommandResult.Success, session.reviewStudy())
            assertEquals(StudyCommandResult.Success, session.acceptConsent())
            session.reconcileAccess()
            assertEquals(StudyCommandResult.Success, session.completeAccessSetup())
            assertEquals(StudyCommandResult.Success, session.start())
        }
        val running = composeRule.activity.getString(R.string.state_running)
        waitUntilExactlyOneNode(hasTestTag(UiTags.STATE) and hasText(running))
        val viewModel = viewModel()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Composition effects run on the test clock here, so the stopped collector only leaves
        // once the clock idles; on a phone it leaves as soon as the Activity stops.
        composeRule.waitForIdle()
        Thread.sleep(UI_STATE_STOP_TIMEOUT_MILLIS + STOP_MARGIN_MILLIS)
        val shownAtStop = viewModel.state.value
        val commitsAtStop = session.snapshot.value.runtime.durableThroughCommit
        runBlocking {
            assertEquals(StudyCommandResult.Success, session.pause())
            withTimeout(TIMEOUT_MILLIS) { session.snapshot.first { it.runtime.state == ExperimentState.PAUSED } }
        }
        assertTrue(session.snapshot.value.runtime.durableThroughCommit > commitsAtStop)
        assertSame("A stopped screen projected a commit", shownAtStop, viewModel.state.value)
        assertPausedNotificationPosted()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val paused = composeRule.activity.getString(R.string.state_paused)
        waitUntilExactlyOneNode(hasTestTag(UiTags.STATE) and hasText(paused))
        composeRule.onNodeWithTag(UiTags.RESUME).assertExists()
        composeRule.onNodeWithTag(UiTags.PAUSE).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.message_operation_failed))
            .assertDoesNotExist()
        val shown = viewModel.state.value as StudyUiState.ActiveStudy
        assertEquals(session.snapshot.value.runtime.durableThroughCommit, shown.model.durableThroughCommit)
    }

    private fun session() = (composeRule.activity.application as CollectorApplication).session

    /** Reads Android's posted notification, including when the participant screen is stopped. */
    private fun assertPausedNotificationPosted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            manager.activeNotifications.any { it.tag == AndroidRecoveryReporter.NOTIFICATION_TAG }
        }
        val posted = manager.activeNotifications.single { it.tag == AndroidRecoveryReporter.NOTIFICATION_TAG }
        val notification = posted.notification
        assertEquals(context.packageName, posted.packageName)
        assertEquals(ParticepsNotificationChannels.RECOVERY, notification.channelId)
        // Exact platform copy prevents the reminder from revealing treatment, package, or failure details.
        assertEquals(
            context.getString(R.string.collection_paused_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.getString(R.string.collection_paused_notification_body),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertEquals(
            context.getString(R.string.collection_paused_notification_body),
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        )
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNotNull("Paused reminder must open the participant app", notification.contentIntent)
        assertTrue(notification.contentIntent.isActivity)
        assertTrue(notification.contentIntent.isImmutable)
        assertEquals(context.packageName, notification.contentIntent.creatorPackage)
    }

    private fun assertPausedNotificationCleared() {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(NotificationManager::class.java)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            manager.activeNotifications.none { it.tag == AndroidRecoveryReporter.NOTIFICATION_TAG }
        }
    }

    /** The Activity's own instance; the factory is never used because the instance already exists. */
    private fun viewModel(): StudyViewModel {
        var viewModel: StudyViewModel? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity, StudyViewModel.Factory(session()))[StudyViewModel::class.java]
        }
        return checkNotNull(viewModel)
    }

    private fun waitUntilExactlyOneNode(
        matcher: SemanticsMatcher,
        useUnmergedTree: Boolean = false,
    ) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher, useUnmergedTree).fetchSemanticsNodes().size == 1
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
        const val STOP_MARGIN_MILLIS = 2_000L
    }
}
