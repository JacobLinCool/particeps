package cool.jacoblin.particeps

import android.content.Context
import android.os.SystemClock
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the participant chose to share debug information. Nothing is sent while [UNDECIDED]. */
enum class DebugSharing { UNDECIDED, SHARED, NOT_SHARED }

/**
 * Debug information about the App itself: which step failed, with what kind of error, and how long
 * steps such as export took. It is separate from the study. It never carries research data,
 * survey answers, configuration content, study or experiment IDs, the installation code, or error
 * messages (which can contain paths or identifiers); only fixed step names, outcome categories,
 * durations, coarse buckets, exception class names and stack frames. PostHog adds the app version
 * and device model. It is sent only after the participant opts in at first launch, and stops when
 * they opt out.
 */
object DebugTelemetry {
    private const val PREFERENCES = "debug-telemetry"
    private const val SHARING_KEY = "sharing"
    private const val PROJECT_TOKEN = "phc_rMzrdUkcmxAAQrFezWWhUE3aqGSvMujFJy92fXbVat9V"
    private const val HOST = "https://us.i.posthog.com"

    private val started = AtomicBoolean(false)
    private val mutableSharing = MutableStateFlow(DebugSharing.UNDECIDED)
    val sharing: StateFlow<DebugSharing> = mutableSharing.asStateFlow()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        mutableSharing.value = readSharing(context)
        if (mutableSharing.value == DebugSharing.SHARED) start(context.applicationContext)
        installCrashCapture()
    }

    fun setSharing(context: Context, shared: Boolean) {
        val choice = if (shared) DebugSharing.SHARED else DebugSharing.NOT_SHARED
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(SHARING_KEY, choice.name)
            .apply()
        mutableSharing.value = choice
        if (shared) {
            start(context.applicationContext)
            if (PostHog.isOptOut()) PostHog.optIn()
            capture("debug_sharing_enabled", emptyMap())
        } else if (started.get()) {
            PostHog.optOut()
        }
    }

    /** One fixed-name step outcome with its duration. [properties] must already be sanitized. */
    fun step(step: String, outcome: String, durationMillis: Long, properties: Map<String, Any> = emptyMap()) {
        capture(
            "app_step",
            properties + mapOf("step" to step, "outcome" to outcome, "duration_ms" to durationMillis),
        )
    }

    /** Times [block] and records its outcome; failures are recorded by class name only. */
    suspend fun <T> timed(step: String, outcome: (T) -> String, block: suspend () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val result = block()
            step(step, outcome(result), SystemClock.elapsedRealtime() - startedAt)
            return result
        } catch (cancelled: CancellationException) {
            step(step, "cancelled", SystemClock.elapsedRealtime() - startedAt)
            throw cancelled
        } catch (failure: Throwable) {
            step(step, "error", SystemClock.elapsedRealtime() - startedAt, errorProperties(failure))
            throw failure
        }
    }

    fun error(step: String, failure: Throwable) {
        capture("app_error", errorProperties(failure) + mapOf("step" to step))
    }

    internal fun errorProperties(failure: Throwable): Map<String, Any> = mapOf(
        "error_type" to failure.javaClass.name,
        "cause_type" to (failure.cause?.javaClass?.name ?: "none"),
        "stack" to stackFrames(failure),
    )

    /** Class, method and line of the top frames; never the exception message. */
    internal fun stackFrames(failure: Throwable, limit: Int = 12): String =
        failure.stackTrace.take(limit).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }

    /** A 50 MB step, the same coarseness participants see; never an exact size. */
    internal fun sizeBucket(bytes: Long): String {
        val step = ParticipantSizeBucket.of(bytes.coerceAtLeast(0))
        return "${step.lowerMegabytes}-${step.upperMegabytes}MB"
    }

    /** Order of magnitude of a count, such as "1k-10k". */
    internal fun countBucket(count: Long): String = when {
        count < 1 -> "0"
        count < 100 -> "1-100"
        count < 1_000 -> "100-1k"
        count < 10_000 -> "1k-10k"
        count < 100_000 -> "10k-100k"
        count < 1_000_000 -> "100k-1m"
        else -> "1m+"
    }

    private fun capture(event: String, properties: Map<String, Any>) {
        if (mutableSharing.value != DebugSharing.SHARED || !started.get()) return
        runCatching { PostHog.capture(event = event, properties = properties) }
    }

    private fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val config = PostHogAndroidConfig(projectToken = PROJECT_TOKEN, host = HOST).apply {
            captureApplicationLifecycleEvents = true
            captureScreenViews = false
            captureDeepLinks = false
            sessionReplay = false
            sendFeatureFlagEvent = false
            preloadFeatureFlags = false
        }
        runCatching { PostHogAndroid.setup(context, config) }.onFailure { started.set(false) }
    }

    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is DebugCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(DebugCrashHandler(previous))
    }

    private class DebugCrashHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, failure: Throwable) {
            runCatching {
                error("uncaught_${if (thread.name == "main") "main" else "background"}", failure)
                if (started.get()) PostHog.flush()
            }
            previous?.uncaughtException(thread, failure)
        }
    }

    /** Forgets the choice so the first-launch question appears again; instrumented tests only. */
    @androidx.annotation.VisibleForTesting
    internal fun forgetChoiceForTesting(context: Context) {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        mutableSharing.value = DebugSharing.UNDECIDED
    }

    private fun readSharing(context: Context): DebugSharing =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SHARING_KEY, null)
            ?.let { stored -> DebugSharing.entries.firstOrNull { it.name == stored } }
            ?: DebugSharing.UNDECIDED
}
