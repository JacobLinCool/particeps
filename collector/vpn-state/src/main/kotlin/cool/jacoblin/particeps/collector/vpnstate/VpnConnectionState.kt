package cool.jacoblin.particeps.collector.vpnstate

/** Callback evidence is required: an empty initial set does not prove that no VPN exists. */
internal class VpnConnectionState<NetworkKey> {
    private val networks = mutableSetOf<NetworkKey>()
    var connected: Boolean? = null
        private set

    fun available(network: NetworkKey): Boolean {
        networks.add(network)
        connected = true
        return true
    }

    fun lost(network: NetworkKey): Boolean {
        networks.remove(network)
        return networks.isNotEmpty().also { connected = it }
    }

    fun clear() {
        networks.clear()
        connected = null
    }
}
