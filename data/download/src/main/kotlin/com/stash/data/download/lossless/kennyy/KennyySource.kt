package com.stash.data.download.lossless.kennyy

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessUrlInspector
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.searchTerms
import com.stash.data.download.lossless.qobuz.QobuzApiException
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.download.lossless.qobuz.QobuzQuality
import com.stash.data.download.lossless.qobuz.QobuzSource
import com.stash.data.download.lossless.qobuz.QobuzTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * [LosslessSource] backed by the Qobuz catalog via the kennyy.com.br
 * public proxy (`qobuz.kennyy.com.br/api`). Searches for the requested
 * track, scores candidates by ISRC / title / artist / duration agreement,
 * and resolves the best match to a signed FLAC download URL at the user's
 * preferred quality tier (read from [LosslessSourcePreferences]).
 *
 * kennyy.com.br is a near-clone of qobuz.squid.wtf (same Qobuz-DL
 * Next.js codebase, different operator). Critically: **no captcha gate**
 * — the download-music endpoint is openly accessible without any cookie.
 * As a result this source has no captcha-expired branch and no
 * [com.stash.data.download.lossless.squid.CaptchaExpiredNotifier] dependency.
 *
 * Scoring logic is identical to [QobuzSource]; normalization and
 * similarity helpers are shared directly from [QobuzSource] companion
 * (same module, internal visibility). A v0.9.11 refactor will extract
 * them to a shared helpers object; for now the in-package coupling
 * is accepted.
 */
