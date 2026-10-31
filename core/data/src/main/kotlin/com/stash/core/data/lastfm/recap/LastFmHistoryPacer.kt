package com.stash.core.data.lastfm.recap

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pace for every bulk read of a listener's Last.fm history, whoever reads it: the Last.fm recap and Your record's Last.fm
 * import share the bulk read keys, so they share one [LastFmRecapPacer] too. Each keeps its own gaps on top (the record waits
 * 350 ms between pages, as the web does), and together they never ask more than [LastFmRecapRepository.REQUEST_INTERVAL_MS]
 * apart, under Last.fm's 5 a second, even when a recap story and an import run at once.
 */
@Singleton
class LastFmHistoryPacer internal constructor(internal val pacer: LastFmRecapPacer) {
    @Inject constructor() : this(LastFmRecapPacer(LastFmRecapRepository.REQUEST_INTERVAL_MS, SystemClock::elapsedRealtime))

    /** [block] as one paced request. */
    suspend fun <T> paced(block: suspend () -> T): T = pacer.paced(block)
}
