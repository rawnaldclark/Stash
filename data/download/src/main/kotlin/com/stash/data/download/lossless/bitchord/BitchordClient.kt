package com.stash.data.download.lossless.bitchord

import android.util.Log
import com.stash.core.data.db.dao.TidalMatchDao
import com.stash.core.data.db.entity.TidalMatchEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.TidalRelay
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
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
 * A playable Tidal stream from a BitChord server. [isDash] = [url] is an MPD
 * (`/dash/td/<id>.mpd?exp&sig`) whose init segment the server has patched; else
 * [url] is a progressive FLAC (Tidal CDN, or the server's signed `/audio/` proxy).
 */
data class BitchordStream(
    val url: String,
    val isDash: Boolean,
    val bitDepth: Int,
    val sampleRateHz: Int,
    val bitrateKbps: Int,
    val coverArtUrl: String?,
    /** Absolute wall-clock expiry of [url], epoch-ms. */
    val expiresAtMs: Long,
    /** Match confidence from [QobuzCandidateMatcher]; the download chain ranks on it. */
    val confidence: Float = 0f,
    /** Server-side id (`td:<n>`), for logs. */
    val trackId: String? = null,
    /** "flac", or "aac" (320 kbps) only when the caller passed `allowAac`. */
    val codec: String = "flac",
)

/** What [BitchordClient.lookup] found, sorted by what the caller should do about it. */
sealed interface TidalLookup {
    data class Found(val stream: BitchordStream) : TidalLookup
    /** A definitive answer: no confident match, or no stream in the asked mode. Cached. */
    data object NoMatch : TidalLookup
    /** The relay pool is busy (503 / 429): no request goes out before [untilMs]. Not a miss. */
    data class Busy(val untilMs: Long) : TidalLookup
    /** A transient error (5xx, I/O, junk body). Not a miss and never cached. */
    data object Failed : TidalLookup
    /**
     * A lossless lookup while the day's lossless allowance is used up ([LosslessQuota]), or the
     * relay's `429 daily_lossless_cap`: no FLAC from the owner's relay before [untilMs]. Not a
     * miss, not busy (AAC lookups still go out), never cached.
     */
    data class Spent(val untilMs: Long) : TidalLookup
    /** Only from a `cacheOnly` lookup: the answer is not cached, the relay would have to be asked. */
    data object NeedsRelay : TidalLookup
    /** No server configured. */
    data object Off : TidalLookup
}

/**
 * Client for a BitChord Lossless Relay (the owner-hosted one from the signed config, or a user override) (upstream
 * `R1Blmmakan/music_addon`, a FastAPI service), used as the Tidal rung of the
 * streaming chain — after Qobuz, before the lossy sources.
 *
 * Contract used (all derived from upstream `app/main.py`):
 *  - `GET {base}/search?q=` → `{tracks:[{id:"td:<n>"|"dz:<n>", title, artist,
 *    album, duration(s), artwork, audioQuality, isrc}]}`. Only `td:` rows whose
 *    `audioQuality` is LOSSLESS / HI_RES_LOSSLESS are considered.
 *  - `GET {base}/stream/td:<n>?client=media3` → `{url, codec, container,
 *    manifest:"dash"|"none", bitDepth, sampleRate, bitrate}`, or 404 when the
 *    track has no FLAC (the server rejects lossy itself): `{error:"lossless_unavailable"}`
 *    is definitive, `{error:"not_available"}` may be transient. 502 `upstream_error` is
 *    transient and never cached. `client=media3` pins the
 *    server's DASH-vs-progressive sniffing to "native DASH client" so it never
 *    swaps the Tidal MPD for a Deezer progressive file meant for browsers.
 *  - 503 (`busy` / `tidal_relay_paused` / `global_daily_cap`) and 429
 *    (`network_daily_cap`) mean the shared account pool is saturated: see [busyUntilMs].
 *    Both carry `Retry-After` (seconds), which sets how long the circuit stays open.
 *  - Auth: when the server sets `ACCESS_TOKEN`, either the base carries it as a
 *    path prefix (`https://host/<token>`, BitChord's own format) or it goes in
 *    `X-Access-Token`. The `/dash/` and `/audio/` URLs it hands back are HMAC-signed
 *    with an `exp` (1 h) and need no token.
 *  - Install headers: a `/stream` to the owner's relay (the signed config's, never a user's own
 *    server) is signed by [RelaySigner] over `td:<numeric id>`, the same key and install id as a
 *    Qobuz mint, so both relays count one allowance. `/search` is not signed.
 *
 * Request budget: every `/search` and `/stream` spends quota on a small shared pool of
 * Tidal accounts, so answers are cached per song in Room ([TidalMatchDao]; matches 30 days,
 * misses 14 days (6 h for a `/stream` 404 that is not `lossless_unavailable`) — never the stream URL itself), concurrent identical lookups share one
 * request, and a busy pool pauses every caller on this device for as long as the relay asks.
 *
 * Matching reuses [QobuzCandidateMatcher] — the scorer the Qobuz rung uses — on
 * neutral fields, so ISRC equality, title/artist normalisation, version words and
 * the duration penalty all behave identically across the two lossless rungs.
 */
@Singleton
class BitchordClient @Inject constructor(
    sharedClient: OkHttpClient,
    private val prefs: LosslessSourcePreferences,
    private val config: LosslessConfigFetcher,
    private val matchDao: TidalMatchDao,
    private val signer: RelaySigner,
    private val quota: LosslessQuota,
) {
    /**
     * Short timeouts: the server itself caps `/stream` at 7 s, and a self-hosted
     * box that is down must fail over to the lossy rungs fast.
     */
    internal var httpClient: OkHttpClient = sharedClient.newBuilder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Non-suspending view of [isConfigured] for the player's media-source factory,
     * which runs on the playback thread and must not block on DataStore. Eager, so
     * it has settled long before the first track is prepared.
     */
    val configured: StateFlow<Boolean> = combine(prefs.bitchordEndpoint, config.tidalRelay) { user, signed ->
        user != null || signed != null
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, false)

    /** True when a server is known (the user's own, or the signed config's); the rung is off otherwise. */
    suspend fun isConfigured(): Boolean = server() != null

    /**
     * The server to use: the user's override from Settings when set — an explicit
     * choice beats an implicit one, and its token goes with it (never the config's,
     * which belongs to another server) — else the owner-hosted one from the signed
     * runtime config ([LosslessConfigFetcher.tidalRelay]). Null = rung off.
     */
    private suspend fun server(): TidalRelay? {
        prefs.bitchordEndpointNow()?.let { return TidalRelay(it, prefs.bitchordAccessTokenNow(), userOwned = true) }
        return config.tidalRelay.first()
    }

    /**
     * Device-wide "pool busy" circuit, epoch-ms. A 503 (`no_account`: every pooled Tidal
     * account is capped or cooling) or a 429 opens it, and until it closes NO request goes to
     * the relay from any path: streaming, downloads, sweeps. Before this, those answers read
     * as "no match", so a saturated pool was asked again for every track and every rung.
     */
    @Volatile private var busyUntilMs: Long = 0L

    /** Wall clock; tests drive it. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Shared lookups outlive any one caller's cancellation, or dedupe would be pointless. */
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-flight relay lookups by [cacheKey] + mode; two callers for one song share one request. */
    private val inFlight = ConcurrentHashMap<String, Deferred<TidalLookup>>()

    @Volatile private var pruned = false

    /** True while the pool-busy circuit is open (no relay calls). */
    fun isBusy(): Boolean = nowMs() < busyUntilMs

    /**
     * The stream for [query], or null on anything else (miss, busy, error, off). Kept for
     * the streaming rung, which only needs "play this or move on"; see [lookup].
     */
    suspend fun resolve(query: TrackQuery, allowAac: Boolean = false): BitchordStream? =
        (lookup(query, allowAac) as? TidalLookup.Found)?.stream

    /**
     * Find [query] on Tidal through the relay, spending as little relay quota as possible:
     *  1. pool-busy circuit open → [TidalLookup.Busy], no request;
     *  2. a fresh cached miss for this mode → [TidalLookup.NoMatch], no request;
     *  3. a lossless lookup on the owner's relay with the day's allowance spent →
     *     [TidalLookup.Spent], no request (AAC lookups still go out: AAC never counts);
     *  4. [cacheOnly] (speculative callers) → [TidalLookup.NeedsRelay], no request;
     *  5. a fresh cached `td:` match → `/stream` only, no `/search`;
     *  6. else `/search` + `/stream`, and the outcome is cached.
     * Concurrent identical lookups share one request.
     *
     * [allowAac]: streaming / AAC download only. CD-only tracks never come back lossless with
     * the relay's Tidal client, so the server can answer with Tidal's 320 kbps AAC instead
     * (`?aac=1`). Lossless downloads leave it off so AAC is never saved as FLAC. A cached
     * lossless miss does not block an AAC lookup: each mode keeps its own miss stamp.
     */
    suspend fun lookup(query: TrackQuery, allowAac: Boolean = false, cacheOnly: Boolean = false): TidalLookup {
        val now = nowMs()
        if (now < busyUntilMs) return TidalLookup.Busy(busyUntilMs)
        val key = cacheKey(query)
        if (cachedMiss(readCache(key), allowAac, now)) return TidalLookup.NoMatch
        if (!allowAac && quota.isSpent() && prefs.bitchordEndpointNow() == null) return TidalLookup.Spent(quota.resetsAtMs())
        if (cacheOnly) return TidalLookup.NeedsRelay
        val server = server() ?: return TidalLookup.Off

        val flightKey = "$key|aac=$allowAac"
        inFlight[flightKey]?.let { return it.await() }
        val fresh = lookupScope.async(start = CoroutineStart.LAZY) { fetch(server, query, key, allowAac) }
        val existing = inFlight.putIfAbsent(flightKey, fresh)
        val deferred = if (existing != null) {
            // Lost the race: drop ours (a LAZY async is still attached to the scope's job).
            fresh.cancel()
            existing
        } else {
            fresh.also { d ->
                d.invokeOnCompletion { inFlight.remove(flightKey, d) }
                d.start()
            }
        }
        return deferred.await()
    }

    /** The network half of [lookup]; runs once per song and mode at a time. */
    private suspend fun fetch(server: TidalRelay, query: TrackQuery, key: String, allowAac: Boolean): TidalLookup =
        withContext(Dispatchers.IO) {
            val base = server.base.toHttpUrlOrNull() ?: return@withContext TidalLookup.Failed
            val token = server.token
            try {
                // Re-read: a lookup that finished just before this one started may have searched.
                val cached = readCache(key)?.takeIf { it.tidalId != null && nowMs() - it.searchedAt < MATCH_TTL_MS }
                val match: TidalMatchEntity = cached ?: run {
                    val searchUrl = base.newBuilder()
                        .addPathSegment("search")
                        .addQueryParameter("q", "${query.artist} ${query.title}".trim())
                        .build()
                    val body = when (val r = get(searchUrl, token)) {
                        is Reply.Ok -> r.body
                        is Reply.Busy -> return@withContext busy(r.untilMs)
                        Reply.Spent -> return@withContext TidalLookup.Spent(quota.resetsAtMs())
                        else -> return@withContext TidalLookup.Failed
                    }
                    val tracks = json.decodeFromString<BitchordSearchResponse>(body).tracks
                    val best = tracks
                        .asSequence()
                        .filter { it.id.startsWith("td:") && it.audioQuality in LOSSLESS_TIERS }
                        .map { it to confidence(query, it) }
                        .filter { it.second >= QobuzCandidateMatcher.MIN_CONFIDENCE }
                        .maxByOrNull { it.second }
                    if (best == null) {
                        Log.d(TAG, "no confident match among ${tracks.size} for '${query.artist} - ${query.title}'")
                        writeCache(TidalMatchEntity(key = key, tidalId = null, searchedAt = nowMs()))
                        return@withContext TidalLookup.NoMatch
                    }
                    val (t, conf) = best
                    TidalMatchEntity(
                        key = key,
                        tidalId = t.id,
                        title = t.title,
                        artist = t.artist,
                        artwork = t.artwork?.takeIf { it.isNotBlank() },
                        confidence = conf,
                        searchedAt = nowMs(),
                    ).also { writeCache(it) }
                }
                val id = match.tidalId ?: return@withContext TidalLookup.NoMatch
                val streamUrl = base.newBuilder()
                    .addPathSegment("stream")
                    .addPathSegment(id)
                    .addQueryParameter("client", "media3")
                    // Hints the server uses if its own Tidal metadata lookup fails.
                    .addQueryParameter("title", match.title)
                    .addQueryParameter("artist", match.artist)
                    .apply { if (allowAac) addQueryParameter("aac", "1") }
                    .build()
                // Only the owner's relay (from the signed config) verifies install headers; a
                // user's own server never sees our install id.
                val sign = if (!server.userOwned) "td:${id.removePrefix("td:")}" else null
                when (val r = get(streamUrl, token, signPayload = sign)) {
                    is Reply.Ok -> {
                        val stream = toStream(json.decodeFromString<BitchordStreamResponse>(r.body), match, allowAac)
                        if (stream == null) {
                            stampMiss(match, allowAac)
                            TidalLookup.NoMatch
                        } else {
                            // The relay prefers lossless, so an AAC answer means there is none —
                            // unless the day's allowance is spent: then AAC is all it would serve.
                            if (stream.codec == "aac" && !quota.isSpent()) writeCache(match.copy(losslessMissAt = nowMs()))
                            TidalLookup.Found(stream)
                        }
                    }
                    // lossless_unavailable: a real answer about this song. not_available (or a body
                    // we can't read) may be a transient refusal: a short miss, see [stampMiss].
                    is Reply.NotFound -> {
                        stampMiss(match, allowAac, definitive = r.definitive)
                        TidalLookup.NoMatch
                    }
                    is Reply.Busy -> busy(r.untilMs)
                    Reply.Spent -> TidalLookup.Spent(quota.resetsAtMs())
                    is Reply.Error -> TidalLookup.Failed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Message only: an IOException's text can carry the URL, and the base
                // may hold the access token as its path prefix.
                Log.w(TAG, "resolve failed: ${e.javaClass.simpleName}")
                TidalLookup.Failed
            }
        }

    private fun busy(untilMs: Long): TidalLookup {
        if (untilMs > busyUntilMs) {
            busyUntilMs = untilMs
            Log.i(TAG, "relay pool busy; pausing Tidal for ${(untilMs - nowMs()) / 1000}s")
        }
        return TidalLookup.Busy(busyUntilMs)
    }

    /**
     * AAC mode's 404 also means no lossless: asked for AAC, the relay still prefers lossless.
     *
     * A [definitive] miss is kept [MISS_TTL_MS]; any other is kept only [SHORT_MISS_TTL_MS]. The
     * row has no TTL column, so a short miss is written BACKDATED — stamped
     * `now − (MISS_TTL_MS − SHORT_MISS_TTL_MS)` — and [cachedMiss]'s one 14-day check expires it
     * 6 h from now. No schema migration, and [TidalMatchDao.deleteOlderThan] (30-day prune) is
     * unaffected: the backdated stamp is still well inside it.
     */
    private suspend fun stampMiss(match: TidalMatchEntity, allowAac: Boolean, definitive: Boolean = true) {
        val now = nowMs()
        val at = if (definitive) now else now - (MISS_TTL_MS - SHORT_MISS_TTL_MS)
        writeCache(if (allowAac) match.copy(aacMissAt = at, losslessMissAt = at) else match.copy(losslessMissAt = at))
    }

    private fun cachedMiss(c: TidalMatchEntity?, allowAac: Boolean, now: Long): Boolean {
        c ?: return false
        if (c.tidalId == null) return now - c.searchedAt < MISS_TTL_MS
        if (now - c.searchedAt >= MATCH_TTL_MS) return false
        val missAt = (if (allowAac) c.aacMissAt else c.losslessMissAt) ?: return false
        return now - missAt < MISS_TTL_MS
    }

    private suspend fun readCache(key: String): TidalMatchEntity? =
        runCatching { matchDao.get(key) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "cache read failed: ${it.javaClass.simpleName}") }
            .getOrNull()

    private suspend fun writeCache(e: TidalMatchEntity) {
        runCatching {
            matchDao.upsert(e)
            if (!pruned) {
                pruned = true
                matchDao.deleteOlderThan(nowMs() - MATCH_TTL_MS)
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "cache write failed: ${it.javaClass.simpleName}") }
    }

    private fun toStream(s: BitchordStreamResponse, match: TidalMatchEntity, allowAac: Boolean): BitchordStream? {
        val url = s.url.toHttpUrlOrNull() ?: return null
        // Defensive: upstream already refuses lossy, but a future server change
        // must not slip AAC into a rung the UI labels lossless.
        val aac = allowAac && s.codec.equals("aac", ignoreCase = true)
        if (!aac && !s.codec.equals("flac", ignoreCase = true)) {
            Log.d(TAG, "non-flac codec '${s.codec}' for ${match.tidalId}; skipping")
            return null
        }
        val isDash = s.manifest.equals("dash", ignoreCase = true)
        Log.d(TAG, "resolved ${match.tidalId} conf=${match.confidence} dash=$isDash ${s.bitDepth}/${s.sampleRate}")
        return BitchordStream(
            url = url.toString(),
            isDash = isDash,
            bitDepth = s.bitDepth,
            sampleRateHz = s.sampleRate,
            bitrateKbps = s.bitrate,
            coverArtUrl = match.artwork,
            expiresAtMs = expiryFor(url, s.expiresAt),
            confidence = match.confidence,
            trackId = match.tidalId,
            codec = if (aac) "aac" else "flac",
        )
    }

    /**
     * The URL's lifetime: the server's signed `exp` (epoch seconds) when present,
     * capped at [MAX_TTL_MS] either way. The cap matters for DASH: the MPD names
     * Tidal CDN segment URLs whose own signatures outlive neither the MPD's nor an
     * hour, and an unsigned (open-mode) server gives no `exp` at all.
     *
     * [expiresAtSec] is the body's `expiresAt` (a progressive Tidal CDN URL, which has no
     * `exp` of ours): it lowers the result to [EXPIRY_MARGIN_MS] before Tidal's own expiry,
     * so a cached URL is refreshed before it 403s mid-track.
     */
    private fun expiryFor(url: HttpUrl, expiresAtSec: Long? = null): Long {
        val now = nowMs()
        val signed = url.queryParameter("exp")?.toDoubleOrNull()?.toLong()?.times(1000L)
        val cap = minOf(now + MAX_TTL_MS, signed ?: Long.MAX_VALUE)
        val upstream = expiresAtSec?.takeIf { it > 0 }?.let { it * 1000L - EXPIRY_MARGIN_MS } ?: return cap
        return minOf(cap, upstream)
    }

    private fun confidence(query: TrackQuery, t: BitchordTrack): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = t.title,
            candArtist = t.artist,
            candIsrc = t.isrc,
            candDurationSec = t.duration,
            candStreamable = true,
        )

    /** One relay answer, sorted by what the caller must do about it. */
    private sealed interface Reply {
        data class Ok(val body: String) : Reply
        /**
         * 404. [definitive] = `lossless_unavailable` (no lossless for this track with this
         * client; the relay itself caches it 5 days). `not_available` — which the relay trusts
         * only 6 h, as it can be a transient refusal — or an unreadable body is not.
         */
        data class NotFound(val definitive: Boolean) : Reply
        /** 503 (no pooled account free) or 429 (slow down): back off until [untilMs] (from Retry-After). */
        data class Busy(val untilMs: Long) : Reply
        /** 429 `daily_lossless_cap`: the day's lossless allowance is used up. Never a miss, never busy. */
        data object Spent : Reply
        /** Anything else (5xx, other 4xx): transient, never cached as a miss. */
        data class Error(val code: Int) : Reply
    }

    /**
     * One GET. With [signPayload] (a `/stream` to the owner's relay) the request carries the
     * install headers ([RelaySigner], message `<install>:td:<numeric id>:<ts>`) and a stored
     * supporter token; a 401 that names the relay's time re-signs once on that clock, like a
     * Qobuz mint.
     */
    private suspend fun get(url: HttpUrl, token: String?, signPayload: String? = null): Reply {
        var mayRetrySkew = true
        while (true) {
            val b = Request.Builder().url(url).get()
                .header("Accept", "application/json")
                .apply { if (token != null) header("X-Access-Token", token) }
            val signed = signPayload != null && signer.sign(b, signPayload, nowMs())
            if (signPayload != null) signer.entitle(b)
            val reply = httpClient.newCall(b.build()).execute().use { r ->
                if (r.code == 401 && signed && mayRetrySkew && signer.adoptServerTime(r, nowMs())) {
                    null
                } else {
                    reply(r)
                }
            }
            if (reply != null) return reply
            mayRetrySkew = false
        }
    }

    /** Sorts one relay response into a [Reply]. */
    private fun reply(r: okhttp3.Response): Reply {
        // Every answer may carry the day's lossless count.
        quota.onResponse(r)
        if (!r.isSuccessful) {
            // Code only: the path may carry the access token as its prefix.
            Log.d(TAG, "HTTP ${r.code}")
            val now = nowMs()
            return when (r.code) {
                404 -> Reply.NotFound(definitive = errorCode(r) == "lossless_unavailable")
                // The relay says how long: 15 s when only seat pacing blocks, 30 s for its
                // per-network limiter, 300 / 600 s when capped or paused. A flat 5 min turned
                // a 15 s pacing blip into a device-wide Tidal blackout.
                503 -> Reply.Busy(now + retryAfterMs(r, BUSY_MIN_RETRY_AFTER_MS, BUSY_MAX_RETRY_AFTER_MS))
                // The daily lossless cap is this install's allowance, not a busy pool: AAC and
                // other installs' traffic are unaffected, so it must not open the busy circuit.
                429 -> if (boundedBody(r)?.let { quota.onCapBody(it, r.header("Retry-After")?.trim()?.toLongOrNull()) } == true) {
                    Reply.Spent
                } else {
                    // network_daily_cap answers Retry-After 3600, hence the 1 h ceiling.
                    Reply.Busy(now + retryAfterMs(r, MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS))
                }
                else -> Reply.Error(r.code)
            }
        }
        val body = r.body ?: throw IOException("empty body")
        if (body.contentLength() > MAX_BODY_BYTES) throw IOException("body too large")
        return Reply.Ok(body.string())
    }

    /** The `error` code of a non-2xx JSON body, or null when absent, oversized or unparseable. */
    private fun errorCode(r: okhttp3.Response): String? = runCatching {
        val text = boundedBody(r) ?: return null
        json.decodeFromString<BitchordErrorResponse>(text).error.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** A non-2xx body as text, or null when absent, oversized or unreadable. */
    private fun boundedBody(r: okhttp3.Response): String? = runCatching {
        val body = r.body ?: return null
        if (body.contentLength() > MAX_BODY_BYTES) return null
        body.string()
    }.getOrNull()

    /** Retry-After (seconds) clamped to [[min], [max]]; absent or junk (an HTTP-date too) → [BUSY_BACKOFF_MS]. */
    private fun retryAfterMs(r: okhttp3.Response, min: Long, max: Long): Long =
        r.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L)?.coerceIn(min, max) ?: BUSY_BACKOFF_MS

    internal companion object {
        const val TAG = "BitchordClient"
        const val TIMEOUT_S = 10L
        const val MAX_BODY_BYTES = 1L * 1024 * 1024
        /** Below upstream's 1 h signature so the cache's margin never serves a dead URL. */
        const val MAX_TTL_MS = 50 * 60_000L
        /** How long before a progressive answer's `expiresAt` the URL is treated as dead. */
        const val EXPIRY_MARGIN_MS = 60_000L
        val LOSSLESS_TIERS = setOf("LOSSLESS", "HI_RES_LOSSLESS")

        /** A confident match is trusted this long before `/search` runs again. */
        const val MATCH_TTL_MS = 30L * 24 * 60 * 60_000
        /** A definitive miss (no match, no lossless, no AAC) is trusted this long. */
        const val MISS_TTL_MS = 14L * 24 * 60 * 60_000
        /** A `/stream` 404 that is not `lossless_unavailable` (the relay trusts `not_available` 6 h). */
        const val SHORT_MISS_TTL_MS = 6L * 60 * 60_000
        /** Pool busy (503 / 429) with no usable Retry-After: leave the relay alone this long. */
        const val BUSY_BACKOFF_MS = 5 * 60_000L
        /** Bounds on a 503's Retry-After: the relay's shortest pacing wait up to a long pause. */
        const val BUSY_MIN_RETRY_AFTER_MS = 15_000L
        const val BUSY_MAX_RETRY_AFTER_MS = 15 * 60_000L
        /**
         * Bounds on a 429's Retry-After, so a junk header can neither hammer nor park us for a day.
         * The ceiling covers `network_daily_cap` (Retry-After 3600).
         */
        const val MIN_RETRY_AFTER_MS = 30_000L
        const val MAX_RETRY_AFTER_MS = 60 * 60_000L
        /** Duration bucket for the title key: a few seconds' drift between catalogs is one song. */
        const val DURATION_BUCKET_S = 10L

        private val WHITESPACE = Regex("\\s+")

        /**
         * The song's cache identity: ISRC when known (the strongest identity across catalogs),
         * else normalised artist|title plus a 10 s duration bucket ("?" when unknown).
         */
        fun cacheKey(q: TrackQuery): String {
            q.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { return "isrc:$it" }
            fun norm(s: String) = s.lowercase().trim().replace(WHITESPACE, " ")
            val bucket = q.durationMs?.takeIf { it > 0 }?.let { ((it / 1000L) + DURATION_BUCKET_S / 2) / DURATION_BUCKET_S }
            return "t:${norm(q.artist)}|${norm(q.title)}|${bucket ?: "?"}"
        }
    }
}
