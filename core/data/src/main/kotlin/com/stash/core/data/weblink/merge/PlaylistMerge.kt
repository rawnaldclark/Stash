package com.stash.core.data.weblink.merge

import com.stash.core.model.weblink.Hlc
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongKey

/*
 * Mirror merge rules of sync-v1 for playlists, and the first-merge helpers (stash-player `docs/sync-v1.md` "Merge rules"; spec §7.3,
 * §7.5). Pure, a port of the web's `src/lib/sync/merge.ts`; both pass `merge-vectors.json`.
 */

/**
 * Pairs each of [ys] with an item of [xs] that is `sameSong`, by occurrence: in [ys] order, each takes the first (lowest-index)
 * item of [xs] not taken yet. Returns, per [ys] item, the index in [xs] or -1. Repeats of one song pair first with first.
 */
fun matchOccurrences(xs: List<SongIdentity>, ys: List<SongIdentity>): IntArray {
    val byKey = HashMap<String, MutableList<Int>>()
    val byText = HashMap<String, MutableList<Int>>()
    val isrcs = xs.map(SongKey::isrcOf)
    xs.forEachIndexed { i, x ->
        byKey.getOrPut(SongKey.keyOf(x)) { mutableListOf() } += i
        byText.getOrPut(SongKey.textKey(x)) { mutableListOf() } += i
    }
    val taken = BooleanArray(xs.size)
    return IntArray(ys.size) { j ->
        val y = ys[j]
        val yIsrc = SongKey.isrcOf(y)
        var best = byKey[SongKey.keyOf(y)]?.firstOrNull { !taken[it] } ?: -1
        for (i in byText[SongKey.textKey(y)].orEmpty()) {
            if (best >= 0 && i >= best) break
            if (!taken[i] && (isrcs[i] == null || yIsrc == null)) {
                best = i
                break
            }
        }
        if (best >= 0) taken[best] = true
        best
    }
}

data class Follow(val id: String, val version: Int)

/** A mirrored playlist at one point: `items = null` is deleted. [ro]: synced from Spotify / YouTube Music, only the phone writes it. */
data class PlVersion<S : SongIdentity>(
    val name: String,
    val items: List<S>?,
    val at: Hlc,
    val follow: Follow? = null,
    val ro: Boolean = false,
)

object PlaylistMerge {
    enum class Action { TAKE, KEEP, MERGE }

    /**
     * [Action.TAKE]: this device's playlist becomes the remote version; [Action.KEEP]: it stays; [Action.MERGE]: it becomes [local].
     * [base] is the new agreed version (the remote one, except when a read-only rule ignores the op: then the old base). A later
     * diff against that base pushes whatever this device holds beyond it.
     */
    data class Result<S : SongIdentity>(val action: Action, val local: PlVersion<S>?, val base: PlVersion<S>?)

    private fun sameItems(a: List<SongIdentity>?, b: List<SongIdentity>?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.size == b.size && a.indices.all { SongKey.sameSong(a[it], b[it]) }
    }

    /** Same content: name, follow and items (pairwise `sameSong`). */
    fun sameVersion(a: PlVersion<*>, b: PlVersion<*>): Boolean = a.name == b.name && a.follow == b.follow && sameItems(a.items, b.items)

    private fun mergeName(base: PlVersion<*>?, l: PlVersion<*>, r: PlVersion<*>): String = when {
        base != null && l.name == base.name -> r.name
        base != null && r.name == base.name -> l.name
        r.at > l.at -> r.name
        else -> l.name
    }

    /** Wraps a song so the result list can find "this occurrence" by reference even when two items are equal. */
    private class Entry<S>(val song: S)

