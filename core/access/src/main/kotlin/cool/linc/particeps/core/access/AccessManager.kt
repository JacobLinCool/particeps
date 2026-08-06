package cool.linc.particeps.core.access

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import cool.linc.particeps.core.collector.AccessKind
import cool.linc.particeps.core.collector.AccessRequirement
import cool.linc.particeps.core.collector.AccessStatus
import cool.linc.particeps.core.collector.StudyAccessGateway

class AccessManager(
    context: Context,
    private val keyboardServiceClassName: String,
) : StudyAccessGateway {
    private val applicationContext = context.applicationContext

    override fun inspect(requirements: Set<AccessRequirement>): List<AccessStatus> = requirements
        .distinctBy { it.kind }
        .sortedBy { it.kind.name }
        .map { AccessStatus(it, isGranted(it.kind)) }

    override fun grantedKinds(requirements: Set<AccessRequirement>): Set<AccessKind> =
        inspect(requirements).filter(AccessStatus::granted).mapTo(mutableSetOf()) { it.requirement.kind }

    fun settingsIntent(kind: AccessKind): Intent? = when (kind) {
        AccessKind.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        AccessKind.RESEARCH_KEYBOARD_ENABLED -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        AccessKind.BACKGROUND_LOCATION -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${applicationContext.packageName}"),
        )
        AccessKind.FINE_LOCATION,
        AccessKind.NOTIFICATIONS,
        AccessKind.RESEARCH_KEYBOARD_SELECTED,
        AccessKind.ACCELEROMETER_HARDWARE,
        AccessKind.GYROSCOPE_HARDWARE,
        AccessKind.AMBIENT_LIGHT_HARDWARE,
        AccessKind.PROXIMITY_HARDWARE -> null
    }

    fun showInputMethodPicker() {
        applicationContext.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun isGranted(kind: AccessKind): Boolean = when (kind) {
        AccessKind.FINE_LOCATION -> permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        AccessKind.BACKGROUND_LOCATION -> permissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        AccessKind.NOTIFICATIONS -> permissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        AccessKind.USAGE_ACCESS -> usageAccessGranted()
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

    @Suppress("DEPRECATION")
    private fun usageAccessGranted(): Boolean {
        val appOps = applicationContext.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                applicationContext.applicationInfo.uid,
                applicationContext.packageName,
                null,
            )
        } else {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                applicationContext.applicationInfo.uid,
                applicationContext.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun enabledKeyboardIds(): Set<String> = inputMethodManager()
        .enabledInputMethodList
        .mapTo(mutableSetOf()) { it.id }

    private fun selectedKeyboardId(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        inputMethodManager().currentInputMethodInfo?.id
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
    }

    private fun inputMethodManager(): InputMethodManager =
        applicationContext.getSystemService(InputMethodManager::class.java)

    private fun keyboardId(): String = ComponentName(
        applicationContext.packageName,
        keyboardServiceClassName,
    ).flattenToShortString()
}
