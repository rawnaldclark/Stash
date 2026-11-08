package com.stash.core.data.sync.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.spotify.SpotifyAccount
import com.stash.core.auth.spotify.withSpotifyAccount
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.sync.AuthExpiryState
import com.stash.core.data.sync.MAX_EXTRA_SPOTIFY_ACCOUNTS
import com.stash.core.data.sync.SpotifyAccountListing
import com.stash.core.data.sync.SpotifyPlaylistIds
import com.stash.core.data.sync.SpotifySavedAlbumsFetcher
import com.stash.core.data.sync.SpotifySyncListings
import com.stash.core.data.sync.hideWhatNoSpotifyAccountListed
import com.stash.core.data.sync.isSpotifySavedAlbumKey
import com.stash.core.data.sync.spotifyExtraAccountLabel
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.data.sync.auth.YoutubeAuthHealthProbe
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.StepStatus
import com.stash.core.model.SyncMode
import com.stash.core.model.SyncResult
import com.stash.core.model.SyncState
import com.stash.core.model.SyncStepResult
import com.stash.core.model.SyncTrigger
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyApiException
import com.stash.data.spotify.SpotifyLibraryPage
import com.stash.data.spotify.sortLikedSongsByAddedAtDesc
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.MusicVideoType
import com.stash.data.ytmusic.model.YTMusicPlaylist
import com.stash.data.ytmusic.model.YTMusicTrack
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

internal fun likedPlaylistArtUrl(trackArtUrls: Sequence<String?>): String? =
    com.stash.core.common.ArtUrlUpgrader.upgrade(
        trackArtUrls.firstOrNull { !it.isNullOrBlank() }
    )

/**
 * Whether a sync may soft-hide playlists that the remote didn't return this run.
 *
 * BOTH conditions are required, and each guards a real data-loss bug:
 *  - `REFRESH` — ACCUMULATE's whole promise is "never remove anything". Hiding a
 *    playlist the user still has is a removal from their point of view.
 *  - `inventoryComplete` — a partial fetch (one failed leg) must never be read as
 *    "the rest are gone" (#343/#348).
 *
 * Applies to EVERY source. It was originally Spotify-only, and YouTube open-coded
 * just the inventory half — so an ACCUMULATE sync still hid YouTube playlists,
 * including user-created ones. Keep both sources on this one predicate.
 */
internal fun shouldDeactivateMissingPlaylists(
    syncMode: SyncMode,
    inventoryComplete: Boolean,
): Boolean = syncMode == SyncMode.REFRESH && inventoryComplete

/** How many playlists the step message names before "and N more". */
private const val UNREAD_NAMED_MAX = 5

/**
 * The Spotify library step's error message, or null when its inventory is whole:
 * the listing walk's failure kept apart from the playlists whose songs couldn't
 * be read, which are named. One "inventory incomplete" covering both, naming
 * nothing, sent #747's hunt through two users' bundles for an empty playlist.
 */
internal fun spotifyInventoryProblem(listingComplete: Boolean, unreadPlaylists: List<String>): String? =
    listOfNotNull(
        "inventory incomplete — a page/folder fetch failed mid-walk".takeUnless { listingComplete },
        unreadPlaylists.takeIf { it.isNotEmpty() }?.let(::unreadPlaylistsMessage),
    ).joinToString("; ").ifEmpty { null }

/** "couldn't read 1 playlist: Road trip (kept as it was)", naming up to [UNREAD_NAMED_MAX]. */
internal fun unreadPlaylistsMessage(names: List<String>): String {
    val more = names.size - UNREAD_NAMED_MAX
    val named = names.take(UNREAD_NAMED_MAX).joinToString(", ") + if (more > 0) " and $more more" else ""
    return if (names.size == 1) {
        "couldn't read 1 playlist: $named (kept as it was)"
    } else {
        "couldn't read ${names.size} playlists: $named (kept as they were)"
    }
}

private val DAILY_MIX_NAME = Regex("""Daily Mix \d+""")

/**
 * Whether a `libraryV3` row should be snapshotted as a user playlist.
 *
 * libraryV3 lists what the user actually SAVED, so ownership says nothing
 * about whether they want it synced: a followed "Made For You" playlist —
 * a friend's Discover Weekly or Release Radar — is owned by "spotify" but
 * never appears in the user's OWN home feed, so the old
 * `owner != "spotify"` filter dropped it here and no other pass picked it
 * up (issue #354).
 *
 * Skip only what the home-feed mix pass already snapshotted this run
 * ([homeFeedMixIds]), plus "Daily Mix N" by name so the home feed stays
 * the single source of those even when the sp_dc call behind it fails.
 */
/**
 * Whether a sync run produced nothing usable and should be marked FAILED:
 * every step that actually RAN errored.
 *
 * [StepStatus.SKIPPED] steps are excluded from the vote. A step the user turned
 * off (auto-mix discovery) is not evidence the sync worked — counting it would
 * make "everything failed" unreachable, so a totally broken sync would report
 * success purely because one step was disabled. A run of nothing but SKIPPED
 * steps isn't a failure either: nothing was attempted.
 */
internal fun allAttemptedStepsFailed(diagnostics: List<SyncStepResult>): Boolean {
    val attempted = diagnostics.filter { it.status != StepStatus.SKIPPED }
    return attempted.isNotEmpty() && attempted.all { it.status == StepStatus.ERROR }
}

internal fun keepAsLibraryPlaylist(
    id: String,
    name: String,
    homeFeedMixIds: Set<String>,
): Boolean = id !in homeFeedMixIds && !DAILY_MIX_NAME.matches(name)

/**
 * The Spotify playlist ids this run can honestly say still exist — the one
 * account's listing the joint hide
 * ([com.stash.core.data.sync.hideWhatNoSpotifyAccountListed]) measures "missing"
 * against, with every other account's.
 *
 * Every id the library walk LISTED, pre-filter. libraryV3 is the account's
 * inventory, so it is the only authority on whether a saved playlist is still
 * saved — deliberately NOT widened with `homeFeedMixIds`, or unsaving a
 * playlist Spotify still features would leave its row active forever.
 *
 * Equally NOT the post-[keepAsLibraryPlaylist] list. That filter is
 * classification routing — "the mix pass owns this id, don't snapshot it
 * twice" — and says nothing about whether the playlist left the account.
 * Feeding the filtered list in hid a playlist the moment it started matching
 * the filter: appear on the home feed, or get renamed to "Daily Mix N", and
 * the CUSTOM row the walk had just listed flipped to `is_active = 0`. The
 * name-match case has no DAILY_MIX snapshot behind it to reactivate the row,
 * so the playlist disappeared for good (#437).
 *
 * ponytail: a name-filtered id counted live here gets no snapshot this run, so
 * its row keeps whatever tracks it already had. Deliberate — stale beats gone,
 * and the only ids that reach it are "Daily Mix N"-named rotating mixes whose
 * contents were never authoritative. The real repair is to snapshot such an
 * item when the home-feed pass didn't claim it; that belongs with the name
 * filter, which exists to keep the home feed the single source of Daily Mixes
 * even when sp_dc fails, and which also keeps [reconciledPlaylistType] from
 * re-filing a genuine Daily Mix as a playlist on a failed-sp_dc run.
 */
internal fun spotifyLivenessIds(observedLibraryIds: List<String>): List<String> =
    observedLibraryIds.distinct()

/**
 * First worker in the sync chain. Authenticates with configured music services,
 * fetches playlist and track metadata, and writes everything to the remote
 * snapshot tables for the subsequent [DiffWorker] to consume.
 *
 * Outputs [KEY_SYNC_ID] so downstream workers can reference the sync run.
 */
