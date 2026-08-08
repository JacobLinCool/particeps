package cool.jacoblin.particeps.core.access

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.AccessSnapshot
import cool.jacoblin.particeps.core.collector.AccessUnavailableReason
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRulesTest {
    @Test
    fun everyAccessKindHasOneClosedRuleAndDependencyGraphOrdersCompositeFlows() {
        assertEquals(AccessKind.entries.toSet(), AccessRules.byKind.keys)

        val requirements = AccessKind.entries
            .mapTo(mutableSetOf()) { AccessRequirement(it, required = true) }
        val ordered = AccessRules.ordered(requirements).map(AccessRequirement::kind)

        assertTrue(ordered.indexOf(AccessKind.FINE_LOCATION) < ordered.indexOf(AccessKind.BACKGROUND_LOCATION))
        assertTrue(
            ordered.indexOf(AccessKind.RESEARCH_KEYBOARD_ENABLED) <
                ordered.indexOf(AccessKind.RESEARCH_KEYBOARD_SELECTED),
        )
    }

    @Test
    fun backgroundLocationIsBlockedUntilForegroundLocationIsSatisfied() {
        val requirements = setOf(
            AccessRequirement(AccessKind.FINE_LOCATION, required = true),
            AccessRequirement(AccessKind.LOCATION_SERVICES, required = true),
            AccessRequirement(AccessKind.BACKGROUND_LOCATION, required = true),
        )

        val initial = AccessRules.resolve(requirements, emptySet(), emptySet())
        assertEquals(
            AccessResolution.ActionRequired(SetupAction.RuntimePermission.FOREGROUND_LOCATION),
            initial.status(AccessKind.FINE_LOCATION).resolution,
        )
        assertEquals(
            AccessResolution.BlockedByPrerequisites(
                setOf(AccessKind.FINE_LOCATION, AccessKind.LOCATION_SERVICES),
            ),
            initial.status(AccessKind.BACKGROUND_LOCATION).resolution,
        )
        assertEquals(
            SetupGuidance.BACKGROUND_LOCATION,
            initial.status(AccessKind.BACKGROUND_LOCATION).guidance,
        )

        val foregroundGranted = AccessRules.resolve(
            requirements,
            setOf(AccessKind.FINE_LOCATION),
            emptySet(),
        )
        assertEquals(
            AccessResolution.ActionRequired(SetupAction.SystemSettings.LOCATION_SERVICES),
            foregroundGranted.status(AccessKind.LOCATION_SERVICES).resolution,
        )
        assertEquals(
            AccessResolution.BlockedByPrerequisites(setOf(AccessKind.LOCATION_SERVICES)),
            foregroundGranted.status(AccessKind.BACKGROUND_LOCATION).resolution,
        )

        val locationServicesGranted = AccessRules.resolve(
            requirements,
            setOf(AccessKind.FINE_LOCATION, AccessKind.LOCATION_SERVICES),
            emptySet(),
        )
        assertEquals(
            AccessResolution.ActionRequired(SetupAction.SystemSettings.APPLICATION_DETAILS),
            locationServicesGranted.status(AccessKind.BACKGROUND_LOCATION).resolution,
        )
    }

    @Test
    fun fixedRuntimeDenialUsesOnlyTheDeclaredSettingsActionAndGuidance() {
        val requirement = AccessRequirement(AccessKind.NOTIFICATIONS, required = true)
        val override = AccessRuleOverride(
            SetupAction.SystemSettings.APPLICATION_NOTIFICATIONS,
            SetupGuidance.NOTIFICATIONS_SETTINGS,
        )

        val snapshot = AccessRules.resolve(
            requirements = setOf(requirement),
            satisfiedKinds = emptySet(),
            unavailableSettings = emptySet(),
            actionOverrides = mapOf(AccessKind.NOTIFICATIONS to override),
        )

        assertEquals(
            AccessResolution.ActionRequired(SetupAction.SystemSettings.APPLICATION_NOTIFICATIONS),
            snapshot.status(AccessKind.NOTIFICATIONS).resolution,
        )
        assertEquals(SetupGuidance.NOTIFICATIONS_SETTINGS, snapshot.status(AccessKind.NOTIFICATIONS).guidance)
    }

    @Test
    fun missingSystemHandlerIsExplicitAndHasNoFallbackAction() {
        val requirements = setOf(AccessRequirement(AccessKind.USAGE_ACCESS, required = true))
        val snapshot = AccessRules.resolve(
            requirements,
            satisfiedKinds = emptySet(),
            unavailableSettings = setOf(SetupAction.SystemSettings.USAGE_ACCESS),
        )

        assertEquals(
            AccessResolution.Unavailable(AccessUnavailableReason.SYSTEM_HANDLER_MISSING),
            snapshot.status(AccessKind.USAGE_ACCESS).resolution,
        )
    }

    @Test
    fun locationProbeFailuresAreUnavailableAndNeverFallBackToSettingsActions() {
        val requirements = setOf(
            AccessRequirement(AccessKind.FINE_LOCATION, required = true),
            AccessRequirement(AccessKind.LOCATION_SERVICES, required = true),
        )
        listOf(
            AccessUnavailableReason.LOCATION_SETTINGS_CHANGE_UNAVAILABLE,
            AccessUnavailableReason.LOCATION_SETTINGS_CHECK_FAILED,
        ).forEach { reason ->
            val snapshot = AccessRules.resolve(
                requirements = requirements,
                satisfiedKinds = setOf(AccessKind.FINE_LOCATION),
                unavailableSettings = emptySet(),
                unavailableKinds = mapOf(AccessKind.LOCATION_SERVICES to reason),
            )

            assertEquals(
                AccessResolution.Unavailable(reason),
                snapshot.status(AccessKind.LOCATION_SERVICES).resolution,
            )
        }
    }

    private fun AccessSnapshot.status(kind: AccessKind) =
        statuses.single { it.requirement.kind == kind }
}
