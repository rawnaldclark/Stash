package com.stash.data.download.jiosaavn

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JioSaavnResolver @Inject constructor(
    private val client: JioSaavnClient,
    private val rateLimiter: AggregatorRateLimiter,
) {
    suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean = false): SourceResult? {
        if (rateLimiter.stateOf(SOURCE_ID).isCircuitBroken) return null
        if (!bypassRateLimit && !rateLimiter.acquire(SOURCE_ID)) return null

        val searchQueries = listOf(
            "${query.artist} ${query.title}".trim(),
            "${query.artist.substringBefore(',').trim()} ${query.title}".trim(),
        ).filter { it.isNotBlank() }.distinct()

        for (searchQuery in searchQueries) {
            when (val outcome = client.search(searchQuery, SEARCH_LIMIT)) {
                JioSaavnSearchOutcome.RateLimited -> {
                    rateLimiter.reportRateLimited(SOURCE_ID)
                    return null
                }
                is JioSaavnSearchOutcome.Failure -> {
                    rateLimiter.reportFailure(SOURCE_ID)
                    Log.d(TAG, "search failed: ${outcome.message}")
                    return null
                }
                is JioSaavnSearchOutcome.Success -> {
                    val match = JioSaavnMatcher.best(query, outcome.songs) ?: continue
                    when (val probe = client.isPlayable320(match.media.url)) {
                        JioSaavnProbeOutcome.Playable -> Unit
                        JioSaavnProbeOutcome.Unavailable -> {
                            Log.d(TAG, "320 candidate unavailable for ${match.song.id}")
                            continue
                        }
                        JioSaavnProbeOutcome.RateLimited -> {
                            rateLimiter.reportRateLimited(SOURCE_ID)
                            return null
                        }
                        is JioSaavnProbeOutcome.Failure -> {
                            rateLimiter.reportFailure(SOURCE_ID)
                            Log.d(TAG, "320 probe transport failed: ${probe.message}")
                            return null
                        }
                    }
                    rateLimiter.reportSuccess(SOURCE_ID)
                    return SourceResult(
                        sourceId = SOURCE_ID,
                        downloadUrl = match.media.url,
                        format = AudioFormat(
                            codec = "aac",
                            bitrateKbps = 320,
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
        return null
    }

    companion object {
        const val SOURCE_ID = "jiosaavn"
        private const val TAG = "JioSaavnResolver"
        private const val SEARCH_LIMIT = 10
    }
}
