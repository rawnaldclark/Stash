package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.stash.core.data.db.entity.SyncUndoPointEntity
import kotlinx.coroutines.flow.Flow

/**
 * Capture/restore for "Undo last sync".
 *
 * Everything is bulk `INSERT … SELECT` against the live tables — no rows are
 * marshalled through Kotlin — so capturing a 14.5k-membership library is a single
 * fast statement rather than 14.5k object round-trips.
 *
 * A sync only ever touches playlists it mirrors from Spotify and YouTube
 * ([SYNCED_SOURCES]): Stash mixes, followed mixes, the user's own playlists and
 * web imports change on their own schedules, so restore leaves them as they are
 * now. Within those, it puts back only what a sync writes: which playlists show,
 * and the sync-added tracks in them. A Download switch (`sync_enabled`,
 * `keep_offline`), a track the user added by hand, or one they removed (a
 * tombstone) is never written by a sync, so any change to it since the point
 * is the user's own, and stays.
 */
@Dao
interface SyncUndoDao {

    // ── Capture (before the sync changes anything) ─────────────────────────

    @Query(
        """
        INSERT OR REPLACE INTO sync_undo_playlists (sync_id, playlist_id, is_active, sync_enabled)
        SELECT :syncId, id, is_active, sync_enabled FROM playlists
        """
    )
    suspend fun capturePlaylists(syncId: Long)

    @Query(
        """
        INSERT OR REPLACE INTO sync_undo_memberships
            (sync_id, playlist_id, track_id, position, added_at, removed_at, locally_added)
        SELECT :syncId, playlist_id, track_id, position, added_at, removed_at, locally_added
        FROM playlist_tracks
        """
    )
    suspend fun captureMemberships(syncId: Long)

    @Query("INSERT OR REPLACE INTO sync_undo_points (sync_id, created_at, playlist_count, membership_count) VALUES (:syncId, :createdAtEpochMs, :playlistCount, :membershipCount)")
    suspend fun insertPoint(syncId: Long, createdAtEpochMs: Long, playlistCount: Int, membershipCount: Int)

    @Query("SELECT COUNT(*) FROM sync_undo_playlists WHERE sync_id = :syncId")
    suspend fun countCapturedPlaylists(syncId: Long): Int

    @Query("SELECT COUNT(*) FROM sync_undo_memberships WHERE sync_id = :syncId")
    suspend fun countCapturedMemberships(syncId: Long): Int

    /**
     * Snapshot the library's current shape, then drop all but the newest
     * [keep] points. One transaction so a kill mid-capture can't leave a point
     * advertising a restore its rows don't back.
     */
    @Transaction
    suspend fun capture(syncId: Long, nowEpochMs: Long, keep: Int = MAX_UNDO_POINTS) {
        capturePlaylists(syncId)
        captureMemberships(syncId)
        insertPoint(
            syncId = syncId,
            createdAtEpochMs = nowEpochMs,
            playlistCount = countCapturedPlaylists(syncId),
            membershipCount = countCapturedMemberships(syncId),
        )
        prune(keep)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM sync_undo_points WHERE sync_id = :syncId)")
    suspend fun hasPoint(syncId: Long): Boolean

    /**
     * [capture] unless sync [syncId] already has its point; returns whether it
     * captured. The fetch step captures just before it hides Spotify playlists
     * the account no longer has; the diff step then keeps that point instead of
     * snapshotting the library a second time, already without them.
     */
    @Transaction
    suspend fun captureOnce(syncId: Long, nowEpochMs: Long, keep: Int = MAX_UNDO_POINTS): Boolean {
        if (hasPoint(syncId)) return false
        capture(syncId, nowEpochMs, keep)
        return true
    }

    // ── What the sync changed ──────────────────────────────────────────────

