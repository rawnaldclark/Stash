package com.stash.data.download.lossless.bitchord

import android.util.Log
import com.stash.core.data.db.dao.TidalMatchDao
import com.stash.core.data.db.entity.TidalMatchEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
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
)

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
 *    track has no FLAC (the server rejects lossy itself). `client=media3` pins the
 *    server's DASH-vs-progressive sniffing to "native DASH client" so it never
 *    swaps the Tidal MPD for a Deezer progressive file meant for browsers.
 *  - 503 (`no_account` / `upstream_unavailable`) and 429 mean the shared account pool
 *    is saturated: see [busyUntilMs].
 *  - Auth: when the server sets `ACCESS_TOKEN`, either the base carries it as a
 *    path prefix (`https://host/<token>`, BitChord's own format) or it goes in
 *    `X-Access-Token`. The `/dash/` and `/audio/` URLs it hands back are HMAC-signed
 *    with an `exp` (1 h) and need no token.
 *
 * Request budget: every `/search` and `/stream` spends quota on a small shared pool of
 * Tidal accounts, so answers are cached per song in Room ([TidalMatchDao]; matches 30 days,
 * misses 14 days — never the stream URL itself), concurrent identical lookups share one
 * request, and a busy pool pauses every caller on this device for a few minutes.
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
        prefs.bitchordEndpointNow()?.let { return TidalRelay(it, prefs.bitchordAccessTokenNow()) }
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
     *  3. [cacheOnly] (speculative callers) → [TidalLookup.NeedsRelay], no request;
     *  4. a fresh cached `td:` match → `/stream` only, no `/search`;
     *  5. else `/search` + `/stream`, and the outcome is cached.
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
                when (val r = get(streamUrl, token)) {
                    is Reply.Ok -> {
                        val stream = toStream(json.decodeFromString<BitchordStreamResponse>(r.body), match, allowAac)
                        if (stream == null) {
                            stampMiss(match, allowAac)
                            TidalLookup.NoMatch
                        } else {
                            // The relay prefers lossless, so an AAC answer means there is none.
                            if (stream.codec == "aac") writeCache(match.copy(losslessMissAt = nowMs()))
                            TidalLookup.Found(stream)
                        }
                    }
                    // lossless_unavailable / not_available: a real answer about this song.
                    Reply.NotFound -> {
                        stampMiss(match, allowAac)
                        TidalLookup.NoMatch
                    }
                    is Reply.Busy -> busy(r.untilMs)
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

    /** AAC mode's 404 also means no lossless: asked for AAC, the relay still prefers lossless. */
    private suspend fun stampMiss(match: TidalMatchEntity, allowAac: Boolean) {
        val now = nowMs()
        writeCache(if (allowAac) match.copy(aacMissAt = now, losslessMissAt = now) else match.copy(losslessMissAt = now))
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
            expiresAtMs = expiryFor(url),
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
     */
    private fun expiryFor(url: HttpUrl): Long {
        val now = nowMs()
        val cap = now + MAX_TTL_MS
        val exp = url.queryParameter("exp")?.toDoubleOrNull()?.toLong()?.times(1000L) ?: return cap
        return minOf(exp, cap)
    }

    private fun confidence(query: TrackQuery, t: BitchordTrack): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = t.title,
            candArtist = t.artist,
            candIsrc = t.isrc,
            candDurationSec = t.duration,
            candStreamable = true,
            candAlbum = t.album.takeIf { it.isNotBlank() },
        )

    /** One relay answer, sorted by what the caller must do about it. */
    private sealed interface Reply {
        data class Ok(val body: String) : Reply
        /** 404: the relay's definitive "no such song / no FLAC / no AAC". */
        data object NotFound : Reply
        /** 503 (no pooled account free) or 429 (slow down): back off until [untilMs]. */
        data class Busy(val untilMs: Long) : Reply
        /** Anything else (5xx, other 4xx): transient, never cached as a miss. */
        data class Error(val code: Int) : Reply
    }

    private fun get(url: HttpUrl, token: String?): Reply {
        val req = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .apply { if (token != null) header("X-Access-Token", token) }
            .build()
        httpClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                // Code only: the path may carry the access token as its prefix.
                Log.d(TAG, "HTTP ${r.code}")
                val now = nowMs()
                return when (r.code) {
                    404 -> Reply.NotFound
                    503 -> Reply.Busy(now + BUSY_BACKOFF_MS)
                    429 -> {
                        val after = r.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L)
                            ?.coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS) ?: BUSY_BACKOFF_MS
                        Reply.Busy(now + after)
                    }
                    else -> Reply.Error(r.code)
                }
            }
            val body = r.body ?: throw IOException("empty body")
            if (body.contentLength() > MAX_BODY_BYTES) throw IOException("body too large")
            return Reply.Ok(body.string())
        }
    }

    internal companion object {
        const val TAG = "BitchordClient"
        const val TIMEOUT_S = 10L
        const val MAX_BODY_BYTES = 1L * 1024 * 1024
        /** Below upstream's 1 h signature so the cache's margin never serves a dead URL. */
        const val MAX_TTL_MS = 50 * 60_000L
        val LOSSLESS_TIERS = setOf("LOSSLESS", "HI_RES_LOSSLESS")

        /** A confident match is trusted this long before `/search` runs again. */
        const val MATCH_TTL_MS = 30L * 24 * 60 * 60_000
        /** A definitive miss (no match, no lossless, no AAC) is trusted this long. */
        const val MISS_TTL_MS = 14L * 24 * 60 * 60_000
        /** Pool busy (503 / no_account): leave the relay alone this long. */
        const val BUSY_BACKOFF_MS = 5 * 60_000L
        /** Bounds on a 429's Retry-After, so a junk header can neither hammer nor park us for a day. */
        const val MIN_RETRY_AFTER_MS = 30_000L
        const val MAX_RETRY_AFTER_MS = 15 * 60_000L
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
