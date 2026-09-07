package cool.jacoblin.particeps.collector.notificationevents

/** Holds no notifications: callbacks are admitted synchronously by the active collector. */
internal object NotificationObservationBridge {
    private var connected = false
    private var owner: Any? = null
    private var observation: ((String, String, Long) -> Unit)? = null
    private var failure: (() -> Unit)? = null

    @Synchronized fun connected() { connected = true }
    @Synchronized fun disconnected() {
        connected = false
        observation = null
        failure?.invoke()
    }
    @Synchronized fun install(owner: Any, observation: (String, String, Long) -> Unit, failure: () -> Unit) {
        check(this.owner == null) { "Notification source already owned" }
        check(connected) { "Notification listener is not connected" }
        this.owner = owner
        this.observation = observation
        this.failure = failure
    }
    @Synchronized fun requireReady(owner: Any) {
        check(connected && observation != null && this.owner === owner) { "Notification listener is not ready" }
    }
    @Synchronized fun uninstall(owner: Any) {
        check(this.owner === owner) { "Notification source owner mismatch" }
        this.owner = null
        observation = null
        failure = null
    }
    @Synchronized fun posted(packageName: String, key: String, postTime: Long) {
        if (connected) observation?.invoke(packageName, key, postTime)
    }
}
