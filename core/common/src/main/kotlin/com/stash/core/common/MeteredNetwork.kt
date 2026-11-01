package com.stash.core.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the active network is metered: mobile data, or a Wi-Fi network the system or the user
 * marked metered (another phone's hotspot). Stash's Data saver keys on this rather than on the
 * transport, because a phone on a hotspot is on Wi-Fi and still spending someone's data plan.
 * The one definition: streaming (`ConnectivityMonitor.isMetered`), pictures ([DataSaverArtCap]) and
 * everything else (`DataSaver` in :core:data) all read it.
 *
 * With no active network (a tunnel, a lift, a hand-over between cells) it answers what it last saw,
 * so a dropout on mobile data neither reads as "Wi-Fi" (a FLAC link would be asked for the moment the
 * signal is back) nor flips every Data saver decision twice. Before anything was seen: not metered.
 * A VPN reports its underlying network's meteredness (Android 10+), so a VPN over mobile data is metered.
 */
fun isActiveNetworkMetered(context: Context): Boolean {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: return MeteredNetwork.lastKnown ?: false
    val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
    return MeteredNetwork.read(caps?.let(MeteredNetwork::isMetered))
}

/** The last metered answer seen, for [isActiveNetworkMetered] and the network callback that feeds it. */
object MeteredNetwork {
    /** Null until the first network is seen. Process-wide: one device, one active network. */
    @Volatile
    var lastKnown: Boolean? = null
        private set

    /** Whether [caps] is metered (no `NOT_METERED` capability). */
    fun isMetered(caps: NetworkCapabilities): Boolean =
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    /**
     * [current] (null: no active network) remembered and returned; with none, the last answer seen,
     * or not metered before any. A network callback reports through here too, so both agree.
     */
    fun read(current: Boolean?): Boolean {
        if (current != null) {
            lastKnown = current
            return current
        }
        return lastKnown ?: false
    }
}
