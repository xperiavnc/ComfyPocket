package com.comfyui.mobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkInitialOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var onNetworkRecovered: (() -> Unit)? = null

    fun setOnNetworkRecoveredListener(listener: () -> Unit) {
        onNetworkRecovered = listener
    }

    private fun checkInitialOnline(): Boolean {
        val cm = connectivityManager ?: return true
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var wasOffline = !checkInitialOnline()

        override fun onAvailable(network: Network) {
            Log.d("NetworkMonitor", "Network available: $network")
            val previouslyOffline = !_isOnline.value || wasOffline
            _isOnline.value = true
            wasOffline = false
            if (previouslyOffline) {
                onNetworkRecovered?.invoke()
            }
        }

        override fun onLost(network: Network) {
            Log.d("NetworkMonitor", "Network lost: $network")
            wasOffline = true
            val online = checkInitialOnline()
            _isOnline.value = online
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val previouslyOffline = !_isOnline.value
            _isOnline.value = hasInternet
            if (hasInternet && previouslyOffline) {
                onNetworkRecovered?.invoke()
            }
        }
    }

    fun start() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback)
            }
        } catch (e: Exception) {
            Log.w("NetworkMonitor", "Failed to register network callback", e)
        }
    }

    fun stop() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }
}