    /**
     * How many playlists the sync behind point [syncId] (captured at
     * [sinceEpochMs]) changed, as Undo would put them back: shown or hidden,
     * added (see [hidePlaylistsAddedSince]), or gaining or losing synced tracks.
     * Order alone doesn't count. The Undo card shows this, not every playlist.
     */
    @Query(
        """
        SELECT COUNT(*) FROM playlists p
        WHERE p.source IN $SYNCED_SOURCES AND (
            EXISTS (SELECT 1 FROM sync_undo_playlists u
                    WHERE u.sync_id = :syncId AND u.playlist_id = p.id AND u.is_active != p.is_active
                      AND p.user_hidden = 0)
            OR (p.is_active = 1 AND p.date_added >= :sinceEpochMs
                AND p.sync_enabled = 0 AND p.keep_offline = 0
                AND p.id NOT IN (SELECT playlist_id FROM sync_undo_playlists WHERE sync_id = :syncId))
            OR p.id IN (
                SELECT playlist_id FROM (
                    SELECT playlist_id, track_id FROM playlist_tracks
                    WHERE locally_added = 0 AND removed_at IS NULL
                      AND playlist_id IN (SELECT playlist_id FROM sync_undo_playlists WHERE sync_id = :syncId)
                    EXCEPT
                    SELECT playlist_id, track_id FROM sync_undo_memberships
                    WHERE sync_id = :syncId AND removed_at IS NULL
                )
                UNION
                SELECT playlist_id FROM (
                    SELECT playlist_id, track_id FROM sync_undo_memberships
                    WHERE sync_id = :syncId AND removed_at IS NULL
                    EXCEPT
                    SELECT playlist_id, track_id FROM playlist_tracks
                )
            )
        )
        """
    )
    suspend fun countChangedPlaylists(syncId: Long, sinceEpochMs: Long): Int

    // ── Restore ─────────────────────────────────────────────────────────────

    /**
     * Shown/hidden back to the point: playlists the sync hid come back, ones it
     * revived go again. Only `is_active`; the Download switch the point also
     * holds is the user's, and a sync never sets it.
     */
    @Query(
        """
        UPDATE playlists SET
            is_active = (SELECT u.is_active FROM sync_undo_playlists u
                         WHERE u.sync_id = :syncId AND u.playlist_id = playlists.id)
        WHERE source IN $SYNCED_SOURCES
          -- One the user took out of the library stays out: undo puts back what sync did.
          AND user_hidden = 0
          AND id IN (SELECT playlist_id FROM sync_undo_playlists WHERE sync_id = :syncId)
        """
    )
    suspend fun restorePlaylistVisibility(syncId: Long): Int

    /**
     * Hides the playlists the sync added: Spotify/YouTube rows that weren't
     * there at the point ([sinceEpochMs]). Hidden, not deleted, like any
     * playlist a sync stops seeing, so the next sync brings one back if it is
     * still in the account. A playlist whose Download switch the user has since
     * turned on is theirs now and stays.
     */
    @Query(
        """
        UPDATE playlists SET is_active = 0
        WHERE is_active = 1
          AND source IN $SYNCED_SOURCES
          AND date_added >= :sinceEpochMs
          AND sync_enabled = 0 AND keep_offline = 0
          AND id NOT IN (SELECT playlist_id FROM sync_undo_playlists WHERE sync_id = :syncId)
        """
    )
    suspend fun hidePlaylistsAddedSince(syncId: Long, sinceEpochMs: Long): Int

    /**
     * Forgets the remote version id of each playlist the sync re-read. The diff
     * skips a playlist whose id hasn't moved, so once its tracks were put back
     * the next sync would have left it undone for good, while re-reading the
     * rest: half the library redone and half not.
     *
     * A playlist the sync skipped as unchanged (its mirror stamp, see
     * SpotifyMirrorStamp, still matched) was not re-read: `last_synced` didn't
     * move, its rows go back exactly as they were, and its stamp stays valid.
     */
    @Query(
        """
        UPDATE playlists SET snapshot_id = NULL
        WHERE snapshot_id IS NOT NULL
          AND last_synced >= :sinceEpochMs
          AND id IN (SELECT playlist_id FROM sync_undo_playlists WHERE sync_id = :syncId)
        """
    )
    suspend fun forgetSnapshotIdsSyncedSince(syncId: Long, sinceEpochMs: Long): Int

    /**
     * Clear only SYNC-added membership for the snapshotted Spotify/YouTube
     * playlists. Rows the user added by hand (`locally_added = 1`) and tracks
     * they removed (`removed_at` set) are left alone — undoing a sync must never
     * throw away the user's own work, including changes made after the sync ran.
     * Same rows as the sync's own REFRESH clear.
     */
    @Query(
        """
        DELETE FROM playlist_tracks
        WHERE locally_added = 0 AND removed_at IS NULL
          AND playlist_id IN (SELECT id FROM playlists WHERE source IN $SYNCED_SOURCES)
          AND playlist_id IN (SELECT playlist_id FROM sync_undo_playlists WHERE sync_id = :syncId)
        """
    )
    suspend fun clearSyncedMembershipForRestore(syncId: Long): Int

