package com.stash.feature.record.archive

import com.stash.feature.record.lastfm.Scrobbles

/**
 * The scrobbles this phone's own plays cover (spec §7 "Stash over Last.fm"; plays.ts `coveredBy`, and `planPhone`'s rule that a
 * skip covers nothing, since a skipped song was never scrobbled). Stash scrobbles what it plays, so most of its plays come back
 * from Last.fm as scrobbles; the archive keeps Stash's own plays out of `record_plays` (they are read live and keep growing), so
 * wherever the two are read together the scrobble a play covers is left out, and one listen counts once.
 *
 * A scrobble is covered when one of the plays has the same folded title (the artist ignored) and the scrobble starts no more than
 * [Scrobbles.SLACK_MS] before the play's start and no later than [Scrobbles.SLACK_MS] after its end. A scrobble whose id is a
 * skip's here is left out too: the record keeps one row per id, and keeps this phone's (it comes first). A play's id is never a
 * scrobble's that it doesn't cover (the same id is the same title starting at the same moment).
 *
 * The folded title is read off the id (`fold(title)|fold(artist)|start`; a folded title has no `|`), so nothing is folded again.
 * Kept small: a title once per song, a play as two longs, a skip as a 64-bit hash of its id (a heavy history has 200,000
 * skips; two different ids sharing a hash, and one of them a scrobble's, is a chance far below one in a billion).
 */
class StashCover private constructor(private val spans: Map<String, LongArray>, private val skipIds: LongArray) {

    /** [r] (a scrobble) is a listen this phone's plays already count. */
    fun covers(r: ArchivePlay): Boolean {
        if (skipIds.isNotEmpty() && skipIds.binarySearch(idHash(r.id)) >= 0) return true
        val list = spans[titleOf(r.id)] ?: return false
        var i = 0
        while (i < list.size) {
            if (r.s >= list[i] - Scrobbles.SLACK_MS && r.s <= list[i + 1] + Scrobbles.SLACK_MS) return true
            i += 2
        }
        return false
    }

    /** Nothing here covers anything (no plays of this phone's own). */
    val isEmpty: Boolean get() = spans.isEmpty() && skipIds.isEmpty()

    /** Each play here: its folded title, start and end (the Home card's [ScrobbleIndex] looks them up). */
    internal fun forEachPlay(block: (title: String, s: Long, e: Long) -> Unit) {
        for ((title, list) in spans) {
            var i = 0
            while (i < list.size) {
                block(title, list[i], list[i + 1])
                i += 2
            }
        }
    }

    /** The skips' id hashes, sorted. */
    internal val skipHashes: LongArray get() = skipIds

    /** Learns this phone's rows as they stream past. */
    class Builder {
        private val spans = HashMap<String, LongArrayList>()
        private val skipIds = LongArrayList()

        /** One of this phone's rows ([RecordFlags.STASH]): a play covers, a skip only claims its id. */
        fun add(r: ArchivePlay) {
            if (r.f and RecordFlags.STASH == 0) return
            if (r.f and RecordFlags.SKIPPED != 0) {
                skipIds.add(idHash(r.id))
                return
            }
            spans.getOrPut(titleOf(r.id)) { LongArrayList() }.add(r.s, r.s + r.ms)
        }

        fun build(): StashCover = StashCover(spans.mapValues { it.value.toArray() }, skipIds.toArray().also { it.sort() })
    }

    /** A growable list of longs (a play as start then end). */
    private class LongArrayList {
        private var a = LongArray(4)
        private var n = 0

        fun add(v: Long) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }

        fun add(s: Long, e: Long) {
            add(s)
            add(e)
        }

        fun toArray(): LongArray = a.copyOf(n)
    }

    companion object {
        /** No plays of this phone's own: nothing covered. */
        val NONE = StashCover(emptyMap(), LongArray(0))

        /** FNV-1a, 64 bits, over an id's UTF-16 units. */
        internal fun idHash(id: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in id) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        /** The folded title an id begins with (plays.ts `overlapKey` of the row's title). */
        internal fun titleOf(id: String): String {
            val bar = id.indexOf('|')
            return if (bar < 0) id else id.substring(0, bar)
        }
    }
}
