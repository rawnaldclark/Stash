package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.model.Track

/**
 * Read-only queries behind "Export for Stash on the web" (WebLibraryExporter). Nothing here writes.
 *
 * Blocked songs never leave: every query drops a track the blocklist matches, by the same three keys
 * the Library's playlist feed uses ([TrackDao.getByPlaylist]).
 *
 * Songs are read as [SongColumns] only (the ten columns a web song is made from), never `t.*`: a big library
 * doesn't pull every file path and download flag into memory just to throw them away.
 */
@Dao
interface WebLibraryExportDao {

    /** The columns of a track that a web song is made from, and nothing else. */
    data class SongColumns(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        @ColumnInfo(name = "duration_ms") val durationMs: Long,
        val isrc: String?,
        @ColumnInfo(name = "spotify_uri") val spotifyUri: String?,
        @ColumnInfo(name = "youtube_id") val youtubeId: String?,
        @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
        /** Epoch ms. */
        @ColumnInfo(name = "date_added") val dateAdded: Long,
    ) {
        /** As a domain [Track], so it goes through the same portable description song links use. */
        fun toTrack(): Track = Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
            spotifyUri = spotifyUri,
            youtubeId = youtubeId,
            albumArtUrl = albumArtUrl,
            dateAdded = dateAdded,
        )
    }

    /** A liked song and when it was liked. */
    data class LikedRow(
        @Embedded val track: SongColumns,
        /** The Stash like, else the earliest time the song entered a Liked Songs playlist; null when neither is known. */
        @ColumnInfo(name = "liked_at") val likedAt: Long?,
    )

    /** One song of a playlist, with when it was added there. */
    data class PlaylistItemRow(
        @Embedded val track: SongColumns,
        @ColumnInfo(name = "added_at") val addedAt: Long?,
    )

    /** One play: the song and when it started. */
    data class PlayRow(
        @Embedded val track: SongColumns,
        @ColumnInfo(name = "played_at") val playedAt: Long,
    )

    /**
     * Your likes, newest first: a Stash like (`stash_liked_at`), or a song in a Liked Songs playlist mirrored from
     * Spotify or YouTube Music (or Stash's own). The same two meanings the likes-only backup uses
     * ([com.stash.core.data.db.DatabaseBackupManager.pruneToLikes]).
     */
    @Query(
        """
        SELECT $SONG_COLUMNS, COALESCE(t.stash_liked_at, (
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

    /** A playlist's first [limit] songs in its order (removed ones left out). */
    @Query(
        """
        SELECT $SONG_COLUMNS, pt.added_at AS added_at FROM playlist_tracks pt
        JOIN tracks t ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId
          AND pt.removed_at IS NULL
          AND $NOT_BLOCKED
        ORDER BY pt.position ASC, pt.added_at ASC, t.id ASC
        LIMIT :limit
        """,
    )
    suspend fun playlistItems(playlistId: Long, limit: Int): List<PlaylistItemRow>

    /** Shared mixes this phone follows (someone else's mix), by playlist. */
    @Query(
        "SELECT * FROM shared_mixes WHERE role = '${SharedMixEntity.ROLE_FOLLOWER}' " +
            "AND status = '${SharedMixEntity.STATUS_ACTIVE}'",
    )
    suspend fun followedMixes(): List<SharedMixEntity>

    /** The newest [limit] plays, newest first. */
    @Query(
        """
        SELECT $SONG_COLUMNS, le.started_at AS played_at FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE $NOT_BLOCKED
        ORDER BY le.started_at DESC, le.id DESC
        LIMIT :limit
        """,
    )
    suspend fun recentPlays(limit: Int): List<PlayRow>

    companion object {
        /** [SongColumns] of a track (`t`). */
        const val SONG_COLUMNS =
            "t.id, t.title, t.artist, t.album, t.duration_ms, t.isrc, t.spotify_uri, t.youtube_id, t.album_art_url, t.date_added"

        /** A track (`t`) the blocklist does not match. */
        const val NOT_BLOCKED = """NOT EXISTS (
            SELECT 1 FROM track_blocklist bl
            WHERE bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
               OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
               OR (bl.youtube_id IS NOT NULL AND bl.youtube_id = t.youtube_id)
        )"""
    }
}
