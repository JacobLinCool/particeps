package cool.jacoblin.particeps

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import cool.jacoblin.particeps.core.application.StudySessionManager
import cool.jacoblin.particeps.core.application.StudySessionSnapshot
import cool.jacoblin.particeps.core.model.ExperimentState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The same participant-safe session projection used by the original host assertion. */
internal data class HostHarnessSafetyPauseState(
    val initialized: Boolean,
    val state: ExperimentState?,
    val lifetimeDataEventCount: Long,
) {
    fun isPaused(): Boolean = initialized && state == ExperimentState.PAUSED && lifetimeDataEventCount >= 0

    companion object {
        fun from(snapshot: StudySessionSnapshot) = HostHarnessSafetyPauseState(
            snapshot.initialized, snapshot.runtime.state, snapshot.runtime.lifetimeDataEventCount,
        )
    }
}

internal data class HostHarnessSafetyPauseObservation(
    val session: HostHarnessSafetyPauseState,
    val elapsedRealtimeMillis: Long,
    val notificationTag: String?,
    val notificationBody: String?,
    val expectedNotificationBody: String,
) {
    fun isVerified(): Boolean = session.isPaused() && elapsedRealtimeMillis >= 0 &&
        notificationTag == AndroidRecoveryReporter.NOTIFICATION_TAG &&
        expectedNotificationBody.isNotEmpty() && notificationBody == expectedNotificationBody

    fun toJsonObject(): JSONObject = JSONObject()
        .put("initialized", session.initialized)
        .put("state", session.state?.name ?: JSONObject.NULL)
        .put("lifetime_data_event_count", session.lifetimeDataEventCount)
        .put("elapsed_realtime_millis", elapsedRealtimeMillis)
        .put("notification_tag", notificationTag ?: JSONObject.NULL)
        .put("notification_body", notificationBody ?: JSONObject.NULL)
        .put("expected_notification_body", expectedNotificationBody)
}

/** Constructible only from two actual, individually verified observations. */
internal class HostHarnessSafetyPauseProof private constructor(
    val observations: List<HostHarnessSafetyPauseObservation>,
) {
    fun toJson(): String = JSONObject()
        .put("schema_version", 1)
        .put("status", "VERIFIED_SAFETY_PAUSED")
        .put("observations", JSONArray(observations.map { it.toJsonObject() }))
        .toString()

    companion object {
        const val QUIESCENCE_MILLIS = 1_000L

        fun from(
            first: HostHarnessSafetyPauseObservation,
            second: HostHarnessSafetyPauseObservation,
        ): HostHarnessSafetyPauseProof {
            check(first.isVerified() && second.isVerified()) { "Safety pause observation is not verified" }
            check(first.session.lifetimeDataEventCount == second.session.lifetimeDataEventCount) {
                "Event admission changed during safety pause observation"
            }
            check(second.elapsedRealtimeMillis >= first.elapsedRealtimeMillis &&
                second.elapsedRealtimeMillis - first.elapsedRealtimeMillis >= QUIESCENCE_MILLIS) {
                "Safety pause observation interval is too short or reversed"
            }
            return HostHarnessSafetyPauseProof(listOf(first, second))
        }
    }
}

/**
 * Runs inside the existing process-bound operation's 90-second deadline. No session/runtime mutex
 * is acquired: the delay must allow collectors and lifecycle work to expose violations. The flow
 * observer also rejects any intervening invalid session projection it receives, even if the final
 * sample would have returned to PAUSED. Actual notification evidence is read at both endpoints.
 */
internal class HostHarnessSafetyPauseProofReader(
    private val sessionChanges: Flow<HostHarnessSafetyPauseState>,
    private val observe: () -> HostHarnessSafetyPauseObservation,
) {
    suspend fun read(): HostHarnessSafetyPauseProof {
        var first = observe()
        while (!first.isVerified()) {
            delay(50)
            first = observe()
        }
        return coroutineScope {
            val expectedCount = first.session.lifetimeDataEventCount
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                sessionChanges.collect { current ->
                    check(current.isPaused() && current.lifetimeDataEventCount == expectedCount) {
                        "Session changed during safety pause observation"
                    }
                }
            }
            try {
                delay(HostHarnessSafetyPauseProof.QUIESCENCE_MILLIS)
                HostHarnessSafetyPauseProof.from(first, observe())
            } finally {
                watcher.cancelAndJoin()
            }
        }
    }

    companion object {
        suspend fun read(context: Context, session: StudySessionManager): HostHarnessSafetyPauseProof {
            val notifications = checkNotNull(context.getSystemService(NotificationManager::class.java))
            return HostHarnessSafetyPauseProofReader(
                session.snapshot.map(HostHarnessSafetyPauseState::from),
            ) {
                val snapshot = session.snapshot.value
                val expected = context.getString(R.string.collection_paused_notification_body)
                val active = notifications.activeNotifications.firstOrNull {
                    it.tag == AndroidRecoveryReporter.NOTIFICATION_TAG &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == expected
                }
                HostHarnessSafetyPauseObservation(
                    HostHarnessSafetyPauseState.from(snapshot),
                    SystemClock.elapsedRealtime(),
                    active?.tag,
                    active?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    expected,
                )
            }.read()
        }
    }
}
