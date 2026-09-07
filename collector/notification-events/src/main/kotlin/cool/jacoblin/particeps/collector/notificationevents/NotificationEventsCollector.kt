package cool.jacoblin.particeps.collector.notificationevents

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cool.jacoblin.particeps.core.collector.*
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.NotificationEventsV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey

/** Android owns this binding. No snapshot or backlog is read when a study starts or resumes. */
class ResearchNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() { NotificationObservationBridge.connected() }
    override fun onListenerDisconnected() { NotificationObservationBridge.disconnected() }
    override fun onDestroy() {
        NotificationObservationBridge.disconnected()
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationObservationBridge.posted(sbn.packageName, sbn.key, sbn.postTime)
    }
}

class NotificationEventsCollectorPlugin : CollectorPlugin {
    override val descriptor = CollectorDescriptor(
        id = NotificationEventsV1ProfileConfiguration.SOURCE_ID,
        displayName = "Notification receipt events",
        accessKinds = setOf(AccessKind.NOTIFICATION_LISTENER),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[NotificationEventsV1ProfileConfiguration.SOURCE_ID]),
    )
    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
        require(configuration is NotificationEventsV1ProfileConfiguration)
        return NotificationEventsCollector(context)
    }
}

private class NotificationEventsCollector(collectorContext: CollectorContext) : SerializedCallbackCollector(collectorContext, 512) {
    override suspend fun registerSource(): SourceRegistrationResult = registerSourceWithRollback(
        register = { NotificationObservationBridge.install(this, ::posted) { fail("NOTIFICATION_LISTENER_DISCONNECTED") } },
        rollback = {}, // install validates all preconditions before acquiring the source.
    )
    override suspend fun onSourceAdmitted() { NotificationObservationBridge.requireReady(this) }
    override suspend fun unregisterSource(): SourceTeardownResult {
        NotificationObservationBridge.uninstall(this)
        return SourceTeardownResult.Released
    }
    private fun posted(packageName: String, key: String, postTime: Long) {
        capture {
            EventDraft(
                EventTypeKey(EventSourceId(NotificationEventsV1ProfileConfiguration.SOURCE_ID), 1, "NOTIFICATION_POSTED"),
                context.clocks.now(),
                mapOf(
                    "package_name" to packageName,
                    "notification_token" to context.tokenEncoder.encode("notification-events.key.v1", key),
                    "post_time_epoch_millis" to postTime.toString(),
                ),
            )
        }
    }
}
