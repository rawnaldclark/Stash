package com.stash.core.data.repository

import com.stash.core.data.db.dao.AlbumSummary
import com.stash.core.data.db.dao.ArtistSummary
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Primary repository interface for music data operations.
 *
 * All list queries return [Flow] for reactive UI updates. Mutation
 * methods are suspend functions that complete once the database
 * write is committed.
 */
interface MusicRepository {

    // ── Track queries ───────────────────────────────────────────────────

    /** All tracks ordered by most-recently-added first. */
    fun getAllTracks(): Flow<List<Track>>

    /** Tracks by a specific artist. */
    fun getTracksByArtist(artist: String): Flow<List<Track>>

    /** Tracks belonging to a playlist. */
    fun getTracksByPlaylist(playlistId: Long): Flow<List<Track>>

    /** Distinct artists with track counts and durations. */
    fun getAllArtists(): Flow<List<ArtistSummary>>

    /** Distinct albums with metadata. */
    fun getAllAlbums(): Flow<List<AlbumSummary>>

    /** Most-recently-added tracks. */
    fun getRecentlyAdded(limit: Int = 20): Flow<List<Track>>

    /** Most-played tracks. */
    fun getMostPlayed(limit: Int = 20): Flow<List<Track>>

    /** Full-text search across title, artist, and album. */
    fun search(query: String): Flow<List<Track>>

    /** Total number of tracks (reactive). */
    fun getTrackCount(): Flow<Int>

    /** Total storage used by all tracks in bytes (reactive). */
    fun getTotalStorageBytes(): Flow<Long>

    /** Count of downloaded tracks from Spotify. */
    fun getSpotifyDownloadedCount(): Flow<Int>

    /** Count of downloaded tracks from YouTube. */
    fun getYouTubeDownloadedCount(): Flow<Int>

    // ── Playlist queries ────────────────────────────────────────────────

    /** All active playlists. */
    fun getAllPlaylists(): Flow<List<Playlist>>

    /** A single playlist with its full track list. */
    suspend fun getPlaylistWithTracks(id: Long): Playlist?

    // ── Mutations ───────────────────────────────────────────────────────

    /** Record a play event: increments play count and updates last-played. */
    suspend fun recordPlay(trackId: Long)

    /** Insert or replace a track. Returns the row ID. */
    suspend fun insertTrack(track: Track): Long

    /**
     * Delete a track from the database and remove its audio file from disk.
     *
     * @param track The track to delete. Its [Track.filePath] is used to locate the file on disk.
     * @return True if the database row was removed (file deletion is best-effort).
     */
    suspend fun deleteTrack(track: Track): Boolean

    /** Insert or replace a playlist. Returns the row ID. */
    suspend fun insertPlaylist(playlist: Playlist): Long

    /** Remove a playlist from the library without deleting its tracks from disk. */
    suspend fun removePlaylist(playlist: Playlist)

    // ── Sync history ────────────────────────────────────────────────────

    /** The most recent sync record, or null. */
    suspend fun getLatestSync(): SyncHistoryEntity?

    /** Reactive stream of the most recent sync record. Emits null when no history exists. */
    fun observeLatestSync(): Flow<SyncHistoryEntity?>

    /** Reactive stream of all sync history records. */
    fun getAllSyncHistory(): Flow<List<SyncHistoryEntity>>
}
