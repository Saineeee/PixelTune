package com.saine.pixeltune.presentation.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages network connectivity state.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - WiFi state tracking (enabled, radio state, network name)
 * - Online / offline tracking (drives the offline-playback guard)
 * - System callback registration and lifecycle management
 */
@Singleton
class ConnectivityStateHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // WiFi State
    private val _isWifiEnabled = MutableStateFlow(false)
    val isWifiEnabled: StateFlow<Boolean> = _isWifiEnabled.asStateFlow()

    private val _isWifiRadioOn = MutableStateFlow(false)
    val isWifiRadioOn: StateFlow<Boolean> = _isWifiRadioOn.asStateFlow()

    private val _wifiName = MutableStateFlow<String?>(null)
    val wifiName: StateFlow<String?> = _wifiName.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // Offline Barrier Event
    // Event to signal that playback was blocked due to offline status
    // Using extraBufferCapacity to ensure the event isn't lost if no collectors are immediately suspended
    private val _offlinePlaybackBlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val offlinePlaybackBlocked: SharedFlow<Unit> = _offlinePlaybackBlocked.asSharedFlow()

    fun triggerOfflineBlockedEvent() {
        _offlinePlaybackBlocked.tryEmit(Unit)
    }

    /**
     * Manually refresh local connection info (e.g. WiFi SSID).
     */
    fun refreshLocalConnectionInfo() {
        val activeNetwork = connectivityManager.activeNetwork
        updateWifiInfo(activeNetwork)
    }

    // System services
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Callbacks and receivers
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null

    private var isInitialized = false

    /**
     * Initialize connectivity monitoring. Should be called once from ViewModel.
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        // Initial state check
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        updateWifiRadioState()
        _isWifiEnabled.value = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (_isWifiEnabled.value) {
            updateWifiInfo(activeNetwork)
        }
        
        _isOnline.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        // Register WiFi network callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            // Track all valid networks to handle rapid switching
            private val availableNetworks = mutableSetOf<Network>()

            override fun onAvailable(network: Network) {
                // Network is available, but waiting for capability check
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                if (hasInternet && isValidated) {
                    availableNetworks.add(network)
                } else {
                    availableNetworks.remove(network)
                }
                
                checkConnectivity()
                
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    _isWifiEnabled.value = true
                    updateWifiInfo(network)
                }
            }

            override fun onLost(network: Network) {
                availableNetworks.remove(network)
                checkConnectivity()
                
                val currentNetwork = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(currentNetwork)
                _isWifiEnabled.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!_isWifiEnabled.value) _wifiName.value = null
            }
            
            private fun checkConnectivity() {
                _isOnline.value = availableNetworks.isNotEmpty()
            }
        }
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)

        // Register receivers
        wifiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                     updateWifiRadioState()
                }
            }
        }
        context.registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
    }

    private fun updateWifiRadioState() {
        _isWifiRadioOn.value = wifiManager?.isWifiEnabled == true
    }

    @SuppressLint("MissingPermission")
    private fun updateWifiInfo(network: Network?) {
         if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
             val info = wifiManager?.connectionInfo
             if (info != null && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED) {
                 var ssid = info.ssid
                 if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                     ssid = ssid.substring(1, ssid.length - 1)
                 }
                 _wifiName.value = ssid
             } else {
                 _wifiName.value = null
             }
         } else {
             // Basic fallback purely on network capabilities if we don't have permission (unlikely for system app but good practice)
             _wifiName.value = "WiFi Connected" 
         }
    }
    @SuppressLint("MissingPermission")
    /**
     * Cleanup resources. Should be called from ViewModel's onCleared.
     */
    fun onCleared() {
        networkCallback?.let { 
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        wifiStateReceiver?.let { 
            runCatching { context.unregisterReceiver(it) }
        }
        isInitialized = false
    }
}
