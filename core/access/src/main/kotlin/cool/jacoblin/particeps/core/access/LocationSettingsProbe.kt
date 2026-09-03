package cool.jacoblin.particeps.core.access

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import com.google.android.gms.location.SettingsClient
import cool.jacoblin.particeps.core.collector.LocationAccessProfile
import cool.jacoblin.particeps.core.definition.LocationV1PriorityValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

enum class LocationSettingsProbeResult {
    READY,
    RESOLUTION_REQUIRED,
    CHANGE_UNAVAILABLE,
    CHECK_FAILED,
}

fun interface LocationSettingsProbe {
    suspend fun inspect(profile: LocationAccessProfile): LocationSettingsProbeResult
}

/** Checks the configured Fused Location Provider request without exporting its resolution intent. */
class GooglePlayLocationSettingsProbe internal constructor(
    private val checkLocationSettings: suspend (LocationAccessProfile) -> Unit,
) : LocationSettingsProbe {
    constructor(context: Context) : this(
        LocationServices.getSettingsClient(context.applicationContext),
    )

    internal constructor(settingsClient: SettingsClient) : this(
        checkLocationSettings = { profile ->
            val request = LocationSettingsRequest.Builder()
                .addLocationRequest(profile.toGooglePlayLocationRequest())
                .build()
            settingsClient.checkLocationSettings(request).await()
        },
    )

    override suspend fun inspect(profile: LocationAccessProfile): LocationSettingsProbeResult = try {
        val completed = withTimeoutOrNull(LOCATION_SETTINGS_CHECK_TIMEOUT_MILLIS) {
            checkLocationSettings(profile)
            true
        } ?: false
        if (completed) LocationSettingsProbeResult.READY else LocationSettingsProbeResult.CHECK_FAILED
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        failure.toProbeResult()
    }
}

internal const val LOCATION_SETTINGS_CHECK_TIMEOUT_MILLIS = 5_000L

internal fun LocationAccessProfile.toGooglePlayLocationRequest(): LocationRequest = LocationRequest.Builder(
    priority.toGooglePlayPriority(),
    intervalMillis,
)
    .setMinUpdateIntervalMillis(minimumIntervalMillis)
    .setMaxUpdateDelayMillis(maximumBatchDelayMillis)
    .setMinUpdateDistanceMeters(minimumDisplacementMeters())
    .build()

internal fun LocationAccessProfile.minimumDisplacementMeters(): Float =
    minimumDisplacementMillimeters / 1_000f

internal fun LocationV1PriorityValue.toGooglePlayPriority(): Int = when (this) {
    LocationV1PriorityValue.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    LocationV1PriorityValue.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
}

private fun Exception.toProbeResult(): LocationSettingsProbeResult = when {
    this is ResolvableApiException && statusCode == CommonStatusCodes.RESOLUTION_REQUIRED ->
        LocationSettingsProbeResult.RESOLUTION_REQUIRED
    this is ApiException && statusCode == LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE ->
        LocationSettingsProbeResult.CHANGE_UNAVAILABLE
    else -> LocationSettingsProbeResult.CHECK_FAILED
}
