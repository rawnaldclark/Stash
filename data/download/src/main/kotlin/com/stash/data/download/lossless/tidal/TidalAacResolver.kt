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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What [TidalAacResolver] found. A download goes on to the lossy fallback only on a definite
 * [NoAac]; [Unavailable] says Tidal could not be asked or did not answer, which is no verdict.
 */
sealed interface TidalAac {
    /** Tidal has the song as AAC: [source] is the file to fetch. */
    data class Found(val source: SourceResult) : TidalAac

    /**
     * Tidal has no AAC of it (no match, a cached or fresh miss), or Tidal AAC is not in play
     * here (the "Tidal AAC when no FLAC" switch off, no relay configured).
     */
    data object NoAac : TidalAac

    /** No answer yet; ask again at [retryAtMs]. */
    data class Unavailable(val reason: Reason, val retryAtMs: Long) : TidalAac

    enum class Reason {
        /** The relay's account pool is busy (503 / 429 that is not the daily cap). */
        BUSY,

        /** This device's own pacing for Tidal AAC lookups. */
        RATE_LIMITED,

        /** An error, an open circuit breaker, or an answer that wasn't AAC. */
        FAILED,

        /** The day's Tidal & Qobuz allowance is spent: nothing until the reset. */
        SPENT,
    }
}

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
 * A [resolve] for a user's tap (`foreground`) skips the bucket, as a tapped FLAC
 * search does, so it does not queue behind a sync's one-per-ten-seconds pace.
 *
 * FLAC answers: the relay can still answer FLAC with `allowAac` (it prefers
 * lossless). That only happens when the lossless rung lost the match for another
 * reason (rate limit, breaker race, a degraded flag). It is [TidalAac.Unavailable]
 * rather than saved through the AAC path — the lossless chain owns FLAC, and the
 * download waits for it.
 *
 * AAC 320 download mode ([resolve] with `aacOnly`): Tidal's AAC for any track, even one it has in
 * FLAC (`?aac=only`). The user chose AAC for every download, so the "Tidal AAC when no FLAC"
 * switch (a Lossless-mode choice) does not apply, and no lossless lookup is made.
 *
 * Every [TidalAac.Unavailable] that will clear by itself (busy, rate-limited, failed) is
 * announced on [retryEvents] with its time, and LosslessRetryScheduler books a sweep then: a
 * download that waited for it is tried again without anyone tapping.
 */
@Singleton
class TidalAacResolver @Inject constructor(
    private val client: BitchordClient,
    private val rateLimiter: AggregatorRateLimiter,
    private val prefs: LosslessSourcePreferences,
) {
    /** Wall clock; tests drive it. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    private val _retryEvents = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** When a waiting download should be tried again, epoch ms (busy, rate-limited, failed). */
    val retryEvents: SharedFlow<Long> = _retryEvents.asSharedFlow()

    suspend fun resolve(query: TrackQuery, aacOnly: Boolean = false, foreground: Boolean = false): TidalAac {
        val label = "${query.artist} - ${query.title}"
        if (!aacOnly && !prefs.tidalAacFallbackNow()) return TidalAac.NoAac
        if (!client.isConfigured()) return TidalAac.NoAac
        if (rateLimiter.stateOf(SOURCE_ID).isCircuitBroken ||
            rateLimiter.stateOf(TidalLosslessSource.SOURCE_ID).isCircuitBroken
        ) {
            Log.i(TAG, "skip '$label': circuit breaker open")
            return unavailable(TidalAac.Reason.FAILED, nowMs() + FAILED_RETRY_MS)
        }
        // A cached AAC miss (or no match at all), an open pool-busy circuit and a spent day cost nothing.
        when (val cached = client.lookup(query, allowAac = true, cacheOnly = true, aacOnly = aacOnly)) {
            TidalLookup.NoMatch, TidalLookup.Off -> return TidalAac.NoAac
            is TidalLookup.Busy -> return unavailable(TidalAac.Reason.BUSY, cached.untilMs)
            is TidalLookup.Spent -> {
                Log.i(TAG, "skip '$label': daily allowance spent")
                return TidalAac.Unavailable(TidalAac.Reason.SPENT, cached.untilMs)
            }
            else -> Unit
        }
        if (!foreground && !rateLimiter.acquire(SOURCE_ID)) {
            Log.i(TAG, "skip '$label': rate-limited")
            return unavailable(TidalAac.Reason.RATE_LIMITED, nowMs() + RATE_LIMITED_RETRY_MS)
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
            is TidalLookup.Busy -> {
                rateLimiter.reportRateLimited(SOURCE_ID)
                return unavailable(TidalAac.Reason.BUSY, outcome.untilMs)
            }
            is TidalLookup.Spent -> return TidalAac.Unavailable(TidalAac.Reason.SPENT, outcome.untilMs)
            TidalLookup.Failed -> {
                rateLimiter.reportFailure(SOURCE_ID)
                return unavailable(TidalAac.Reason.FAILED, nowMs() + FAILED_RETRY_MS)
            }
            TidalLookup.NoMatch, TidalLookup.Off -> return TidalAac.NoAac
            TidalLookup.NeedsRelay -> return unavailable(TidalAac.Reason.FAILED, nowMs() + FAILED_RETRY_MS)
        }
        rateLimiter.reportSuccess(SOURCE_ID)
        if (stream.codec != "aac" || stream.isDash) {
            Log.i(TAG, "skip '$label': relay answered ${stream.codec} (dash=${stream.isDash}); lossless chain owns it")
            return unavailable(TidalAac.Reason.FAILED, nowMs() + FAILED_RETRY_MS)
        }
        Log.i(TAG, "match '$label' -> ${stream.trackId} aac ${stream.bitrateKbps} kbps${if (aacOnly) " (AAC mode)" else ""}")
        return TidalAac.Found(
            SourceResult(
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
            ),
        )
    }

    /** A transient [TidalAac.Unavailable], announced on [retryEvents]. */
    private fun unavailable(reason: TidalAac.Reason, retryAtMs: Long): TidalAac.Unavailable {
        val at = maxOf(retryAtMs, nowMs() + MIN_RETRY_MS)
        _retryEvents.tryEmit(at)
        return TidalAac.Unavailable(reason, at)
    }

    companion object {
        const val SOURCE_ID = "tidal_aac"
        const val AAC_KBPS = 320
        private const val TAG = "TidalAacResolver"

        /** One token per ten seconds (the bucket's rate), with some slack. */
        internal const val RATE_LIMITED_RETRY_MS = 30_000L

        /** An error or an open breaker: look again in a few minutes, not at once. */
        internal const val FAILED_RETRY_MS = 5 * 60_000L

        /** No retry sooner than this, so a waiting queue is never swept in a tight loop. */
        internal const val MIN_RETRY_MS = 15_000L
    }
}
