package com.stash.core.data.repository

import com.stash.core.data.db.dao.AlbumSummary
import com.stash.core.data.db.dao.ArtistSummary
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Primary repository interface for music data operations.
 *
 * All list queries return [Flow] for reactive UI updates. Mutation
 * methods are suspend functions that complete once the database
 * write is committed.
 */
interface MusicRepository {

    // ── Deletion events ─────────────────────────────────────────────────

    /**
     * Emits a track id every time the repository deletes that track's audio
     * file + DB row (or tombstones it via blacklist). Collectors such as
     * `PlayerRepositoryImpl` subscribe once and evict the track from any
     * in-memory queue so playback doesn't continue on a deleted file.
     *
     * Any future delete-entry-point added to the repository MUST emit here
     * after the terminal write — that's what keeps the player eviction
     * hook automatic instead of requiring every ViewModel to remember.
     */
    val trackDeletions: SharedFlow<Long>

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

    /**
     * Look up multiple [Track] rows by their YouTube videoId in one call.
     *
     * Returns only the subset of [videoIds] that resolve to an existing row;
     * unknown ids are silently dropped. Used by
     * `AlbumDiscoveryViewModel.shuffleDownloaded` to resolve the downloaded
     * subset of an album for playback in a single pass.
     */
    suspend fun findByYoutubeIds(videoIds: Collection<String>): List<Track>

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

    /** All active playlists of a given type (e.g. LIKED_SONGS). */
    fun getPlaylistsByType(type: com.stash.core.model.PlaylistType): Flow<List<Playlist>>

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

    /** Update a playlist's cover art URL (local file path or remote URL). */
    suspend fun updatePlaylistArtUrl(playlistId: Long, artUrl: String?)

    // ── Cleanup ──────────────────────────────────────────────────────────

    /**
     * Deletes tracks that have no playlist membership left after a mix refresh.
     *
     * Only deletes tracks that:
     * 1. Have is_downloaded = true (have files on disk)
     * 2. Are NOT in ANY active playlist_tracks entry (removed_at IS NULL)
     * 3. Are NOT of source BOTH (local/custom imports)
     *
     * This prevents accidentally deleting tracks the user wants to keep.
     *
     * @return The number of orphaned tracks cleaned up.
     */
    suspend fun cleanOrphanedMixTracks(): Int

    // ── Download queue cleanup ────────────────────────────────────────────

    /** Cancel pending downloads when a service is disconnected. */
    suspend fun cancelPendingDownloadsForSource(source: String): Int

    // ── Custom playlist management ────────────────────────────────────────

    /** Create a new empty custom playlist. Returns the playlist ID. */
    suspend fun createPlaylist(name: String): Long

    /** Add a track to a playlist. */
    suspend fun addTrackToPlaylist(trackId: Long, playlistId: Long)

    /** Remove a track from a playlist (soft delete). */
    suspend fun removeTrackFromPlaylist(trackId: Long, playlistId: Long)

    /** Get all user-created custom playlists. */
    fun getUserCreatedPlaylists(): Flow<List<Playlist>>

    // ── Unmatched tracks ────────────────────────────────────────────────

    /** Unmatched tracks (matching failures, not dismissed). */
    fun getUnmatchedTracks(): Flow<List<com.stash.core.data.db.dao.UnmatchedTrackView>>

    /** Count of unmatched tracks. */
    fun getUnmatchedCount(): Flow<Int>

    /** Dismiss a track from matching — marks TrackEntity and deletes queue entry. */
    suspend fun dismissMatch(trackId: Long)

    // ── Wrong-match flagging (user-initiated from Now Playing) ──────────

    /**
     * Flag the currently-playing track as the wrong song. Surfaces it in
     * the Failed Matches screen alongside unmatched tracks. Set [flagged]
     * to `false` to clear a previously-set flag (used by approve/dismiss).
     */
    suspend fun setMatchFlagged(trackId: Long, flagged: Boolean)

    /**
     * Flagged tracks — i.e. successfully downloaded tracks that the user
     * said are the wrong song. Used by the Failed Matches screen.
     */
    fun getFlaggedTracks(): Flow<List<com.stash.core.data.db.entity.TrackEntity>>

