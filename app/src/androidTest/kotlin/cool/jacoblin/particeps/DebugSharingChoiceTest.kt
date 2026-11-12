package cool.jacoblin.particeps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugSharingChoiceTest {
    @get:Rule(order = 0)
    val undecided = object : ExternalResource() {
        override fun before() {
            DebugTelemetry.forgetChoiceForTesting(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchAsksOnceAndDecliningSendsNothingAndShowsTheApp() {
        composeRule.waitUntil(STARTUP_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTagCount(UiTags.DEBUG_SHARING_CHOICE) == 1
        }
        composeRule.onNodeWithTag(UiTags.DEBUG_SHARING_DECLINE).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(DebugSharing.NOT_SHARED, DebugTelemetry.sharing.value)
        composeRule.onNodeWithTag(UiTags.DEBUG_SHARING_CHOICE).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.DEBUG_SHARING_ROW).performScrollTo().assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 30_000L
    }
}