    /**
     * Three-way merge of the songs (both sides changed them). [p] (the newer side) gives the order; a base song removed on either
     * side is removed; a song added on either side is kept (the same song added on both sides once); [s]'s additions go right
     * after their nearest preceding neighbour (in [s]) that is in the result, else at the start.
     */
    private fun <S : SongIdentity> mergeItems(base: List<S>, p: List<S>, s: List<S>): List<S> {
        val pToBase = matchOccurrences(base, p)
        val sToBase = matchOccurrences(base, s)
        val inP = BooleanArray(base.size).also { a -> pToBase.forEach { if (it >= 0) a[it] = true } }
        val inS = BooleanArray(base.size).also { a -> sToBase.forEach { if (it >= 0) a[it] = true } }

        val result = mutableListOf<Entry<S>>()
        val byBase = HashMap<Int, Entry<S>>()
        val pAdded = mutableListOf<Entry<S>>()
        p.forEachIndexed { i, song ->
            val b = pToBase[i]
            if (b >= 0 && !inS[b]) return@forEachIndexed // removed on the other side
            val e = Entry(song)
            result += e
            if (b >= 0) byBase[b] = e else pAdded += e
        }
        val sAddedIdx = s.indices.filter { sToBase[it] < 0 }
        val sAddedToP = matchOccurrences(pAdded.map { it.song }, sAddedIdx.map { s[it] })
        val sameAdd = HashMap<Int, Entry<S>>()
        sAddedIdx.forEachIndexed { k, si -> if (sAddedToP[k] >= 0) sameAdd[si] = pAdded[sAddedToP[k]] }

        var anchor: Entry<S>? = null
        s.forEachIndexed { i, song ->
            val b = sToBase[i]
            if (b >= 0) {
                if (inP[b]) byBase[b]?.let { anchor = it }
                return@forEachIndexed
            }
            sameAdd[i]?.let {
                anchor = it
                return@forEachIndexed
            }
            val e = Entry(song)
            val at = anchor?.let { a -> result.indexOfFirst { it === a } + 1 } ?: 0
            result.add(at, e)
            anchor = e
        }
        return result.map { it.song }
    }

    /**
     * Merges a remote `pl` op into this device's playlist. [phone]: this device is the phone. [base]: the version last agreed on.
     * [local] / [localHash]: this device's current version and its hash, or null when it doesn't have the playlist. [remote],
     * [parent] (the hash the edit started from) and [fromPhone] (the phone wrote it) describe the op.
     */
    fun <S : SongIdentity> merge(
        phone: Boolean,
        base: PlVersion<S>?,
        local: PlVersion<S>?,
        localHash: String?,
        remote: PlVersion<S>,
        parent: String?,
        fromPhone: Boolean,
    ): Result<S> {
        val r = remote
        val take = Result(Action.TAKE, r, r)
        fun keep(b: PlVersion<S>? = r) = Result(Action.KEEP, local, b)

        // Read-only (Spotify / YouTube Music-synced) playlists: only the phone writes them.
        if (local?.ro == true || r.ro) return if (phone || !fromPhone) keep(base) else take
        if (local == null) return take
        if (parent != null && parent == localHash) return take
        if (base != null && sameVersion(local, base)) return take
        if (base != null && sameVersion(r, base)) return keep()

        // Both changed.
        val l = local
        if (r.items == null || l.items == null) {
            if (r.items == null && l.items == null) return take
            return if (r.at > l.at) take else keep()
        }
        val name = mergeName(base, l, r)
        val at = maxOf(l.at, r.at)
        val merged: PlVersion<S> = if (l.follow != null || r.follow != null) {
            // A followed shared mix: its songs are never merged; the follow with the higher version (same mix) or the newer edit wins.
            val rWins = if (l.follow != null && r.follow != null && l.follow.id == r.follow.id) {
                r.follow.version > l.follow.version || (r.follow.version == l.follow.version && r.at > l.at)
            } else {
                r.at > l.at
            }
            val w = if (rWins) r else l
            PlVersion(name, w.items, at, w.follow)
        } else {
            val rFirst = r.at > l.at
            PlVersion(name, mergeItems(base?.items.orEmpty(), if (rFirst) r.items else l.items, if (rFirst) l.items else r.items), at)
        }
        if (sameVersion(merged, r)) return take
        if (sameVersion(merged, l)) return Result(Action.KEEP, l, r)
        return Result(Action.MERGE, merged, r)
    }
}

object FirstMerge {
    data class Counts(val a: Int, val b: Int, val both: Int, val combined: Int, val onlyA: Int, val onlyB: Int)

    /** The counts the first-merge question shows (spec §2.4): [both] pairs songs by [matchOccurrences]. */
    fun counts(a: List<SongIdentity>, b: List<SongIdentity>): Counts {
        val both = matchOccurrences(a, b).count { it >= 0 }
        return Counts(a.size, b.size, both, a.size + b.size - both, a.size - both, b.size - both)
    }

    const val MAX_PLAYLIST_NAME = 100

    /**
     * "Combine" keeps two playlists with one name as two: the web's copy becomes "Night drive (web)", then "(web 2)", "(web 3)"…
     * Names compare folded; the name is cut so name + suffix fits 100 UTF-16 units.
     */
    fun combinedName(name: String, taken: Collection<String>): String {
        val t = taken.mapTo(HashSet(), SongKey::fold)
        if (SongKey.fold(name) !in t) return name
        var n = 1
        while (true) {
            val suffix = if (n == 1) " (web)" else " (web $n)"
            val c = name.take(MAX_PLAYLIST_NAME - suffix.length) + suffix
            if (SongKey.fold(c) !in t) return c
            n++
        }
    }
}
