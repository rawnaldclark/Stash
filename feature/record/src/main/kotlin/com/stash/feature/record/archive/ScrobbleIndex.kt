package com.stash.feature.record.archive

import com.stash.feature.record.lastfm.Scrobbles
import java.util.BitSet

/**
 * The archive's scrobbles in brief, for the Home card: each one's title (hashed) and start, sorted, and its id (hashed). Built
 * once per import (a pass over the scrobbles), it answers in memory which of them this phone's plays cover ([fingerprint]), so
 * a song played here (the card reads again after each) costs a lookup per play, not a pass over a heavy Last.fm history: the
 * scrobbles are read again only when what is covered changed (rare: a song played after a top-up already brought its scrobble).
 *
 * Hashes only tell a change; which scrobbles are covered is always decided on the words ([StashCover]). Three longs a scrobble.
 */
internal class ScrobbleIndex private constructor(
    private val titles: LongArray,
    private val starts: LongArray,
    private val ids: LongArray,
) {
    val size: Int get() = titles.size

    /**
     * What [cover] covers here, as one number: the same plays covering the same scrobbles give the same number. (Two different
     * coverings sharing it is a chance of one in 2^64; then the card keeps the scrobbles' part it had until the next import.)
     */
    fun fingerprint(cover: StashCover): Long {
        val hit = BitSet(titles.size)
        cover.forEachPlay { title, s, e ->
            val k = StashCover.idHash(title)
            var i = lowerBound(k, s - Scrobbles.SLACK_MS)
            while (i < titles.size && titles[i] == k && starts[i] <= e + Scrobbles.SLACK_MS) {
                hit.set(i)
                i++
            }
        }
        var h = hit.hashCode().toLong() * 31 + hit.cardinality()
        // A skip here claiming a scrobble's id (both lists sorted: one pass)
        val skips = cover.skipHashes
        var a = 0
        var b = 0
        while (a < ids.size && b < skips.size) {
            when {
                ids[a] < skips[b] -> a++
                ids[a] > skips[b] -> b++
                else -> {
                    h = h * 31 + ids[a]
                    a++
                    b++
                }
            }
        }
        return h
    }

    /** The first position at or after (title [k], start [t]). */
    private fun lowerBound(k: Long, t: Long): Int {
        var lo = 0
        var hi = titles.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (titles[mid] < k || (titles[mid] == k && starts[mid] < t)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    class Builder {
        private var titles = LongArray(INITIAL)
        private var starts = LongArray(INITIAL)
        private var ids = LongArray(INITIAL)
        private var n = 0

        fun add(r: ArchivePlay) {
            if (n == titles.size) {
                titles = titles.copyOf(n * 2)
                starts = starts.copyOf(n * 2)
                ids = ids.copyOf(n * 2)
            }
            titles[n] = StashCover.idHash(StashCover.titleOf(r.id))
            starts[n] = r.s
            ids[n] = StashCover.idHash(r.id)
            n++
        }

        fun build(): ScrobbleIndex {
            val order = (0 until n).sortedWith { x, y ->
                val c = titles[x].compareTo(titles[y])
                if (c != 0) c else starts[x].compareTo(starts[y])
            }
            return ScrobbleIndex(
                LongArray(n) { titles[order[it]] },
                LongArray(n) { starts[order[it]] },
                ids.copyOf(n).also { it.sort() },
            )
        }

        private companion object {
            const val INITIAL = 1024
        }
    }
}
