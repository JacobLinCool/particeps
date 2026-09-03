package cool.jacoblin.particeps.actuator.trafficshaping

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Process
import kotlinx.coroutines.CompletableDeferred

internal class GenerationOwnedNetworkSet<T>(
    val generation: Long,
    private val onAllLost: () -> Unit,
) {
    private val lock = Any()
    private val owned = mutableSetOf<T>()
    private var observedOwnedNetwork = false
    private var closed = false

    fun observe(network: T, isOurs: Boolean) {
        var notifyLost = false
        synchronized(lock) {
            if (closed) return
            if (isOurs) {
                owned += network
                observedOwnedNetwork = true
            } else if (owned.remove(network) && observedOwnedNetwork && owned.isEmpty()) {
                notifyLost = true
            }
        }
        if (notifyLost) onAllLost()
    }

    fun lost(network: T) {
        var notifyLost = false
        synchronized(lock) {
            if (!closed && owned.remove(network) && observedOwnedNetwork && owned.isEmpty()) {
                notifyLost = true
            }
        }
        if (notifyLost) onAllLost()
    }

    fun hasOwnedNetwork(): Boolean = synchronized(lock) { !closed && owned.isNotEmpty() }

    fun close() {
        synchronized(lock) {
            closed = true
            owned.clear()
        }
    }
}

internal class VpnOwnershipMonitor(
    private val connectivityManager: ConnectivityManager,
    generation: Long,
    private val onAllLost: () -> Unit,
) {
    private val ownerUid = Process.myUid()
    private val networks = GenerationOwnedNetworkSet<Network>(generation, onAllLost)
    private val firstOwned = CompletableDeferred<Unit>()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onAvailable(network: Network) {
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                observe(network, capabilities)
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            observe(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            networks.lost(network)
        }
    }

    fun start() {
        check(!registered) { "VPN ownership callback already registered" }
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .setIncludeOtherUidNetworks(true)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        registered = true
    }

    suspend fun awaitOwnedNetwork() {
        if (!networks.hasOwnedNetwork()) firstOwned.await()
    }

    fun isOwnedNetworkPresent(): Boolean = networks.hasOwnedNetwork()

    fun close() {
        networks.close()
        if (registered) {
            registered = false
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    private fun observe(network: Network, capabilities: NetworkCapabilities) {
        val ours = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.ownerUid == ownerUid
        networks.observe(network, ours)
        if (ours) firstOwned.complete(Unit)
    }
}
