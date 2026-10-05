package cool.jacoblin.particeps

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live clock behind the study age and pause duration keeps a composition alive but must not
 * wake the phone while its Activity is stopped, and must be current again as soon as it restarts.
 */
@RunWith(AndroidJUnit4::class)
class WallClockLifecycleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeFixtureActivity>()

    @After
    fun clearFixture() {
        composeRule.clearFixtureContent()
    }

    @Test
    fun theClockDoesNotTickWhileStoppedAndIsCurrentOnceStartedAgain() {
        lateinit var owner: ControlledLifecycleOwner
        composeRule.runOnUiThread {
            owner = ControlledLifecycleOwner()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.setFixtureContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val now by rememberWallClockMillis()
                Text(now.toString(), Modifier.testTag(CLOCK))
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        val started = shownMillis()
        Thread.sleep(REAL_CLOCK_STEP_MILLIS)
        composeRule.mainClock.advanceTimeBy(ONE_TICK_MILLIS)
        assertTrue("The clock should advance while started", shownMillis() > started)

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.mainClock.advanceTimeByFrame()
        val stopped = shownMillis()
        Thread.sleep(REAL_CLOCK_STEP_MILLIS)
        composeRule.mainClock.advanceTimeBy(TEN_TICKS_MILLIS)
        assertEquals("A stopped clock must not tick", stopped, shownMillis())

        val restartedAfter = System.currentTimeMillis()
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue("Restarting should read the clock afresh", shownMillis() >= restartedAfter)
    }

    private fun shownMillis(): Long = composeRule.onNodeWithTag(CLOCK)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .single()
        .text
        .toLong()

    private class ControlledLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val CLOCK = "clock"
        const val REAL_CLOCK_STEP_MILLIS = 5L
        const val ONE_TICK_MILLIS = WALL_CLOCK_TICK_MILLIS + 1_000L
        const val TEN_TICKS_MILLIS = 10 * WALL_CLOCK_TICK_MILLIS + 10_000L
    }
}
