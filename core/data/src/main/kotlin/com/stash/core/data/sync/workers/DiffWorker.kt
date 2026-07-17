package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.model.PlaylistType
import androidx.room.withTransaction
import androidx.work.workDataOf
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import com.stash.core.model.SyncState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * A newly-discovered playlist's initial [PlaylistEntity.syncEnabled].
 * Algorithmic mixes (DAILY_MIX) auto-enable in Online mode so they surface
 * immediately with no download. Everything else — and every playlist in
 * Offline mode — stays opt-in: the first Sync Now is a discovery pass that
 * downloads nothing unasked. [online] is the streaming-mode flag (on = stream,
 * don't download).
 */
internal fun defaultSyncEnabled(type: PlaylistType, online: Boolean): Boolean =
    type == PlaylistType.DAILY_MIX && online

/**
 * Whether a playlist's tracks should be enqueued for download during this sync.
 * Online/streaming mode never downloads (tracks stream on tap). In Offline mode
 * everything downloads EXCEPT algorithmic mixes (DAILY_MIX) — those are
 * surface-only (stream-on-tap), so an auto-enabled mix never pulls bytes even
 * after the user switches to Offline and re-syncs.
 */
internal fun shouldEnqueueForDownload(type: PlaylistType, streamingMode: Boolean): Boolean =
    !streamingMode && type != PlaylistType.DAILY_MIX

/**
 * Second worker in the sync chain. Compares remote playlist/track snapshots
 * against the local database to find new tracks that need downloading.
 *
 * For each new track discovered, creates a [TrackEntity] and a
 * [DownloadQueueEntity] with PENDING status. Updates playlist membership
 * via [PlaylistTrackCrossRef].
 *
 * Outputs [KEY_SYNC_ID] and [KEY_NEW_TRACKS] for downstream workers.
 */
@HiltWorker
class DiffWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val database: StashDatabase,
    private val remoteSnapshotDao: RemoteSnapshotDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val syncHistoryDao: SyncHistoryDao,
    private val trackMatcher: TrackMatcher,
    private val syncStateManager: SyncStateManager,
    private val musicRepository: MusicRepository,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        const val KEY_NEW_TRACKS = "new_tracks"
        const val KEY_PLAYLISTS_CHECKED = "playlists_checked"
        private const val TAG = "DiffWorker"
    }

    override suspend fun doWork(): Result {
        val syncId = inputData.getLong(PlaylistFetchWorker.KEY_SYNC_ID, -1L)
        if (syncId == -1L) {
            syncStateManager.onError("DiffWorker: missing sync ID")
            return Result.failure()
        }

        try {
            syncStateManager.onDiffing()
            syncHistoryDao.updateStatus(syncId, SyncState.DIFFING)

            // Read each source's sync mode once at the start of the diff
            // pass. Per-source (not global) as of v0.5 — the user picks
            // REFRESH/ACCUMULATE independently for Spotify and YouTube in
            // the Sync Preferences cards.
            val spotifySyncMode = syncPreferencesManager.spotifySyncMode.first()
            val youtubeSyncMode = syncPreferencesManager.youtubeSyncMode.first()

            // Read the streaming-mode toggle ONCE up front. When on, new
            // track rows still land in the `tracks` table (the metadata is
            // needed regardless — Home playlist surfaces + playlist detail
            // rely on it) but skip the `download_queue` enqueue. Tracks
            // become available via streaming-tap through KennyySource.
            val streamingMode = streamingPreference.current()

            val playlistSnapshots = remoteSnapshotDao.getPlaylistSnapshotsBySyncId(syncId)
            var newTrackCount = 0

            for (playlistSnapshot in playlistSnapshots) {
                // Pick the mode for this specific playlist's source so a
                // user can Refresh Spotify Daily Mixes while Accumulating
                // YouTube Liked Music on the same sync run.
                val playlistSyncMode = when (playlistSnapshot.source) {
                    MusicSource.YOUTUBE -> youtubeSyncMode
                    else -> spotifySyncMode
                }

                // Find or create the local playlist (writes, but outside
                // the per-playlist transaction — it owns its own atomicity
                // and needs its id to drive the block below).
                val localPlaylist = findOrCreatePlaylist(playlistSnapshot, streamingMode)

                // Skip playlists the user has disabled in Sync Preferences.
                if (!localPlaylist.syncEnabled) {
                    Log.d(TAG, "Playlist '${playlistSnapshot.playlistName}' sync disabled, skipping")
                    continue
                }

                // Check snapshot_id for change detection (Spotify only).
                val localSnapshotId = playlistDao.getSnapshotId(localPlaylist.id)
                if (localSnapshotId != null &&
                    playlistSnapshot.snapshotId != null &&
                    localSnapshotId == playlistSnapshot.snapshotId
                ) {
                    Log.d(TAG, "Playlist '${playlistSnapshot.playlistName}' unchanged, skipping")
                    continue
                }

                // Get track snapshots for this playlist (read is outside
                // the transaction to keep the critical section short).
                val trackSnapshots = remoteSnapshotDao.getTrackSnapshotsByPlaylistId(
                    playlistSnapshot.id
                )

                // Per-playlist atomicity: a crash mid-loop no longer leaves
                // an empty playlist (REFRESH cleared but never re-inserted)
                // or half-linked membership rows. Scope is per-playlist so
                // the transaction stays short — wrapping the whole diff
                // pass would block the writer during long syncs.
                val playlistNewTracks = database.withTransaction {
                    processPlaylist(
                        playlistSnapshot = playlistSnapshot,
                        localPlaylist = localPlaylist,
                        trackSnapshots = trackSnapshots,
                        syncMode = playlistSyncMode,
                        syncId = syncId,
                        streamingMode = streamingMode,
                    )
                }
                newTrackCount += playlistNewTracks
            }

            // Soft-hide YouTube playlists that rotated off the home feed
            // since the last sync. Without this, the Home screen keeps
            // showing stale "My Mix N" cards that point at empty
            // playlist_tracks (they were never populated because sync was
            // disabled at the time). Only targets YOUTUBE — Spotify
            // playlists are user-curated and shouldn't silently disappear
            // just because the sync didn't surface them. findOrCreatePlaylist
            // above re-activates a hidden playlist that reappears in a
            // later snapshot, so the cycle is reversible.
            val youtubeSourceIds = playlistSnapshots
                .filter { it.source == MusicSource.YOUTUBE }
                .map { it.sourcePlaylistId }
            if (youtubeSourceIds.isNotEmpty()) {
                val hidden = playlistDao.deactivateMissingForSource(
                    source = MusicSource.YOUTUBE,
                    currentSourceIds = youtubeSourceIds,
                )
                if (hidden > 0) {
                    Log.i(TAG, "Deactivated $hidden stale YouTube playlist(s)")
                }
            }

            // Clean up orphaned tracks whose playlists were refreshed and
            // that no longer belong to any playlist. Frees disk storage.
            val cleaned = musicRepository.cleanOrphanedMixTracks()
            if (cleaned > 0) {
                Log.i(TAG, "Cleaned $cleaned orphaned track(s) after diff")
            }

            // Update sync history with counts.
            syncHistoryDao.updateCounts(
                id = syncId,
                playlistsChecked = playlistSnapshots.size,
                newTracksFound = newTrackCount,
                tracksDownloaded = 0,
                tracksFailed = 0,
                bytesDownloaded = 0,
            )

            return Result.success(
                workDataOf(
                    KEY_SYNC_ID to syncId,
                    KEY_NEW_TRACKS to newTrackCount,
                    KEY_PLAYLISTS_CHECKED to playlistSnapshots.size,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Diff failed", e)
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message,
            )
            syncStateManager.onError("Diff failed: ${e.message}", e)
            return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
        }
    }

    /**
     * Finds an existing local playlist matching the remote snapshot,
     * or creates a new one if none exists.
     */
    private suspend fun findOrCreatePlaylist(
        snapshot: RemotePlaylistSnapshotEntity,
        streamingMode: Boolean,
    ): PlaylistEntity {
        val existing = playlistDao.findBySourceId(snapshot.sourcePlaylistId)
        if (existing != null) {
            // Art refresh: ONLY for DAILY_MIX. Daily Mixes (and Spotify's
            // weekly mixes — Discover Weekly, Release Radar, etc., which
            // share the DAILY_MIX type) rotate, so their cover should
            // follow the tracks. Curated content (LIKED_SONGS, CUSTOM,
            // STASH_MIX) keeps whatever art was imported on first sync —
            // overwriting it surprises users whose personal playlists
            // would otherwise look different every sync.
            val rotatesArt = existing.type == PlaylistType.DAILY_MIX
            if (rotatesArt && snapshot.artUrl != null && snapshot.artUrl != existing.artUrl) {
                playlistDao.updateArtUrl(existing.id, snapshot.artUrl)
            }
            if (snapshot.playlistName.isNotBlank() &&
                snapshot.playlistName != existing.name
            ) {
                playlistDao.updateName(existing.id, snapshot.playlistName)
            }
            // Re-activate a previously auto-hidden playlist when it
            // reappears in today's snapshot. Pairs with the post-loop
            // deactivateMissingForSource call below — without it, a mix
            // that rotated off and back on would stay invisible forever.
            if (!existing.isActive) {
                playlistDao.reactivateById(existing.id)
            }
            return existing.copy(
                artUrl = if (rotatesArt) snapshot.artUrl ?: existing.artUrl else existing.artUrl,
                name = snapshot.playlistName.ifBlank { existing.name },
                isActive = true,
            )
        }

        val newPlaylist = PlaylistEntity(
            name = snapshot.playlistName,
            source = snapshot.source,
            sourceId = snapshot.sourcePlaylistId,
            type = snapshot.playlistType,
            mixNumber = snapshot.mixNumber,
            artUrl = snapshot.artUrl,
            trackCount = snapshot.trackCount,
            // Opt-in by default — EXCEPT algorithmic mixes in Online mode.
            // A DAILY_MIX discovered while streaming auto-enables so it
            // surfaces immediately with no download (Online skips the
            // download_queue enqueue anyway). Every other type, and every
            // playlist in Offline mode, stays opt-in: the first Sync Now is
            // a discovery pass that populates playlist rows but queues
            // nothing until the user picks what they want in the Sync
            // Preferences card. Fixes issue #10 (unchecked playlists
            // downloading anyway) and keeps YouTube in line with Spotify.
            syncEnabled = defaultSyncEnabled(snapshot.playlistType, streamingMode),
        )
        val id = playlistDao.insert(newPlaylist)
        return newPlaylist.copy(id = id)
    }

    /**
     * Checks whether a remote track already exists in the local database.
     * Collapses three previously-sequential lookups (Spotify URI →
     * YouTube ID → canonical identity) into a single OR-query whose
     * ORDER BY mirrors the legacy priority. For a 3,000-track library
     * this eliminates ~6,000 extra SELECTs per full sync.
     */
    private suspend fun findExistingTrack(snapshot: RemoteTrackSnapshotEntity): TrackEntity? {
        val canonicalTitle = trackMatcher.canonicalTitle(snapshot.title)
        val canonicalArtist = trackMatcher.canonicalArtist(snapshot.artist)
        return trackDao.findByAnyIdentity(
            spotifyUri = snapshot.spotifyUri,
            spotifyUriIsNull = if (snapshot.spotifyUri == null) 1 else 0,
            youtubeId = snapshot.youtubeId,
            youtubeIdIsNull = if (snapshot.youtubeId == null) 1 else 0,
            canonicalTitle = canonicalTitle,
            canonicalArtist = canonicalArtist,
        )
    }

    /**
     * Per-playlist diff body — runs inside a Room transaction so the
     * REFRESH clear + re-insert + metadata updates commit (or fail) as a
     * single unit. If the worker is killed mid-way through, either
     * everything for this playlist is applied or nothing is.
     *
     * Returns the number of newly-queued tracks so the caller can roll
     * the count up.
     */
    private suspend fun processPlaylist(
        playlistSnapshot: RemotePlaylistSnapshotEntity,
        localPlaylist: PlaylistEntity,
        trackSnapshots: List<RemoteTrackSnapshotEntity>,
        syncMode: SyncMode,
        syncId: Long,
        streamingMode: Boolean,
    ): Int {
        // In REFRESH mode, clear existing playlist-track associations
        // before inserting the current set. In ACCUMULATE mode, keep
        // existing tracks — new ones are added, the soft-delete guard in
        // ensurePlaylistMembership stops user-removed tracks from coming
        // back, and duplicates fall out naturally since existing rows are
        // re-stamped by the same (playlistId, trackId) primary key.
        //
        // v0.9.23 — only wipe SYNC-added rows. User-added tracks
        // (locally_added = 1) survive REFRESH so manual additions to
        // imported Spotify / YT Music playlists persist across re-syncs.
        // See issue #42.
        if (syncMode == SyncMode.REFRESH) {
            playlistDao.clearSyncedPlaylistTracks(localPlaylist.id)
        }

        var newTrackCount = 0
        for (trackSnapshot in trackSnapshots) {
            // v0.9.15: Blocklist guard at the door. Identity-keyed so a
            // re-like on a different source / canonical disagreement / new
            // tracks row can't slip past. Replaces the prior
            // `existingTrack.isBlacklisted` check, which only worked when
            // findExistingTrack happened to land on the same row.
            if (blocklistGuard.isBlocked(
                    artist = trackSnapshot.artist,
                    title = trackSnapshot.title,
                    spotifyUri = trackSnapshot.spotifyUri,
                    youtubeId = trackSnapshot.youtubeId,
                )) {
                Log.d(TAG, "Skipping blocked snapshot: ${trackSnapshot.artist} - ${trackSnapshot.title}")
                continue
            }

            val existingTrack = findExistingTrack(trackSnapshot)

            if (existingTrack != null) {
                ensurePlaylistMembership(
                    playlistId = localPlaylist.id,
                    trackId = existingTrack.id,
                    position = trackSnapshot.position,
                )

                // v0.9.21 enrichment: if the new snapshot has a youtube_id
                // and the existing track doesn't, fill it in — and stamp
                // any pending download_queue row with the YT URL. Without
                // this, a track originally created by a Spotify sync stays
                // `youtube_id=null` forever; even after a YT Music sync
                // brings the same track in with its videoId, DownloadManager
                // still routes it through the slow Spotify → YT match path
                // because `preResolvedUrl` is null. See conversation
                // 2026-05-12: 4 tracks downloading via Spotify even after
                // Spotify playlists were deselected.
                val snapshotYtId = trackSnapshot.youtubeId
                if (!snapshotYtId.isNullOrBlank() && existingTrack.youtubeId.isNullOrBlank()) {
                    trackDao.updateYoutubeId(existingTrack.id, snapshotYtId)
                    val ytUrl = "https://music.youtube.com/watch?v=$snapshotYtId"
                    downloadQueueDao.fillMissingYoutubeUrlForTrack(existingTrack.id, ytUrl)
                }

                // v0.9.21 enrichment: refresh album-art URL when the
                // incoming snapshot differs from what's stored. Snapshot
                // URLs flow through ArtUrlUpgrader at write time, so when
                // the upgrader gains support for a new CDN, the next
                // re-sync of a playlist rewrites stale rows in place. No
                // backfill migration needed. See conversation 2026-05-13:
                // 228 yt3.googleusercontent.com tracks frozen at 120×120
                // because they synced before the upgrader knew that CDN.
                val snapshotArt = trackSnapshot.albumArtUrl
                if (!snapshotArt.isNullOrBlank() && snapshotArt != existingTrack.albumArtUrl) {
                    trackDao.updateAlbumArtUrl(existingTrack.id, snapshotArt)
                }

                // Auto-reconciliation: if this track is undownloaded,
                // check if a manually-downloaded track with the same
                // canonical identity exists. This handles cases where a
                // user downloaded a track via a different playlist or
                // source, so the existing entry can be resolved
                // automatically.
                if (!existingTrack.isDownloaded && !existingTrack.matchDismissed) {
                    val downloadedMatch = trackDao.findDownloadedByCanonical(
                        canonicalTitle = existingTrack.canonicalTitle.lowercase(),
                        canonicalArtist = existingTrack.canonicalArtist.lowercase(),
                    )
                    if (downloadedMatch != null && downloadedMatch.id != existingTrack.id) {
                        ensurePlaylistMembership(
                            playlistId = localPlaylist.id,
                            trackId = downloadedMatch.id,
                            position = trackSnapshot.position,
                        )
                        val failedEntry = downloadQueueDao.getFailedByTrackId(existingTrack.id)
                        if (failedEntry != null) {
                            downloadQueueDao.updateStatus(
                                id = failedEntry.id,
                                status = DownloadStatus.COMPLETED,
                            )
                        }
                    }
                }
            } else {
                // New track: create entity and queue for download.
                val canonicalTitle = trackMatcher.canonicalTitle(trackSnapshot.title)
                val canonicalArtist = trackMatcher.canonicalArtist(trackSnapshot.artist)

                val newTrack = TrackEntity(
                    title = trackSnapshot.title,
                    artist = trackSnapshot.artist,
                    album = trackSnapshot.album ?: "",
                    durationMs = trackSnapshot.durationMs,
                    source = playlistSnapshot.source,
                    spotifyUri = trackSnapshot.spotifyUri,
                    youtubeId = trackSnapshot.youtubeId,
                    albumArtUrl = trackSnapshot.albumArtUrl,
                    canonicalTitle = canonicalTitle,
                    canonicalArtist = canonicalArtist,
                    isDownloaded = false,
                    isrc = trackSnapshot.isrc,
                    explicit = trackSnapshot.explicit,
                )
                val trackId = trackDao.insert(newTrack)

                ensurePlaylistMembership(
                    playlistId = localPlaylist.id,
                    trackId = trackId,
                    position = trackSnapshot.position,
                )

                // Streaming mode: track row + playlist membership land
                // normally so the playlist surfaces on Home and the playlist
                // detail screen can stream the track via Kennyy on tap. We
                // skip the download_queue enqueue so no bytes hit disk.
                // Offline mode: enqueue the download — EXCEPT algorithmic mixes
                // (DAILY_MIX), which stay surface-only (stream-on-tap) so an
                // auto-enabled mix never pulls bytes after switching to Offline.
                val searchQuery = "${trackSnapshot.artist} - ${trackSnapshot.title}"
                if (shouldEnqueueForDownload(localPlaylist.type, streamingMode)) {
                    Log.i(TAG, "QueueTrace: DiffWorker.insert track_id=$trackId playlist=${localPlaylist.id} '${trackSnapshot.artist} - ${trackSnapshot.title}'")
                    downloadQueueDao.insert(
                        DownloadQueueEntity(
                            trackId = trackId,
                            syncId = syncId,
                            searchQuery = searchQuery,
                            youtubeUrl = trackSnapshot.youtubeId?.let {
                                "https://music.youtube.com/watch?v=$it"
                            },
                        )
                    )
                } else {
                    Log.i(TAG, "QueueTrace: DiffWorker surface-only insert track_id=$trackId playlist=${localPlaylist.id} (download skipped)")
                }
                newTrackCount++
            }
        }

        // Update local playlist metadata inside the transaction so the
        // track count + snapshot id only change if the track work
        // committed successfully.
        playlistDao.updateLastSynced(localPlaylist.id, System.currentTimeMillis())
        if (playlistSnapshot.snapshotId != null) {
            playlistDao.updateSnapshotId(localPlaylist.id, playlistSnapshot.snapshotId)
        }
        playlistDao.updateTrackCount(localPlaylist.id, trackSnapshots.size)

        // Refresh the playlist's cover art from the first unique track
        // album art — but ONLY for DAILY_MIX, which rotates. Curated
        // content (LIKED_SONGS, CUSTOM, STASH_MIX) keeps the art that
        // was imported on first sync; overwriting it on every sync
        // surprises users whose personal playlists would otherwise
        // visually drift toward whatever the latest first track happens
        // to be. Spotify's Daily Mix mosaic URL is aggressively cached
        // upstream and often doesn't rotate between syncs — deriving
        // the cover from current tracks guarantees a visible change
        // when the tracklist rotates.
        if (localPlaylist.type == PlaylistType.DAILY_MIX) {
            val coverToSet = trackSnapshots
                .mapNotNull { it.albumArtUrl }
                .firstOrNull()
                ?: playlistSnapshot.artUrl
            if (coverToSet != null && coverToSet != localPlaylist.artUrl) {
                playlistDao.updateArtUrl(localPlaylist.id, coverToSet)
            }
        }

        return newTrackCount
    }

    /**
     * Ensures a cross-reference exists between a playlist and a track.
     *
     * Three behaviours, picked by the state of any existing row:
     *
     * 1. **No prior row** — insert fresh with `addedAt = Instant.now()`.
     * 2. **Prior row, removed_at IS NULL** — preserve the original
     *    `addedAt` so ACCUMULATE mode can sort newest-added first. If we
     *    let REPLACE stamp `Instant.now()` every sync, every row would
     *    end up with the same addedAt and the "newest on top" UX vanishes.
     * 3. **Prior row, removed_at IS NOT NULL** — the user soft-deleted
     *    this track from this playlist. Do NOT re-insert; that would
     *    overwrite the removal marker and resurrect the track the user
     *    explicitly removed. Tombstone stays; remote snapshot is ignored
     *    for this `(playlist, track)` pair until the user un-removes it.
     *
     * Scope note: behaviour (3) only matters in ACCUMULATE mode. REFRESH
     * hard-deletes all cross-refs for the playlist before this method
     * runs, so there's no prior row to find — REFRESH resurrection is a
     * separate problem and is not addressed here.
     */
    private suspend fun ensurePlaylistMembership(
        playlistId: Long,
        trackId: Long,
        position: Int,
    ) {
        val existingRef = playlistDao.getCrossRef(playlistId, trackId)
        if (existingRef != null && existingRef.removedAt != null) {
            Log.d(
                TAG,
                "Skipping re-link for soft-deleted track $trackId " +
                    "in playlist $playlistId (user removed it)",
            )
            return
        }
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = position,
                addedAt = existingRef?.addedAt ?: java.time.Instant.now(),
            )
        )
    }
}
