package com.stash.data.download.lossless.qobuz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the qobuz.squid.wtf JSON API. The deployed Next.js
 * proxy at `qobuz.squid.wtf/api/...` wraps every Qobuz response in a
 * `{success, data, error}` envelope and otherwise passes the upstream
 * Qobuz catalog payload through verbatim. We model only what the
 * matcher and resolver consume; everything else falls through under
 * the parser's `ignoreUnknownKeys = true` and disappears silently.
 *
 * If the squid.wtf wire shape drifts, untyped fields default to safe
 * zero/null values rather than failing deserialisation — what we want
 * for a third-party reverse-engineered API.
 */

// ── Envelope ────────────────────────────────────────────────────────────

/**
 * Common response envelope. squid.wtf returns this shape for every
 * `/api/...` route. On a clean response, [success] is true and [data] is
 * non-null; on a validation failure (HTTP 400), [success] is false and
 * [error] carries a short human-readable message. Upstream Qobuz
 * blocking (region lock, throttling) typically surfaces as HTTP 4xx —
 * caller deals with that separately via [QobuzApiException].
 */
@Serializable
data class SquidWtfEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val error: String? = null,
)

// ── Search (`/api/get-music`) ───────────────────────────────────────────

@Serializable
data class QobuzSearchData(
    val query: String? = null,
    val tracks: QobuzTrackList? = null,
    val albums: QobuzAlbumList? = null,
    /**
     * Set when the caller passed a Qobuz URL as the query — squid.wtf
     * parses the URL, runs the matching get-* call, and tags the
     * result with `albums` | `tracks` | `artists` so the UI knows
     * which section to surface. Stash always passes free-text, so
     * this is informational only.
     */
    val switchTo: String? = null,
)

@Serializable
data class QobuzTrackList(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val items: List<QobuzTrack> = emptyList(),
)

@Serializable
data class QobuzAlbumList(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val items: List<QobuzAlbum> = emptyList(),
)

// ── Track ───────────────────────────────────────────────────────────────

/**
 * Minimal track projection. [id] is the canonical Qobuz track id used
 * for `/api/download-music?track_id=...`. [streamable] reflects whether
 * the deployment's backing Qobuz account can actually stream this track
 * in its region — when false, the resolver should bail without calling
 * download-music (the call would 403).
 *
 * [maximumBitDepth] / [maximumSamplingRate] tell us what the source
 * file actually is upstream — useful for reporting to Library Health
 * before AudioDurationExtractor confirms post-download.
 */
@Serializable
data class QobuzTrack(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val isrc: String? = null,
    val performer: QobuzPerformer? = null,
    val album: QobuzAlbum? = null,
    @SerialName("maximum_bit_depth") val maximumBitDepth: Int = 0,
    @SerialName("maximum_sampling_rate") val maximumSamplingRate: Float = 0f,
    val streamable: Boolean = true,
    val hires: Boolean = false,
    /**
     * Some tracks have version/edition info embedded in `version` rather
     * than in the title (e.g. "Remastered 2011"). Useful for matching
     * against Spotify metadata that includes the version in the title
     * string. Optional.
     */
    val version: String? = null,
)

@Serializable
data class QobuzPerformer(
    val id: Long? = null,
    val name: String,
)

@Serializable
data class QobuzAlbum(
    val id: String? = null,
    val title: String? = null,
    @SerialName("tracks_count") val tracksCount: Int = 0,
    val artist: QobuzPerformer? = null,
    val image: QobuzImage? = null,
    val upc: String? = null,
    @SerialName("released_at") val releasedAt: Long = 0L,
)

@Serializable
data class QobuzImage(
    val small: String? = null,
    val thumbnail: String? = null,
    val large: String? = null,
    val back: String? = null,
)

// ── Download URL (`/api/download-music`) ────────────────────────────────

/**
 * Result of `/api/download-music`. squid.wtf strips the upstream Qobuz
 * `mime_type` / `sampling_rate` / `bit_depth` / `restrictions` fields
 * and returns just the signed CDN URL — region/streamable issues
 * surface as HTTP 4xx on this call rather than an in-payload field.
 *
 * The URL is short-lived (signed Akamai CDN); resolve-and-download
 * must be tightly coupled — don't cache.
 */
@Serializable
data class QobuzDownloadData(
    val url: String? = null,
)

/**
 * Quality codes accepted by `/api/download-music?quality=…`. The values
 * are upstream Qobuz `format_id` integers (preserved verbatim by
 * squid.wtf). Not a Kotlin enum so the wire ints stay stable.
 */
object QobuzQuality {
    /** MP3 320 kbps — last-resort lossy fallback. */
    const val MP3_320: Int = 5

    /** CD-quality FLAC 16-bit / 44.1 kHz. */
    const val FLAC_CD: Int = 6

    /** Hi-res FLAC up to 24-bit / 96 kHz when available. */
    const val FLAC_HIRES_96: Int = 7

    /**
     * Hi-res FLAC up to 24-bit / 192 kHz when available. squid.wtf's
     * own default — Qobuz returns highest-available <= requested, so
     * asking for 27 always gets you the best the source has.
     */
    const val FLAC_HIRES_192: Int = 27
}