@HiltWorker
class PlaylistFetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val tokenManager: TokenManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val ytMusicApiClient: YTMusicApiClient,
    private val innerTubeClient: InnerTubeClient,
    private val playlistDao: PlaylistDao,
    private val syncHistoryDao: SyncHistoryDao,
    private val remoteSnapshotDao: RemoteSnapshotDao,
    private val syncStateManager: SyncStateManager,
    private val syncLog: com.stash.core.data.sync.SyncLog,
    private val syncNotificationManager: SyncNotificationManager,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val spotifyAuthHealthProbe: SpotifyAuthHealthProbe,
    private val youtubeAuthHealthProbe: YoutubeAuthHealthProbe,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    private val syncUndoDao: com.stash.core.data.db.dao.SyncUndoDao,
    private val soundCloudLikes: com.stash.core.data.sync.SoundCloudLikesFetcher? = null,
    private val soundCloudPlaylists: com.stash.core.data.sync.SoundCloudPlaylistsFetcher? = null,
    private val spotifySavedAlbums: com.stash.core.data.sync.SpotifySavedAlbumsFetcher? = null,
    /** The extra Spotify accounts (plan 2026-11-04): each gets its own pass after the main's. */
    private val spotifyAccountDao: com.stash.core.data.db.dao.SpotifyAccountDao? = null,
    /** The clock of [EXTRA_ACCOUNTS_BACKGROUND_BUDGET_MS]; the phone's own when not given (tests give theirs). */
    syncClock: com.stash.core.data.sync.SyncElapsedClock? = null,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        const val KEY_YOUTUBE_INVENTORY_COMPLETE = "youtube_inventory_complete"

        /**
         * Output: the Spotify sync mode (a [SyncMode] name) this fetch decided its
         * skips by. The diff mirrors Spotify in this same mode, so a mode switched
         * mid-sync can't skip a playlist under one mode and mirror under another.
         * Absent when the Spotify library walk didn't run or couldn't read it.
         */
        const val KEY_SPOTIFY_SYNC_MODE = "spotify_sync_mode"

        /** Input: the [SyncTrigger] name of what started this sync; absent = MANUAL. */
        const val KEY_TRIGGER = "sync_trigger"
        private const val TAG = "StashSync"
        /** Cap on concurrent in-flight YT browse calls during a sync. */
        private const val MAX_PARALLEL_YT_FETCHES = 3

        // Home Mixes (Daily Mix etc.) are infinite radio — every page returns
        // another continuation token. Cap to the initial page (~100 tracks)
        // so a sync doesn't spend minutes walking radio. User playlists +
        // Liked Songs use the full MAX_PAGES default in YTMusicApiClient.
        private const val HOME_MIX_MAX_PAGES = 1

        /**
         * Safety cap on Spotify Liked Songs pages (50 each, so 50,000 songs).
         * Hitting it marks the snapshot partial rather than truncating silently.
         */
        private const val LIKED_SONGS_PAGE_CAP = 1000

        /** Snapshot ids bound in one re-tag write, well inside SQLite's bind limit. */
        private const val SNAPSHOT_IDS_PER_WRITE = 500

        /**
         * How long a sync running in the background may have run before the extra Spotify
         * accounts are left for the next sync. Android 12+ stops a background job at about 10
         * minutes, and a scheduled sync can't go foreground ([promoteToForeground]): stopped, it
         * starts again from scratch, and an extra's first sync (every playlist read) could be
         * stopped every time. What is left of the 10 minutes is for YouTube, SoundCloud and the
         * rest. A sync in the foreground has no limit, as before.
         */
        internal const val EXTRA_ACCOUNTS_BACKGROUND_BUDGET_MS = 6 * 60 * 1000L

        // Shared reason string for auth-expiry short-circuit so the
        // sync_history FAILED row and the SyncStateManager onError call
        // can't drift out of sync.
        private const val AUTH_EXPIRED_REASON = "Credentials expired — re-authenticate to resume sync"
    }

    /**
     * Running count of playlists/mixes whose tracks have been fetched
     * (Spotify daily mixes + liked songs + user playlists, YouTube home
     * mixes + liked songs + user playlists) — fed to
     * [SyncStateManager.onFetchingPlaylists] so a long fetch on a big
     * library shows a live "Fetched N playlists…" counter instead of the
     * bar sitting frozen. Thread-safe: several of these fetches run
     * concurrently via [fetchYouTubePlaylists]'s Semaphore-bounded async.
     */
    private val fetchedPlaylistCounter = AtomicInteger(0)

    private fun reportPlaylistFetched() {
        syncStateManager.onFetchingPlaylists(fetchedPlaylistCounter.incrementAndGet())
    }

    /**
    * Flips to false the moment ANY YouTube fetch leg this run (home mixes
    * listing, liked songs, user-playlist listing, or any individual mix/
    * playlist's track fetch) errors or throws. Read by [doWork] and passed
    * to DiffWorker via workDataOf so it can skip
    * [PlaylistDao.deactivateMissingForSource] on a partial inventory —
    * otherwise one flaky sub-fetch makes every OTHER YouTube playlist look
    * "missing" and DiffWorker hides them (#post-343 YouTube analogue).
    */
    private val youtubeInventoryComplete = java.util.concurrent.atomic.AtomicBoolean(true)

    /** The Spotify sync mode this run read, handed to the diff ([KEY_SPOTIFY_SYNC_MODE]). */
    @Volatile
    private var spotifySyncModeThisRun: SyncMode? = null

    private val clock = syncClock ?: com.stash.core.data.sync.SyncElapsedClock()

    /** When [doWork] started, on [clock]: the system's job limit counts from about here. */
    @Volatile
    private var startedAtMs = 0L

    /** Whether [promoteToForeground] worked: a foreground sync has no job time limit. */
    @Volatile
    private var inForeground = false

    /**
     * Whether a background sync has used [EXTRA_ACCOUNTS_BACKGROUND_BUDGET_MS]: the extra Spotify
     * accounts not read yet wait for the next sync, and one being read stops before its next read.
     */
    private fun outOfTimeForExtraAccounts(): Boolean =
        !inForeground && clock.nowMs() - startedAtMs >= EXTRA_ACCOUNTS_BACKGROUND_BUDGET_MS

    private fun markYoutubeIncomplete(reason: String) {
        if (youtubeInventoryComplete.compareAndSet(true, false)) {
            Log.w(TAG, "YouTube inventory marked incomplete: $reason")
        }
    }

    /**
     * An Empty answer from a library-level YouTube listing (library
     * playlists, Liked Songs, saved albums) is NOT proof the library is empty:
     * an expired cookie makes InnerTube answer as a logged-out user, whose
     * library browses come back empty rather than failing (see
     * [YoutubeAuthHealthProbe]), and the logged-out home feed still returns
     * mixes, so the run looks like a success. Counting that Empty as a
     * complete inventory let REFRESH hide every user playlist, saved album
     * and Liked Songs until the user re-authed. So when we already hold
     * ACTIVE rows of that kind, the Empty marks the inventory incomplete
     * (deactivation is skipped, nothing is lost); with no such rows there is
     * nothing to hide and the run stays complete.
     *
     * Trade-off: a user who really emptied that whole section keeps the old
     * rows until a listing returns something again, the same fail-closed
     * choice #343 made for tracks.
     *
     * @return true when the inventory was marked incomplete.
     */
    private suspend fun markIncompleteIfEmptyListingHidesRows(
        listing: String,
        type: PlaylistType,
        savedAlbums: Boolean = false,
    ): Boolean {
        if (!playlistDao.hasActiveYouTubeRows(type, savedAlbums)) return false
        markYoutubeIncomplete("$listing returned Empty while active rows exist (logged out?)")
        return true
    }

    /**
     * The "Fetching your library…" foreground notification, used by
     * [promoteToForeground] so the system doesn't stop the fetch at the
     * ~10-minute background-job limit on huge libraries (3000+ liked songs can
     * take more than 10 minutes of paginated API calls).
     *
     * WorkManager itself only calls this for EXPEDITED work on Android 11 and
     * below; the sync chain is not expedited, so overriding it alone never
     * promoted anything — the fetch ran as an ordinary job, was stopped at the
     * limit, and restarted from scratch with a new sync_history row.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = syncNotificationManager.buildProgressNotification(
            title = "Syncing playlists",
            text = "Fetching your library…",
            progress = -1f, // indeterminate spinner — we don't know total until mid-fetch
            cancelIntent = cancelIntent,
        )
        return ForegroundInfo(
            SyncNotificationManager.NOTIFICATION_ID_PROGRESS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * Best-effort promotion to a foreground service for the long fetch below.
     * Succeeds when the sync starts with the app in the foreground ("Sync
     * now"); a scheduled run that starts in the background gets
     * ForegroundServiceStartNotAllowedException on Android 12+ and carries on
     * as a regular job, exactly as before — a missing notification is no reason
     * to fail the sync. Mirrors [TrackDownloadWorker]'s safeSetForeground, but
     * lets a stopped sync's cancellation through.
     *
     * @return whether the fetch now runs in the foreground (no job time limit).
     */
    private suspend fun promoteToForeground(): Boolean =
        try {
            setForeground(getForegroundInfo())
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "setForeground failed (backgrounded / foreground-restricted); fetching as a regular job", e)
            false
        }

    override suspend fun doWork(): Result {
        startedAtMs = clock.nowMs()
        // Step 1: Create a sync history record. Only one sync runs at a time, so a
        // row still open now belongs to a chain that was replaced while paused
        // between steps: close it as interrupted, not "Running" until the next launch.
        syncHistoryDao.resetStaleSyncs()
        val syncEntry = SyncHistoryEntity(
            status = SyncState.AUTHENTICATING,
            // SyncScheduler says what started the sync; a chain queued before it did counts as MANUAL.
            trigger = inputData.getString(KEY_TRIGGER)?.let { SyncTrigger.valueOf(it) } ?: SyncTrigger.MANUAL,
            streamingMode = streamingPreference.current(),
            startedAt = Instant.now(),
        )
        val syncId = syncHistoryDao.insert(syncEntry)
        val diagnostics = java.util.Collections.synchronizedList(mutableListOf<SyncStepResult>())
        // Starts a fresh terminal for this run. Previous lines are cleared here
        // rather than at the end, so the last run stays readable until a new one
        // begins — reading it AFTER the fact is the point.
        syncLog.beginRun("Looking for new music…")

        try {
            // Step 2: Authenticating phase.
            syncStateManager.onAuthenticating()

            val isSpotifyAuthenticated = tokenManager.isAuthenticated(AuthService.SPOTIFY)
            val isYouTubeAuthenticated = tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC)

            val soundCloudSession = if (soundCloudLikes != null) try {
                if (tokenManager.soundCloudSession.first()?.oauthToken != null) tokenManager.getSoundCloudToken()
                tokenManager.soundCloudSession.first()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                diagnostics.add(SyncStepResult("SOUNDCLOUD", "authenticate", StepStatus.ERROR,
                    errorMessage = "SoundCloud session could not be renewed. Reconnect SoundCloud in Accounts."))
                null
            } else null
            if (!isSpotifyAuthenticated && !isYouTubeAuthenticated && soundCloudSession == null) {
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.FAILED,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = "No authenticated services",
                )
                syncStateManager.onError("No authenticated services")
                return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
            }

            // Step 2b: Probe live auth health for each connected source. We
            // abort the chain ONLY when no connected source is usable (every
            // one expired) — downstream workers (DiffWorker, TrackDownloadWorker,
            // SyncFinalizeWorker) won't run because Result.failure() short-circuits
            // the WorkContinuation. When only some sources are expired the chain
            // proceeds and we fetch just the healthy ones (Step 4/5). The same
            // isAuthenticated() booleans gate both the probe and the fetch so the
            // "usable source" predicate can't drift. The AuthExpiredBanner reads
            // syncStateManager.authExpiry to render the per-source "Re-authenticate" CTA.
            val probeResult = runAuthProbes(
                spotifyConnected = isSpotifyAuthenticated,
                youtubeConnected = isYouTubeAuthenticated,
                spotifyProbe = spotifyAuthHealthProbe,
                youtubeProbe = youtubeAuthHealthProbe,
            )
            syncStateManager.onAuthExpiryProbed(probeResult.state)
            if (probeResult.shortCircuit && soundCloudSession == null) {
                Log.i(
                    TAG,
                    "Auth expired (spotify=${probeResult.state.spotifyExpired}, " +
                        "youtube=${probeResult.state.youtubeExpired}); aborting chain",
                )
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.FAILED,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = AUTH_EXPIRED_REASON,
                )
                syncStateManager.onError(AUTH_EXPIRED_REASON)
                return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
            }

            // Step 3: Transition to fetching playlists. This is the long part,
            // so ask to run it as a foreground service first.
            inForeground = promoteToForeground()
            syncStateManager.onFetchingPlaylists()
            syncHistoryDao.updateStatus(syncId, SyncState.FETCHING_PLAYLISTS)

            // Step 4 & 5: Fetch each source the probe deemed usable. The
            // fetch flags fold in both "connected" and "not expired", and are
            // the SAME values that drove the short-circuit decision above — so
            // a source skipped here is exactly a source that didn't count
            // toward keeping the sync alive.
            if (probeResult.fetchSpotify) {
                fetchSpotifyPlaylists(syncId, diagnostics)
            } else {
                noteExtraSpotifyAccountsNotRead(diagnostics)
            }
            var youtubeComplete = true
            if (probeResult.fetchYoutube) {
                youtubeComplete = fetchYouTubePlaylists(syncId, diagnostics)
            }

            if (soundCloudSession != null && soundCloudLikes != null) {
                try {
                    val applied = soundCloudLikes.fetch(syncId, soundCloudSession)
                    diagnostics.add(SyncStepResult("SOUNDCLOUD", "getLikedSongs",
                        if (applied) StepStatus.SUCCESS else StepStatus.SKIPPED))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    diagnostics.add(SyncStepResult("SOUNDCLOUD", "getLikedSongs", StepStatus.ERROR,
                        errorMessage = "SoundCloud likes could not be fetched; library kept unchanged"))
                }
            }
            if (soundCloudSession != null && soundCloudPlaylists != null) {
                val steps: List<Pair<String, suspend () -> com.stash.core.data.sync.SoundCloudPlaylistFetchResult>> = listOf(
                    "getPlaylists" to { soundCloudPlaylists.fetchPlaylists(syncId, soundCloudSession) },
                    "getPersonalMixes" to {
                        if (syncPreferencesManager.discoverAutoMixes.first()) soundCloudPlaylists.fetchMixes(syncId, soundCloudSession)
                        else com.stash.core.data.sync.SoundCloudPlaylistFetchResult(skipped = true)
                    },
                )
                for ((name, fetch) in steps) {
                    try {
                        val fetched = fetch()
                        if (fetched.published > 0 || fetched.failed == 0) {
                            diagnostics.add(SyncStepResult("SOUNDCLOUD", name, when {
                                fetched.skipped -> StepStatus.SKIPPED
                                fetched.published == 0 -> StepStatus.EMPTY
                                else -> StepStatus.SUCCESS
                            }, itemCount = fetched.published))
                        }
                        if (fetched.failed > 0) diagnostics.add(SyncStepResult("SOUNDCLOUD", "${name}Details", StepStatus.ERROR,
                            errorMessage = "${fetched.failed} SoundCloud lists could not be fetched; their existing contents were kept"))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        diagnostics.add(SyncStepResult("SOUNDCLOUD", name, StepStatus.ERROR,
                            errorMessage = "SoundCloud lists could not be fetched; existing lists kept unchanged"))
                    }
                }
            }

            // Persist diagnostics.
            syncHistoryDao.updateDiagnostics(syncId, Json.encodeToString(diagnostics.toList()))

            // If every step that RAN errored, the sync produced no data -- fail.
            if (allAttemptedStepsFailed(diagnostics)) {
                val summary = diagnostics
                    .filter { it.status != StepStatus.SKIPPED }
                    .joinToString("; ") { "${it.service}/${it.step}: ${it.errorMessage}" }
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.FAILED,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = "All API calls failed: $summary",
                )
                syncStateManager.onError("All API calls failed")
                return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
            }

            return Result.success(
                androidx.work.Data.Builder()
                    .putLong(KEY_SYNC_ID, syncId)
                    .putBoolean(KEY_YOUTUBE_INVENTORY_COMPLETE, youtubeComplete)
                    .apply { spotifySyncModeThisRun?.let { putString(KEY_SPOTIFY_SYNC_MODE, it.name) } }
                    .build()
            )
        } catch (e: SpotifyApiException) {
            // Transient Spotify API failure (e.g. 429 rate limit exhausted) --
            // ask WorkManager to schedule a retry so the sync can succeed later.
            Log.w(TAG, "Spotify API error (HTTP ${e.httpCode}), scheduling retry", e)
            syncHistoryDao.updateDiagnostics(syncId, Json.encodeToString(diagnostics.toList()))
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = "Spotify rate limited (HTTP ${e.httpCode}), will retry",
            )
            syncStateManager.onError("Spotify API rate limited, retrying...", e)
            return Result.retry()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Playlist fetch failed", e)
            syncHistoryDao.updateDiagnostics(syncId, Json.encodeToString(diagnostics.toList()))
            syncHistoryDao.updateStatus(
                id = syncId,
                status = SyncState.FAILED,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message,
            )
            syncStateManager.onError("Fetch failed: ${e.message}", e)
            return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
        }
    }

    /**
     * The Spotify part of a sync (plan 2026-11-04, Wave 2): one pass per account, the main account's
     * first, then each extra account's ([fetchExtraSpotifyAccounts]), then ONE joint hide of what no
     * account listed ([hideMissingSpotifyRows]). One sync mode for every account, read once here.
     *
     * The main account's pass is exactly the one-account sync it always was: a [SpotifyApiException]
     * it rethrows (a 429) ends the Spotify fetch right here, so no extra account is read and nothing
     * is hidden, and [doWork] retries the chain. An extra account's failures never get that far.
     *
     * @throws SpotifyApiException only from the main account's pass (see [fetchSpotifyAccount]).
     */
    private suspend fun fetchSpotifyPlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
        // Read once: what decides the skips also gates the joint hide, for every account alike.
        // Unreadable: nothing is skipped and nothing is hidden.
        val mode = runCatching { syncPreferencesManager.spotifySyncMode.first() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        spotifySyncModeThisRun = mode
        // Every extra account, read or not this sync (over the limit, signed out): a row tagged
        // with one of them is that account's until its own listing proves otherwise.
        val extras = extraSpotifyAccountsOrNone()
        val run = SpotifySyncRun(
            syncId, mode,
            extraTags = extras.mapTo(HashSet()) { SpotifyPlaylistIds.accountTag(it.userId) },
            outOfTime = ::outOfTimeForExtraAccounts,
        )

        // Auto-mix discovery is opt-OUT (#335, #344). These are playlists the
        // user never created or saved, so when someone turns them off we must
        // not fetch them at all — skipping the call is also what makes their
        // sync shorter, which was half the complaint. Anything already in the
        // library stays put; the per-mix Home toggle in Manage Playlists still
        // applies to those. Defaults to true — see SyncPreferencesManager.
        val discoverMixes = runCatching {
            syncPreferencesManager.discoverAutoMixes.first()
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(true)

        // Spotify's 429s so far: each of the main's reads swallows its own into a failed page, so
        // the count says whether the main's pass met any. Read only with an extra to protect.
        val rateLimitedBefore = if (extras.isNotEmpty()) spotifyApiClient.rateLimitedAnswers else 0
        fetchSpotifyAccount(SpotifyPass.main(discoverMixes), run, diagnostics)
        // Spotify turning the main's requests away: the extras would only add to it, and get the
        // same answers. They wait for the next sync, their rows left as they are, and nothing of a
        // kind they sync is hidden this time ([holdBackHides]).
        val extrasHeldBack = when {
            extras.isEmpty() -> null
            spotifyApiClient.rateLimitedAnswers > rateLimitedBefore ->
                "not read this sync: Spotify was turning requests away (HTTP 429) while the main account synced (read on the next sync)"
            run.albumBudget.failedInARow >= SpotifySavedAlbumsFetcher.FAILURES_TO_STOP ->
                "not read this sync: the main account's album reads kept failing, as when Spotify turns requests away (read on the next sync)"
            else -> null
        }
        fetchExtraSpotifyAccounts(extras, run, diagnostics, extrasHeldBack)
        // With no extra account at any row, every tag is an orphan's (or the main's): nothing to keep.
        if (extras.isNotEmpty()) keepRowsWithUnprovenOwners(run)
        hideMissingSpotifyRows(run, diagnostics)
    }

    /**
     * The extra Spotify accounts' passes, after the main's: at most [MAX_EXTRA_SPOTIFY_ACCOUNTS], in
     * row-id order. Each runs acting as its account ([withSpotifyAccount]) and only when its sign-in
     * still gives a token, decided once, before the pass ([fetchExtraSpotifyAccount]).
     */
    private suspend fun fetchExtraSpotifyAccounts(
        extras: List<com.stash.core.data.db.entity.SpotifyAccountEntity>,
        run: SpotifySyncRun,
        diagnostics: MutableList<SyncStepResult>,
        /** Why no extra is read this sync (the main's pass met Spotify's limits); null to read them. */
        heldBack: String?,
    ) {
        for ((index, row) in extras.withIndex()) {
            currentCoroutineContext().ensureActive()
            val label = spotifyExtraAccountLabel(row.id)
            if (index >= MAX_EXTRA_SPOTIFY_ACCOUNTS) {
                // Past the limit: not read, and its rows left exactly as they are.
                diagnostics.add(
                    SyncStepResult(
                        "SPOTIFY", "accountLimit", StepStatus.SKIPPED, account = label,
                        note = "not read: a sync reads at most $MAX_EXTRA_SPOTIFY_ACCOUNTS extra accounts",
                    ),
                )
                continue
            }
            if (heldBack != null) {
                diagnostics.add(SyncStepResult("SPOTIFY", "accountPass", StepStatus.SKIPPED, account = label, note = heldBack))
                holdBackHides(row, run)
                continue
            }
            if (run.outOfTime()) {
                // A background sync near the system's limit: read next time, its rows left as they
                // are, and nothing of a kind it syncs hidden for anyone until then.
                Log.i(TAG, "fetchSpotifyPlaylists: $label — out of time for a background sync, not read")
                holdBackHides(row, run)
                diagnostics.add(
                    SyncStepResult(
                        "SPOTIFY", "accountPass", StepStatus.SKIPPED, account = label,
                        note = "not read this sync: a sync in the background gets about 10 minutes, and the accounts before it used most of them (read on the next sync)",
                    ),
                )
                continue
            }
            fetchExtraSpotifyAccount(row, run, diagnostics)
        }
    }

    /**
     * One extra account's pass. Contained: whatever it throws, a 429 ([SpotifyApiException])
     * included, is caught here and recorded as this account's step. It never fails or retries the
     * chain, and never touches what the main account's pass (or another extra's) brought in. A
     * listing it didn't finish stays incomplete, so nothing of that kind is hidden this sync.
     * Stopped for time ([ExtraAccountOutOfTime]), every listing of it is left not whole
     * ([holdBackHides]), so nothing of a kind it syncs is hidden this sync.
     *
     * Whether it runs is decided once, by asking for its access token acting as it: null means its
     * sign-in no longer works, so the pass is skipped, its rows left exactly as they are, and
     * `signed_out_at` set ("Sign in again"). A token clears `signed_out_at` again. Asked once rather
     * than per request: a dead extra's token exchange would otherwise be retried on every call.
     */
    private suspend fun fetchExtraSpotifyAccount(
        row: com.stash.core.data.db.entity.SpotifyAccountEntity,
        run: SpotifySyncRun,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        val label = spotifyExtraAccountLabel(row.id)
        try {
            val acting = SpotifyAccount(row.id, row.userId)
            withSpotifyAccount(acting) {
                val token = tokenManager.getSpotifyAccessToken()
                if (token == null) {
                    noteExtraSignedOut(row)
                    Log.w(TAG, "fetchSpotifyPlaylists: $label — sign-in no longer works, not read")
                    syncLog.warn("Spotify · ${row.label.ifBlank { "another account" }} — sign in again to sync it (kept as it was)")
                    diagnostics.add(
                        SyncStepResult(
                            "SPOTIFY", "signIn", StepStatus.ERROR, account = label,
                            errorMessage = "this account's sign-in no longer works: sign in again (its playlists are kept as they are)",
                        ),
                    )
                    return@withSpotifyAccount
                }
                noteExtraSignedIn(row)
                syncLog.info("Spotify · ${row.label.ifBlank { "another account" }}…")
                fetchSpotifyAccount(SpotifyPass.extra(row), run, diagnostics)
            }
        } catch (e: ExtraAccountOutOfTime) {
            // Stopped before its next read. What it read stays (the diff applies it, and the next
            // sync skips what is unchanged since); nothing of a kind it syncs is hidden this sync.
            holdBackHides(row, run)
            Log.i(TAG, "fetchSpotifyPlaylists: $label — out of time for a background sync, stopped part way")
            diagnostics.add(
                SyncStepResult(
                    "SPOTIFY", "accountPass", StepStatus.SKIPPED, account = label,
                    note = "stopped part way: a sync in the background gets about 10 minutes (the rest is read on the next sync)",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchSpotifyPlaylists: $label — pass failed, the other accounts' results stand", e)
            syncLog.warn("Spotify · ${row.label.ifBlank { "another account" }} — couldn't finish reading it (kept as it was)")
            diagnostics.add(
                SyncStepResult(
                    "SPOTIFY", "accountPass", StepStatus.ERROR, account = label,
                    httpCode = (e as? SpotifyApiException)?.httpCode,
                    // PRIVACY: an error's text could carry the account's id (a URL); it never reaches History.
                    errorMessage = withoutAccountId(e.message ?: e.javaClass.simpleName, row.userId),
                ),
            )
        }
    }

    /**
     * An extra not read this sync, or stopped part way, for a passing reason: a background sync out
     * of time, or Spotify turning the main account away. Its library is unknown this time, and it may
     * still follow a playlist (or have saved an album) another account just dropped, so nothing of a
     * kind it syncs is hidden this sync, for anyone: each of its listings of a kind switched on is
     * left, or registered, not whole ([com.stash.core.data.sync.SpotifyAccountListing.listedAll]),
     * which the joint hide reads as "hide none of this kind". Its own rows wait too, and none moves
     * away from it.
     *
     * Not for a signed-out or over-the-limit account: that can last for weeks, so it holds nobody
     * back, and only its own rows wait (plan 2026-11-04 §2.4).
     */
    private fun holdBackHides(row: com.stash.core.data.db.entity.SpotifyAccountEntity, run: SpotifySyncRun) {
        val tag = SpotifyPlaylistIds.accountTag(row.userId)
        fun holdBack(listings: MutableList<SpotifyAccountListing>, switchedOn: Boolean, kind: String) {
            val own = listings.filter { it.owner == tag }
            if (own.isEmpty()) {
                if (!switchedOn) return
                // Never started: registered as never listed.
                listings += SpotifyAccountListing(tag)
            }
            for (listing in own) {
                listing.listedAll = false
                listing.complete = false
            }
            run.hidesHeldBack += kind
        }
        holdBack(run.listings.playlists, row.syncPlaylists, "playlists")
        holdBack(run.listings.albums, row.syncAlbums && spotifySavedAlbums != null, "albums")
    }

    /** The extra Spotify accounts in row-id order; none when there is no table to read (tests) or it can't be read. */
    private suspend fun extraSpotifyAccountsOrNone(): List<com.stash.core.data.db.entity.SpotifyAccountEntity> {
        val dao = spotifyAccountDao ?: return emptyList()
        return try {
            dao.getAll().sortedBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchSpotifyPlaylists: couldn't read the extra Spotify accounts; main account only", e)
            emptyList()
        }
    }

    /**
     * The Spotify fetch didn't run this sync (the main account isn't connected, or its sign-in
     * expired), so neither did any extra account's: they need the main's pass to run first (its
     * claims, and ownership settling on the main). Said once per extra in the steps.
     */
    private suspend fun noteExtraSpotifyAccountsNotRead(diagnostics: MutableList<SyncStepResult>) {
        for ((index, row) in extraSpotifyAccountsOrNone().withIndex()) {
            if (index >= MAX_EXTRA_SPOTIFY_ACCOUNTS) break
            diagnostics.add(
                SyncStepResult(
                    "SPOTIFY", "accountPass", StepStatus.SKIPPED, account = spotifyExtraAccountLabel(row.id),
                    note = "not read: the main Spotify account wasn't read this sync",
                ),
            )
        }
    }

    /** Records that [row]'s sign-in stopped working, keeping the first time it was found. Never fatal. */
    private suspend fun noteExtraSignedOut(row: com.stash.core.data.db.entity.SpotifyAccountEntity) {
        if (row.signedOutAt != null) return
        runCatching { spotifyAccountDao?.updateSignedOutAt(row.id, System.currentTimeMillis()) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /** Clears [row]'s "sign in again" once its sign-in works again. Never fatal. */
    private suspend fun noteExtraSignedIn(row: com.stash.core.data.db.entity.SpotifyAccountEntity) {
        if (row.signedOutAt == null) return
        runCatching { spotifyAccountDao?.updateSignedOutAt(row.id, null) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Ownership moves away from an account only when that account's listing of the kind was whole
     * this sync (plan 2026-11-04 §2.4, review of #858). The diff gives a row the account of the
     * snapshot applied to it; a snapshot from one account over a row another account owns is
     * tagged back to the owner here, after every pass, when the owner:
     *  - ran this sync, but its listing of that kind missed a page (read before or after the
     *    snapshot's account);
     *  - or didn't list that kind at all: signed out, switched off, over the limit, out of time,
     *    or the main account with its saved albums switched off.
     * The row's songs still follow the snapshot; only the tag stays. So a playlist the main can't
     * list this sync (a folder over 5 deep, a library past the 40-page walk) but an extra follows
     * is read, refreshed and, when hidden, shown again by the extra's pass, and stays the main's.
     * No pass holds such a row back: the main didn't snapshot it (an id it snapshots is one it
     * listed, so claimed), so the extra's snapshot is the only one under that key this sync. A row whose tag names no
     * account at all (a removed one, or one a backup carried in) goes to the account that lists
     * it, as it always did. With no extra accounts there is nothing to do, and this isn't called.
     *
     * Only an extra's snapshot is ever tagged back. One from the main account always moves the
     * row to the main: a playlist the main lists settles on it (plan 2026-11-04 §2.4, §3.5), and
     * the main is never removed, so the row can't then be hidden with an extra that wasn't read.
     *
     * Not caught: a snapshot left over a row it would wrongly take must never reach the diff, so
     * a failure here fails the fetch (and the chain) instead.
     */
    private suspend fun keepRowsWithUnprovenOwners(run: SpotifySyncRun) {
        val moving = remoteSnapshotDao.spotifySnapshotsMovingARow(run.syncId)
        if (moving.isEmpty()) return
        val kept = moving.filter { snapshot ->
            val owner = snapshot.rowAccount
            // An account with no row behind it: whoever lists the row now adopts it.
            if (owner != null && owner !in run.extraTags) return@filter false
            val listings = if (isSpotifySavedAlbumKey(snapshot.sourceId)) run.listings.albums else run.listings.playlists
            listings.none { it.owner == owner && it.listedAll }
        }
        for ((owner, snapshots) in kept.groupBy { it.rowAccount }) {
            for (ids in snapshots.map { it.snapshotId }.chunked(SNAPSHOT_IDS_PER_WRITE)) {
                remoteSnapshotDao.setSpotifyAccount(ids, owner)
            }
        }
        if (kept.isNotEmpty()) {
            Log.i(TAG, "fetchSpotifyPlaylists: ${kept.size} row(s) stay with their account (its listing wasn't whole this sync); ${moving.size - kept.size} move")
        }
    }

    /**
     * The joint hide after every account's pass ([hideWhatNoSpotifyAccountListed], plan 2026-11-04
     * §2.4), outside any acting account. The albums it hid are named on their account's albums step,
     * as that step did when it still hid them itself. Never fatal: a failure here hides nothing and
     * costs no account its results.
     */
    private suspend fun hideMissingSpotifyRows(run: SpotifySyncRun, diagnostics: MutableList<SyncStepResult>) {
        if (run.hidesHeldBack.isNotEmpty() && run.mode == SyncMode.REFRESH) {
            // Said once: an extra skipped or stopped for a passing reason ([holdBackHides]).
            val kinds = run.hidesHeldBack.joinToString(" or ")
            syncLog.info("Hiding no Spotify $kinds this sync: one of your accounts couldn't be read in full")
        }
        val outcome = try {
            hideWhatNoSpotifyAccountListed(playlistDao, syncUndoDao, syncLog, run.syncId, run.mode, run.listings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchSpotifyPlaylists: the joint hide failed; nothing hidden", e)
            diagnostics.add(SyncStepResult("SPOTIFY", "hideMissing", StepStatus.ERROR, errorMessage = e.message))
            return
        }
        for ((owner, hidden) in outcome.hiddenAlbums) {
            val label = run.labels[owner]
            synchronized(diagnostics) {
                val at = diagnostics.indexOfFirst {
                    it.service == "SPOTIFY" && it.step == com.stash.core.data.sync.SpotifySavedAlbumsFetcher.STEP && it.account == label
                }
                if (at >= 0) diagnostics[at] = com.stash.core.data.sync.SpotifySavedAlbumsFetcher.withHidden(diagnostics[at], hidden)
            }
        }
    }

    /**
     * One Spotify account's pass: its mixes, then its Liked Songs, then the library walk, then its
     * saved albums, each as its switches allow ([SpotifyPass]). An extra's pass runs acting as that
     * account, so every Spotify call in it reads that account's library. Every snapshot it writes
     * carries the account ([SpotifyPass.owner]); an id an earlier pass listed this sync is counted
     * as listed but not snapshotted again ([SpotifySyncRun.claimed]). It hides nothing: it registers
     * what it listed ([SpotifySyncRun.listings]) for the joint hide.
     *
     * Uses the two-pronged approach:
     * - Daily Mixes enumeration uses sp_dc/GraphQL (may fail gracefully)
     * - Track fetching uses client_credentials/Web API (reliable)
     * - Liked Songs uses sp_dc/GraphQL (may fail gracefully)
     *
     * Partial failures are logged but do NOT cause the entire sync to fail.
     * For example, if Daily Mixes can't be enumerated (sp_dc issue) but
     * Liked Songs work, the sync still succeeds with whatever data was fetched.
     *
     * @throws SpotifyApiException only if a critical, retryable API error occurs
     *         during track fetching (e.g., client_credentials token endpoint down).
     */
    private suspend fun fetchSpotifyAccount(
        pass: SpotifyPass,
        run: SpotifySyncRun,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        Log.d(TAG, "fetchSpotifyPlaylists: starting ${pass.label ?: "main account"} for syncId=${run.syncId}")
        run.labels[pass.owner] = pass.label
        // What earlier passes listed this sync: theirs to snapshot (one snapshot per id per sync).
        // A row an earlier account owns but didn't list is read here like any other; whose it stays
        // is settled after every pass (keepRowsWithUnprovenOwners).
        val claimedBefore: Set<String> = HashSet(run.claimed)
        // Registered before anything is read, as incomplete: a pass that fails part way, however it
        // fails, leaves them so, and the joint hide then hides nothing for anyone.
        val playlistListing = if (pass.playlists) SpotifyAccountListing(pass.owner).also { run.listings.playlists += it } else null
        val albumListing = if (pass.albums && spotifySavedAlbums != null) {
            SpotifyAccountListing(pass.owner).also { run.listings.albums += it }
        } else {
            null
        }
        // Ids covered by the home-feed mix pass; the library walk
        // excludes exactly these instead of everything spotify owns (#354).
        val homeFeedMixIds = mutableSetOf<String>()
        try {
            readSpotifyAccount(pass, run, claimedBefore, homeFeedMixIds, playlistListing, albumListing, diagnostics)
        } finally {
            // What this pass listed is claimed for the passes after it, even when it failed part
            // way: what it did snapshot must not be snapshotted again (REPLACE on (sync, id)).
            run.claimed += homeFeedMixIds
            playlistListing?.let { run.claimed += it.ids }
            albumListing?.let { run.claimed += it.ids }
        }
    }

    /** The reads of [fetchSpotifyAccount], in order; [playlistListing] / [albumListing] null when switched off. */
    private suspend fun readSpotifyAccount(
        pass: SpotifyPass,
        run: SpotifySyncRun,
        claimedBefore: Set<String>,
        homeFeedMixIds: MutableSet<String>,
        playlistListing: SpotifyAccountListing?,
        albumListing: SpotifyAccountListing?,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        val syncId = run.syncId
        var dailyMixCount = 0
        var likedSongCount = 0

        // Fetch Daily Mixes (sp_dc dependent -- may return empty if GraphQL fails).
        if (!pass.mixes) {
            Log.d(TAG, "fetchSpotifyPlaylists: auto-mix discovery disabled, skipping home feed")
            diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.SKIPPED, 0, account = pass.label))
        } else try {
            when (val result = spotifyApiClient.getDailyMixes()) {
                is SyncResult.Success -> {
                    // Fold in the yearly recaps ("Your Top Songs 2024", "Your
                    // All-Time Top Songs"). They only appear on the home feed for
                    // a few weeks each December, so without this a sync in any
                    // other month can never find them — the long-standing "why
                    // don't we pull yearly mixes" gap.
                    //
                    // Additive and best-effort: anything but Success is ignored,
                    // so a change on Spotify's side costs these extra playlists,
                    // never the mixes the home feed already found.
                    val yearly = when (val y = spotifyApiClient.getYearlyMixes()) {
                        is SyncResult.Success -> y.data
                        else -> emptyList()
                    }
                    val seenIds = result.data.mapTo(mutableSetOf()) { it.id }
                    val extraYearly = yearly.filter { seenIds.add(it.id) }
                    if (extraYearly.isNotEmpty()) {
                        Log.d(TAG, "fetchSpotifyPlaylists: +${extraYearly.size} yearly recap(s)")
                        syncLog.success(
                            "More mixes — " + extraYearly.joinToString(", ") { it.name },
                        )
                    }
                    val dailyMixes = result.data + extraYearly
                    diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.SUCCESS, dailyMixes.size, account = pass.label))
                    Log.d(TAG, "fetchSpotifyPlaylists: found ${dailyMixes.size} daily mixes")

                    for (mix in dailyMixes) {
                        homeFeedMixIds += mix.id
                        // An earlier account's pass already snapshotted it this sync.
                        if (mix.id in claimedBefore) continue
                        stopIfOutOfTime(pass, run)
                        try {
                            val mixNumber = Regex("""\d+""").find(mix.name)?.value?.toIntOrNull()

                            // Fetch tracks FIRST, as the custom-playlist loop below
                            // does (#343), so the snapshot carries an honest partial
                            // flag. This loop used to insert the snapshot first and
                            // only log a failed fetch, leaving a present-but-empty
                            // mix with partial=false and trackCount 0 (the home feed
                            // often omits the count; yearly recaps never have one) —
                            // which slips past DiffWorker's suspicious-empty guard,
                            // so REFRESH cleared the mix and the orphan sweep could
                            // delete its downloads.
                            // Track fetching uses client_credentials (Prong 1) -- reliable
                            var fetchFailed = false
                            val fetched = when (val tracksResult = spotifyApiClient.getPlaylistTracks(mix.id)) {
                                // A mix with no songs isn't a real answer, and nothing here
                                // could catch a wrong one: the home feed rarely gives a mix a
                                // count, so the diff's listing check sees 0 and would mirror
                                // the mix empty. Kept as it was instead.
                                is SyncResult.Success -> if (tracksResult.data.tracks.isEmpty()) {
                                    fetchFailed = true
                                    Log.w(TAG, "fetchSpotifyPlaylists: no songs read for mix '${mix.name}', kept as it was")
                                    null
                                } else {
                                    tracksResult.data
                                }
                                is SyncResult.Empty -> {
                                    Log.d(TAG, "fetchSpotifyPlaylists: no tracks for '${mix.name}': ${tracksResult.reason}")
                                    null
                                }
                                is SyncResult.Error -> {
                                    if (tracksResult.cause is SpotifyApiException) {
                                        throw tracksResult.cause as SpotifyApiException
                                    }
                                    fetchFailed = true
                                    Log.e(TAG, "fetchSpotifyPlaylists: failed to fetch tracks for '${mix.name}': ${tracksResult.message}")
                                    null
                                }
                            }
                            if (fetched?.partial == true) {
                                Log.w(TAG, "fetchSpotifyPlaylists: partial fetch for '${mix.name}': ${fetched?.partialReason}")
                            }

                            val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                                RemotePlaylistSnapshotEntity(
                                    syncId = syncId,
                                    source = MusicSource.SPOTIFY,
                                    sourcePlaylistId = mix.id,
                                    playlistName = mix.name,
                                    playlistType = PlaylistType.DAILY_MIX,
                                    mixNumber = mixNumber,
                                    trackCount = mix.tracks?.total ?: 0,
                                    artUrl = mix.images?.firstOrNull()?.url,
                                    partial = fetchFailed || fetched?.partial == true,
                                    spotifyAccount = pass.owner,
                                )
                            )

                            if (fetched != null) {
                                val tracks = fetched.tracks
                                val trackSnapshots = tracks.mapIndexedNotNull { index, item ->
                                    val track = item.track ?: return@mapIndexedNotNull null
                                    RemoteTrackSnapshotEntity(
                                        syncId = syncId,
                                        snapshotPlaylistId = playlistSnapshotId,
                                        title = track.name,
                                        artist = track.artists.joinToString(", ") { it.name },
                                        album = track.album?.name,
                                        durationMs = track.duration_ms,
                                        spotifyUri = track.uri,
                                        albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.album?.images?.firstOrNull()?.url),
                                        position = index,
                                        isrc = track.isrc,
                                        explicit = track.explicit,
                                    )
                                }
                                if (trackSnapshots.isNotEmpty()) {
                                    remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                                    dailyMixCount++
                                }
                                Log.d(TAG, "fetchSpotifyPlaylists: saved ${trackSnapshots.size} tracks for '${mix.name}'")
                            }
                        } catch (e: SpotifyApiException) {
                            // Re-throw SpotifyApiException so doWork() can decide to retry
                            throw e
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "fetchSpotifyPlaylists: failed to fetch tracks for mix '${mix.name}'", e)
                            // Continue with next mix rather than aborting
                        }
                        reportPlaylistFetched()
                    }
                }
                is SyncResult.Empty -> {
                    diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.EMPTY, errorMessage = result.reason, account = pass.label))
                    Log.d(TAG, "fetchSpotifyPlaylists: daily mixes empty: ${result.reason}")
                }
                is SyncResult.Error -> {
                    diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.ERROR, errorMessage = result.message, account = pass.label))
                    Log.e(TAG, "fetchSpotifyPlaylists: daily mixes error: ${result.message}")
                }
            }
        } catch (e: SpotifyApiException) {
            throw e // Let doWork handle retries
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "fetchSpotifyPlaylists: daily mixes enumeration failed (sp_dc issue), continuing", e)
            diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.ERROR, errorMessage = e.message, account = pass.label))
        }

        // Fetch Liked Songs with pagination (sp_dc dependent).
        // Spotify returns max 50 per page; we loop until we get all tracks.
        if (!pass.liked) {
            diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.SKIPPED, account = pass.label))
        } else try {
            val allLikedSongs = mutableListOf<com.stash.data.spotify.model.SpotifyTrackItem>()
            var likedOffset = 0
            val likedPageSize = 50
            // #343: an error mid-pagination used to break out and snapshot
            // the truncated pile as if it were the complete library —
            // REFRESH then mirrored Liked Songs DOWN to it. Track the
            // failure so the snapshot admits incompleteness.
            var likedFetchPartial = false
            var likedFetchError: String? = null
            // Raw (pre-filter) items walked and the library size Spotify
            // reports, for the count check after the loop.
            var likedRawWalked = 0
            var likedTotalCount: Int? = null
            var likedPagesFetched = 0

            while (true) {
                stopIfOutOfTime(pass, run)
                // Bounded: if the persisted query ever stopped honouring
                // `offset`, every page would come back full and this loop
                // would never end, growing allLikedSongs without limit.
                if (likedPagesFetched >= LIKED_SONGS_PAGE_CAP) {
                    likedFetchPartial = true
                    Log.w(TAG, "fetchSpotifyPlaylists: liked songs hit the $LIKED_SONGS_PAGE_CAP-page cap, marking partial")
                    break
                }
                when (val result = spotifyApiClient.getLikedSongs(limit = likedPageSize, offset = likedOffset)) {
                    is SyncResult.Success -> {
                        val page = result.data
                        likedPagesFetched++
                        allLikedSongs.addAll(page.tracks)
                        likedRawWalked += page.rawItemCount
                        page.totalCount?.let { likedTotalCount = it }
                        Log.d(
                            TAG,
                            "fetchSpotifyPlaylists: liked songs page offset=$likedOffset, " +
                                "got ${page.tracks.size}/raw=${page.rawItemCount}",
                        )
                        // Page end on the RAW count, not the parsed one: a liked
                        // local file or an unavailable track is dropped by the
                        // parser, and `parsed < pageSize` ended the walk on that
                        // page — mirroring a 1,200-song library down to ~149 in
                        // REFRESH mode. Same fix as libraryV3 and fetchPlaylist.
                        if (page.rawItemCount < likedPageSize) break
                        likedOffset += likedPageSize
                    }
                    is SyncResult.Empty -> break
                    is SyncResult.Error -> {
                        likedFetchPartial = true
                        likedFetchError = result.message
                        Log.e(TAG, "fetchSpotifyPlaylists: liked songs page error at offset=$likedOffset: ${result.message}")
                        break
                    }
                }
            }
            // Backstop for a silent short walk: Spotify returning a short page
            // early, or an empty one mid-library, ends the loop without an
            // Error. Spotify's own count says whether we saw everything.
            if (!likedFetchPartial &&
                com.stash.data.spotify.fetchedBelowExpected(likedRawWalked, likedTotalCount)
            ) {
                likedFetchPartial = true
                Log.w(
                    TAG,
                    "fetchSpotifyPlaylists: liked songs walked $likedRawWalked of $likedTotalCount, marking partial",
                )
            }

            // #410: position IS the Liked Songs order the user sees, and it was
            // just the index the endpoint happened to hand back — fetchLibraryTracks
            // is called without an `order` variable, so that index is not ours to
            // trust. Order by the add date Spotify reports instead; pages that
            // carry no add date come back untouched.
            val orderedLikedSongs = sortLikedSongsByAddedAtDesc(allLikedSongs)

            if (orderedLikedSongs.isNotEmpty()) {
                diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.SUCCESS, orderedLikedSongs.size, account = pass.label))
                Log.d(TAG, "fetchSpotifyPlaylists: found ${orderedLikedSongs.size} liked songs total")

                val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
                    RemotePlaylistSnapshotEntity(
                        syncId = syncId,
                        source = MusicSource.SPOTIFY,
                        // Each account's own row: the main's `spotify_liked_songs`, an extra's `spotify_liked_songs:<userId>`.
                        sourcePlaylistId = pass.likedSongsId,
                        playlistName = "Liked Songs",
                        playlistType = PlaylistType.LIKED_SONGS,
                        trackCount = orderedLikedSongs.size,
                        artUrl = likedPlaylistArtUrl(
                            orderedLikedSongs.asSequence().map {
                                it.track?.album?.images?.firstOrNull()?.url
                            }
                        ),
                        partial = likedFetchPartial,
                        expectedCount = likedTotalCount,
                        spotifyAccount = pass.owner,
                    )
                )

                val trackSnapshots = orderedLikedSongs.mapIndexedNotNull { index, item ->
                    val track = item.track ?: return@mapIndexedNotNull null
                    RemoteTrackSnapshotEntity(
                        syncId = syncId,
                        snapshotPlaylistId = likedPlaylistId,
                        title = track.name,
                        artist = track.artists.joinToString(", ") { it.name },
                        album = track.album?.name,
                        durationMs = track.duration_ms,
                        spotifyUri = track.uri,
                        albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.album?.images?.firstOrNull()?.url),
                        position = index,
                        isrc = track.isrc,
                        explicit = track.explicit,
                    )
                }
                if (trackSnapshots.isNotEmpty()) {
                    remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                    likedSongCount = trackSnapshots.size
                }
            } else if (likedFetchError != null) {
                // Nothing fetched because the request FAILED — report it as the
                // error it is. Recording it EMPTY ("No liked songs found") hid
                // the failure and kept allAttemptedStepsFailed from seeing it.
                diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.ERROR, errorMessage = likedFetchError, account = pass.label))
                Log.e(TAG, "fetchSpotifyPlaylists: liked songs fetch failed: $likedFetchError")
            } else {
                diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.EMPTY, errorMessage = "No liked songs found", account = pass.label))
                Log.d(TAG, "fetchSpotifyPlaylists: liked songs empty after pagination")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "fetchSpotifyPlaylists: liked songs fetch failed (sp_dc issue), continuing", e)
            diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.ERROR, errorMessage = e.message, account = pass.label))
        }
        if (pass.liked) reportPlaylistFetched()

        // Fetch user-created/saved playlists from the Spotify library.
        var userPlaylistCount = 0
        // Whether the library walk listed every playlist: every page and folder read.
        var listingComplete = true
        // Listed playlists whose songs this run couldn't read, id to name. Each is
        // kept as it was and read again next sync, and named in the step, the live
        // log and the History row: "inventory incomplete" on every sync, naming
        // nothing, was all #747's reporters had.
        val unreadPlaylists = linkedMapOf<String, String>()
        if (playlistListing == null) {
            diagnostics.add(SyncStepResult("SPOTIFY", "getUserPlaylists", StepStatus.SKIPPED, account = pass.label))
        } else try {
            // v0.9.21: paginate libraryV3 instead of a single 50-item fetch.
            // The previous single-call path silently capped users at ≤50
            // playlists (often <50 since the parser drops non-Playlist
            // library "feature" rows like LIKED_SONGS / YOUR_EPISODES on
            // page 1). Issue #49: user with hundreds of playlists saw only
            // 46. Loop bound is a 2000-playlist safety cap — at that scale
            // the user likely has bigger problems than missing playlists.
            //
            // Issues #48/#26/#80/#136: libraryV3 is HIERARCHICAL — playlists
            // filed inside folders never appear at the root listing. Each
            // page now also yields folderUris; after the root walk we
            // descend into every folder breadth-first (folders nest, so
            // each folder page can queue more folders). Page-end is
            // detected on rawItemCount, NOT parsed size: a 50-item page
            // dense with folder/pseudo rows parses to few playlists, and
            // the old post-filter "short page" check ended the whole walk
            // right there — losing every playlist after it too.
            val userPlaylists = mutableListOf<com.stash.data.spotify.model.SpotifyPlaylistItem>()
            val pageSize = 50
            val pageCap = 40
            val maxFolderDepth = 5
            var pagesFetched = 0
            var foldersWalked = 0
            val folderQueue = ArrayDeque<Pair<String, Int>>() // folder uri to depth
            val visitedFolders = mutableSetOf<String>()

            suspend fun pageThrough(folderUri: String?, depth: Int): Boolean {
                var offset = 0
                var complete = true
                while (pagesFetched < pageCap) {
                    val page = spotifyApiClient.getUserPlaylists(
                        limit = pageSize,
                        offset = offset,
                        folderUri = folderUri,
                    )
                    // SpotifyApiClient uses this singleton as its failure/invalid-response
                    // sentinel. A genuinely empty parsed library is a distinct instance.
                    currentCoroutineContext().ensureActive()
                    if (page === SpotifyLibraryPage.EMPTY) return false
                    if (!page.isComplete) complete = false
                    pagesFetched++
                    userPlaylists += page.playlists
                    for (uri in page.folderUris) {
                        if (visitedFolders.add(uri)) folderQueue.addLast(uri to depth + 1)
                    }
                    if (page.rawItemCount < pageSize) return complete
                    offset += pageSize
                }
                return false
            }

            listingComplete = pageThrough(folderUri = null, depth = 0)
            while (folderQueue.isNotEmpty() && pagesFetched < pageCap) {
                val (uri, depth) = folderQueue.removeFirst()
                if (depth > maxFolderDepth) {
                    listingComplete = false
                    Log.w(TAG, "fetchSpotifyPlaylists: folder '$uri' beyond depth $maxFolderDepth, skipping")
                    continue
                }
                foldersWalked++
                if (!pageThrough(folderUri = uri, depth = depth)) listingComplete = false
            }
            if (folderQueue.isNotEmpty()) listingComplete = false

            Log.i(
                TAG,
                "fetchSpotifyPlaylists: paged ${userPlaylists.size} playlists across " +
                    "$pagesFetched page(s), $foldersWalked folder(s) descended",
            )
            // What this account listed, pre-filter (see [spotifyLivenessIds]): the joint hide
            // measures "missing" against it, and a later account's pass treats it as claimed.
            playlistListing.ids += spotifyLivenessIds(userPlaylists.map { it.id })
            // Every id listed, whatever becomes of the songs in them below: what another account's
            // rows need of this listing (SpotifyAccountListing.listedAll).
            playlistListing.listedAll = listingComplete
            // Drop only what the home-feed mix pass already covered (#354).
            // distinctBy: defensive — a playlist must not snapshot twice even if
            // Spotify ever lists it both at the root and inside a folder.
            val keptPlaylists = userPlaylists.distinctBy { it.id }.filter { playlist ->
                keepAsLibraryPlaylist(playlist.id, playlist.name, homeFeedMixIds)
            }
            // A playlist an earlier account's pass listed this sync is that account's to snapshot:
            // it still counts as listed here (above), and isn't read again. A playlist two accounts
            // follow is one row, and ownership settles on the account read first (the main).
            val customPlaylists = keptPlaylists.filter { it.id !in claimedBefore }
            val sharedWithEarlier = keptPlaylists.size - customPlaylists.size
            Log.d(TAG, "fetchSpotifyPlaylists: found ${customPlaylists.size} user playlists (filtered from ${userPlaylists.size}, $sharedWithEarlier read by an earlier account)")

            // Skip reading what hasn't changed (see SpotifyMirrorStamp): one
            // read of every stamp, and the mode a re-read would sync in. Either
            // read failing just means every playlist is read, as before
            // skipping existed. A fingerprint is computed per candidate only.
            val mirrorStamps = loadSpotifyMirrorStamps()
            // The sync's one mode, read once for every account: what decides the skips also gates
            // the joint hide. Unreadable: nothing is skipped and nothing is hidden.
            val spotifySyncMode = run.mode
            val appVersion = appVersionCode(applicationContext)
            val skipCheckedAtMs = System.currentTimeMillis()
            var skippedUnchanged = 0

            // Once per playlist, however many ways its read failed.
            fun markUnread(id: String, name: String) {
                if (unreadPlaylists.put(id, name) == null) {
                    syncLog.warn("$name — couldn't read its songs (kept as it was)")
                }
            }

            for (playlist in customPlaylists) {
                // Before anything of this playlist is written: a stop never leaves half a snapshot.
                stopIfOutOfTime(pass, run)
                try {
                    val trustedStamp = mirrorStamps[playlist.id]
                        ?.takeIf { isStampable(playlist.id, PlaylistType.CUSTOM) }
                        ?.takeIf { skipCandidate(playlist.revisionId, it.stamp, spotifySyncMode, skipCheckedAtMs, appVersion) != null }
                        ?.let { row ->
                            // Revision, mode, version and age match: only now the fingerprint.
                            stampToSkipOn(
                                playlist.revisionId, row.stamp, fingerprintOrNull(row.id),
                                spotifySyncMode, skipCheckedAtMs, appVersion,
                            )
                        }
                    if (trustedStamp != null) {
                        // Unchanged on Spotify and here since its last full
                        // sync: no tracks fetched. The row keeps it in this
                        // run's inventory and tells the diff to leave it alone.
                        remoteSnapshotDao.insertPlaylistSnapshot(
                            unchangedSpotifyPlaylistSnapshot(playlist, trustedStamp, syncId)
                                .copy(spotifyAccount = pass.owner),
                        )
                        skippedUnchanged++
                        userPlaylistCount++
                        Log.d(TAG, "fetchSpotifyPlaylists: '${playlist.name}' — unchanged since its last full sync, not re-read")
                        reportPlaylistFetched()
                        continue
                    }

                    // #343: fetch tracks FIRST (like the YouTube paths) so
                    // the snapshot row can carry an HONEST partial flag. The
                    // old order inserted the snapshot, then a failed track
                    // fetch left it present-but-empty with partial=false —
                    // which REFRESH mode mirror-cleared into real data loss.
                    var fetchFailed = false
                    // A walk that ended early (a later GraphQL page failed, or
                    // Spotify's count says items are missing) is not the whole
                    // playlist — snapshot it partial so REFRESH merges instead
                    // of mirroring the playlist down to the pages that loaded.
                    var fetchPartial = false
                    // Spotify's own count confirmed every song was read: only
                    // then may the diff stamp this playlist for a later skip.
                    var countVerified = false
                    // The revision the walk itself started from, to cross-check the listing's.
                    var fetchedRevision: String? = null
                    val fetchedItems = try {
                        when (val tracksResult = spotifyApiClient.getPlaylistTracks(playlist.id)) {
                            is SyncResult.Success -> {
                                if (tracksResult.data.partial) {
                                    fetchPartial = true
                                    Log.w(
                                        TAG,
                                        "fetchSpotifyPlaylists: '${playlist.name}' — partial: " +
                                            "${tracksResult.data.partialReason}",
                                    )
                                }
                                countVerified = tracksResult.data.countVerified
                                fetchedRevision = tracksResult.data.revisionId
                                tracksResult.data.tracks
                            }
                            is SyncResult.Empty -> emptyList()
                            is SyncResult.Error -> {
                                fetchFailed = true
                                markUnread(playlist.id, playlist.name)
                                Log.w(TAG, "fetchSpotifyPlaylists: '${playlist.name}' — error: ${tracksResult.message}")
                                emptyList()
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        fetchFailed = true
                        markUnread(playlist.id, playlist.name)
                        Log.w(TAG, "fetchSpotifyPlaylists: failed to fetch tracks for '${playlist.name}'", e)
                        emptyList()
                    }

                    if (countVerified && playlist.revisionId != null && fetchedRevision != playlist.revisionId) {
                        // Changed between the two reads, or the two ids aren't the same kind
                        // of value (not yet proven on a device): either way, no stamp.
                        Log.d(
                            TAG,
                            "fetchSpotifyPlaylists: '${playlist.id}' not stamped: listing revision " +
                                "${playlist.revisionId} != fetched revision $fetchedRevision",
                        )
                    }
                    val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                        spotifyLibraryPlaylistSnapshot(
                            playlist = playlist,
                            syncId = syncId,
                            partial = fetchFailed || fetchPartial,
                            // The listing's revision, read before this fetch and
                            // confirmed by it: what the diff stamps once the
                            // tracks are mirrored.
                            snapshotId = revisionToRecord(
                                listRevision = playlist.revisionId,
                                countVerified = countVerified && !fetchFailed && !fetchPartial,
                                fetchedRevision = fetchedRevision,
                            ),
                        ).copy(spotifyAccount = pass.owner)
                    )

                    val trackSnapshots = fetchedItems.mapIndexedNotNull { index, item ->
                        val track = item.track ?: return@mapIndexedNotNull null
                        RemoteTrackSnapshotEntity(
                            syncId = syncId,
                            snapshotPlaylistId = playlistSnapshotId,
                            title = track.name,
                            artist = track.artists.joinToString(", ") { it.name },
                            album = track.album?.name,
                            durationMs = track.duration_ms,
                            spotifyUri = track.uri,
                            albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.album?.images?.firstOrNull()?.url),
                            position = index,
                            isrc = track.isrc,
                            explicit = track.explicit,
                        )
                    }
                    if (trackSnapshots.isNotEmpty()) {
                        remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                    }
                    if (!fetchFailed) {
                        userPlaylistCount++
                        Log.d(TAG, "fetchSpotifyPlaylists: '${playlist.name}' — ${trackSnapshots.size} tracks")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    markUnread(playlist.id, playlist.name)
                    Log.w(TAG, "fetchSpotifyPlaylists: snapshot failed for '${playlist.name}'", e)
                }
                reportPlaylistFetched()
            }
            if (skippedUnchanged > 0) {
                Log.i(
                    TAG,
                    "fetchSpotifyPlaylists: skipped $skippedUnchanged Spotify playlist(s) unchanged " +
                        "since the last sync, read ${customPlaylists.size - skippedUnchanged}",
                )
            }

            // Hiding this account's own playlist as gone takes its whole inventory: every page
            // listed, every playlist read. The joint hide, after every account's pass, reads it
            // from the listing.
            val inventoryComplete = listingComplete && unreadPlaylists.isEmpty()
            playlistListing.complete = inventoryComplete
            val notes = listOfNotNull(
                // A shared diagnostics bundle shows how many weren't re-read.
                "$skippedUnchanged unchanged since the last sync, not re-read".takeIf { skippedUnchanged > 0 },
                "$sharedWithEarlier also on an account read before, read there".takeIf { sharedWithEarlier > 0 },
            )
            diagnostics.add(
                SyncStepResult(
                    "SPOTIFY",
                    "getUserPlaylists",
                    if (inventoryComplete) StepStatus.SUCCESS else StepStatus.ERROR,
                    userPlaylistCount,
                    errorMessage = spotifyInventoryProblem(listingComplete, unreadPlaylists.values.toList()),
                    note = notes.joinToString("; ").ifEmpty { null },
                    unreadPlaylists = unreadPlaylists.values.take(SyncStepResult.UNREAD_NAMES_STORED),
                    unreadPlaylistCount = unreadPlaylists.size,
                    account = pass.label,
                )
            )
        } catch (e: SpotifyApiException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "fetchSpotifyPlaylists: user playlists fetch failed", e)
            diagnostics.add(
                SyncStepResult(
                    "SPOTIFY", "getUserPlaylists", StepStatus.ERROR, errorMessage = e.message,
                    unreadPlaylists = unreadPlaylists.values.take(SyncStepResult.UNREAD_NAMES_STORED),
                    unreadPlaylistCount = unreadPlaylists.size,
                    account = pass.label,
                ),
            )
        }

        fetchSpotifySavedAlbums(pass, run, albumListing, claimedBefore, diagnostics)

        Log.d(TAG, "fetchSpotifyPlaylists: completed ${pass.label ?: "main account"} -- $dailyMixCount daily mixes, $likedSongCount liked songs, $userPlaylistCount user playlists")
    }

    /**
     * The saved-albums step (item 5): Library › Albums from the account's saved albums, into
     * [listing] (null: an extra account with the switch off). Its own listing, measured by the joint
     * hide; the sync's one album-reading budget, shared by every account. A failure here costs this
     * step only, never the playlists already fetched, and leaves [listing] incomplete.
     */
    private suspend fun fetchSpotifySavedAlbums(
        pass: SpotifyPass,
        run: SpotifySyncRun,
        listing: SpotifyAccountListing?,
        claimedBefore: Set<String>,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        val fetcher = spotifySavedAlbums ?: return
        if (listing == null) {
            diagnostics.add(SyncStepResult("SPOTIFY", "getSavedAlbums", StepStatus.SKIPPED, account = pass.label))
            return
        }
        try {
            val step = fetcher.fetch(
                syncId = run.syncId,
                syncMode = run.mode,
                listing = listing,
                accountLabel = pass.label,
                claimed = claimedBefore,
                budget = run.albumBudget,
                onAlbumRead = ::reportPlaylistFetched,
                // An extra's albums past the time left wait for the next sync, as past the budget.
                stopReading = if (pass.owner != null) run.outOfTime else NEVER_OUT_OF_TIME,
            )
            // The main account's switch is off: it listed nothing, so it has nothing to measure.
            if (step.status == StepStatus.SKIPPED) run.listings.albums.remove(listing)
            diagnostics.add(step)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchSpotifySavedAlbums: failed", e)
            diagnostics.add(SyncStepResult("SPOTIFY", "getSavedAlbums", StepStatus.ERROR, errorMessage = e.message, account = pass.label))
        }
    }

    /**
     * Stops an extra account's pass ([ExtraAccountOutOfTime]) once a background sync is out of
     * time ([SpotifySyncRun.outOfTime]). Called only between reads, before anything of the next one
     * is written. Never stops the main account's pass, which runs exactly as it always did.
     */
    private fun stopIfOutOfTime(pass: SpotifyPass, run: SpotifySyncRun) {
        if (pass.owner != null && run.outOfTime()) throw ExtraAccountOutOfTime()
    }

    /**
     * [PlaylistDao.mirrorDigest] for a skip candidate, or null when it can't be
     * computed. Null reads the playlist in full, as if nothing were stamped: a
     * failure here must cost the skip, never the playlist's update or this run's
     * inventory.
     */
    private suspend fun fingerprintOrNull(playlistId: Long): String? = try {
        playlistDao.mirrorDigest(playlistId)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "fetchSpotifyPlaylists: couldn't fingerprint playlist $playlistId, reading it in full", e)
        null
    }

    /**
     * Every Spotify playlist carrying a mirror stamp, by Spotify playlist id: the
     * library playlists this sync may skip reading ([skipCandidate],
     * [stampToSkipOn]). Empty when the read fails, so a broken read costs the
     * skip, never a playlist's update.
     */
    private suspend fun loadSpotifyMirrorStamps(): Map<String, com.stash.core.data.db.dao.StampedSpotifyPlaylist> =
        try {
            playlistDao.stampedSpotifyPlaylists().associateBy { it.sourceId }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "fetchSpotifyPlaylists: couldn't read mirror stamps, reading every playlist", e)
            emptyMap()
        }

    /**
     * Fetches Home Mixes, Liked Songs, and user playlists from YouTube Music
     * and writes snapshot rows. Wraps the whole fetch in a single InnerTube
     * auth-cache session so all paginated requests reuse the same cached
     * visitor data / cookies rather than re-fetching them per page.
     *
     * Parallelism is capped by a shared [Semaphore] so no more than
     * [MAX_PARALLEL_YT_FETCHES] browse requests are in-flight at once.
     * Liked Songs runs concurrently with user-playlist discovery and
     * per-playlist fan-out — they are fully independent.
     */
    private suspend fun fetchYouTubePlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>): Boolean {
        youtubeInventoryComplete.set(true)
        innerTubeClient.beginSyncSession()
        val sem = Semaphore(MAX_PARALLEL_YT_FETCHES)
        try {
            Log.d(TAG, "fetchYouTubePlaylists: starting for syncId=$syncId")

            // 1. Discover home mixes (1 serial call — needs the list before fanning out).
            when (val result = ytMusicApiClient.getHomeMixes()) {
                is SyncResult.Success -> {
                    val homeMixes = result.data
                    diagnostics.add(SyncStepResult("YOUTUBE", "getHomeMixes", StepStatus.SUCCESS, homeMixes.size))
                    Log.d(TAG, "fetchYouTubePlaylists: found ${homeMixes.size} home mixes")
                    coroutineScope {
                        homeMixes.mapIndexed { index, mix ->
                            async { sem.withPermit { fetchAndSnapshotMix(mix, index, syncId, diagnostics) } }
                        }.awaitAll()
                    }
                }
                is SyncResult.Empty -> {
                    diagnostics.add(SyncStepResult("YOUTUBE", "getHomeMixes", StepStatus.EMPTY, errorMessage = result.reason))
                    Log.d(TAG, "fetchYouTubePlaylists: home mixes empty: ${result.reason}")
                }
                is SyncResult.Error -> {
                    diagnostics.add(SyncStepResult("YOUTUBE", "getHomeMixes", StepStatus.ERROR, errorMessage = result.message))
                    Log.e(TAG, "fetchYouTubePlaylists: home mixes error: ${result.message}")
                    markYoutubeIncomplete("getHomeMixes: ${result.message}")
                }
            }

            // 2. Liked Songs runs concurrently with user-playlist discovery + per-playlist fan-out.
            coroutineScope {
                val likedDeferred = async { sem.withPermit { fetchAndSnapshotLikedSongs(syncId, diagnostics) } }

                // Fetch user-library playlists (user-created + user-saved from
                // Library → Playlists). These come with `PlaylistType.CUSTOM`
                // and land in the "Other Playlists" toggle group on the Sync
                // screen, alongside Home Mixes / Liked Songs. Built-ins
                // (Liked Music, Episodes for Later) are filtered out by
                // parseUserPlaylists, so no duplicate snapshot rows.
                when (val result = ytMusicApiClient.getUserPlaylists()) {
                    is SyncResult.Success -> {
                        val paged = result.data
                        val userPlaylists = paged.playlists
                        val partialMsg = if (paged.partial) "partial library list: ${paged.partialReason}" else null
                        diagnostics.add(
                            SyncStepResult(
                                "YOUTUBE",
                                "getUserPlaylists",
                                StepStatus.SUCCESS,
                                userPlaylists.size,
                                errorMessage = partialMsg,
                            )
                        )
                        if (paged.partial) {
                            Log.w(TAG, "fetchYouTubePlaylists: user playlists list partial — ${paged.partialReason}")
                            markYoutubeIncomplete("getUserPlaylists partial: ${paged.partialReason}")
                        }
                        Log.d(TAG, "fetchYouTubePlaylists: found ${userPlaylists.size} user playlists")
                        userPlaylists.map { playlist ->
                            async { sem.withPermit { fetchAndSnapshotUserPlaylist(playlist, syncId, diagnostics) } }
                        }.awaitAll()
                    }
                    is SyncResult.Empty -> {
                        diagnostics.add(SyncStepResult("YOUTUBE", "getUserPlaylists", StepStatus.EMPTY, errorMessage = result.reason))
                        Log.d(TAG, "fetchYouTubePlaylists: user playlists empty: ${result.reason}")
                        markIncompleteIfEmptyListingHidesRows("getUserPlaylists", PlaylistType.CUSTOM)
                    }
                    is SyncResult.Error -> {
                        diagnostics.add(SyncStepResult("YOUTUBE", "getUserPlaylists", StepStatus.ERROR, errorMessage = result.message))
                        Log.e(TAG, "fetchYouTubePlaylists: user playlists error: ${result.message}")
                        markYoutubeIncomplete("getUserPlaylists: ${result.message}")
                    }
                }
                val albumsDeferred = async { fetchAndSnapshotSavedAlbums(sem, syncId, diagnostics) }

                if (!likedDeferred.await()) markYoutubeIncomplete("getLikedSongs leg failed")
                albumsDeferred.await()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "YouTube Music fetch failed, continuing with other services", e)
            diagnostics.add(SyncStepResult("YOUTUBE", "fetchYouTubePlaylists", StepStatus.ERROR, errorMessage = e.message))
            markYoutubeIncomplete("top-level exception: ${e.message}")
        } finally {
            innerTubeClient.endSyncSession()
        }
        return youtubeInventoryComplete.get()
    }

    /**
     * Fetches tracks for a single home mix and writes snapshot rows.
     * Own try/catch so one failure does not abort the parallel group.
     */
    private suspend fun fetchAndSnapshotMix(
        mix: YTMusicPlaylist,
        index: Int,
        syncId: Long,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        try {
            // Fetch tracks FIRST so we know partial/expectedCount before inserting snapshot row.
            // Home mixes are radio: cap at the initial page to avoid walking infinite continuations.
            when (val tracksResult = ytMusicApiClient.getPlaylistTracks(mix.playlistId, maxPages = HOME_MIX_MAX_PAGES)) {
                is SyncResult.Success -> {
                    val paged = tracksResult.data
                    val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                        RemotePlaylistSnapshotEntity(
                            syncId = syncId,
                            source = MusicSource.YOUTUBE,
                            sourcePlaylistId = mix.playlistId,
                            playlistName = mix.title,
                            playlistType = PlaylistType.DAILY_MIX,
                            mixNumber = index + 1,
                            trackCount = paged.tracks.size,
                            artUrl = mix.thumbnailUrl,
                            partial = paged.partial,
                            expectedCount = paged.expectedCount,
                        )
                    )
                    val trackSnapshots = paged.tracks.mapIndexed { position, track ->
                        RemoteTrackSnapshotEntity(
                            syncId = syncId,
                            snapshotPlaylistId = playlistSnapshotId,
                            title = track.title,
                            artist = track.artists,
                            album = track.album,
                            durationMs = track.durationMs ?: 0L,
                            youtubeId = track.videoId,
                            albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.thumbnailUrl),
                            position = position,
                            // YouTube's explicit badge: true when the row has it, else unknown.
                            explicit = track.explicit,
                        )
                    }
                    if (trackSnapshots.isNotEmpty()) {
                        remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                    }
                    if (paged.partial) {
                        Log.w(TAG, "fetchAndSnapshotMix: partial fetch for '${mix.title}': ${paged.partialReason}")
                    }
                }
                is SyncResult.Empty -> {
                    Log.d(TAG, "fetchAndSnapshotMix: no tracks for '${mix.title}': ${tracksResult.reason}")
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "fetchAndSnapshotMix: failed to fetch tracks for '${mix.title}': ${tracksResult.message}")
                    markYoutubeIncomplete("mix '${mix.title}' tracks: ${tracksResult.message}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "fetchAndSnapshotMix: exception for '${mix.title}'", e)
            markYoutubeIncomplete("mix '${mix.title}' exception: ${e.message}")
        }
        reportPlaylistFetched()
    }

    /**
     * Fetches YouTube Music Liked Songs and writes snapshot rows.
     * Own try/catch so a failure here does not abort the parallel group.
     */
    private suspend fun fetchAndSnapshotLikedSongs(
        syncId: Long,
        diagnostics: MutableList<SyncStepResult>,
    ): Boolean {
        try {
            when (val result = ytMusicApiClient.getLikedSongs()) {
                is SyncResult.Success -> {
                    val paged = result.data
                    val rawTracks = paged.tracks
                    // Spec: DataStore corruption falls back to "everything syncs" rather than
                    // aborting this entire sync step. runCatching keeps that fallback explicit
                    // even though the outer try/catch would also handle a thrown exception.
                    val studioOnly = runCatching {
                        syncPreferencesManager.youtubeLikedStudioOnly.first()
                    }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
                    val likedSongs = if (studioOnly) filterStudioOnly(rawTracks) else rawTracks
                    val filteredCount = rawTracks.size - likedSongs.size

                    // Build the annotation: partial-fetch reason + filter note (either, both, or neither).
                    val partialNote = if (paged.partial) {
                        "partial: ${rawTracks.size}/${paged.expectedCount ?: "?"} — ${paged.partialReason}"
                    } else null
                    val filterNote = if (studioOnly && filteredCount > 0) {
                        "filtered $filteredCount UGC/podcast tracks (studio-only mode)"
                    } else null
                    val combinedNote = listOfNotNull(partialNote, filterNote).joinToString("; ").ifEmpty { null }

                    diagnostics.add(
                        SyncStepResult(
                            "YOUTUBE",
                            "getLikedSongs",
                            StepStatus.SUCCESS,
                            likedSongs.size,
                            errorMessage = combinedNote,
                        )
                    )
                    if (paged.partial) {
                        Log.w(TAG, "fetchAndSnapshotLikedSongs: liked songs partial — $partialNote")
                    }
                    if (filterNote != null) {
                        Log.d(TAG, "fetchAndSnapshotLikedSongs: $filterNote")
                    }

                    // Empty-after-filter: don't write a phantom playlist row.
                    if (likedSongs.isEmpty()) {
                        Log.d(TAG, "fetchAndSnapshotLikedSongs: all ${rawTracks.size} liked songs filtered (studio-only mode)")
                        reportPlaylistFetched()
                        return true  // filtered-to-empty is a legit outcome, not a fetch failure
                    }

                    val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
                        RemotePlaylistSnapshotEntity(
                            syncId = syncId,
                            source = MusicSource.YOUTUBE,
                            sourcePlaylistId = "youtube_liked_songs",
                            playlistName = "Liked Songs",
                            playlistType = PlaylistType.LIKED_SONGS,
                            trackCount = likedSongs.size,
                            artUrl = likedPlaylistArtUrl(
                                likedSongs.asSequence().map { it.thumbnailUrl }
                            ),
                            partial = paged.partial,
                            expectedCount = paged.expectedCount,
                        )
                    )

                    val trackSnapshots = likedSongs.mapIndexed { position, track ->
                        RemoteTrackSnapshotEntity(
                            syncId = syncId,
                            snapshotPlaylistId = likedPlaylistId,
                            title = track.title,
                            artist = track.artists,
                            album = track.album,
                            durationMs = track.durationMs ?: 0L,
                            youtubeId = track.videoId,
                            albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.thumbnailUrl),
                            position = position,
                            // YouTube's explicit badge: true when the row has it, else unknown.
                            explicit = track.explicit,
                        )
                    }
                    if (trackSnapshots.isNotEmpty()) {
                        remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                    }
                }
                is SyncResult.Empty -> {
                    diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.EMPTY, errorMessage = result.reason))
                    Log.d(TAG, "fetchAndSnapshotLikedSongs: liked songs empty: ${result.reason}")
                    if (markIncompleteIfEmptyListingHidesRows("getLikedSongs", PlaylistType.LIKED_SONGS)) {
                        reportPlaylistFetched()
                        return false
                    }
                }
                is SyncResult.Error -> {
                    diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.ERROR, errorMessage = result.message))
                    Log.e(TAG, "fetchAndSnapshotLikedSongs: liked songs error: ${result.message}")
                    reportPlaylistFetched()
                    return false
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "fetchAndSnapshotLikedSongs: exception", e)
            diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.ERROR, errorMessage = e.message))
            reportPlaylistFetched()
            return false
        }
        reportPlaylistFetched()
        return true
    }

        /**
     * Fetches the user's saved-albums list, then fans out one [getAlbum]
     * call per album to pull its tracklist and snapshot it — same pattern
     * as the home-mix / user-playlist fan-outs above, gated by the same
     * [sem] semaphore via the caller.
     */
    private suspend fun fetchAndSnapshotSavedAlbums(
        sem: Semaphore,
        syncId: Long,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        when (val result = ytMusicApiClient.getSavedAlbums()) {
            is SyncResult.Success -> {
                val paged = result.data
                diagnostics.add(
                    SyncStepResult(
                        "YOUTUBE",
                        "getSavedAlbums",
                        StepStatus.SUCCESS,
                        paged.albums.size,
                        errorMessage = if (paged.partial) paged.partialReason else null,
                    )
                )
                // Flip the shared completeness flag on a real truncation only.
                // It gates deactivateMissingForSource for every YouTube-source
                // playlist this run, saved albums included now that they're
                // typed CUSTOM (#343/#348). paged.partial now comes from
                // paginateBrowse itself — the grid probe that used to stand in
                // for it is gone, because it fired on every paginated library.
                if (paged.partial) markYoutubeIncomplete("getSavedAlbums partial: ${paged.partialReason}")
                Log.d(TAG, "fetchAndSnapshotSavedAlbums: found ${paged.albums.size} saved albums")
                coroutineScope {
                    paged.albums.map { album ->
                        async { sem.withPermit { fetchAndSnapshotSavedAlbum(album, syncId, diagnostics) } }
                    }.awaitAll()
                }
            }
            is SyncResult.Empty -> {
                diagnostics.add(SyncStepResult("YOUTUBE", "getSavedAlbums", StepStatus.EMPTY, errorMessage = result.reason))
                Log.d(TAG, "fetchAndSnapshotSavedAlbums: no saved albums: ${result.reason}")
                markIncompleteIfEmptyListingHidesRows("getSavedAlbums", PlaylistType.CUSTOM, savedAlbums = true)
            }
            is SyncResult.Error -> {
                diagnostics.add(SyncStepResult("YOUTUBE", "getSavedAlbums", StepStatus.ERROR, errorMessage = result.message))
                Log.e(TAG, "fetchAndSnapshotSavedAlbums: error: ${result.message}")
                markYoutubeIncomplete("getSavedAlbums: ${result.message}")
            }
        }
    }

    /**
     * Fetches one saved album's full tracklist via [YTMusicApiClient.getAlbum]
     * (album browseIds don't route through [YTMusicApiClient.getPlaylistTracks])
     * and writes snapshot rows. Own try/catch so one album failing doesn't
     * abort the rest.
     *
     * [getAlbum] never returns null — per its kdoc, a failed/blocked fetch
     * comes back with a blank title, which is treated as a per-album failure
     * here (mirrors the playlist legs' `SyncResult.Error` handling).
     */
    private suspend fun fetchAndSnapshotSavedAlbum(
        album: com.stash.data.ytmusic.model.AlbumSummary,
        syncId: Long,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        try {
            val detail = ytMusicApiClient.getAlbum(album.id)
            if (detail.title.isBlank()) {
                Log.w(TAG, "fetchAndSnapshotSavedAlbum: empty detail for '${album.title}' (${album.id})")
                markYoutubeIncomplete("album '${album.title}' detail fetch failed")
                reportPlaylistFetched()
                return
            }
            val coverUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
                detail.thumbnailUrl ?: album.thumbnailUrl,
            )
            val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                RemotePlaylistSnapshotEntity(
                    syncId = syncId,
                    source = MusicSource.YOUTUBE,
                    sourcePlaylistId = album.id,
                    playlistName = detail.title,
                    // CUSTOM, not a dedicated ALBUM type — see PR discussion.
                    // A saved album is diffed, toggled, and deactivated
                    // through the exact same path as any synced playlist;
                    // no code path needs to distinguish them yet, and adding
                    // a type without a display/filter surface everywhere
                    // PlaylistType is switched on is worse than not adding it.
                    playlistType = PlaylistType.CUSTOM,
                    trackCount = detail.tracks.size,
                    artUrl = coverUrl,
                )
            )
            val trackSnapshots = detail.tracks.mapIndexed { position, track ->
                RemoteTrackSnapshotEntity(
                    syncId = syncId,
                    snapshotPlaylistId = playlistSnapshotId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album ?: detail.title,
                    durationMs = (track.durationSeconds * 1000).toLong(),
                    youtubeId = track.videoId,
                    albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
                        track.thumbnailUrl ?: detail.thumbnailUrl,
                    ),
                    position = position,
                    // YouTube's explicit badge on the album's tracklist row, else unknown.
                    explicit = track.explicit,
                )
            }
            if (trackSnapshots.isNotEmpty()) {
                remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "fetchAndSnapshotSavedAlbum: exception for '${album.title}'", e)
            markYoutubeIncomplete("album '${album.title}' exception: ${e.message}")
        }
        reportPlaylistFetched()
    }

    /**
     * Fetches tracks for a single user playlist and writes snapshot rows.
     * Own try/catch so one failure does not abort the parallel group.
     */
    private suspend fun fetchAndSnapshotUserPlaylist(
        playlist: YTMusicPlaylist,
        syncId: Long,
        diagnostics: MutableList<SyncStepResult>,
    ) {
        try {
            // Fetch tracks FIRST so we know partial/expectedCount before inserting snapshot row.
            when (val tracksResult = ytMusicApiClient.getPlaylistTracks(playlist.playlistId)) {
                is SyncResult.Success -> {
                    val trackedPaged = tracksResult.data
                    val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                        RemotePlaylistSnapshotEntity(
                            syncId = syncId,
                            source = MusicSource.YOUTUBE,
                            sourcePlaylistId = playlist.playlistId,
                            playlistName = playlist.title,
                            playlistType = PlaylistType.CUSTOM,
                            trackCount = trackedPaged.tracks.size,
                            artUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(playlist.thumbnailUrl),
                            partial = trackedPaged.partial,
                            expectedCount = trackedPaged.expectedCount,
                        )
                    )
                    val trackSnapshots = trackedPaged.tracks.mapIndexed { position, track ->
                        RemoteTrackSnapshotEntity(
                            syncId = syncId,
                            snapshotPlaylistId = playlistSnapshotId,
                            title = track.title,
                            artist = track.artists,
                            album = track.album,
                            durationMs = track.durationMs ?: 0L,
                            youtubeId = track.videoId,
                            albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.thumbnailUrl),
                            position = position,
                            // YouTube's explicit badge: true when the row has it, else unknown.
                            explicit = track.explicit,
                        )
                    }
                    if (trackSnapshots.isNotEmpty()) {
                        remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                    }
                    if (trackedPaged.partial) {
                        Log.w(TAG, "fetchAndSnapshotUserPlaylist: partial fetch for '${playlist.title}': ${trackedPaged.partialReason}")
                    }
                }
                is SyncResult.Empty -> {
                    Log.d(TAG, "fetchAndSnapshotUserPlaylist: no tracks for '${playlist.title}': ${tracksResult.reason}")
                    // Still snapshot it: a playlist the library listing returned
                    // but this run wrote no row for is "missing" to DiffWorker,
                    // and REFRESH hid every genuinely empty user playlist on
                    // every sync. See emptyYouTubePlaylistSnapshot for why this
                    // can't mirror-clear a playlist that does have tracks.
                    remoteSnapshotDao.insertPlaylistSnapshot(emptyYouTubePlaylistSnapshot(playlist, syncId))
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "fetchAndSnapshotUserPlaylist: failed tracks for '${playlist.title}': ${tracksResult.message}")
                    markYoutubeIncomplete("playlist '${playlist.title}' tracks: ${tracksResult.message}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "fetchAndSnapshotUserPlaylist: exception for '${playlist.title}'", e)
            markYoutubeIncomplete("playlist '${playlist.title}' exception: ${e.message}")
        }
        reportPlaylistFetched()
    }
}

