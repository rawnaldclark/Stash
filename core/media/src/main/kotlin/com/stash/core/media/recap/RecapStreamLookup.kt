package com.stash.core.media.recap

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toEntity
import com.stash.core.media.streaming.JioSaavnStreamResolver
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.YouTubeStreamResolver
import com.stash.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds a stream for a Recap story card's song WITHOUT the main player's source chain.
 *
 * A story flicks through a song every few seconds, so a preview must cost almost nothing:
 *  1. **The main player's cached URL** for the song ([StreamUrlCache.get]): free, and it is the
 *     version the user actually hears (often the FLAC).
 *  2. Otherwise **YouTube's InnerTube fast lane** ([YouTubeStreamResolver.resolve] with
 *     `allowYtDlp = false`): one cheap request, its URL tail-probed so a PO-token-gated one that
 *     would stop after ~1 MB is rejected. No yt-dlp process.
 *  3. Nothing → the card stays quiet.
 *
 * What it never does, on purpose:
 *  - **Never asks the registry** (StreamSourceRegistry): no qbdlx / Tidal relay request, so a
 *    preview never spends the shared daily lossless allowance, never runs JioSaavn or the hedged
 *    lanes, and a Qobuz miss never counts toward the Home "lossless offline" banner.
 *  - **Never writes [StreamUrlCache]**: the cache is keyed by track id and the main player trusts
 *    it, so a YouTube URL put there would make the user's next real tap of that song play
 *    YouTube instead of FLAC.
 *
 * Called from the card's switch job: swiping to the next card cancels the caller. (The extractor
 * shares one in-flight InnerTube request per video id, which may finish in the background; it is
 * one short request and its result is dropped.)
 */
@Singleton
class RecapStreamLookup internal constructor(
    private val cached: (Long) -> StreamUrl?,
    private val youTubeFastLane: suspend (TrackEntity) -> StreamUrl?,
) {
    @Inject
    constructor(streamUrlCache: StreamUrlCache, youTubeStreamResolver: YouTubeStreamResolver) : this(
        cached = streamUrlCache::get,
        youTubeFastLane = { entity -> youTubeStreamResolver.resolve(entity, allowYtDlp = false) },
    )

    /** A playable URL for [track], or null (the card stays quiet). */
    suspend fun lookup(track: Track): RecapSource.Url? {
        cached(track.id)?.let { hit ->
            return RecapSource.Url(
                url = hit.url,
                isDash = hit.isDash,
                noRedirects = hit.origin == JioSaavnStreamResolver.ORIGIN,
            )
        }
        val yt = youTubeFastLane(track.toEntity()) ?: return null
        return RecapSource.Url(url = yt.url)
    }
}
