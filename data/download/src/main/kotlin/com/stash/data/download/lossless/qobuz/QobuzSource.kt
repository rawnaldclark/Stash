package com.stash.data.download.lossless.qobuz

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
import com.stash.data.download.lossless.squid.CaptchaExpiredNotifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [LosslessSource] backed by the Qobuz catalog via the public squid.wtf
 * proxy (`qobuz.squid.wtf/api`). Searches for the requested track,
 * scores candidates by ISRC / title / artist / duration agreement,
 * and resolves the best match to a signed FLAC download URL.
 *
 * No user credentials required — squid.wtf's operator runs the upstream
 * Qobuz subscription on everyone's behalf. Because of that, this source
 * relies hard on [AggregatorRateLimiter] to keep Stash from accidentally
 * DDoSing what is, structurally, one paid Qobuz account serving an
 * unbounded user base. Conservative defaults are deliberate.
 *
 * Quality is read from [LosslessSourcePreferences.qualityTierNow] on every
 * `resolve()` call — users select a tier (CD/Hi-Res/Max) via Settings;
 * squid.wtf passes the request through to upstream Qobuz, which serves
 * "highest available <= requested." The actual delivered bit-depth and
 * sample rate come back on the `track.maximumBitDepth` /
 * `maximumSamplingRate` fields and flow into the [SourceResult.format].
 */
