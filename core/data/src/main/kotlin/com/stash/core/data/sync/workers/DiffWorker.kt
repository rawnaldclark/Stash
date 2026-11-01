package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import androidx.work.workDataOf
import com.stash.core.common.TrackCanonical
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
import com.stash.core.data.library.SameSongRule
import com.stash.core.data.library.libraryCopiesOf
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.core.data.sync.MixStacking
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
 * Whether a playlist sync finds for the first time goes straight onto Home's
 * Your playlists rail: after "Stream everything" on the first-sync sheet
 * ([pinNewPlaylists]), in Stream only, and only the user's own playlists. Mixes
 * have their own Home rails and Liked Songs its own card. In Download mode a
 * pinned playlist would sit on Home with nothing on the phone, so it waits for
 * its Save switch like before.
 */
internal fun pinOnDiscovery(type: PlaylistType, streamingMode: Boolean, pinNewPlaylists: Boolean): Boolean =
    pinNewPlaylists && streamingMode && type == PlaylistType.CUSTOM

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
 * (a mix downloads only once its switch is on) and its Home-vs-Library
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
 * the playlist's own switch decides — mixes included. Until 2026-09-16 this
 * also refused every DAILY_MIX by type (#368: auto-enabled mixes pulled
 * thousands of tracks nobody asked for); the protection is now that discovered
 * mixes start switched off ([defaultSyncEnabled]), so an un-switched mix links
 * for Home and pulls no bytes, and a switched-on one downloads like anything
 * else. Stash Mixes never reach this gate (a locally generated mix is not a
 * remote snapshot); the DownloadQueueDao predicates keep them ineligible.
 */