/**
 * Whose library one Spotify sync pass reads, and which kinds of it (plan 2026-11-04 §3.3).
 *
 * @property owner What the pass's snapshots are tagged with (`spotify_account`): null for the main
 *   account, an extra's Spotify user id as its `spotify_accounts` row stores it otherwise.
 * @property label The account's diagnostics label ([spotifyExtraAccountLabel]); null for the main.
 * @property likedSongsId The account's own Liked Songs row ([SpotifyPlaylistIds]).
 * @property albums For the main account, "ask the saved-albums step", which applies the main's own
 *   Sync setting; for an extra, its row's switch.
 */
internal class SpotifyPass(
    val owner: String?,
    val label: String?,
    val likedSongsId: String,
    val mixes: Boolean,
    val liked: Boolean,
    val playlists: Boolean,
    val albums: Boolean,
) {
    companion object {
        /** The main account: every kind, its mixes as the Sync setting says. */
        fun main(discoverMixes: Boolean) = SpotifyPass(
            owner = null,
            label = null,
            likedSongsId = SpotifyPlaylistIds.MAIN_LIKED_SONGS,
            mixes = discoverMixes,
            liked = true,
            playlists = true,
            albums = true,
        )

        /**
         * An extra account, as its row's switches say. Its user id is used exactly as the row stores
         * it, through [SpotifyPlaylistIds.accountTag] (which refuses one that isn't normalised).
         */
        fun extra(row: com.stash.core.data.db.entity.SpotifyAccountEntity) = SpotifyPass(
            owner = SpotifyPlaylistIds.accountTag(row.userId),
            label = spotifyExtraAccountLabel(row.id),
            likedSongsId = SpotifyPlaylistIds.extraLikedSongs(row.userId),
            mixes = row.syncMixes,
            liked = row.syncLiked,
            playlists = row.syncPlaylists,
            albums = row.syncAlbums,
        )
    }
}

