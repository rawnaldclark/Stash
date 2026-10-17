package com.stash.data.download.lossless.tidal

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import com.stash.data.download.lossless.bitchord.TidalLookup
import com.stash.data.download.lossless.relay.LosslessDownloadPurpose
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [LosslessSource] for Tidal through the BitChord relay — the same server and
 * matching the streaming rung uses ([BitchordClient]), so a track that streams
 * from Tidal also downloads from it. DASH answers come back with
 * [SourceResult.isDash] set; the downloader fetches the segments and remuxes.
 *
 * Outcomes reach the breaker distinctly: a pool-busy answer counts as rate-limited, a
 * transient error as a failure, a definitive miss as neither (the client caches it).
 *
 * FLAC upgrade sweeps ([LosslessDownloadPurpose.upgradeSweep]) can walk a whole library, so
 * they get a rolling budget of [SWEEP_BUDGET_PER_HOUR] relay lookups. Cached answers are
 * free and do not count. When the budget is spent the track is marked paced: the worker
 * leaves it pending and retries later (WorkManager backoff) instead of filing it as a miss.
 */
@Singleton
class TidalLosslessSource internal constructor(
    private val client: BitchordClient,
    private val rateLimiter: AggregatorRateLimiter,
    private val nowMs: () -> Long,
) : LosslessSource {

    @Inject constructor(client: BitchordClient, rateLimiter: AggregatorRateLimiter) :
        this(client, rateLimiter, System::currentTimeMillis)

    override val id: String = SOURCE_ID
    override val displayName: String = "Tidal"

    private val sweepMutex = Mutex()
    private val sweepSpends = ArrayDeque<Long>()

    override suspend fun isEnabled(): Boolean =
        !rateLimiter.stateOf(id).isCircuitBroken && client.isConfigured()

    override suspend fun resolve(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
        if (!isEnabled()) return null
        val purpose = currentCoroutineContext()[LosslessDownloadPurpose]
        // Cached answers and an open busy circuit cost nothing: settle them before spending a
        // rate-limit token or a sweep slot.
        when (client.lookup(query, cacheOnly = true)) {
            TidalLookup.NoMatch, TidalLookup.Off -> return null
            // Spent: the day's lossless allowance; FLAC is back after the reset, so not a miss.
            is TidalLookup.Busy, is TidalLookup.Spent -> {
                purpose?.searchIncomplete = true
                return null
            }
            else -> Unit
        }
        if (purpose?.upgradeSweep == true) {
            val waitSec = takeSweepSlot()
            if (waitSec != null) {
                Log.i(TAG, "sweep budget spent; pacing the FLAC sweep ${waitSec}s")
                purpose.pacedRetryAfterSec = waitSec
                purpose.searchIncomplete = true
                return null
            }
        }
        if (!bypassRateLimit && !rateLimiter.acquire(id)) return null
        val outcome = try {
            client.lookup(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolve threw ${e.javaClass.simpleName}")
            TidalLookup.Failed
        }
        val stream = when (outcome) {
            is TidalLookup.Found -> outcome.stream
            TidalLookup.NoMatch, TidalLookup.Off, TidalLookup.NeedsRelay -> return null
            is TidalLookup.Busy -> {
                purpose?.searchIncomplete = true
                rateLimiter.reportRateLimited(id)
                return null
            }
            TidalLookup.Failed -> {
                purpose?.searchIncomplete = true
                rateLimiter.reportFailure(id)
                return null
            }
            // The relay's cap answer: a healthy relay, so the breaker hears nothing.
            is TidalLookup.Spent -> {
                purpose?.searchIncomplete = true
                return null
            }
        }
        rateLimiter.reportSuccess(id)
        return SourceResult(
            sourceId = id,
            downloadUrl = stream.url,
            downloadHeaders = emptyMap(), // relay URLs are HMAC-signed; no token needed
            format = AudioFormat(
                codec = "flac",
                bitrateKbps = stream.bitrateKbps,
                sampleRateHz = stream.sampleRateHz,
                bitsPerSample = stream.bitDepth,
                fileExtension = "flac",
            ),
            confidence = stream.confidence,
            sourceTrackId = stream.trackId,
            coverArtUrl = stream.coverArtUrl,
            isDash = stream.isDash,
        )
    }

    /** Claims one sweep lookup; null when granted, else the seconds until a slot frees. */
    private suspend fun takeSweepSlot(): Long? = sweepMutex.withLock {
        val now = nowMs()
        while (sweepSpends.isNotEmpty() && now - sweepSpends.first() >= SWEEP_WINDOW_MS) sweepSpends.removeFirst()
        if (sweepSpends.size >= SWEEP_BUDGET_PER_HOUR) {
            ((sweepSpends.first() + SWEEP_WINDOW_MS - now) / 1000L).coerceAtLeast(1L)
        } else {
            sweepSpends.addLast(now)
            null
        }
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    companion object {
        const val SOURCE_ID = "tidal_bitchord"
        /**
         * Uncached Tidal lookups a FLAC upgrade sweep may spend per rolling hour (each is up to
         * one `/search` + one `/stream`). A per-run cap would not hold: a paced worker re-runs
         * within minutes. 25/h keeps a library-wide sweep near 50 relay requests/hour per device
         * instead of the token bucket's ~720, and cached misses make repeat sweeps free.
         */
        const val SWEEP_BUDGET_PER_HOUR = 25
        private const val SWEEP_WINDOW_MS = 60 * 60_000L
        private const val TAG = "TidalLosslessSource"
    }
}