internal fun shouldEnqueueForDownload(streamingMode: Boolean, syncEnabled: Boolean): Boolean =
    !streamingMode && syncEnabled

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
    private val canonicalRecompute: com.stash.core.data.library.CanonicalRecomputeRunner,
    private val soundCloudAccounts: com.stash.core.auth.soundcloud.SoundCloudAccountManager? = null,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        const val KEY_NEW_TRACKS = "new_tracks"
        const val KEY_PLAYLISTS_CHECKED = "playlists_checked"
        /**
         * Set by SyncScheduler on a "Sync now" chain started online: the kept-playlist
         * sweep then uses any network. A "Sync now" queued offline leaves it unset, so
         * when it finally runs its downloads follow the Sync tab's Wi-Fi rule.
         */
        const val KEY_MANUAL_SYNC = "manual_sync"
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

            // Before anything is looked up by its words: rows saved before
            // schema 55 hold the old strict words, which this diff can't find.
            // A sync that ran ahead of the one-time re-key filed a permanent
            // duplicate of each, so it runs here first when it hasn't finished.
            reKeyOldCanonicalWords()

            // Restore point for "Undo last sync", captured BEFORE anything
            // destructive: everything above this line only reads or writes
            // snapshot tables, while below it REFRESH clears playlist membership
            // and stale playlists get deactivated. Bulk INSERT…SELECT, so this is
            // one statement per table rather than a per-row copy. Kept as is when
            // the fetch step already took it, before hiding Spotify playlists the
            // account no longer has. Never fatal (see captureUndoPoint).
            captureUndoPoint(syncUndoDao, syncLog, syncId, TAG)

            // Read each source's sync mode once at the start of the diff
            // pass. Per-source (not global) as of v0.5 — the user picks
            // REFRESH/ACCUMULATE independently for Spotify and YouTube in
            // the Sync Preferences cards.
            // Spotify: the mode the fetch decided its skips by, when it handed one on,
            // so one sync runs in one mode even if the setting changes mid-sync.
            val spotifySyncMode = inputData.getString(PlaylistFetchWorker.KEY_SPOTIFY_SYNC_MODE)
                ?.let { name -> SyncMode.entries.firstOrNull { it.name == name } }
                ?: syncPreferencesManager.spotifySyncMode.first()
            val youtubeSyncMode = syncPreferencesManager.youtubeSyncMode.first()


            // Read the streaming-mode toggle ONCE up front. When on, new
            // track rows still land in the `tracks` table (the metadata is
            // needed regardless — Home playlist surfaces + playlist detail
            // rely on it) but skip the `download_queue` enqueue. Tracks
            // become available via streaming-tap through KennyySource.
            val streamingMode = streamingPreference.current()
            val pinNewPlaylists = syncPreferencesManager.pinNewPlaylists.first()

            // Stacking became a switch per mix (2026-10-29). Once, before the first diff under
            // it: an account that was accumulating keeps EVERY mix it has stacking, not only the
            // ones that offer the switch to new mixes (Release Radar, My Supermix, a Daily Mix
            // under another locale's name all collected too), so an upgrade never shrinks a
            // collected mix back to this week's songs (review on #737). They keep the switch
            // ([MixStacking.showsSwitch]) to turn it off.
            if (!syncPreferencesManager.mixStackSeeded.first()) {
                var seeded = 0
                for (mix in playlistDao.getMixPlaylists()) {
                    val accountMode = if (mix.source == MusicSource.YOUTUBE) youtubeSyncMode else spotifySyncMode
                    if (accountMode == SyncMode.ACCUMULATE) {
                        playlistDao.setStackSongs(mix.id, true)
                        seeded++
                    }
                }
                syncPreferencesManager.markMixStackSeeded()
                if (seeded > 0) Log.i(TAG, "Stack switched on for $seeded mix(es) that were accumulating")
            }

            val playlistSnapshots = remoteSnapshotDao.getPlaylistSnapshotsBySyncId(syncId)
            val soundCloudSyncMode = if (playlistSnapshots.any { it.source == MusicSource.SOUNDCLOUD })
                syncPreferencesManager.soundCloudSyncMode.first() else SyncMode.ACCUMULATE
            syncStateManager.onDiffing(playlistsDiffed = 0, totalPlaylists = playlistSnapshots.size)
            var newTrackCount = 0
            var playlistsDiffed = 0
            // For the one summary line below: the per-playlist skip lines are
            // debug-level and scattered, and "why is my playlist empty" starts here.
            var skippedSwitchedOff = 0
            var skippedUnchanged = 0

            for (playlistSnapshot in playlistSnapshots) {
                val applySnapshot: suspend () -> Unit = applySnapshot@ {
                // Find or create the local playlist (writes, but outside
                // the per-playlist transaction — it owns its own atomicity
                // and needs its id to drive the block below).
                val localPlaylist = findOrCreatePlaylist(playlistSnapshot, streamingMode, pinNewPlaylists)

                // A mix follows its own Stack switch: stacking accumulates, anything else
                // mirrors what the service gave this time. The user's own playlists and Liked
                // keep their account's mode (whether a song removed there goes here too).
                val playlistSyncMode = when {
                    localPlaylist.type == PlaylistType.DAILY_MIX ->
                        if (localPlaylist.stackSongs) SyncMode.ACCUMULATE else SyncMode.REFRESH
                    playlistSnapshot.source == MusicSource.YOUTUBE -> youtubeSyncMode
                    playlistSnapshot.source == MusicSource.SOUNDCLOUD -> soundCloudSyncMode
                    else -> spotifySyncMode
                }
                if (localPlaylist.userHidden) {
                    Log.d(TAG, "Playlist '${playlistSnapshot.playlistName}' is out of the library, skipping")
                    skippedSwitchedOff++
                    return@applySnapshot
                }

                // Skip playlists the user has switched off — EXCEPT algorithmic
                // mixes, and EXCEPT anything at all in Online mode.
                //
                // Linking is separate from downloading: shouldEnqueueForDownload
                // reads the switch, so linking a switched-off row never queues a
                // byte. And the fetch worker has ALREADY pulled the tracks over
                // the network this run, so skipping the link buys nothing and
                // throws that work away — the "I have 130 mixes but Home shows
                // nothing" report for mixes, and "it says it synced, the playlist
                // is empty" (#477, #478) for everything else on a fresh install,
                // where nothing is switched on yet (defaultSyncEnabled). So mixes
                // always link (Home shows them, Online streams them on tap), and
                // in Online mode everything links. Offline mode keeps the skip for
                // switched-off playlists, except one kept on the phone (#474): its
                // Download switch needs what the playlist gained linked here, or the
                // sweep below has nothing new to queue.
                if (!localPlaylist.syncEnabled &&
                    !localPlaylist.keepOffline &&
                    localPlaylist.type != PlaylistType.DAILY_MIX &&
                    !streamingMode
                ) {
                    Log.d(TAG, "Playlist '${playlistSnapshot.playlistName}' sync disabled, skipping")
                    skippedSwitchedOff++
                    return@applySnapshot
                }

                // A Spotify library playlist the fetch step didn't re-read: its
                // mirror stamp still vouched for it (see SpotifyMirrorStamp), so
                // the snapshot carries that stamp and no tracks. Matching the
                // playlist's own stamp, it is left exactly as the last full sync
                // left it. A fetched snapshot carries a bare revision instead,
                // which never equals a stamp, so whatever was fetched is diffed.
                // Should the stamp have been forgotten since the fetch, this
                // misses and the snapshot's partial flag keeps the diff below
                // from clearing or recording anything.
                val localSnapshotId = playlistDao.getSnapshotId(localPlaylist.id)
                if (localSnapshotId != null &&
                    playlistSnapshot.snapshotId != null &&
                    localSnapshotId == playlistSnapshot.snapshotId
                ) {
                    Log.d(TAG, "Playlist '${playlistSnapshot.playlistName}' unchanged, skipping")
                    skippedUnchanged++
                    // Checked, with nothing to do: the progress still moves.
                    playlistsDiffed++
                    syncStateManager.onDiffing(playlistsDiffed, playlistSnapshots.size)
                    return@applySnapshot
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
                if (playlistSnapshot.source == MusicSource.SOUNDCLOUD) {
                    val connection = playlistSnapshot.soundCloudConnectionId
                    if (connection != null) soundCloudAccounts?.withCurrentSession(connection, applySnapshot)
                } else {
                    applySnapshot()
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

            // A playlist kept on the phone (#474) downloads what it gained this
            // run, in either mode: new songs, and library songs newly linked to it
            // (the existing-track path never queues). Queued, failed and cancelled
            // songs are left alone, so one pass per run is safe, and it starts one
            // drain that follows the Sync tab's "Wi-Fi only", like this sync's own
            // downloads, or any network for "Sync now". Never fatal: the sync
            // itself already worked.
            runCatching {
                val queued = musicRepository.queueKeptPlaylists(
                    manualSync = inputData.getBoolean(KEY_MANUAL_SYNC, false),
                )
                if (queued > 0) {
                    syncLog.info("Downloading $queued song${if (queued == 1) "" else "s"} for playlists kept on this phone")
                }
            }.onFailure { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Log.w(TAG, "Keep-offline download sweep failed for sync $syncId", e)
            }

            Log.i(
                TAG,
                "diff summary: ${playlistSnapshots.size} checked, $skippedSwitchedOff skipped (switched off), " +
                    "$skippedUnchanged skipped (unchanged snapshot), $newTrackCount new tracks, " +
                    "streamingMode=$streamingMode",
            )

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
     * Runs the one-time canonical re-key ([com.stash.core.data.library.CanonicalRecomputeRunner])
     * when it hasn't finished: a few seconds of short transactions, once, and a single DataStore
     * read on every sync after. Never fatal: a sync that can't re-key first works as it did before
     * the re-key existed, and the startup worker retries it.
     */
    private suspend fun reKeyOldCanonicalWords() {
        try {
            if (canonicalRecompute.runIfPending()) {
                Log.i(TAG, "re-keyed rows saved before schema 55 before diffing")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "canonical re-key before the diff failed; diffing anyway", e)
        }
    }

    /**
     * Finds an existing local playlist matching the remote snapshot,
     * or creates a new one if none exists.
     */
    private suspend fun findOrCreatePlaylist(
        snapshot: RemotePlaylistSnapshotEntity,
        streamingMode: Boolean,
        pinNewPlaylists: Boolean,
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
            // One the user took out of their library stays out: not
            // reactivated, and the caller skips it (no linking, no downloads).
            if (existing.userHidden) return existing
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
            // Opt-in by default, every type, both modes (#368 — a discovered
            // mix used to auto-enable in Online mode, and each rotation then
            // pulled a batch of downloads nobody asked for). The first Sync Now
            // is a discovery pass: it creates the playlist rows and, in Online
            // mode, links their tracks, but queues nothing until the user picks
            // what they want in the Sync Preferences card (#10). What reaches
            // Home is decided by PlaylistDao.getAllVisible, not by this flag:
            // Online, a playlist holding streamable tracks shows regardless.
            syncEnabled = defaultSyncEnabled(snapshot.playlistType, streamingMode),
            pinnedToHomeAt = if (pinOnDiscovery(snapshot.playlistType, streamingMode, pinNewPlaylists)) {
                System.currentTimeMillis()
            } else {
                null
            },
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
        // Preload every current cross-ref for this playlist ONCE instead of
        // one getCrossRef() SELECT per track. This MUST run before the
        // REFRESH clear below: the clear deletes every synced membership row,
        // so a preload taken after it saw only local adds and tombstones, and
        // every re-linked track fell through to addedAt = now. Each REFRESH
        // sync re-dated the whole playlist (the recency sort, Liked Songs'
        // liked_at = MIN(added_at), mix ordering). Same transaction, so the
        // map is exactly the state the clear removes.
        val existingCrossRefs = playlistDao.getCrossRefsForPlaylist(localPlaylist.id)
            .associateBy { it.trackId }

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
            finalizePlaylistMetadata(playlistSnapshot, localPlaylist, trackSnapshots, syncMode)
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
            finalizePlaylistMetadata(playlistSnapshot, localPlaylist, trackSnapshots, syncMode)
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
        // Blank keys are left out (the DAO skips them too): they name no song.
        val canonicalIdentities = canonicalOf.values
            .filterNot { TrackCanonical.isBlankKey(it.title, it.artist) }
            .map { (t, a) -> t to a }
            .distinct()

        val soundCloudCandidates = allowedSnapshots.mapNotNull { it.soundCloudId }.distinct()
            .chunked(900).flatMap { trackDao.findBySoundCloudIds(it) }
        val soundCloudIsrcs = allowedSnapshots.filter { it.soundCloudId != null }.mapNotNull { it.isrc }
            .filter { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }.distinct()
        val isrcCandidates = soundCloudIsrcs.chunked(900).flatMap { trackDao.findByIsrcs(it) }
        val candidates = (isrcCandidates + trackDao.findExistingForBatch(spotifyUris, youtubeIds, canonicalIdentities) + soundCloudCandidates).distinctBy { it.id }
        val candidateTracks = candidates.associate { candidate ->
            candidate.id to BatchTrack(candidate, isNew = false)
        }
        val bySpotifyUri = mutableMapOf<String, BatchTrack>()
        val byYoutubeId = mutableMapOf<String, BatchTrack>()
        val bySoundCloudId = mutableMapOf<String, BatchTrack>()
        // A canonical key can legitimately map to MULTIPLE rows — e.g. two
        // distinct YouTube uploads of "the same song" that arrive with
        // different videoIds. A single-value map here would collapse them
        // into one row, silently discarding the second identity.
        val byCanonical = mutableMapOf<CanonicalKey, MutableList<BatchTrack>>()

        fun registerBatchTrack(track: BatchTrack) {
            track.entity.soundCloudId?.let { bySoundCloudId[it] = track }
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

        // A canonical-title/artist match is only safe to reuse under
        // SameSongRule, the rule every path that finds a row by its words
        // shares: neither side's strong identifiers actively disagree (two
        // different YouTube uploads of the same song stay two rows), and the
        // titles name the same version. The words are loose, so without the
        // version check a "Song (Live)" saved from Search would take a synced
        // "Song" (each side has only its own kind of id, so ids can't tell).
        // The version check is a deny-list (only a performance word one title
        // names and the other doesn't), so Spotify's "STAY (with Justin
        // Bieber)" still meets YouTube's "STAY": for people syncing both
        // services this match is the only link between their rows.
        fun mayReuseByWords(
            track: BatchTrack,
            snapshot: RemoteTrackSnapshotEntity,
        ): Boolean = SameSongRule.mayReuseByWords(
            row = track.entity,
            title = snapshot.title,
            youtubeId = snapshot.youtubeId,
            spotifyUri = snapshot.spotifyUri,
            forDownload = false,
        )

        fun matchBatchTrack(snapshot: RemoteTrackSnapshotEntity): BatchTrack? {
            snapshot.soundCloudId?.let { id ->
                bySoundCloudId[id]?.let { return it }
                // An official ISRC is an exact recording identity. Never reuse another upload id.
                if (snapshot.isrc in soundCloudIsrcs) return candidateTracks.values.firstOrNull {
                    it.entity.isrc == snapshot.isrc && it.entity.soundCloudId == null && it.entity.source != MusicSource.CLOUD && !it.entity.matchFlagged && !it.entity.matchDismissed
                }
                return null
            }
            snapshot.spotifyUri?.takeIf(String::isNotBlank)?.let { uri ->
                bySpotifyUri[uri]?.let { return it }
            }
            snapshot.youtubeId?.takeIf(String::isNotBlank)?.let { yid ->
                byYoutubeId[yid]?.let { return it }
            }
            // A blank key ("(Intro)", no artist) names no song: "(Intro)" and
            // "[Untitled]" by one artist would otherwise be one row.
            val key = canonicalOf.getValue(snapshot)
            if (TrackCanonical.isBlankKey(key.title, key.artist)) return null
            return byCanonical[key]?.firstOrNull {
                mayReuseByWords(it, snapshot)
            }
        }

        suspend fun enrichExistingBatchTrack(
            batchTrack: BatchTrack,
            snapshot: RemoteTrackSnapshotEntity,
        ) {
            if (snapshot.soundCloudId != null) {
                val enriched = batchTrack.entity.copy(soundCloudId = snapshot.soundCloudId,
                    soundCloudPermalink = snapshot.soundCloudPermalink, soundCloudPreview = snapshot.soundCloudPreview)
                trackDao.update(enriched)
                batchTrack.entity = enriched
                bySoundCloudId[snapshot.soundCloudId] = batchTrack
            }
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

            // Spotify's parental advisory, kept current: songs synced before the web-player
            // parsers read it were stored as clean (2026-10-26), and only a sync can say otherwise.
            // YouTube's badge only fills an unknown flag: it marks explicit songs and leaves the
            // rest unmarked, and a song Spotify calls clean shouldn't flip with each YouTube sync.
            val snapshotExplicit = snapshot.explicit?.takeIf {
                playlistSnapshot.source != MusicSource.YOUTUBE || batchTrack.entity.explicit == null
            }
            if (snapshotExplicit != null && snapshotExplicit != batchTrack.entity.explicit) {
                trackDao.updateExplicit(existingTrack.id, snapshotExplicit)
                batchTrack.entity = batchTrack.entity.copy(explicit = snapshotExplicit)
            }
        }

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
                        soundCloudId = snapshot.soundCloudId,
                        soundCloudPermalink = snapshot.soundCloudPermalink,
                        soundCloudPreview = snapshot.soundCloudPreview,
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

        // Downloads follow the playlist's own switch, mixes included: in Download
        // mode a switched-on row pulls bytes, a switched-off one only links so
        // Home can show it. This site once tested raw `!streamingMode`, and every
        // track of every rotating mix was queued (#368: "it downloads 6000+ from
        // users playlists and Spotify mixes that I don't need"). The protection
        // is now the switch — discovered mixes start off — not the type.
        var linkedCopies = 0
        // Copies of new songs already on the phone, by copy id: written after [crossRefsToInsert]
        // and insert-if-absent, so they never move or revive a link that is already there.
        val copyLinks = linkedMapOf<Long, PlaylistTrackCrossRef>()
        if (shouldEnqueueForDownload(streamingMode, localPlaylist.syncEnabled)) {
            // A new song already on the phone as another row (cause 4 of the duplicates: a Spotify
            // row downloaded under one video while this YouTube playlist holds another upload, so
            // the ids keep them two rows) is not downloaded again: its copy is linked here, where
            // the song sits, and the new row keeps its own ids for the next sync. The link is
            // insert-if-absent ([copyLinks]): a copy already in this playlist keeps its place, and
            // one the user took out (a removal mark written while this sync ran included) is not
            // linked back; the song is still not downloaded twice. The shared check every download
            // path asks (SameSongRule.isLibraryCopy), its candidates read once per playlist.
            val copies = trackDao.libraryCopiesOf(
                newTracks.filterNot { it.entity.soundCloudPreview }
                    .map { it.entity.copy(id = checkNotNull(it.persistedId)) },
            )
            val firstPositionOf = newOccurrences.groupBy({ it.first }, { it.second.position })
                .mapValues { (_, positions) -> positions.min() }
            for (batchTrack in newTracks) {
                if (batchTrack.entity.soundCloudPreview) continue // A preview is never queued for download.
                val trackId = checkNotNull(batchTrack.persistedId)
                val copy = copies[trackId]
                if (copy != null) {
                    copyLinks.putIfAbsent(
                        copy.id,
                        PlaylistTrackCrossRef(
                            playlistId = localPlaylist.id,
                            trackId = copy.id,
                            position = firstPositionOf[batchTrack] ?: 0,
                        ),
                    )
                    linkedCopies++
                    continue
                }
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
        //
        // Auto-reconciliation candidates are looked up ONCE per playlist, by
        // (title, artist) pairs through the canonical index: the per-track
        // findDownloadedByCanonical was a full table scan for every
        // undownloaded existing track (every membership, for an Online-mode
        // user) on every sync, inside this write transaction. Lowest id per key
        // reproduces that query's unordered LIMIT 1 (scan order is rowid), and
        // pairs can't collide the way a `|`-joined key could. Only a
        // downloaded row of the same version counts (SameSongRule's version
        // check): the words are loose, so a downloaded "Song (Live)" is not
        // this playlist's "Song", while a downloaded "Song [Official Video]"
        // is. Ids are not compared: the downloaded copy is another row by
        // design, usually a different video than the stub's.
        fun reconcileKey(track: TrackEntity) =
            CanonicalKey(track.canonicalTitle.lowercase(), track.canonicalArtist.lowercase())
        val reconcileKeys = existingPairs
            .map { it.first.entity }
            .filter { !it.isDownloaded && !it.matchDismissed }
            .map { reconcileKey(it) }
            // A blank key would take any downloaded song with a blank key, every local import included.
            .filterNot { TrackCanonical.isBlankKey(it.title, it.artist) }
            .distinct()
        val downloadedByKey: Map<CanonicalKey, List<TrackEntity>> = if (reconcileKeys.isEmpty()) {
            emptyMap()
        } else {
            val wanted = reconcileKeys.toSet()
            trackDao.findDownloadedByCanonicalIdentities(reconcileKeys.map { it.title to it.artist })
                .filter { reconcileKey(it) in wanted }
                .groupBy(::reconcileKey)
                .mapValues { (_, rows) -> rows.sortedBy { it.id } }
        }
        for ((batchTrack, snapshot) in existingPairs) {
            if (snapshot.soundCloudId != null) {
                val enriched = batchTrack.entity.copy(soundCloudId = snapshot.soundCloudId,
                    soundCloudPermalink = snapshot.soundCloudPermalink, soundCloudPreview = snapshot.soundCloudPreview)
                trackDao.update(enriched)
                batchTrack.entity = enriched
                bySoundCloudId[snapshot.soundCloudId] = batchTrack
            }
            val existingTrack = batchTrack.entity
            addCrossRefIfNotSoftDeleted(localPlaylist.id, existingTrack.id, snapshot.position, existingCrossRefs, crossRefsToInsert)

            if (!existingTrack.isDownloaded && !existingTrack.matchDismissed) {
                val downloadedMatch = downloadedByKey[reconcileKey(existingTrack)]
                    ?.firstOrNull { TrackCanonical.sameVersion(it.title, existingTrack.title) }
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
        if (copyLinks.isNotEmpty()) {
            playlistDao.insertCrossRefsIfAbsent(copyLinks.values.toList())
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
                    "downloadsQueued=${downloadEntries.size}, alreadyOnPhone=$linkedCopies",
            )
        }

        finalizePlaylistMetadata(playlistSnapshot, localPlaylist, trackSnapshots, syncMode)
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
     * path (including the early-return-on-empty branches): when it was last
     * read, its song count, its mirror stamp and, for mixes, its cover.
     */
    private suspend fun finalizePlaylistMetadata(
        playlistSnapshot: RemotePlaylistSnapshotEntity,
        localPlaylist: PlaylistEntity,
        trackSnapshots: List<RemoteTrackSnapshotEntity>,
        syncMode: SyncMode,
    ) {
        playlistDao.updateLastSynced(localPlaylist.id, System.currentTimeMillis())
        if (snapshotUnreliable(playlistSnapshot, trackSnapshots)) {
            // #343: an unreliable fetch was NOT mirrored (see processPlaylist).
            // Stamping it would make the next sync skip this playlist as
            // "unchanged" and the missing tracks would never re-sync; stamping
            // trackSnapshots.size would show a lying track count over retained
            // content. Leave the count as it was, and an older stamp too: it no
            // longer matches this pass's merged rows unless nothing was merged,
            // and then the mirror it vouches for still stands.
            return
        }
        playlistDao.updateTrackCount(localPlaylist.id, trackSnapshots.size)
        // After the count: the stamp's digest covers it.
        recordMirrorStamp(playlistSnapshot, localPlaylist, syncMode)

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

    /**
     * Stamps a playlist this pass mirrored completely, so the next sync can
     * skip reading it while nothing has changed, there or here (see
     * [SpotifyMirrorStamp]). Only a snapshot carrying a revision gets one: a
     * Spotify library playlist whose fetch Spotify's own count verified and
     * whose revision held through the fetch ([revisionToRecord]), the user's
     * own kind of playlist ([isStampable]); every other playlist is always
     * read. Runs after this pass's last write to the playlist's rows and
     * count, inside the same transaction, so the digest describes exactly what
     * this mirror left.
     */
    private suspend fun recordMirrorStamp(
        playlistSnapshot: RemotePlaylistSnapshotEntity,
        localPlaylist: PlaylistEntity,
        syncMode: SyncMode,
    ) {
        // A Spotify-generated playlist or a row filed as a mix is always read
        // (isStampable); so is a mirror with no verified revision to stamp. An
        // older stamp must not outlive this mirror and vouch for it: dropped.
        val revision = playlistSnapshot.snapshotId
            ?.takeIf { SpotifyMirrorStamp.isUsableRevision(it) }
            ?.takeIf { isStampable(localPlaylist.sourceId, localPlaylist.type) }
        val digest = revision?.let { playlistDao.mirrorDigest(localPlaylist.id) }
        if (revision == null || digest == null) {
            playlistDao.forgetMirrorStamp(localPlaylist.id)
            return
        }
        val stamp = SpotifyMirrorStamp(
            revision = revision,
            syncMode = syncMode,
            recordedAtMs = System.currentTimeMillis(),
            digest = digest,
            appVersion = appVersionCode(applicationContext),
        )
        playlistDao.updateSnapshotId(localPlaylist.id, stamp.encode())
    }
}
