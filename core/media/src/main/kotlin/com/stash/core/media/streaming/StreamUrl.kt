package com.stash.core.media.streaming

/**
 * Result of a successful stream-URL lookup. [url] is the signed CDN URL
 * the player should fetch; [expiresAtMs] is the wall-clock instant
 * (epoch-millis, comparable to [System.currentTimeMillis]) at which the
 * signature stops being valid and the CDN starts returning 403.
 *
 * Callers cache the URL keyed by track id with this TTL — the
 * [com.stash.core.media.streaming.StreamUrlCache] (Task 4) and the mid-
 * stream refresh path in [com.stash.core.media.streaming
 * .RefreshingDataSourceFactory] (Task 6) both rely on it.
 */
data class StreamUrl(
    val url: String,
    val expiresAtMs: Long,
    /**
     * Lowercase codec tag from the Qobuz API (`"flac"`, `"mp3"`, etc.).
     * Null when the source didn't surface it.
     */
    val codec: String? = null,
    /** Bits per sample (16, 24). Null/0 for lossy codecs. */
    val bitsPerSample: Int? = null,
    /** Sample rate in Hz. Null when unknown. */
    val sampleRateHz: Int? = null,
    /** Stated bitrate in kbps. Null when unknown. */
    val bitrateKbps: Int? = null,
    /**
     * Studio cover-art URL from the upstream catalog. Used to overwrite
     * YouTube-sourced tracks' Music-Video thumbnails with proper album
     * art whenever a Qobuz match is found at stream time. Null when the
     * source didn't surface it.
     */
    val coverArtUrl: String? = null,
    /**
     * Which resolver served this URL — used by the UI to label tracks
     * that are streaming from a non-lossless fallback (e.g. YouTube)
     * so the user knows quality has degraded from the Qobuz baseline.
     *
     * Conventional values:
     *  - `"qbdlx"`      — the listener's own Qobuz account (lossless)
     *  - `"tidal"`      — Tidal through a BitChord server (lossless, or AAC)
     *  - `"cloud"`      — the listener's own copy in their cloud storage
     *  - `"jiosaavn"`   — JioSaavn AAC-320 fallback (lossy)
     *  - `"soundcloud"` — SoundCloud (lossy)
     *  - `"youtube"`    — yt-dlp/InnerTube extraction (lossy)
     */
    val origin: String? = null,
    /**
     * [url] is a DASH MPD, not a progressive file (Tidal via BitChord — see
     * [TidalStreamResolver]). Must be played by a DASH media source: the
     * progressive byte-pipe [LazyResolvingDataSource] refuses it.
     */
    val isDash: Boolean = false,
    val isHls: Boolean = false,
    val isPreview: Boolean = false,
)
