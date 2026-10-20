package com.stash.core.media.streaming

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.stash.core.data.db.dao.TrackDao

/**
 * Player-wide [MediaSource.Factory] that routes **only YouTube-origin streaming
 * items** through the [StreamingMediaSourceFactory] refresh chain
 * (CacheDataSource → [RefreshingDataSource] → HTTP), and everything else —
 * downloaded/local files AND lossless (Kennyy/Squid) streams — through the
 * plain [DefaultMediaSourceFactory], exactly as before.
 *
 * **Why scope to YouTube only.** Background queue-fill seeds the timeline with
 * cheap InnerTube/iOS placeholder URLs (deep, in-order), but those are
 * PO-token-gated to ~1 MB and return HTTP 403 on full playback. The default
 * factory has no recovery — a 403 surfaces as `onPlayerError`, and the cascade
 * guard skip-storms the whole queue. Wrapping YouTube items in
 * [RefreshingDataSource] makes that 403 transparently re-resolve via yt-dlp
 * (full-range-playable) and continue at the same byte offset — no skip, no
 * Halt. Lossless and local playback are left on their proven path so this
 * change can't regress them.
 *
 * The per-item decision is delegated to [streamingTrackId]: it returns the
 * track id when the item should use the refresh chain (YouTube http(s) stream
 * with a valid id), or null otherwise. The service owns that predicate because
 * the metadata-extra keys live there.
 */
