package com.stash.core.data.repository

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

    /** One-time migrations and fixups. */
    suspend fun runMigrations() {
        trackDao.backfillSpotifyDateAdded()
        // Reset exhausted retries so tracks failed due to now-fixed bugs get another chance.
        downloadQueueDao.resetExhaustedRetries()
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

    // ── Sync history ────────────────────────────────────────────────────

    override suspend fun getLatestSync(): SyncHistoryEntity? =
        syncHistoryDao.getLatest()

    override fun observeLatestSync(): Flow<SyncHistoryEntity?> =
        syncHistoryDao.observeLatest()

    override fun getAllSyncHistory(): Flow<List<SyncHistoryEntity>> =
        syncHistoryDao.observeAll()
}
