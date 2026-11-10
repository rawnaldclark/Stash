package com.stash.data.download.lossless.bitchord

import android.util.Log
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.lossless.tidal.TidalCandidate
import com.stash.data.download.lossless.tidal.TidalClient
import com.stash.data.download.lossless.tidal.TidalStreamMode
import com.stash.data.download.lossless.tidal.TidalWire
import com.stash.data.download.lossless.tidal.TidalWireStream
import com.stash.data.download.lossless.tidal.WireReply
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One `/search` row from a BitChord server (`app/schemas.py` TrackItem; extra keys ignored). */
@Serializable
internal data class BitchordTrack(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** SECONDS (Tidal's unit, rounded server-side). */
    val duration: Int = 0,
    val artwork: String? = null,
    val audioQuality: String = "",
    val isrc: String? = null,
    /**
     * Tidal's parental advisory: which of a song's clean and explicit twins (same title, album
     * and length, so the same score) this is. The matcher breaks ties on it. Null until the relay
     * passes Tidal's `explicit` through, and from any server that doesn't.
     */
    val explicit: Boolean? = null,
)

@Serializable
internal data class BitchordSearchResponse(val tracks: List<BitchordTrack> = emptyList())

/** `/stream/{id}` body (`app/schemas.py` StreamResponse). */
@Serializable
internal data class BitchordStreamResponse(
    val url: String = "",
    val codec: String = "",
    val container: String = "",
    /** `"dash"` for a Tidal MPD, `"none"` for a progressive file. */
    val manifest: String = "none",
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    /**
     * Progressive answers only: when the Tidal URL itself expires, epoch SECONDS. Null when
     * the relay doesn't say (older relays, DASH, whose MPD URL carries a signed `exp` instead).
     */
    val expiresAt: Long? = null,
)

/** A non-2xx body from the relay: `{error:"<code>"}`. */
@Serializable
internal data class BitchordErrorResponse(val error: String = "")

/**
 * The legacy wire: a BitChord Lossless Relay (the owner-hosted one from the signed config, or the
 * user's own server; upstream `R1Blmmakan/music_addon`), exactly as the Tidal rung spoke to it
 * before add-ons. [TidalClient] keeps the policy; this is only the network half.
 *
 * Contract used (all derived from upstream `app/main.py`):
 *  - `GET {base}/search?q=` → `{tracks:[{id:"td:<n>"|"dz:<n>", title, artist, album, duration(s),
 *    artwork, audioQuality, isrc, explicit}]}`.
 *  - `GET {base}/stream/td:<n>?client=media3` → `{url, codec, container, manifest:"dash"|"none",
 *    bitDepth, sampleRate, bitrate, expiresAt}`, or 404 when the track has no FLAC:
 *    `{error:"lossless_unavailable"}` is definitive, `{error:"not_available"}` may be transient.
 *    `client=media3` pins the server's DASH-vs-progressive sniffing to "native DASH client".
 *    `aac=1` allows Tidal's AAC 320 when there is no FLAC; `aac=only&hires=0` asks for AAC whatever
 *    Tidal has.
 *  - 503 (`busy` / `tidal_relay_paused` / `global_daily_cap`) and 429 (`network_daily_cap`) carry
 *    `Retry-After`; 429 `daily_lossless_cap` is the day's allowance; 426 `app_too_old`.
 *  - Auth: `X-Access-Token` (or the token as the base's path prefix). The `/dash/` and `/audio/`
 *    URLs it hands back are HMAC-signed with an `exp` (1 h) and need no token.
 *  - Install headers: a `/stream` to the owner's relay (never a user's own server) is signed by
 *    [RelaySigner] over `td:<numeric id>`. `/search` is not signed.
 *
 * A cross-host `307` from the cutover shim (plan §3.3) is followed by the shared OkHttp client,
 * which carries `X-Access-Token` and `X-Stash-*` on (it drops only `Authorization`).
 */
@Singleton
class LegacyRelayWire @Inject constructor(
    sharedClient: OkHttpClient,
    private val signer: RelaySigner,
    private val quota: LosslessQuota,
) {
    /**
     * Short timeouts: the server itself caps `/stream` at 7 s, and a self-hosted box that is down
     * must fail over to the lossy rungs fast.
     */
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TidalClient.TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TidalClient.TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TidalClient.TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** This wire on [relay]; [clock] is the client's (tests drive it). */
    fun bind(relay: TidalRelay, clock: () -> Long): TidalWire = Bound(relay, clock)

    private inner class Bound(private val relay: TidalRelay, private val clock: () -> Long) : TidalWire {
        override val kind = TidalWire.Kind.LEGACY
        override val owner: Boolean = !relay.userOwned
        override val serverKey: String = relay.base

        private val base: HttpUrl? = relay.base.toHttpUrlOrNull()

        override suspend fun search(q: String): WireReply<List<TidalCandidate>> {
            val url = (base ?: return WireReply.Error(0)).newBuilder().addPathSegment("search").addQueryParameter("q", q).build()
            return when (val r = get(url, signPayload = null)) {
                is WireReply.Ok -> WireReply.Ok(json.decodeFromString<BitchordSearchResponse>(r.value).tracks.map { it.toCandidate() })
                is WireReply.NotFound -> WireReply.Error(404)
                is WireReply.Busy -> r
                WireReply.Spent -> WireReply.Spent
                WireReply.AppTooOld -> WireReply.AppTooOld
                WireReply.Unreachable -> WireReply.Unreachable
                is WireReply.Error -> r
            }
        }

        override suspend fun stream(id: String, mode: TidalStreamMode, title: String, artist: String): WireReply<TidalWireStream> {
            val url = (base ?: return WireReply.Error(0)).newBuilder()
                .addPathSegment("stream")
                .addPathSegment(id)
                .addQueryParameter("client", "media3")
                // Hints the server uses if its own Tidal metadata lookup fails.
                .addQueryParameter("title", title)
                .addQueryParameter("artist", artist)
                .apply {
                    when (mode) {
                        // hires=0: no Hi-Res lookup, which the web player wants and a download
                        // doesn't (one Tidal call fewer). A relay without it ignores the flag.
                        TidalStreamMode.AAC_ONLY -> addQueryParameter("aac", "only").addQueryParameter("hires", "0")
                        TidalStreamMode.LOSSLESS_OR_AAC -> addQueryParameter("aac", "1")
                        TidalStreamMode.LOSSLESS -> Unit
                    }
                }
                .build()
            // Only the owner's relay (from the signed config) verifies install headers; a user's
            // own server never sees our install id.
            val sign = if (owner) "td:${id.removePrefix("td:")}" else null
            return when (val r = get(url, signPayload = sign)) {
                is WireReply.Ok -> {
                    val s = json.decodeFromString<BitchordStreamResponse>(r.value)
                    WireReply.Ok(
                        TidalWireStream(
                            url = s.url,
                            codec = s.codec,
                            isDash = s.manifest.equals("dash", ignoreCase = true),
                            bitDepth = s.bitDepth,
                            sampleRate = s.sampleRate,
                            bitrateKbps = s.bitrate,
                            expiresAtSec = s.expiresAt,
                        ),
                    )
                }
                is WireReply.NotFound -> r
                is WireReply.Busy -> r
                WireReply.Spent -> WireReply.Spent
                WireReply.AppTooOld -> WireReply.AppTooOld
                WireReply.Unreachable -> WireReply.Unreachable
                is WireReply.Error -> r
            }
        }

        /**
         * One GET. With [signPayload] (a `/stream` to the owner's relay) the request carries the
         * install headers ([RelaySigner], message `<install>:td:<numeric id>:<ts>`) and a stored
         * supporter token; a 401 that names the relay's time re-signs once on that clock. Only an
         * [owner] relay's answers feed the daily allowance. No answer at all is [WireReply.Unreachable].
         */
        private suspend fun get(url: HttpUrl, signPayload: String?): WireReply<String> {
            var mayRetrySkew = true
            while (true) {
                val b = Request.Builder().url(url).get()
                    .header("Accept", "application/json")
                    .apply { if (relay.token != null) header("X-Access-Token", relay.token) }
                if (url.encodedPath.startsWith("/stream/")) signer.appBuild(b)
                val signed = signPayload != null && signer.sign(b, signPayload, clock())
                if (signPayload != null && owner) signer.entitle(b)
                val response = try {
                    httpClient.newCall(b.build()).execute()
                } catch (e: IOException) {
                    // Class only: an IOException's text can carry the URL, and the base may hold
                    // the access token as its path prefix.
                    Log.w(TidalClient.TAG, "relay unreachable: ${e.javaClass.simpleName}")
                    return WireReply.Unreachable
                }
                val reply = response.use { r ->
                    if (r.code == 401 && signed && mayRetrySkew && signer.adoptServerTime(r, clock())) null else reply(r)
                }
                if (reply != null) return reply
                mayRetrySkew = false
            }
        }

        /** Sorts one relay response. */
        private fun reply(r: okhttp3.Response): WireReply<String> {
            // Every owner relay answer may carry the day's lossless count.
            if (owner) quota.onResponse(r)
            if (!r.isSuccessful) {
                // Code only: the path may carry the access token as its prefix.
                Log.d(TidalClient.TAG, "HTTP ${r.code}")
                val now = clock()
                return when (r.code) {
                    404 -> WireReply.NotFound(definitive = errorCode(r) == "lossless_unavailable")
                    RelaySigner.APP_TOO_OLD -> WireReply.AppTooOld
                    // The relay says how long: 15 s when only seat pacing blocks, 30 s for its
                    // per-network limiter, 300 / 600 s when capped or paused.
                    503 -> WireReply.Busy(now + retryAfterMs(r, TidalClient.BUSY_MIN_RETRY_AFTER_MS, TidalClient.BUSY_MAX_RETRY_AFTER_MS))
                    // The daily lossless cap is this install's allowance, not a busy pool.
                    429 -> if (owner && boundedBody(r)?.let { quota.onCapBody(it) } == true) {
                        WireReply.Spent
                    } else {
                        // network_daily_cap answers Retry-After 3600, hence the 1 h ceiling.
                        WireReply.Busy(now + retryAfterMs(r, TidalClient.MIN_RETRY_AFTER_MS, TidalClient.MAX_RETRY_AFTER_MS))
                    }
                    else -> WireReply.Error(r.code)
                }
            }
            val body = r.body ?: throw IOException("empty body")
            if (body.contentLength() > TidalClient.MAX_BODY_BYTES) throw IOException("body too large")
            return WireReply.Ok(body.string())
        }
    }

    private fun BitchordTrack.toCandidate() = TidalCandidate(
        id = id,
        title = title,
        artist = artist,
        album = album.takeIf { it.isNotBlank() },
        durationSec = duration,
        artwork = artwork?.takeIf { it.isNotBlank() },
        lossless = audioQuality in LOSSLESS_TIERS,
        isrc = isrc,
        explicit = explicit,
    )

    /** The `error` code of a non-2xx JSON body, or null when absent, oversized or unparseable. */
    private fun errorCode(r: okhttp3.Response): String? = runCatching {
        val text = boundedBody(r) ?: return null
        json.decodeFromString<BitchordErrorResponse>(text).error.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** A non-2xx body as text, or null when absent, oversized or unreadable. */
    private fun boundedBody(r: okhttp3.Response): String? = runCatching {
        val body = r.body ?: return null
        if (body.contentLength() > TidalClient.MAX_BODY_BYTES) return null
        body.string()
    }.getOrNull()

    /** Retry-After (seconds) clamped to [[min], [max]]; absent or junk (an HTTP-date too) → [TidalClient.BUSY_BACKOFF_MS]. */
    private fun retryAfterMs(r: okhttp3.Response, min: Long, max: Long): Long =
        r.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L)?.coerceIn(min, max) ?: TidalClient.BUSY_BACKOFF_MS

    internal companion object {
        /** The relay's `audioQuality` values that are FLAC. */
        val LOSSLESS_TIERS = setOf("LOSSLESS", "HI_RES_LOSSLESS")
    }
}
