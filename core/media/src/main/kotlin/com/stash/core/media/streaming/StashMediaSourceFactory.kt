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
    /** "Play my cloud copies first": told when a cloud copy won't open. Null in tests. */
    private val cloudCopies: CloudCopyFinder? = null,
    /**
     * False in Download mode. Then only a file on the phone opens: every stream (a
     * placeholder's resolve, an http(s) URL, a cloud file) fails to open and is skipped,
     * whichever path queued it. The player's own gates keep streams out of the queue;
     * this is the last line behind them.
     */
    private val streamAllowed: () -> Boolean = { true },
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
                // "Play my cloud copies first": a stream answered by the user's own cloud file.
                cloudDelegate = cloudDataSourceFactory?.let { cloud -> { cloud.createDataSource() } },
                cloudCopies = cloudCopies,
                streamAllowed = streamAllowed,
            )
        }
    private val lazyFactory = DefaultMediaSourceFactory(lazyDataSourceFactory)

    /** What a stream opens as in Download mode: an error, so the player skips it. */
    private val refusedDataSourceFactory = DataSource.Factory { DownloadModeRefusal() }
    private val refusedFactory = DefaultMediaSourceFactory(refusedDataSourceFactory)

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
        refusedFactory.setDrmSessionManagerProvider(provider)
        jioSaavnFactory.setDrmSessionManagerProvider(provider)
        lazyFactory.setDrmSessionManagerProvider(provider)
        cloudFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        localFactory.setLoadErrorHandlingPolicy(policy)
        refusedFactory.setLoadErrorHandlingPolicy(policy)
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
        if (mediaItem.localConfiguration?.uri?.isRefusedStream() == true) return refusedDataSourceFactory
        if (mediaItem.localConfiguration?.uri?.scheme == STASH_RESOLVE_SCHEME) return lazyDataSourceFactory
        if (mediaItem.localConfiguration?.uri?.scheme == com.stash.core.model.STASH_CLOUD_SCHEME) {
            return cloudUncachedDataSourceFactory ?: cloudSourceFactory
        }
        streamingTrackId(mediaItem)?.let { return streamingFactory.createUncachedDataSourceFactory(it) }
        if (isJioSaavnOrigin(mediaItem)) return jioSaavnDataSourceFactory
        return localDataSourceFactory
    }

    /**
     * True for a stream in Download mode. A placeholder isn't refused here: it may be a
     * downloaded song queued before its file landed, and [LazyResolvingDataSource] refuses
     * only the resolve.
     */
    private fun android.net.Uri.isRefusedStream(): Boolean {
        if (streamAllowed()) return false
        return when (scheme?.lowercase()) {
            null, "file", "content", "android.resource", STASH_RESOLVE_SCHEME -> false
            else -> true
        }
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        // Placeholder items (full-timeline queue) resolve in the DataSource
        // layer — checked first because they carry no http(s) scheme for the
        // predicates below to match.
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.isRefusedStream() == true) return refusedFactory.createMediaSource(mediaItem)
        // The user's own cloud file: its own resolver and cache, never the online chain below.
        if (uri?.scheme == com.stash.core.model.STASH_CLOUD_SCHEME) return cloudFactory.createMediaSource(mediaItem)
        if (uri?.scheme == STASH_RESOLVE_SCHEME) {
            val trackId = uri.lastPathSegment?.toLongOrNull()
            if (trackId != null && !isRowOverride(uri) && streamAllowed() && resolveBeforeSourcePick()) {
                return ResolveFirstMediaSource(
                    mediaItem = mediaItem,
                    scope = resolveScope,
                    resolve = {
                        urlCache.get(trackId) ?: placeholderEntity(trackDao, trackId, uri)
                            ?.let { resolver.resolve(it, allowYouTube = true, allowYtDlp = true) }
                            ?.also { urlCache.put(trackId, it) }
                    },
                    childFor = { item, resolved ->
                        if (resolved?.isDash == true) {
                            streamingFactory.createDashMediaSource(item, resolved.url)
                        } else {
                            lazyFactory.createMediaSource(item)
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

/** A stream's source in Download mode: it never opens, so the player skips the song. */
@OptIn(UnstableApi::class)
private class DownloadModeRefusal : DataSource {
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long =
        throw java.io.IOException("Download mode: ${dataSpec.uri.scheme} streams don't play")
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw java.io.IOException("not open")
    override fun getUri(): android.net.Uri? = null
    override fun close() = Unit
}
