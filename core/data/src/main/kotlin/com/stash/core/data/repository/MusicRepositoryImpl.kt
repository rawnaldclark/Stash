package com.stash.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import javax.inject.Inject

/**
 * Default [MusicRepository] implementation backed by Room DAOs.
 *
 * All Flow-returning methods delegate directly to the DAO layer and map
 * entities to domain models via extension functions in the mapper package.
 */
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val syncHistoryDao: SyncHistoryDao,
    private val downloadQueueDao: com.stash.core.data.db.dao.DownloadQueueDao,
    private val discoveryQueueDao: com.stash.core.data.db.dao.DiscoveryQueueDao,
) : MusicRepository {

    // ── Deletion event plumbing ─────────────────────────────────────────
    //
    // Every repo method that actually removes a track file + DB row emits
    // the track id here. The player (and any future component that holds
    // references to tracks) subscribes once and reacts automatically, so
    // new delete entry-points can't forget to tell the player.
    //
    // Buffer is generous so emits from a cascade-delete loop don't suspend
    // the caller (we use tryEmit).
    private val _trackDeletions = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val trackDeletions: SharedFlow<Long> = _trackDeletions.asSharedFlow()

    /**
     * Deletes the audio file at [path]. Handles both app-internal paths
     * (plain `java.io.File`) and SAF-backed external storage URIs (the
     * `content://...` strings returned by [com.stash.data.download.files.FileOrganizer]
     * when the user has picked an SD card / USB-OTG folder). Without the
     * `content://` branch, external-storage users would leak every deleted
     * track's file, because `File(contentUri).delete()` silently returns
     * false for a URI that isn't a real filesystem path.
     *
     * Returns true on successful unlink. Best-effort: false just means the
     * file was already gone, the SAF grant was revoked, or I/O failed.
     */
    private fun deleteTrackFile(path: String): Boolean = runCatching {
        if (path.startsWith("content://")) {
            DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete() == true
        } else {
            java.io.File(path).delete()
        }
    }.getOrDefault(false)

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
        trackDao.getAllByDateAdded()
            // `SELECT * FROM tracks` with a multi-thousand-row library
            // bumps against Android's CursorWindow size limit (~2 MB).
            // Libraries past that boundary read the tail rows from a
            // separate fetched window, and if the tracks table mutates
            // while Room's suspending query is mid-iteration (e.g. the
            // user just tapped "delete playlist and songs" in Library
            // tab while this Flow is live), CursorWindow throws
            // `IllegalStateException: Couldn't read row N, col 0 from
            // CursorWindow`. Without a retry the exception propagates
            // through combine() → StateFlow → viewModelScope → CRASH
            // (issue #14). Retrying re-subscribes the upstream; Room's
            // InvalidationTracker has by then committed the mutation,
            // so the fresh cursor reads a consistent table. Cap at 3
            // attempts so a non-race failure doesn't loop forever.
            .retryWhen { cause, attempt ->
                val raced = cause is IllegalStateException &&
                    cause.message?.contains("CursorWindow") == true
                raced && attempt < 3
            }
            .map { entities ->
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

    override suspend fun findByYoutubeIds(videoIds: Collection<String>): List<Track> =
        videoIds.mapNotNull { trackDao.findByYoutubeId(it)?.toDomain() }

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
        // Uses the sync-enabled-gated query so toggled-off external
        // playlists vanish from Home + Library in step with their
        // Sync Preferences state. See PlaylistDao.getAllVisible for
        // the source=BOTH exemption that keeps local CUSTOM + STASH_MIX
        // visible while still gating imported YouTube CUSTOM playlists.
        playlistDao.getAllVisible().map { entities -> entities.map { it.toDomain() } }

    override fun getPlaylistsByType(type: com.stash.core.model.PlaylistType): Flow<List<Playlist>> =
        playlistDao.getByType(type).map { entities -> entities.map { it.toDomain() } }

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
        track.filePath?.let { deleteTrackFile(it) }
        // Album art lives in the app cache (internal only) but route it
        // through the same helper so a future SAF-backed art cache would
        // work without another code change.
        track.albumArtPath?.let { deleteTrackFile(it) }
        trackDao.delete(track.toEntity())
        _trackDeletions.tryEmit(track.id)
        return true
    }

    override suspend fun insertPlaylist(playlist: Playlist): Long =
        playlistDao.insert(playlist.toEntity())

    override suspend fun removePlaylist(playlist: Playlist) {
        playlistDao.delete(playlist.toEntity())
    }

    override suspend fun updatePlaylistArtUrl(playlistId: Long, artUrl: String?) {
        playlistDao.updateArtUrl(playlistId, artUrl)
    }

    // ── Custom playlist management ──────────────────────────────────────

    override suspend fun createPlaylist(name: String): Long {
        val entity = com.stash.core.data.db.entity.PlaylistEntity(
            name = name,
            source = com.stash.core.model.MusicSource.BOTH,
            sourceId = "custom_${java.util.UUID.randomUUID()}",
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

    override suspend fun ensureDownloadsMixSeeded(): Long {
        val existing = playlistDao.findBySourceId(DOWNLOADS_MIX_SOURCE_ID)
        if (existing != null) return existing.id
        val entity = com.stash.core.data.db.entity.PlaylistEntity(
            name = "Your Downloads",
            source = com.stash.core.model.MusicSource.BOTH,
            sourceId = DOWNLOADS_MIX_SOURCE_ID,
            type = com.stash.core.model.PlaylistType.DOWNLOADS_MIX,
            syncEnabled = false,
        )
        return playlistDao.insert(entity)
    }

    override suspend fun linkTrackToDownloadsMix(trackId: Long) {
        val playlistId = ensureDownloadsMixSeeded()
        if (playlistDao.getCrossRef(playlistId, trackId) != null) return
        addTrackToPlaylist(trackId = trackId, playlistId = playlistId)
    }

    override suspend fun removeTrackFromPlaylist(trackId: Long, playlistId: Long) {
        playlistDao.softDeleteTrackFromPlaylist(playlistId, trackId)
        val count = trackDao.getByPlaylist(playlistId).first().size
        playlistDao.updateTrackCount(playlistId, count)
    }

    override fun getUserCreatedPlaylists(): Flow<List<com.stash.core.model.Playlist>> =
        playlistDao.getUserCreatedPlaylists().map { entities -> entities.map { it.toDomain() } }

    // ── Unmatched tracks ────────────────────────────────────────────────

    override fun getUnmatchedTracks(): Flow<List<com.stash.core.data.db.dao.UnmatchedTrackView>> =
        downloadQueueDao.getUnmatchedTracks()

    override fun getUnmatchedCount(): Flow<Int> =
        downloadQueueDao.getUnmatchedCount()

    override suspend fun dismissMatch(trackId: Long) {
        trackDao.dismissMatch(trackId)
        downloadQueueDao.deleteByTrackId(trackId)
    }

    // ── Wrong-match flagging ────────────────────────────────────────────

    override suspend fun setMatchFlagged(trackId: Long, flagged: Boolean) {
        trackDao.updateMatchFlagged(trackId, flagged)
    }

    override fun getFlaggedTracks(): Flow<List<com.stash.core.data.db.entity.TrackEntity>> =
        trackDao.getFlaggedTracks()

    override fun getFlaggedCount(): Flow<Int> =
        trackDao.getFlaggedCount()

    // ── Blacklist + cascade deletion ────────────────────────────────────

    override suspend fun removeTrackFromPlaylistAndMaybeDelete(
        trackId: Long,
        fromPlaylistId: Long,
        alsoBlacklist: Boolean,
    ): MusicRepository.CascadeRemovalSummary {
        // Step 1: always detach from the target playlist.
        playlistDao.removeTrackFromPlaylist(fromPlaylistId, trackId)

        // Step 2: protected-playlist escape hatch. Liked Songs and in-app
        // custom playlists count as user-curated data — we refuse to let a
        // cascade from elsewhere destroy them. Blacklist is also bypassed
        // so the user doesn't accidentally block a track they cared enough
        // about to put in a protected list.
        if (trackDao.isTrackInProtectedPlaylist(trackId)) {
            return MusicRepository.CascadeRemovalSummary(
                deleted = 0,
                keptProtected = 1,
                keptElsewhere = 0,
                blacklisted = 0,
            )
        }

        // Step 3: another non-protected playlist still claims it. Keep.
        val otherClaims = trackDao.countOtherPlaylistsClaimingTrack(
            trackId = trackId,
            excludePlaylistId = fromPlaylistId,
        )
        if (otherClaims > 0) {
            return MusicRepository.CascadeRemovalSummary(
                deleted = 0,
                keptProtected = 0,
                keptElsewhere = 1,
                blacklisted = 0,
            )
        }

        // Step 4: nothing else claims the track. Destroy the file + art,
        // then either keep a blacklisted tombstone or delete the row.
        val track = trackDao.getById(trackId) ?: return MusicRepository.CascadeRemovalSummary(
            deleted = 0, keptProtected = 0, keptElsewhere = 0, blacklisted = 0,
        )

        track.filePath?.let { deleteTrackFile(it) }
        track.albumArtPath?.let { deleteTrackFile(it) }

        return if (alsoBlacklist) {
            // Tombstone: keep the row so future sync identity matches
            // (spotify_uri, youtube_id, canonical title+artist) still find
            // it and see is_blacklisted = 1, never re-queueing.
            trackDao.markBlacklistedAndClear(trackId)
            _trackDeletions.tryEmit(trackId)
            MusicRepository.CascadeRemovalSummary(
                deleted = 1, keptProtected = 0, keptElsewhere = 0, blacklisted = 1,
            )
        } else {
            // Hard delete; next sync of the same identity starts fresh.
            trackDao.delete(track)
            _trackDeletions.tryEmit(trackId)
            MusicRepository.CascadeRemovalSummary(
                deleted = 1, keptProtected = 0, keptElsewhere = 0, blacklisted = 0,
            )
        }
    }

    override suspend fun deletePlaylistWithCascade(
        playlistId: Long,
        alsoBlacklist: Boolean,
    ): MusicRepository.CascadeRemovalSummary {
        // Snapshot the track list BEFORE any mutation — iterating a live
        // Flow while deleting would race with cascades.
        val trackIds = playlistDao.getPlaylistWithTracks(playlistId)?.tracks
            ?.map { it.id }
            ?: emptyList()

        var deleted = 0
        var keptProtected = 0
        var keptElsewhere = 0
        var blacklisted = 0

        for (id in trackIds) {
            val result = removeTrackFromPlaylistAndMaybeDelete(
                trackId = id,
                fromPlaylistId = playlistId,
                alsoBlacklist = alsoBlacklist,
            )
            deleted += result.deleted
            keptProtected += result.keptProtected
            keptElsewhere += result.keptElsewhere
            blacklisted += result.blacklisted
        }

        // Finally remove the playlist itself. playlist_tracks rows for it
        // have already been handled per-track above; this just clears the
        // container row. Uses the existing remove path for consistency.
        playlistDao.getById(playlistId)?.let { playlistDao.delete(it) }

        return MusicRepository.CascadeRemovalSummary(
            deleted = deleted,
            keptProtected = keptProtected,
            keptElsewhere = keptElsewhere,
            blacklisted = blacklisted,
        )
    }

    override suspend fun isTrackProtectedExcluding(
        trackId: Long,
        excludePlaylistId: Long,
    ): Boolean = trackDao.isTrackInProtectedPlaylistExcluding(trackId, excludePlaylistId)

    override suspend fun blacklistTrack(trackId: Long) {
        val track = trackDao.getById(trackId) ?: return
        track.filePath?.let { deleteTrackFile(it) }
        track.albumArtPath?.let { deleteTrackFile(it) }
        trackDao.markBlacklistedAndClear(trackId)
        _trackDeletions.tryEmit(trackId)
    }

    override suspend fun unblacklistTrack(trackId: Long) {
        trackDao.updateBlacklisted(trackId, false)
    }

    override fun getBlacklistedTracks(): Flow<List<com.stash.core.data.db.entity.TrackEntity>> =
        trackDao.getBlacklistedTracks()

    override fun getBlacklistedCount(): Flow<Int> =
        trackDao.getBlacklistedCount()

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
        val rawOrphans = trackDao.getOrphanedDownloadedTracks()
        if (rawOrphans.isEmpty()) return 0

        // Don't delete tracks the Discovery pipeline still owns. A
        // Discovery download completes, then the weekly mix refresh
        // clears the playlist_tracks row before re-linking — between
        // those two writes the track looks orphaned to this sweeper.
        // Before the guard, that gap was long enough to delete the
        // audio file (see 2026-04-21 audit: 9 of 10 DONE discovery
        // entries had dangling track_ids from this race).
        val protectedIds = discoveryQueueDao.getActiveTrackIds().toHashSet()
        val orphans = rawOrphans.filterNot { it.id in protectedIds }
        val skipped = rawOrphans.size - orphans.size
        if (skipped > 0) {
            android.util.Log.d(
                "StashCleanup",
                "Skipped $skipped orphan(s) protected by active discovery queue",
            )
        }
        if (orphans.isEmpty()) return 0

        for (track in orphans) {
            // Delete the audio file from disk (SAF-aware — see deleteTrackFile).
            track.filePath?.let { deleteTrackFile(it) }
            // Delete locally-stored album art if present.
            track.albumArtPath?.let { deleteTrackFile(it) }
            // Remove the track entity from the database.
            trackDao.delete(track)
            _trackDeletions.tryEmit(track.id)
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

    companion object {
        private const val DOWNLOADS_MIX_SOURCE_ID = "stash_downloads_mix"
    }
}
