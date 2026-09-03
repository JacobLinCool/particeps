package cool.jacoblin.particeps.collector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.HandlerThread
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.LocationV1PriorityValue
import cool.jacoblin.particeps.core.definition.LocationV1ProfileConfiguration
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
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
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LocationCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = LocationV1ProfileConfiguration.SOURCE_ID,
        displayName = "Location",
        accessKinds = setOf(
            AccessKind.FINE_LOCATION,
            AccessKind.LOCATION_SERVICES,
            AccessKind.BACKGROUND_LOCATION,
        ),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[LocationV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(
        configuration: CollectorProfileConfiguration,
        context: CollectorContext,
    ): Collector = LocationCollector(
        applicationContext,
        configuration as? LocationV1ProfileConfiguration
            ?: throw IllegalArgumentException("Invalid location configuration"),
        context,
    )
}

private class LocationCollector(
    private val applicationContext: Context,
    private val configuration: LocationV1ProfileConfiguration,
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
                    client.requestLocationUpdates(request, callback, thread.looper)
                        .awaitAcknowledged("location update registration")
                    updatesRegistered = true
                }
            },
            rollback = {
                completeSourceTeardown(
                    {
                        if (updatesRegistered) {
                            client.removeLocationUpdates(callback).awaitAcknowledged("location update removal")
                        }
                    },
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
            { client.removeLocationUpdates(callback).awaitAcknowledged("location update removal") },
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
                type = EventTypeKey(EventSourceId(LocationV1ProfileConfiguration.SOURCE_ID), 1, "LOCATION_FIX"),
                observedTime = context.clocks.now(),
                fields = fields,
            )
        }
    }

    private fun LocationV1PriorityValue.toPlayServicesPriority(): Int = when (this) {
        LocationV1PriorityValue.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationV1PriorityValue.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
    }

    /**
     * Bounded await: a Play services Task carries no deadline of its own, and registration runs
     * while the whole app is still behind its starting screen. The timeout is converted out of
     * [TimeoutCancellationException] so an unresponsive Play services reads as a failed collector
     * start — handled and reported — rather than as cancellation of the caller.
     */
    private suspend fun <T> Task<T>.awaitAcknowledged(action: String): T = try {
        withTimeout(PLAY_SERVICES_ACKNOWLEDGEMENT_TIMEOUT_MILLIS) { await() }
    } catch (timeout: TimeoutCancellationException) {
        throw IllegalStateException("Play services did not acknowledge $action", timeout)
    }

    private companion object {
        const val CHANNEL_CAPACITY = 512
        const val PLAY_SERVICES_ACKNOWLEDGEMENT_TIMEOUT_MILLIS = 10_000L
    }
}