/**
 * What one sync's Spotify passes share: the one sync mode, the ids claimed so far (listed by an
 * earlier pass: a later pass counts them as listed and doesn't snapshot them again), every
 * listing for the joint hide, the one album-reading budget, and each account's label by owner.
 *
 * @property extraTags Every extra account's tag, read this sync or not: a row tagged with one of
 *   them has an account behind it (any other tag is an orphan's).
 */
internal class SpotifySyncRun(
    val syncId: Long,
    val mode: SyncMode?,
    val extraTags: Set<String> = emptySet(),
    /** Whether a background sync is out of time for extra accounts ([PlaylistFetchWorker.EXTRA_ACCOUNTS_BACKGROUND_BUDGET_MS]). */
    val outOfTime: () -> Boolean = NEVER_OUT_OF_TIME,
) {
    val claimed: MutableSet<String> = HashSet()
    val listings = SpotifySyncListings()

    /** The kinds ("playlists", "albums") whose hide an extra held back this sync (PlaylistFetchWorker.holdBackHides). */
    val hidesHeldBack: MutableSet<String> = LinkedHashSet()
    val albumBudget = SpotifySavedAlbumsFetcher.ReadBudget()
    val labels: MutableMap<String?, String?> = HashMap()
}

/** A sync with no time limit for extra accounts (the foreground, and the main account's pass). */
private val NEVER_OUT_OF_TIME: () -> Boolean = { false }

