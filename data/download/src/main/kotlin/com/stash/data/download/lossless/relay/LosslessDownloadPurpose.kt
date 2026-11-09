package com.stash.data.download.lossless.relay

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks bulk, delay-tolerant work — sync downloads, the FLAC upgrade sweep, the lossless retry
 * sweep. No element means a stream (a tap, the next track, a preview): that is the default
 * on purpose, so nothing that forgets to label itself can be made to wait.
 *
 * A coroutine element rather than a parameter because the request passes through the source
 * registry and the source, none of which care; only the two ends do. When a lossless source
 * asks this work to wait (the Tidal rung's hourly sweep budget), it records it here and the
 * caller defers the track (waits for FLAC) instead of taking a lossy copy that would stay
 * lossy forever.
 */
class LosslessDownloadPurpose(
    /**
     * The FLAC upgrade worker (a sweep, or a Library batch): the one bulk path that can walk a
     * whole library. The Tidal rung spends its own small hourly budget on it
     * ([com.stash.data.download.lossless.tidal.TidalLosslessSource.SWEEP_BUDGET_PER_HOUR]).
     */
    val upgradeSweep: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    /**
     * Set when a lossless source could not really search this track (busy, an error,
     * its breaker open). The sweep must then not file the track as "no lossless version".
     */
    @Volatile var searchIncomplete: Boolean = false

    /** Set when a lossless source paced this work; the seconds until it may try again. */
    @Volatile var pacedRetryAfterSec: Long? = null

    /**
     * Set once a lossless source matched the track. A null result after this is a failed
     * fetch or save, not "no lossless version", and the FLAC upgrade sweep must not file
     * it as a miss (it would leave the track alone for two weeks).
     */
    @Volatile var matchFound: Boolean = false
    companion object Key : CoroutineContext.Key<LosslessDownloadPurpose>
}
