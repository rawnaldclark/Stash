package com.stash.data.download.lossless.tidal

/**
 * The network half of the Tidal rung (add-ons phase 2 §5.1). [TidalClient] keeps every policy
 * (the busy circuit, in-flight dedupe, the cache in `addon_refs`, miss stamps, the spent day, the
 * pick) and asks a wire only "search" and "stream". Two wires speak to the same Tidal backend:
 *  - [com.stash.data.download.lossless.bitchord.LegacyRelayWire]: today's BitChord routes on the
 *    signed config's `tidal_relay_url` (or the user's own server), unchanged;
 *  - [AddonV1Wire]: the Stash Add-ons v1 face of the first-party Tidal add-on, through
 *    `HttpAddonClient`.
 * A wire is bound to one server; [TidalWires] picks which one a lookup uses.
 */
interface TidalWire {
    val kind: Kind

    /** Stash's own Tidal (the signed config's), not the user's own server: its answers count the daily allowance. */
    val owner: Boolean

    /** Identity of the server for per-server state (the app-too-old pause). A secret: never logged. */
    val serverKey: String

    /** `"<artist> <title>"` on Tidal: every row, as neutral candidates; the client filters and picks. */
    suspend fun search(q: String): WireReply<List<TidalCandidate>>

    /**
     * A stream of the `td:<n>` track [id] in [mode]. [title] and [artist]: hints a relay may use.
     * [cdOnly]: the listener's quality setting is CD, so nothing above CD FLAC is wanted (decision
     * D15). Only the v1 wire can ask for that; the legacy relay ignores it and answers what Tidal
     * gives, as it always did.
     */
    suspend fun stream(id: String, mode: TidalStreamMode, title: String, artist: String, cdOnly: Boolean = false): WireReply<TidalWireStream>

    enum class Kind(val label: String) {
        LEGACY("legacy relay"),
        V1("v1 add-on"),
    }
}

/** What the rung asks a stream for. */
enum class TidalStreamMode {
    /** FLAC only (relay: no `aac`; v1: `tier=hires&minTier=lossless&manifest=none,dash`, `tier=lossless` at the CD setting). */
    LOSSLESS,

    /** FLAC, else Tidal's AAC 320 (relay: `aac=1`; v1: `tier=hires&minTier=high&manifest=none,dash`, `tier=lossless` at the CD setting). */
    LOSSLESS_OR_AAC,

    /** AAC 320 even when FLAC exists (relay: `aac=only&hires=0`; v1: `tier=high&minTier=high&manifest=none`). */
    AAC_ONLY,
}

/** One search row in neither wire's shape. [lossless]: Tidal has it in FLAC (relay `audioQuality`, v1 `maxTier`). */
data class TidalCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    /** SECONDS, Tidal's unit (0 = unknown). */
    val durationSec: Int,
    val artwork: String?,
    val lossless: Boolean,
    val isrc: String?,
    val explicit: Boolean?,
)

/** One stream answer in neither wire's shape. [expiresAtSec]: the progressive URL's own expiry, when the server says. */
data class TidalWireStream(
    val url: String,
    val codec: String,
    val isDash: Boolean,
    val bitDepth: Int,
    val sampleRate: Int,
    val bitrateKbps: Int,
    val expiresAtSec: Long?,
)

/** One answer from a wire, sorted by what the client must do about it. */
sealed interface WireReply<out T> {
    data class Ok<T>(val value: T) : WireReply<T>

    /**
     * No stream of this track in the asked mode. [definitive]: a real answer about the song (relay
     * `lossless_unavailable`, v1 `quality_unavailable`), trusted 14 days; anything else (relay
     * `not_available`, v1 `not_found` or `region_unavailable`) may be transient: 6 h.
     */
    data class NotFound(val definitive: Boolean) : WireReply<Nothing>

    /** The pool is busy or this caller is asking too fast: nothing before [untilMs]. */
    data class Busy(val untilMs: Long) : WireReply<Nothing>

    /** The day's allowance is spent (already recorded in `LosslessQuota`). */
    data object Spent : WireReply<Nothing>

    /** The server refuses this app build (relay `426 app_too_old`). */
    data object AppTooOld : WireReply<Nothing>

    /** No answer at all (timeout, refused connection, no route). */
    data object Unreachable : WireReply<Nothing>

    /** Anything else: transient, never cached as a miss. [code]: the HTTP status, 0 when none. */
    data class Error(val code: Int) : WireReply<Nothing>
}
