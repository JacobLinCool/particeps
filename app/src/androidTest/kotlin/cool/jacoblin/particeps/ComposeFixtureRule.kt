package cool.jacoblin.particeps

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule

internal fun AndroidComposeTestRule<ActivityScenarioRule<ComposeFixtureActivity>, ComposeFixtureActivity>.setFixtureContent(
    content: @Composable () -> Unit,
) {
    activityRule.scenario.onActivity { it.setFixtureContent(content) }
    waitForIdle()
}

internal fun AndroidComposeTestRule<ActivityScenarioRule<ComposeFixtureActivity>, ComposeFixtureActivity>.clearFixtureContent() {
    activityRule.scenario.onActivity { it.clearFixtureContent() }
}
