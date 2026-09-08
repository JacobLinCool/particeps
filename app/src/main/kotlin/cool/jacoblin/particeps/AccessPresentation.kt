package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessUnavailableReason
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance

internal data class SetupGuidancePresentation(
    val descriptionRes: Int,
    val stepResources: List<Int>,
) {
    init {
        require(stepResources.isNotEmpty()) { "Setup guidance must contain at least one step" }
        require(stepResources.distinct().size == stepResources.size) { "Setup guidance steps must be unique" }
    }
}

internal fun AccessKind.labelRes(): Int = when (this) {
    AccessKind.FINE_LOCATION -> R.string.access_fine_location
    AccessKind.LOCATION_SERVICES -> R.string.access_location_services
    AccessKind.BACKGROUND_LOCATION -> R.string.access_background_location
    AccessKind.NOTIFICATIONS -> R.string.access_notifications
    AccessKind.USAGE_ACCESS -> R.string.access_usage_access
    AccessKind.RESEARCH_KEYBOARD_ENABLED -> R.string.access_research_keyboard_enabled
    AccessKind.RESEARCH_KEYBOARD_SELECTED -> R.string.access_research_keyboard_selected
    AccessKind.ACCELEROMETER_HARDWARE -> R.string.access_accelerometer_hardware
    AccessKind.GYROSCOPE_HARDWARE -> R.string.access_gyroscope_hardware
    AccessKind.AMBIENT_LIGHT_HARDWARE -> R.string.access_ambient_light_hardware
    AccessKind.PROXIMITY_HARDWARE -> R.string.access_proximity_hardware
}

internal fun SetupAction.labelRes(): Int = when (this) {
    is SetupAction.RuntimePermission -> R.string.action_allow_access
    is SetupAction.SystemSettings -> R.string.action_open_settings
    SetupAction.ShowInputMethodPicker -> R.string.action_choose_keyboard
}

internal fun SetupGuidance.presentation(): SetupGuidancePresentation = when (this) {
    SetupGuidance.FOREGROUND_LOCATION_SETTINGS -> SetupGuidancePresentation(
        descriptionRes = R.string.access_fine_location_settings_description,
        stepResources = listOf(
            R.string.access_fine_location_settings_step_permissions,
            R.string.access_fine_location_settings_step_precise,
            R.string.access_fine_location_settings_step_return,
        ),
    )
    SetupGuidance.LOCATION_SERVICES -> SetupGuidancePresentation(
        descriptionRes = R.string.access_location_services_description,
        stepResources = listOf(
            R.string.access_location_services_step_enable,
            R.string.access_location_services_step_return,
        ),
    )
    SetupGuidance.BACKGROUND_LOCATION -> SetupGuidancePresentation(
        descriptionRes = R.string.access_background_location_description,
        stepResources = listOf(
            R.string.access_background_location_step_open_permissions,
            R.string.access_background_location_step_choose_always,
            R.string.access_background_location_step_return,
        ),
    )
    SetupGuidance.NOTIFICATIONS_SETTINGS -> SetupGuidancePresentation(
        descriptionRes = R.string.access_notifications_settings_description,
        stepResources = listOf(
            R.string.access_notifications_settings_step_enable,
            R.string.access_notifications_settings_step_channels,
            R.string.access_notifications_settings_step_return,
        ),
    )
    SetupGuidance.USAGE_ACCESS -> SetupGuidancePresentation(
        descriptionRes = R.string.access_usage_access_description,
        stepResources = listOf(
            R.string.access_usage_access_step_choose_app,
            R.string.access_usage_access_step_enable,
            R.string.access_usage_access_step_return,
        ),
    )
    SetupGuidance.RESEARCH_KEYBOARD_ENABLE -> SetupGuidancePresentation(
        descriptionRes = R.string.access_keyboard_enable_description,
        stepResources = listOf(
            R.string.access_keyboard_enable_step_manage,
            R.string.access_keyboard_enable_step_enable,
            R.string.access_keyboard_enable_step_return,
        ),
    )
    SetupGuidance.RESEARCH_KEYBOARD_SELECT -> SetupGuidancePresentation(
        descriptionRes = R.string.access_keyboard_select_description,
        stepResources = listOf(
            R.string.access_keyboard_select_step_choose,
            R.string.access_keyboard_select_step_return,
        ),
    )
}

internal fun AccessUnavailableReason.messageRes(): Int = when (this) {
    AccessUnavailableReason.HARDWARE_ABSENT -> R.string.access_hardware_unavailable
    AccessUnavailableReason.LOCATION_SETTINGS_CHANGE_UNAVAILABLE ->
        R.string.access_location_settings_change_unavailable
    AccessUnavailableReason.LOCATION_SETTINGS_CHECK_FAILED ->
        R.string.access_location_settings_check_failed
    AccessUnavailableReason.SYSTEM_HANDLER_MISSING -> R.string.access_system_screen_unavailable
}
