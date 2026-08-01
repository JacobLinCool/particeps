package cool.linc.androiddatacollector.collector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.HandlerThread
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.collector.AccessKind
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.LocationConfiguration
import cool.linc.androiddatacollector.core.definition.LocationPriority
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.SerializedCallbackCollector
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

class LocationCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = LocationConfiguration.ID,
        payloadSchemaVersion = 1,
        displayName = "Location",
        privacyClass = PrivacyClass.SENSITIVE,
        maximumEncodedEventBytes = 4_096,
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        val typed = configuration as? LocationConfiguration
            ?: throw IllegalArgumentException("Invalid location configuration")
        return setOf(
            AccessRequirement(AccessKind.FINE_LOCATION, typed.required),
            AccessRequirement(AccessKind.BACKGROUND_LOCATION, typed.required),
        )
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector = LocationCollector(
        applicationContext,
        configuration as? LocationConfiguration
            ?: throw IllegalArgumentException("Invalid location configuration"),
        context,
    )
}

private class LocationCollector(
    private val applicationContext: Context,
    private val configuration: LocationConfiguration,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(applicationContext)
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::capture)
        }
    }
    private var handlerThread: HandlerThread? = null

    override suspend fun registerSource() {
        if (applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Fine location permission is unavailable")
        }
        val thread = HandlerThread("adc-location").also { it.start() }
        val request = LocationRequest.Builder(configuration.priority.toPlayServicesPriority(), configuration.intervalMillis)
            .setMinUpdateIntervalMillis(configuration.minimumIntervalMillis)
            .setMaxUpdateDelayMillis(configuration.maximumBatchDelayMillis)
            .setMinUpdateDistanceMeters(configuration.minimumDisplacementMeters)
            .build()
        try {
            client.requestLocationUpdates(request, callback, thread.looper).await()
        } catch (failure: Throwable) {
            thread.quitSafely()
            throw failure
        }
        handlerThread = thread
    }

    override suspend fun unregisterSource() {
        client.removeLocationUpdates(callback).await()
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun capture(location: Location) {
        capture {
            val fields = buildMap {
                put("source_elapsed_realtime_nanos", location.elapsedRealtimeNanos.toString())
                put("latitude_degrees", location.latitude.toString())
                put("longitude_degrees", location.longitude.toString())
                put("horizontal_accuracy_meters", location.accuracy.toString())
                put("source_time_utc_millis", location.time.toString())
                if (location.hasAltitude()) put("altitude_meters", location.altitude.toString())
                if (location.hasVerticalAccuracy()) {
                    put("vertical_accuracy_meters", location.verticalAccuracyMeters.toString())
                }
                if (location.hasSpeed()) put("speed_meters_per_second", location.speed.toString())
                if (location.hasSpeedAccuracy()) {
                    put("speed_accuracy_meters_per_second", location.speedAccuracyMetersPerSecond.toString())
                }
                if (location.hasBearing()) put("bearing_degrees", location.bearing.toString())
                if (location.hasBearingAccuracy()) {
                    put("bearing_accuracy_degrees", location.bearingAccuracyDegrees.toString())
                }
                put("mock", location.isMock.toString())
            }
            EventDraft(
                collectorId = LocationConfiguration.ID,
                payloadSchemaVersion = 1,
                observedTime = context.clocks.now(),
                payloadType = "LOCATION_FIX",
                fields = fields,
            )
        }
    }

    private fun LocationPriority.toPlayServicesPriority(): Int = when (this) {
        LocationPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
    }

    private companion object {
        const val CHANNEL_CAPACITY = 512
    }
}
