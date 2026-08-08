package com.stash.data.download.lossless

import com.stash.data.download.lossless.spotifyresolve.SpotifyUriResolver

/**
 * Pluggable resolver for lossless (or higher-quality-than-current) audio
 * sources. Each external source — squid.wtf's per-provider endpoints,
 * lucida, doubledouble, Bandcamp purchases, Internet Archive, a watched
 * local-file inbox — implements this interface.
 *
 * The resolver chain ([LosslessSourceRegistry]) iterates registered
 * sources in user-configured priority order and returns the first match
 * meeting the user's quality threshold. The actual file fetch happens
 * downstream via the existing download pipeline; a [LosslessSource] is
 * responsible only for "given a track, find a download URL."
 *
 * Implementations MUST gate every network call behind
 * [AggregatorRateLimiter.acquire] for their [id]. The rate limiter is
 * the single load-bearing safeguard against the failure mode where Stash
 * accidentally DDoSes a community-run aggregator service and gets us all
 * banned. Conservative defaults are wired in the limiter, not here.
 */
interface LosslessSource {
    /**
     * Stable identifier — used as the key for rate-limit state, user
     * priority preferences, and logs. Keep snake_case ASCII.
     * Examples: `"squid_qobuz"`, `"squid_tidal"`, `"lucida"`,
     * `"bandcamp"`, `"ia_lma"`, `"local_inbox"`.
     */
    val id: String

    /**
     * Human-readable name for the Settings UI. Examples:
     * `"squid.wtf (Qobuz)"`, `"Bandcamp Purchases"`, `"Internet Archive"`.
     */
    val displayName: String

    /**
     * Whether this source is currently usable. Returns false if the user
     * has disabled it, if required credentials are missing, or if the
     * source is in a circuit-broken state from repeated failures.
     * Callers should skip disabled sources without logging a warning.
     */
    suspend fun isEnabled(): Boolean

    /**
     * Searches the source for the given track. Returns null when no
     * confident match exists, when the rate limiter blocks the call, or
     * when the source's network call fails. Implementations are
     * expected to:
     *
     *  1. Check [isEnabled]; bail early if disabled.
     *  2. Acquire a token from [AggregatorRateLimiter] for [id]; bail if
     *     the source is circuit-broken.
     *  3. Perform the lookup, biased toward ISRC match when [TrackQuery.isrc]
     *     is non-null, then artist+title fuzzy match.
     *  4. Validate the returned format meets a credible bar (lossless or
     *     stated bitrate matches the file's actual bitrate within a
     *     reasonable tolerance — never trust source claims blindly).
     *  5. On 429, call [AggregatorRateLimiter.reportRateLimited]; on
     *     any other failure, [AggregatorRateLimiter.reportFailure]; on
     *     success, [AggregatorRateLimiter.reportSuccess].
     *
     * @param bypassRateLimit when true, a user-initiated (foreground) resolve
     *   skips the per-source token bucket. Background/speculative callers pass
     *   false (the default). Sources without a rate limiter ignore it.
     */
    suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean = false): SourceResult?

    /**
     * Inspect the source's current rate-limit state. Used by the Library
     * Health / Lossless-Sources Settings screen to surface "this source
     * is paused for 4m13s" type information.
     */
    suspend fun rateLimitState(): RateLimitState
}

/**
 * Identification fields for a track lookup. ISRC is the most precise
 * identifier — when present, sources should match by ISRC first, falling
 * back to artist+title fuzzy match only when no ISRC hit is found. Duration
 * is used to disambiguate "studio vs live" type mismatches: a 4-minute
 * Spotify track shouldn't match a 12-minute live recording even if the
 * artist+title strings agree.
 */
