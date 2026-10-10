package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track

/**
 * The library as Link Stash on the web's mirror reads it (link-sync spec §7, §9): likes with whether each is a Stash like, this
 * phone's own plays, and the playlists one can choose to mirror with their songs. Read-only; the mirror writes through the
 * library's usual paths. Songs are read as [MirrorSong] (the columns a wire song is made from, plus what tells a phone-only file).
 */
@Dao
interface MirrorDao {
    data class MirrorSong(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        @ColumnInfo(name = "duration_ms") val durationMs: Long,
        val isrc: String?,
        @ColumnInfo(name = "spotify_uri") val spotifyUri: String?,
        @ColumnInfo(name = "youtube_id") val youtubeId: String?,
        @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
        @ColumnInfo(name = "date_added") val dateAdded: Long,
        val source: MusicSource,
        @ColumnInfo(name = "is_downloaded") val isDownloaded: Boolean,
    ) {
        fun toTrack(): Track = Track(
            id = id, title = title, artist = artist, album = album, durationMs = durationMs, isrc = isrc, spotifyUri = spotifyUri,
            youtubeId = youtubeId, albumArtUrl = albumArtUrl, dateAdded = dateAdded, source = source, isDownloaded = isDownloaded,
        )
    }

    /** A liked song. [stash]: a Stash like (the heart, or Stash's own Liked Songs); else liked only through Spotify / YouTube Music. */
    data class LikedRow(@Embedded val song: MirrorSong, val stash: Boolean, @ColumnInfo(name = "liked_at") val likedAt: Long?)

    data class PlayRow(@Embedded val song: MirrorSong, @ColumnInfo(name = "played_at") val playedAt: Long)

    data class ItemRow(@Embedded val song: MirrorSong)

    /** Every liked song, newest like first: the same two meanings of a like as the export ([WebLibraryExportDao.likes]). */
    @Query(
        """
        SELECT $COLUMNS,
          (t.stash_liked_at IS NOT NULL OR EXISTS (
              SELECT 1 FROM playlist_tracks pt JOIN playlists p ON p.id = pt.playlist_id
              WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type = 'STASH_LIKED'
          )) AS stash,
          COALESCE(t.stash_liked_at, (
              SELECT MIN(pt.added_at) FROM playlist_tracks pt JOIN playlists p ON p.id = pt.playlist_id
              WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
          )) AS liked_at
        FROM tracks t
        WHERE t.stash_liked_at IS NOT NULL OR EXISTS (
            SELECT 1 FROM playlist_tracks pt JOIN playlists p ON p.id = pt.playlist_id
            WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
        )
        ORDER BY liked_at DESC, t.id ASC
        """,
    )
    suspend fun likes(): List<LikedRow>

    /** This phone's own plays (never one from another device) that started at or after [since], newest first. */
    @Query(
        """
        SELECT $COLUMNS, le.started_at AS played_at FROM listening_events le JOIN tracks t ON t.id = le.track_id
        WHERE le.origin_device IS NULL AND le.started_at >= :since AND le.started_at > 0
        ORDER BY le.started_at DESC, le.id DESC
        LIMIT :limit
        """,
    )
    suspend fun ownPlays(since: Long, limit: Int): List<PlayRow>

    /** When the [offset]-th newest own play started (0-based), or null when there are fewer. */
    @Query(
        """
        SELECT started_at FROM listening_events WHERE origin_device IS NULL AND started_at > 0
        ORDER BY started_at DESC, id DESC LIMIT 1 OFFSET :offset
        """,
    )
    suspend fun ownPlayAt(offset: Int): Long?

    /** Every play's words and start between [fromMs] and [toMs], to skip a play that is already here. */
    @Query(
        """
        SELECT t.title, t.artist, t.isrc, le.started_at FROM listening_events le JOIN tracks t ON t.id = le.track_id
        WHERE le.started_at BETWEEN :fromMs AND :toMs
        """,
    )
    suspend fun playKeysBetween(fromMs: Long, toMs: Long): List<WebLibraryExportDao.PlayKeyRow>

    @Query("DELETE FROM listening_events WHERE origin_device = :origin AND started_at < :before")
    suspend fun dropPlaysFrom(origin: String, before: Long): Int

    /**
     * "Clear on all your devices" from another device: every play from another device before [before], and this phone's own
     * plays before it that the mirror ever covered (from [floor] on): not the older History the mirror never sent.
     */
    @Query("DELETE FROM listening_events WHERE started_at < :before AND (origin_device IS NOT NULL OR started_at >= :floor)")
    suspend fun dropPlaysBefore(before: Long, floor: Long): Int

    /**
     * The playlists one can choose to mirror: your own and the ones synced from Spotify or YouTube Music, as Library shows them
     * ([WebLibraryExportDao.playlists]), oldest first.
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
                  SELECT 1 FROM playlist_tracks pt JOIN tracks t ON pt.track_id = t.id
                  WHERE pt.playlist_id = p.id AND pt.removed_at IS NULL
                    AND (t.is_downloaded = 1 OR t.is_streamable = 1 OR t.is_streamable_checked_at IS NULL)
              )
          )
        ORDER BY p.date_added ASC, p.id ASC
        """,
    )
    suspend fun playlists(): List<PlaylistEntity>

    /** A playlist's songs in order (removed ones left out). */
    @Query(
        """
        SELECT $COLUMNS FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId AND pt.removed_at IS NULL
        ORDER BY pt.position ASC, pt.added_at ASC, t.id ASC
        LIMIT :limit
        """,
    )
    suspend fun playlistItems(playlistId: Long, limit: Int): List<ItemRow>

    /** How many songs each playlist holds, for the chooser. */
    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :playlistId AND removed_at IS NULL")
    suspend fun playlistCount(playlistId: Long): Int

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    // ------------------------------------------------------------------------------------- the change signal's cheap digests

    /** Likes: how many and the newest like time (a like or an unlike moves one of them). */
    @Query(
        """
        SELECT COUNT(*) || ':' || COALESCE(SUM(t.id), 0) || ':' || COALESCE(MAX(t.stash_liked_at), 0) FROM tracks t
        WHERE t.stash_liked_at IS NOT NULL OR EXISTS (
            SELECT 1 FROM playlist_tracks pt JOIN playlists p ON p.id = pt.playlist_id
            WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
        )
        """,
    )
    suspend fun likesDigest(): String

    /** Own plays: the newest one. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM listening_events WHERE origin_device IS NULL")
    suspend fun playsDigest(): Long

    /** Playlists [ids]: names, songs and order. */
    @Query(
        """
        SELECT COALESCE(GROUP_CONCAT(d, ';'), '') FROM (
            SELECT p.id || ':' || p.name || ':' || p.is_active || ':' || (
                SELECT COUNT(*) || '/' || COALESCE(SUM(pt.track_id * (pt.position + 1)), 0) FROM playlist_tracks pt
                WHERE pt.playlist_id = p.id AND pt.removed_at IS NULL
            ) AS d FROM playlists p WHERE p.id IN (:ids) ORDER BY p.id
        )
        """,
    )
    suspend fun playlistsDigest(ids: List<Long>): String

    /** How many playlists exist at all (a new one matters to "Mirror new playlists too"). */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(id), 0) FROM playlists WHERE type = 'CUSTOM' AND is_active = 1")
    suspend fun playlistSetDigest(): String

    companion object {
        const val COLUMNS =
            "t.id, t.title, t.artist, t.album, t.duration_ms, t.isrc, t.spotify_uri, t.youtube_id, t.album_art_url, t.date_added, t.source, t.is_downloaded"
    }
}
