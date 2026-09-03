package cool.jacoblin.particeps.collector.applifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.SerializedCallbackCollector
import cool.jacoblin.particeps.core.collector.SourceRegistrationResult
import cool.jacoblin.particeps.core.collector.SourceTeardownResult
import cool.jacoblin.particeps.core.definition.AppLifecycleV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppLifecycleCollectorPlugin(
    private val application: Application,
) : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id = AppLifecycleV1ProfileConfiguration.SOURCE_ID,
        displayName = "Own-app lifecycle",
        accessKinds = emptySet(),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[AppLifecycleV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(
        configuration: CollectorProfileConfiguration,
        context: CollectorContext,
    ): Collector {
        require(configuration is AppLifecycleV1ProfileConfiguration) { "Invalid app-lifecycle configuration" }
        return AppLifecycleCollector(application = application, collectorContext = context)
    }
}

private class AppLifecycleCollector(
    private val application: Application,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY),
    Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = capture(activity, "ACTIVITY_CREATED")

    override fun onActivityStarted(activity: Activity) = capture(activity, "ACTIVITY_STARTED")

    override fun onActivityResumed(activity: Activity) = capture(activity, "ACTIVITY_RESUMED")

    override fun onActivityPaused(activity: Activity) = capture(activity, "ACTIVITY_PAUSED")

    override fun onActivityStopped(activity: Activity) = capture(activity, "ACTIVITY_STOPPED")

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = capture(activity, "ACTIVITY_INSTANCE_STATE_SAVED")

    override fun onActivityDestroyed(activity: Activity) = capture(activity, "ACTIVITY_DESTROYED")

    private fun capture(
        activity: Activity,
        payloadType: String,
    ) {
        capture {
            EventDraft(
                type = EventTypeKey(
                    EventSourceId(AppLifecycleV1ProfileConfiguration.SOURCE_ID),
                    1,
                    payloadType,
                ),
                observedTime = context.clocks.now(),
                fields = mapOf("activity_class" to activity.javaClass.name),
            )
        }
    }

    override suspend fun registerSource(): SourceRegistrationResult = withContext(Dispatchers.Main.immediate) {
        application.registerActivityLifecycleCallbacks(this@AppLifecycleCollector)
        SourceRegistrationResult.Registered
    }

    override suspend fun unregisterSource(): SourceTeardownResult = withContext(Dispatchers.Main.immediate) {
        application.unregisterActivityLifecycleCallbacks(this@AppLifecycleCollector)
        SourceTeardownResult.Released
    }

    private companion object {
        const val CHANNEL_CAPACITY = 128
    }
}
