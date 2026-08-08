package com.stash.core.media.streaming

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
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
 *
 * **amz routing.** amz-origin http(s) items get a third branch: a progressive
 * [androidx.media3.datasource.okhttp.OkHttpDataSource] built on the shared,
 * interceptor-bearing [okhttp3.OkHttpClient]. amz playback is gated by an
 * `x-captcha-token` header (added by `AmzCaptchaInterceptor`); routing through
 * OkHttp makes that header ride every request — initial and each Range/seek —
 * and a stale-token response is transparently re-minted + retried by the
 * interceptor. No [RefreshingDataSource] (the auth is a header, not a URL) and
 * no disk cache (large FLAC, same as how lossless already streams). The amz
 * predicate [isAmzOrigin] is likewise owned by the service.
 */
@OptIn(UnstableApi::class)
class StashMediaSourceFactory(
    context: Context,
    private val streamingFactory: StreamingMediaSourceFactory,
    private val streamingTrackId: (MediaItem) -> Long?,
    private val isAmzOrigin: (MediaItem) -> Boolean,
    private val isJioSaavnOrigin: (MediaItem) -> Boolean,
    amzHttpClient: okhttp3.OkHttpClient,
    resolver: StreamSourceRegistry,
    urlCache: StreamUrlCache,
    trackDao: TrackDao,
) : MediaSource.Factory {

    private val localFactory = DefaultMediaSourceFactory(context)

    // amz: progressive HTTP via OkHttpDataSource on the interceptor-bearing
    // shared client, so the x-captcha-token header rides every (range) request
    // and a mid-stream stale-token response is transparently re-minted + retried
    // by AmzCaptchaInterceptor. No RefreshingDataSource (amz auth is a header,
    // not a URL — URL-refresh would be wrong), no disk cache (large FLAC, matches
    // how lossless Kennyy/Squid already streams).
    private val amzFactory = DefaultMediaSourceFactory(
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(amzHttpClient),
    )
    internal val jioSaavnHttpClient = amzHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jioSaavnFactory = DefaultMediaSourceFactory(
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(jioSaavnHttpClient),
    )

    // Full-timeline placeholders: stash-resolve://track/<id> items resolve
    // their URL inside LazyResolvingDataSource.open() on the loader thread.
    // Cold-jump path only — the next-up prefetch upgrades the common case to
    // a real URL in place, which then routes through the branches above.
    private val lazyFactory = DefaultMediaSourceFactory(
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
                amzDelegate = {
                    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(amzHttpClient)
                        .createDataSource()
                },
                jioSaavnDelegate = {
                    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(jioSaavnHttpClient)
                        .createDataSource()
                },
            )
        },
    )

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        localFactory.setDrmSessionManagerProvider(provider)
        amzFactory.setDrmSessionManagerProvider(provider)
        jioSaavnFactory.setDrmSessionManagerProvider(provider)
        lazyFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        localFactory.setLoadErrorHandlingPolicy(policy)
        amzFactory.setLoadErrorHandlingPolicy(policy)
        jioSaavnFactory.setLoadErrorHandlingPolicy(policy)
        lazyFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = localFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        // Placeholder items (full-timeline queue) resolve in the DataSource
        // layer — checked first because they carry no http(s) scheme for the
        // predicates below to match.
        if (mediaItem.localConfiguration?.uri?.scheme == STASH_RESOLVE_SCHEME) {
            return lazyFactory.createMediaSource(mediaItem)
        }
        // Order matches existing precedence: YouTube-trackId refresh chain first,
        // then amz authed-HTTP, then local/default. youtube and amz origins are
        // mutually exclusive, so order between them is moot.
        streamingTrackId(mediaItem)?.let { trackId ->
            return streamingFactory.create(trackId).createMediaSource(mediaItem)
        }
        if (isAmzOrigin(mediaItem)) {
            return amzFactory.createMediaSource(mediaItem)
        }
        if (isJioSaavnOrigin(mediaItem)) {
            return jioSaavnFactory.createMediaSource(mediaItem)
        }
        return localFactory.createMediaSource(mediaItem)
    }
}
