package com.stash.core.media.streaming

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.stash.core.data.db.dao.TrackDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composed [MediaSource.Factory] for online-streaming playback. Builds the
 * full read pipeline ExoPlayer consumes when sourcing bytes for a streamed
 * track:
 *
 * ```
 * CacheDataSource           <- writes hits into the shared [SimpleCache]
 *  └─ RefreshingDataSource  <- catches 403/410, re-resolves via Kennyy
 *      └─ DefaultHttpDataSource  <- actual HTTP GET against the signed CDN URL
 * ```
 *
 * The cache layer is on top so a partial download survives a 403 refresh
 * mid-stream — bytes already cached before the URL went stale aren't re-
 * fetched, and the refresh only kicks in for the still-missing tail.
 *
 * **Per-track factory via singleton + method.** The static deps
 * ([streamCache], [resolver], [urlCache], [trackDao]) are Hilt-injected
 * application-scope singletons; the only per-playback variable is
 * [create]'s `trackId`. Each call to [create] returns a fresh
 * [MediaSource.Factory] that closes over that id so Media3 can spawn
 * multiple [androidx.media3.datasource.DataSource] instances for the
 * same track and they'll all share the refresh policy.
 *
 * **CacheDataSource flags:**
 * - [CacheDataSource.FLAG_BLOCK_ON_CACHE]: if two readers ask for the same
 *   byte range concurrently, the second blocks until the first writes —
 *   avoids duplicate HTTP fetches for the same range.
 * - [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]: if cache I/O throws,
 *   fall through to the network rather than surfacing the cache error as
 *   a playback failure.
 *
 * **User-Agent** uses the same hard-coded "Stash/x.y.z" pattern as
 * [com.stash.core.media.preview.di.PreviewCacheModule] — the `core/media`
 * module doesn't generate a [BuildConfig] with `VERSION_NAME`, and adding
 * one just for this header would be scope creep beyond Task 7. Bump in
 * lock-step with `app/build.gradle.kts versionName` when releasing.
 *
 * Consumed by Task 11 ([com.stash.core.media.PlayerRepository]) and
 * Task 12 ([StashPlaybackService]) — both inject this singleton and call
 * [create] inside their per-track [androidx.media3.exoplayer.source.MediaSource]
 * construction.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamingMediaSourceFactory @Inject constructor(
    @StreamCache private val streamCache: SimpleCache,
    private val resolver: StreamSourceRegistry,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
) {
    fun create(trackId: Long): MediaSource.Factory {
        val cachedFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(createUncachedDataSourceFactory(trackId))
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(streamCache),
            )
            .setFlags(
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                    CacheDataSource.FLAG_BLOCK_ON_CACHE,
            )
        return DefaultMediaSourceFactory(cachedFactory)
    }

    /**
     * The 403-refreshing HTTP pipeline under [create], without the cache. The
     * cast media server reads through this: a speaker opens a new range
     * request on every seek while its old connection may still be draining,
     * and [CacheDataSource.FLAG_BLOCK_ON_CACHE] would make the new request
     * wait on the old one's span lock (cast spec 2026-10-06 §4).
     */
    fun createUncachedDataSourceFactory(trackId: Long): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Stash/0.9.26")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
        return RefreshingDataSourceFactory(
            innerFactory = httpFactory,
            resolver = resolver,
            cache = urlCache,
            trackDao = trackDao,
            trackId = trackId,
        )
    }
}
