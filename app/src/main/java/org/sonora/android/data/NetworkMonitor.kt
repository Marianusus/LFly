package org.sonora.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device currently has a validated internet connection, plus whether
 * that connection is Wi-Fi/Ethernet. Used to gate downloads and to reveal the offline
 * library section only when the app really cannot reach the network.
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    @Volatile
    private var connectivityManager: ConnectivityManager? = null

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isWifiOrEthernet = MutableStateFlow(false)
    val isWifiOrEthernet: StateFlow<Boolean> = _isWifiOrEthernet.asStateFlow()

    fun init(context: Context) {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
        connectivityManager = manager ?: return
        refresh()
        Log.i(TAG, "Monitoring started (online=${_isOnline.value}, wifi=${_isWifiOrEthernet.value})")

        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) = refresh()
            })
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register network callback: ${e.message}")
        }
    }

    /** True when downloading is allowed under the current network policy. */
    fun canDownload(wifiOnly: Boolean): Boolean =
        _isOnline.value && (!wifiOnly || _isWifiOrEthernet.value)

    private fun refresh() {
        val manager = connectivityManager ?: return
        val capabilities = try {
            val network = manager.activeNetwork ?: return offline()
            manager.getNetworkCapabilities(network)
        } catch (e: Exception) {
            Log.w(TAG, "Network capabilities unavailable: ${e.message}")
            null
        }

        val online = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Log.i(TAG, "Network changed: online=$online")
        _isOnline.value = online
        _isWifiOrEthernet.value = capabilities?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } ?: false
    }

    private fun offline() {
        _isOnline.value = false
        _isWifiOrEthernet.value = false
    }
}
