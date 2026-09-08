package cool.jacoblin.particeps

import android.Manifest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.model.ExperimentState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** QR transport must preserve the same five deliberate setup steps as file import. */
@RunWith(AndroidJUnit4::class)
class QrStudyConsentFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val application: CollectorApplication
        get() = composeRule.activity.application as CollectorApplication

    @Before
    fun clearStudyBeforeTest() = runBlocking { application.session.clearStudyDataForTest() }

    @After
    fun clearStudyAfterTest() = runBlocking { application.session.clearStudyDataForTest() }

    @Test
    fun verifiedQrStillRequiresStudyReviewDataReviewConsentAccessAndExplicitStart() {
        val session = application.session
        val fixture = qrStudyImportFixture(application)
        runBlocking { session.importSignedConfiguration(fixture.envelope, fixture.join) }
        waitForState(ExperimentState.CONFIG_VERIFIED)

        assertStep(R.string.step_study)
        composeRule.onNodeWithTag(UiTags.SCAN_STUDY_QR).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.IMPORT_CONFIGURATION).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.START).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.REVIEW).performScrollTo().performClick()
        waitForState(ExperimentState.CONSENT_PENDING)

        assertStep(R.string.step_data)
        composeRule.onNodeWithTag(UiTags.CONSENT_CHECKBOX).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.CONTINUE).performScrollTo().performClick()

        assertStep(R.string.step_consent)
        composeRule.onNodeWithTag(UiTags.CONSENT_CHECKBOX).performScrollTo().assertIsOff()
        composeRule.onNodeWithTag(UiTags.PREPARE).performScrollTo().assertIsNotEnabled()
        assertEquals(ExperimentState.CONSENT_PENDING, session.snapshot.value.runtime.state)
        assertNull(session.snapshot.value.runtime.startedAtUtcMillis)
        composeRule.onNodeWithTag(UiTags.CONSENT_CHECKBOX).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.PREPARE).performScrollTo().assertIsEnabled().performClick()
        waitForState(ExperimentState.ACCESS_SETUP)

        assertStep(R.string.step_access)
        composeRule.onNodeWithTag(UiTags.START).assertDoesNotExist()
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
        waitForState(ExperimentState.READY)

        assertStep(R.string.step_start)
        composeRule.onNodeWithTag(UiTags.START).performScrollTo().assertIsEnabled()
        assertNull("neither scanning nor consent starts collection", session.snapshot.value.runtime.startedAtUtcMillis)
        assertEquals(0L, session.snapshot.value.runtime.lifetimeDataEventCount)
    }

    private fun waitForState(state: ExperimentState) {
        composeRule.waitUntil(TIMEOUT_MILLIS) { application.session.snapshot.value.runtime.state == state }
        composeRule.waitForIdle()
    }

    private fun assertStep(label: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(label)).assertExists()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 40_000L
    }
}
