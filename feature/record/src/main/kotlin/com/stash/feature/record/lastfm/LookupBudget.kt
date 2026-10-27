package com.stash.feature.record.lastfm

import kotlinx.coroutines.CancellationException
import java.util.TreeMap

/**
 * The lengths pass's share of Stash's Last.fm proxy, on this device: the lookups that reached the network, by hour, over a rolling
 * day, at most [cap] of them; and, after a rate limit, when it may ask again. Answers already on the phone never count. The hours
 * are kept until a whole day has passed since their end, so no rolling day ever holds more than [cap].
 *
 * Kept in memory as the pass asks, and written back ([flush]) once a slice: losing the last slice's count to the app's death
 * costs at most a slice's asks of the day's budget.
 */
class LookupBudget(
    private val cap: Int,
    private val load: () -> String?,
    private val save: (String?) -> Unit,
) {
    /** Hour (epoch ms / an hour) -> lookups made in it. */
    private val hours = TreeMap<Long, Int>()
    private var blockedUntil = 0L
    private var dirty = false

    init {
        read(load())
    }

    /** Lookups left in the rolling day at [nowMs]. */
    @Synchronized
    fun left(nowMs: Long): Int {
        prune(nowMs)
        return cap - hours.values.sum()
    }

    /** When asking may go on, if not now (epoch ms): after a rate limit, or once the day's [cap] frees up. */
    @Synchronized
    fun retryAt(nowMs: Long): Long? {
        prune(nowMs)
        var at = if (blockedUntil > nowMs) blockedUntil else null
        var used = hours.values.sum()
        if (used >= cap) {
            // The hours leave the window oldest first: the first after which there is room again
            for ((h, n) in hours) {
                used -= n
                if (used < cap) {
                    at = maxOf(at ?: 0L, end(h))
                    break
                }
            }
        }
        return at
    }

    /** One lookup reached the network at [nowMs]. */
    @Synchronized
    fun spend(nowMs: Long) {
        hours.merge(nowMs / HOUR_MS, 1, Int::plus)
        dirty = true
    }

    /** A rate limit: no lookups before [untilMs]. */
    @Synchronized
    fun block(untilMs: Long) {
        if (untilMs > blockedUntil) {
            blockedUntil = untilMs
            dirty = true
        }
    }

    /** What changed, written back. */
    @Synchronized
    fun flush() {
        if (!dirty) return
        save(write())
        dirty = false
    }

    /** When hour [h]'s lookups leave the rolling day: a whole day after the hour ends. */
    private fun end(h: Long) = (h + 1) * HOUR_MS + DAY_MS

    private fun prune(nowMs: Long) {
        val it = hours.entries.iterator()
        while (it.hasNext()) {
            if (end(it.next().key) <= nowMs) {
                it.remove()
                dirty = true
            } else {
                break
            }
        }
    }

    /** "blockedUntil;hour:count,hour:count". */
    private fun write(): String = "$blockedUntil;" + hours.entries.joinToString(",") { "${it.key}:${it.value}" }

    private fun read(s: String?) {
        if (s.isNullOrBlank()) return
        val parts = s.split(';', limit = 2)
        blockedUntil = parts[0].toLongOrNull() ?: 0L
        parts.getOrNull(1)?.split(',')?.forEach { e ->
            val (h, n) = e.split(':', limit = 2).let { (it.getOrNull(0)?.toLongOrNull() ?: return@forEach) to (it.getOrNull(1)?.toIntOrNull() ?: return@forEach) }
            hours[h] = n
        }
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
    }
}

/**
 * [inner], within this device's [budget]: refused (a limit, with when to come back) while the day's lookups are spent or a rate
 * limit holds; each lookup that reached the network counted (an answer the phone had isn't); a rate limit holds every ask after it
 * until it lifts ([LastfmError.retryAt], else [limitWaitMs]).
 */
internal class BudgetedLengthReader(
    private val inner: LengthReader,
    private val budget: LookupBudget,
    private val now: () -> Long = System::currentTimeMillis,
    private val limitWaitMs: Long = RecordLengths.LIMITED_WAIT_MS,
) : LengthReader {
    override suspend fun look(artist: String, track: String): LengthAnswer {
        val t = now()
        budget.retryAt(t)?.let { throw LastfmError(LastfmFailure.BUSY, limited = true, retryAt = it) }
        try {
            return inner.look(artist, track).also { if (!it.hit) budget.spend(t) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LastfmError) {
            if (e.limited) {
                budget.spend(t)
                budget.block(e.retryAt ?: (t + limitWaitMs))
                throw LastfmError(LastfmFailure.BUSY, limited = true, retryAt = budget.retryAt(now()) ?: (t + limitWaitMs))
            }
            // A busy answer reached the network too
            if (e.kind == LastfmFailure.BUSY) budget.spend(t)
            throw e
        }
    }
}