data class TrackQuery(
    val artist: String,
    val title: String,
    val album: String? = null,
    val isrc: String? = null,
    val durationMs: Long? = null,
    /** Explicit-content identity when known; null means the source cannot verify it. */
    val explicit: Boolean? = null,
    /**
     * The track's Spotify URI (`spotify:track:<id>`, a bare id, or an
     * `open.spotify.com/track/<id>` URL), when known. Used only by sources
     * that resolve from a Spotify URL (antra). Null for search-tab /
     * YouTube-pathway downloads, which correctly makes them skip antra.
     */
    val spotifyUri: String? = null,
    /**
     * The local DB id of the track, when known. Used as the resolution key by
     * [SpotifyUriResolver] (cache side-table + in-flight coalescing) when no
     * [spotifyUri] is present, so a YouTube-pathway track can still be routed
     * to antra after a Spotify search. Null when there is no backing DB row.
     */
    val trackId: Long? = null,
)

/**
 * The `https://open.spotify.com/track/<id>` URL antra's `/api/resolve`
 * consumes, derived from [TrackQuery.spotifyUri]. Normalises the three
 * shapes a URI arrives in (the `spotify:track:<id>` URI, a bare id, or an
 * already-open URL) and returns null for anything that isn't a *track* —
 * so albums/playlists/episodes never get mis-routed to a track resolver.
 */
fun TrackQuery.spotifyTrackUrl(): String? {
    val raw = spotifyUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = when {
        raw.startsWith("spotify:track:") -> raw.removePrefix("spotify:track:")
        raw.startsWith("spotify:") -> return null // album/playlist/episode URI
        raw.contains("open.spotify.com/track/") ->
            raw.substringAfter("open.spotify.com/track/").substringBefore("?").substringBefore("/")
        raw.contains("open.spotify.com/") -> return null // non-track open URL
        raw.contains(":") || raw.contains("/") -> return null // unrecognised scheme/path
        else -> raw // bare id
    }.trim()
    return id.takeIf { it.isNotEmpty() }?.let { "https://open.spotify.com/track/$it" }
}

/**
 * The Spotify track URL to hand antra: the existing one derived from
 * [spotifyUri] when present, otherwise — when a [trackId] is available —
 * a [SpotifyUriResolver] lookup (cache-first Spotify search). Returns null
 * when there is neither a usable existing URL nor a trackId to resolve on,
 * so the resolver is never consulted for a track it could not key.
 */
suspend fun TrackQuery.resolvedSpotifyTrackUrl(resolver: SpotifyUriResolver): String? =
    spotifyTrackUrl() ?: trackId?.let { id -> resolver.resolveUrl(id, this) }

/**
 * The proxy search terms to try for this query, in priority order.
 *
 * - When an [TrackQuery.isrc] is present it is the precise index key — used alone.
 * - Otherwise the FULL artist credit is tried first ("Tyler, The Creator Foo"),
 *   so single artists whose name contains a comma still match.
 * - Then, for multi-artist credits joined with commas (e.g.
 *   "¥$, Kanye West, Ty Dolla $ign"), a fallback using only the PRIMARY artist
 *   (text before the first comma → "¥$") is appended. Sending the full credit
 *   verbatim makes the proxy return the featured artists' popular tracks
 *   instead of the actual song, so every candidate scores 0 and the download
 *   fails; the primary-artist retry rescues it. (Stream + download share this.)
 */
fun TrackQuery.searchTerms(): List<String> {
    isrc?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
    val full = "$artist $title".trim()
    val primary = artist.substringBefore(",").trim()
    return if (primary.isNotEmpty() && !primary.equals(artist.trim(), ignoreCase = true)) {
        listOf(full, "$primary $title".trim())
    } else {
        listOf(full)
    }
}

/**
 * A successful match returned by a [LosslessSource]. The downstream
 * download pipeline consumes [downloadUrl] and [downloadHeaders];
 * [format] and [confidence] drive whether the result is accepted versus
 * skipped in favor of trying the next source in the chain.
 */
