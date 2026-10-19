package com.stash.data.download.lossless.tidal

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import com.stash.data.download.lossless.bitchord.TidalLookup
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Tidal's 320 kbps AAC for a download whose lossless chain came up empty — the
 * first lossy rung, ahead of JioSaavn and YouTube. Deliberately NOT a
 * [com.stash.data.download.lossless.LosslessSource]: AAC must never satisfy a
 * lossless request, so it lives outside the registry and callers invoke it only
 * after the lossless rung missed.
 *
 * Gates: the user's "Tidal AAC when no FLAC" switch, the relay configured, and
 * its own [SOURCE_ID] rate-limit bucket (same numbers as `tidal_bitchord`); the
 * lossless source's open breaker also stops it, since both hit the same relay.
 *
 * FLAC answers: the relay can still answer FLAC with `allowAac` (it prefers
 * lossless). That only happens when the lossless rung lost the match for another
 * reason (rate limit, breaker race, a degraded flag); this resolver returns null
 * for it rather than save FLAC through the AAC path — the lossless chain owns
 * FLAC, its retry picks the track up later.
 *
 * AAC 320 download mode ([resolve] with `aacOnly`): Tidal's AAC for any track, even one it has in
 * FLAC (`?aac=only`). The user chose AAC for every download, so the "Tidal AAC when no FLAC"
 * switch (a Lossless-mode choice) does not apply, and no lossless lookup is made.
 */
@Singleton
class TidalAacResolver @Inject constructor(
    private val client: BitchordClient,
    private val rateLimiter: AggregatorRateLimiter,
    private val prefs: LosslessSourcePreferences,
) {
    suspend fun resolve(query: TrackQuery, aacOnly: Boolean = false): SourceResult? {
        val label = "${query.artist} - ${query.title}"
        if (!aacOnly && !prefs.tidalAacFallbackNow()) return null
        if (!client.isConfigured()) return null
        if (rateLimiter.stateOf(SOURCE_ID).isCircuitBroken ||
            rateLimiter.stateOf(TidalLosslessSource.SOURCE_ID).isCircuitBroken
        ) {
            Log.i(TAG, "skip '$label': circuit breaker open")
            return null
        }
        // A cached AAC miss (or no match at all) and an open pool-busy circuit cost nothing.
        when (client.lookup(query, allowAac = true, cacheOnly = true, aacOnly = aacOnly)) {
            TidalLookup.NoMatch, TidalLookup.Off, is TidalLookup.Busy -> return null
            else -> Unit
        }
        if (!rateLimiter.acquire(SOURCE_ID)) {
            Log.i(TAG, "skip '$label': rate-limited")
            return null
        }
        val outcome = try {
            client.lookup(query, allowAac = true, aacOnly = aacOnly)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve threw ${e.javaClass.simpleName}")
            TidalLookup.Failed
        }
        val stream = when (outcome) {
            is TidalLookup.Found -> outcome.stream
            is TidalLookup.Busy -> { rateLimiter.reportRateLimited(SOURCE_ID); return null }
            TidalLookup.Failed -> { rateLimiter.reportFailure(SOURCE_ID); return null }
            else -> return null
        }
        rateLimiter.reportSuccess(SOURCE_ID)
        if (stream.codec != "aac" || stream.isDash) {
            Log.i(TAG, "skip '$label': relay answered ${stream.codec} (dash=${stream.isDash}); lossless chain owns it")
            return null
        }
        Log.i(TAG, "match '$label' -> ${stream.trackId} aac ${stream.bitrateKbps} kbps${if (aacOnly) " (AAC mode)" else ""}")
        return SourceResult(
            sourceId = SOURCE_ID,
            downloadUrl = stream.url,
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                codec = "aac",
                bitrateKbps = stream.bitrateKbps.takeIf { it > 0 } ?: AAC_KBPS,
                sampleRateHz = stream.sampleRateHz.takeIf { it > 0 } ?: 44_100,
                bitsPerSample = 0, // lossy
                fileExtension = "m4a",
            ),
            confidence = stream.confidence,
            sourceTrackId = stream.trackId,
            coverArtUrl = stream.coverArtUrl,
            isDash = false,
        )
    }

    companion object {
        const val SOURCE_ID = "tidal_aac"
        const val AAC_KBPS = 320
        private const val TAG = "TidalAacResolver"
    }
}
