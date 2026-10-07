package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.db.entity.TrackEntity

/**
 * Read-only queries behind "Export for Stash on the web" (WebLibraryExporter). Nothing here writes.
 *
 * Blocked songs never leave: every query drops a track the blocklist matches, by the same three keys
 * the Library's playlist feed uses ([TrackDao.getByPlaylist]).
 */
@Dao
interface WebLibraryExportDao {

    /** A liked song and when it was liked. */
    data class LikedRow(
        @Embedded val track: TrackEntity,
        /** The Stash like, else the earliest time the song entered a Liked Songs playlist; null when neither is known. */
        @ColumnInfo(name = "liked_at") val likedAt: Long?,
    )

    /** One song of a playlist, with when it was added there. */
    data class PlaylistItemRow(
        @Embedded val track: TrackEntity,
        @ColumnInfo(name = "added_at") val addedAt: Long?,
    )

    /** One play: the song and when it started. */
    data class PlayRow(
        @Embedded val track: TrackEntity,
        @ColumnInfo(name = "played_at") val playedAt: Long,
    )

    /**
     * Your likes, newest first: a Stash like (`stash_liked_at`), or a song in a Liked Songs playlist mirrored from
     * Spotify or YouTube Music (or Stash's own). The same two meanings the likes-only backup uses
     * ([com.stash.core.data.db.DatabaseBackupManager.pruneToLikes]).
     */
    @Query(
        """
        SELECT t.*, COALESCE(t.stash_liked_at, (
            SELECT MIN(pt.added_at) FROM playlist_tracks pt
            JOIN playlists p ON p.id = pt.playlist_id
            WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
        )) AS liked_at
        FROM tracks t
        WHERE (
            t.stash_liked_at IS NOT NULL
            OR EXISTS (
                SELECT 1 FROM playlist_tracks pt
                JOIN playlists p ON p.id = pt.playlist_id
                WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
            )
        )
        AND $NOT_BLOCKED
        ORDER BY liked_at DESC, t.id ASC
        """,
    )
    suspend fun likes(): List<LikedRow>

    /**
     * Your own playlists ([com.stash.core.model.PlaylistType.CUSTOM]) as Library shows them (online, see
     * [PlaylistDao.getAllVisible]): active, and synced, pinned, made in Stash, a followed shared mix, or holding a
     * song you can play. Daily Mixes, Stash Mixes, Liked Songs and the Downloads list are other types and never
     * come here. Oldest first.
     */
    @Query(
        """
        SELECT p.* FROM playlists p
        WHERE p.type = 'CUSTOM'
          AND p.is_active = 1
          AND (
              p.sync_enabled = 1
              OR p.pinned_to_home_at IS NOT NULL
              OR p.source_id LIKE 'share:%'
              OR p.source_id LIKE 'custom_%'
              OR EXISTS (
                  SELECT 1 FROM playlist_tracks pt
                  JOIN tracks t ON pt.track_id = t.id
                  WHERE pt.playlist_id = p.id
                    AND pt.removed_at IS NULL
                    AND (t.is_downloaded = 1 OR t.is_streamable = 1 OR t.is_streamable_checked_at IS NULL)
              )
          )
        ORDER BY p.date_added ASC, p.id ASC
        """,
    )
    suspend fun playlists(): List<PlaylistEntity>

    /** A playlist's songs in its order (removed ones left out). */
    @Query(
        """
        SELECT t.*, pt.added_at AS added_at FROM playlist_tracks pt
        JOIN tracks t ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId
          AND pt.removed_at IS NULL
          AND $NOT_BLOCKED
        ORDER BY pt.position ASC, pt.added_at ASC, t.id ASC
        """,
    )
    suspend fun playlistItems(playlistId: Long): List<PlaylistItemRow>

    /** Shared mixes this phone follows (someone else's mix), by playlist. */
    @Query(
        "SELECT * FROM shared_mixes WHERE role = '${SharedMixEntity.ROLE_FOLLOWER}' " +
            "AND status = '${SharedMixEntity.STATUS_ACTIVE}'",
    )
    suspend fun followedMixes(): List<SharedMixEntity>

    /** The newest [limit] plays, newest first. */
    @Query(
        """
        SELECT t.*, le.started_at AS played_at FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE $NOT_BLOCKED
        ORDER BY le.started_at DESC, le.id DESC
        LIMIT :limit
        """,
    )
    suspend fun recentPlays(limit: Int): List<PlayRow>

    companion object {
        /** A track (`t`) the blocklist does not match. */
        const val NOT_BLOCKED = """NOT EXISTS (
            SELECT 1 FROM track_blocklist bl
            WHERE bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
               OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
               OR (bl.youtube_id IS NOT NULL AND bl.youtube_id = t.youtube_id)
        )"""
    }
}