/**
 * An extra account's pass stopped because a background sync is out of time
 * ([PlaylistFetchWorker.EXTRA_ACCOUNTS_BACKGROUND_BUDGET_MS]). A [CancellationException], like
 * kotlinx's own timeout, so every catch-all in the pass lets it through to the pass's caller,
 * which catches it by type. It never leaves the pass: the sync itself carries on.
 */
private class ExtraAccountOutOfTime : CancellationException("out of time for extra Spotify accounts in a background sync")

/**
 * [message] with every mention of the account id [userId] masked, for a step an extra account's
 * failure writes: Sync History and diagnostics name an account by its label only.
 */
internal fun withoutAccountId(message: String, userId: String): String =
    if (userId.isBlank()) message else message.replace(userId, "<account>", ignoreCase = true)

/**
 * The snapshot row for a YouTube library playlist whose track fetch came back
 * Empty. Written so the playlist stays in this run's inventory (otherwise
 * REFRESH hides every genuinely empty playlist), and shaped so DiffWorker can
 * never mirror-clear real tracks off the back of it:
 *  - the listing said N > 0 tracks: trackCount = N with zero track rows is
 *    DiffWorker's `suspiciousEmpty` shape, so local tracks are kept;
 *  - the listing said 0: genuinely empty, mirrored as such;
 *  - the listing gave no count: we can't tell an empty playlist from a failed
 *    browse, so it is marked partial (kept, nothing cleared).
 */
