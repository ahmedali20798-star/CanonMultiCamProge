package com.canon.multicam.protocol.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

object CameraNetworkBinder {
    @Volatile
    private var connectivityManager: ConnectivityManager? = null

    @Volatile
    private var activeWifiNetwork: Network? = null

    private var callbackRegistered = false

    fun initialize(context: Context) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        activeWifiNetwork = findCurrentWifiNetwork(cm)

        if (!callbackRegistered) {
            callbackRegistered = true
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching {
                cm.registerNetworkCallback(
                    request,
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            activeWifiNetwork = network
                        }

                        override fun onLost(network: Network) {
                            if (activeWifiNetwork == network) {
                                activeWifiNetwork = findCurrentWifiNetwork(cm)
                            }
                        }
                    }
                )
            }
        }
    }

    fun getWifiNetwork(): Network? {
        val cached = activeWifiNetwork
        if (cached != null) return cached
        val cm = connectivityManager ?: return null
        return findCurrentWifiNetwork(cm).also { activeWifiNetwork = it }
    }

    fun bindSocket(socket: Socket) {
        runCatching { getWifiNetwork()?.bindSocket(socket) }
    }

    fun bindDatagramSocket(socket: DatagramSocket) {
        runCatching { getWifiNetwork()?.bindSocket(socket) }
    }

    fun openWifiBoundConnection(url: URL): HttpURLConnection {
        val wifiNet = getWifiNetwork()
        val rawConn = if (wifiNet != null) {
            runCatching { wifiNet.openConnection(url) }.getOrElse { url.openConnection() }
        } else {
            url.openConnection()
        }
        return rawConn as HttpURLConnection
    }

    @Suppress("DEPRECATION")
    private fun findCurrentWifiNetwork(cm: ConnectivityManager): Network? {
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return net
            }
        }
        return null
    }
}
