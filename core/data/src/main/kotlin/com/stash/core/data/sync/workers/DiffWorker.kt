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
        private const val NEVER_MATCH_SENTINEL = "\u0000__stash_never_match__"
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
        if (syncMode == SyncMode.REFRESH) {
            playlistDao.clearSyncedPlaylistTracks(localPlaylist.id)
        }

        if (trackSnapshots.isEmpty()) {
            finalizePlaylistMetadata(playlistSnapshot, localPlaylist, trackSnapshots)
            return 0
        }

        // Blocklist guard up front, same predicate as before, just applied
        // to the whole batch instead of inline per-iteration.
        val allowedSnapshots = trackSnapshots.filterNot { snapshot ->
            val blocked = blocklistGuard.isBlocked(
                artist = snapshot.artist,
                title = snapshot.title,
                spotifyUri = snapshot.spotifyUri,
                youtubeId = snapshot.youtubeId,
            )
            if (blocked) {
                Log.d(TAG, "Skipping blocked snapshot: ${snapshot.artist} - ${snapshot.title}")
            }
            blocked
        }

        if (allowedSnapshots.isEmpty()) {
            finalizePlaylistMetadata(playlistSnapshot, localPlaylist, trackSnapshots)
            return 0
        }

        // ── Bulk identity resolution ─────────────────────────────────────
        // Replaces the old per-snapshot findExistingTrack() N+1 (one SELECT
        // per remote track — 9,000 individual round-trips on a large sync)
        // with a single batched lookup, then matches candidates in memory
        // using the same spotifyUri -> youtubeId -> canonical priority.
        val canonicalOf = allowedSnapshots.associateWith {
            trackMatcher.canonicalTitle(it.title) to trackMatcher.canonicalArtist(it.artist)
        }
        val spotifyUris = allowedSnapshots.mapNotNull { it.spotifyUri }.distinct()
            .ifEmpty { listOf(NEVER_MATCH_SENTINEL) }
        val youtubeIds = allowedSnapshots.mapNotNull { it.youtubeId }.distinct()
            .ifEmpty { listOf(NEVER_MATCH_SENTINEL) }
        val canonicalKeys = canonicalOf.values.map { (t, a) -> "$t|$a" }.distinct()

        val candidates = trackDao.findExistingForBatch(spotifyUris, youtubeIds, canonicalKeys)
        val bySpotifyUri = candidates.filter { it.spotifyUri != null }.associateBy { it.spotifyUri }
        val byYoutubeId = candidates.filter { it.youtubeId != null }.associateBy { it.youtubeId }
        val byCanonical = candidates.associateBy { "${it.canonicalTitle}|${it.canonicalArtist}" }

        fun matchExisting(snapshot: RemoteTrackSnapshotEntity): TrackEntity? {
            snapshot.spotifyUri?.let { uri -> bySpotifyUri[uri]?.let { return it } }
            snapshot.youtubeId?.let { yid -> byYoutubeId[yid]?.let { return it } }
            val (ct, ca) = canonicalOf.getValue(snapshot)
            return byCanonical["$ct|$ca"]
        }

        // Preload every current cross-ref for this playlist ONCE instead of
        // one getCrossRef() SELECT per track.
        val existingCrossRefs = playlistDao.getCrossRefsForPlaylist(localPlaylist.id)
            .associateBy { it.trackId }

        val newSnapshots = mutableListOf<RemoteTrackSnapshotEntity>()
        val newEntities = mutableListOf<TrackEntity>()
        val existingPairs = mutableListOf<Pair<TrackEntity, RemoteTrackSnapshotEntity>>()

        for (snapshot in allowedSnapshots) {
            val existing = matchExisting(snapshot)
            if (existing != null) {
                existingPairs.add(existing to snapshot)
            } else {
                val (ct, ca) = canonicalOf.getValue(snapshot)
                newEntities.add(
                    TrackEntity(
                        title = snapshot.title,
                        artist = snapshot.artist,
                        album = snapshot.album ?: "",
                        durationMs = snapshot.durationMs,
                        source = playlistSnapshot.source,
                        spotifyUri = snapshot.spotifyUri,
                        youtubeId = snapshot.youtubeId,
                        albumArtUrl = snapshot.albumArtUrl,
                        canonicalTitle = ct,
                        canonicalArtist = ca,
                        isDownloaded = false,
                        isrc = snapshot.isrc,
                        explicit = snapshot.explicit,
                    )
                )
                newSnapshots.add(snapshot)
            }
        }

        // ── Bulk insert new tracks ───────────────────────────────────────
        // Room's insertAll returns generated row ids in the same order as
        // the input list.
        val newTrackIds = if (newEntities.isNotEmpty()) trackDao.insertAll(newEntities) else emptyList()

        val crossRefsToInsert = mutableListOf<PlaylistTrackCrossRef>()
        val downloadEntries = mutableListOf<DownloadQueueEntity>()
        var newTrackCount = 0

        newTrackIds.forEachIndexed { index, trackId ->
            val snapshot = newSnapshots[index]
            addCrossRefIfNotSoftDeleted(localPlaylist.id, trackId, snapshot.position, existingCrossRefs, crossRefsToInsert)
            if (!streamingMode) {
                downloadEntries.add(
                    DownloadQueueEntity(
                        trackId = trackId,
                        syncId = syncId,
                        searchQuery = "${snapshot.artist} - ${snapshot.title}",
                        youtubeUrl = snapshot.youtubeId?.let { "https://music.youtube.com/watch?v=$it" },
                    )
                )
            }
            newTrackCount++
        }

        // ── Existing-track path: membership + enrichment ─────────────────
        // Enrichment writes (youtubeId backfill, art refresh, auto-
        // reconciliation) stay per-row — they're targeted single-column
        // UPDATEs, not the insert flood that caused the slowdown.
        for ((existingTrack, snapshot) in existingPairs) {
            addCrossRefIfNotSoftDeleted(localPlaylist.id, existingTrack.id, snapshot.position, existingCrossRefs, crossRefsToInsert)

            val snapshotYtId = snapshot.youtubeId
            if (!snapshotYtId.isNullOrBlank() && existingTrack.youtubeId.isNullOrBlank()) {
                // Guarded: a DIFFERENT track can already own this youtube_id
                // (UNIQUE column) — e.g. one snapshot matching separate rows
                // by spotifyUri and youtubeId. The unguarded UPDATE threw
                // SQLiteConstraintException and failed the entire diff.
                val applied = trackDao.updateYoutubeIdIfUnclaimed(existingTrack.id, snapshotYtId)
                if (applied == 1) {
                    val ytUrl = "https://music.youtube.com/watch?v=$snapshotYtId"
                    downloadQueueDao.fillMissingYoutubeUrlForTrack(existingTrack.id, ytUrl)
                }
            }

            val snapshotArt = snapshot.albumArtUrl
            if (!snapshotArt.isNullOrBlank() && snapshotArt != existingTrack.albumArtUrl) {
                trackDao.updateAlbumArtUrl(existingTrack.id, snapshotArt)
            }

            if (!existingTrack.isDownloaded && !existingTrack.matchDismissed) {
                val downloadedMatch = trackDao.findDownloadedByCanonical(
                    canonicalTitle = existingTrack.canonicalTitle.lowercase(),
                    canonicalArtist = existingTrack.canonicalArtist.lowercase(),
                )
                if (downloadedMatch != null && downloadedMatch.id != existingTrack.id) {
                    addCrossRefIfNotSoftDeleted(localPlaylist.id, downloadedMatch.id, snapshot.position, existingCrossRefs, crossRefsToInsert)
                    val failedEntry = downloadQueueDao.getFailedByTrackId(existingTrack.id)
                    if (failedEntry != null) {
                        downloadQueueDao.updateStatus(id = failedEntry.id, status = DownloadStatus.COMPLETED)
                    }
                }
            }
        }

        // ── Flush batched writes ─────────────────────────────────────────
        if (crossRefsToInsert.isNotEmpty()) {
            playlistDao.insertAllCrossRefs(crossRefsToInsert)
        }
        if (downloadEntries.isNotEmpty()) {
            downloadQueueDao.insertAll(downloadEntries)
        }
        // Single summary line per playlist instead of one Log.i per track —
        // the prior per-row logging was flooding logcat (LOG_FLOWCTRL
        // dropping rows) on large syncs.
        if (newTrackCount > 0) {
            Log.i(
                TAG,
                "Playlist '${playlistSnapshot.playlistName}' (id=${localPlaylist.id}): " +
                    "$newTrackCount new track(s), streamingMode=$streamingMode, " +
                    "downloadsQueued=${downloadEntries.size}",
            )
        }

        finalizePlaylistMetadata(playlistSnapshot, localPlaylist, trackSnapshots)
        return newTrackCount
    }

    /**
     * Adds a cross-ref to [out] unless a soft-deleted row already exists
     * for (playlistId, trackId) — mirrors the removedAt guard that used to
     * live inline in ensurePlaylistMembership. [existingByTrackId] is the
     * whole-playlist cross-ref map preloaded once per [processPlaylist] call.
     */
    private fun addCrossRefIfNotSoftDeleted(
        playlistId: Long,
        trackId: Long,
        position: Int,
        existingByTrackId: Map<Long, PlaylistTrackCrossRef>,
        out: MutableList<PlaylistTrackCrossRef>,
    ) {
        val prior = existingByTrackId[trackId]
        if (prior != null && prior.removedAt != null) {
            Log.d(TAG, "Skipping re-link for soft-deleted track $trackId in playlist $playlistId (user removed it)")
            return
        }
        out.add(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = position,
                addedAt = prior?.addedAt ?: java.time.Instant.now(),
            )
        )
    }

    /**
     * Playlist metadata bookkeeping shared by every processPlaylist exit
     * path (including the early-return-on-empty branches). Unchanged from
     * the original inline tail of processPlaylist.
     */
    private suspend fun finalizePlaylistMetadata(
        playlistSnapshot: RemotePlaylistSnapshotEntity,
        localPlaylist: PlaylistEntity,
        trackSnapshots: List<RemoteTrackSnapshotEntity>,
    ) {
        playlistDao.updateLastSynced(localPlaylist.id, System.currentTimeMillis())
        if (playlistSnapshot.snapshotId != null) {
            playlistDao.updateSnapshotId(localPlaylist.id, playlistSnapshot.snapshotId)
        }
        playlistDao.updateTrackCount(localPlaylist.id, trackSnapshots.size)

        if (localPlaylist.type == PlaylistType.DAILY_MIX) {
            val coverToSet = trackSnapshots
                .mapNotNull { it.albumArtUrl }
                .firstOrNull()
                ?: playlistSnapshot.artUrl
            if (coverToSet != null && coverToSet != localPlaylist.artUrl) {
                playlistDao.updateArtUrl(localPlaylist.id, coverToSet)
            }
        }
    }   
}