internal fun emptyYouTubePlaylistSnapshot(
    playlist: YTMusicPlaylist,
    syncId: Long,
): RemotePlaylistSnapshotEntity = RemotePlaylistSnapshotEntity(
    syncId = syncId,
    source = MusicSource.YOUTUBE,
    sourcePlaylistId = playlist.playlistId,
    playlistName = playlist.title,
    playlistType = PlaylistType.CUSTOM,
    trackCount = playlist.trackCount ?: 0,
    artUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(playlist.thumbnailUrl),
    partial = playlist.trackCount == null,
    expectedCount = playlist.trackCount,
)

/**
 * Filters out UGC and PODCAST_EPISODE tracks. Used by the
 * "Studio recordings only" Liked Songs preference.
 *
 * Tracks with [MusicVideoType.ATV], [MusicVideoType.OMV],
 * [MusicVideoType.OFFICIAL_SOURCE_MUSIC], or `null` (parser-couldn't-classify)
 * pass through. Null is preserved deliberately so a parser regression
 * doesn't silently drop legitimate studio tracks.
 */
internal fun filterStudioOnly(tracks: List<YTMusicTrack>): List<YTMusicTrack> =
    tracks.filter {
        it.musicVideoType != MusicVideoType.UGC &&
        it.musicVideoType != MusicVideoType.PODCAST_EPISODE
    }

