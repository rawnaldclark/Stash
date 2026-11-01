package com.stash.core.media.preview

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.stash.core.data.prefs.DataSaver
import com.stash.core.model.TrackItem
import com.stash.data.download.preview.PreviewUrlExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an ExoPlayer [MediaSource] for a search-result/artist-profile
 * track. Tries lossless first; falls through to the existing yt-dlp/
 * InnerTube preview-URL extractor on Qobuz miss. Both paths route
 * through the same [SimpleCache] keyed by `lossless:videoId` or
 * `ytdlp:videoId` — so a preview→download flow finalises from cache
 * without re-fetching.
 *
 * Where Data saver applies (the switch on, a metered network) neither: YouTube's
 * lowest audio only, cached as `ytdlp-low:videoId`.
 *
 * Confidence threshold for lossless acceptance is 0.65 (vs sync's 0.5)
 * because search-tab matches lack ISRC and reliable duration; we want
 * tighter title+artist agreement to avoid wrong-version downloads.
 */
@Singleton
class SearchPreviewMediaSource @Inject constructor(
    private val prefetcher: LosslessUrlPrefetcher,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewCache: SimpleCache,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val cacheKeyFactory: TrackKeyCacheKeyFactory,
    private val dataSaver: DataSaver,
) {
    suspend fun create(track: TrackItem): MediaSource {
        // Data saver on mobile data: a preview is a taste, so the low tier, as streams get it.
        // No lossless lookup (a FLAC preview is the biggest thing this could fetch) and YouTube's
        // lowest audio, under its own cache key: a key shared with the full-quality file would
        // splice two encodings' bytes in the cache.
        if (dataSaver.appliesNow()) {
            Log.d(TAG, "preview low tier ${track.videoId} (Data saver on a metered network)")
            return buildCachedSource(
                upstreamUrl = previewUrlExtractor.extractStreamUrl(track.videoId, lowestQuality = true),
                cacheKey = "ytdlp-low:${track.videoId}",
            )
        }
        val match = prefetcher.lookup(track)
        // A DASH match (Tidal) is a manifest this progressive cached source cannot play.
        return if (match != null && !match.isDash && match.confidence >= MIN_SEARCH_CONFIDENCE) {
            Log.d(
                TAG,
                "preview lossless ${track.videoId} via ${match.sourceId} " +
                    "confidence=${"%.2f".format(match.confidence)}",
            )
            buildCachedSource(
                upstreamUrl = match.downloadUrl,
                cacheKey = "lossless:${track.videoId}",
            )
        } else {
            Log.d(
                TAG,
                "preview yt-dlp ${track.videoId} " +
                    "(lossless ${if (match == null) "miss" else "confidence ${"%.2f".format(match.confidence)} < $MIN_SEARCH_CONFIDENCE"})",
            )
            val ytUrl = previewUrlExtractor.extractStreamUrl(track.videoId)
            buildCachedSource(
                upstreamUrl = ytUrl,
                cacheKey = "ytdlp:${track.videoId}",
            )
        }
    }

    private fun buildCachedSource(upstreamUrl: String, cacheKey: String): MediaSource {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(previewCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Cache key goes on MediaItem.customCacheKey (propagates to
        // DataSpec.key); URL is forwarded to upstream HTTP unchanged.
        // Earlier code appended `?trackKey=...` to the URL, which broke
        // Qobuz + YouTube signed-URL HMACs and 403'd every byte fetch —
        // the v0.9.12 launch-day "preview takes forever" symptom.
        val mediaItem = MediaItem.Builder()
            .setUri(upstreamUrl)
            .setCustomCacheKey(cacheKey)
            .build()
        // Fail-fast retry policy: ExoPlayer's default 3-retry exponential
        // backoff means a broken URL or transient outage takes 30-90s to
        // surface as an error. 1 retry routes failures to the yt-dlp
        // retry path (TrackActionsDelegate.onPreviewError) within ~10s.
        val errorPolicy = DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 1)
        return ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .setLoadErrorHandlingPolicy(errorPolicy)
            .createMediaSource(mediaItem)
    }

    companion object {
        private const val TAG = "SearchPreviewMediaSource"
        const val MIN_SEARCH_CONFIDENCE = 0.65f
    }
}