@OptIn(UnstableApi::class)
class StashMediaSourceFactory(
    context: Context,
    private val streamingFactory: StreamingMediaSourceFactory,
    private val streamingTrackId: (MediaItem) -> Long?,
    private val isJioSaavnOrigin: (MediaItem) -> Boolean,
    httpClient: okhttp3.OkHttpClient,
    resolver: StreamSourceRegistry,
    urlCache: StreamUrlCache,
    trackDao: TrackDao,
    /**
     * True while a Tidal (BitChord) server is configured: placeholders then
     * resolve BEFORE choosing a media source ([ResolveFirstMediaSource]), since
     * Tidal may answer with a DASH MPD. False keeps the lazy path exactly as it was.
     */
    private val resolveBeforeSourcePick: () -> Boolean = { false },
    /**
     * `stash-cloud://` items (the user's own Dropbox / OneDrive files): the stream cache over
     * [CloudResolvingDataSource]. Null in tests and builds without cloud wiring; such an item
     * then fails to open (and is skipped), it is never routed to the online resolvers.
     */
    cloudDataSourceFactory: DataSource.Factory? = null,
    /** The same without the cache, for Cast. */
    private val cloudUncachedDataSourceFactory: DataSource.Factory? = null,
) : MediaSource.Factory {

    private val resolver = resolver
    private val urlCache = urlCache
    private val trackDao = trackDao

    /** Where [ResolveFirstMediaSource] resolves; outlives any one source. */
    private val resolveScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private val localDataSourceFactory = DefaultDataSource.Factory(context)
    private val localFactory = DefaultMediaSourceFactory(localDataSourceFactory)

    internal val jioSaavnHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jioSaavnDataSourceFactory =
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(jioSaavnHttpClient)
    private val jioSaavnFactory = DefaultMediaSourceFactory(jioSaavnDataSourceFactory)

    // Full-timeline placeholders: stash-resolve://track/<id> items resolve
    // their URL inside LazyResolvingDataSource.open() on the loader thread.
    // Cold-jump path only — the next-up prefetch upgrades the common case to
    // a real URL in place, which then routes through the branches above.
    private val lazyDataSourceFactory =
        DataSource.Factory {
            LazyResolvingDataSource(
                resolver = resolver,
                urlCache = urlCache,
                trackDao = trackDao,
                httpDelegate = {
                    DefaultHttpDataSource.Factory().setUserAgent("Stash/0.9.26")
                        .setConnectTimeoutMs(10_000).setReadTimeoutMs(30_000)
                        .createDataSource()
                },
                jioSaavnDelegate = {
                    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(jioSaavnHttpClient)
                        .createDataSource()
                },
            )
        }
    private val lazyFactory = DefaultMediaSourceFactory(lazyDataSourceFactory)

    private val cloudSourceFactory: DataSource.Factory =
        cloudDataSourceFactory ?: DataSource.Factory { throw java.io.IOException("cloud playback isn't wired") }
    // A blocked or changed cloud file fails at once instead of being retried (CloudLoadErrorPolicy).
    private val cloudFactory = DefaultMediaSourceFactory(cloudSourceFactory).apply {
        setLoadErrorHandlingPolicy(CloudLoadErrorPolicy())
    }

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        localFactory.setDrmSessionManagerProvider(provider)
        jioSaavnFactory.setDrmSessionManagerProvider(provider)
        lazyFactory.setDrmSessionManagerProvider(provider)
        cloudFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        localFactory.setLoadErrorHandlingPolicy(policy)
        jioSaavnFactory.setLoadErrorHandlingPolicy(policy)
        lazyFactory.setLoadErrorHandlingPolicy(policy)
        cloudFactory.setLoadErrorHandlingPolicy(CloudLoadErrorPolicy(policy))
        return this
    }

    override fun getSupportedTypes(): IntArray = localFactory.supportedTypes

    /**
     * The byte source [createMediaSource] would play [mediaItem] from, with the
     * same routing. The cast media server reads through it, so a speaker gets
     * the lazy resolve, the YouTube 403 refresh and JioSaavn's no-redirect
     * client exactly as local playback does (spec 2026-10-06 §4) — minus the
     * stream cache, see [StreamingMediaSourceFactory.createUncachedDataSourceFactory].
     */
    fun dataSourceFactoryFor(mediaItem: MediaItem): DataSource.Factory {
        if (mediaItem.localConfiguration?.uri?.scheme == STASH_RESOLVE_SCHEME) return lazyDataSourceFactory
        if (mediaItem.localConfiguration?.uri?.scheme == com.stash.core.model.STASH_CLOUD_SCHEME) {
            return cloudUncachedDataSourceFactory ?: cloudSourceFactory
        }
        streamingTrackId(mediaItem)?.let { return streamingFactory.createUncachedDataSourceFactory(it) }
        if (isJioSaavnOrigin(mediaItem)) return jioSaavnDataSourceFactory
        return localDataSourceFactory
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        // Placeholder items (full-timeline queue) resolve in the DataSource
        // layer — checked first because they carry no http(s) scheme for the
        // predicates below to match.
        val uri = mediaItem.localConfiguration?.uri
        // The user's own cloud file: its own resolver and cache, never the online chain below.
        if (uri?.scheme == com.stash.core.model.STASH_CLOUD_SCHEME) return cloudFactory.createMediaSource(mediaItem)
        if (uri?.scheme == STASH_RESOLVE_SCHEME) {
            val trackId = uri.lastPathSegment?.toLongOrNull()
            if (trackId != null && !isRowOverride(uri) && resolveBeforeSourcePick()) {
                return ResolveFirstMediaSource(
                    mediaItem = mediaItem,
                    scope = resolveScope,
                    resolve = {
                        urlCache.get(trackId) ?: placeholderEntity(trackDao, trackId, uri)
                            ?.let { resolver.resolve(it, allowYouTube = true, allowYtDlp = true) }
                            ?.also { urlCache.put(trackId, it) }
                    },
                    childFor = { resolved ->
                        if (resolved?.isDash == true) {
                            streamingFactory.createDashMediaSource(mediaItem, resolved.url)
                        } else {
                            lazyFactory.createMediaSource(mediaItem)
                        }
                    },
                )
            }
            return lazyFactory.createMediaSource(mediaItem)
        }
        // A resolved Tidal MPD (prefetch / foreground tap set the item's URI to it).
        // Keyed on the path, not an extra: the session strips local configuration,
        // and upstream serves every MPD at `/dash/td/<id>.mpd`.
        if ((uri?.scheme == "https" || uri?.scheme == "http") && uri.path?.endsWith(".mpd") == true) {
            return streamingFactory.createDashMediaSource(mediaItem, uri.toString())
        }
        // Order matches existing precedence: YouTube-trackId refresh chain first,
        // then JioSaavn authed-HTTP, then local/default.
        streamingTrackId(mediaItem)?.let { trackId ->
            return streamingFactory.create(trackId).createMediaSource(mediaItem)
        }
        if (isJioSaavnOrigin(mediaItem)) {
            return jioSaavnFactory.createMediaSource(mediaItem)
        }
        return localFactory.createMediaSource(mediaItem)
    }
}
