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
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.core.model.SyncState
import com.stash.data.spotify.SpotifyApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * A newly-discovered playlist's initial [PlaylistEntity.syncEnabled]: always
 * opt-in. The first Sync Now is a discovery pass that downloads nothing unasked.
 *
 * DAILY_MIX used to auto-enable in Online mode "so they surface immediately with
 * no download". That was redundant and load-bearing only for harm:
 * [com.stash.core.data.db.dao.PlaylistDao.getAllVisible] already surfaces a
 * `sync_enabled = 0` playlist whose tracks are streamable when
 * `includeStreamable = true` (pinned by PlaylistDaoMixVisibilityTest), so mixes
 * still appear on Home in Online mode without it. The flag's only other effects
 * were making the mix download-eligible and making the orphan sweep spare its
 * tracks — and because mixes rotate, every new one pulled a fresh batch of
 * downloads the user never asked for (#368).
 *
 * Parameters are retained to document what was considered and to keep the
 * decision testable.
 */
@Suppress("UNUSED_PARAMETER")
internal fun defaultSyncEnabled(type: PlaylistType, online: Boolean): Boolean = false

/**
 * The type to re-write onto an existing playlist row, or null to leave it.
 *
 * `playlists.type` used to be write-once, set by whichever fetch pass saw the
 * `source_id` first. That made one combination permanent and wrong: the
 * Spotify home-feed mix pass inserts DAILY_MIX, and the library
 * walk reports the same id as a saved CUSTOM playlist — but nothing ever
 * updated the row, so it stayed a "mix" and disappeared from every CUSTOM
 * surface (the Sync tab's "n/n PLAYLISTS" count, the Library Playlists grid).
 * Issue #437: five Spotify playlists fetched, one shown.
 *
 * Deliberately ONE-WAY. Reconciling both directions would flap the type every
 * run for a Spotify-owned playlist the user has saved: with auto-mix discovery
 * on, the home feed claims the id and snapshots it DAILY_MIX; with it off, the
 * library walk claims it and snapshots it CUSTOM. A saved library playlist
 * wins, so the row settles — and with it the row's download eligibility
 * ([shouldEnqueueForDownload] excludes DAILY_MIX) and its Home-vs-Library
 * placement, which would otherwise change under the user run to run.
 *
 * Every other pair is left alone. Local-only types (STASH_MIX, STASH_LIKED,
 * DOWNLOADS_MIX) are owned by Stash and never by a snapshot; LIKED_SONGS is a
 * per-source singleton with a synthetic source id that no library walk should
 * ever re-type.
 *
 * Scoped to Spotify ids OUTSIDE Spotify's own namespace, because the type pair
 * alone does not identify the #437 case and the filters that used to stand in
 * for it are not load-bearing:
 *
 * - YouTube reaches the same pair with a real mix. A saved auto-mix tile is a
 *   `VLRD…` browseId, and `parseSinglePlaylistFromTwoRowRenderer` accepts any
 *   VL-prefixed id but `VLLM`/`VLSE` and strips the `VL` — yielding the very
 *   `RD…` id the home-mix pass snapshotted as DAILY_MIX, now arriving again as
 *   CUSTOM. `getPlaylistTracks` already documents that `getUserPlaylists()`
 *   returns saved radios. Nothing on the YouTube path filters this.
 * - On Spotify, `keepAsLibraryPlaylist` only withholds ids in `homeFeedMixIds`,
 *   and that set is filled solely inside the home-feed `Success` branch. One
 *   `Empty`, `Error`, thrown exception, or discovery-off run leaves it empty,
 *   after which every saved mix whose name isn't literally "Daily Mix N" —
 *   Discover Weekly, Release Radar, On Repeat, daylist, Blends, the yearly
 *   recaps — is snapshotted CUSTOM.
 *
 * A wrong re-type is worse than the bug this fixes, and the rule is one-way so
 * it never heals: the row leaves the Home mix rails, `mix_number` is gone,
 * DiffWorker's syncEnabled gate exempts DAILY_MIX only so the row is skipped
 * forever after, and `shouldEnqueueForDownload(CUSTOM, offline)` is true — so
 * one "Enable all" queues an entire rotating mix, the #368 regression the
 * surrounding code exists to prevent.
 *
 * [SPOTIFY_OWNED_ID_PREFIX] is the honest discriminator: Spotify generates
 * every mix inside it, and the #437 playlists — the user's own — are never in
 * it. So a Spotify-generated mix is protected whether or not the home-feed pass
 * ran, and YouTube cannot reach the reconcile at all.
 */
internal fun reconciledPlaylistType(
    existing: PlaylistType,
    snapshot: PlaylistType,
    source: MusicSource,
    sourceId: String,
): PlaylistType? =
    if (source == MusicSource.SPOTIFY &&
        !sourceId.startsWith(SpotifyApiClient.SPOTIFY_OWNED_ID_PREFIX) &&
        existing == PlaylistType.DAILY_MIX &&
        snapshot == PlaylistType.CUSTOM
    ) {
        snapshot
    } else {
        null
    }

/**
 * Whether a playlist's tracks should be enqueued for download during this sync.
 * Online/streaming mode never downloads (tracks stream on tap). In Offline mode
 * everything downloads EXCEPT algorithmic mixes (DAILY_MIX) — those are
 * surface-only (stream-on-tap), so an auto-enabled mix never pulls bytes even
 * after the user switches to Offline and re-syncs.
 */
// STASH_MIX is deliberately absent from this exclusion: a locally-generated mix
// never arrives as a remote playlist snapshot, so it cannot reach this guard, and
// ShouldEnqueueForDownloadTest pins that on purpose. Stash mixes are kept
// download-ineligible where they ARE reachable — the DownloadQueueDao predicates.
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
    private val syncUndoDao: com.stash.core.data.db.dao.SyncUndoDao,
    private val syncLog: com.stash.core.data.sync.SyncLog,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        const val KEY_NEW_TRACKS = "new_tracks"
        const val KEY_PLAYLISTS_CHECKED = "playlists_checked"
        private const val TAG = "DiffWorker"
        /** How many new songs to name before collapsing to "+N more". */
        private const val NEW_TRACKS_NAMED = 3
        private const val NEVER_MATCH_SENTINEL = "\u0000__stash_never_match__"
    }

    /**
     * Mutable identity state for one playlist batch. Newly discovered tracks
     * are registered before the bulk insert so duplicate snapshots reuse the
     * same pending row instead of staging another UNIQUE-key conflict.
     */
    private class BatchTrack(
        var entity: TrackEntity,
        val isNew: Boolean,
        var persistedId: Long? = entity.id.takeUnless { isNew },
    )

    private data class CanonicalKey(
        val title: String,
        val artist: String,
    )

    override suspend fun doWork(): Result {
        val syncId = inputData.getLong(PlaylistFetchWorker.KEY_SYNC_ID, -1L)
        // Defaults to false (fail-closed) — if the key is somehow missing, treat
        // the inventory as unreliable and skip deactivation rather than risk
        // hiding real playlists.
        val youtubeInventoryComplete = inputData.getBoolean(
            PlaylistFetchWorker.KEY_YOUTUBE_INVENTORY_COMPLETE, false,
        )
        if (syncId == -1L) {
            syncStateManager.onError("DiffWorker: missing sync ID")
            return Result.failure()
        }

        try {
            syncStateManager.onDiffing()
            syncHistoryDao.updateStatus(syncId, SyncState.DIFFING)

            // Restore point for "Undo last sync", captured BEFORE anything
            // destructive: everything above this line only reads or writes
            // snapshot tables, while below it REFRESH clears playlist membership
            // and stale playlists get deactivated. Bulk INSERT…SELECT, so this is
            // one statement per table rather than a per-row copy.
            //
            // Never fatal: a sync that works must not be blocked because the
            // safety net couldn't be set up. Worst case the user has no undo for
            // this run, which is exactly where they were before the feature.
            runCatching {
                syncUndoDao.capture(syncId, System.currentTimeMillis())
            }.onFailure { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Log.w(TAG, "Undo restore point capture failed for sync $syncId", e)
                syncLog.warn("Couldn't save a restore point — undo unavailable for this sync")
            }

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
            syncStateManager.onDiffing(playlistsDiffed = 0, totalPlaylists = playlistSnapshots.size)
            var newTrackCount = 0
            var playlistsDiffed = 0

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

                // Skip playlists the user has disabled in Sync Preferences —
                // EXCEPT algorithmic mixes, which are surface-only.
                //
                // A mix can never enqueue a download regardless of this flag
                // (shouldEnqueueForDownload excludes DAILY_MIX outright), and the
                // fetch worker has ALREADY pulled its tracks over the network this
                // run. Skipping therefore bought nothing and threw that work away,
                // leaving the mix on screen with zero tracks — the "I have 130
                // mixes but Home shows nothing" report. Linking them is pure
                // local bookkeeping: no extra request, no unasked downloads, and
                // Online mode can stream them on tap.
                if (!localPlaylist.syncEnabled && localPlaylist.type != PlaylistType.DAILY_MIX) {
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
                playlistsDiffed++
                syncStateManager.onDiffing(playlistsDiffed, playlistSnapshots.size)
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
            // Close with the answer to "what did this sync actually get me?".
            // Saying "no new music" outright matters as much as listing finds: a
            // sync that changed nothing should look different from one that
            // failed, and previously both just stopped.
            if (newTrackCount > 0) {
                syncLog.success("$newTrackCount new song${if (newTrackCount == 1) "" else "s"} added")
            } else {
                syncLog.info("No new music this time — everything already in your library")
            }

            // Gated by the SAME predicate Spotify uses — REFRESH *and* a complete
            // inventory. This previously checked only the inventory half, so an
            // ACCUMULATE sync still hid YouTube playlists that the run didn't
            // return, including user-created ones: the mode that promises "never
            // remove anything" was quietly removing things. Spotify honoured the
            // mode, YouTube didn't, which is why a sync could gain Spotify
            // playlists and lose YouTube ones in the same pass.
            if (youtubeSourceIds.isNotEmpty() &&
                shouldDeactivateMissingPlaylists(youtubeSyncMode, youtubeInventoryComplete)
            ) {
                val hidden = playlistDao.deactivateMissingForSource(
                    source = MusicSource.YOUTUBE,
                    currentSourceIds = youtubeSourceIds,
                )
                if (hidden > 0) {
                    Log.i(TAG, "Deactivated $hidden stale YouTube playlist(s)")
                    syncLog.warn("Hid $hidden YouTube playlist(s) not returned this run (Refresh mode)")
                }
            } else if (youtubeSourceIds.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Skipping stale-YouTube-playlist deactivation " +
                        "(mode=$youtubeSyncMode inventoryComplete=$youtubeInventoryComplete)",
                )
                syncLog.info(
                    if (youtubeSyncMode != com.stash.core.model.SyncMode.REFRESH) {
                        "Kept all YouTube playlists (Accumulate never removes)"
                    } else {
                        "Kept all YouTube playlists — this run's fetch was incomplete"
                    },
                )
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
            // A cancelled run (user edits sync settings mid-diff, constraints
            // drop) is NOT a failure — rethrow so it isn't logged as "Diff
            // failed" and written FAILED, which pollutes the #337 triage.
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            // A playlist the home feed once called a mix, and the library walk
            // now reports as one the user saved, is re-typed here — see
            // [reconciledPlaylistType] for why this is one-way only. Resolved
            // BEFORE the art check so a row leaving DAILY_MIX stops rotating
            // its cover in the same pass.
            val reconciledType = reconciledPlaylistType(
                existing = existing.type,
                snapshot = snapshot.playlistType,
                source = snapshot.source,
                sourceId = snapshot.sourcePlaylistId,
            )
            if (reconciledType != null) {
                playlistDao.updateType(existing.id, reconciledType, mixNumber = null)
                Log.i(
                    TAG,
                    "Re-typed '${existing.name}' ${existing.type} -> $reconciledType " +
                        "(source_id=${snapshot.sourcePlaylistId})",
                )
                syncLog.info("${existing.name} is your playlist, not a mix — moved to Playlists")
            }
            val effectiveType = reconciledType ?: existing.type
            // Art refresh: ONLY for DAILY_MIX. Daily Mixes (and Spotify's
            // weekly mixes — Discover Weekly, Release Radar, etc., which
            // share the DAILY_MIX type) rotate, so their cover should
            // follow the tracks. Other playlist types never rotate here;
            // LIKED_SONGS gets only a missing-art repair during metadata
            // finalization below.
            val rotatesArt = effectiveType == PlaylistType.DAILY_MIX
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
                type = effectiveType,
                mixNumber = if (reconciledType != null) null else existing.mixNumber,
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
        // A playlist or mix that wasn't here before is news — arguably the most
        // interesting thing a sync can report, and previously invisible.
        val kind = if (snapshot.playlistType == PlaylistType.DAILY_MIX) "mix" else "playlist"
        syncLog.success("New $kind: ${snapshot.playlistName}")
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
            // #343: NEVER mirror-clear against a fetch that admits (or looks
            // like) incompleteness — one flaky API call was emptying whole
            // playlists, and the orphan cleaner then deleted the files.
            //  - partial: the fetch worker marked this snapshot incomplete
            //    (track fetch threw/errored, or pagination hit its cap — the
            //    long-standing YT truncation loss is this same hole).
            //  - suspiciousEmpty: zero tracks fetched while the playlist
            //    LISTING claimed some exist — an unmarked failure shape.
            // In both cases we fall through and merge additions only
            // (accumulate semantics); a later clean fetch re-mirrors fully.
            if (snapshotUnreliable(playlistSnapshot, trackSnapshots)) {
                Log.w(
                    TAG,
                    "REFRESH: keeping local tracks for '${playlistSnapshot.playlistName}' — " +
                        if (playlistSnapshot.partial) {
                            "fetch marked partial (${trackSnapshots.size} snapshot tracks)"
                        } else {
                            "snapshot empty but listing claims ${playlistSnapshot.trackCount} tracks"
                        },
                )
            } else {
                playlistDao.clearSyncedPlaylistTracks(localPlaylist.id)
            }
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
            CanonicalKey(
                title = trackMatcher.canonicalTitle(it.title),
                artist = trackMatcher.canonicalArtist(it.artist),
            )
        }
        val spotifyUris = allowedSnapshots.mapNotNull { it.spotifyUri?.takeIf(String::isNotBlank) }.distinct()
            .ifEmpty { listOf(NEVER_MATCH_SENTINEL) }
        val youtubeIds = allowedSnapshots.mapNotNull { it.youtubeId?.takeIf(String::isNotBlank) }.distinct()
            .ifEmpty { listOf(NEVER_MATCH_SENTINEL) }
        val canonicalKeys = canonicalOf.values.map { (t, a) -> "$t|$a" }.distinct()

        val candidates = trackDao.findExistingForBatch(spotifyUris, youtubeIds, canonicalKeys)
        val candidateTracks = candidates.associate { candidate ->
            candidate.id to BatchTrack(candidate, isNew = false)
        }
        val bySpotifyUri = mutableMapOf<String, BatchTrack>()
        val byYoutubeId = mutableMapOf<String, BatchTrack>()
        // A canonical key can legitimately map to MULTIPLE rows — e.g. two
        // distinct YouTube uploads of "the same song" that arrive with
        // different videoIds. A single-value map here would collapse them
        // into one row, silently discarding the second identity.
        val byCanonical = mutableMapOf<CanonicalKey, MutableList<BatchTrack>>()

        fun registerBatchTrack(track: BatchTrack) {
            track.entity.spotifyUri?.takeIf(String::isNotBlank)?.let { bySpotifyUri[it] = track }
            track.entity.youtubeId?.takeIf(String::isNotBlank)?.let { byYoutubeId[it] = track }
            val canonicalKey = CanonicalKey(
                title = track.entity.canonicalTitle,
                artist = track.entity.canonicalArtist,
            )
            val canonicalMatches = byCanonical.getOrPut(canonicalKey) { mutableListOf() }
            if (canonicalMatches.none { it === track }) {
                canonicalMatches.add(track)
            }
        }

        candidateTracks.values.forEach(::registerBatchTrack)

        // A canonical-title/artist match is only safe to reuse when neither
        // side's strong identifiers actively disagree. Without this, two
        // tracks that share a title/artist but carry different spotifyUri
        // or youtubeId values would incorrectly merge into a single row —
        // e.g. two different YouTube uploads of the same song.
        fun strongIdsCompatible(
            track: BatchTrack,
            snapshot: RemoteTrackSnapshotEntity,
        ): Boolean {
            fun compatible(stored: String?, incoming: String?): Boolean =
                stored.isNullOrBlank() || incoming.isNullOrBlank() || stored == incoming

            return compatible(track.entity.spotifyUri, snapshot.spotifyUri) &&
                compatible(track.entity.youtubeId, snapshot.youtubeId)
        }

        fun matchBatchTrack(snapshot: RemoteTrackSnapshotEntity): BatchTrack? {
            snapshot.spotifyUri?.takeIf(String::isNotBlank)?.let { uri ->
                bySpotifyUri[uri]?.let { return it }
            }
            snapshot.youtubeId?.takeIf(String::isNotBlank)?.let { yid ->
                byYoutubeId[yid]?.let { return it }
            }
            return byCanonical[canonicalOf.getValue(snapshot)]?.firstOrNull {
                strongIdsCompatible(it, snapshot)
            }
        }

        suspend fun enrichExistingBatchTrack(
            batchTrack: BatchTrack,
            snapshot: RemoteTrackSnapshotEntity,
        ) {
            val existingTrack = batchTrack.entity
            val snapshotYtId = snapshot.youtubeId?.takeIf(String::isNotBlank)
            if (snapshotYtId != null && existingTrack.youtubeId.isNullOrBlank()) {
                val youtubeOwner = byYoutubeId[snapshotYtId]
                if (youtubeOwner == null || youtubeOwner === batchTrack) {
                    val applied = trackDao.updateYoutubeIdIfUnclaimed(existingTrack.id, snapshotYtId)
                    if (applied == 1) {
                        batchTrack.entity = batchTrack.entity.copy(youtubeId = snapshotYtId)
                        byYoutubeId[snapshotYtId] = batchTrack
                        val ytUrl = "https://music.youtube.com/watch?v=$snapshotYtId"
                        downloadQueueDao.fillMissingYoutubeUrlForTrack(existingTrack.id, ytUrl)
                    }
                }
            }

            val snapshotArt = snapshot.albumArtUrl
            if (!snapshotArt.isNullOrBlank() && snapshotArt != batchTrack.entity.albumArtUrl) {
                trackDao.updateAlbumArtUrl(existingTrack.id, snapshotArt)
                batchTrack.entity = batchTrack.entity.copy(albumArtUrl = snapshotArt)
            }
        }

        // Preload every current cross-ref for this playlist ONCE instead of
        // one getCrossRef() SELECT per track.
        val existingCrossRefs = playlistDao.getCrossRefsForPlaylist(localPlaylist.id)
            .associateBy { it.trackId }

        val newTracks = mutableListOf<BatchTrack>()
        val newOccurrences = mutableListOf<Pair<BatchTrack, RemoteTrackSnapshotEntity>>()
        val existingPairs = mutableListOf<Pair<BatchTrack, RemoteTrackSnapshotEntity>>()

        for (snapshot in allowedSnapshots) {
            val batchTrack = matchBatchTrack(snapshot) ?: run {
                val (ct, ca) = canonicalOf.getValue(snapshot)
                BatchTrack(
                    entity = TrackEntity(
                        title = snapshot.title,
                        artist = snapshot.artist,
                        album = snapshot.album ?: "",
                        durationMs = snapshot.durationMs,
                        source = playlistSnapshot.source,
                        spotifyUri = snapshot.spotifyUri?.takeIf(String::isNotBlank),
                        youtubeId = snapshot.youtubeId?.takeIf(String::isNotBlank),
                        albumArtUrl = snapshot.albumArtUrl,
                        canonicalTitle = ct,
                        canonicalArtist = ca,
                        isDownloaded = false,
                        isrc = snapshot.isrc,
                        explicit = snapshot.explicit,
                    ),
                    isNew = true,
                ).also {
                    newTracks.add(it)
                    registerBatchTrack(it)
                }
            }

            if (batchTrack.isNew) {
                val snapshotYtId = snapshot.youtubeId?.takeIf(String::isNotBlank)
                val youtubeOwner = snapshotYtId?.let(byYoutubeId::get)
                if (snapshotYtId != null &&
                    batchTrack.entity.youtubeId.isNullOrBlank() &&
                    (youtubeOwner == null || youtubeOwner === batchTrack)
                ) {
                    batchTrack.entity = batchTrack.entity.copy(youtubeId = snapshotYtId)
                    byYoutubeId[snapshotYtId] = batchTrack
                }

                val snapshotArt = snapshot.albumArtUrl
                if (!snapshotArt.isNullOrBlank() && snapshotArt != batchTrack.entity.albumArtUrl) {
                    batchTrack.entity = batchTrack.entity.copy(albumArtUrl = snapshotArt)
                }
                newOccurrences.add(batchTrack to snapshot)
            } else {
                enrichExistingBatchTrack(batchTrack, snapshot)
                existingPairs.add(batchTrack to snapshot)
            }
        }

        // ── Bulk insert new tracks ───────────────────────────────────────
        // Room's insertAll returns generated row ids in the same order as
        // the input list.
        val newTrackIds = if (newTracks.isNotEmpty()) {
            trackDao.insertAll(newTracks.map { it.entity })
        } else {
            emptyList()
        }
        check(newTrackIds.size == newTracks.size) {
            "Expected ${newTracks.size} generated track ids, got ${newTrackIds.size}"
        }
        newTracks.zip(newTrackIds).forEach { (track, trackId) ->
            track.persistedId = trackId
        }

        val crossRefsToInsert = linkedMapOf<Long, PlaylistTrackCrossRef>()
        val downloadEntries = mutableListOf<DownloadQueueEntity>()

        for ((batchTrack, snapshot) in newOccurrences) {
            val trackId = checkNotNull(batchTrack.persistedId)
            addCrossRefIfNotSoftDeleted(localPlaylist.id, trackId, snapshot.position, existingCrossRefs, crossRefsToInsert)
        }

        // Mixes are surface-only: they stream on tap and must never pull bytes.
        // shouldEnqueueForDownload has encoded that since it was written, and was
        // unit-tested — but this site tested raw `!streamingMode`, so the guard
        // was never consulted and every track of every rotating mix was queued in
        // Offline mode (#368: "it downloads 6000+ from users playlists and
        // Spotify mixes that I don't need").
        if (shouldEnqueueForDownload(localPlaylist.type, streamingMode)) {
            for (batchTrack in newTracks) {
                val trackId = checkNotNull(batchTrack.persistedId)
                downloadEntries.add(
                    DownloadQueueEntity(
                        trackId = trackId,
                        syncId = syncId,
                        searchQuery = "${batchTrack.entity.artist} - ${batchTrack.entity.title}",
                        youtubeUrl = batchTrack.entity.youtubeId?.let {
                            "https://music.youtube.com/watch?v=$it"
                        },
                    )
                )
            }
        }
        val newTrackCount = newTracks.size
        // Name what actually arrived. A count ("3 new tracks") still leaves the
        // user hunting for which three — the whole complaint about sync being a
        // dead end. These are tracks NEW TO THE LIBRARY, so it is genuinely music
        // they have not had before, not a reshuffle.
        if (newTracks.isNotEmpty()) {
            val named = newTracks.take(NEW_TRACKS_NAMED)
                .joinToString(", ") { "${it.entity.artist} - ${it.entity.title}" }
            val more = (newTracks.size - NEW_TRACKS_NAMED).coerceAtLeast(0)
            syncLog.success(
                "${playlistSnapshot.playlistName}: $named" + if (more > 0) ", +$more more" else ""
            )
        }

        // ── Existing-track path: membership + enrichment ─────────────────
        // Enrichment writes (youtubeId backfill, art refresh, auto-
        // reconciliation) stay per-row — they're targeted single-column
        // UPDATEs, not the insert flood that caused the slowdown.
        for ((batchTrack, snapshot) in existingPairs) {
            val existingTrack = batchTrack.entity
            addCrossRefIfNotSoftDeleted(localPlaylist.id, existingTrack.id, snapshot.position, existingCrossRefs, crossRefsToInsert)

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
            playlistDao.insertAllCrossRefs(crossRefsToInsert.values.toList())
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
        out: MutableMap<Long, PlaylistTrackCrossRef>,
    ) {
        val storedPrior = existingByTrackId[trackId]
        if (storedPrior != null && storedPrior.removedAt != null) {
            Log.d(TAG, "Skipping re-link for soft-deleted track $trackId in playlist $playlistId (user removed it)")
            return
        }
        val batchPrior = out[trackId]
        out[trackId] = PlaylistTrackCrossRef(
            playlistId = playlistId,
            trackId = trackId,
            position = position,
            addedAt = batchPrior?.addedAt ?: storedPrior?.addedAt ?: java.time.Instant.now(),
        )
    }

    /**
     * A snapshot that must NOT be treated as the full remote truth (#343):
     * either the fetch worker marked it partial (track fetch errored, or
     * pagination hit a cap), or it fetched zero tracks while the playlist
     * LISTING claims some exist — an unmarked failure shape. Shared by the
     * REFRESH clear guard and [finalizePlaylistMetadata] so the "don't
     * mirror" and "don't record mirrored state" decisions can't drift.
     */
    private fun snapshotUnreliable(
        snapshot: RemotePlaylistSnapshotEntity,
        trackSnapshots: List<RemoteTrackSnapshotEntity>,
    ): Boolean = snapshot.partial || (trackSnapshots.isEmpty() && snapshot.trackCount > 0)

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
        if (snapshotUnreliable(playlistSnapshot, trackSnapshots)) {
            // #343: an unreliable fetch was NOT mirrored (see processPlaylist).
            // Recording its snapshot_id would make the next sync skip this
            // playlist as "unchanged" and the missing tracks would never
            // re-sync; stamping trackSnapshots.size would show a lying track
            // count over retained content. Leave both as they were.
            return
        }
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
        } else if (
            localPlaylist.type == PlaylistType.LIKED_SONGS &&
            localPlaylist.artUrl.isNullOrBlank() &&
            !playlistSnapshot.artUrl.isNullOrBlank()
        ) {
            playlistDao.updateArtUrl(localPlaylist.id, playlistSnapshot.artUrl)
        }
    }   
}
