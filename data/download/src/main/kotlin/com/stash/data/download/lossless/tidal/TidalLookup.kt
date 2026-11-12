package com.stash.data.download.lossless.tidal

/**
 * A playable Tidal stream, from either wire ([TidalWire]). [isDash] = [url] is an MPD
 * (`/dash/td/<id>.mpd?exp&sig`) whose init segment the server has patched; else [url] is a
 * progressive file (Tidal CDN, or the server's signed `/audio/` proxy).
 */
data class TidalStream(
    val url: String,
    val isDash: Boolean,
    val bitDepth: Int,
    val sampleRateHz: Int,
    val bitrateKbps: Int,
    val coverArtUrl: String?,
    /** Absolute wall-clock expiry of [url], epoch-ms. */
    val expiresAtMs: Long,
    /** Match confidence from the scorer; the download chain ranks on it. */
    val confidence: Float = 0f,
    /** Server-side id (`td:<n>`), for logs. */
    val trackId: String? = null,
    /** "flac", or "aac" (320 kbps) only when the caller allowed AAC. */
    val codec: String = "flac",
)

/** What [TidalClient.lookup] found, sorted by what the caller should do about it. */
sealed interface TidalLookup {
    data class Found(val stream: TidalStream) : TidalLookup
    /** A definitive answer: no confident match, or no stream in the asked mode. Cached. */
    data object NoMatch : TidalLookup
    /** The relay pool is busy (503 / 429): no request goes out before [untilMs]. Not a miss. */
    data class Busy(val untilMs: Long) : TidalLookup
    /** A transient error (5xx, I/O, junk body). Not a miss and never cached. */
    data object Failed : TidalLookup
    /**
     * Any lookup (FLAC or AAC) while the day's allowance is used up (`LosslessQuota`), or the
     * relay's `429 daily_lossless_cap` (v1: `quota_exceeded`): nothing from Stash's Tidal before
     * [untilMs]. Not a miss, not busy, never cached.
     */
    data class Spent(val untilMs: Long) : TidalLookup
    /** Only from a `cacheOnly` lookup: the answer is not cached, the server would have to be asked. */
    data object NeedsRelay : TidalLookup
    /** No server configured. */
    data object Off : TidalLookup
}
