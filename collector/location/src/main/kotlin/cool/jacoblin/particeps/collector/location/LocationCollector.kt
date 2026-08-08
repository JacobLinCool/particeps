package cool.jacoblin.particeps.collector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.HandlerThread
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.definition.CollectorConfiguration
import cool.jacoblin.particeps.core.definition.LocationConfiguration
import cool.jacoblin.particeps.core.definition.LocationPriority
import cool.jacoblin.particeps.core.collector.PrivacyClass
import cool.jacoblin.particeps.core.collector.ProtocolEventContracts
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.SerializedCallbackCollector
import cool.jacoblin.particeps.core.collector.SourceCallbackBoundary
import cool.jacoblin.particeps.core.collector.SourceRegistrationResult
import cool.jacoblin.particeps.core.collector.SourceTeardownResult
import cool.jacoblin.particeps.core.collector.completeSourceTeardown
import cool.jacoblin.particeps.core.collector.registerSourceWithRollback
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LocationCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = LocationConfiguration.ID,
        displayName = "Location",
        privacyClass = PrivacyClass.SENSITIVE,
        accessKinds = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        ),
        eventContract = requireNotNull(ProtocolEventContracts[LocationConfiguration.ID]),
    )

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
    private val callbackBoundary = SourceCallbackBoundary()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            callbackBoundary.runIfActive { result.locations.forEach(::capture) }
        }
    }
    private var handlerThread: HandlerThread? = null

    override suspend fun registerSource(): SourceRegistrationResult {
        if (applicationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("Fine location permission is unavailable")
        }
        val thread = HandlerThread("particeps-location").also { it.start() }
        val request = LocationRequest.Builder(configuration.priority.toPlayServicesPriority(), configuration.intervalMillis)
            .setMinUpdateIntervalMillis(configuration.minimumIntervalMillis)
            .setMaxUpdateDelayMillis(configuration.maximumBatchDelayMillis)
            .setMinUpdateDistanceMeters(configuration.minimumDisplacementMillimeters / 1_000f)
            .build()
        callbackBoundary.activate()
        var updatesRegistered = false
        val result = registerSourceWithRollback(
            register = {
                withContext(NonCancellable) {
                    client.requestLocationUpdates(request, callback, thread.looper).await()
                    updatesRegistered = true
                }
            },
            rollback = {
                completeSourceTeardown(
                    { if (updatesRegistered) client.removeLocationUpdates(callback).await() },
                    { callbackBoundary.deactivate() },
                    { thread.quitSafely() },
                )
            },
        )
        if (result == SourceRegistrationResult.Registered) handlerThread = thread
        return result
    }

    override suspend fun unregisterSource(): SourceTeardownResult {
        val thread = handlerThread
        completeSourceTeardown(
            { client.removeLocationUpdates(callback).await() },
            { callbackBoundary.deactivate() },
            {
                thread?.quitSafely()
                handlerThread = null
            },
        )
        return SourceTeardownResult.Released
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
