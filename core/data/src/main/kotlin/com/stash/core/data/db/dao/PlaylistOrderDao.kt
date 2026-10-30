package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.PlaylistOrderEntity
import com.stash.core.data.db.entity.PlaylistOrderEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * The listener's own order for a playlist (Edit order). Membership and the service's order stay in
 * `playlist_tracks`; this only says where each song sits. [TrackDao.getByPlaylist] reads it.
 */
@Dao
abstract class PlaylistOrderDao {

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_order WHERE playlist_id = :playlistId)")
    abstract fun observeIsCustom(playlistId: Long): Flow<Boolean>

    /**
     * Moves [trackId] to sit just before [beforeTrackId] (the song now below it), or, at the end of what's shown,
     * just after [afterTrackId] (the song now above it). Both are the neighbours on screen, which in Download mode
     * hides songs: songs not shown keep their places among the rest. The first move takes the playlist's order as
     * shown in full, then switches it to its own. Returns false when the song or both neighbours are gone (a sync
     * moved on meanwhile): nothing changes.
     */
    @Transaction
    open suspend fun move(playlistId: Long, trackId: Long, beforeTrackId: Long?, afterTrackId: Long?): Boolean {
        val members = memberIdsInOrder(playlistId)
        if (trackId !in members) return false
        // Seed, or rank the songs that arrived since (they sit last, in the service's order).
        if (!isCustom(playlistId)) {
            insertOrder(PlaylistOrderEntity(playlistId))
            deleteEntries(playlistId)
        }
        val ranked = entries(playlistId).associate { it.trackId to it.rank }
        var top = ranked.values.maxOrNull() ?: 0L
        val order = ArrayList<Pair<Long, Long>>(members.size)
        for (id in members) {
            val rank = ranked[id] ?: (top + SPACING).also { top = it; upsert(PlaylistOrderEntryEntity(playlistId, id, it)) }
            order += id to rank
        }
        val rest = order.filter { it.first != trackId }
        val at = when {
            beforeTrackId != null && rest.any { it.first == beforeTrackId } -> rest.indexOfFirst { it.first == beforeTrackId }
            afterTrackId != null && rest.any { it.first == afterTrackId } -> rest.indexOfFirst { it.first == afterTrackId } + 1
            else -> return false
        }
        if (order.map { it.first } == rest.map { it.first }.toMutableList().apply { add(at, trackId) }) return true
        val below = rest.getOrNull(at - 1)?.second
        val above = rest.getOrNull(at)?.second
        val rank = when {
            below == null && above == null -> SPACING
            below == null -> above!! - SPACING
            above == null -> below + SPACING
            above - below >= 2 -> below + (above - below) / 2
            else -> null
        }
        if (rank != null && rank > Long.MIN_VALUE / 2 && rank < Long.MAX_VALUE / 2) {
            upsert(PlaylistOrderEntryEntity(playlistId, trackId, rank))
        } else {
            // No room between the neighbours: space the whole playlist out again, in its new order.
            val ids = rest.map { it.first }.toMutableList().apply { add(at, trackId) }
            ids.forEachIndexed { i, id -> upsert(PlaylistOrderEntryEntity(playlistId, id, (i + 1L) * SPACING)) }
        }
        // Songs that left the playlist drop their places, so one added back later lands at the bottom.
        deleteDeparted(playlistId)
        return true
    }

    /** Back to the service's order (Use Spotify order …): the playlist's own order is forgotten. */
    @Transaction
    open suspend fun reset(playlistId: Long) {
        deleteEntries(playlistId)
        deleteOrder(playlistId)
    }

    /**
     * A playlist's members in the order the screen shows them, Download mode and blocked songs aside: the same
     * ORDER BY as [TrackDao.getByPlaylist].
     */
    @Query(
        """
        SELECT pt.track_id FROM playlist_tracks pt
        INNER JOIN playlists p ON pt.playlist_id = p.id
        LEFT JOIN playlist_order o ON o.playlist_id = pt.playlist_id
        LEFT JOIN playlist_order_entries e ON e.playlist_id = pt.playlist_id AND e.track_id = pt.track_id
        WHERE pt.playlist_id = :playlistId AND pt.removed_at IS NULL
        ORDER BY
            CASE WHEN o.playlist_id IS NOT NULL THEN e.rank IS NULL END ASC,
            CASE WHEN o.playlist_id IS NOT NULL THEN e.rank END ASC,
            CASE WHEN p.type = 'DAILY_MIX' AND o.playlist_id IS NULL THEN pt.added_at END DESC,
            pt.position ASC,
            pt.added_at ASC,
            pt.track_id ASC
        """
    )
    abstract suspend fun memberIdsInOrder(playlistId: Long): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_order WHERE playlist_id = :playlistId)")
    protected abstract suspend fun isCustom(playlistId: Long): Boolean

    @Query("SELECT * FROM playlist_order_entries WHERE playlist_id = :playlistId")
    abstract suspend fun entries(playlistId: Long): List<PlaylistOrderEntryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOrder(order: PlaylistOrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsert(entry: PlaylistOrderEntryEntity)

    @Query("DELETE FROM playlist_order WHERE playlist_id = :playlistId")
    protected abstract suspend fun deleteOrder(playlistId: Long)

    @Query("DELETE FROM playlist_order_entries WHERE playlist_id = :playlistId")
    protected abstract suspend fun deleteEntries(playlistId: Long)

    @Query(
        """
        DELETE FROM playlist_order_entries WHERE playlist_id = :playlistId AND track_id NOT IN (
            SELECT track_id FROM playlist_tracks WHERE playlist_id = :playlistId AND removed_at IS NULL
        )
        """
    )
    protected abstract suspend fun deleteDeparted(playlistId: Long)

    companion object {
        /** Room between neighbouring songs: ~20 halvings before a playlist is spaced out again. */
        const val SPACING = 1L shl 20
    }
}
