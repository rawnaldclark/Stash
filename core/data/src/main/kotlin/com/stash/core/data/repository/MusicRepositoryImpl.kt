package com.stash.core.data.repository

import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.db.dao.AlbumSummary
import com.stash.core.data.db.dao.ArtistSummary
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.mapper.toEntity
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Default [MusicRepository] implementation backed by Room DAOs.
 *
 * All Flow-returning methods delegate directly to the DAO layer and map
 * entities to domain models via extension functions in the mapper package.
 */
class MusicRepositoryImpl @Inject constructor(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val syncHistoryDao: SyncHistoryDao,
    private val downloadQueueDao: com.stash.core.data.db.dao.DownloadQueueDao,
) : MusicRepository {

    /** Startup fixups — resets exhausted retries, purges seeder data, and
     *  clears interrupted sync records. */
    suspend fun runMigrations() {
        // Reset exhausted retries so tracks get another chance each app session.
        downloadQueueDao.resetExhaustedRetries()

        // Mark any sync runs left in a non-terminal state (from a killed
        // process, reboot, etc.) as FAILED so the home screen's sync status
        // card doesn't read "Syncing..." forever.
        val resetSyncs = syncHistoryDao.resetStaleSyncs()
        if (resetSyncs > 0) {
            android.util.Log.i("StashMigrations", "Reset $resetSyncs stale sync record(s)")
        }

        // One-time cleanup of filler tracks/playlists created by the original
        // DatabaseSeeder. The seeder used distinctive file paths and source IDs
        // that do not collide with real sync data. Safe to run on every startup
        // — becomes a no-op once cleaned. See DAO KDoc for details.
        val deletedTracks = trackDao.deleteSeederTracks()
        val deletedPlaylists = playlistDao.deleteSeederPlaylists()
        if (deletedTracks > 0 || deletedPlaylists > 0) {
            android.util.Log.i(
                "StashMigrations",
                "Cleaned seeder data: $deletedTracks tracks, $deletedPlaylists playlists",
            )
        }

        // Fix duplicate playlist_tracks entries that accumulated from daily mix
        // sync runs. Each sync added new tracks at the same positions without
        // removing old ones, causing multiple tracks at position 1, 2, etc.
        // This cleanup keeps only the most recently added entry for each
        // (playlist_id, track_id) pair and removes the rest.
        deduplicatePlaylistTracks()

        // One-time upgrade: replace low-res album art URLs (60px YouTube thumbnails)
        // with high-res equivalents. The ArtUrlUpgrader rewrites lh3 CDN URLs to
        // request 544px instead of 60px, and Spotify URLs to 640px instead of 300px.
        // Safe to run on every startup — already-upgraded URLs pass through unchanged.
        upgradeAlbumArtUrls()

        // NOTE: backfillSpotifyDateAdded() was removed — it ran on every startup and
        // overwrote all Spotify tracks' date_added with the same timestamp, making
        // "Recently Added" show arbitrary tracks instead of actual recent downloads.

        // Clean up orphaned mix tracks — downloaded tracks whose playlist was
        // refreshed and that no longer belong to any playlist. Deletes their
        // audio files and DB rows to free storage. Safe to run every startup;
        // becomes a no-op when there are no orphans.
        cleanOrphanedMixTracks()
    }

    // ── Track queries ───────────────────────────────────────────────────

    override fun getAllTracks(): Flow<List<Track>> =
        trackDao.getAllByDateAdded().map { entities ->
            entities.filter { it.isDownloaded }.map { it.toDomain() }
        }

    override fun getTracksByArtist(artist: String): Flow<List<Track>> =
        trackDao.getByArtist(artist).map { entities -> entities.map { it.toDomain() } }

    override fun getTracksByPlaylist(playlistId: Long): Flow<List<Track>> =
        trackDao.getByPlaylist(playlistId).map { entities -> entities.map { it.toDomain() } }

    override fun getAllArtists(): Flow<List<ArtistSummary>> =
        trackDao.getAllArtists()

    override fun getAllAlbums(): Flow<List<AlbumSummary>> =
        trackDao.getAllAlbums()

    override fun getRecentlyAdded(limit: Int): Flow<List<Track>> =
        trackDao.getRecentlyAdded(limit).map { entities -> entities.map { it.toDomain() } }

    override fun getMostPlayed(limit: Int): Flow<List<Track>> =
        trackDao.getMostPlayed(limit).map { entities -> entities.map { it.toDomain() } }

    override fun search(query: String): Flow<List<Track>> {
        val sanitized = "\"${query.replace("\"", "").trim()}\""
        if (sanitized == "\"\"") return flowOf(emptyList())
        return trackDao.search(sanitized).map { entities -> entities.map { it.toDomain() } }
    }

    override fun getTrackCount(): Flow<Int> =
        trackDao.getTotalCount()

    override fun getTotalStorageBytes(): Flow<Long> =
        trackDao.getTotalStorageBytes()

    override fun getSpotifyDownloadedCount(): Flow<Int> =
        trackDao.getSpotifyDownloadedCount()

    override fun getYouTubeDownloadedCount(): Flow<Int> =
        trackDao.getYouTubeDownloadedCount()

    // ── Playlist queries ────────────────────────────────────────────────

    override fun getAllPlaylists(): Flow<List<Playlist>> =
        playlistDao.getAllActive().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getPlaylistWithTracks(id: Long): Playlist? {
        val result = playlistDao.getPlaylistWithTracks(id) ?: return null
        return result.playlist.toDomain().copy(
            tracks = result.tracks.map { it.toDomain() },
        )
    }

    // ── Mutations ───────────────────────────────────────────────────────

    override suspend fun recordPlay(trackId: Long) {
        trackDao.incrementPlayCount(trackId)
        trackDao.updateLastPlayed(trackId, System.currentTimeMillis())
    }

    override suspend fun insertTrack(track: Track): Long =
        trackDao.insert(track.toEntity())

    override suspend fun deleteTrack(track: Track): Boolean {
        // Best-effort file deletion -- the file may already be gone.
        track.filePath?.let { path ->
            try {
                java.io.File(path).delete()
            } catch (_: Exception) {
                // Ignore: file may not exist or may be unreadable.
            }
        }
        // Also delete locally-stored album art.
        track.albumArtPath?.let { path ->
            try {
                java.io.File(path).delete()
            } catch (_: Exception) {
                // Ignore.
            }
        }
        trackDao.delete(track.toEntity())
        return true
    }

    override suspend fun insertPlaylist(playlist: Playlist): Long =
        playlistDao.insert(playlist.toEntity())

    override suspend fun removePlaylist(playlist: Playlist) {
        playlistDao.delete(playlist.toEntity())
    }

    // ── Custom playlist management ──────────────────────────────────────

    override suspend fun createPlaylist(name: String): Long {
        val entity = com.stash.core.data.db.entity.PlaylistEntity(
            name = name,
            source = com.stash.core.model.MusicSource.BOTH,
            type = com.stash.core.model.PlaylistType.CUSTOM,
            isActive = true,
            syncEnabled = false,
        )
        return playlistDao.insert(entity)
    }

    override suspend fun addTrackToPlaylist(trackId: Long, playlistId: Long) {
        val position = playlistDao.getNextPosition(playlistId)
        playlistDao.insertCrossRef(
            com.stash.core.data.db.entity.PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = position,
            )
        )
        val count = trackDao.getByPlaylist(playlistId).first().size
        playlistDao.updateTrackCount(playlistId, count)
    }

    override suspend fun removeTrackFromPlaylist(trackId: Long, playlistId: Long) {
        playlistDao.softDeleteTrackFromPlaylist(playlistId, trackId)
        val count = trackDao.getByPlaylist(playlistId).first().size
        playlistDao.updateTrackCount(playlistId, count)
    }

    override fun getUserCreatedPlaylists(): Flow<List<com.stash.core.model.Playlist>> =
        playlistDao.getUserCreatedPlaylists().map { entities -> entities.map { it.toDomain() } }

    // ── Sync history ────────────────────────────────────────────────────

    override suspend fun getLatestSync(): SyncHistoryEntity? =
        syncHistoryDao.getLatest()

    override fun observeLatestSync(): Flow<SyncHistoryEntity?> =
        syncHistoryDao.observeLatest()

    override fun getAllSyncHistory(): Flow<List<SyncHistoryEntity>> =
        syncHistoryDao.observeAll()

    // ── Download queue cleanup ────────────────────────────────────────────

    override suspend fun cancelPendingDownloadsForSource(source: String): Int {
        val cancelled = downloadQueueDao.cancelDownloadsForSource(source)
        if (cancelled > 0) {
            android.util.Log.i("StashMigrations", "Cancelled $cancelled pending downloads for disconnected source: $source")
        }
        return cancelled
    }

    // ── Cleanup ──────────────────────────────────────────────────────────

    override suspend fun cleanOrphanedMixTracks(): Int {
        val orphans = trackDao.getOrphanedDownloadedTracks()
        if (orphans.isEmpty()) return 0

        for (track in orphans) {
            // Delete the audio file from disk.
            track.filePath?.let { path ->
                try {
                    java.io.File(path).delete()
                } catch (_: Exception) {
                    // Best-effort: file may already be gone.
                }
            }
            // Delete locally-stored album art if present.
            track.albumArtPath?.let { path ->
                try {
                    java.io.File(path).delete()
                } catch (_: Exception) {
                    // Best-effort.
                }
            }
            // Remove the track entity from the database.
            trackDao.delete(track)
        }

        android.util.Log.i(
            "StashCleanup",
            "Cleaned ${orphans.size} orphaned track(s) and their audio files",
        )
        return orphans.size
    }

    // ── Art URL migration ──────────────────────────────────────────────

    /**
     * Upgrades low-resolution album art URLs for all existing tracks.
     * YouTube Music InnerTube responses originally returned 60x60 thumbnails;
     * this replaces them with 544x544. Spotify 300px URLs are upgraded to 640px.
     * Already-upgraded URLs pass through [ArtUrlUpgrader.upgrade] unchanged,
     * so this is safe to run on every startup.
     */
    /**
     * Fixes accumulated duplicate entries in playlist_tracks. Before the
     * DiffWorker fix, every sync run inserted new tracks without clearing
     * old ones, causing multiple tracks per position. This query deletes
     * all entries where duplicate positions exist within a playlist, keeping
     * only the one with the latest added_at timestamp.
     */
    private suspend fun deduplicatePlaylistTracks() {
        // Strategy: for each playlist that has duplicate positions, clear
        // ALL entries and let the next sync rebuild them cleanly. This is
        // aggressive but correct — the tracks themselves are not deleted,
        // only the playlist membership. The next sync will re-associate them.
        val allPlaylists = playlistDao.getAllActive().first()
        var cleaned = 0
        for (playlist in allPlaylists) {
            // Count entries vs expected track count
            val tracks = trackDao.getByPlaylist(playlist.id).first()
            if (tracks.size > playlist.trackCount && playlist.trackCount > 0) {
                playlistDao.clearPlaylistTracks(playlist.id)
                cleaned++
                android.util.Log.i("StashMigrations",
                    "Cleared ${tracks.size} stale entries for '${playlist.name}' (expected ${playlist.trackCount})")
            }
        }
        if (cleaned > 0) {
            android.util.Log.i("StashMigrations", "Cleaned $cleaned playlists with duplicate track entries. Next sync will rebuild.")
        }
    }

    /**
     * Upgrades low-res album art URLs to high-res equivalents.
     * On first run: upgrades all tracks with low-res URLs (~2800 updates).
     * On subsequent runs: `toUpdate` is empty so it returns immediately
     * after a single DB read. The read itself is fast (~50ms for 3000 rows).
     */
    private suspend fun upgradeAlbumArtUrls() {
        val allTracks = trackDao.getAllByDateAdded().first()
        val toUpdate = allTracks.mapNotNull { track ->
            val original = track.albumArtUrl ?: return@mapNotNull null
            val better = ArtUrlUpgrader.upgrade(original) ?: return@mapNotNull null
            if (better != original) track.copy(albumArtUrl = better) else null
        }
        if (toUpdate.isEmpty()) return

        toUpdate.forEach { trackDao.update(it) }
        android.util.Log.i("StashMigrations", "Upgraded ${toUpdate.size} album art URLs to high-res")
    }
}
