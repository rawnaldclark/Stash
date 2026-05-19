package com.stash.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import javax.inject.Inject
import androidx.core.net.toUri

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
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val trackMatcher: com.stash.core.data.sync.TrackMatcher,
    private val stashMixRecipeDao: com.stash.core.data.db.dao.StashMixRecipeDao,
    private val downloadNetworkPreference: com.stash.core.data.prefs.DownloadNetworkPreference,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
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
     * when the user has picked an SD card / USB-OTG folder).
     *
     * Returns true on successful unlink. Best-effort: false just means the
     * file was already gone, the SAF grant was revoked, or I/O failed.
     */
    private fun deleteTrackFile(path: String): Boolean = runCatching {
        if (path.startsWith("content://")) {
            DocumentFile.fromSingleUri(context, path.toUri())?.delete() == true
        } else {
            val plainPath = if (path.startsWith("file://")) {
                path.toUri().path ?: path.removePrefix("file://")
            } else {
                path
            }
            java.io.File(plainPath).delete()
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

        // v0.9.21: removed the periodic art-URL upgrade passes. ArtUrlUpgrader
        // now runs at every sync write site (PlaylistFetchWorker for both
        // tracks and playlists, DiffWorker for the new-row path) so URLs land
        // already-upgraded. A re-process on every launch is dead weight —
        // bandwidth + compute the user doesn't want to pay. Users on
        // pre-fix builds get HQ art on their next sync; clearing app data
        // forces an immediate refresh.

        // NOTE: backfillSpotifyDateAdded() was removed — it ran on every startup and
        // overwrote all Spotify tracks' date_added with the same timestamp, making
        // "Recently Added" show arbitrary tracks instead of actual recent downloads.

        // v0.9.21: file integrity sweep — finds tracks marked is_downloaded=1
        // whose file has vanished from disk and resets them to undownloaded.
        // Without this, getDoneTrackIdsForRecipe surfaces them as mix
        // survivors (they pass the is_downloaded=1 filter) but playback fails
        // because the file is gone. Run BEFORE cleanOrphanedMixTracks so the
        // sweep itself doesn't choke on stale paths. See conversation
        // 2026-05-12: tracks with FLAC quality + album art + no audible
        // playback because the file was missing.
        reconcileMissingDownloadedFiles()

        // Clean up orphaned mix tracks — downloaded tracks whose playlist was
        // refreshed and that no longer belong to any playlist. Deletes their
        // audio files and DB rows to free storage. Safe to run every startup;
        // becomes a no-op when there are no orphans.
        cleanOrphanedMixTracks()

        // v0.9.21: cancel pending download_queue rows whose tracks no
        // longer belong to any sync-enabled playlist. Catches the
        // pre-fix-install case where a user deselected a playlist before
        // SyncViewModel learned to clean up — those queue rows would
        // drain indefinitely otherwise. Idempotent.
        val cancelledOrphans = downloadQueueDao.cancelDownloadsWithNoEnabledPlaylist()
        if (cancelledOrphans > 0) {
            android.util.Log.i(
                "StashMigrations",
                "cancelled $cancelledOrphans orphan PENDING download(s) — tracks have no enabled playlist",
            )
        }
    }

    /**
     * Verifies every `is_downloaded=1` row's file is actually readable.
     * Handles both regular filesystem paths and SAF `content://` URIs.
     * Rows with missing files have `is_downloaded`, `file_path`, and
     * `file_size_bytes` cleared so the rest of the system stops treating
     * them as playable.
     *
     * Skipped: rows whose path is already null (they're a separate kind
     * of corrupt state that bulkResetForReDownload will also clean —
     * captured in `nullPath`).
     */
    private suspend fun reconcileMissingDownloadedFiles() {
        val refs = trackDao.getDownloadedFileRefs()
        if (refs.isEmpty()) return

        val missing = mutableListOf<Long>()
        var nullPath = 0
        for (ref in refs) {
            val path = ref.filePath
            if (path.isNullOrBlank()) {
                missing += ref.id
                nullPath++
                continue
            }
            val exists = runCatching {
                if (path.startsWith("content://")) {
                    DocumentFile.fromSingleUri(context, path.toUri())?.exists() == true
                } else {
                    val plainPath = if (path.startsWith("file://")) {
                        path.toUri().path ?: path.removePrefix("file://")
                    } else {
                        path
                    }
                    java.io.File(plainPath).exists()
                }
            }.getOrDefault(false)
            if (!exists) missing += ref.id
        }

        if (missing.isEmpty()) {
            android.util.Log.d("StashMigrations", "file integrity: all ${refs.size} downloaded files present")
            return
        }
        // Bulk update in chunks to stay under SQLite's parameter ceiling.
        missing.chunked(500).forEach { chunk ->
            trackDao.bulkResetForReDownload(chunk)
        }
        android.util.Log.i(
            "StashMigrations",
            "file integrity: scanned ${refs.size} downloaded rows, " +
                "reset ${missing.size} with missing files (nullPath=$nullPath)",
        )
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

    // v0.9.30 Path A: Library Songs/Albums/Artists views are curated =
    // downloaded-only, always. Streaming mode does NOT change what shows
    // in those Library sub-views — it would only expose ghost rows from
    // historic failed downloads. The Spotify/Apple Music mental model:
    // Library is YOUR saved music; search/streaming is a separate surface.
    //
    // EXCEPTION: getTracksByPlaylist. The playlist-detail screen is the
    // single place where streaming mode IS meaningful for a Library-ish
    // surface — a synced playlist in streaming mode should show all of
    // its tracks (streamable + downloaded), and tapping a streamable
    // track streams via Kennyy. In offline mode it stays downloaded-only.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getTracksByPlaylist(playlistId: Long): Flow<List<Track>> =
        streamingPreference.enabled.flatMapLatest { enabled ->
            trackDao.getByPlaylist(playlistId, includeStreamable = enabled)
        }.map { entities -> entities.map { it.toDomain() } }

    override fun getAllArtists(): Flow<List<ArtistSummary>> =
        trackDao.getAllArtists(includeStreamable = false)

    override fun getAllAlbums(): Flow<List<AlbumSummary>> =
        trackDao.getAllAlbums(includeStreamable = false)

    override fun getRecentlyAdded(limit: Int): Flow<List<Track>> =
        trackDao.getRecentlyAdded(limit).map { entities -> entities.map { it.toDomain() } }

    override fun getMostPlayed(limit: Int): Flow<List<Track>> =
        trackDao.getMostPlayed(limit).map { entities -> entities.map { it.toDomain() } }

    override fun search(query: String): Flow<List<Track>> {
        val sanitized = "\"${query.replace("\"", "").trim()}\""
        if (sanitized == "\"\"") return flowOf(emptyList())
        return trackDao.search(sanitized, includeStreamable = false)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun findByYoutubeIds(videoIds: Collection<String>): List<Track> =
        videoIds.mapNotNull { trackDao.findByYoutubeId(it)?.toDomain() }

    override suspend fun applyStashMixesEnabled(enabled: Boolean) {
        val wm = androidx.work.WorkManager.getInstance(context)
        if (enabled) {
            // Recipes + playlists first so the workers wake up to active state.
            stashMixRecipeDao.setActiveForBuiltins(true)
            playlistDao.setActiveForBuiltinMixes(true)
            // Re-schedule the five periodic workers. Cheap idempotent operation —
            // KEEP-policy means duplicate enqueues no-op.
            val mode = downloadNetworkPreference.current()
            com.stash.core.data.sync.workers.StashMixRefreshWorker.schedulePeriodic(context)
            com.stash.core.data.sync.workers.StashDiscoveryWorker.schedulePeriodic(context, mode)
            com.stash.core.data.sync.workers.TagEnrichmentWorker.schedulePeriodic(context, mode)
            com.stash.core.data.sync.workers.TrackInfoEnrichmentWorker.schedulePeriodic(context)
            // Fire a one-shot refresh so the surfaces repopulate immediately
            // rather than waiting for the next periodic cycle.
            com.stash.core.data.sync.workers.StashMixRefreshWorker.enqueueOneTime(context)
        } else {
            // Cancel periodic + one-shot work by unique name. The constants
            // live inside the workers as private vals; the names are stable
            // and grepped from each worker (see core/data/.../sync/workers/).
            for (name in STASH_MIX_WORK_NAMES) {
                wm.cancelUniqueWork(name)
            }
            // Hide the surfaces. Recipes off → refresh no-op even if a worker
            // somehow slips through. Playlists off → invisible to Library/Home
            // queries that filter on is_active = 1.
            stashMixRecipeDao.setActiveForBuiltins(false)
            playlistDao.setActiveForBuiltinMixes(false)
        }
    }

    override suspend fun backfillAlbumForTracks(
        videoIds: Collection<String>,
        album: String,
        albumArtist: String,
    ) {
        if (album.isBlank() && albumArtist.isBlank()) return
        videoIds.forEach { videoId ->
            val existing = trackDao.findByYoutubeId(videoId) ?: return@forEach
            if (album.isNotBlank() && existing.album.isBlank()) {
                trackDao.updateAlbumIfEmpty(existing.id, album)
            }
            if (albumArtist.isNotBlank() && existing.albumArtist.isBlank()) {
                trackDao.updateAlbumArtistIfEmpty(existing.id, albumArtist)
            }
        }
    }

    override suspend fun getAllDownloadedTracks(): List<Track> =
        trackDao.getAllDownloaded().map { it.toDomain() }

    override fun getTrackCount(): Flow<Int> =
        trackDao.getTotalCount(includeStreamable = false)

    override fun getTotalStorageBytes(): Flow<Long> =
        trackDao.getTotalStorageBytes()

    override fun getFlacTrackCount(): Flow<Int> =
        trackDao.getFlacCount()

    override fun getFlacStorageBytes(): Flow<Long> =
        trackDao.getFlacStorageBytes()

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
        playlistDao.getAllVisible(includeStreamable = false)
            .map { entities -> entities.map { it.toDomain() } }

    override fun getPlaylistsByType(type: com.stash.core.model.PlaylistType): Flow<List<Playlist>> =
        playlistDao.getByType(type).map { entities -> entities.map { it.toDomain() } }

    override fun observeLikeState(trackId: Long): Flow<com.stash.core.data.db.dao.TrackLikeState?> =
        trackDao.observeLikeState(trackId)

    override fun observeTrackById(trackId: Long): Flow<Track?> =
        trackDao.observeById(trackId).map { it?.toDomain() }

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

    // ── User-initiated download / remove-download ─────────────────────
    //
    // These methods feed the same `download_queue` infrastructure that
    // sync uses, but mark `sync_id = null` so the rows land in the
    // discovery-worker partition. Removal nulls the file_path / flags
    // but keeps the row so the track remains streamable (Path A).

    override suspend fun queueDownload(trackId: Long) {
        val entity = trackDao.getById(trackId) ?: return
        if (entity.isDownloaded) return

        val existing = downloadQueueDao.getByTrackId(trackId)
        if (existing != null && existing.status in NON_TERMINAL_QUEUE_STATES) return

        downloadQueueDao.insert(
            com.stash.core.data.db.entity.DownloadQueueEntity(
                trackId = trackId,
                syncId = null,
                searchQuery = "${entity.artist} - ${entity.title}",
                youtubeUrl = entity.youtubeId?.let { "https://music.youtube.com/watch?v=$it" },
            )
        )

        val mode = downloadNetworkPreference.current()
        com.stash.core.data.sync.workers.DiscoveryDownloadWorker.enqueueOneTime(
            context = context,
            constraints = com.stash.core.data.sync.workers.constraintsForManualTrigger(mode),
        )
    }

    override suspend fun removeDownload(trackId: Long) {
        val entity = trackDao.getById(trackId) ?: return
        entity.filePath?.let { deleteTrackFile(it) }
        // Drop pending/in-flight queue entries so a fresh download can't
        // immediately repopulate the file we just removed.
        downloadQueueDao.deleteByTrackId(trackId)
        trackDao.clearDownloadState(trackId)
        // Deliberately no trackDeletions emit — the row is still alive.
        // ExoPlayer's open FD on the unlinked file keeps the currently-
        // playing track audible through track end (Unix semantics). The
        // next play picks up the streaming path naturally.
    }

    override suspend fun queueDownloadsForPlaylist(playlistId: Long): Int {
        val tracks = trackDao.getByPlaylist(playlistId, includeStreamable = true)
            .first()
        val candidates = tracks.filter { !it.isDownloaded }
        if (candidates.isEmpty()) return 0

        val entries = candidates.mapNotNull { entity ->
            val existing = downloadQueueDao.getByTrackId(entity.id)
            if (existing != null && existing.status in NON_TERMINAL_QUEUE_STATES) {
                return@mapNotNull null
            }
            com.stash.core.data.db.entity.DownloadQueueEntity(
                trackId = entity.id,
                syncId = null,
                searchQuery = "${entity.artist} - ${entity.title}",
                youtubeUrl = entity.youtubeId?.let { "https://music.youtube.com/watch?v=$it" },
            )
        }
        if (entries.isNotEmpty()) {
            downloadQueueDao.insertAll(entries)
            val mode = downloadNetworkPreference.current()
            com.stash.core.data.sync.workers.DiscoveryDownloadWorker.enqueueOneTime(
                context = context,
                constraints = com.stash.core.data.sync.workers.constraintsForManualTrigger(mode),
            )
        }
        return entries.size
    }

    override suspend fun removeDownloadsForPlaylist(playlistId: Long): Int {
        val tracks = trackDao.getByPlaylist(playlistId, includeStreamable = true)
            .first()
        val downloaded = tracks.filter { it.isDownloaded }
        for (entity in downloaded) {
            entity.filePath?.let { deleteTrackFile(it) }
            downloadQueueDao.deleteByTrackId(entity.id)
            trackDao.clearDownloadState(entity.id)
        }
        return downloaded.size
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
            syncEnabled = true,
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
                // v0.9.23: mark as user-added so REFRESH-mode sync of
                // imported Spotify / YT Music playlists doesn't wipe it.
                // See issue #42.
                locallyAdded = true,
            )
        )
        // v0.9.27 — count downloaded-only here; stream-only tracks don't
        // affect the persisted track_count metric used for UI badges.
        val count = trackDao.getByPlaylist(playlistId, includeStreamable = false).first().size
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
        // v0.9.27 — count downloaded-only here; stream-only tracks don't
        // affect the persisted track_count metric used for UI badges.
        val count = trackDao.getByPlaylist(playlistId, includeStreamable = false).first().size
        playlistDao.updateTrackCount(playlistId, count)
    }

    override fun getUserCreatedPlaylists(): Flow<List<com.stash.core.model.Playlist>> =
        playlistDao.getUserCreatedPlaylists().map { entities -> entities.map { it.toDomain() } }

    override fun getPickablePlaylists(): Flow<List<com.stash.core.model.Playlist>> =
        playlistDao.getPickablePlaylists().map { entities -> entities.map { it.toDomain() } }

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
        // v0.9.15: explicit-block override. If the user ticked "Block this
        // track" on the delete dialog, that intent always wins — we tear
        // down the file + every cross-ref + insert the blocklist entry,
        // even if the track is in Liked Songs or another playlist. The old
        // protection logic was a safety net for *delete-without-block*; an
        // explicit block should never silently no-op.
        if (alsoBlacklist) {
            val track = trackDao.getById(trackId) ?: return MusicRepository.CascadeRemovalSummary(
                deleted = 0, keptProtected = 0, keptElsewhere = 0, blacklisted = 0,
            )
            blocklistGuard.block(track, com.stash.core.data.blocklist.BlockSource.PLAYLIST_DELETE)
            _trackDeletions.tryEmit(trackId)
            return MusicRepository.CascadeRemovalSummary(
                deleted = 1, keptProtected = 0, keptElsewhere = 0, blacklisted = 1,
            )
        }

        // Step 1: always detach from the target playlist.
        playlistDao.removeTrackFromPlaylist(fromPlaylistId, trackId)

        // Step 2: protected-playlist escape hatch. Liked Songs and in-app
        // custom playlists count as user-curated data — we refuse to let a
        // cascade from elsewhere destroy them.
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

        // Step 4: nothing else claims the track. Hard delete the row + file.
        val track = trackDao.getById(trackId) ?: return MusicRepository.CascadeRemovalSummary(
            deleted = 0, keptProtected = 0, keptElsewhere = 0, blacklisted = 0,
        )
        track.filePath?.let { deleteTrackFile(it) }
        track.albumArtPath?.let { deleteTrackFile(it) }
        trackDao.delete(track)
        _trackDeletions.tryEmit(trackId)
        return MusicRepository.CascadeRemovalSummary(
            deleted = 1, keptProtected = 0, keptElsewhere = 0, blacklisted = 0,
        )
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
        // v0.9.15: Delegate to BlocklistGuard for the atomic transaction
        // (insert blocklist row + delete playlist_tracks + delete queue
        // rows + delete tracks row + delete files). Identity-keyed so a
        // re-like on a different source can't resurrect the track.
        val track = trackDao.getById(trackId) ?: return
        blocklistGuard.block(track, com.stash.core.data.blocklist.BlockSource.OTHER)
        _trackDeletions.tryEmit(trackId)
    }

    override suspend fun unblacklistTrack(trackId: Long) {
        // v0.9.15: After Phase 3 ships, the tracks row is gone for blocked
        // identities so this getById returns null. Settings UI should call
        // BlocklistGuard.unblock(canonicalKey) directly going forward; this
        // method exists only for backward-compat with any pre-rebind caller.
        val track = trackDao.getById(trackId) ?: return
        val key = com.stash.core.data.blocklist.BlocklistKey.of(
            artist = track.artist, title = track.title, matcher = trackMatcher,
        )
        blocklistGuard.unblock(key)
    }

    // ── Streaming engine ────────────────────────────────────────────────

    override suspend fun applyStreamingMode(enabled: Boolean) {
        // v0.9.30 Path A: Library is downloaded-only regardless of toggle.
        // The pref only gates search-tap streaming behaviour, so the
        // orchestrator collapses to a single pref write.
        streamingPreference.setEnabled(enabled)
    }

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
            // v0.9.27 — downloaded-only is correct: this is a duplicate-detection
            // migration that compares the persisted track_count against actual
            // rows, both of which historically counted downloaded entries only.
            val tracks = trackDao.getByPlaylist(playlist.id, includeStreamable = false).first()
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

    companion object {
        private const val DOWNLOADS_MIX_SOURCE_ID = "stash_downloads_mix"

        /**
         * WorkManager unique-work names for the five Stash Mix workers. Used
         * by [applyStashMixesEnabled] to cancel them all in one shot when
         * the user opts out. Names mirror the `WORK_NAME` / `UNIQUE_WORK_NAME`
         * constants inside each worker — kept in sync by code review.
         */
        private val STASH_MIX_WORK_NAMES = listOf(
            "stash_mix_refresh",
            "stash_discovery",
            "stash_tag_enrichment",
            "stash_track_info_enrichment",
            "discovery_download",
        )

        /**
         * Queue statuses we treat as "still in flight" — if any of these
         * already exists for a track, [queueDownload] / bulk variants
         * short-circuit so rapid taps don't fan out into duplicate inserts.
         */
        private val NON_TERMINAL_QUEUE_STATES = setOf(
            com.stash.core.model.DownloadStatus.PENDING,
            com.stash.core.model.DownloadStatus.IN_PROGRESS,
            com.stash.core.model.DownloadStatus.WAITING_FOR_LOSSLESS,
        )
    }
}
