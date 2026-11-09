package com.stash.data.lyrics.source

interface LyricsSource {
    val id: String
    val displayName: String

    /**
     * True for a source asked only for a track someone is playing or just downloaded
     * ([LyricsQuery.onDemand]), never by a library-wide pass. It is asked in its place in the
     * chain (`LyricsModule`: Spicy Lyrics right after Apple, lrc.red and Binimum after every
     * other source), and again by the word-synced upgrades that ask the on-demand sources (see
     * `LyricsRepository`). Its failures count as misses: it is an extra, never the answer the
     * other sources depend on.
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

    /** Spotify track id (22 chars), when known. Read by [SpicyLyricsSource], which falls back to [isrc], then to ids it looks up by title/artist. */
    val spotifyTrackId: String? = null,
    /**
     * True for the word-synced upgrade passes (`LyricsRepository.upgradeToTtml`), which run after a
     * song already has lyrics, so nothing waits on them. Only there may [SpicyLyricsSource] spend a
     * request looking a song up by title/artist (iTunes Search, lrc.red's search). In the first
     * lookup it only uses an id it already has, so lrc.red never delays LRCLIB (#671).
     */
    val searchForIds: Boolean = false,
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
    /** Who made this sync, when the source names them (Spicy Lyrics' own catalogue only). */
    val credits: LyricsCredits? = null,
)

/** Who made a lyrics sync; only Spicy Lyrics' own catalogue has these. */
data class LyricsCredits(
    val uploaderName: String?, val uploaderUrl: String?,
    val makerName: String?, val makerUrl: String?,
)
