package com.stash.core.media.streaming

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.model.CloudRef
import com.stash.core.model.STASH_CLOUD_SCHEME
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.cloud.provider.CloudLink
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * The player's address for a cloud song: its `stash-cloud://` URI, or an address nothing can
 * open when the row somehow lacks one, so the item fails and is skipped. A cloud song must never
 * fall back to a `stash-resolve://` placeholder, which would look up an online match by its words.
 */
fun cloudPlaybackUri(cloudUri: String?): Uri =
    Uri.parse(cloudUri?.takeIf { CloudRef.parse(it) != null } ?: "$STASH_CLOUD_SCHEME://missing")

/**
 * Plays a `stash-cloud://<provider>/<account>/<item>?v=<version>` item straight from the
 * user's own cloud storage (spec 2026-10-19-cloud-storage-design.md §5).
 *
 * At open() the item's short-lived link (OneDrive `downloadUrl`, Dropbox temporary link) comes
 * from [CloudLinkResolver] (cached per file, so a seek doesn't cost an API call), and the bytes
 * are read from it with HTTP Range and no Authorization header. A link the content host refuses
 * (401/403/404/410: expired or revoked) is resolved again and the open retried once at the same
 * position. When the host sends no Content-Length, the length comes from the file size in the
 * provider's metadata, or seeking (and Android Auto's scrubber) breaks.
 *
 * Runs on ExoPlayer's loader thread; the one blocking resolve per open is the same trade
 * [LazyResolvingDataSource] makes.
 */
/** The cloud file changed since the library last scanned it (see [CloudResolvingDataSource]). */
class CloudFileChangedException : IOException("the cloud file changed since the last scan")

@OptIn(UnstableApi::class)
class CloudResolvingDataSource(
    private val resolver: CloudLinkResolver,
    private val httpFactory: () -> HttpDataSource,
) : DataSource {

    private var delegate: DataSource? = null
    private var openedUri: Uri? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val ref = CloudRef.parse(dataSpec.uri.toString()) ?: throw IOException("not a cloud song: ${dataSpec.uri.scheme}")
        val link = runBlocking { resolver.resolve(ref, RequestPriority.INTERACTIVE) }
        requireSameVersion(ref, link)
        return try {
            openWith(link, dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode !in REFRESH_CODES) throw e
            resolver.invalidate(ref)
            val fresh = runBlocking { resolver.resolve(ref, RequestPriority.INTERACTIVE, fresh = true) }
            requireSameVersion(ref, fresh)
            openWith(fresh, dataSpec)
        }
    }

    /**
     * The stream cache keys bytes by the version in the item's URI (`?v=`). If the file changed in
     * the cloud since the scan, the link now serves the NEW bytes, and caching them under the old
     * key would splice two files into one song. Fail instead (the player skips it); the next scan
     * gives the song its new version and it plays again.
     */
    private fun requireSameVersion(ref: CloudRef, link: CloudLink) {
        val expected = ref.version ?: return
        val actual = link.version ?: return
        if (expected != actual) {
            resolver.invalidate(ref)
            throw CloudFileChangedException()
        }
    }

    private fun openWith(link: CloudLink, spec: DataSpec): Long {
        closeQuietly()
        val http = httpFactory()
        listeners.forEach(http::addTransferListener)
        val length = http.open(spec.buildUpon().setUri(Uri.parse(link.url)).build())
        delegate = http
        openedUri = Uri.parse(link.url)
        if (length != C.LENGTH_UNSET.toLong()) return length
        if (spec.length != C.LENGTH_UNSET.toLong()) return spec.length
        return if (link.size > 0) (link.size - spec.position).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IOException("read before open")

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            openedUri = null
        }
    }

    private fun closeQuietly() = runCatching { delegate?.close() }.also { delegate = null }

    companion object {
        private val REFRESH_CODES = setOf(401, 403, 404, 410)

        /**
         * Stream-cache key: provider + item + content version (`dropbox:<id>:<rev>`,
         * `onedrive:<id>:<cTag>`), never the expiring URL, so cached bytes are found again
         * through the next link and a changed file never serves stale ones.
         */
        val CACHE_KEY_FACTORY = CacheKeyFactory { spec ->
            CloudRef.parse(spec.uri.toString())?.cacheKey ?: spec.key ?: spec.uri.toString()
        }

        /**
         * The DataSource.Factory the player uses for cloud items: the evicting stream cache over
         * [CloudResolvingDataSource]. A cache hit never resolves a link at all.
         */
        fun factory(resolver: CloudLinkResolver, cache: SimpleCache?, httpFactory: () -> HttpDataSource): DataSource.Factory {
            val upstream = DataSource.Factory { CloudResolvingDataSource(resolver, httpFactory) }
            if (cache == null) return upstream
            return CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(CACHE_KEY_FACTORY)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        /** Uncached, for Cast (the speaker's reads must not fill the phone's cache). */
        fun uncachedFactory(resolver: CloudLinkResolver, httpFactory: () -> HttpDataSource): DataSource.Factory =
            DataSource.Factory { CloudResolvingDataSource(resolver, httpFactory) }
    }
}
