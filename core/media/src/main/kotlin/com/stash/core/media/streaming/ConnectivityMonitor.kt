package com.stash.core.media.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [ConnectivityManager] for the streaming engine's
 * routing decisions in [com.stash.core.media.PlayerRepositoryImpl].
 *
 * Two questions the routing layer asks at every track tap that has to stream:
 *
 *  - **[isConnected]** — is there any network that actually reaches the
 *    public internet? Distinguishes "Wi-Fi captive-portal landing page"
 *    (associated, no validated internet) from a real connection. Driven
 *    by [NetworkCapabilities.NET_CAPABILITY_VALIDATED], which is the
 *    framework's "captive-portal probe passed" signal.
 *  - **[isCellular]** — is the active path a mobile-data transport?
 *    Used together with [com.stash.core.data.prefs.StreamingPreference
 *    .streamOnCellular] to decide whether to refuse a stream rather than
 *    silently burn the user's data plan.
 *
 * Both methods return `false` if there's no active network at all
 * (airplane mode, all transports down) — the routing layer surfaces
 * that as the `NoConnectivity` snackbar reason. Synchronous on purpose:
 * the routing decision tree runs on the IO dispatcher during a
 * suspending tap handler, and a single `getActiveNetwork()` /
 * `getNetworkCapabilities()` call is cheap enough that adding a Flow
 * abstraction would be over-engineering for v1.
 *
 * No `NetworkCallback` subscription here — connectivity state is only
 * sampled at decision time. Future Task: register a callback so the
 * Now Playing screen can surface "lost connection while streaming"
 * before ExoPlayer's read fails.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * True iff the active network has both internet capability and the
     * captive-portal probe has validated a real path to the internet.
     */
    fun isConnected(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * True iff the active network uses the cellular transport. False when
     * the device is on Wi-Fi, Ethernet, or has no active network at all.
     */
    fun isCellular(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * True iff the active network is metered: mobile data, or a Wi-Fi hotspot marked metered.
     * What Data saver keys on ([StreamQualityPolicy]); the transport ([isCellular]) would read a
     * phone tethered to another phone's hotspot as Wi-Fi. False with no active network, like the
     * two above (see [com.stash.core.common.isActiveNetworkMetered]).
     */
    fun isMetered(): Boolean = com.stash.core.common.isActiveNetworkMetered(context)
}