data class SourceResult(
    /** [LosslessSource.id] of the source that produced this match. */
    val sourceId: String,

    /** HTTPS URL the downloader should fetch. */
    val downloadUrl: String,

    /**
     * Optional HTTP headers to attach to the fetch — auth tokens,
     * referer, signed-request headers. Empty for sources that serve
     * unauthenticated direct URLs.
     */
    val downloadHeaders: Map<String, String> = emptyMap(),

    /** Expected format of the file at [downloadUrl]. */
    val format: AudioFormat,

    /**
     * Confidence in the match, on [0.0, 1.0]. ISRC-derived matches should
     * report >=0.95; fuzzy artist+title matches lower (proportional to
     * Levenshtein / token-set similarity). The chain prefers higher
     * confidence within a tier and falls back to lower-priority sources
     * when nothing crosses a configurable threshold.
     */
    val confidence: Float,

    /**
     * Source-side track identifier (e.g. Tidal track id, Bandcamp track
     * url, IA item identifier). Useful for logging and avoiding redundant
     * lookups; not load-bearing for download.
     */
    val sourceTrackId: String? = null,

    /**
     * Album art URL surfaced by the source's catalog API. The download
     * pipeline persists this to `tracks.album_art_url` (fill-only-if-blank
     * semantics) so FLAC tracks show artwork in lists / now-playing the
     * same way YouTube-pathway tracks do. `null` when the source's
     * response had no usable image — the pipeline falls back to its
     * existing Last.fm / generic resolution where applicable.
     *
     * Sources should pick the highest reasonable size (Qobuz's `large`
     * is ~600px; bandcamp's `art_large` is similar). Coil downsamples
     * client-side as needed.
     */
    val coverArtUrl: String? = null,

    /**
     * AES-128 hex key for sources whose [downloadUrl] serves CENC/CMAF
     * *encrypted* bytes that must be decrypted client-side before the file
     * is usable (amz/Amazon Music). When non-null, the downloader fetches
     * the encrypted body then runs `ffmpeg -decryption_key` (see
     * `AmzDecryptor`) to produce clear FLAC. `null` for sources that serve
     * already-clear audio (Qobuz/Kennyy) — the common case.
     */
    val decryptionKey: String? = null,
)

/**
 * Audio format expected from the source. Used by the resolver chain to
 * filter out sources that can't meet the user's minimum quality
 * threshold and to populate `TrackEntity.file_format` / `quality_kbps`
 * in advance of post-download verification.
 *
 * Bitrate is approximate for VBR codecs; the canonical value is whatever
 * `AudioDurationExtractor.extract()` reports after the file is on disk.
 */
data class AudioFormat(
    /** Lower-case codec tag — `"flac"`, `"alac"`, `"aac"`, `"opus"`, `"mp3"`, `"vorbis"`. */
    val codec: String,
    /** Stated bitrate in kbps. Approximate for VBR; verify post-download. */
    val bitrateKbps: Int,
    /** Sample rate in Hz; 0 if unknown. */
    val sampleRateHz: Int = 0,
    /** Bits per sample for lossless; 0 if unknown / lossy. */
    val bitsPerSample: Int = 0,
    /** On-disk container extension; differs from [codec] for AAC in MP4/M4A. */
    val fileExtension: String = codec,
) {
    /**
     * True for codecs that encode audio without information loss —
     * FLAC, ALAC, WAV, APE. Does not consider source-master fidelity
     * (a "FLAC" file may itself have been transcoded from lossy and is
     * still flagged lossless here; we trust the codec, not the chain).
     */
    val isLossless: Boolean
        get() = codec.lowercase() in LOSSLESS_CODECS

    companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "ape", "tta", "wv", "aiff")
    }
}

/**
 * Snapshot of a source's rate-limit state. [tokensAvailable] under 1.0
 * means the next call will wait; [isCircuitBroken] true means the source
 * has been temporarily disabled because of repeated failures.
 *
 * Settings UI uses this to render a per-source status row.
 */
data class RateLimitState(
    val tokensAvailable: Double,
    val msUntilNextToken: Long,
    val isCircuitBroken: Boolean,
    val msUntilUnblock: Long,
    val recentFailures: Int,
)
