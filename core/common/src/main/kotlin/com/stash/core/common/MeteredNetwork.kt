package com.stash.core.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the active network is metered: mobile data, or a Wi-Fi network the system or the user
 * marked metered (another phone's hotspot). Stash's Data saver keys on this rather than on the
 * transport, because a phone on a hotspot is on Wi-Fi and still spending someone's data plan.
 *
 * False with no active network at all, and when the system can't say. Unlike
 * `ConnectivityManager.isActiveNetworkMetered` (true with no network), so a moment offline does not
 * read as "on mobile data" and flip every Data saver decision twice. A VPN reports its underlying
 * network's meteredness (Android 10+), so a VPN over mobile data is metered.
 */
fun isActiveNetworkMetered(context: Context): Boolean {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
    return runCatching {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}
