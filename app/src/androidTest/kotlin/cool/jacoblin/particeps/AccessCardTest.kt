package cool.jacoblin.particeps

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.model.ExperimentState
import java.text.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeFixtureActivity>()

    @After
    fun clearFixture() {
        composeRule.clearFixtureContent()
    }

    @Test
    fun backgroundLocationShowsManualStepsAndWaitsForPreciseLocation() {
        val check = accessItem(
            kind = AccessKind.BACKGROUND_LOCATION,
            resolution = ParticipantAccessResolution.BlockedByPrerequisites(
                listOf(AccessKind.FINE_LOCATION, AccessKind.LOCATION_SERVICES),
            ),
            guidance = SetupGuidance.BACKGROUND_LOCATION,
            required = false,
            owners = listOf(
                ParticipantAccessOwner.DataCategory(ParticipantDataKind.LOCATION, required = false),
            ),
        )

        composeRule.setFixtureContent {
            MaterialTheme { AccessCard(check, actions(), busy = false) }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithTag(UiTags.accessInstructions(AccessKind.BACKGROUND_LOCATION)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.access_background_location_description)).assertExists()
        composeRule.onNodeWithText(
            context.getString(
                R.string.access_background_location_step_choose_always,
                context.packageManager.backgroundPermissionOptionLabel,
            ),
            substring = true,
        ).assertExists()
        listOf(AccessKind.FINE_LOCATION, AccessKind.LOCATION_SERVICES).forEach { prerequisite ->
            composeRule.onNodeWithText(
                context.getString(
                    R.string.access_complete_first,
                    context.getString(prerequisiteLabel(prerequisite)),
                ),
            ).assertExists()
        }
        composeRule.onNodeWithTag(UiTags.accessAction(AccessKind.BACKGROUND_LOCATION)).assertDoesNotExist()
    }

    @Test
    fun sharedUsageAccessIsOneActionWithBothDataCategoryOwners() {
        val expectedAction = SetupAction.SystemSettings.USAGE_ACCESS
        var launchedAction: SetupAction? = null
        val check = accessItem(
            kind = AccessKind.USAGE_ACCESS,
            resolution = ParticipantAccessResolution.ActionRequired(expectedAction),
            guidance = SetupGuidance.USAGE_ACCESS,
            owners = listOf(
                ParticipantAccessOwner.DataCategory(ParticipantDataKind.NETWORK_USAGE, required = true),
                ParticipantAccessOwner.DataCategory(ParticipantDataKind.USAGE_EVENTS, required = false),
            ),
        )

        composeRule.setFixtureContent {
            MaterialTheme {
                AccessCard(check, actions { launchedAction = it }, busy = false)
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithTag(UiTags.accessOwners(AccessKind.USAGE_ACCESS)).assertExists()
        composeRule.onNodeWithText(
            context.getString(
                R.string.access_owner_item,
                context.getString(R.string.collector_network_usage_name),
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            context.getString(
                R.string.access_owner_item,
                context.getString(R.string.collector_usage_events_name),
            ),
        ).assertExists()
        composeRule.onNodeWithTag(UiTags.accessAction(AccessKind.USAGE_ACCESS)).performClick()
        assertEquals(expectedAction, launchedAction)
    }

    @Test
    fun unavailableLocationCheckShowsOnlyGenericFailureAndNoActionableSteps() {
        val check = accessItem(
            kind = AccessKind.LOCATION_SERVICES,
            resolution = ParticipantAccessResolution.Unavailable,
            guidance = SetupGuidance.LOCATION_SERVICES,
            owners = listOf(
                ParticipantAccessOwner.DataCategory(ParticipantDataKind.LOCATION, required = true),
            ),
        )

        composeRule.setFixtureContent {
            MaterialTheme { AccessCard(check, actions(), busy = false) }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.access_system_screen_unavailable)).assertExists()
        composeRule.onNodeWithTag(UiTags.accessInstructions(AccessKind.LOCATION_SERVICES)).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.accessAction(AccessKind.LOCATION_SERVICES)).assertDoesNotExist()
        composeRule.onNodeWithText("LOCATION_SETTINGS_CHECK_FAILED").assertDoesNotExist()
    }

    @Test
    fun runningStudyCanRepairOptionalAccessWithoutLeavingCollectionControls() {
        val expectedAction = SetupAction.SystemSettings.USAGE_ACCESS
        var launchedAction: SetupAction? = null
        val check = accessItem(
            kind = AccessKind.USAGE_ACCESS,
            required = false,
            resolution = ParticipantAccessResolution.ActionRequired(expectedAction),
            guidance = SetupGuidance.USAGE_ACCESS,
            owners = listOf(
                ParticipantAccessOwner.DataCategory(ParticipantDataKind.USAGE_EVENTS, required = false),
            ),
        )

        composeRule.setFixtureContent {
            MaterialTheme {
                OptionalAccessRemediation(
                    study = participantModel(access = listOf(check)),
                    checks = listOf(check),
                    actions = actions { launchedAction = it },
                    busy = false,
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.optional_access_title)).assertExists()
        composeRule.onNodeWithTag(UiTags.accessAction(AccessKind.USAGE_ACCESS)).performClick()
        assertEquals(expectedAction, launchedAction)
    }

    @Test
    fun recoveryScreenShowsGenericCopyRetryAndDestructiveConfirmation() {
        var retries = 0
        var resets = 0
        val actions = actions().copy(
            retryRecovery = { retries += 1 },
            resetAndRestart = { resets += 1 },
        )
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.NoStudy(
                    message = null,
                    busy = false,
                    recoveryStatus = ParticipantRecoveryState.ACTION_REQUIRED,
                ),
                actions = actions,
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithText(context.getString(R.string.recovery_panel_title)).assertExists()
        composeRule.onNodeWithText("RECOVERY_TIME_UNTRUSTED").assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.RECOVERY_RETRY).performClick()
        assertEquals(1, retries)
        composeRule.onNodeWithTag(UiTags.RECOVERY_RESET).performClick()
        composeRule.onNodeWithText(context.getString(R.string.confirm_reset_title)).assertExists()
        assertEquals(0, resets)
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        assertEquals(1, resets)
    }

    @Test
    fun completeControlIsConfirmedOnlyForRunningOrPausedStudy() {
        val state = mutableStateOf(ExperimentState.RUNNING)
        var completions = 0
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(emptyList(), state.value),
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions().copy(complete = { completions += 1 }),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithTag(UiTags.COMPLETE).assertExists().performClick()
        composeRule.onNodeWithText(context.getString(R.string.confirm_complete_title)).assertExists()
        assertEquals(0, completions)
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        assertEquals(1, completions)

        composeRule.runOnUiThread { state.value = ExperimentState.PAUSED }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.COMPLETE).assertExists()

        composeRule.runOnUiThread { state.value = ExperimentState.COMPLETED }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.COMPLETE).assertDoesNotExist()

        composeRule.runOnUiThread { state.value = ExperimentState.WITHDRAWN }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.COMPLETE).assertDoesNotExist()
    }

    @Test
    fun setupStatesOfferDeclineAndNeverAWithdrawThatCannotSucceed() {
        val requiredMissing = accessItem(
            kind = AccessKind.USAGE_ACCESS,
            resolution = ParticipantAccessResolution.ActionRequired(SetupAction.SystemSettings.USAGE_ACCESS),
            guidance = SetupGuidance.USAGE_ACCESS,
            owners = listOf(
                ParticipantAccessOwner.DataCategory(ParticipantDataKind.USAGE_EVENTS, required = true),
            ),
        )
        val model = mutableStateOf(participantModel(emptyList(), ExperimentState.CONSENT_PENDING))
        var declines = 0
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = model.value,
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions().copy(
                    decline = { declines += 1 },
                    withdraw = { error("Withdraw is not a command before Start") },
                ),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun assertOnlyDeclineIsOffered() {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(UiTags.WITHDRAW).assertDoesNotExist()
            composeRule.onNodeWithTag(UiTags.DECLINE).performScrollTo().assertIsEnabled()
        }

        // CONSENT_PENDING: the data page, then the consent page.
        assertOnlyDeclineIsOffered()
        composeRule.onNodeWithTag(UiTags.CONTINUE).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.CONSENT_CHECKBOX).assertExists()
        assertOnlyDeclineIsOffered()
        composeRule.onNodeWithTag(UiTags.DECLINE).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.confirm_decline_title)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.confirm_decline_body)).assertExists()
        assertEquals(0, declines)
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        assertEquals(1, declines)

        // READY: the Start step, and the remediation screen when required access went missing.
        composeRule.runOnUiThread { model.value = participantModel(emptyList(), ExperimentState.READY) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.START).assertExists()
        assertOnlyDeclineIsOffered()
        composeRule.runOnUiThread {
            model.value = participantModel(listOf(requiredMissing), ExperimentState.READY)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.START).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.accessItem(AccessKind.USAGE_ACCESS)).assertExists()
        assertOnlyDeclineIsOffered()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.DECLINE).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_confirm)).performClick()
        assertEquals(2, declines)
    }

    @Test
    fun declineSaysSoWhenAnEarlierReleaseAlreadySentSetupRecords() {
        // Only a study imported under an earlier release, which uploaded from import, can have an
        // acknowledged upload before Start.
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(emptyList(), ExperimentState.READY).copy(uploadedThroughCommit = 4),
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions().copy(decline = {}),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithTag(UiTags.DECLINE).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.confirm_decline_body_setup_sent)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.confirm_decline_body)).assertDoesNotExist()
    }

    @Test
    fun endedStudyOffersDeleteWithoutAWithdrawThatCannotSucceed() {
        val state = mutableStateOf(ExperimentState.COMPLETED)
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(emptyList(), state.value),
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions(),
            )
        }

        listOf(ExperimentState.COMPLETED, ExperimentState.WITHDRAWN).forEach { ended ->
            composeRule.runOnUiThread { state.value = ended }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(UiTags.DELETE).performScrollTo().assertIsEnabled()
            composeRule.onNodeWithTag(UiTags.WITHDRAW).assertDoesNotExist()
            composeRule.onNodeWithTag(UiTags.DECLINE).assertDoesNotExist()
        }
        listOf(ExperimentState.RUNNING, ExperimentState.PAUSED).forEach { started ->
            composeRule.runOnUiThread { state.value = started }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(UiTags.WITHDRAW).performScrollTo().assertIsEnabled()
            composeRule.onNodeWithTag(UiTags.DECLINE).assertDoesNotExist()
            composeRule.onNodeWithTag(UiTags.DELETE).assertDoesNotExist()
        }
    }

    @Test
    fun exportAndCancellationLeaveCollectionControlsAvailable() {
        val export = mutableStateOf<ParticipantExportState>(
            ParticipantExportState.Running(ParticipantExportPhase.ENCRYPTING, 10, 20),
        )
        var pauses = 0
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(emptyList()),
                    export = export.value,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions().copy(
                    pause = { pauses++ },
                    cancelExport = { export.value = ParticipantExportState.Cancelling },
                ),
            )
        }
        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, pauses)
        composeRule.onNodeWithTag(UiTags.COMPLETE).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.WITHDRAW).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(UiTags.EXPORT_STATUS).performScrollTo()
        captureExportScreenshot("export-progress")
        composeRule.onNodeWithTag(UiTags.EXPORT_CANCEL).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.EXPORT_CANCEL).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.EXPORT_STATUS).performScrollTo()
        captureExportScreenshot("export-cancelling")
        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.COMPLETE).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.WITHDRAW).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun terminalDeletionWaitsForExportCleanupAndReportsAnUnremovedFile() {
        val export = mutableStateOf<ParticipantExportState>(ParticipantExportState.Cancelling)
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(emptyList(), ExperimentState.COMPLETED),
                    export = export.value,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions(),
            )
        }
        composeRule.onNodeWithTag(UiTags.DELETE).performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle {
            export.value = ParticipantExportState.Cancelled(incompleteFileRemains = true)
        }
        composeRule.onNodeWithTag(UiTags.DELETE).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo().assertIsEnabled()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.export_incomplete_file_remains))
            .performScrollTo().assertExists()
        composeRule.runOnIdle { export.value = ParticipantExportState.Failed(incompleteFileRemains = true) }
        composeRule.onNodeWithTag(UiTags.EXPORT_STATUS).performScrollTo()
        captureExportScreenshot("export-failed")
    }

    @Test
    fun studyAndMyDataIsOneIdenticalEntryOnEveryCollectionScreen() {
        val model = mutableStateOf(participantModel(emptyList()))
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = model.value,
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions(),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedLabel = listOf(
            context.getString(R.string.my_data_entry),
            context.getString(R.string.my_data_entry_detail),
        )
        val shaped = listOf(false, true)
        val requiredMissing = accessItem(
            kind = AccessKind.USAGE_ACCESS,
            resolution = ParticipantAccessResolution.ActionRequired(SetupAction.SystemSettings.USAGE_ACCESS),
            guidance = SetupGuidance.USAGE_ACCESS,
            owners = listOf(
                ParticipantAccessOwner.DataCategory(ParticipantDataKind.USAGE_EVENTS, required = true),
            ),
        )
        fun assertTheEntry(case: String) {
            composeRule.waitForIdle()
            val entry = composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo()
            entry.assertIsEnabled()
            val node = entry.fetchSemanticsNode()
            assertEquals(case, expectedLabel, node.config[SemanticsProperties.Text].map { it.text })
            // The title names the destination; it is not repeated as the click action's verb.
            assertEquals(case, null, node.config[SemanticsActions.OnClick].label)
        }
        listOf(
            ExperimentState.RUNNING,
            ExperimentState.PAUSED,
            ExperimentState.COMPLETED,
            ExperimentState.WITHDRAWN,
        ).forEach { collectionState ->
            shaped.forEach { trafficShaping ->
                composeRule.runOnUiThread {
                    model.value = participantModel(emptyList(), collectionState, trafficShaping)
                }
                assertTheEntry("$collectionState, shaping=$trafficShaping")
            }
        }
        // Repairing required access replaces the collection screen but keeps the same entry.
        listOf(ExperimentState.RUNNING, ExperimentState.PAUSED).forEach { startedState ->
            composeRule.runOnUiThread { model.value = participantModel(listOf(requiredMissing), startedState) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(UiTags.accessItem(AccessKind.USAGE_ACCESS)).assertExists()
            composeRule.onNodeWithTag(UiTags.EXPORT).assertDoesNotExist()
            assertTheEntry("$startedState, repairing access")
        }
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performClick()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_SCREEN)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, context.getString(R.string.my_data_entry)))
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_BACK).performClick()
        // Setup keeps its five steps; the entry appears only once the study has started.
        composeRule.runOnUiThread { model.value = participantModel(emptyList(), ExperimentState.READY) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).assertDoesNotExist()
    }

    @Test
    fun activityRecreationRetainsTheStudyAndMyDataEntryAndAcceptsModelUpdates() {
        val model = mutableStateOf(participantModel(emptyList()))
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = model.value,
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions(),
            )
        }
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().assertIsEnabled()
        val originalActivity = composeRule.activity

        composeRule.activityRule.scenario.recreate()

        assertNotSame(originalActivity, composeRule.activity)
        assertTrue(originalActivity.isDestroyed)
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { model.value = participantModel(emptyList(), ExperimentState.PAUSED) }
        composeRule.onNodeWithTag(UiTags.RESUME).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.PAUSE).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().performClick()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_SCREEN).assertExists()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_BACK).performClick()
        composeRule.onNodeWithTag(UiTags.RESUME).performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { model.value = participantModel(emptyList(), ExperimentState.READY) }
        composeRule.onNodeWithTag(UiTags.START).assertExists()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).assertDoesNotExist()
    }

    @Test
    fun studyAndMyDataAddsNoLifecycleControlAndBackReturnsToTheSameControls() {
        var storageReads = 0
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(
                        access = emptyList(),
                        upload = ParticipantUploadDisclosure("upload.example.invalid", 60, false),
                    ),
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions().copy(
                    readLocalStorageSize = {
                        storageReads++
                        ParticipantSizeBucket.of(2_048L)
                    },
                ),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().performClick()

        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_SCREEN).assertExists()
        LIFECYCLE_CONTROLS.forEach { composeRule.onNodeWithTag(it).assertDoesNotExist() }
        listOf(
            R.string.rights_pause_upload,
            R.string.rights_withdraw_upload,
            R.string.rights_delete_upload,
        ).forEach { composeRule.onNodeWithText(context.getString(it)).performScrollTo().assertExists() }
        composeRule.onNodeWithTag(UiTags.PARTICIPATION_STORAGE).performScrollTo()
        composeRule.waitUntil { storageReads == 1 }
        composeRule.onNodeWithText(
            context.getString(R.string.size_below_step, NumberFormat.getIntegerInstance().format(50)),
        ).assertExists()

        Espresso.pressBack()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.PAUSE).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.WITHDRAW).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().performClick()
        composeRule.waitUntil { storageReads == 2 }
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA_BACK).performClick()
        composeRule.onNodeWithTag(UiTags.EXPORT).performScrollTo().assertIsEnabled()
        composeRule.waitForIdle()
        assertEquals("Storage is read once per visit, never polled", 2, storageReads)
    }

    @Test
    fun studyWithoutUploadStatesNoUploadConsequences() {
        composeRule.setFixtureContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(emptyList(), ExperimentState.WITHDRAWN),
                    export = ParticipantExportState.Idle,
                    message = null,
                    busy = false,
                    recoveryStatus = null,
                ),
                actions = actions(),
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithTag(UiTags.STUDY_AND_MY_DATA).performScrollTo().performClick()

        composeRule.onNodeWithText(context.getString(R.string.rights_delete_body)).performScrollTo().assertExists()
        listOf(
            R.string.rights_pause_upload,
            R.string.rights_withdraw_upload,
            R.string.rights_delete_upload,
        ).forEach { composeRule.onNodeWithText(context.getString(it)).assertDoesNotExist() }
        composeRule.onNodeWithText(context.getString(R.string.consent_upload_none_title)).performScrollTo().assertExists()
        composeRule.onNodeWithTag(UiTags.DELETE).assertDoesNotExist()
    }

    private fun captureExportScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureExportScreenshots") != "true") return
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            .resolve("export-screenshots")
        check(directory.isDirectory || directory.mkdirs())
        directory.resolve("$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private companion object {
        private fun actions(requestAccess: (SetupAction) -> Unit = {}) = StudyUiActions(
            scan = {},
            import = {},
            demo = null,
            review = {},
            acceptConsent = {},
            completeAccess = {},
            requestAccess = requestAccess,
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
            readLocalStorageSize = { null },
        )

        private fun accessItem(
            kind: AccessKind,
            resolution: ParticipantAccessResolution,
            guidance: SetupGuidance?,
            required: Boolean = true,
            owners: List<ParticipantAccessOwner> = emptyList(),
        ) = ParticipantAccessItem(kind, required, owners, resolution, guidance)

        private fun participantModel(
            access: List<ParticipantAccessItem>,
            state: ExperimentState = ExperimentState.RUNNING,
            trafficShaping: Boolean = false,
            upload: ParticipantUploadDisclosure? = null,
        ) = ParticipantStudyUiModel(
            experimentId = "access-card-test",
            title = "Access card test",
            purpose = "Access UI test",
            researcherName = "Test researcher",
            researcherContact = "test@example.invalid",
            durationHours = 1,
            consentSummary = "Test consent",
            consentDocumentVersion = "test-1",
            signerFingerprint = "0000 0000 0000 0000 0000 0000 0000 0000",
            signerAnchored = false,
            assignedParticipantId = null,
            participantInstanceId = "00000000-0000-4000-8000-000000000000",
            dataCategories = listOf(ParticipantDataCategory(ParticipantDataKind.USAGE_EVENTS, optional = true)),
            access = access,
            upload = upload,
            state = state,
            lifetimeDataEventCount = 0,
            durableThroughCommit = 0,
            uploadedThroughCommit = 0,
            retainedFromCommit = 1,
            pausedAtUtcMillis = null,
            participation = ParticipantParticipationSummary(
                studyDayCount = 1,
                plannedEndUtcMillis = null,
                studyLength = null,
                activeCollection = ParticipantElapsedTime.Settled(0),
                ended = state == ExperimentState.COMPLETED || state == ExperimentState.WITHDRAWN,
            ),
            lastExport = null,
            trafficShapingDisclosureRequired = trafficShaping,
        )

        private fun prerequisiteLabel(kind: AccessKind): Int = when (kind) {
            AccessKind.FINE_LOCATION -> R.string.access_fine_location
            AccessKind.LOCATION_SERVICES -> R.string.access_location_services
            else -> error("Unexpected prerequisite")
        }

        val LIFECYCLE_CONTROLS = listOf(
            UiTags.START,
            UiTags.PAUSE,
            UiTags.RESUME,
            UiTags.COMPLETE,
            UiTags.WITHDRAW,
            UiTags.DECLINE,
            UiTags.EXPORT,
            UiTags.DELETE,
        )
    }
}