    /**
     * Put the snapshotted membership back.
     *
     * `OR IGNORE` + the two `IN` guards stand in for the foreign keys the undo
     * tables deliberately don't have: a playlist or track deleted since the
     * snapshot is skipped rather than failing the whole restore, and a surviving
     * user-added row or removal is never overwritten.
     */
    @Query(
        """
        INSERT OR IGNORE INTO playlist_tracks
            (playlist_id, track_id, position, added_at, removed_at, locally_added)
        SELECT playlist_id, track_id, position, added_at, removed_at, locally_added
        FROM sync_undo_memberships
        WHERE sync_id = :syncId
          AND playlist_id IN (SELECT id FROM playlists WHERE source IN $SYNCED_SOURCES)
          AND track_id IN (SELECT id FROM tracks)
        """
    )
    suspend fun restoreMemberships(syncId: Long)

    /**
     * How many snapshotted memberships are still restorable — same predicate as
     * [restoreMemberships]. Room can't return a row count from an INSERT, and the
     * honest number is the one that survives the FK re-checks, not the raw
     * snapshot size.
     */
    @Query(
        """
        SELECT COUNT(*) FROM sync_undo_memberships
        WHERE sync_id = :syncId
          AND playlist_id IN (SELECT id FROM playlists WHERE source IN $SYNCED_SOURCES)
          AND track_id IN (SELECT id FROM tracks)
        """
    )
    suspend fun countRestorableMemberships(syncId: Long): Int

    @Query("SELECT * FROM sync_undo_points WHERE sync_id = :syncId")
    suspend fun pointNow(syncId: Long): SyncUndoPointEntity?

    /**
     * Undo one sync, atomically: the playlists it hid come back, the ones it
     * added are hidden, and the ones it emptied or refilled get their tracks
     * back. Returns the number of membership rows restored (0, changing nothing,
     * when the point is gone).
     *
     * Every point is dropped afterwards, this one and older: the same undo can't
     * be applied twice, and the card doesn't then offer the sync before, whose
     * point predates this one and would undo work the user just kept.
     */
    @Transaction
    suspend fun restore(syncId: Long): Int {
        val since = pointNow(syncId)?.createdAtEpochMs ?: return 0
        restorePlaylistVisibility(syncId)
        hidePlaylistsAddedSince(syncId, since)
        forgetSnapshotIdsSyncedSince(syncId, since)
        clearSyncedMembershipForRestore(syncId)
        val restored = countRestorableMemberships(syncId)
        restoreMemberships(syncId)
        deleteAllPoints()
        return restored
    }

    // ── Listing / retention ─────────────────────────────────────────────────

    @Query("SELECT * FROM sync_undo_points ORDER BY created_at DESC LIMIT 1")
    fun latestPoint(): Flow<SyncUndoPointEntity?>

    @Query("SELECT * FROM sync_undo_points ORDER BY created_at DESC LIMIT 1")
    suspend fun latestPointNow(): SyncUndoPointEntity?

    @Transaction
    suspend fun deletePoint(syncId: Long) {
        deletePointRow(syncId)
        deletePointPlaylists(syncId)
        deletePointMemberships(syncId)
    }

    @Query("DELETE FROM sync_undo_points WHERE sync_id = :syncId")
    suspend fun deletePointRow(syncId: Long)

    @Query("DELETE FROM sync_undo_playlists WHERE sync_id = :syncId")
    suspend fun deletePointPlaylists(syncId: Long)

    @Query("DELETE FROM sync_undo_memberships WHERE sync_id = :syncId")
    suspend fun deletePointMemberships(syncId: Long)

    /** Drops every restore point. */
    @Transaction
    suspend fun deleteAllPoints() {
        deleteAllPointRows()
        deleteAllPointPlaylists()
        deleteAllPointMemberships()
    }

    @Query("DELETE FROM sync_undo_points")
    suspend fun deleteAllPointRows()

    @Query("DELETE FROM sync_undo_playlists")
    suspend fun deleteAllPointPlaylists()

    @Query("DELETE FROM sync_undo_memberships")
    suspend fun deleteAllPointMemberships()

    @Query("SELECT sync_id FROM sync_undo_points ORDER BY created_at DESC LIMIT -1 OFFSET :keep")
    suspend fun pointIdsBeyond(keep: Int): List<Long>

    /** Drop everything except the newest [keep] points. */
    @Transaction
    suspend fun prune(keep: Int = MAX_UNDO_POINTS) {
        pointIdsBeyond(keep).forEach { deletePoint(it) }
    }

    companion object {
        /**
         * How many restore points to keep. Undo is a "that sync went wrong, put it
         * back" tool used within minutes, not a backup history — and each point is
         * a full copy of the membership table, so the cap keeps the DB bounded.
         */
        const val MAX_UNDO_POINTS = 3

        /** The `playlists.source` values a sync writes: the playlists it mirrors. */
        const val SYNCED_SOURCES = "('SPOTIFY', 'YOUTUBE')"
    }
}
