package com.stash.data.download.lossless.qbdlx

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.searchTerms
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async

/**
 * [LosslessSource] backed by the Qobuz catalog through the DIRECT Qobuz API.
 * Catalog search is tokenless (web app_id); the file URL comes from
 * [QbdlxFileUrlRouter] (BYO login → custom endpoint → config relays);
 * [LosslessAvailability] gates both enablement and every resolve so an
 * unavailable file-URL path costs zero catalog HTTP.
 *
 * Keeps [com.stash.data.download.lossless.qobuz.QobuzSource]'s resolve /
 * resolveImmediate split: background [resolve] respects the rate limiter +
 * breaker; user-initiated [resolveImmediate] bypasses both but still reports
 * outcomes so the breaker state stays accurate.
 *
 * The [AggregatorRateLimiter] breaker tracks CATALOG health only: a search
 * success resets the counter before the file-URL call, a dead credential arrives
 * as a [QbdlxResolveResult.TokenDead] value, and relay outages are the relay
 * client's per-base cooldowns. Only repeated catalog failures can open it.
 */
@Singleton
class QbdlxQobuzSource @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val router: QbdlxFileUrlRouter,
    private val availability: LosslessAvailability,
    private val rateLimiter: AggregatorRateLimiter,
    private val losslessPrefs: LosslessSourcePreferences,
) : LosslessSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Direct Qobuz"

    override suspend fun isEnabled(): Boolean =
        !rateLimiter.stateOf(id).isCircuitBroken && availability.qbdlxEnabledNow()

    /** Streaming gate: same predicate without the breaker (a user tap bypasses it). */
    suspend fun isEnabledForStreaming(): Boolean = availability.qbdlxEnabledNow()

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
        if (!isEnabled()) return null
        return resolveInternal(query, bypassRateLimit = bypassRateLimit, requestedQuality = null)
    }

    /** User-initiated immediate resolve for the streaming path. Skips the token bucket AND the breaker. */
    suspend fun resolveImmediate(query: TrackQuery, requestedQuality: Int? = null): SourceResult? {
        if (!isEnabledForStreaming()) return null
        return resolveInternal(query, bypassRateLimit = true, requestedQuality = requestedQuality)
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun resolveInternal(query: TrackQuery, bypassRateLimit: Boolean, requestedQuality: Int?): SourceResult? {
        // Availability FIRST: a cooled relay / dead login must cost zero catalog HTTP.
        if (!availability.fileUrlAvailableNow()) {
            Log.d(TAG, "no file-url path available right now — skipping '${query.title}'")
            return null
        }
        val (track, conf) = search(query, bypassRateLimit) ?: return null
        // Honor the user's quality tier on the download path (CD/Hi-Res/Max → qobuzCode 6/7/27); the
        // stream path passes the streaming tier explicitly.
        val formatId = requestedQuality ?: losslessPrefs.qualityTierNow().qobuzCode
        val result = callLimited(bypassRateLimit) { router.getFileUrl(track.id, formatId) } ?: return null
        return when (result) {
            is QbdlxResolveResult.Ok -> build(track, conf, result)
            QbdlxResolveResult.TokenDead -> { Log.w(TAG, "connected account dead for '${query.title}'"); null }
            QbdlxResolveResult.RegionLocked -> { Log.d(TAG, "not streamable/region-locked: '${query.title}'"); null }
        }
    }

    /** Tokenless catalog search + match. Null when nothing crosses threshold or the catalog rejects us. */
    private suspend fun search(query: TrackQuery, bypassRateLimit: Boolean): Pair<QbdlxTrack, Float>? = supervisorScope {
        // Every term is asked at once; answers are read in priority order, so the
        // first term still outranks the second. A miss costs the slowest search,
        // not the sum (2026-09-06: 1.1 s + 0.9 s sequential on every catalog gap).
        val answers = query.searchTerms().map { term ->
            async { runCatching { callLimited(bypassRateLimit) { apiClient.search(term) } } }
        }
        try {
            for (answer in answers) {
                val candidates = answer.await().getOrThrow() ?: continue
                val match = candidates
                    .map { it to confidence(query, it) }
                    .filter { it.second >= QobuzCandidateMatcher.MIN_CONFIDENCE }
                    .maxByOrNull { it.second }
                if (match != null) return@supervisorScope match
            }
            null
        } catch (e: QbdlxAuthException) {
            // Catalog 401 after the client's own self-heal: nothing to rotate to.
            Log.w(TAG, "catalog auth-failed (${e.status}) even under the web app_id")
            null
        } finally {
            answers.forEach { it.cancel() }
        }
    }

    private fun build(track: QbdlxTrack, conf: Float, ok: QbdlxResolveResult.Ok): SourceResult {
        val img = track.album?.image
        val art = img?.large ?: img?.thumbnail ?: img?.small
        return SourceResult(
            sourceId = id,
            downloadUrl = ok.url,
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                codec = ok.codec,
                bitrateKbps = 0, // FLAC is VBR; canonical value comes post-download.
                sampleRateHz = ok.sampleRateHz,
                bitsPerSample = ok.bitDepth,
            ),
            confidence = conf,
            sourceTrackId = track.id.toString(),
            coverArtUrl = art,
        )
    }

    private fun confidence(query: TrackQuery, candidate: QbdlxTrack): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = candidate.title,
            candArtist = candidate.performer?.name.orEmpty(),
            candIsrc = candidate.isrc,
            candDurationSec = candidate.duration,
            candStreamable = candidate.streamable,
        )

    /**
     * Wraps an API call with rate-limiter bookkeeping (mirrors
     * [com.stash.data.download.lossless.qobuz.QobuzSource]'s `callLimited`).
     * Returns null on rate-limit denial / api error (already reported) — and
     * also when the block itself returns null (the router having no path), which
     * is not a health failure. [QbdlxAuthException] is RETHROWN — only [search]
     * can raise it now (the router converts a 401 into
     * [QbdlxResolveResult.TokenDead]) and a dead credential must not trip the
     * breaker.
     */
    private suspend fun <T> callLimited(
        bypassRateLimit: Boolean,
        block: suspend () -> T,
    ): T? {
        if (!bypassRateLimit && !rateLimiter.acquire(id)) return null
        return try {
            block().also { rateLimiter.reportSuccess(id) }
        } catch (e: QbdlxAuthException) {
            throw e // caller's concern; do NOT report (not a health failure)
        } catch (e: CancellationException) {
            throw e // never swallow cancellation as a failure
        } catch (e: QbdlxApiException) {
            if (e.status == 429) rateLimiter.reportRateLimited(id) else rateLimiter.reportFailure(id)
            Log.w(TAG, "qbdlx api call failed status=${e.status}")
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "qbdlx call threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    companion object {
        const val SOURCE_ID = "qbdlx_qobuz"
        private const val TAG = "QbdlxSource" // no "Qobuz" — keeps the source out of shared logcat diagnostics
    }
}
