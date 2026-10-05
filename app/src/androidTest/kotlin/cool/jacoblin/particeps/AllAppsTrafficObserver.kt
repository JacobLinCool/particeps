package cool.jacoblin.particeps

import android.os.SystemClock
import android.util.Log
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingActuator
import cool.jacoblin.particeps.actuator.trafficshaping.TrafficShapingCounterSnapshot
import cool.jacoblin.particeps.core.resource.DesiredResourceState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in test observation; neither sampling nor its failures change the transfer oracle. */
internal class AllAppsTrafficObserver(
    private val actuator: TrafficShapingActuator,
    private val desired: DesiredResourceState,
    connectionCount: Int,
) {
    val connections = List(connectionCount) { ConnectionProgress(it) }
    private val finished = AtomicBoolean(false)
    private var job: Job? = null
    private var firstCounters: TrafficShapingCounterSnapshot? = null

    fun start(scope: CoroutineScope) {
        check(job == null)
        job = scope.launch(Dispatchers.IO) {
            repeat(180) {
                capture("periodic")
                delay(1_000)
            }
            // The observer has its own finite lifetime, without terminating or extending the test.
            emit(JSONObject().put("event", "sample_limit_reached"))
        }
    }

    suspend fun finish() {
        if (!finished.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            job?.cancelAndJoin()
            capture("before_release")
        }
    }

    private fun capture(event: String) {
        try {
            val record = JSONObject()
                .put("event", event)
                .put("sample_started_elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos())
            try {
                val health = actuator.health()
                record.put("health_status", health.status.name)
                    .put("health_matches_expected", health.generation == desired.generation &&
                        health.appliedProfileSha256 == desired.profile?.expectedSha256)
                    // ResourceHealth validates this value as a fixed reason code, not free text.
                    .put("failure_reason", health.failureReason ?: JSONObject.NULL)
            } catch (error: Exception) {
                record.put("health_error_class", error.javaClass.simpleName)
            }
            try {
                val counters = actuator.snapshot()
                record.put("snapshot_available", counters != null)
                if (counters != null) {
                    val initial = firstCounters ?: counters.also { firstCounters = it }
                    record.put("snapshot_profile_matches_expected", counters.profileSha256 == desired.profile?.expectedSha256)
                        .put("snapshot_identity_matches_first", counters.nativeGeneration == initial.nativeGeneration &&
                            counters.vpnGenerationId == initial.vpnGenerationId && counters.profileSha256 == initial.profileSha256)
                        .put("uplink_bytes", counters.uplinkBytes)
                        .put("uplink_packets", counters.uplinkPackets)
                        .put("downlink_bytes", counters.downlinkBytes)
                        .put("downlink_packets", counters.downlinkPackets)
                        .put("uplink_throttled_nanos", counters.uplinkThrottledNanos)
                        .put("downlink_throttled_nanos", counters.downlinkThrottledNanos)
                }
            } catch (error: Exception) {
                record.put("snapshot_error_class", error.javaClass.simpleName)
            }
            record.put("connections", JSONArray(connections.map { it.snapshot() }))
                .put("sample_completed_elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos())
            emit(record)
        } catch (error: Exception) {
            // Diagnostics must not replace a transfer failure or turn missing counters into zero.
            emit(JSONObject().put("event", "observer_error").put("error_class", error.javaClass.simpleName))
        }
    }

    private fun emit(record: JSONObject) {
        try {
            Log.i("AllAppsTrafficObserver", record.toString())
        } catch (_: Exception) {
            // Logging is observational, including when Android's logger is unavailable.
        }
    }

    class ConnectionProgress(private val index: Int) {
        private data class State(
            val started: Long,
            val stage: String,
            val bytes: Int,
            val transferStarted: Long?,
            val firstByte: Long?,
            val lastProgress: Long?,
            val eof: Boolean,
            val errorClass: String?,
        )
        private var state: State? = null

        @Synchronized
        fun record(
            started: Long,
            stage: String,
            bytes: Int,
            transferStarted: Long?,
            firstByte: Long?,
            lastProgress: Long?,
            eof: Boolean,
            errorClass: String?,
        ) {
            state = State(started, stage, bytes, transferStarted, firstByte, lastProgress, eof, errorClass)
        }

        fun snapshot(): JSONObject {
            val current = synchronized(this) { state }
            val record = JSONObject().put("connection_index", index).put("started", current != null)
            if (current != null) {
                record.put("started_elapsed_realtime_millis", current.started)
                    .put("stage", current.stage)
                    .put("received_bytes", current.bytes)
                    .put("transfer_started_elapsed_millis", current.transferStarted ?: JSONObject.NULL)
                    .put("first_byte_elapsed_millis", current.firstByte ?: JSONObject.NULL)
                    .put("last_progress_elapsed_millis", current.lastProgress ?: JSONObject.NULL)
                    .put("reached_eof", current.eof)
                    .put("error_class", current.errorClass ?: JSONObject.NULL)
            }
            return record
        }
    }
}
