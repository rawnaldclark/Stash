package com.stash.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.common.matchesArtistCredits
import com.stash.core.data.db.dao.AlbumSummary
import com.stash.core.data.db.dao.ArtistSummary
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.mapper.toEntity
import com.stash.core.data.mix.LastFmRecommendationSource
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
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
    private val localFileOps: com.stash.core.data.files.LocalFileOps,
    private val syncPreferencesManager: com.stash.core.data.sync.SyncPreferencesManager,
    private val singleTrackDownloadEnqueuer: com.stash.core.data.sync.SingleTrackDownloadEnqueuer,
    private val lastFmRecommendationSource: LastFmRecommendationSource,
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
    /**
     * Deleted track ids, consumed by PlayerRepositoryImpl to evict them from the
     * live queue. Every event is load-bearing: a missed one leaves a deleted track
     * playable, pointing at a file that no longer exists — the exact symptom a user
     * reported (a removed song carrying on playing).
     *
     * Emitted with `emit`, NOT `tryEmit`. All five call sites are suspend functions,
     * so real backpressure is available: a slow consumer makes the deleter wait
     * instead of silently dropping the notification. `tryEmit` never suspends — on a
     * full buffer it returns false, and all five sites ignored the result, so a bulk
     * delete (`cleanOrphanedMixTracks`, a multi-select removal) could exceed the
     * buffer and lose evictions with nothing logged.
     *
     * The buffer still exists so the common single-delete case never blocks; it is
     * headroom now rather than the only thing standing between us and data loss.
     */
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
     * Download-integrity sweep (runs every launch via [runMigrations]). Checks
     * every `is_downloaded=1` row's file against [LocalFileOps.classify]:
     *  - reliably MISSING or TOO_SMALL (a failed download's tiny garbage body)
     *    -> the row is un-marked (`is_downloaded`/`file_path`/`file_size_bytes`
     *    cleared) so it streams / re-downloads; junk files are also deleted.
     *  - OK or INCONCLUSIVE -> left untouched. INCONCLUSIVE (a SAF document
     *    whose size couldn't be read at cold start) is the safety valve: we
     *    never un-mark or delete on an ambiguous read, so a flaky boot can't
     *    damage a real external-storage library.
     *
     * Handles both plain filesystem paths and SAF `content://` URIs. Null/blank
     * paths count toward `nullPath` and are un-marked.
     */
    private suspend fun reconcileMissingDownloadedFiles() {
        val refs = trackDao.getDownloadedFileRefs()
        if (refs.isEmpty()) return

        // A downloaded row is unusable when its file is reliably missing OR too
        // small to be real audio (a ~274-byte failed-download body). classify()
        // distinguishes those from an INCONCLUSIVE SAF read (provider didn't
        // report a size / transient cold-start failure), which we must NOT act
        // on — un-marking/deleting on an ambiguous read could damage a real
        // external-storage library on a flaky boot.
        val result = classifyDownloadedRefs(refs) {
            localFileOps.classify(it, com.stash.core.common.constants.StashConstants.MIN_PLAYABLE_LOCAL_BYTES)
        }

        if (result.resetIds.isEmpty()) {
            android.util.Log.d("StashMigrations", "download integrity: all ${refs.size} downloaded files usable")
            return
        }

        // Delete the junk files (present-but-tiny). Missing/null-path rows
        // contribute no path. Best-effort, SAF-aware.
        result.junkPaths.forEach { localFileOps.delete(it) }

        // Un-mark the unusable rows in chunks to stay under SQLite's parameter
        // ceiling — they become not-downloaded and therefore streamable.
        result.resetIds.chunked(500).forEach { chunk ->
            trackDao.bulkResetForReDownload(chunk)
        }
        android.util.Log.i(
            "StashMigrations",
            "download integrity: scanned ${refs.size} downloaded rows, reset ${result.resetIds.size} " +
                "unusable (junk-deleted=${result.junkPaths.size}, nullPath=${result.nullPath})",
        )
    }

    // ── Track queries ───────────────────────────────────────────────────

    override fun getAllTracks(): Flow<List<Track>> =
        trackDao.getLibraryByDateAdded()
            // #380 root-cause fix lives in the DAO: a narrow projection
            // (rows small enough that realistic libraries fit one
            // ~2 MB CursorWindow), `is_downloaded = 1` in SQL instead
            // of the in-memory filter below, and @Transaction so each
            // emission reads one consistent snapshot even when a
            // playlist delete mutates `tracks` mid-query (the old
            // SELECT * re-executed per window refill and raced the
            // delete — issues #14/#380). This retry predates that fix;
            // it stays as the last-resort belt for any CursorWindow
            // surprise a vendor SQLite build cooks up. Cap at 3
            // attempts so a non-race failure doesn't loop forever.
            .retryWhen { cause, attempt ->
                val raced = cause is IllegalStateException &&
                    cause.message?.contains("CursorWindow") == true
                raced && attempt < 3
            }
            .map { rows -> rows.map { it.toDomain() } }

    override fun getTracksByArtist(artist: String): Flow<List<Track>> =
        trackDao.getByArtist(artist)
            .map { entities ->
                entities
                    .filter { matchesArtistCredits(it.artist, it.albumArtist, artist) }
                    .map { it.toDomain() }
            }
            // The matchesArtistCredits post-filter is O(n) over the SQL
            // candidate superset and off the main thread.
            .flowOn(Dispatchers.Default)

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
    // track streams via Kennyy. In offline mode it stays downloaded-only —
    // EXCEPT Stash Mixes, which are an inherently online discovery surface
    // and stay fully visible offline (the DAO's STASH_MIX exemption in
    // TrackDao.getByPlaylist). Tap-time playability is governed by live
    // connectivity in PlaylistDetailViewModel, not this preference.
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
            // The Last.fm source (#255) is a STASH_MIX but NOT a builtin, so
            // the sweeps above miss it in both directions. reconcile() re-
            // derives its state from session + toggle + this switch, so one
            // call restores it here and hides it below — no second copy of
            // the predicate to drift.
            runCatching { lastFmRecommendationSource.reconcile() }
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
            // Same single call as the enable branch: reconcile() reads the
            // now-persisted master switch and deactivates the Last.fm recipe
            // and its playlist without deleting anything.
            runCatching { lastFmRecommendationSource.reconcile() }
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

    override fun observeTrackByYoutubeId(youtubeId: String): Flow<Track?> =
        trackDao.observeByYoutubeId(youtubeId).map { it?.toDomain() }

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

    override suspend fun ensureTrackPersisted(track: Track): Long {
        // Quick exit: real DB row already.
        if (track.id > 0L) {
            val existing = trackDao.getById(track.id)
            if (existing != null) {
                backfillDurationIfBetter(existing.id, existing.durationMs, track.durationMs)
                return track.id
            }
        }

        // Dedup by every UNIQUE key — youtube_id, then spotify_uri — before the
        // canonical fuzzy match. Missing spotify_uri here used to be harmless
        // (REPLACE silently absorbed the collision by wiping the existing row);
        // now that insert ABORTs, an un-deduped spotify_uri would THROW, so a
        // Spotify track whose canonical identity drifted (feat./remaster tags)
        // must still resolve to its existing row by URI.
        val youtubeId = track.youtubeId
        if (!youtubeId.isNullOrBlank()) {
            trackDao.findByYoutubeId(youtubeId)?.let { existing ->
                backfillDurationIfBetter(existing.id, existing.durationMs, track.durationMs)
                return existing.id
            }
        }
        val spotifyUri = track.spotifyUri
        if (!spotifyUri.isNullOrBlank()) {
            trackDao.findBySpotifyUri(spotifyUri)?.let { existing ->
                backfillDurationIfBetter(existing.id, existing.durationMs, track.durationMs)
                return existing.id
            }
        }
        val cTitle = canonicalizeIdentity(track.title)
        val cArtist = canonicalizeIdentity(track.artist)
        if (cTitle.isNotBlank() && cArtist.isNotBlank()) {
            trackDao.findByCanonicalIdentity(cTitle, cArtist)?.let { existing ->
                backfillDurationIfBetter(existing.id, existing.durationMs, track.durationMs)
                return existing.id
            }
        }

        // Insert a fresh stub — id = 0 so Room autogens.
        return trackDao.insert(
            track.toEntity().copy(
                id = 0L,
                canonicalTitle = cTitle,
                canonicalArtist = cArtist,
                isStreamable = true,
            )
        )
    }

    private suspend fun backfillDurationIfBetter(trackId: Long, existing: Long, incoming: Long) {
        if (existing <= 0L && incoming > 0L) {
            trackDao.backfillDurationIfMissing(trackId, incoming)
        }
    }

    /** Same normalization as [SearchDownloadCoordinator.canonicalize] — kept
     *  local to avoid leaking that private helper out of `:data:download`. */
    private fun canonicalizeIdentity(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    override suspend fun deleteTrack(track: Track): Boolean {
        // Best-effort file deletion -- the file may already be gone.
        track.filePath?.let { deleteTrackFile(it) }
        // Album art lives in the app cache (internal only) but route it
        // through the same helper so a future SAF-backed art cache would
        // work without another code change.
        track.albumArtPath?.let { deleteTrackFile(it) }
        trackDao.delete(track.toEntity())
        _trackDeletions.emit(track.id)
        return true
    }

    // ── User-initiated download / remove-download ─────────────────────
    //
    // These methods feed the same `download_queue` infrastructure that
    // sync uses, but mark `sync_id = null` so the rows land in the
    // discovery-worker partition. Removal nulls the file_path / flags
    // but keeps the row so the track remains streamable (Path A).

    override suspend fun queueDownload(trackId: Long): Boolean {
        val entity = trackDao.getById(trackId) ?: return false
        if (entity.isDownloaded) return false

        // Reuse an existing queue row if there is one; otherwise insert a fresh
        // manual (discovery-partition) row. A manual tap is an explicit
        // "download this now", so a pre-existing non-terminal row must NOT make
        // this a silent no-op: previously a stuck sync row (streaming-mode
        // PENDING / deferred WAITING_FOR_LOSSLESS) made every already-synced
        // track report "Couldn't queue download". Reset the row to PENDING so it
        // downloads fresh.
        val existing = downloadQueueDao.getByTrackId(trackId)
        val queueId = if (existing == null) {
            downloadQueueDao.insert(
                com.stash.core.data.db.entity.DownloadQueueEntity(
                    trackId = trackId,
                    syncId = null,
                    searchQuery = "${entity.artist} - ${entity.title}",
                    youtubeUrl = entity.youtubeId?.let { "https://music.youtube.com/watch?v=$it" },
                )
            )
        } else {
            downloadQueueDao.resetToPending(listOf(existing.id))
            existing.id
        }

        // Drive THIS row through TrackDownloadWorker single-track mode, which
        // downloads regardless of streaming mode and the sync/discovery
        // partition — unlike the chain/discovery drains, which skip in streaming
        // mode (the reason the stuck sync rows never drained).
        singleTrackDownloadEnqueuer.enqueue(queueId)
        return true
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

    override suspend fun removePlaylist(playlist: Playlist) {
        playlistDao.delete(playlist.toEntity())
    }

    override suspend fun updatePlaylistArtUrl(playlistId: Long, artUrl: String?) {
        playlistDao.updateArtUrl(playlistId, artUrl)
    }

    override suspend fun setPlaylistPinned(playlistId: Long, pinned: Boolean) {
        playlistDao.setPinned(playlistId, pinned)
    }

    override suspend fun setPlaylistPinnedToHome(playlistId: Long, pinnedAt: Long?) {
        playlistDao.setPinnedToHome(playlistId, pinnedAt)
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
        // Issue #114: this previously inserted the cross-ref unconditionally
        // and crashed the app with SQLITE_CONSTRAINT_FOREIGNKEY when either
        // parent row was missing (track orphaned by cleanup, playlist deleted
        // by a parallel REFRESH-mode sync, stale UI cache after re-sync, etc.).
        // Defensive pre-check + try/catch so the crash becomes a logged no-op
        // and the user can keep using the app.
        val trackExists = trackDao.getById(trackId) != null
        val playlistExists = playlistDao.getById(playlistId) != null
        if (!trackExists || !playlistExists) {
            android.util.Log.w(
                "MusicRepository",
                "addTrackToPlaylist: skipping insert — trackExists=$trackExists " +
                    "(id=$trackId), playlistExists=$playlistExists (id=$playlistId)",
            )
            return
        }
        val position = playlistDao.getNextPosition(playlistId)
        try {
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
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // Pre-check raced with a delete (orphan cleanup, sync REFRESH, blocklist).
            // The user-visible effect is the same as a missing-parent no-op: the
            // tap appears to do nothing, but the app stays alive.
            android.util.Log.w(
                "MusicRepository",
                "addTrackToPlaylist: FK constraint failed after pre-check " +
                    "(trackId=$trackId, playlistId=$playlistId) — likely race with delete",
                e,
            )
            return
        }
        // v0.9.37 — count downloaded + streamable. Stream-only tracks
        // (e.g. Liked Songs added from the Now Playing heart on a
        // streaming track) are first-class playlist members under the
        // streaming-engine model and must be reflected in the badge,
        // or the Library card lies ("1 tracks" but the detail shows 4).
        val count = trackDao.getByPlaylist(playlistId, includeStreamable = true).first().size
        playlistDao.updateTrackCount(playlistId, count)
    }

    override suspend fun ensureDownloadsMixSeeded(): Long {
        return playlistDao.ensurePlaylist(downloadsMixEntity())
    }

    override suspend fun linkTrackToDownloadsMix(trackId: Long) {
        val playlistId = playlistDao.ensurePlaylistAndActiveCrossRef(
            playlist = downloadsMixEntity(),
            trackId = trackId,
        )
        val count = trackDao.getByPlaylist(playlistId, includeStreamable = true).first().size
        playlistDao.updateTrackCount(playlistId, count)
    }

    private fun downloadsMixEntity() =
        com.stash.core.data.db.entity.PlaylistEntity(
            name = "Your Downloads",
            source = com.stash.core.model.MusicSource.BOTH,
            sourceId = DOWNLOADS_MIX_SOURCE_ID,
            type = com.stash.core.model.PlaylistType.DOWNLOADS_MIX,
            syncEnabled = false,
        )

    override suspend fun removeTrackFromPlaylist(trackId: Long, playlistId: Long) {
        playlistDao.softDeleteTrackFromPlaylist(playlistId, trackId)
        // v0.9.37 — count downloaded + streamable. Stream-only tracks
        // (e.g. Liked Songs added from the Now Playing heart on a
        // streaming track) are first-class playlist members under the
        // streaming-engine model and must be reflected in the badge,
        // or the Library card lies ("1 tracks" but the detail shows 4).
        val count = trackDao.getByPlaylist(playlistId, includeStreamable = true).first().size
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
            _trackDeletions.emit(trackId)
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
        _trackDeletions.emit(trackId)
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
        _trackDeletions.emit(trackId)
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
        // ACCUMULATE = never auto-delete. While any source accumulates, the
        // library is append-only — a deselected playlist, a rotated mix, or a
        // disconnected source must not delete a downloaded track or its files.
        // This single gate covers BOTH callers (DiffWorker per-sync + the startup
        // sweep). See SyncMode / the refresh-accumulate design.
        if (syncPreferencesManager.anyAccumulate()) {
            android.util.Log.d("StashCleanup", "Skipped orphan sweep — accumulate mode active")
            return 0
        }
        // Re-evaluate the orphan predicate (membership + active-discovery
        // exclusion) and delete the rows in ONE transaction, so a track
        // re-linked by DiffWorker / the mix materializer in the gap can't be
        // raced and lose its fresh cross-ref + irrecoverable FLAC. Files are
        // removed only AFTER their rows are gone (a crash mid-file-delete
        // leaves a harmless orphaned file, never a row pointing at nothing).
        val deleted = trackDao.deleteOrphanedDownloadedTracks()
        if (deleted.isEmpty()) return 0

        for (track in deleted) {
            // Delete the audio file from disk (SAF-aware — see deleteTrackFile).
            track.filePath?.let { deleteTrackFile(it) }
            // Delete locally-stored album art if present.
            track.albumArtPath?.let { deleteTrackFile(it) }
            _trackDeletions.emit(track.id)
        }

        android.util.Log.i(
            "StashCleanup",
            "Cleaned ${deleted.size} orphaned track(s) and their audio files",
        )
        return deleted.size
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
