package com.stash.data.cloud.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/** Who is asking: the player and the user's taps go first, the library scan waits. */
enum class RequestPriority { INTERACTIVE, BACKGROUND }

/**
 * Paces one provider's API calls: about [requestsPerSecond] a second for background work, with
 * interactive requests (resolving a link to play, browsing folders, a download) jumping the queue,
 * and everyone pausing while the provider has asked us to back off (429/503 `Retry-After`).
 *
 * Background callers yield while any interactive caller waits, so a scan of thousands of files
 * never makes a tap on Play wait behind it. Interactive callers skip the spacing (a seek must not
 * queue behind a page of tag reads) but still honour a Retry-After pause, capped at
 * [maxInteractiveWaitMs] so a long server back-off fails the play fast instead of hanging it.
 *
 * Only the provider's API host is gated. Ranged reads on a download/temporary link go to the
 * content host, which has its own limits, and are not counted here.
 *
 * @param clock Milliseconds; injectable so tests run on virtual time.
 * @param sleep Suspending wait; `delay` in production, the test scheduler in tests.
 */
class RequestGate(
    requestsPerSecond: Int = 5,
    private val maxInteractiveWaitMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val minIntervalMs = 1000L / requestsPerSecond.coerceAtLeast(1)
    private val mutex = Mutex()
    private var nextSlotAt = 0L

    @Volatile
    private var pausedUntil = 0L
    private val interactiveWaiting = AtomicInteger(0)

    /** Suspends until a request of [priority] may be sent. */
    suspend fun acquire(priority: RequestPriority) {
        if (priority == RequestPriority.INTERACTIVE) {
            interactiveWaiting.incrementAndGet()
            try {
                val wait = (pausedUntil - clock()).coerceAtMost(maxInteractiveWaitMs)
                if (wait > 0) sleep(wait)
                // Counted against the budget, so background work spaces itself after it.
                mutex.withLock { nextSlotAt = maxOf(nextSlotAt, clock()) + minIntervalMs }
            } finally {
                interactiveWaiting.decrementAndGet()
            }
            return
        }
        while (true) {
            if (interactiveWaiting.get() > 0) {
                sleep(YIELD_MS)
                continue
            }
            val wait = mutex.withLock {
                val now = clock()
                val start = maxOf(nextSlotAt, pausedUntil)
                if (start <= now) {
                    nextSlotAt = now + minIntervalMs
                    0L
                } else {
                    start - now
                }
            }
            if (wait == 0L) return
            sleep(wait.coerceAtMost(MAX_SLICE_MS))
        }
    }

    /** The provider asked for a pause of [millis] (Retry-After); every caller waits it out. */
    fun backOff(millis: Long) {
        val until = clock() + millis.coerceIn(0, MAX_BACKOFF_MS)
        if (until > pausedUntil) pausedUntil = until
    }

    /** Milliseconds left in the current back-off, 0 when none (for diagnostics and tests). */
    fun remainingBackOffMs(): Long = (pausedUntil - clock()).coerceAtLeast(0)

    companion object {
        private const val YIELD_MS = 25L
        private const val MAX_SLICE_MS = 250L

        /** A misbehaving header never parks the scan for more than this. */
        const val MAX_BACKOFF_MS = 10 * 60_000L

        /** What we wait when a 429/503 carries no usable Retry-After. */
        const val DEFAULT_BACKOFF_MS = 5_000L

        private val HTTP_DATE = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        }

        /**
         * A `Retry-After` value in milliseconds: delta-seconds ("120") or an HTTP date. Null when
         * absent or unreadable (the caller then uses [DEFAULT_BACKOFF_MS]).
         */
        fun parseRetryAfter(header: String?, now: Long = System.currentTimeMillis()): Long? {
            val v = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            v.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            v.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
            return runCatching { HTTP_DATE.get()!!.parse(v)!!.time - now }.getOrNull()?.coerceAtLeast(0)
        }
    }
}
