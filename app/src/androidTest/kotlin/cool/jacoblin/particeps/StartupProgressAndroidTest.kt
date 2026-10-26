package cool.jacoblin.particeps

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.application.StartupProgress
import cool.jacoblin.particeps.core.application.StartupStage
import java.util.Locale
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupProgressAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeFixtureActivity>()

    @After
    fun clearFixture() {
        composeRule.clearFixtureContent()
    }

    @Test
    fun measuredProgressChangesAndEndsBeforeRuntimePreparation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = mutableStateOf(StartupProgress(StartupStage.VERIFYING_STORAGE, 37))
        composeRule.mainClock.autoAdvance = false
        composeRule.setFixtureContent { ApplicationStartupScreen(ApplicationStartupState.Starting(progress.value)) }
        composeRule.mainClock.advanceTimeBy(3_000)

        composeRule.onNodeWithText(context.getString(R.string.startup_storage_progress, 37)).assertExists()
        assertProgress(ProgressBarRangeInfo(0.37f, 0f..1f))
        composeRule.onNodeWithTag(UiTags.STARTUP_ELAPSED).assertExists()
        composeRule.onNodeWithTag(UiTags.EXPORT).assertDoesNotExist()

        composeRule.runOnUiThread { progress.value = StartupProgress(StartupStage.VERIFYING_STORAGE, 100) }
        composeRule.mainClock.advanceTimeByFrame()
        assertProgress(ProgressBarRangeInfo(1f, 0f..1f))

        composeRule.runOnUiThread { progress.value = StartupProgress(StartupStage.PREPARING_STUDY) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(context.getString(R.string.startup_stage_preparing_study)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.startup_storage_progress, 100)).assertDoesNotExist()
        assertProgress(ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun traditionalChineseShowsMeasuredDataProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("zh-TW"))
        }
        val localized = context.createConfigurationContext(configuration)
        composeRule.mainClock.autoAdvance = false
        composeRule.setFixtureContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                ApplicationStartupScreen(
                    ApplicationStartupState.Starting(StartupProgress(StartupStage.VERIFYING_STORAGE, 37)),
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.onNodeWithText("資料已檢查 37%").assertExists()
        composeRule.onNodeWithText(localized.getString(R.string.startup_stage_validating_storage)).assertExists()
        assertProgress(ProgressBarRangeInfo(0.37f, 0f..1f))

        if (InstrumentationRegistry.getArguments().getString("captureStartupScreenshot") == "true") {
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            context.cacheDir.resolve("startup-progress-zh-TW.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
    }

    private fun assertProgress(expected: ProgressBarRangeInfo) {
        composeRule.onNodeWithTag(UiTags.STARTUP_PROGRESS).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, expected),
        )
    }
}
