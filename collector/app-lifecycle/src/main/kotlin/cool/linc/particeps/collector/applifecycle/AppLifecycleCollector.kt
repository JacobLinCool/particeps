package cool.linc.particeps.collector.applifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import cool.linc.particeps.core.model.EventDraft
import cool.linc.particeps.core.collector.Collector
import cool.linc.particeps.core.collector.CollectorContext
import cool.linc.particeps.core.collector.CollectorDescriptor
import cool.linc.particeps.core.collector.CollectorPlugin
import cool.linc.particeps.core.collector.AccessRequirement
import cool.linc.particeps.core.collector.SerializedCallbackCollector
import cool.linc.particeps.core.collector.SourceRegistrationResult
import cool.linc.particeps.core.collector.SourceTeardownResult
import cool.linc.particeps.core.definition.AppLifecycleConfiguration
import cool.linc.particeps.core.definition.CollectorConfiguration
import cool.linc.particeps.core.collector.PrivacyClass
import cool.linc.particeps.core.collector.ProtocolEventContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppLifecycleCollectorPlugin(
    private val application: Application,
) : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id = COLLECTOR_ID,
        displayName = "Own-app lifecycle",
        privacyClass = PrivacyClass.SENSITIVE,
        eventContract = requireNotNull(ProtocolEventContracts[COLLECTOR_ID]),
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        require(configuration is AppLifecycleConfiguration) { "Invalid app-lifecycle configuration" }
        return emptySet()
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector {
        require(configuration is AppLifecycleConfiguration) { "Invalid app-lifecycle configuration" }
        return AppLifecycleCollector(application = application, collectorContext = context)
    }

    private companion object {
        const val COLLECTOR_ID = "app_lifecycle.v1"
        const val PAYLOAD_SCHEMA_VERSION = 1
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
                collectorId = COLLECTOR_ID,
                payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
                observedTime = context.clocks.now(),
                payloadType = payloadType,
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
        const val COLLECTOR_ID = "app_lifecycle.v1"
        const val PAYLOAD_SCHEMA_VERSION = 1
        const val CHANNEL_CAPACITY = 128
    }
}