/**
 * Outcome of running the two [com.stash.core.data.sync.auth.AuthHealthProbe]
 * implementations at the head of a sync run.
 *
 * @property state Per-source expiry flags to write to
 *  [SyncStateManager.onAuthExpiryProbed]. Drives the AuthExpiredBanner UI.
 * @property fetchSpotify `true` when the Spotify fetch should run — i.e.
 *  Spotify is connected AND its probe did not report expired.
 * @property fetchYoutube `true` when the YouTube fetch should run — same
 *  rule for YouTube.
 *
 * [shortCircuit] is DERIVED from these: the worker aborts only when there is
 * nothing left to fetch (every connected source expired). When just one of
 * several sources is expired the chain proceeds and the worker skips only
 * that source — this is intentionally NOT `state.anyExpired`. The fetch flags
 * are the single source of truth shared by the short-circuit decision and the
 * per-source fetch gate in [PlaylistFetchWorker.doWork], so the two can never
 * disagree about which sources are usable.
 */
internal data class AuthProbeResult(
    val state: AuthExpiryState,
    val fetchSpotify: Boolean,
    val fetchYoutube: Boolean,
) {
    val shortCircuit: Boolean get() = !fetchSpotify && !fetchYoutube
}

/**
 * Runs [spotifyProbe] and [youtubeProbe] in parallel, but only for sources
 * the caller reports as connected. Skipping disconnected sources is critical
 * UX — a user who never linked YouTube must never see a "YouTube expired"
 * banner just because the probe network call would fail.
 *
 * [spotifyConnected]/[youtubeConnected] MUST be the same `isAuthenticated()`
 * values [PlaylistFetchWorker.doWork] uses to gate the actual fetch, so the
 * "is this source usable" predicate is identical for the short-circuit
 * decision and the fetch — they cannot drift to two different signals.
 *
 * Extracted from [PlaylistFetchWorker.doWork] so the orchestration can be
 * unit-tested without spinning up a HiltWorker/WorkerParameters harness for
 * an @AssistedInject worker. See [PlaylistFetchWorkerAuthProbeTest].
 */
internal suspend fun runAuthProbes(
    spotifyConnected: Boolean,
    youtubeConnected: Boolean,
    spotifyProbe: SpotifyAuthHealthProbe,
    youtubeProbe: YoutubeAuthHealthProbe,
): AuthProbeResult = coroutineScope {
    val spotifyDeferred = async {
        if (spotifyConnected) spotifyProbe.isExpired() else false
    }
    val youtubeDeferred = async {
        if (youtubeConnected) youtubeProbe.isExpired() else false
    }

    val state = AuthExpiryState(
        spotifyExpired = spotifyDeferred.await(),
        youtubeExpired = youtubeDeferred.await(),
    )
    // Per-source gating: a source is fetchable only when it's connected AND not
    // expired. shortCircuit (derived) is true only when NEITHER is fetchable,
    // so a single expired source can't nuke a healthy one — the per-source
    // banner still surfaces from `state`. This is what stops a YouTube
    // false-positive from killing an otherwise-fine Spotify sync.
    AuthProbeResult(
        state = state,
        fetchSpotify = spotifyConnected && !state.spotifyExpired,
        fetchYoutube = youtubeConnected && !state.youtubeExpired,
    )
}
