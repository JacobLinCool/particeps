package cool.jacoblin.particeps.core.access

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import cool.jacoblin.particeps.collector.usagecommon.isUsageAccessGranted
import cool.jacoblin.particeps.core.collector.AccessInspectionRequest
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessRequirement
import cool.jacoblin.particeps.core.collector.AccessSnapshot
import cool.jacoblin.particeps.core.collector.AccessUnavailableReason
import cool.jacoblin.particeps.core.collector.LocationAccessProfile
import cool.jacoblin.particeps.core.collector.NotificationAccessFeature
import cool.jacoblin.particeps.core.collector.SetupAction
import cool.jacoblin.particeps.core.collector.SetupGuidance
import cool.jacoblin.particeps.core.collector.StudyAccessGateway

class AccessManager(
    context: Context,
    private val keyboardServiceClassName: String,
    notificationChannelIdsByFeature: Map<NotificationAccessFeature, String>,
    private val locationSettingsProbe: LocationSettingsProbe = GooglePlayLocationSettingsProbe(context),
) : StudyAccessGateway {
    private val applicationContext = context.applicationContext
    private val permissionState = applicationContext.getSharedPreferences(
        PERMISSION_STATE_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val notificationChannelIdsByFeature = validatedNotificationChannelIds(notificationChannelIdsByFeature)

    override suspend fun inspect(request: AccessInspectionRequest): AccessSnapshot {
        val requirements = request.requirements
        val satisfiedKinds = requirements
            .filterTo(mutableSetOf()) { requirement ->
                requirement.kind != AccessKind.LOCATION_SERVICES &&
                    isGranted(requirement.kind, request.notificationFeatures)
            }
            .mapTo(mutableSetOf(), AccessRequirement::kind)
        val locationResult = if (
            requirements.any { it.kind == AccessKind.LOCATION_SERVICES } &&
            AccessRules.byKind.getValue(AccessKind.LOCATION_SERVICES)
                .prerequisites.all(satisfiedKinds::contains)
        ) {
            inspectLocationSettings(requireNotNull(request.locationProfile))
        } else {
            null
        }
        if (locationResult == LocationSettingsProbeResult.READY) {
            satisfiedKinds += AccessKind.LOCATION_SERVICES
        }
        val unavailableSettings = SetupAction.SystemSettings.entries
            .filterTo(mutableSetOf()) { action -> settingsIntent(action) == null }
        val actionOverrides = mutableMapOf<AccessKind, AccessRuleOverride>()
        val unavailableKinds = mutableMapOf<AccessKind, AccessUnavailableReason>()
        when (locationResult) {
            LocationSettingsProbeResult.CHANGE_UNAVAILABLE -> unavailableKinds[AccessKind.LOCATION_SERVICES] =
                AccessUnavailableReason.LOCATION_SETTINGS_CHANGE_UNAVAILABLE
            LocationSettingsProbeResult.CHECK_FAILED -> unavailableKinds[AccessKind.LOCATION_SERVICES] =
                AccessUnavailableReason.LOCATION_SETTINGS_CHECK_FAILED
            LocationSettingsProbeResult.READY,
            LocationSettingsProbeResult.RESOLUTION_REQUIRED,
            null -> Unit
        }
        requirements.forEach { requirement ->
            val permission = runtimePermission(requirement.kind) ?: return@forEach
            if (requirement.kind in satisfiedKinds) return@forEach
            if (
                settingsRequired(requirement.kind) ||
                requirement.kind == AccessKind.NOTIFICATIONS && permissionGranted(permission)
            ) {
                actionOverrides[requirement.kind] = when (requirement.kind) {
                    AccessKind.FINE_LOCATION -> AccessRuleOverride(
                        SetupAction.SystemSettings.APPLICATION_DETAILS,
                        SetupGuidance.FOREGROUND_LOCATION_SETTINGS,
                    )
                    AccessKind.NOTIFICATIONS -> AccessRuleOverride(
                        SetupAction.SystemSettings.APPLICATION_NOTIFICATIONS,
                        SetupGuidance.NOTIFICATIONS_SETTINGS,
                    )
                    else -> error("Only runtime permissions can have a fixed-denial action")
                }
            }
        }
        return AccessRules.resolve(
            requirements,
            satisfiedKinds,
            unavailableSettings,
            actionOverrides,
            unavailableKinds,
        )
    }

    fun recordRuntimePermissionResult(
        action: SetupAction.RuntimePermission,
        granted: Boolean,
        canRequestAgain: Boolean,
    ) {
        val kind = when (action) {
            SetupAction.RuntimePermission.FOREGROUND_LOCATION -> AccessKind.FINE_LOCATION
            SetupAction.RuntimePermission.NOTIFICATIONS -> AccessKind.NOTIFICATIONS
        }
        permissionState.edit()
            .putBoolean(settingsRequiredKey(kind), !granted && !canRequestAgain)
            .apply()
    }

    fun settingsIntent(action: SetupAction.SystemSettings): Intent? {
        val unresolved = when (action) {
            SetupAction.SystemSettings.APPLICATION_DETAILS -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${applicationContext.packageName}"),
            )
            SetupAction.SystemSettings.APPLICATION_NOTIFICATIONS -> Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ).putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
            SetupAction.SystemSettings.LOCATION_SERVICES -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            SetupAction.SystemSettings.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            SetupAction.SystemSettings.INPUT_METHODS -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        }
        val resolved = applicationContext.packageManager.resolveActivity(
            unresolved,
            PackageManager.ResolveInfoFlags.of(
                (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY).toLong(),
            ),
        ) ?: return null
        return unresolved.setComponent(
            ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name),
        )
    }

    fun showInputMethodPicker() {
        applicationContext.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private suspend fun inspectLocationSettings(profile: LocationAccessProfile): LocationSettingsProbeResult {
        val globallyEnabled = applicationContext
            .getSystemService(LocationManager::class.java)
            .isLocationEnabled
        return if (globallyEnabled) {
            locationSettingsProbe.inspect(profile)
        } else {
            LocationSettingsProbeResult.RESOLUTION_REQUIRED
        }
    }

    private fun isGranted(
        kind: AccessKind,
        notificationFeatures: Set<NotificationAccessFeature>,
    ): Boolean = when (kind) {
        AccessKind.FINE_LOCATION -> permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        AccessKind.LOCATION_SERVICES -> error("Location services require asynchronous inspection")
        AccessKind.BACKGROUND_LOCATION -> permissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        AccessKind.NOTIFICATIONS -> notificationsEnabled(notificationFeatures)
        AccessKind.USAGE_ACCESS -> isUsageAccessGranted(applicationContext)
        AccessKind.RESEARCH_KEYBOARD_ENABLED -> keyboardId() in enabledKeyboardIds()
        AccessKind.RESEARCH_KEYBOARD_SELECTED -> keyboardId() == selectedKeyboardId()
        AccessKind.ACCELEROMETER_HARDWARE -> applicationContext
            .getSystemService(SensorManager::class.java)
            .getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        AccessKind.GYROSCOPE_HARDWARE -> hasSensor(Sensor.TYPE_GYROSCOPE)
        AccessKind.AMBIENT_LIGHT_HARDWARE -> hasSensor(Sensor.TYPE_LIGHT)
        AccessKind.PROXIMITY_HARDWARE -> hasSensor(Sensor.TYPE_PROXIMITY)
    }

    private fun hasSensor(type: Int): Boolean = applicationContext
        .getSystemService(SensorManager::class.java)
        .getDefaultSensor(type) != null

    private fun permissionGranted(permission: String): Boolean =
        applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationsEnabled(features: Set<NotificationAccessFeature>): Boolean {
        require(features.isNotEmpty()) { "Notifications access requires at least one feature" }
        if (!permissionGranted(Manifest.permission.POST_NOTIFICATIONS)) return false
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && notificationChannelIds(
            features,
            notificationChannelIdsByFeature,
        ).all { channelId ->
            val channel = manager.getNotificationChannel(channelId)
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    private fun settingsRequired(kind: AccessKind): Boolean =
        permissionState.getBoolean(settingsRequiredKey(kind), false)

    private fun settingsRequiredKey(kind: AccessKind) = "settings-required-${kind.name}"

    private fun runtimePermission(kind: AccessKind): String? = when (kind) {
        AccessKind.FINE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        AccessKind.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        AccessKind.LOCATION_SERVICES,
        AccessKind.BACKGROUND_LOCATION,
        AccessKind.USAGE_ACCESS,
        AccessKind.RESEARCH_KEYBOARD_ENABLED,
        AccessKind.RESEARCH_KEYBOARD_SELECTED,
        AccessKind.ACCELEROMETER_HARDWARE,
        AccessKind.GYROSCOPE_HARDWARE,
        AccessKind.AMBIENT_LIGHT_HARDWARE,
        AccessKind.PROXIMITY_HARDWARE -> null
    }

    private fun enabledKeyboardIds(): Set<String> = inputMethodManager()
        .enabledInputMethodList
        .mapTo(mutableSetOf()) { it.id }

    private fun selectedKeyboardId(): String? = inputMethodManager().currentInputMethodInfo?.id

    private fun inputMethodManager(): InputMethodManager =
        applicationContext.getSystemService(InputMethodManager::class.java)

    private fun keyboardId(): String = ComponentName(
        applicationContext.packageName,
        keyboardServiceClassName,
    ).flattenToShortString()

    private companion object {
        const val PERMISSION_STATE_PREFERENCES = "particeps-access-state-v1"
    }
}

internal fun validatedNotificationChannelIds(
    channels: Map<NotificationAccessFeature, String>,
): Map<NotificationAccessFeature, String> = channels.toMap().also { snapshot ->
    require(snapshot.keys == NotificationAccessFeature.entries.toSet()) {
        "Every notification feature must have exactly one app-owned channel"
    }
    require(snapshot.values.all(String::isNotBlank)) { "Notification channel IDs must not be blank" }
    require(snapshot.values.distinct().size == snapshot.size) {
        "Notification features must use distinct channels"
    }
}

internal fun notificationChannelIds(
    features: Set<NotificationAccessFeature>,
    channels: Map<NotificationAccessFeature, String>,
): Set<String> = features.mapTo(mutableSetOf(), channels::getValue)
