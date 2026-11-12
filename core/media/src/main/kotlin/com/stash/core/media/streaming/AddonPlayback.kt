package com.stash.core.media.streaming

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.stash.core.addons.HttpAddonClient

/**
 * How the player reads a stream an add-on answered (add-ons plan phase 4 §2.6; review-882 B1): on
 * the add-on client's guarded media client ([HttpAddonClient.mediaClient]) and on nothing else.
 * One place builds that source and names such a stream, so the playback service and the wiring
 * test (`AddonPlaybackWiringTest`) use the same two things.
 */
@OptIn(UnstableApi::class)
object AddonPlayback {

    /**
     * The HTTP source for `addon:` streams, their DASH manifests and segments included: every
     * request and every redirect hop is held to the base rules by the media client's guard.
     */
    fun dataSourceFactory(client: HttpAddonClient): DataSource.Factory =
        OkHttpDataSource.Factory(client.mediaClient).setUserAgent(client.mediaUserAgent)

    /** A stream origin ([StreamUrl.origin], the media item's origin extra) an add-on answered. */
    fun isAddonOrigin(origin: String?): Boolean = origin?.startsWith(AddonStreamRung.ORIGIN_PREFIX) == true
}
