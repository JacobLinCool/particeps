package cool.linc.androiddatacollector.collector.networkstate

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import cool.linc.androiddatacollector.core.model.EventDraft
import cool.linc.androiddatacollector.core.collector.AccessRequirement
import cool.linc.androiddatacollector.core.definition.CollectorConfiguration
import cool.linc.androiddatacollector.core.definition.NetworkStateConfiguration
import cool.linc.androiddatacollector.core.collector.PrivacyClass
import cool.linc.androiddatacollector.core.collector.Collector
import cool.linc.androiddatacollector.core.collector.CollectorContext
import cool.linc.androiddatacollector.core.collector.CollectorDescriptor
import cool.linc.androiddatacollector.core.collector.CollectorPlugin
import cool.linc.androiddatacollector.core.collector.SerializedCallbackCollector

class NetworkStateCollectorPlugin(
    context: Context,
) : CollectorPlugin {
    private val applicationContext = context.applicationContext

    override val descriptor = CollectorDescriptor(
        id = NetworkStateConfiguration.ID,
        payloadSchemaVersion = 1,
        displayName = "Network connection state",
        privacyClass = PrivacyClass.SENSITIVE,
        maximumEncodedEventBytes = 4_096,
    )

    override fun accessRequirements(configuration: CollectorConfiguration): Set<AccessRequirement> {
        require(configuration is NetworkStateConfiguration) { "Invalid network-state configuration" }
        return emptySet()
    }

    override fun create(
        configuration: CollectorConfiguration,
        context: CollectorContext,
    ): Collector = NetworkStateCollector(
        applicationContext,
        configuration as? NetworkStateConfiguration
            ?: throw IllegalArgumentException("Invalid network-state configuration"),
        context,
    )
}

private class NetworkStateCollector(
    androidContext: Context,
    private val configuration: NetworkStateConfiguration,
    collectorContext: CollectorContext,
) : SerializedCallbackCollector(collectorContext, CHANNEL_CAPACITY) {
    private val connectivityManager = androidContext.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = capture("NETWORK_AVAILABLE", emptyMap())

        override fun onLost(network: Network) = capture("NETWORK_LOST", emptyMap())

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = capture("NETWORK_CAPABILITIES", encodeCapabilities(networkCapabilities))
    }

    override suspend fun registerSource() {
        connectivityManager.registerDefaultNetworkCallback(callback)
        captureCurrentState()
    }

    override suspend fun unregisterSource() {
        connectivityManager.unregisterNetworkCallback(callback)
    }

    private fun captureCurrentState() {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        if (capabilities == null) {
            capture("NETWORK_SNAPSHOT", mapOf("connected" to "false"))
        } else {
            capture("NETWORK_SNAPSHOT", encodeCapabilities(capabilities) + ("connected" to "true"))
        }
    }

    private fun encodeCapabilities(capabilities: NetworkCapabilities): Map<String, String> = buildMap {
        put("wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI).toString())
        put("mobile", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR).toString())
        put("ethernet", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET).toString())
        put("vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN).toString())
        put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).toString())
        put("metered", (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).toString())
        put("roaming", (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)).toString())
        if (configuration.includeBandwidthEstimates) {
            put("downstream_kbps", capabilities.linkDownstreamBandwidthKbps.toString())
            put("upstream_kbps", capabilities.linkUpstreamBandwidthKbps.toString())
        }
    }

    private fun capture(
        type: String,
        fields: Map<String, String>,
    ) {
        capture {
            EventDraft(
                collectorId = NetworkStateConfiguration.ID,
                payloadSchemaVersion = 1,
                observedTime = context.clocks.now(),
                payloadType = type,
                fields = fields,
            )
        }
    }

    private companion object {
        const val CHANNEL_CAPACITY = 256
    }
}