@Singleton
class QobuzSource @Inject constructor(
    private val apiClient: QobuzApiClient,
    private val rateLimiter: AggregatorRateLimiter,
    private val captchaExpiredNotifier: CaptchaExpiredNotifier,
    private val losslessPrefs: LosslessSourcePreferences,
    private val urlInspector: LosslessUrlInspector,
    private val healthGate: LosslessSourceHealthGate,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "Qobuz (via squid.wtf)"

    /**
     * Backing flow for [lastKnownBadCookie]. Set when an API call
     * returns the captcha-required 403 — used by isEnabled to skip
     * squid until the user pastes a fresh cookie. Implicit reset:
     * when the user updates the cookie via Settings, the new value
     * differs from this one and isEnabled returns true again.
     *
     * Exposed as an observable [StateFlow] so the Settings UI can
     * render an "expired" badge and re-surface the captcha solver
     * link reactively (rather than the previous "non-empty cookie =
     * active" approximation that lied after expiry).
     */
    private val _lastKnownBadCookie = MutableStateFlow<String?>(null)

    /** Observable view of the most recently rejected captcha cookie. */
    val lastKnownBadCookie: StateFlow<String?> = _lastKnownBadCookie.asStateFlow()

    /**
     * Clear the lastKnownBad flag. Called by [SquidCookieAutoRefresher]
     * after a successful `/api/altcha/verify` round-trip — that endpoint
     * IS the server's authoritative cookie validation, so if it accepted
     * the cookie any subsequent transient 403 (e.g. cookie-validator
     * propagation lag across edge nodes) shouldn't pin the UI to "Expired"
     * indefinitely. Effectively a "I just proved this cookie works" reset.
     */
    fun clearLastKnownBad() {
        _lastKnownBadCookie.value = null
    }

    override suspend fun isEnabled(): Boolean {
        // Circuit-broken via repeated failures? Skip.
        if (rateLimiter.stateOf(id).isCircuitBroken) return false
        // No captcha cookie set? Squid's download endpoint requires it; skip.
        val currentCookie = losslessPrefs.captchaCookieValueNow()
        if (currentCookie.isNullOrBlank()) return false
        // Recently confirmed bad? Skip until user pastes a fresh cookie
        // (different value will not match lastKnownBadCookie).
        if (currentCookie == _lastKnownBadCookie.value) {
            return false
        }
        return true
    }

    /**
     * Streaming-only enablement gate. Same captcha-cookie check as
     * [isEnabled] but **ignores the circuit breaker** — user-initiated
     * taps bypass the breaker (see [resolveImmediate]) so it would be
     * inconsistent to gate enablement on it here.
     *
     * Used by the streaming registry to avoid wasting an HTTP call when
     * we already know squid will 403 on the captcha gate (no cookie
     * pasted, or current cookie was previously rejected).
     */
    suspend fun isEnabledForStreaming(): Boolean {
        val currentCookie = losslessPrefs.captchaCookieValueNow()
        if (currentCookie.isNullOrBlank()) return false
        if (currentCookie == _lastKnownBadCookie.value) return false
        return true
    }

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? =
        resolveInternal(query, bypassRateLimit = bypassRateLimit, requestedQuality = null)

    /**
     * User-initiated immediate resolve for the streaming path. Mirrors
     * [KennyySource.resolveImmediate] — skips BOTH the token bucket and
     * the circuit breaker so a tap can't be blocked by background-worker
     * traffic OR by a stale breaker state. Background paths ([resolve])
     * still respect both gates.
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
        Log.d(TAG, "resolve attempt artist='${query.artist}' title='${query.title}' isrc=${query.isrc ?: "none"}")
        // 1. Search squid.wtf for candidates. ISRC is Qobuz's best
        // index key — when we have one, send it as the query directly.
        // Try the full artist credit first (so single artists whose NAME
        // contains a comma — "Tyler, The Creator", "Earth, Wind & Fire" —
        // still match), then fall back to the PRIMARY artist (before the
        // first comma). The fallback rescues multi-artist credits like
        // "¥$, Kanye West, Ty Dolla $ign": sending the full credit verbatim
        // makes the proxy return the featured artists' popular tracks instead
        // of the actual song, so every candidate scores 0 → "failed". ISRC,
        // when present, is used alone (precise key). See TrackQuery.searchTerms.
        var found: Pair<QobuzTrack, Float>? = null
        for (term in query.searchTerms()) {
            val searchData = callLimited(bypassRateLimit) { apiClient.search(term) } ?: continue
            val candidates = searchData.tracks?.items.orEmpty()
            if (candidates.isEmpty()) continue

            // Score and pick the best candidate that crosses the threshold.
            val scored = candidates.map { it to confidence(query, it) }
            val match = scored.filter { it.second >= QobuzCandidateMatcher.MIN_CONFIDENCE }
                .maxByOrNull { it.second }
            if (match != null) {
                found = match
                break
            }
            // Log the top 3 rejected candidates per term — shows *why* a
            // search returned results but nothing crossed the threshold.
            val top = scored.sortedByDescending { it.second }.take(3)
            Log.d(
                TAG,
                "below_confidence (<${QobuzCandidateMatcher.MIN_CONFIDENCE}) term='$term' for '${query.artist} - ${query.title}': " +
                    top.joinToString(", ") { (c, s) ->
                        "[${"%.2f".format(s)} '${c.title}' by '${c.performer?.name}']"
                    },
            )
        }
        val best = found ?: run {
            Log.d(TAG, "no_match artist='${query.artist}' title='${query.title}'")
            return null
        }

        // 3. Resolve to a signed download URL. squid.wtf returns 403
        // when the track is non-streamable in the deployment's region;
        // callLimited swallows the exception and returns null so we
        // fall through to the next source cleanly.
        val requestedQualityCode = requestedQuality ?: losslessPrefs.qualityTierNow().qobuzCode
        Log.d(
            TAG,
            "squid_qobuz: requested quality=$requestedQualityCode " +
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

        // Album art — Qobuz returns multiple sizes on `track.album.image`.
        // Prefer `large` (~600px); fall back to `thumbnail` then `small`
        // so a thinly-populated catalog row still produces something.
        // `null` propagates to the download pipeline which then has a
        // chance to fall through to its other art-resolution paths.
        val albumImage = best.first.album?.image
        val artUrl = albumImage?.large
            ?: albumImage?.thumbnail
            ?: albumImage?.small

        val format = AudioFormat(
            // squid.wtf strips the upstream `mime_type`; map from
            // the requested format_id since Qobuz returns the
            // matching codec for each.
            codec = if (requestedQualityCode == QobuzQuality.MP3_320) "mp3" else "flac",
            // Bitrate left at 0 — FLAC is variable; the
            // canonical value comes from AudioDurationExtractor
            // after the file's on disk.
            bitrateKbps = 0,
            sampleRateHz = (best.first.maximumSamplingRate * 1000f).toInt(),
            bitsPerSample = best.first.maximumBitDepth,
        )
        val result = SourceResult(
            sourceId = id,
            downloadUrl = download.url,
            // squid.wtf's CDN URLs are pre-signed query strings — no
            // extra headers needed for the actual file fetch.
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
     */
    private suspend fun <T> callLimited(bypassRateLimit: Boolean = false, block: suspend () -> T): T? {
        if (!bypassRateLimit) {
            if (!rateLimiter.acquire(id)) return null
        }
        // bypassRateLimit = true: user-initiated streaming path. Skip the
        // token bucket AND the breaker (we don't call acquire). Failure
        // reporting below still updates the breaker state so background
        // [resolve] calls see accurate health.
        return try {
            val result = block()
            rateLimiter.reportSuccess(id)
            result
        } catch (e: QobuzApiException) {
            val isCaptchaRequired = e.status == 403 &&
                e.message?.contains("Captcha", ignoreCase = true) == true
            when {
                e.status == 429 -> rateLimiter.reportRateLimited(id)
                // 403 "Captcha required" is the normal expired-cookie
                // state. It's recoverable (user pastes / re-verifies
                // a fresh cookie) and shouldn't trip the circuit
                // breaker — otherwise three quick 403s during a sync
                // disable the source for 30min even after the user
                // refreshes the cookie. We skip the call but don't
                // accumulate failures.
                isCaptchaRequired -> {
                    Log.w(TAG, "failed reason=captcha_required cookie likely expired; skipping without circuit-break")
                    captchaExpiredNotifier.notifyExpired()
                    // Mark the current cookie as bad so isEnabled() skips squid until
                    // the user pastes a new value. Prevents wasting ~16s/track on
                    // doomed squid attempts when the captcha is known stale.
                    _lastKnownBadCookie.value = losslessPrefs.captchaCookieValueNow()
                    Log.i(TAG, "squid_qobuz: captcha cookie marked bad; will skip until user updates cookie via Settings")
                }
                else -> rateLimiter.reportFailure(id)
            }
            if (!isCaptchaRequired) {
                Log.w(TAG, "failed reason=network squid.wtf API call failed", e)
            }
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "failed reason=network squid.wtf call threw: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Confidence score on [0.0, 1.0]. Delegates to the shared
     * [QobuzCandidateMatcher] (extracted so the qbdlx direct-API source reuses
     * the exact same scoring); maps this source's [QobuzTrack] wire type onto
     * the matcher's neutral fields.
     */
    private fun confidence(query: TrackQuery, candidate: QobuzTrack): Float =
        QobuzCandidateMatcher.confidence(
            query = query,
            candTitle = candidate.title,
            candArtist = candidate.performer?.name.orEmpty(),
            candIsrc = candidate.isrc,
            candDurationSec = candidate.duration,
            candStreamable = candidate.streamable,
            candVersion = candidate.version,
        )

    companion object {
        /** Per LosslessSource KDoc convention: `squid_<catalog>`. */
        const val SOURCE_ID = "squid_qobuz"
        private const val TAG = "QobuzSource"

        // ── Delegating shims ──────────────────────────────────────────────
        // The matching primitives moved into [QobuzCandidateMatcher]. These
        // thin shims stay so [QobuzSourceTest] (which exercises them directly)
        // keeps compiling against QobuzSource's companion.
        internal fun normalize(s: String): String = QobuzCandidateMatcher.normalize(s)
        internal fun jaccard(a: String, b: String): Float = QobuzCandidateMatcher.jaccard(a, b)
        internal fun artistSimilarity(a: String, b: String): Float =
            QobuzCandidateMatcher.artistSimilarity(a, b)
    }
}
