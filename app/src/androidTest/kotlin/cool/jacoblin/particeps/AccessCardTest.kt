package cool.jacoblin.particeps

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.model.ExperimentState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessCardTest {
    @get:Rule
    val composeRule = createComposeRule()

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

        composeRule.setContent {
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

        composeRule.setContent {
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

        composeRule.setContent {
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

        composeRule.setContent {
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
        composeRule.setContent {
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
        composeRule.setContent {
            CollectorApp(
                state = StudyUiState.ActiveStudy(
                    model = participantModel(emptyList(), state.value),
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

    private fun actions(requestAccess: (SetupAction) -> Unit = {}) = StudyUiActions(
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
        export = {},
        delete = {},
        retryRecovery = {},
        resetAndRestart = {},
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
    ) = ParticipantStudyUiModel(
        experimentId = "access-card-test",
        title = "Access card test",
        purpose = "Access UI test",
        researcherName = "Test researcher",
        researcherContact = "test@example.invalid",
        durationHours = 1,
        consentSummary = "Test consent",
        consentDocumentVersion = "test-1",
        configurationId = "access-card-config",
        signerFingerprint = "0000 0000 0000 0000 0000 0000 0000 0000",
        signerAnchored = false,
        assignedParticipantId = null,
        participantInstanceId = "00000000-0000-4000-8000-000000000000",
        dataCategories = listOf(ParticipantDataCategory(ParticipantDataKind.USAGE_EVENTS, optional = true)),
        access = access,
        upload = null,
        state = state,
        lifetimeDataEventCount = 0,
        durableThroughCommit = 0,
        uploadedThroughCommit = 0,
        retainedFromCommit = 1,
        startedAtUtcMillis = null,
        pausedAtUtcMillis = null,
        endedAtUtcMillis = null,
        lastExport = null,
        trafficShapingDisclosureRequired = false,
    )

    private fun prerequisiteLabel(kind: AccessKind): Int = when (kind) {
        AccessKind.FINE_LOCATION -> R.string.access_fine_location
        AccessKind.LOCATION_SERVICES -> R.string.access_location_services
        else -> error("Unexpected prerequisite")
    }
}
