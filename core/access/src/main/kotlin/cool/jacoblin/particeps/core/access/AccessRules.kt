package cool.jacoblin.particeps.core.access

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessResolution
import cool.jacoblin.particeps.core.collector.AccessSnapshot
import cool.jacoblin.particeps.core.collector.AccessStatus
import cool.jacoblin.particeps.core.collector.AccessUnavailableReason
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance

internal data class AccessRule(
    val order: Int,
    val prerequisites: Set<AccessKind> = emptySet(),
    val action: SetupAction? = null,
    val guidance: SetupGuidance? = null,
)

internal data class AccessRuleOverride(
    val action: SetupAction,
    val guidance: SetupGuidance?,
)

/**
 * The closed Android acquisition contract for every access capability collectors may declare.
 *
 * Collectors name capabilities only. They cannot provide permission strings, intents, UI text, or
 * callbacks. Keeping those operations here makes the platform behaviour exhaustive and auditable.
 */
internal object AccessRules {
    val byKind: Map<AccessKind, AccessRule> = mapOf(
        AccessKind.NOTIFICATIONS to AccessRule(
            order = 0,
            action = SetupAction.RuntimePermission.NOTIFICATIONS,
        ),
        AccessKind.FINE_LOCATION to AccessRule(
            order = 10,
            action = SetupAction.RuntimePermission.FOREGROUND_LOCATION,
        ),
        AccessKind.LOCATION_SERVICES to AccessRule(
            order = 11,
            prerequisites = setOf(AccessKind.FINE_LOCATION),
            action = SetupAction.SystemSettings.LOCATION_SERVICES,
            guidance = SetupGuidance.LOCATION_SERVICES,
        ),
        AccessKind.BACKGROUND_LOCATION to AccessRule(
            order = 12,
            prerequisites = setOf(AccessKind.FINE_LOCATION, AccessKind.LOCATION_SERVICES),
            action = SetupAction.SystemSettings.APPLICATION_DETAILS,
            guidance = SetupGuidance.BACKGROUND_LOCATION,
        ),
        AccessKind.USAGE_ACCESS to AccessRule(
            order = 20,
            action = SetupAction.SystemSettings.USAGE_ACCESS,
            guidance = SetupGuidance.USAGE_ACCESS,
        ),
        AccessKind.RESEARCH_KEYBOARD_ENABLED to AccessRule(
            order = 30,
            action = SetupAction.SystemSettings.INPUT_METHODS,
            guidance = SetupGuidance.RESEARCH_KEYBOARD_ENABLE,
        ),
        AccessKind.RESEARCH_KEYBOARD_SELECTED to AccessRule(
            order = 31,
            prerequisites = setOf(AccessKind.RESEARCH_KEYBOARD_ENABLED),
            action = SetupAction.ShowInputMethodPicker,
            guidance = SetupGuidance.RESEARCH_KEYBOARD_SELECT,
        ),
        AccessKind.ACCELEROMETER_HARDWARE to AccessRule(order = 40),
        AccessKind.GYROSCOPE_HARDWARE to AccessRule(order = 41),
        AccessKind.AMBIENT_LIGHT_HARDWARE to AccessRule(order = 42),
        AccessKind.PROXIMITY_HARDWARE to AccessRule(order = 43),
    )

    init {
        require(byKind.keys == AccessKind.entries.toSet()) { "Every access kind must have exactly one rule" }
        require(byKind.values.map(AccessRule::order).distinct().size == byKind.size) {
            "Access setup order values must be unique"
        }
        byKind.forEach { (kind, rule) ->
            require(kind !in rule.prerequisites) { "$kind cannot depend on itself" }
            require(rule.prerequisites.all(byKind::containsKey)) { "$kind has an unknown prerequisite" }
        }
        ordered(AccessKind.entries.map { AccessRequirement(it, required = false) }.toSet())
    }

    fun ordered(requirements: Set<AccessRequirement>): List<AccessRequirement> {
        val byRequiredKind = requirements.associateBy(AccessRequirement::kind)
        require(byRequiredKind.size == requirements.size) { "Duplicate access requirements" }
        requirements.forEach { requirement ->
            val missing = byKind.getValue(requirement.kind).prerequisites - byRequiredKind.keys
            require(missing.isEmpty()) {
                "${requirement.kind} requires undeclared access: ${missing.sortedBy(AccessKind::name)}"
            }
        }

        val remaining = byRequiredKind.toMutableMap()
        val completed = mutableSetOf<AccessKind>()
        val result = mutableListOf<AccessRequirement>()
        while (remaining.isNotEmpty()) {
            val next = remaining.values
                .filter { requirement -> byKind.getValue(requirement.kind).prerequisites.all(completed::contains) }
                .minByOrNull { requirement -> byKind.getValue(requirement.kind).order }
                ?: error("Access prerequisite graph contains a cycle")
            result += next
            completed += next.kind
            remaining -= next.kind
        }
        return result
    }

    fun resolve(
        requirements: Set<AccessRequirement>,
        satisfiedKinds: Set<AccessKind>,
        unavailableSettings: Set<SetupAction.SystemSettings>,
        actionOverrides: Map<AccessKind, AccessRuleOverride> = emptyMap(),
        unavailableKinds: Map<AccessKind, AccessUnavailableReason> = emptyMap(),
    ): AccessSnapshot {
        require(satisfiedKinds.all { kind -> requirements.any { it.kind == kind } }) {
            "Satisfied access must be part of the requested requirements"
        }
        val requestedKinds = requirements.mapTo(mutableSetOf(), AccessRequirement::kind)
        require(actionOverrides.keys.all(requestedKinds::contains)) {
            "Access action overrides must be part of the requested requirements"
        }
        require(unavailableKinds.keys.all(requestedKinds::contains)) {
            "Unavailable access must be part of the requested requirements"
        }
        require((actionOverrides.keys intersect unavailableKinds.keys).isEmpty()) {
            "Access cannot be both actionable and unavailable"
        }
        val statuses = ordered(requirements).map { requirement ->
            val rule = byKind.getValue(requirement.kind)
            val override = actionOverrides[requirement.kind]
            val action = override?.action ?: rule.action
            val guidance = override?.guidance ?: rule.guidance
            val missingPrerequisites = rule.prerequisites - satisfiedKinds
            val resolution = when {
                requirement.kind in satisfiedKinds -> AccessResolution.Satisfied
                missingPrerequisites.isNotEmpty() ->
                    AccessResolution.BlockedByPrerequisites(missingPrerequisites)
                requirement.kind in unavailableKinds ->
                    AccessResolution.Unavailable(unavailableKinds.getValue(requirement.kind))
                action == null -> AccessResolution.Unavailable(AccessUnavailableReason.HARDWARE_ABSENT)
                action is SetupAction.SystemSettings && action in unavailableSettings ->
                    AccessResolution.Unavailable(AccessUnavailableReason.SYSTEM_HANDLER_MISSING)
                else -> AccessResolution.ActionRequired(action)
            }
            AccessStatus(requirement, resolution, guidance)
        }
        return AccessSnapshot(statuses)
    }
}
