package com.stash.data.download.jiosaavn

import android.util.Log
import com.stash.core.model.QualityTier
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The JioSaavn variant the Download quality picker asks for. JioSaavn's CDN
 * holds every song as AAC at 96, 160 and 320 kbps (see [jioSaavnVariantUrl]).
 */
fun QualityTier.toJioSaavnKbps(): Int = when (this) {
    QualityTier.LOW, QualityTier.NORMAL -> 96
    QualityTier.HIGH -> 160
    QualityTier.BEST, QualityTier.MAX -> 320
}

@Singleton
class JioSaavnResolver @Inject constructor(
    private val client: JioSaavnClient,
    private val rateLimiter: AggregatorRateLimiter,
) {
    /**
     * @param kbps the variant to fetch: 96, 160 or 320. No default on purpose:
     *   every caller answers to a user setting (downloads [toJioSaavnKbps],
     *   streaming Save Data), so none may fall back to 320 silently.
     */
    suspend fun resolve(query: TrackQuery, kbps: Int, bypassRateLimit: Boolean = false): SourceResult? {
        val label = "${query.artist} - ${query.title}"
        if (rateLimiter.stateOf(SOURCE_ID).isCircuitBroken) {
            Log.i(TAG, "skip '$label': circuit breaker open")
            return null
        }
        if (!bypassRateLimit && !rateLimiter.acquire(SOURCE_ID)) {
            Log.i(TAG, "skip '$label': rate-limited")
            return null
        }

        val searchQueries = listOf(
            "${query.artist} ${query.title}".trim(),
            // Primary-artist retry; "Alka Yagnik & Udit Narayan" has no comma (#484).
            "${query.artist.split(Regex("\\s*(,|&| x )\\s*")).first().trim()} ${query.title}".trim(),
        ).filter { it.isNotBlank() }.distinct()

        // Counted only so the terminal miss can say whether the catalog had
        // nothing or the matcher rejected everything — the two look identical
        // from outside and need very different fixes.
        var candidatesSeen = 0

        for (searchQuery in searchQueries) {
            when (val outcome = client.search(searchQuery, SEARCH_LIMIT)) {
                JioSaavnSearchOutcome.RateLimited -> {
                    rateLimiter.reportRateLimited(SOURCE_ID)
                    Log.i(TAG, "skip '$label': search rate-limited")
                    return null
                }
                is JioSaavnSearchOutcome.Failure -> {
                    rateLimiter.reportFailure(SOURCE_ID)
                    Log.d(TAG, "search failed: ${outcome.message}")
                    return null
                }
                is JioSaavnSearchOutcome.Success -> {
                    candidatesSeen += outcome.songs.size
                    val match = JioSaavnMatcher.best(query, outcome.songs) ?: continue
                    val url = jioSaavnVariantUrl(match.media.url, kbps)
                    when (val probe = client.isPlayable(url)) {
                        JioSaavnProbeOutcome.Playable -> Unit
                        JioSaavnProbeOutcome.Unavailable -> {
                            Log.d(TAG, "$kbps kbps candidate unavailable for ${match.song.id}")
                            continue
                        }
                        JioSaavnProbeOutcome.RateLimited -> {
                            rateLimiter.reportRateLimited(SOURCE_ID)
                            return null
                        }
                        is JioSaavnProbeOutcome.Failure -> {
                            rateLimiter.reportFailure(SOURCE_ID)
                            Log.d(TAG, "$kbps kbps probe transport failed: ${probe.message}")
                            return null
                        }
                    }
                    rateLimiter.reportSuccess(SOURCE_ID)
                    return SourceResult(
                        sourceId = SOURCE_ID,
                        downloadUrl = url,
                        // Every variant is stereo AAC-LC at 44.1 kHz (measured 2026-09-28).
                        format = AudioFormat(
                            codec = "aac",
                            bitrateKbps = kbps,
                            sampleRateHz = 44_100,
                            fileExtension = "m4a",
                        ),
                        confidence = match.confidence,
                        sourceTrackId = match.song.id,
                        coverArtUrl = match.song.image.firstOrNull {
                            it.quality == "500x500" && it.url.startsWith("https://")
                        }?.url,
                    )
                }
            }
        }
        // Search itself succeeded. An unavailable candidate URL is a catalog
        // miss, not enough evidence to open the provider-wide circuit breaker.
        rateLimiter.reportSuccess(SOURCE_ID)
        // The miss that was invisible. JioSaavn sits in every fallback chain
        // and, on a Western-catalog library, misses essentially every track —
        // but it returned null without a word, so "is JioSaavn even working?"
        // was unanswerable from a log and it read as silently covering for
        // the lossless sources. `candidates=0` means the catalog genuinely had
        // nothing; a non-zero count means the matcher rejected what came back
        // (usually a wrong-region namesake), which is a matcher question, not
        // an outage.
        Log.i(TAG, "no match for '$label': $candidatesSeen candidate(s) over ${searchQueries.size} query/ies")
        return null
    }

    companion object {
        const val SOURCE_ID = "jiosaavn"
        private const val TAG = "JioSaavnResolver"
        private const val SEARCH_LIMIT = 10
    }
}
