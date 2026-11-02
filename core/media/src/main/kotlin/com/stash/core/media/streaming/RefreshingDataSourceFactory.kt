package com.stash.core.media.streaming

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.stash.core.data.db.dao.TrackDao
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP response codes that signal a stale signed-URL and trigger a
 * re-resolve. 403 is what the Qobuz/Akamai CDN returns past `etsp`; 410
 * is the spec-compliant "gone" code some upstreams emit. Other non-2xx
 * codes (404, 5xx) propagate as fatal so the player surfaces them.
 */
private val REFRESH_TRIGGERS = setOf(403, 410)

/**
 * Per-track [DataSource] wrapper that transparently re-resolves the
 * upstream signed URL when the CDN returns 403/410 mid-stream. This is
 * the seam that makes pause-and-resume-hours-later work: if the user
 * pauses a streamed track and resumes past `etsp`, the next HTTP read
 * here catches the 403, re-resolves the same YouTube file, and re-opens at
 * the same byte offset.
 *
 * Kotlin delegation (`DataSource by inner`) forwards every member to
 * the wrapped HTTP source except [open], which we override to install
 * the refresh logic.
 *
 * `setPosition(N)` on [DataSpec.Builder] is the byte offset (used by
 * Media3 for HTTP `Range:` requests, not the time offset). The retry
 * preserves it implicitly via `spec.buildUpon().setUri(...)` so the
 * fresh URL resumes at exactly the byte we failed on.
 *
 * **Construction:** This class is NOT Hilt-injected — it's instantiated
 * per-track inside a `StreamingMediaSourceFactory` (Task 7) which IS
 * a Hilt singleton and threads through the injected `resolver` /
 * `cache` / `trackDao` here.
 *
 * **Re-entrance / runBlocking safety:** Media3 calls [open] from its
 * own loader thread (never main), so [runBlocking] is safe here. We
 * use **one** [runBlocking] for both the DAO lookup and the resolver
 * call — splitting it into two could re-enter Room's connection pool
 * or Kennyy's [com.stash.data.download.lossless.AggregatorRateLimiter]
 * and deadlock.
 */
@OptIn(UnstableApi::class)
class RefreshingDataSource(
    private val inner: HttpDataSource,
    private val resolver: StreamSourceRegistry,
    private val cache: StreamUrlCache,
    private val trackId: Long,
    private val trackDao: TrackDao,
    /**
     * The last URL a refresh produced for this item, shared by every source the
     * same [RefreshingDataSourceFactory] creates. See [open].
     */
    private val lastRefreshed: AtomicReference<String?> = AtomicReference(null),
) : DataSource by inner {

    /**
     * Opens [spec], refreshing a dead signed URL in place.
     *
     * The first attempt goes to the URL an earlier refresh produced, when there is
     * one, not to [DataSpec.uri]. The MediaItem keeps its baked URL for life, so
     * without that every later open — each uncached seek, and every HTTP request
     * the cast server makes (it builds a fresh source per request) — retried the
     * dead URL, took the 403 again and walked the whole resolver chain again: up to
     * 45 s and a qbdlx lookup per seek, long enough for a speaker to give up on
     * the load.
     *
     * A 403/410 then tries, in order: a URL already in [StreamUrlCache] for this
     * track (someone else refreshed it), then a YouTube-only re-resolve. Either is
     * used only when it is the SAME file as [DataSpec.uri] ([sameUrlFileParams]):
     * the bytes from this offset join an extractor, and a stream-cache entry keyed
     * by the baked URL, that were set up from that file. Anything else fails with
     * the original error, so the player's error path takes over rather than
     * splicing two files together.
     */
    override fun open(spec: DataSpec): Long {
        val first = lastRefreshed.get()?.let(Uri::parse) ?: spec.uri
        val dead = try {
            return inner.open(spec.withUri(first))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode !in REFRESH_TRIGGERS) throw e
            e
        }

        // A URL another open already refreshed (the cast server's previous request,
        // the next-up prefetch) costs nothing to try. Same origin and file only: the
        // cache is keyed by track id alone and may hold another source's URL.
        val cached = cache.get(trackId)?.takeIf {
            it.origin == YouTubeStreamResolver.ORIGIN &&
                it.url != first.toString() &&
                sameUrlFileParams(spec.uri.toString(), it.url)
        }
        if (cached != null) {
            try {
                return openRefreshed(spec, cached.url)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode !in REFRESH_TRIGGERS) throw e
            }
        }

        val fresh = refresh(dead) ?: throw dead
        if (!sameUrlFileParams(spec.uri.toString(), fresh.url)) {
            // Another itag or length (Data saver toggled or the network changed, another client's format
            // list): not the file the extractor and the cache key belong to.
            Log.w(TAG, "refresh for track $trackId returned a different file; failing the load")
            throw dead
        }
        cache.put(trackId, fresh)
        return openRefreshed(spec, fresh.url)
    }

    private fun openRefreshed(spec: DataSpec, url: String): Long {
        lastRefreshed.set(url)
        return inner.open(spec.withUri(Uri.parse(url)))
    }

    /**
     * Single coroutine scope for both lookups — avoids re-entrance hazards from two
     * independent runBlocking calls sharing the loader thread with Room's connection
     * pool + the aggregator rate-limiter.
     *
     * Bounded, and every failure reads as "no refresh". The loader thread is not a
     * coroutine, so a timeout escaping as a CancellationException (not an
     * IOException) became Media3's UnexpectedLoaderException, which is never
     * retried; rethrowing [dead] keeps the failure an ordinary HTTP error.
     */
    private fun refresh(dead: HttpDataSource.InvalidResponseCodeException): StreamUrl? = try {
        runBlocking {
            withTimeoutOrNull(LazyResolvingDataSource.RESOLVE_DEADLINE_MS) {
                val track = trackDao.getById(trackId) ?: return@withTimeoutOrNull null
                resolver.resolveYouTubeRefresh(track)
            }
        }
    } catch (e: Exception) {
        dead.addSuppressed(e)
        null
    }

    private fun DataSpec.withUri(uri: Uri): DataSpec =
        if (uri == this.uri) this else buildUpon().setUri(uri).build()

    private companion object {
        private const val TAG = "RefreshingDataSource"
    }
}

/**
 * Per-track factory for [RefreshingDataSource]. Constructed inside
 * `StreamingMediaSourceFactory.create(trackId)` (Task 7) which captures
 * the trackId in this factory closure so every [DataSource] that
 * Media3 spawns for the same track shares the same refresh policy.
 *
 * Holds only the captured trackId and the item's last refreshed URL — the
 * cache / resolver / DAO references come from the Hilt-injected singleton above.
 */
@OptIn(UnstableApi::class)
class RefreshingDataSourceFactory(
    private val innerFactory: HttpDataSource.Factory,
    private val resolver: StreamSourceRegistry,
    private val cache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val trackId: Long,
) : DataSource.Factory {

    /** One per item: every source for it starts from the last refreshed URL. */
    private val lastRefreshed = AtomicReference<String?>(null)

    override fun createDataSource(): DataSource = RefreshingDataSource(
        inner = innerFactory.createDataSource(),
        resolver = resolver,
        cache = cache,
        trackId = trackId,
        trackDao = trackDao,
        lastRefreshed = lastRefreshed,
    )
}
