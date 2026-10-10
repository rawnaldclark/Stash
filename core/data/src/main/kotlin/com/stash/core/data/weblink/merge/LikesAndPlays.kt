package com.stash.core.data.weblink.merge

import com.stash.core.model.weblink.Hlc
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongIndex
import com.stash.core.model.weblink.SongKey

/*
 * Mirror merge rules of sync-v1 for likes and plays (stash-player `docs/sync-v1.md` "Merge rules"; spec §7.1, §7.2). Pure, a port
 * of the web's `src/lib/sync/merge.ts`; both pass `merge-vectors.json` (a byte-for-byte copy in src/test/resources/sync/).
 */

/** A like's state, and the `like` op's body: `on = false` is a tombstone. */
data class LikeRec<S : SongIdentity>(val s: S, val on: Boolean, val at: Hlc)

/** A song liked on this device. [external]: liked only through a Spotify / YouTube Music Liked Songs playlist. */
data class LikedSong<S : SongIdentity>(val s: S, val external: Boolean = false)

object LikesMerge {
    /**
     * Applies remote like ops in order. An op changes the record it matches ([SongIndex]: own key first, then the words with a
     * compatible ISRC) only when its `at` is newer; the record keeps its own song. An op that matches nothing is added, tombstones
     * too (so an older like arriving later can't bring the song back). Records keep their order; new ones follow in op order.
     */
    fun <S : SongIdentity> apply(state: List<LikeRec<S>>, ops: List<LikeRec<S>>): List<LikeRec<S>> {
        val out = state.toMutableList()
        val index = SongIndex.of(out.mapIndexed { i, r -> r.s to i })
        for (op in ops) {
            val i = index.find(op.s)
            if (i == null) {
                index.add(op.s, out.size)
                out += LikeRec(op.s, op.on, op.at)
            } else if (op.at > out[i].at) {
                out[i] = LikeRec(out[i].s, op.on, op.at)
            }
        }
        return out
    }

    /**
     * This device's like changes since [base], all stamped [at]: a liked song with no base record (or a base `on = false`) becomes
     * `on = true`; a base `on = true` song no longer liked becomes `on = false`. An [LikedSong.external] like whose base says
     * `on = false` (a mirrored unlike the phone can't carry out on Spotify / YouTube Music) is not sent back as a like.
     * Ops: likes in [liked] order, then unlikes in [base] order.
     */
    fun <S : SongIdentity> diff(base: List<LikeRec<S>>, liked: List<LikedSong<S>>, at: Hlc): List<LikeRec<S>> {
        val index = SongIndex.of(base.mapIndexed { i, r -> r.s to i })
        val seen = BooleanArray(base.size)
        val ops = mutableListOf<LikeRec<S>>()
        for (l in liked) {
            val i = index.find(l.s)
            if (i == null) {
                ops += LikeRec(l.s, true, at)
                continue
            }
            seen[i] = true
            if (!base[i].on && !l.external) ops += LikeRec(l.s, true, at)
        }
        base.forEachIndexed { i, r -> if (r.on && !seen[i]) ops += LikeRec(r.s, false, at) }
        return ops
    }
}

/** A play. [origin] is set on plays that came from another device: they never go back, and never feed mixes. */
data class PlayRec<S : SongIdentity>(val s: S, val playedAt: Long, val origin: String? = null)

data class PlaysState<S : SongIdentity>(val plays: List<PlayRec<S>>, val clearedBefore: Long)

sealed interface PlayOp<out S : SongIdentity> {
    data class Play<S : SongIdentity>(val s: S, val playedAt: Long) : PlayOp<S>
    data class ClearPlays(val before: Long, val at: Hlc) : PlayOp<Nothing>
}

object PlaysMerge {
    /** A play's identity: the song's words and when it started, so the ISRC and words keys of one song dedupe. */
    fun playId(s: SongIdentity, playedAt: Long): String = "${SongKey.textKey(s)}|$playedAt"

    /**
     * Applies remote play ops in order, from device [origin]. `clearPlays` raises `clearedBefore` and drops plays with
     * `playedAt < before`; a play older than `clearedBefore` or already present ([playId]) is skipped. The result is sorted newest
     * `playedAt` first (stable: existing plays before new ones on a tie).
     */
    fun <S : SongIdentity> apply(state: PlaysState<S>, ops: List<PlayOp<S>>, origin: String): PlaysState<S> {
        var plays = state.plays.toMutableList()
        var clearedBefore = state.clearedBefore
        val ids = plays.mapTo(HashSet()) { playId(it.s, it.playedAt) }
        for (op in ops) {
            when (op) {
                is PlayOp.ClearPlays -> if (op.before > clearedBefore) {
                    clearedBefore = op.before
                    plays = plays.filterTo(mutableListOf()) { it.playedAt >= clearedBefore }
                }
                is PlayOp.Play -> if (op.playedAt >= clearedBefore && ids.add(playId(op.s, op.playedAt))) {
                    plays += PlayRec(op.s, op.playedAt, origin)
                }
            }
        }
        return PlaysState(plays.sortedByDescending { it.playedAt }, clearedBefore)
    }

    /**
     * This device's play changes since the base (the play ids it last agreed on, [baseIds], and the clear mark): a `clearPlays`
     * first when its own mark moved, then its own plays (no origin, not cleared) not in the base, in [local] order.
     */
    fun <S : SongIdentity> diff(baseIds: Set<String>, baseClearedBefore: Long, local: PlaysState<S>, at: Hlc): List<PlayOp<S>> {
        val ops = mutableListOf<PlayOp<S>>()
        if (local.clearedBefore > baseClearedBefore) ops += PlayOp.ClearPlays(local.clearedBefore, at)
        for (p in local.plays) {
            if (p.origin != null || p.playedAt < local.clearedBefore || playId(p.s, p.playedAt) in baseIds) continue
            ops += PlayOp.Play(p.s, p.playedAt)
        }
        return ops
    }
}
