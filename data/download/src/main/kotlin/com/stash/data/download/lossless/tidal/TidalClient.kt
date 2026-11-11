package com.stash.data.download.lossless.tidal

import android.util.Log
import com.stash.core.data.db.dao.AddonRefDao
import com.stash.core.data.db.entity.AddonRefEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.LegacyRelayWire
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.RelaySigner
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The Tidal rung's client: the second lossless rung of the streaming chain (after Qobuz, before
 * the lossy sources), the Tidal download source and the Tidal AAC resolver all ask it. A user's own
 * server with none of the user's own Qobuz set up goes ahead of Qobuz
 * ([com.stash.data.download.lossless.LosslessAvailability.ownTidalFirst]).
 *
 * **Two wires, one policy** (add-ons phase 2 §5.1). Everything here is policy: the busy circuit,
 * in-flight dedupe, the cache-only look-ahead, the AAC-only stamps, the miss stamps, the spent
 * day, the cache key and the pick. The network half is a [TidalWire] that [TidalWires] picks per
 * lookup: the legacy BitChord relay ([LegacyRelayWire], today's wire, unchanged) or the first-party
 * Tidal add-on's v1 face ([AddonV1Wire]). Both read and write the same cache rows, so the config
 * switch between them never empties it, and both are scored here by the same [QobuzCandidateMatcher]
 * on the same neutral rows: the same fixtures give the same [TidalLookup] through either
 * (`TidalWireParityTest`).
 *
 * Request budget: every `/search` and `/stream` spends quota on a small shared pool of Tidal
 * accounts, so answers are cached per song in Room (`addon_refs` under [REFS_ADDON_ID], through
 * [AddonRefDao]; matches 30 days, misses 14 days (6 h for a `/stream` miss that is not
 * definitive), never the stream URL itself), concurrent identical lookups share one request, and a
 * busy pool pauses every caller on this device for as long as the server asks.
 *
 * Matching reuses [QobuzCandidateMatcher], the scorer the Qobuz rung uses, on neutral fields, so
 * ISRC equality, title/artist normalisation, version words and the duration penalty all behave
 * identically across the two lossless rungs.
 */
@Singleton
class TidalClient @Inject constructor(
    private val prefs: LosslessSourcePreferences,
    private val refs: AddonRefDao,
    private val quota: LosslessQuota,
    private val wires: TidalWires,
) {
    /**
     * Non-suspending view of [isConfigured] for the player's media-source factory, which runs on
     * the playback thread and must not block on DataStore ([TidalWires.configured]).
     */
    val configured: StateFlow<Boolean> get() = wires.configured

    /** Tests: the legacy wire, for its HTTP client seam. */
    internal val legacyWire: LegacyRelayWire get() = wires.legacy

    /** True when a server is known (the user's own, the Tidal add-on, or the signed config's relay); the rung is off otherwise. */
    suspend fun isConfigured(): Boolean = wires.route() != null

    /**
     * Device-wide "pool busy" circuit, epoch-ms. A 503 (`no_account`: every pooled Tidal
     * account is capped or cooling) or a 429 opens it, and until it closes NO request goes to
     * Tidal from any path: streaming, downloads, sweeps. The user's own server giving no answer
     * opens it too ([ownServerUnreachable]).
     */
    @Volatile private var busyUntilMs: Long = 0L

    /**
     * The user's own server timing out or refusing in a row, since it last answered: sets how long
     * [ownServerUnreachable] pauses it. Nothing else reads it.
     */
    @Volatile private var ownServerMisses = 0

    /**
     * Servers that refused this app build ([RelaySigner.APP_TOO_OLD]), by [TidalWire.serverKey],
     * epoch-ms. Only that server is skipped, by every path, for [RelaySigner.APP_TOO_OLD_BACKOFF_MS].
     */
    private val appTooOldUntilMs = ConcurrentHashMap<String, Long>()

    /** Wall clock; tests drive it. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Shared lookups outlive any one caller's cancellation, or dedupe would be pointless. */
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-flight lookups by [cacheKey] + mode; two callers for one song share one request. */
    private val inFlight = ConcurrentHashMap<String, Deferred<TidalLookup>>()

    @Volatile private var pruned = false

    /** True while the pool-busy circuit is open (no Tidal calls). */
    fun isBusy(): Boolean = nowMs() < busyUntilMs

    /**
     * The stream for [query], or null on anything else (miss, busy, error, off). Kept for
     * the streaming rung, which only needs "play this or move on"; see [lookup].
     */
    suspend fun resolve(query: TrackQuery, allowAac: Boolean = false): TidalStream? =
        (lookup(query, allowAac) as? TidalLookup.Found)?.stream

    /**
     * Find [query] on Tidal, spending as little relay quota as possible:
     *  1. pool-busy circuit open → [TidalLookup.Busy], no request;
     *  2. a fresh cached miss for this mode → [TidalLookup.NoMatch], no request;
     *  3. any lookup on Stash's Tidal with the day's allowance spent → [TidalLookup.Spent],
     *     no request: AAC counts toward the allowance like FLAC, and past the limit the server
     *     answers neither (a user's own server is not the owner's allowance);
     *  4. [cacheOnly] (speculative callers) → [TidalLookup.NeedsRelay], no request;
     *  5. a fresh cached `td:` match → `/stream` only, no `/search`;
     *  6. else `/search` + `/stream`, and the outcome is cached.
     * Concurrent identical lookups share one request.
     *
     * [allowAac]: streaming / AAC download only. CD-only tracks never come back lossless with
     * the relay's Tidal client, so the server can answer with Tidal's 320 kbps AAC instead.
     * Lossless downloads leave it off so AAC is never saved as FLAC. A cached lossless miss does
     * not block an AAC lookup: each mode keeps its own miss stamp.
     *
     * [aacOnly]: the AAC 320 download mode. Tidal's AAC for ANY track, even one it has in FLAC.
     * Implies [allowAac]. A cached AAC miss skips it; its own miss is not cached (see [fetch]).
     */
    suspend fun lookup(
        query: TrackQuery,
        allowAac: Boolean = false,
        cacheOnly: Boolean = false,
        aacOnly: Boolean = false,
    ): TidalLookup {
        val aac = allowAac || aacOnly
        val now = nowMs()
        if (now < busyUntilMs) return TidalLookup.Busy(busyUntilMs)
        val key = cacheKey(query)
        if (cachedMiss(readCache(key), aac, now)) return TidalLookup.NoMatch
        // AAC mode's own "no AAC" stamp: its retry sweep skips the song for free.
        if (aacOnly && aacOnlyMissed(key, now)) return TidalLookup.NoMatch
        if (quota.isSpent() && !wires.ownServerSet()) return TidalLookup.Spent(quota.resetsAtMs())
        if (cacheOnly) return TidalLookup.NeedsRelay
        val wire = wires.current { nowMs() } ?: return TidalLookup.Off
        appTooOldUntilMs[wire.serverKey]?.let { until ->
            if (now < until) return TidalLookup.Busy(until)
            appTooOldUntilMs.remove(wire.serverKey, until)
        }

        val flightKey = "$key|aac=${if (aacOnly) "only" else aac.toString()}"
        inFlight[flightKey]?.let { return it.await() }
        val fresh = lookupScope.async(start = CoroutineStart.LAZY) { fetch(wire, query, key, aac, aacOnly) }
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

    /** The network half of [lookup], over [wire]; runs once per song and mode at a time. */
    private suspend fun fetch(
        wire: TidalWire,
        query: TrackQuery,
        key: String,
        allowAac: Boolean,
        aacOnly: Boolean = false,
    ): TidalLookup =
        withContext(Dispatchers.IO) {
            try {
                // Re-read: a lookup that finished just before this one started may have searched.
                val cached = readCache(key)?.takeIf { it.remoteId != null && nowMs() - it.resolvedAt < MATCH_TTL_MS }
                val match: AddonRefEntity = cached ?: run {
                    val rows = when (val r = heard(wire, wire.search("${query.artist} ${query.title}".trim()))) {
                        is WireReply.Ok -> r.value
                        else -> return@withContext outcome(wire, r)
                    }
                    val lossless = rows.filter { it.id.startsWith("td:") && it.lossless }
                    // The row's own recording when Tidal lists it. The search is by words, so a
                    // song's twins both come back, and an ISRC match scores 0.95 against 1.0 for a
                    // twin that matches on words and length: the clean twin beat the explicit
                    // recording the row names. The Qobuz sources look the ISRC up first.
                    val sameRecording = query.isrc?.takeIf { it.isNotBlank() }
                        ?.let { isrc -> lossless.filter { it.isrc.equals(isrc, ignoreCase = true) } }
                        .orEmpty()
                    val best = QobuzCandidateMatcher.pickBest(
                        query,
                        sameRecording.ifEmpty { lossless }.map { it to confidence(query, it) },
                        explicit = { it.explicit },
                    ) { it.durationSec }
                    if (best == null) {
                        Log.d(TAG, "no confident match among ${rows.size} for '${query.artist} - ${query.title}'")
                        writeCache(AddonRefEntity(REFS_ADDON_ID, key, remoteId = null, resolvedAt = nowMs()))
                        return@withContext TidalLookup.NoMatch
                    }
                    val (t, conf) = best
                    AddonRefEntity(
                        addonId = REFS_ADDON_ID,
                        songKey = key,
                        remoteId = t.id,
                        title = t.title,
                        artist = t.artist,
                        artwork = t.artwork?.takeIf { it.isNotBlank() },
                        confidence = conf,
                        resolvedAt = nowMs(),
                    ).also { writeCache(it) }
                }
                val id = match.remoteId ?: return@withContext TidalLookup.NoMatch
                val mode = when {
                    aacOnly -> TidalStreamMode.AAC_ONLY
                    allowAac -> TidalStreamMode.LOSSLESS_OR_AAC
                    else -> TidalStreamMode.LOSSLESS
                }
                when (val r = heard(wire, wire.stream(id, mode, match.title, match.artist))) {
                    is WireReply.Ok -> {
                        val stream = toStream(r.value, match, allowAac, wire)
                        if (stream == null) {
                            if (aacOnly) stampAacOnlyMiss(key, definitive = true) else stampMiss(match, allowAac)
                            TidalLookup.NoMatch
                        } else {
                            // The server prefers lossless, so an AAC answer means there is none —
                            // unless the day's allowance is spent: then AAC is all it would serve.
                            // An aac=only answer is AAC whatever Tidal has, so it says nothing.
                            if (!aacOnly && stream.codec == "aac" && !quota.isSpent()) {
                                writeCache(match.copy(losslessMissAt = nowMs()))
                            }
                            TidalLookup.Found(stream)
                        }
                    }
                    // A definitive miss is a real answer about this song; any other may be a
                    // transient refusal: a short miss, see [stampMiss].
                    is WireReply.NotFound -> {
                        // Not for aac=only: the shared AAC stamp would also stop streaming's
                        // aac=1, which can still get FLAC for this song.
                        if (aacOnly) {
                            stampAacOnlyMiss(key, definitive = r.definitive)
                        } else {
                            stampMiss(match, allowAac, definitive = r.definitive)
                        }
                        TidalLookup.NoMatch
                    }
                    else -> outcome(wire, r)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class only: an exception's text can carry the URL, and the base may hold the
                // access token in its path.
                Log.w(TAG, "resolve failed: ${e.javaClass.simpleName}")
                TidalLookup.Failed
            }
        }

    /** A reply from the user's own server: an answer starts its unreachable count over, no answer pauses it. */
    private fun <T> heard(wire: TidalWire, reply: WireReply<T>): WireReply<T> {
        if (!wire.owner) {
            if (reply == WireReply.Unreachable) ownServerUnreachable() else ownServerMisses = 0
        }
        return reply
    }

    /** What a reply that is neither an answer nor a miss means for the caller. */
    private fun outcome(wire: TidalWire, r: WireReply<*>): TidalLookup = when (r) {
        is WireReply.Busy -> busy(r.untilMs)
        WireReply.Spent -> TidalLookup.Spent(quota.resetsAtMs())
        WireReply.AppTooOld -> appTooOld(wire)
        else -> TidalLookup.Failed
    }

    /** [wire]'s server refused this app build: it is left alone for [RelaySigner.APP_TOO_OLD_BACKOFF_MS]. */
    private fun appTooOld(wire: TidalWire): TidalLookup {
        val until = nowMs() + RelaySigner.APP_TOO_OLD_BACKOFF_MS
        appTooOldUntilMs[wire.serverKey] = until
        Log.w(TAG, "relay refuses this app build; pausing it for ${RelaySigner.APP_TOO_OLD_BACKOFF_MS / 60_000} min")
        return TidalLookup.Busy(until)
    }

    private fun busy(untilMs: Long, why: String = "relay pool busy"): TidalLookup {
        if (untilMs > busyUntilMs) {
            busyUntilMs = untilMs
            Log.i(TAG, "$why; pausing Tidal for ${(untilMs - nowMs()) / 1000}s")
        }
        return TidalLookup.Busy(busyUntilMs)
    }

    /**
     * The user's own server timed out or refused the connection: a box that is off, or a home
     * address away from home. Every path then skips it through the busy circuit, for
     * [OWN_SERVER_PAUSE_MS], doubling while it stays unreachable, up to [OWN_SERVER_MAX_PAUSE_MS];
     * any answer from it starts over ([heard]). Stash's own Tidal is not paused this way: its
     * outages are the owner's to fix, not the user's.
     */
    private fun ownServerUnreachable() {
        val pause = (OWN_SERVER_PAUSE_MS shl ownServerMisses.coerceAtMost(3)).coerceAtMost(OWN_SERVER_MAX_PAUSE_MS)
        ownServerMisses++
        busy(nowMs() + pause, "own Tidal server unreachable")
    }

    /**
     * AAC mode's miss also means no lossless: asked for AAC, the server still prefers lossless.
     *
     * A [definitive] miss is kept [MISS_TTL_MS]; any other is kept only [SHORT_MISS_TTL_MS]. The
     * row has no TTL column, so a short miss is written BACKDATED — stamped
     * `now − (MISS_TTL_MS − SHORT_MISS_TTL_MS)` — and [cachedMiss]'s one 14-day check expires it
     * 6 h from now. [AddonRefDao.deleteOlderThan] (30-day prune) is unaffected: the backdated
     * stamp is still well inside it.
     *
     * A stamp only ever moves later: an AAC lookup's short miss also stamps lossless, and must
     * not cut a definitive lossless miss already cached (14 days) down to 6 h.
     */
    private suspend fun stampMiss(match: AddonRefEntity, allowAac: Boolean, definitive: Boolean = true) {
        val now = nowMs()
        val at = if (definitive) now else now - (MISS_TTL_MS - SHORT_MISS_TTL_MS)
        val lossless = maxOf(match.losslessMissAt ?: at, at)
        writeCache(
            if (allowAac) match.copy(aacMissAt = maxOf(match.aacMissAt ?: at, at), losslessMissAt = lossless)
            else match.copy(losslessMissAt = lossless),
        )
    }

    /**
     * AAC mode's (`aac=only`) miss, on a stamp of its own: a row under [AAC_ONLY_KEY_PREFIX] +
     * the song's key, never read by streaming's `aac=1` or a lossless lookup (an `aac=only` miss
     * says nothing about either). A definitive miss is kept [AAC_ONLY_MISS_TTL_MS], any other
     * [SHORT_MISS_TTL_MS]; the 30-day prune clears it like any row.
     */
    private suspend fun stampAacOnlyMiss(key: String, definitive: Boolean) {
        val now = nowMs()
        val at = if (definitive) now else now - (AAC_ONLY_MISS_TTL_MS - SHORT_MISS_TTL_MS)
        writeCache(AddonRefEntity(REFS_ADDON_ID, AAC_ONLY_KEY_PREFIX + key, remoteId = null, resolvedAt = at))
    }

    private suspend fun aacOnlyMissed(key: String, now: Long): Boolean =
        readCache(AAC_ONLY_KEY_PREFIX + key)?.let { now - it.resolvedAt < AAC_ONLY_MISS_TTL_MS } == true

    private fun cachedMiss(c: AddonRefEntity?, allowAac: Boolean, now: Long): Boolean {
        c ?: return false
        if (c.remoteId == null) return now - c.resolvedAt < MISS_TTL_MS
        if (now - c.resolvedAt >= MATCH_TTL_MS) return false
        val missAt = (if (allowAac) c.aacMissAt else c.losslessMissAt) ?: return false
        return now - missAt < MISS_TTL_MS
    }

    private suspend fun readCache(key: String): AddonRefEntity? =
        runCatching { refs.get(REFS_ADDON_ID, key) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "cache read failed: ${it.javaClass.simpleName}") }
            .getOrNull()

    private suspend fun writeCache(e: AddonRefEntity) {
        runCatching {
            refs.upsert(e)
            if (!pruned) {
                pruned = true
                refs.deleteOlderThan(REFS_ADDON_ID, nowMs() - MATCH_TTL_MS)
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "cache write failed: ${it.javaClass.simpleName}") }
    }

    private fun toStream(s: TidalWireStream, match: AddonRefEntity, allowAac: Boolean, wire: TidalWire): TidalStream? {
        val url = s.url.toHttpUrlOrNull() ?: return null
        // Defensive: the server already refuses lossy when not asked for it, but a future change
        // must not slip AAC into a rung the UI labels lossless.
        val aac = allowAac && s.codec.equals("aac", ignoreCase = true)
        if (!aac && !s.codec.equals("flac", ignoreCase = true)) {
            Log.d(TAG, "non-flac codec '${s.codec}' for ${match.remoteId}; skipping")
            return null
        }
        Log.d(TAG, "resolved ${match.remoteId} via ${wire.kind.label} conf=${match.confidence} dash=${s.isDash} ${s.bitDepth}/${s.sampleRate}")
        return TidalStream(
            url = url.toString(),
            isDash = s.isDash,
            bitDepth = s.bitDepth,
            sampleRateHz = s.sampleRate,
            bitrateKbps = s.bitrateKbps,
            coverArtUrl = match.artwork,
            expiresAtMs = expiryFor(url, s.expiresAtSec),
            confidence = match.confidence,
            trackId = match.remoteId,
            codec = if (aac) "aac" else "flac",
        )
    }

    /**
     * The URL's lifetime: the server's signed `exp` (epoch seconds) when present, capped at
     * [MAX_TTL_MS] either way. The cap matters for DASH: the MPD names Tidal CDN segment URLs whose
     * own signatures outlive neither the MPD's nor an hour, and an unsigned (open-mode) server gives
     * no `exp` at all.
     *
     * [expiresAtSec] is the answer's own expiry for a progressive Tidal CDN URL, which has no `exp`
     * of ours: it lowers the result to [EXPIRY_MARGIN_MS] before Tidal's own expiry, so a cached
     * URL is refreshed before it 403s mid-track.
     */
    private fun expiryFor(url: HttpUrl, expiresAtSec: Long? = null): Long {
        val now = nowMs()
        val signed = url.queryParameter("exp")?.toDoubleOrNull()?.toLong()?.times(1000L)
        val cap = minOf(now + MAX_TTL_MS, signed ?: Long.MAX_VALUE)
        val upstream = expiresAtSec?.takeIf { it > 0 }?.let { it * 1000L - EXPIRY_MARGIN_MS } ?: return cap
        return minOf(cap, upstream)
    }

    private fun confidence(query: TrackQuery, t: TidalCandidate): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = t.title,
            candArtist = t.artist,
            candIsrc = t.isrc,
            candDurationSec = t.durationSec,
            candStreamable = true,
            candAlbum = t.album?.takeIf { it.isNotBlank() },
        )

    companion object {
        const val TAG = "TidalClient"

        /** The `addon_refs` add-on id the Tidal rung's rows live under, whichever wire wrote them. */
        const val REFS_ADDON_ID = "tidal"

        const val TIMEOUT_S = 10L
        const val MAX_BODY_BYTES = 1L * 1024 * 1024
        /** Below upstream's 1 h signature so the cache's margin never serves a dead URL. */
        const val MAX_TTL_MS = 50 * 60_000L
        /** How long before a progressive answer's `expiresAt` the URL is treated as dead. */
        const val EXPIRY_MARGIN_MS = 60_000L

        /** A confident match is trusted this long before `/search` runs again. */
        const val MATCH_TTL_MS = 30L * 24 * 60 * 60_000
        /** A definitive miss (no match, no lossless, no AAC) is trusted this long. */
        const val MISS_TTL_MS = 14L * 24 * 60 * 60_000
        /** A `/stream` miss that is not definitive (the relay trusts `not_available` 6 h). */
        const val SHORT_MISS_TTL_MS = 6L * 60 * 60_000

        /** How long AAC mode trusts "Tidal has no AAC of it" before asking again. */
        const val AAC_ONLY_MISS_TTL_MS = 3L * 24 * 60 * 60_000

        /** The cache key prefix of AAC mode's own miss stamp ([stampAacOnlyMiss]). */
        internal const val AAC_ONLY_KEY_PREFIX = "aaconly|"
        /** Pool busy (503 / 429) with no usable Retry-After: leave the server alone this long. */
        const val BUSY_BACKOFF_MS = 5 * 60_000L
        /** Bounds on a 503's Retry-After: the relay's shortest pacing wait up to a long pause. */
        const val BUSY_MIN_RETRY_AFTER_MS = 15_000L
        const val BUSY_MAX_RETRY_AFTER_MS = 15 * 60_000L
        /**
         * The user's own server gave no answer: skip it this long, doubling per miss in a row up to
         * [OWN_SERVER_MAX_PAUSE_MS]. Short at first, so a blip on the network costs little; longer
         * while it stays down, so a tap minutes after the last miss doesn't wait on it again.
         */
        const val OWN_SERVER_PAUSE_MS = 60_000L
        const val OWN_SERVER_MAX_PAUSE_MS = 5 * 60_000L
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
         * else normalised artist|title plus a 10 s duration bucket ("?" when unknown, "~" before
         * it when the length is only a hint, [TrackQuery.durationReliable]); then the version the
         * pick prefers, `|explicit` or `|clean` ([explicitWant]).
         *
         * A hinted length picks differently from an exact one of the same value, so the two don't
         * share an answer. A search tap once asked with a music video's 4:25 as exact, got the
         * compilation copy nearest it, and would otherwise keep being served that pick.
         *
         * The preference is in the key, rather than a bare version prefix, for two reasons:
         *  - [QobuzCandidateMatcher.pickBest] takes the explicit twin unless the row is known
         *    clean, so a clean row and an explicit (or unknown) row with the same words can pick
         *    different Tidal ids and must not share one cached answer;
         *  - keys written before that tie-break (no suffix) no longer match. Their rows are never
         *    read again and go with the 30-day prune.
         * Unchanged by the move to `addon_refs` (v80): the migrated rows keep their keys.
         */
        fun cacheKey(q: TrackQuery): String {
            val want = explicitWant(q)
            q.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { return "isrc:$it|$want" }
            fun norm(s: String) = s.lowercase().trim().replace(WHITESPACE, " ")
            val bucket = q.durationMs?.takeIf { it > 0 }
                ?.let { ((it / 1000L) + DURATION_BUCKET_S / 2) / DURATION_BUCKET_S }
                ?.let { if (q.durationReliable) "$it" else "~$it" }
            return "t:${norm(q.artist)}|${norm(q.title)}|${bucket ?: "?"}|$want"
        }

        /**
         * The twin [cacheKey] is for: "clean" only for a row known to be clean
         * ([TrackQuery.explicit] false), "explicit" otherwise. Unknown reads as explicit, as it
         * does in the pick: the two make the same pick, so they share one cached answer.
         */
        private fun explicitWant(q: TrackQuery): String = if (q.explicit == false) "clean" else "explicit"
    }
}
