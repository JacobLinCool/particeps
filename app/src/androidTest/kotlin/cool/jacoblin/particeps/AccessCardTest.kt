package cool.jacoblin.particeps

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cool.jacoblin.particeps.core.application.StudyAccessOwner
import cool.jacoblin.particeps.core.application.StudyAccessStatus
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.AccessUnavailableReason
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.LocationConfiguration
import cool.jacoblin.particeps.core.definition.LocationPriority
import cool.jacoblin.particeps.core.definition.NetworkTransport
import cool.jacoblin.particeps.core.definition.NetworkUsageConfiguration
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.UsageEventsConfiguration
import java.time.Instant
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
        val location = LocationConfiguration(
            required = false,
            intervalMillis = 10_000,
            minimumIntervalMillis = 5_000,
            maximumBatchDelayMillis = 30_000,
            minimumDisplacementMillimeters = 5_000,
            priority = LocationPriority.BALANCED,
        )
        val check = StudyAccessStatus(
            requirement = AccessRequirement(AccessKind.BACKGROUND_LOCATION, required = false),
            owners = setOf(StudyAccessOwner.Collector(location.id, required = false)),
            resolution = AccessResolution.BlockedByPrerequisites(
                setOf(AccessKind.FINE_LOCATION, AccessKind.LOCATION_SERVICES),
            ),
            guidance = SetupGuidance.BACKGROUND_LOCATION,
        )

        composeRule.setContent {
            MaterialTheme {
                AccessCard(configuration(listOf(location)), check, actions(), busy = false)
            }
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
        composeRule.onNodeWithText(
            context.getString(
                R.string.access_complete_first,
                context.getString(R.string.access_fine_location),
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            context.getString(
                R.string.access_complete_first,
                context.getString(R.string.access_location_services),
            ),
        ).assertExists()
        composeRule.onNodeWithTag(UiTags.accessAction(AccessKind.BACKGROUND_LOCATION)).assertDoesNotExist()
    }

    @Test
    fun sharedUsageAccessIsOneActionWithBothCollectorOwners() {
        val networkUsage = NetworkUsageConfiguration(
            required = true,
            transports = setOf(NetworkTransport.WIFI),
            pollIntervalMinutes = 5,
        )
        val usageEvents = UsageEventsConfiguration(required = false, pollIntervalMinutes = 15)
        val expectedAction = SetupAction.SystemSettings.USAGE_ACCESS
        var launchedAction: SetupAction? = null
        val check = StudyAccessStatus(
            requirement = AccessRequirement(AccessKind.USAGE_ACCESS, required = true),
            owners = setOf(
                StudyAccessOwner.Collector(networkUsage.id, required = true),
                StudyAccessOwner.Collector(usageEvents.id, required = false),
            ),
            resolution = AccessResolution.ActionRequired(expectedAction),
            guidance = SetupGuidance.USAGE_ACCESS,
        )

        composeRule.setContent {
            MaterialTheme {
                AccessCard(
                    configuration(listOf(networkUsage, usageEvents)),
                    check,
                    actions { launchedAction = it },
                    busy = false,
                )
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
    fun unavailableLocationCheckShowsOnlyTheFailureNotActionableSteps() {
        val location = LocationConfiguration(
            required = true,
            intervalMillis = 10_000,
            minimumIntervalMillis = 5_000,
            maximumBatchDelayMillis = 30_000,
            minimumDisplacementMillimeters = 5_000,
            priority = LocationPriority.BALANCED,
        )
        val check = StudyAccessStatus(
            requirement = AccessRequirement(AccessKind.LOCATION_SERVICES, required = true),
            owners = setOf(StudyAccessOwner.Collector(location.id, required = true)),
            resolution = AccessResolution.Unavailable(
                AccessUnavailableReason.LOCATION_SETTINGS_CHECK_FAILED,
            ),
            guidance = SetupGuidance.LOCATION_SERVICES,
        )

        composeRule.setContent {
            MaterialTheme {
                AccessCard(configuration(listOf(location)), check, actions(), busy = false)
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.access_location_settings_check_failed)).assertExists()
        composeRule.onNodeWithTag(UiTags.accessInstructions(AccessKind.LOCATION_SERVICES)).assertDoesNotExist()
        composeRule.onNodeWithTag(UiTags.accessAction(AccessKind.LOCATION_SERVICES)).assertDoesNotExist()
    }

    @Test
    fun runningStudyCanRepairOptionalAccessWithoutLeavingCollectionControls() {
        val usageEvents = UsageEventsConfiguration(required = false, pollIntervalMinutes = 15)
        val expectedAction = SetupAction.SystemSettings.USAGE_ACCESS
        var launchedAction: SetupAction? = null
        val check = StudyAccessStatus(
            requirement = AccessRequirement(AccessKind.USAGE_ACCESS, required = false),
            owners = setOf(StudyAccessOwner.Collector(usageEvents.id, required = false)),
            resolution = AccessResolution.ActionRequired(expectedAction),
            guidance = SetupGuidance.USAGE_ACCESS,
        )

        composeRule.setContent {
            MaterialTheme {
                OptionalAccessRemediation(
                    configuration = configuration(listOf(usageEvents)),
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
        withdraw = {},
        export = {},
        delete = {},
    )

    private fun configuration(collectors: List<CollectorConfiguration>) = StudyConfiguration(
        schemaVersion = StudyConfiguration.CURRENT_SCHEMA_VERSION,
        experimentId = "access-card-test",
        configurationId = "access-card-config",
        assignedParticipantId = null,
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = StudyConfiguration.ANDROID_PLATFORM,
        minimumClientVersion = 1,
        title = "Access card test",
        researcherName = "Test researcher",
        researcherContact = "test@example.invalid",
        purpose = "Access UI test",
        durationHours = 1,
        consentDocumentVersion = "test-1",
        consentSummary = "Test consent",
        collectors = collectors,
        surveys = emptyList(),
        interventions = emptyList(),
        maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
        signer = SignerIdentity("test-signer", RAW_PUBLIC_KEY),
        export = ExportConfiguration("export-key", RAW_PUBLIC_KEY),
        upload = null,
    )

    private companion object {
        const val RAW_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
