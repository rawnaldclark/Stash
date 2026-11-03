package com.stash.data.lyrics.source

interface LyricsSource {
    val id: String
    val displayName: String

    /**
     * True for a source asked only for a track someone is playing or just downloaded
     * ([LyricsQuery.onDemand]), never by a library-wide pass, and only after every other source
     * (see `LyricsRepository`). Its failures count as misses: it is an extra, never the answer
     * the other sources depend on.
     */
    val onDemandOnly: Boolean get() = false

    suspend fun resolve(query: LyricsQuery): LyricsResult?
}

data class LyricsQuery(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val youtubeVideoId: String?,
    /** The track's own ISRC, when known. Read by ISRC-keyed sources; never written back from here. */
    val isrc: String? = null,
    /**
     * True when this fetch is blocking something the user is actively watching (the lyrics sheet's
     * on-open fetch, a Retry tap). Sources MAY trade patience for latency on this — a shorter
     * timeout, no retry — so an unreachable host fails fast instead of stalling the UI. Ignored by
     * sources with nothing to trade off.
     */
    val interactive: Boolean = false,
    /**
     * True for a lookup for a track being played (Now Playing, its prefetch) or just downloaded
     * (the post-download fetch). Only these consult [LyricsSource.onDemandOnly] sources; a
     * library-wide pass leaves it false so it never fans out to them.
     */
    val onDemand: Boolean = false,

    /** Spotify track id (22 chars), when known. Read by [SpicyLyricsSource]; null skips that source. */
    val spotifyTrackId: String? = null,
)

data class LyricsResult(
    val sourceId: String,
    val plainText: String?,
    val syncedLrc: String?,
    val instrumental: Boolean,
    val language: String?,
    val sourceLyricsId: String?,
    /** Raw word-synced TTML when the source has one; null for LRC/plain-only sources. */
    val ttml: String? = null,
    val credits: LyricsCredits? = null,
)

/** Who made a lyrics sync; only Spicy Lyrics' own catalogue has these. */
data class LyricsCredits(
    val uploaderName: String?, val uploaderUrl: String?,
    val makerName: String?, val makerUrl: String?,
)
