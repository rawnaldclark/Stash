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
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.sync.AuthExpiryState
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.auth.SpotifyAuthHealthProbe
import com.stash.core.data.sync.auth.YoutubeAuthHealthProbe
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.StepStatus
import com.stash.core.model.SyncResult
import com.stash.core.model.SyncState
import com.stash.core.model.SyncStepResult
import com.stash.core.model.SyncTrigger
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyApiException
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.MusicVideoType
import com.stash.data.ytmusic.model.YTMusicPlaylist
import com.stash.data.ytmusic.model.YTMusicTrack
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

internal fun likedPlaylistArtUrl(trackArtUrls: Sequence<String?>): String? =
    com.stash.core.common.ArtUrlUpgrader.upgrade(
        trackArtUrls.firstOrNull { !it.isNullOrBlank() }
    )

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
    private val syncHistoryDao: SyncHistoryDao,
    private val remoteSnapshotDao: RemoteSnapshotDao,
    private val syncStateManager: SyncStateManager,
    private val syncNotificationManager: SyncNotificationManager,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val spotifyAuthHealthProbe: SpotifyAuthHealthProbe,
    private val youtubeAuthHealthProbe: YoutubeAuthHealthProbe,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        private const val TAG = "StashSync"
        /** Cap on concurrent in-flight YT browse calls during a sync. */
        private const val MAX_PARALLEL_YT_FETCHES = 3

        // Home Mixes (Daily Mix etc.) are infinite radio — every page returns
        // another continuation token. Cap to the initial page (~100 tracks)
        // so a sync doesn't spend minutes walking radio. User playlists +
        // Liked Songs use the full MAX_PAGES default in YTMusicApiClient.
        private const val HOME_MIX_MAX_PAGES = 1

        // Shared reason string for auth-expiry short-circuit so the
        // sync_history FAILED row and the SyncStateManager onError call
        // can't drift out of sync.
        private const val AUTH_EXPIRED_REASON = "Credentials expired — re-authenticate to resume sync"
    }

    /**
     * Called by WorkManager BEFORE [doWork] runs. Promotes the worker to a
     * foreground service with a "Fetching playlists…" notification so the
     * system doesn't kill it during long fetches on huge libraries (3000+
     * liked songs can take more than 10 minutes of paginated API calls).
     *
     * See [TrackDownloadWorker.getForegroundInfo] for background on why
     * this override is necessary rather than calling setForeground() inside
     * doWork().
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

    override suspend fun doWork(): Result {
        // Step 1: Create a sync history record.
        val syncEntry = SyncHistoryEntity(
            status = SyncState.AUTHENTICATING,
            trigger = SyncTrigger.MANUAL,
            streamingMode = streamingPreference.current(),
            startedAt = Instant.now(),
        )
        val syncId = syncHistoryDao.insert(syncEntry)
        val diagnostics = java.util.Collections.synchronizedList(mutableListOf<SyncStepResult>())

        try {
            // Step 2: Authenticating phase.
            syncStateManager.onAuthenticating()

            val isSpotifyAuthenticated = tokenManager.isAuthenticated(AuthService.SPOTIFY)
            val isYouTubeAuthenticated = tokenManager.isAuthenticated(AuthService.YOUTUBE_MUSIC)

            if (!isSpotifyAuthenticated && !isYouTubeAuthenticated) {
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
            if (probeResult.shortCircuit) {
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

            // Step 3: Transition to fetching playlists.
            syncStateManager.onFetchingPlaylists()
            syncHistoryDao.updateStatus(syncId, SyncState.FETCHING_PLAYLISTS)

            // Step 4 & 5: Fetch each source the probe deemed usable. The
            // fetch flags fold in both "connected" and "not expired", and are
            // the SAME values that drove the short-circuit decision above — so
            // a source skipped here is exactly a source that didn't count
            // toward keeping the sync alive.
            if (probeResult.fetchSpotify) {
                fetchSpotifyPlaylists(syncId, diagnostics)
            }
            if (probeResult.fetchYoutube) {
                fetchYouTubePlaylists(syncId, diagnostics)
            }

            // Persist diagnostics.
            syncHistoryDao.updateDiagnostics(syncId, Json.encodeToString(diagnostics.toList()))

            // If ALL diagnostics entries errored, the sync produced no data -- fail.
            if (diagnostics.isNotEmpty() && diagnostics.all { it.status == StepStatus.ERROR }) {
                val summary = diagnostics.joinToString("; ") { "${it.service}/${it.step}: ${it.errorMessage}" }
                syncHistoryDao.updateStatus(
                    id = syncId,
                    status = SyncState.FAILED,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = "All API calls failed: $summary",
                )
                syncStateManager.onError("All API calls failed")
                return Result.failure(workDataOf(KEY_SYNC_ID to syncId))
            }

            return Result.success(workDataOf(KEY_SYNC_ID to syncId))
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
     * Fetches Daily Mixes and Liked Songs from Spotify and writes snapshot rows.
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
    private suspend fun fetchSpotifyPlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
        Log.d(TAG, "fetchSpotifyPlaylists: starting for syncId=$syncId")
        var dailyMixCount = 0
        var likedSongCount = 0

        // Fetch Daily Mixes (sp_dc dependent -- may return empty if GraphQL fails).
        try {
            when (val result = spotifyApiClient.getDailyMixes()) {
                is SyncResult.Success -> {
                    val dailyMixes = result.data
                    diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.SUCCESS, dailyMixes.size))
                    Log.d(TAG, "fetchSpotifyPlaylists: found ${dailyMixes.size} daily mixes")

                    for (mix in dailyMixes) {
                        try {
                            val mixNumber = Regex("""\d+""").find(mix.name)?.value?.toIntOrNull()
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
                                )
                            )

                            // Track fetching uses client_credentials (Prong 1) -- reliable
                            when (val tracksResult = spotifyApiClient.getPlaylistTracks(mix.id)) {
                                is SyncResult.Success -> {
                                    val tracks = tracksResult.data
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
                                is SyncResult.Empty -> {
                                    Log.d(TAG, "fetchSpotifyPlaylists: no tracks for '${mix.name}': ${tracksResult.reason}")
                                }
                                is SyncResult.Error -> {
                                    if (tracksResult.cause is SpotifyApiException) {
                                        throw tracksResult.cause as SpotifyApiException
                                    }
                                    Log.e(TAG, "fetchSpotifyPlaylists: failed to fetch tracks for '${mix.name}': ${tracksResult.message}")
                                }
                            }
                        } catch (e: SpotifyApiException) {
                            // Re-throw SpotifyApiException so doWork() can decide to retry
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "fetchSpotifyPlaylists: failed to fetch tracks for mix '${mix.name}'", e)
                            // Continue with next mix rather than aborting
                        }
                    }
                }
                is SyncResult.Empty -> {
                    diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.EMPTY, errorMessage = result.reason))
                    Log.d(TAG, "fetchSpotifyPlaylists: daily mixes empty: ${result.reason}")
                }
                is SyncResult.Error -> {
                    diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.ERROR, errorMessage = result.message))
                    Log.e(TAG, "fetchSpotifyPlaylists: daily mixes error: ${result.message}")
                }
            }
        } catch (e: SpotifyApiException) {
            throw e // Let doWork handle retries
        } catch (e: Exception) {
            Log.e(TAG, "fetchSpotifyPlaylists: daily mixes enumeration failed (sp_dc issue), continuing", e)
            diagnostics.add(SyncStepResult("SPOTIFY", "getDailyMixes", StepStatus.ERROR, errorMessage = e.message))
        }

        // Fetch Liked Songs with pagination (sp_dc dependent).
        // Spotify returns max 50 per page; we loop until we get all tracks.
        try {
            val allLikedSongs = mutableListOf<com.stash.data.spotify.model.SpotifyTrackItem>()
            var likedOffset = 0
            val likedPageSize = 50

            while (true) {
                when (val result = spotifyApiClient.getLikedSongs(limit = likedPageSize, offset = likedOffset)) {
                    is SyncResult.Success -> {
                        allLikedSongs.addAll(result.data)
                        Log.d(TAG, "fetchSpotifyPlaylists: liked songs page offset=$likedOffset, got ${result.data.size}")
                        if (result.data.size < likedPageSize) break
                        likedOffset += likedPageSize
                    }
                    is SyncResult.Empty -> break
                    is SyncResult.Error -> {
                        Log.e(TAG, "fetchSpotifyPlaylists: liked songs page error at offset=$likedOffset: ${result.message}")
                        break
                    }
                }
            }

            if (allLikedSongs.isNotEmpty()) {
                diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.SUCCESS, allLikedSongs.size))
                Log.d(TAG, "fetchSpotifyPlaylists: found ${allLikedSongs.size} liked songs total")

                val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
                    RemotePlaylistSnapshotEntity(
                        syncId = syncId,
                        source = MusicSource.SPOTIFY,
                        sourcePlaylistId = "spotify_liked_songs",
                        playlistName = "Liked Songs",
                        playlistType = PlaylistType.LIKED_SONGS,
                        trackCount = allLikedSongs.size,
                        artUrl = likedPlaylistArtUrl(
                            allLikedSongs.asSequence().map {
                                it.track?.album?.images?.firstOrNull()?.url
                            }
                        ),
                    )
                )

                val trackSnapshots = allLikedSongs.mapIndexedNotNull { index, item ->
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
            } else {
                diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.EMPTY, errorMessage = "No liked songs found"))
                Log.d(TAG, "fetchSpotifyPlaylists: liked songs empty after pagination")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSpotifyPlaylists: liked songs fetch failed (sp_dc issue), continuing", e)
            diagnostics.add(SyncStepResult("SPOTIFY", "getLikedSongs", StepStatus.ERROR, errorMessage = e.message))
        }

        // Fetch user-created/saved playlists from the Spotify library.
        var userPlaylistCount = 0
        try {
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

            suspend fun pageThrough(folderUri: String?, depth: Int) {
                var offset = 0
                while (pagesFetched < pageCap) {
                    val page = spotifyApiClient.getUserPlaylists(
                        limit = pageSize,
                        offset = offset,
                        folderUri = folderUri,
                    )
                    pagesFetched++
                    userPlaylists += page.playlists
                    for (uri in page.folderUris) {
                        if (visitedFolders.add(uri)) folderQueue.addLast(uri to depth + 1)
                    }
                    if (page.rawItemCount < pageSize) break // true last page
                    offset += pageSize
                }
            }

            pageThrough(folderUri = null, depth = 0)
            while (folderQueue.isNotEmpty() && pagesFetched < pageCap) {
                val (uri, depth) = folderQueue.removeFirst()
                if (depth > maxFolderDepth) {
                    Log.w(TAG, "fetchSpotifyPlaylists: folder '$uri' beyond depth $maxFolderDepth, skipping")
                    continue
                }
                foldersWalked++
                pageThrough(folderUri = uri, depth = depth)
            }

            Log.i(
                TAG,
                "fetchSpotifyPlaylists: paged ${userPlaylists.size} playlists across " +
                    "$pagesFetched page(s), $foldersWalked folder(s) descended",
            )
            // Filter out daily mixes (already handled above) and any Spotify-owned playlists.
            // distinctBy: defensive — a playlist must not snapshot twice even if
            // Spotify ever lists it both at the root and inside a folder.
            val customPlaylists = userPlaylists.distinctBy { it.id }.filter { playlist ->
                !playlist.owner.id.equals("spotify", ignoreCase = true) &&
                    !playlist.name.matches(Regex("""Daily Mix \d+"""))
            }
            Log.d(TAG, "fetchSpotifyPlaylists: found ${customPlaylists.size} user playlists (filtered from ${userPlaylists.size})")

            for (playlist in customPlaylists) {
                try {
                    val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                        RemotePlaylistSnapshotEntity(
                            syncId = syncId,
                            source = MusicSource.SPOTIFY,
                            sourcePlaylistId = playlist.id,
                            playlistName = playlist.name,
                            playlistType = PlaylistType.CUSTOM,
                            trackCount = playlist.tracks?.total ?: 0,
                            artUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
                                playlist.images?.firstOrNull()?.url,
                            ),
                        )
                    )

                    when (val tracksResult = spotifyApiClient.getPlaylistTracks(playlist.id)) {
                        is SyncResult.Success -> {
                            val trackSnapshots = tracksResult.data.mapIndexedNotNull { index, item ->
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
                            userPlaylistCount++
                            Log.d(TAG, "fetchSpotifyPlaylists: '${playlist.name}' — ${trackSnapshots.size} tracks")
                        }
                        is SyncResult.Empty -> {
                            Log.d(TAG, "fetchSpotifyPlaylists: '${playlist.name}' — empty")
                        }
                        is SyncResult.Error -> {
                            Log.w(TAG, "fetchSpotifyPlaylists: '${playlist.name}' — error: ${tracksResult.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetchSpotifyPlaylists: failed to fetch tracks for '${playlist.name}'", e)
                }
            }
            diagnostics.add(SyncStepResult("SPOTIFY", "getUserPlaylists", StepStatus.SUCCESS, userPlaylistCount))
        } catch (e: SpotifyApiException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchSpotifyPlaylists: user playlists fetch failed", e)
            diagnostics.add(SyncStepResult("SPOTIFY", "getUserPlaylists", StepStatus.ERROR, errorMessage = e.message))
        }

        Log.d(TAG, "fetchSpotifyPlaylists: completed -- $dailyMixCount daily mixes, $likedSongCount liked songs, $userPlaylistCount user playlists")
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
    private suspend fun fetchYouTubePlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
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
                }
            }

            // 2. Liked Songs runs concurrently with user-playlist discovery + per-playlist fan-out.
            coroutineScope {
                launch { sem.withPermit { fetchAndSnapshotLikedSongs(syncId, diagnostics) } }

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
                        }
                        Log.d(TAG, "fetchYouTubePlaylists: found ${userPlaylists.size} user playlists")
                        userPlaylists.map { playlist ->
                            async { sem.withPermit { fetchAndSnapshotUserPlaylist(playlist, syncId, diagnostics) } }
                        }.awaitAll()
                    }
                    is SyncResult.Empty -> {
                        diagnostics.add(SyncStepResult("YOUTUBE", "getUserPlaylists", StepStatus.EMPTY, errorMessage = result.reason))
                        Log.d(TAG, "fetchYouTubePlaylists: user playlists empty: ${result.reason}")
                    }
                    is SyncResult.Error -> {
                        diagnostics.add(SyncStepResult("YOUTUBE", "getUserPlaylists", StepStatus.ERROR, errorMessage = result.message))
                        Log.e(TAG, "fetchYouTubePlaylists: user playlists error: ${result.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube Music fetch failed, continuing with other services", e)
            diagnostics.add(SyncStepResult("YOUTUBE", "fetchYouTubePlaylists", StepStatus.ERROR, errorMessage = e.message))
        } finally {
            innerTubeClient.endSyncSession()
        }
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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndSnapshotMix: exception for '${mix.title}'", e)
        }
    }

    /**
     * Fetches YouTube Music Liked Songs and writes snapshot rows.
     * Own try/catch so a failure here does not abort the parallel group.
     */
    private suspend fun fetchAndSnapshotLikedSongs(
        syncId: Long,
        diagnostics: MutableList<SyncStepResult>,
    ) {
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
                    }.getOrDefault(false)
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
                        return  // exits the inner block; the outer try/catch around fetchAndSnapshotLikedSongs is unaffected
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
                        )
                    }
                    if (trackSnapshots.isNotEmpty()) {
                        remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                    }
                }
                is SyncResult.Empty -> {
                    diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.EMPTY, errorMessage = result.reason))
                    Log.d(TAG, "fetchAndSnapshotLikedSongs: liked songs empty: ${result.reason}")
                }
                is SyncResult.Error -> {
                    diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.ERROR, errorMessage = result.message))
                    Log.e(TAG, "fetchAndSnapshotLikedSongs: liked songs error: ${result.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndSnapshotLikedSongs: exception", e)
            diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.ERROR, errorMessage = e.message))
        }
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
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "fetchAndSnapshotUserPlaylist: failed tracks for '${playlist.title}': ${tracksResult.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndSnapshotUserPlaylist: exception for '${playlist.title}'", e)
        }
    }
}

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
