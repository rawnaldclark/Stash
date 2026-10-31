package com.stash.feature.record.archive

import com.stash.feature.record.lastfm.Scrobbles
import java.util.BitSet

/**
 * The archive's scrobbles in brief, for the Home card: each one's title (hashed) and start, sorted, and its id (hashed). Built
 * once per import (a pass over the scrobbles), it answers in memory which of them this phone's plays cover ([covering]), so
 * a song played here (the card reads again after each) costs a lookup per play, not a pass over a heavy Last.fm history: the
 * scrobbles are read again only when what is covered changed (rare: a song played after a top-up already brought its scrobble).
 *
 * Hashed titles only tell a change; which scrobbles are covered is always decided on the words ([StashCover]). Three longs a
 * scrobble.
 */
internal class ScrobbleIndex private constructor(
    private val titles: LongArray,
    private val starts: LongArray,
    private val ids: LongArray,
) {
    val size: Int get() = titles.size

    /**
     * What [cover] covers here: which positions (by title hash and start) and which skips claim a scrobble's id. Compared whole
     * ([Covering] is equal only for the same positions and the same ids), so a change is never missed. Only two titles sharing a
     * 64-bit hash could make one covering look like another, which the words decide later anyway.
     */
    fun covering(cover: StashCover): Covering {
        val hit = BitSet(titles.size)
        cover.forEachPlay { title, s, e ->
            val k = StashCover.idHash(title)
            var i = lowerBound(k, s - Scrobbles.SLACK_MS)
            while (i < titles.size && titles[i] == k && starts[i] <= e + Scrobbles.SLACK_MS) {
                hit.set(i)
                i++
            }
        }
        // A skip here claiming a scrobble's id (both lists sorted: one pass)
        val claimed = ArrayList<Long>()
        val skips = cover.skipHashes
        var a = 0
        var b = 0
        while (a < ids.size && b < skips.size) {
            when {
                ids[a] < skips[b] -> a++
                ids[a] > skips[b] -> b++
                else -> {
                    claimed += ids[a]
                    a++
                    b++
                }
            }
        }
        return Covering(hit, claimed)
    }

    /** The scrobbles a set of plays covers, by position here, and the ids skips claim (hashed). Equal only when both are. */
    data class Covering(val positions: BitSet, val claimed: List<Long>)

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
