package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

            // Read user's sync mode preference once at the start of the diff pass.
            val syncMode = syncPreferencesManager.syncMode.first()

            val playlistSnapshots = remoteSnapshotDao.getPlaylistSnapshotsBySyncId(syncId)
            var newTrackCount = 0

            for (playlistSnapshot in playlistSnapshots) {
                // Find or create the local playlist.
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

                // In REFRESH mode, clear existing playlist-track associations before
                // inserting the current set. Without this, the table accumulates entries
                // from every sync run with overlapping position values.
                // In ACCUMULATE mode, keep existing tracks — new ones are added and
                // duplicates are handled by INSERT ... ON CONFLICT REPLACE in the DAO.
                if (syncMode == SyncMode.REFRESH) {
                    playlistDao.clearPlaylistTracks(localPlaylist.id)
                }

                // Get track snapshots for this playlist.
                val trackSnapshots = remoteSnapshotDao.getTrackSnapshotsByPlaylistId(
                    playlistSnapshot.id
                )

                for (trackSnapshot in trackSnapshots) {
                    val existingTrack = findExistingTrack(trackSnapshot)

                    // Blacklist: user explicitly blocked this identity from
                    // ever being re-downloaded. Skip BOTH the download-queue
                    // insert and the playlist_tracks link — the track stays
                    // invisible to the library unless the user unblocks from
                    // Settings → Blocked Songs.
                    if (existingTrack != null && existingTrack.isBlacklisted) {
                        Log.d(
                            TAG,
                            "Skipping blacklisted track id=${existingTrack.id} " +
                                "'${existingTrack.title}' by ${existingTrack.artist}",
                        )
                        continue
                    }

                    if (existingTrack != null) {
                        // Track already exists locally; ensure playlist membership.
                        ensurePlaylistMembership(localPlaylist.id, existingTrack.id, trackSnapshot.position)

                        // Auto-reconciliation: if this track is undownloaded, check if a
                        // manually-downloaded track with the same canonical identity exists.
                        // This handles cases where a user downloaded a track via a different
                        // playlist or source, so the existing entry can be resolved automatically.
                        if (!existingTrack.isDownloaded && !existingTrack.matchDismissed) {
                            val downloadedMatch = trackDao.findDownloadedByCanonical(
                                canonicalTitle = existingTrack.canonicalTitle.lowercase(),
                                canonicalArtist = existingTrack.canonicalArtist.lowercase(),
                            )
                            if (downloadedMatch != null && downloadedMatch.id != existingTrack.id) {
                                ensurePlaylistMembership(localPlaylist.id, downloadedMatch.id, trackSnapshot.position)
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

                        // Link to playlist.
                        ensurePlaylistMembership(localPlaylist.id, trackId, trackSnapshot.position)

                        // Queue for download.
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

                // Update local playlist metadata.
                playlistDao.updateLastSynced(localPlaylist.id, System.currentTimeMillis())
                if (playlistSnapshot.snapshotId != null) {
                    playlistDao.updateSnapshotId(localPlaylist.id, playlistSnapshot.snapshotId)
                }
                playlistDao.updateTrackCount(
                    localPlaylist.id,
                    trackSnapshots.size,
                )

                // Refresh the playlist's cover art from the first 2 unique
                // track album arts, joined with '|'. Callers that want the
                // mosaic render both tiles side-by-side; single-image
                // callers take the portion before '|' (see
                // HomeScreen.primaryArtUrl). Spotify's own Daily Mix mosaic
                // URL is aggressively cached upstream and often doesn't
                // rotate between syncs — deriving the cover from the
                // current tracks guarantees a visible change every time
                // the tracklist rotates.
                // v0.4.1: mosaic collapsed to single-image so the mix is
                // recognizable at a glance. Still refreshed every sync —
                // the FIRST unique track art becomes the new cover, so
                // rotations remain visible, just cleaner.
                val derivedTiles = trackSnapshots
                    .mapNotNull { it.albumArtUrl }
                    .distinct()
                    .take(1)
                val coverToSet = derivedTiles.firstOrNull() ?: playlistSnapshot.artUrl
                val currentArt = playlistDao.findBySourceId(playlistSnapshot.sourcePlaylistId)?.artUrl
                if (coverToSet != null && coverToSet != currentArt) {
                    playlistDao.updateArtUrl(localPlaylist.id, coverToSet)
                }
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
     * Checks whether a remote track already exists in the local database using
     * three strategies in order:
     * 1. Exact match by Spotify URI.
     * 2. Exact match by YouTube video ID.
     * 3. Canonical title + artist match.
     */
    private suspend fun findExistingTrack(snapshot: RemoteTrackSnapshotEntity): TrackEntity? {
        // Strategy 1: Spotify URI lookup.
        snapshot.spotifyUri?.let { uri ->
            trackDao.findBySpotifyUri(uri)?.let { return it }
        }

        // Strategy 2: YouTube ID lookup.
        snapshot.youtubeId?.let { ytId ->
            trackDao.findByYoutubeId(ytId)?.let { return it }
        }

        // Strategy 3: Canonical identity match.
        val canonicalTitle = trackMatcher.canonicalTitle(snapshot.title)
        val canonicalArtist = trackMatcher.canonicalArtist(snapshot.artist)
        return trackDao.findByCanonicalIdentity(canonicalTitle, canonicalArtist)
    }

    /**
     * Ensures a cross-reference exists between a playlist and a track. For
     * existing rows we preserve the original `addedAt` so ACCUMULATE mode
     * can sort newest-added first — if we let the default REPLACE behavior
     * stamp `Instant.now()` every sync, every track would get the same
     * addedAt and the "newest on top" UX vanishes. For new rows we pick up
     * the default `Instant.now()` from the data class.
     */
    private suspend fun ensurePlaylistMembership(
        playlistId: Long,
        trackId: Long,
        position: Int,
    ) {
        val existingRef = playlistDao.getCrossRef(playlistId, trackId)
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