@Singleton
class KennyySource @Inject constructor(
    private val apiClient: KennyyApiClient,
    private val rateLimiter: AggregatorRateLimiter,
    private val losslessPrefs: LosslessSourcePreferences,
    private val urlInspector: LosslessUrlInspector,
    private val healthGate: LosslessSourceHealthGate,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "Qobuz (kennyy.com.br)"

    /**
     * Set to true by [callLimited] when the most recent call failed due
     * to a network/HTTP/proxy error (NOT a catalog miss). Read by
     * [com.stash.core.media.streaming.KennyyStreamResolver] to disambiguate
     * "request failed" from "no match" before recording the outcome with
     * KennyyHealthMonitor.
     *
     * @Volatile because the flag is written from one coroutine and read
     * from another (resolver runs on the IO dispatcher; reader is the
     * caller scope). resolveImmediate-style callers are serialized by
     * the rate limiter / single-flight contract so concurrent races on
     * the flag itself aren't a concern in practice.
     */
    @Volatile
    var lastResolveFailedNetwork: Boolean = false
        private set

    override suspend fun isEnabled(): Boolean {
        // No credentials gate — the only reason to skip this source is
        // the circuit breaker.
        return !rateLimiter.stateOf(id).isCircuitBroken
    }

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? =
        resolveInternal(query, bypassRateLimit = bypassRateLimit, requestedQuality = null)

    /**
     * User-initiated immediate resolve for the streaming path. Skips
     * BOTH the token bucket AND the circuit breaker. Rationale: a tap
     * is a deliberate user action, infrequent and self-rate-limited
     * (user can only press play so fast). The breaker exists to stop
     * background workers from spamming a sick upstream; it should not
     * stand between a user and their music. If Kennyy is genuinely
     * down, each user-initiated call fails on its own merits and the
     * user notices immediately — vastly better UX than a 30-minute
     * silent dead zone after a transient burst of 5 failures earlier
     * in the session. Background paths ([resolve]) still respect the
     * breaker.
     */
    suspend fun resolveImmediate(
        query: TrackQuery,
        requestedQuality: Int? = null,
    ): SourceResult? =
        resolveInternal(query, bypassRateLimit = true, requestedQuality = requestedQuality)

    private suspend fun resolveInternal(
        query: TrackQuery,
        bypassRateLimit: Boolean,
        requestedQuality: Int?,
    ): SourceResult? {
        lastResolveFailedNetwork = false
        Log.d(TAG, "resolve attempt artist='${query.artist}' title='${query.title}' isrc=${query.isrc ?: "none"}")
        // 1. Search kennyy.com.br for candidates. ISRC is Qobuz's best
        // index key — when we have one, send it as the query directly.
        // Try the full artist credit first (single artists with commas in
        // their NAME still match), then fall back to the PRIMARY artist
        // (before the first comma) — rescues multi-artist credits like
        // "¥$, Kanye West, Ty Dolla $ign" whose full-credit query returns the
        // featured artists' tracks (all scoring 0). ISRC, when present, is
        // tried first. See TrackQuery.searchTerms.
        var found: Pair<QobuzTrack, Float>? = null
        for (term in query.searchTerms()) {
            val searchData = callLimited(bypassRateLimit) { apiClient.search(term) } ?: continue
            val candidates = searchData.tracks?.items.orEmpty()
            if (candidates.isEmpty()) continue

            val scored = candidates.map { it to confidence(query, it) }
            val match = QobuzCandidateMatcher.pickBest(query, scored, explicit = { it.explicit }) { it.duration }
            if (match != null) {
                found = match
                break
            }
            val top = scored.sortedByDescending { it.second }.take(3)
            Log.d(
                TAG,
                "below_confidence (<$MIN_CONFIDENCE) term='$term' for '${query.artist} - ${query.title}': " +
                    top.joinToString(", ") { (c, s) ->
                        "[${"%.2f".format(s)} '${c.title}' by '${c.performer?.name}']"
                    },
            )
        }
        val best = found ?: run {
            Log.d(TAG, "no_match artist='${query.artist}' title='${query.title}'")
            return null
        }

        // 3. Resolve to a signed download URL. kennyy.com.br returns 403
        // when the track is non-streamable; callLimited returns null so
        // we fall through to the next source cleanly.
        val requestedQualityCode = requestedQuality ?: losslessPrefs.qualityTierNow().qobuzCode
        Log.d(
            TAG,
            "kennyy_qobuz: requested quality=$requestedQualityCode " +
                "(${if (requestedQuality != null) "explicit" else "download-tier"})",
        )
        val download = callLimited(bypassRateLimit) {
            apiClient.getFileUrl(best.first.id, requestedQualityCode)
        } ?: return null

        if (download.url.isNullOrEmpty()) {
            Log.d(TAG, "download-music returned empty url for ${best.first.id}")
            return null
        }
        if (urlInspector.isDegraded(download.url, requestedQualityCode)) {
            // Proxy returned a preview sample or a lossy downgrade instead of
            // the requested lossless track. Treat as a miss so the registry
            // fails over, and cool the source down so we stop wasting a
            // round-trip per track until it recovers.
            Log.w(
                TAG,
                "degraded url for ${best.first.id} (sample/downgrade) — failing over; " +
                    "url=${download.url.take(80)}",
            )
            healthGate.recordDegraded(id)
            return null
        }

        // Album art — prefer `large` (~600px); fall back to `thumbnail`
        // then `small` so a thinly-populated catalog row still produces
        // something.
        val albumImage = best.first.album?.image
        val artUrl = albumImage?.large
            ?: albumImage?.thumbnail
            ?: albumImage?.small

        val format = AudioFormat(
            codec = if (requestedQualityCode == QobuzQuality.MP3_320) "mp3" else "flac",
            bitrateKbps = 0,
            sampleRateHz = (best.first.maximumSamplingRate * 1000f).toInt(),
            bitsPerSample = best.first.maximumBitDepth,
        )
        val result = SourceResult(
            sourceId = id,
            downloadUrl = download.url,
            downloadHeaders = emptyMap(),
            format = format,
            confidence = best.second,
            sourceTrackId = best.first.id.toString(),
            coverArtUrl = artUrl,
        )
        Log.d(TAG, "resolved '${query.title}' url=${result.downloadUrl.take(60)}... codec=${format.codec}")
        return result
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Wraps an API call with rate-limiter bookkeeping. Returns null on
     * any failure mode (rate-limit denial, exception, circuit-break)
     * so callers can simply `?: return null` to skip cleanly.
     *
     * No captcha-expired branch — kennyy.com.br has no captcha gate.
     * 429 → reportRateLimited; all other exceptions → reportFailure.
     */
    private suspend fun <T> callLimited(bypassRateLimit: Boolean = false, block: suspend () -> T): T? {
        if (bypassRateLimit) {
            // User-initiated path: skip BOTH the token bucket and the
            // circuit breaker. A user tap should never be blocked by
            // either gate — see [resolveImmediate] KDoc for rationale.
            // We still report outcomes below so the breaker state
            // continues to reflect reality for background paths.
        } else {
            if (!rateLimiter.acquire(id)) return null
        }
        return try {
            val result = block()
            rateLimiter.reportSuccess(id)
            result
        } catch (e: QobuzApiException) {
            when {
                e.status == 429 -> rateLimiter.reportRateLimited(id)
                else -> rateLimiter.reportFailure(id)
            }
            lastResolveFailedNetwork = true
            Log.w(TAG, "failed reason=network kennyy.com.br API call failed", e)
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            lastResolveFailedNetwork = true
            Log.w(TAG, "failed reason=network kennyy.com.br call threw: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Confidence score on [0.0, 1.0]. ISRC equality short-circuits to
     * 0.95 — same recording, same master, by definition. Otherwise
     * combines token-overlap on title and artist with a duration
     * penalty that downweights mismatched cuts (live, extended, edits)
     * without hard-rejecting them.
     *
     * Normalization and similarity helpers are shared from [QobuzSource]
     * companion (internal to the same Gradle module). A v0.9.11 refactor
     * will extract them to a dedicated helpers file.
     */
    private fun confidence(query: TrackQuery, candidate: QobuzTrack): Float {
        if (!candidate.streamable) return 0f

        val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
        val candidateIsrc = candidate.isrc?.takeIf { it.isNotBlank() }
        if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            return 0.95f
        }

        val titleSim = QobuzSource.jaccard(
            QobuzSource.normalize(query.title),
            QobuzSource.normalize(candidate.title),
        )
        val artistSim = QobuzSource.artistSimilarity(
            QobuzSource.normalize(query.artist),
            QobuzSource.normalize(candidate.performer?.name.orEmpty()),
        )

        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candidate.duration <= 0) return@run 1.0f
            val candidateMs = candidate.duration * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f
                drift < 0.10 -> 0.85f
                drift < 0.20 -> 0.6f
                else -> 0.3f
            }
        }

        // When title AND artist both match near-perfectly, trust the name
        // match and don't reject on duration alone. Music videos on YouTube
        // routinely have different durations from the studio Qobuz cut
        // (e.g. Drake's "God's Plan" YT video is 5:57 with the giving-money
        // intro, Qobuz studio is 3:18 — same audio, drift = 44%, which
        // used to score 0.30 and fail the 0.5 threshold). For fuzzy name
        // matches the duration still weighs in as a useful tiebreaker.
        val nameScore = titleSim * artistSim
        return if (nameScore >= 0.9f) {
            nameScore
        } else {
            nameScore * durationFactor
        }
    }

    companion object {
        /** Per LosslessSource KDoc convention: `kennyy_<catalog>`. */
        const val SOURCE_ID = "kennyy_qobuz"
        private const val TAG = "KennyySource"

        /** Threshold below which a candidate is rejected outright. */
        private const val MIN_CONFIDENCE = 0.5f
    }
}
