package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Summary projection for artist browsing.
 *
 * @property artist  Artist name.
 * @property trackCount  Number of tracks by this artist.
 * @property totalDurationMs  Combined duration of all tracks by this artist.
 */
data class ArtistSummary(
    val artist: String,
    val trackCount: Int,
    val totalDurationMs: Long,
)

/**
 * Summary projection for album browsing.
 *
 * @property album  Album name.
 * @property artist  Primary artist on the album.
 * @property trackCount  Number of tracks in the album.
 * @property artPath  Local path to album artwork, if available.
 */
data class AlbumSummary(
    val album: String,
    val artist: String,
    val trackCount: Int,
    val artPath: String?,
)

/**
 * Data-access object for [TrackEntity].
 *
 * Provides CRUD operations, various sorted/filtered queries, full-text
 * search via FTS4, and play-tracking helpers.
 */
@Dao
interface TrackDao {

    // ── Inserts ─────────────────────────────────────────────────────────

    /** Insert a single track, replacing on conflict (e.g. same Spotify URI). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity): Long

    /** Insert multiple tracks, replacing on conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    // ── Update / Delete ─────────────────────────────────────────────────

    /** Update an existing track entity. */
    @Update
    suspend fun update(track: TrackEntity)

    /** Delete a track entity. */
    @Delete
    suspend fun delete(track: TrackEntity)

    // ── List queries (all reactive) ─────────────────────────────────────

    /** All tracks ordered by most-recently-added first. */
    @Query("SELECT * FROM tracks ORDER BY date_added DESC")
    fun getAllByDateAdded(): Flow<List<TrackEntity>>

    /** All tracks by a specific artist, ordered by album then title. */
    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY album ASC, title ASC")
    fun getByArtist(artist: String): Flow<List<TrackEntity>>

    /**
     * All tracks belonging to a playlist, resolved through the
     * [playlist_tracks] join table. Only includes non-removed entries.
     */
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId AND pt.removed_at IS NULL
        ORDER BY pt.position ASC
        """
    )
    fun getByPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    /** Most-recently-added downloaded tracks, limited to [limit] results. */
    @Query("SELECT * FROM tracks WHERE is_downloaded = 1 ORDER BY date_added DESC LIMIT :limit")
    fun getRecentlyAdded(limit: Int): Flow<List<TrackEntity>>

    /** Most-played tracks, limited to [limit] results. */
    @Query("SELECT * FROM tracks WHERE play_count > 0 ORDER BY play_count DESC LIMIT :limit")
    fun getMostPlayed(limit: Int): Flow<List<TrackEntity>>

    // ── Single-item lookups ─────────────────────────────────────────────

    /** Find a track by its Spotify URI, or null if not found. */
    @Query("SELECT * FROM tracks WHERE spotify_uri = :spotifyUri LIMIT 1")
    suspend fun findBySpotifyUri(spotifyUri: String): TrackEntity?

    /** Find a track by its YouTube video ID, or null if not found. */
    @Query("SELECT * FROM tracks WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun findByYoutubeId(youtubeId: String): TrackEntity?

    /**
     * Find a track by its canonical title + artist combination.
     * Used for cross-source deduplication.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE canonical_title = :title AND canonical_artist = :artist
        LIMIT 1
        """
    )
    suspend fun findByCanonicalIdentity(title: String, artist: String): TrackEntity?

    /** Find a track by primary key. */
    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): TrackEntity?

    // ── Download tracking ────────────────────────────────────────────────

    /**
     * Atomically marks a track as downloaded and records its file path and size.
     *
     * @param trackId       Primary key of the track.
     * @param filePath      Absolute path to the downloaded audio file on disk.
     * @param fileSizeBytes Size of the downloaded file in bytes.
     */
    @Query(
        """
        UPDATE tracks
        SET is_downloaded = 1,
            file_path = :filePath,
            file_size_bytes = :fileSizeBytes,
            date_added = :downloadedAt
        WHERE id = :trackId
        """
    )
    suspend fun markAsDownloaded(
        trackId: Long,
        filePath: String,
        fileSizeBytes: Long,
        downloadedAt: Long = System.currentTimeMillis(),
    )

    // ── Play tracking ───────────────────────────────────────────────────

    /** Atomically increment [play_count] for the given track. */
    @Query("UPDATE tracks SET play_count = play_count + 1 WHERE id = :trackId")
    suspend fun incrementPlayCount(trackId: Long)

    /** Update the [last_played] timestamp for the given track. */
    @Query("UPDATE tracks SET last_played = :timestamp WHERE id = :trackId")
    suspend fun updateLastPlayed(trackId: Long, timestamp: Long)

    /**
     * Backfill: set date_added to now for all downloaded Spotify tracks.
     * These tracks had date_added set at sync time (when discovered), not
     * download time. This one-time fix makes them appear in Recently Added.
     */
    @Query("UPDATE tracks SET date_added = :now WHERE is_downloaded = 1 AND source = 'SPOTIFY'")
    suspend fun backfillSpotifyDateAdded(now: Long = System.currentTimeMillis())

    // ── Full-text search ────────────────────────────────────────────────

    /**
     * Search tracks by title, artist, or album using FTS4.
     *
     * The query string supports SQLite FTS match syntax (e.g. prefix
     * searches with `*`).
     */
    @Query(
        """
        SELECT tracks.* FROM tracks
        JOIN tracks_fts ON tracks.rowid = tracks_fts.rowid
        WHERE tracks_fts MATCH :query
        """
    )
    fun search(query: String): Flow<List<TrackEntity>>

    // ── Count / storage queries ─────────────────────────────────────────

    /** Total number of downloaded tracks (reactive). */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1")
    fun getTotalCount(): Flow<Int>

    /** Total number of downloaded tracks (one-shot). */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1")
    suspend fun getCount(): Int

    /** Sum of all downloaded track file sizes in bytes (reactive). */
    @Query("SELECT COALESCE(SUM(file_size_bytes), 0) FROM tracks WHERE is_downloaded = 1")
    fun getTotalStorageBytes(): Flow<Long>

    /** Count of downloaded tracks from Spotify. */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1 AND source = 'SPOTIFY'")
    fun getSpotifyDownloadedCount(): Flow<Int>

    /** Count of downloaded tracks from YouTube. */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1 AND source = 'YOUTUBE'")
    fun getYouTubeDownloadedCount(): Flow<Int>

    // ── Aggregate queries ───────────────────────────────────────────────

    /**
     * All distinct artists with their track count and total duration.
     * Ordered by artist name ascending.
     */
    @Query(
        """
        SELECT artist,
               COUNT(*) AS trackCount,
               SUM(duration_ms) AS totalDurationMs
        FROM tracks
        GROUP BY artist
        ORDER BY artist ASC
        """
    )
    fun getAllArtists(): Flow<List<ArtistSummary>>

    /**
     * All distinct albums with their primary artist, track count, and
     * local art path. Ordered by album name ascending.
     */
    @Query(
        """
        SELECT album,
               artist,
               COUNT(*) AS trackCount,
               album_art_path AS artPath
        FROM tracks
        WHERE album != ''
        GROUP BY album, artist
        ORDER BY album ASC
        """
    )
    fun getAllAlbums(): Flow<List<AlbumSummary>>
}