    /** Count of flagged tracks. Drives the Sync-tab warning card. */
    fun getFlaggedCount(): Flow<Int>

    // ── Blacklist (never-download list) ─────────────────────────────────

    /**
     * Outcome summary returned by [removeTrackFromPlaylistAndMaybeDelete]
     * and its batch wrapper. Used by the UI to render a post-delete
     * snackbar so the user sees exactly what happened and why.
     *
     * @property deleted Track rows whose audio file + DB entry were removed.
     * @property keptProtected Tracks still belonging to Liked Songs or an
     *   in-app custom playlist; only unlinked from the deleted playlist.
     * @property keptElsewhere Tracks still belonging to some *other* non-
     *   protected playlist; file stays because another list claims it.
     * @property blacklisted Subset of [deleted] that was also marked as
     *   never-download-again. Always ≤ [deleted].size.
     */
    data class CascadeRemovalSummary(
        val deleted: Int,
        val keptProtected: Int,
        val keptElsewhere: Int,
        val blacklisted: Int,
    )

    /**
     * Removes [trackId]'s membership from [fromPlaylistId] and applies the
     * protected-playlist cascade policy:
     *
     * 1. Row `(trackId, fromPlaylistId)` is always deleted from playlist_tracks.
     * 2. If the track still lives in at least one protected playlist (Liked
     *    Songs or an in-app custom playlist), nothing else happens — file
     *    stays, blacklist is NOT applied (the user may not realize the
     *    track is protected and we refuse to surprise them).
     * 3. Otherwise, if another non-protected playlist still claims it,
     *    the file stays and the track stays in the DB. Only the one
     *    membership row is gone.
     * 4. Otherwise the file + album art + DB row are deleted. If
     *    [alsoBlacklist] is `true`, the row is kept as a tombstone with
     *    `is_blacklisted = 1` so future syncs skip its identity forever.
     */
    suspend fun removeTrackFromPlaylistAndMaybeDelete(
        trackId: Long,
        fromPlaylistId: Long,
        alsoBlacklist: Boolean,
    ): CascadeRemovalSummary

    /**
     * Batch wrapper: run [removeTrackFromPlaylistAndMaybeDelete] for every
     * track currently in [playlistId], then remove the playlist entity
     * itself. Summary aggregates the counts across all tracks so the UI
     * can tell the user "12 deleted, 3 kept in Liked Songs, 2 blacklisted."
     */
    suspend fun deletePlaylistWithCascade(
        playlistId: Long,
        alsoBlacklist: Boolean,
    ): CascadeRemovalSummary

    /**
     * Is [trackId] in at least one protected playlist OTHER than
     * [excludePlaylistId]? Used by delete-confirmation UIs to preview how
     * many tracks would actually be destroyed by a playlist delete and
     * how many would stay because they're also in Liked Songs / custom.
     */
    suspend fun isTrackProtectedExcluding(trackId: Long, excludePlaylistId: Long): Boolean

    /**
     * Standalone blacklist: mark a track as never-download-again without
     * going through the playlist cascade. Used for future single-track
     * "block" actions outside of playlist deletion.
     */
    suspend fun blacklistTrack(trackId: Long)

    /** Clear the blacklist flag on a previously-blocked track. */
    suspend fun unblacklistTrack(trackId: Long)

    /** All currently-blacklisted tracks for the Settings viewer. */
    fun getBlacklistedTracks(): Flow<List<com.stash.core.data.db.entity.TrackEntity>>

    /** Count of blacklisted tracks for the Settings row badge. */
    fun getBlacklistedCount(): Flow<Int>

    // ── Sync history ────────────────────────────────────────────────────

    /** The most recent sync record, or null. */
    suspend fun getLatestSync(): SyncHistoryEntity?

    /** Reactive stream of the most recent sync record. Emits null when no history exists. */
    fun observeLatestSync(): Flow<SyncHistoryEntity?>

    /** Reactive stream of all sync history records. */
    fun getAllSyncHistory(): Flow<List<SyncHistoryEntity>>
}
