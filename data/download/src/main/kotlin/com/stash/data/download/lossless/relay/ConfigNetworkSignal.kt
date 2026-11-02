package com.stash.data.download.lossless.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/**
 * Whether there is a network that reaches the internet, and a way to wait for one, for [LosslessConfigFetcher]'s refresh
 * loop: offline it waits for the network instead of retrying on a timer. A seam so the loop is tested on virtual time.
 */
internal interface ConfigNetworkSignal {
    /** True when the default network has internet and Android has validated it (not a captive portal). */
    fun isOnline(): Boolean

    /**
     * Waits until [isOnline] would be true and returns true; at once when it already is. Returns false at once when the
     * network can't be watched: nothing was waited for, so the caller's failures still count.
     */
    suspend fun awaitOnline(): Boolean
}

/**
 * [ConfigNetworkSignal] over [ConnectivityManager], with the same "validated internet" rule as the player's
 * `ConnectivityMonitor` (`:core:media`, which this module can't depend on).
 *
 * [awaitOnline] registers a default-network callback only while waiting and unregisters when done. Android sends the
 * current default network's capabilities as soon as the callback is registered, so an already-online phone returns at
 * once, and a captive portal resolves when the user signs in (the network turns VALIDATED). If no callback can be
 * registered (the per-app callback limit) or there is no [ConnectivityManager], it returns false at once: the fetcher
 * then keeps its failure count, so its back-off (up to hourly) spaces the attempts out.
 */
internal class AndroidConfigNetworkSignal(private val context: Context) : ConfigNetworkSignal {

    private val cm: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val cm = cm ?: return true // can't tell: behave as before (try)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.reachesInternet()
    }

    override suspend fun awaitOnline(): Boolean {
        val cm = cm ?: return false
        return callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.reachesInternet()) trySend(true)
                }
            }
            try {
                cm.registerDefaultNetworkCallback(callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "can't watch the network; trying the config anyway (${e.javaClass.simpleName})")
                trySend(false)
            }
            awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
        }.first()
    }

    private fun NetworkCapabilities.reachesInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val TAG = "LosslessConfig"
    }
}
