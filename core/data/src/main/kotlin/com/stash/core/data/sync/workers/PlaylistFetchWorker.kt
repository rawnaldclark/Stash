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
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.StepStatus
import com.stash.core.model.SyncResult
import com.stash.core.model.SyncState
import com.stash.core.model.SyncStepResult
import com.stash.core.model.SyncTrigger
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyApiException
import com.stash.data.ytmusic.YTMusicApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

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
    private val syncHistoryDao: SyncHistoryDao,
    private val remoteSnapshotDao: RemoteSnapshotDao,
    private val syncStateManager: SyncStateManager,
    private val syncNotificationManager: SyncNotificationManager,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        private const val TAG = "StashSync"
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
            startedAt = Instant.now(),
        )
        val syncId = syncHistoryDao.insert(syncEntry)
        val diagnostics = mutableListOf<SyncStepResult>()

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

            // Step 3: Transition to fetching playlists.
            syncStateManager.onFetchingPlaylists()
            syncHistoryDao.updateStatus(syncId, SyncState.FETCHING_PLAYLISTS)

            // Step 4: Fetch Spotify playlists and tracks.
            if (isSpotifyAuthenticated) {
                fetchSpotifyPlaylists(syncId, diagnostics)
            }

            // Step 5: Fetch YouTube Music playlists and tracks.
            if (isYouTubeAuthenticated) {
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

        Log.d(TAG, "fetchSpotifyPlaylists: completed -- $dailyMixCount daily mixes, $likedSongCount liked songs")
    }

    /**
     * Fetches Home Mixes and Liked Songs from YouTube Music and writes snapshot rows.
     */
    private suspend fun fetchYouTubePlaylists(syncId: Long, diagnostics: MutableList<SyncStepResult>) {
        try {
            Log.d(TAG, "fetchYouTubePlaylists: starting for syncId=$syncId")

            // Fetch Home Mixes.
            when (val result = ytMusicApiClient.getHomeMixes()) {
                is SyncResult.Success -> {
                    val homeMixes = result.data
                    diagnostics.add(SyncStepResult("YOUTUBE", "getHomeMixes", StepStatus.SUCCESS, homeMixes.size))
                    Log.d(TAG, "fetchYouTubePlaylists: found ${homeMixes.size} home mixes")

                    for ((index, mix) in homeMixes.withIndex()) {
                        val playlistSnapshotId = remoteSnapshotDao.insertPlaylistSnapshot(
                            RemotePlaylistSnapshotEntity(
                                syncId = syncId,
                                source = MusicSource.YOUTUBE,
                                sourcePlaylistId = mix.playlistId,
                                playlistName = mix.title,
                                playlistType = PlaylistType.DAILY_MIX,
                                mixNumber = index + 1,
                                trackCount = mix.trackCount ?: 0,
                                artUrl = mix.thumbnailUrl,
                            )
                        )

                        when (val tracksResult = ytMusicApiClient.getPlaylistTracks(mix.playlistId)) {
                            is SyncResult.Success -> {
                                val tracks = tracksResult.data
                                val trackSnapshots = tracks.mapIndexed { position, track ->
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
                            }
                            is SyncResult.Empty -> {
                                Log.d(TAG, "fetchYouTubePlaylists: no tracks for '${mix.title}': ${tracksResult.reason}")
                            }
                            is SyncResult.Error -> {
                                Log.e(TAG, "fetchYouTubePlaylists: failed to fetch tracks for '${mix.title}': ${tracksResult.message}")
                            }
                        }
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

            // Fetch Liked Songs.
            when (val result = ytMusicApiClient.getLikedSongs()) {
                is SyncResult.Success -> {
                    val likedSongs = result.data
                    diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.SUCCESS, likedSongs.size))

                    val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
                        RemotePlaylistSnapshotEntity(
                            syncId = syncId,
                            source = MusicSource.YOUTUBE,
                            sourcePlaylistId = "youtube_liked_songs",
                            playlistName = "Liked Songs",
                            playlistType = PlaylistType.LIKED_SONGS,
                            trackCount = likedSongs.size,
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
                    Log.d(TAG, "fetchYouTubePlaylists: liked songs empty: ${result.reason}")
                }
                is SyncResult.Error -> {
                    diagnostics.add(SyncStepResult("YOUTUBE", "getLikedSongs", StepStatus.ERROR, errorMessage = result.message))
                    Log.e(TAG, "fetchYouTubePlaylists: liked songs error: ${result.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube Music fetch failed, continuing with other services", e)
            diagnostics.add(SyncStepResult("YOUTUBE", "fetchYouTubePlaylists", StepStatus.ERROR, errorMessage = e.message))
        }
    }
}
