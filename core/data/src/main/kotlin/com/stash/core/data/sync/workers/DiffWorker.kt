package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
                val localPlaylist = findOrCreatePlaylist(playlistSnapshot)

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
    ): PlaylistEntity {
        val existing = playlistDao.findBySourceId(snapshot.sourcePlaylistId)
        if (existing != null) {
            // Refresh metadata from the remote snapshot so the Home page
            // picks up new cover art and renamed mixes. Without this, a
            // Daily Mix would render the same cover art forever even
            // though the tracks rotate — making mixes "feel stale."
            // Only write when the value actually changed so Room doesn't
            // broadcast spurious Flow emissions on every sync.
            if (snapshot.artUrl != null && snapshot.artUrl != existing.artUrl) {
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
                artUrl = snapshot.artUrl ?: existing.artUrl,
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
            // Opt-in by default for every source. The first Sync Now is
            // effectively a discovery pass — it populates playlist rows
            // but queues nothing for download until the user picks what
            // they actually want in the Sync Preferences card. Fixes
            // issue #10 (unchecked playlists downloading anyway) and
            // brings YouTube in line with Spotify's existing behavior.
            syncEnabled = false,
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
    ): Int {
        // In REFRESH mode, clear existing playlist-track associations
        // before inserting the current set. In ACCUMULATE mode, keep
        // existing tracks — new ones are added, the soft-delete guard in
        // ensurePlaylistMembership stops user-removed tracks from coming
        // back, and duplicates fall out naturally since existing rows are
        // re-stamped by the same (playlistId, trackId) primary key.
        if (syncMode == SyncMode.REFRESH) {
            playlistDao.clearPlaylistTracks(localPlaylist.id)
        }

        var newTrackCount = 0
        for (trackSnapshot in trackSnapshots) {
            val existingTrack = findExistingTrack(trackSnapshot)

            // Blacklist: user explicitly blocked this identity from ever
            // being re-downloaded. Skip both the download-queue insert
            // and the playlist_tracks link — the track stays invisible
            // to the library unless the user unblocks from Settings →
            // Blocked Songs.
            if (existingTrack != null && existingTrack.isBlacklisted) {
                Log.d(
                    TAG,
                    "Skipping blacklisted track id=${existingTrack.id} " +
                        "'${existingTrack.title}' by ${existingTrack.artist}",
                )
                continue
            }

            if (existingTrack != null) {
                ensurePlaylistMembership(
                    playlistId = localPlaylist.id,
                    trackId = existingTrack.id,
                    position = trackSnapshot.position,
                )

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

                val searchQuery = "${trackSnapshot.artist} - ${trackSnapshot.title}"
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
        // album art (Spotify's Daily Mix mosaic URL is aggressively
        // cached upstream and often doesn't rotate between syncs —
        // deriving the cover from the current tracks guarantees a
        // visible change every time the tracklist rotates).
        val coverToSet = trackSnapshots
            .mapNotNull { it.albumArtUrl }
            .firstOrNull()
            ?: playlistSnapshot.artUrl
        if (coverToSet != null && coverToSet != localPlaylist.artUrl) {
            playlistDao.updateArtUrl(localPlaylist.id, coverToSet)
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
