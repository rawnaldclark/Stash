package com.stash.core.common

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock

/**
 * The largest cover variant `ArtSizeInterceptor` (Coil) and `SizedArtBitmapLoader` (media3)
 * may pick right now: [ArtUrlSizer.DATA_SAVER_MAX_PX]
 * while Android's Data Saver restricts this app on a metered network, else no cap.
 *
 * Covers are fetched once and disk-cached, and they are small next to the audio, so the bigger
 * variants are kept on cellular. But a user who turned Data Saver on has asked apps to spend less,
 * and Qobuz's only size above 600 (`_max`) measured up to 2.2 MB.
 *
 * Read on every image request, so the answer is cached for [TTL_MS]: two binder calls per image
 * would add up while a list scrolls, and Data Saver or the network changing a second late only
 * means one cover fetched at the other size.
 */
class DataSaverArtCap(context: Context) : () -> Int {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile private var cached = ArtUrlSizer.NO_CAP
    @Volatile private var readAt = Long.MIN_VALUE

    override fun invoke(): Int {
        val now = SystemClock.elapsedRealtime()
        if (readAt != Long.MIN_VALUE && now - readAt < TTL_MS) return cached
        cached = read()
        readAt = now
        return cached
    }

    private fun read(): Int {
        val cm = connectivity ?: return ArtUrlSizer.NO_CAP
        return runCatching {
            ArtUrlSizer.maxVariantPx(
                dataSaverRestricting =
                    cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                metered = cm.isActiveNetworkMetered,
            )
        }.getOrDefault(ArtUrlSizer.NO_CAP)
    }

    private companion object {
        const val TTL_MS = 5_000L
    }
}
