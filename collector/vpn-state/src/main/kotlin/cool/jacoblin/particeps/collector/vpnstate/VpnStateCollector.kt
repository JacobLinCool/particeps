package cool.jacoblin.particeps.collector.vpnstate

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.SerializedCallbackCollector
import cool.jacoblin.particeps.core.collector.SourceCallbackBoundary
import cool.jacoblin.particeps.core.collector.SourceRegistrationResult
import cool.jacoblin.particeps.core.collector.SourceTeardownResult
import cool.jacoblin.particeps.core.collector.completeSourceTeardown
import cool.jacoblin.particeps.core.collector.registerSourceWithRollback
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.VpnStateV1ProfileConfiguration
import cool.jacoblin.particeps.core.model.EventDraft
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey

class VpnStateCollectorPlugin(context: Context) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = VpnStateV1ProfileConfiguration.SOURCE_ID,
        displayName = "VPN connection state",
        accessKinds = emptySet(),
        sourceContract = requireNotNull(ProtocolEventSourceRegistry[VpnStateV1ProfileConfiguration.SOURCE_ID]),
    )

    override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector {
        require(configuration is VpnStateV1ProfileConfiguration) { "Invalid VPN-state configuration" }
        return VpnStateCollector(applicationContext, context)
    }
}

/** Observes VPN networks independently of which network is the research application's default. */
private class VpnStateCollector(
    androidContext: Context,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY) {
    private val connectivityManager = androidContext.getSystemService(ConnectivityManager::class.java)
    private val callbackBoundary = SourceCallbackBoundary()
    private val state = VpnConnectionState<Network>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            callbackBoundary.runIfActive {
                synchronized(state) { captureState(state.available(network)) }
            }
        }

        override fun onLost(network: Network) {
            callbackBoundary.runIfActive {
                synchronized(state) { captureState(state.lost(network)) }
            }
        }
    }

    override suspend fun registerSource(): SourceRegistrationResult {
        callbackBoundary.activate()
        var registered = false
        return registerSourceWithRollback(
            register = {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .setIncludeOtherUidNetworks(true)
                        .build(),
                    callback,
                )
                registered = true
            },
            rollback = {
                completeSourceTeardown(
                    { if (registered) connectivityManager.unregisterNetworkCallback(callback) },
                    { callbackBoundary.deactivate { synchronized(state) { state.clear() } } },
                )
            },
        )
    }

    override suspend fun onSourceAdmitted() {
        synchronized(state) { captureState(state.connected) }
    }

    override suspend fun unregisterSource(): SourceTeardownResult {
        completeSourceTeardown(
            { connectivityManager.unregisterNetworkCallback(callback) },
            { callbackBoundary.deactivate { synchronized(state) { state.clear() } } },
        )
        return SourceTeardownResult.Released
    }

    private fun captureState(connected: Boolean?) {
        capture {
            EventDraft(
                type = EventTypeKey(EventSourceId(VpnStateV1ProfileConfiguration.SOURCE_ID), 1, "VPN_STATUS"),
                observedTime = context.clocks.now(),
                fields = connected?.let { mapOf("connected" to it.toString()) } ?: emptyMap(),
            )
        }
    }

    private companion object {
        const val CHANNEL_CAPACITY = 256
    }
}
