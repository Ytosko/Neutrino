package dev.ytosko.neutrino.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone can reach the internet right now, updated live. "Connected to Wi-Fi" isn't
 * enough: Android must have checked that the network really reaches the internet (a café Wi-Fi
 * without internet counts as offline). Photos, voice and new-food estimates need it; the rest of
 * Neutrino works offline.
 */
class NetworkMonitor(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(current())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _online.value = capabilities.reachesInternet()
                }

                override fun onLost(network: Network) {
                    _online.value = current()
                }
            })
        }
    }

    private fun current(): Boolean {
        val manager = connectivity ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.reachesInternet()
    }

    private fun NetworkCapabilities.reachesInternet() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
