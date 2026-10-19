package com.stash.core.data.lastfm.recap

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces the Last.fm recap's requests at least [intervalMs] apart, whoever makes them (Last.fm
 * asks for at most 5 a second per key). Each call to [paced] is one request: a fallback that
 * makes a second HTTP call goes through [paced] again.
 *
 * [elapsedMs] is a monotonic clock (SystemClock.elapsedRealtime in the app): a wall-clock change
 * must neither stall the recap nor let a burst through.
 */
internal class LastFmRecapPacer(private val intervalMs: Long, private val elapsedMs: () -> Long) {
    private val mutex = Mutex()
    private var lastAt: Long? = null

    suspend fun <T> paced(block: suspend () -> T): T {
        mutex.withLock {
            val wait = lastAt?.let { it + intervalMs - elapsedMs() } ?: 0L
            if (wait > 0) delay(wait)
            lastAt = elapsedMs()
        }
        return block()
    }
}
