package com.stash.core.data.weblink.mirror

import com.stash.core.data.weblink.merge.PlaysMerge
import com.stash.core.data.weblink.merge.SyncHashes
import com.stash.core.model.weblink.Hlc
import com.stash.core.model.weblink.SongIndex

/**
 * The view of the space, kept as the log is read (sync-v1 §5.4, §7): a port of the web's `src/lib/sync/mirror-view.ts`. Pure.
 */
object MirrorView {
    /** Tombstones (an unlike, a deleted playlist) older than this are left out of a snapshot (sync-v1 §5.4). */
    const val TOMBSTONE_KEEP_MS = 90L * 86_400_000

    fun joinMark(kind: Kind, since: Long, device: String) = "${kind.wire}|$since|$device"

    /** A `pl` op's version hash (sync-v1 §6). */
    fun hashOf(op: MirrorOp.Pl): String = SyncHashes.playlistHash(op.name, op.items, op.follow)

    /**
     * One batch from [device] at log position [seq]. Likes: last writer wins by `at` (a like the view lacks is added, tombstones
     * too). Plays: grow-only; `clearPlays` drops the sender's plays before `before`, or with `all` every play and raises the mark.
     * Playlists: the last version in log order; a read-only one only from the phone ([fromPhone]).
     */
    fun applyBatch(view: SpaceView, device: String, seq: Long, ops: List<MirrorOp>, fromPhone: Boolean): SpaceView {
        val likes = view.likes.toMutableList()
        var plays = view.plays
        var clearedBefore = view.clearedBefore
        val playlists = view.playlists.toMutableMap()
        var joined = view.joined
        var likeIdx: SongIndex<Int>? = null
        var playIds: HashSet<String>? = null
        val newPlays = mutableListOf<ViewPlay>()
        for (op in ops) {
            when (op) {
                is MirrorOp.Like -> {
                    val idx = likeIdx ?: SongIndex.of(likes.mapIndexed { i, r -> r.s to i }).also { likeIdx = it }
                    val i = idx.find(op.s)
                    if (i == null) {
                        idx.add(op.s, likes.size)
                        likes += ViewLike(op.s, op.on, op.at, seq)
                    } else if (op.at > likes[i].at) {
                        likes[i] = ViewLike(likes[i].s, op.on, op.at, seq)
                    }
                }
                is MirrorOp.Play -> {
                    val ids = playIds ?: plays.mapTo(HashSet()) { PlaysMerge.playId(it.s, it.playedAt) }.also { playIds = it }
                    if (op.playedAt < clearedBefore) continue
                    if (!ids.add(PlaysMerge.playId(op.s, op.playedAt))) continue
                    newPlays += ViewPlay(op.s, op.playedAt, device, seq)
                }
                is MirrorOp.ClearPlays -> {
                    val all = plays + newPlays
                    newPlays.clear()
                    plays = if (op.all) {
                        if (op.before > clearedBefore) clearedBefore = op.before
                        all.filter { it.playedAt >= clearedBefore }
                    } else {
                        all.filter { it.device != device || it.playedAt >= op.before }
                    }
                    playIds = null
                }
                is MirrorOp.Joined -> {
                    val m = joinMark(op.kind, op.since, device)
                    if (m !in joined) joined = joined + m
                }
                is MirrorOp.Pl -> {
                    val cur = playlists[op.id]
                    if ((cur?.ro == true || op.ro) && !fromPhone) continue
                    playlists[op.id] = ViewPl(op.name, op.items, FollowRec.of(op.follow), op.ro, op.at, hashOf(op), seq)
                }
            }
        }
        if (newPlays.isNotEmpty()) plays = plays + newPlays
        val sorted = if (plays === view.plays) plays else plays.sortedByDescending { it.playedAt }
        return SpaceView(likes, sorted, clearedBefore, playlists, joined)
    }

    /** The view a snapshot gives: every record as written at its `uptoSeq`. */
    fun fromState(st: MirrorState): SpaceView = SpaceView(
        likes = st.likes.map { ViewLike(it.s, it.on, it.at, st.uptoSeq) },
        plays = st.plays.map { ViewPlay(it.s, it.playedAt, it.device, st.uptoSeq) }.sortedByDescending { it.playedAt },
        clearedBefore = st.playsClearedBefore,
        playlists = st.playlists.associate { it.id to ViewPl(it.name, it.items, FollowRec.of(it.follow), it.ro, it.at, it.hash, st.uptoSeq) },
        joined = st.joined.map { it.key },
    )

    /** Leaves out what the space held of each kind from before it was last turned on (`since`). */
    fun sinceFilter(view: SpaceView, c: MirrorConfig): SpaceView = SpaceView(
        likes = view.likes.filter { it.seq > c.likes.since },
        plays = view.plays.filter { it.seq > c.plays.since },
        clearedBefore = view.clearedBefore,
        playlists = view.playlists.filterValues { it.seq > c.playlists.since },
        joined = view.joined.filter { m ->
            val parts = m.split('|')
            val k = Kind.of(parts.getOrNull(0)) ?: return@filter false
            (parts.getOrNull(1)?.toLongOrNull() ?: -1) >= c.of(k).since
        },
    )

    /** The snapshot of [view] after log entry [uptoSeq]; tombstones older than 90 days (server time) left out. */
    fun stateOf(view: SpaceView, uptoSeq: Long, serverNow: Long): MirrorState {
        fun old(at: Hlc) = at.wall < serverNow - TOMBSTONE_KEEP_MS
        return MirrorState(
            uptoSeq = uptoSeq,
            likes = view.likes.filter { it.on || !old(it.at) }.map { MirrorState.StateLike(it.s, it.on, it.at) },
            plays = view.plays.map { MirrorState.StatePlay(it.s, it.playedAt, it.device) },
            playsClearedBefore = view.clearedBefore,
            playlists = view.playlists.filter { (_, p) -> p.items != null || !old(p.at) }
                .map { (id, p) -> MirrorState.StatePl(id, p.name, p.items, p.follow?.follow(), p.ro, p.at, p.hash) },
            joined = view.joined.mapNotNull { m ->
                val parts = m.split('|')
                val k = Kind.of(parts.getOrNull(0)) ?: return@mapNotNull null
                val since = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                JoinMark(k, since, parts.getOrNull(2) ?: return@mapNotNull null)
            },
        )
    }
}
