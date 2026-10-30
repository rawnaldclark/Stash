package com.stash.core.media.streaming

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Download mode, checked each time a network source OPENS (review on #731), not only when its media source was made: a
 * source built in Stream only and opened after the switch flipped (a prefetched next song, a seek's new range, a DASH
 * segment, a cloud file) is refused too, before a byte is asked for. A file on the phone always opens.
 *
 * [alwaysNetwork]: the source only ever reads the network (a cloud file's `stash-cloud://` item), whatever its URI says.
 */
@OptIn(UnstableApi::class)
class DownloadModeGate(
    private val upstream: DataSource,
    private val streamAllowed: () -> Boolean,
    private val alwaysNetwork: Boolean = false,
) : DataSource by upstream {

    override fun open(dataSpec: DataSpec): Long {
        if (!streamAllowed() && (alwaysNetwork || isNetwork(dataSpec.uri))) {
            throw IOException("Download mode: ${dataSpec.uri.scheme} streams don't play")
        }
        return upstream.open(dataSpec)
    }

    companion object {
        private val LOCAL_SCHEMES = setOf("file", "content", "android.resource", "asset", "data", "rawresource")

        /** A URI that reads the network: anything but a file on the phone (and the lazy placeholder, which decides itself). */
        fun isNetwork(uri: Uri?): Boolean {
            val scheme = uri?.scheme?.lowercase() ?: return false
            return scheme !in LOCAL_SCHEMES && scheme != STASH_RESOLVE_SCHEME
        }

        /** [factory] with every source it makes gated by [streamAllowed]. */
        fun gate(factory: DataSource.Factory, streamAllowed: () -> Boolean, alwaysNetwork: Boolean = false): DataSource.Factory =
            DataSource.Factory { DownloadModeGate(factory.createDataSource(), streamAllowed, alwaysNetwork) }
    }
}
