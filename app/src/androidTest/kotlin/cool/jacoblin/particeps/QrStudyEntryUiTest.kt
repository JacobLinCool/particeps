package cool.jacoblin.particeps

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrStudyEntryUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun participantCanChooseCameraOrFileFromTheEmptyStudyScreen() {
        var scans = 0
        var files = 0
        composeRule.setContent {
            CollectorApp(
                state = StudyUiState.NoStudy(message = null, busy = false, recoveryStatus = null),
                actions = actions(scan = { scans += 1 }, import = { files += 1 }),
            )
        }

        composeRule.onNodeWithTag(UiTags.SCAN_STUDY_QR).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, scans)
        assertEquals(0, files)
        composeRule.onNodeWithTag(UiTags.IMPORT_CONFIGURATION).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, scans)
        assertEquals(1, files)
        composeRule.onNodeWithTag(UiTags.IMPORT_PROGRESS).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.IMPORT_DEMO).assertDoesNotExist()
    }

    @Test
    fun pendingImportShowsProgressAndDisablesEveryImportUntilItFinishes() {
        val busy = mutableStateOf(true)
        composeRule.setContent {
            CollectorApp(
                state = StudyUiState.NoStudy(message = null, busy = busy.value, recoveryStatus = null),
                actions = actions().copy(demo = {}),
            )
        }

        composeRule.onNodeWithTag(UiTags.IMPORT_PROGRESS).assertExists()
        listOf(UiTags.SCAN_STUDY_QR, UiTags.IMPORT_CONFIGURATION, UiTags.IMPORT_DEMO).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }

        composeRule.runOnUiThread { busy.value = false }
        composeRule.onNodeWithTag(UiTags.IMPORT_PROGRESS).assertDoesNotExist()
        listOf(UiTags.SCAN_STUDY_QR, UiTags.IMPORT_CONFIGURATION, UiTags.IMPORT_DEMO).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsEnabled()
        }
    }

    private fun actions(scan: () -> Unit = {}, import: () -> Unit = {}) = StudyUiActions(
        scan = scan,
        import = import,
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
        export = {},
        cancelExport = {},
        delete = {},
        retryRecovery = {},
        resetAndRestart = {},
    )
}
