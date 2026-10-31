package com.stash.core.media.streaming

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Refresh only a manifest. A segment's byte offset can never be applied to a playlist. */
@OptIn(UnstableApi::class)
class HlsManifestDataSource(
    private val inner: DataSource,
    private val originalManifest: String,
    private val currentManifest: AtomicReference<String>,
    private val streamAllowed: () -> Boolean,
    private val refresh: () -> String?,
    private val invalidate: () -> Unit,
) : DataSource by inner {
    override fun open(dataSpec: DataSpec): Long {
        if (!streamAllowed()) throw IOException("Download mode: streaming disabled")
        val manifest = dataSpec.uri.toString() == originalManifest || dataSpec.uri.toString() == currentManifest.get()
        val spec = if (manifest) dataSpec.withUri(Uri.parse(currentManifest.get())) else dataSpec
        try {
            return inner.open(spec).also { if (manifest) inner.uri?.let { currentManifest.set(it.toString()) } }
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode !in setOf(401, 403, 410)) throw e
            invalidate()
            // A segment failure needs a fresh media source; never substitute manifest bytes.
            if (!manifest || dataSpec.position != 0L) throw e
            inner.close()
            if (!streamAllowed()) throw IOException("Download mode: streaming disabled", e)
            val fresh = refresh() ?: throw e
            currentManifest.set(fresh)
            if (!streamAllowed()) throw IOException("Download mode: streaming disabled", e)
            return inner.open(dataSpec.withUri(Uri.parse(fresh))).also { inner.uri?.let { currentManifest.set(it.toString()) } }
        }
    }
}
