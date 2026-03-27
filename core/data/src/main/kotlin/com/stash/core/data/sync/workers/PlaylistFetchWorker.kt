package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncState
import com.stash.core.model.SyncTrigger
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyApiException
import com.stash.data.ytmusic.YTMusicApiClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SYNC_ID = "sync_id"
        private const val TAG = "StashSync"
    }

    override suspend fun doWork(): Result {
        // Step 1: Create a sync history record.
        val syncEntry = SyncHistoryEntity(
            status = SyncState.AUTHENTICATING,
            trigger = SyncTrigger.MANUAL,
            startedAt = Instant.now(),
        )
        val syncId = syncHistoryDao.insert(syncEntry)

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
                fetchSpotifyPlaylists(syncId)
            }

            // Step 5: Fetch YouTube Music playlists and tracks.
            if (isYouTubeAuthenticated) {
                fetchYouTubePlaylists(syncId)
            }

            return Result.success(workDataOf(KEY_SYNC_ID to syncId))
        } catch (e: SpotifyApiException) {
            // Transient Spotify API failure (e.g. 429 rate limit exhausted) --
            // ask WorkManager to schedule a retry so the sync can succeed later.
            Log.w(TAG, "Spotify API error (HTTP ${e.httpCode}), scheduling retry", e)
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
    private suspend fun fetchSpotifyPlaylists(syncId: Long) {
        Log.d(TAG, "fetchSpotifyPlaylists: starting for syncId=$syncId")
        var dailyMixCount = 0
        var likedSongCount = 0

        // Fetch Daily Mixes (sp_dc dependent -- may return empty if GraphQL fails).
        try {
            val dailyMixes = spotifyApiClient.getDailyMixes()
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
                    val tracks = spotifyApiClient.getPlaylistTracks(mix.id)
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
                            albumArtUrl = track.album?.images?.firstOrNull()?.url,
                            position = index,
                        )
                    }
                    if (trackSnapshots.isNotEmpty()) {
                        remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                        dailyMixCount++
                    }
                    Log.d(TAG, "fetchSpotifyPlaylists: saved ${trackSnapshots.size} tracks for '${mix.name}'")
                } catch (e: SpotifyApiException) {
                    // Re-throw SpotifyApiException so doWork() can decide to retry
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSpotifyPlaylists: failed to fetch tracks for mix '${mix.name}'", e)
                    // Continue with next mix rather than aborting
                }
            }
        } catch (e: SpotifyApiException) {
            throw e // Let doWork handle retries
        } catch (e: Exception) {
            Log.e(TAG, "fetchSpotifyPlaylists: daily mixes enumeration failed (sp_dc issue), continuing", e)
        }

        // Fetch Liked Songs (sp_dc dependent -- may return empty if GraphQL fails).
        try {
            val likedSongs = spotifyApiClient.getLikedSongs()
            Log.d(TAG, "fetchSpotifyPlaylists: found ${likedSongs.size} liked songs")

            if (likedSongs.isNotEmpty()) {
                val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
                    RemotePlaylistSnapshotEntity(
                        syncId = syncId,
                        source = MusicSource.SPOTIFY,
                        sourcePlaylistId = "spotify_liked_songs",
                        playlistName = "Liked Songs",
                        playlistType = PlaylistType.LIKED_SONGS,
                        trackCount = likedSongs.size,
                    )
                )

                val trackSnapshots = likedSongs.mapIndexedNotNull { index, item ->
                    val track = item.track ?: return@mapIndexedNotNull null
                    RemoteTrackSnapshotEntity(
                        syncId = syncId,
                        snapshotPlaylistId = likedPlaylistId,
                        title = track.name,
                        artist = track.artists.joinToString(", ") { it.name },
                        album = track.album?.name,
                        durationMs = track.duration_ms,
                        spotifyUri = track.uri,
                        albumArtUrl = track.album?.images?.firstOrNull()?.url,
                        position = index,
                    )
                }
                if (trackSnapshots.isNotEmpty()) {
                    remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                    likedSongCount = trackSnapshots.size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSpotifyPlaylists: liked songs fetch failed (sp_dc issue), continuing", e)
        }

        Log.d(TAG, "fetchSpotifyPlaylists: completed -- $dailyMixCount daily mixes, $likedSongCount liked songs")
    }

    /**
     * Fetches Home Mixes and Liked Songs from YouTube Music and writes snapshot rows.
     */
    private suspend fun fetchYouTubePlaylists(syncId: Long) {
        try {
            // Fetch Home Mixes.
            val homeMixes = ytMusicApiClient.getHomeMixes()
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

                val tracks = ytMusicApiClient.getPlaylistTracks(mix.playlistId)
                val trackSnapshots = tracks.mapIndexed { position, track ->
                    RemoteTrackSnapshotEntity(
                        syncId = syncId,
                        snapshotPlaylistId = playlistSnapshotId,
                        title = track.title,
                        artist = track.artists,
                        album = track.album,
                        durationMs = track.durationMs ?: 0L,
                        youtubeId = track.videoId,
                        albumArtUrl = track.thumbnailUrl,
                        position = position,
                    )
                }
                if (trackSnapshots.isNotEmpty()) {
                    remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                }
            }

            // Fetch Liked Songs.
            val likedSongs = ytMusicApiClient.getLikedSongs()
            if (likedSongs.isNotEmpty()) {
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
                        albumArtUrl = track.thumbnailUrl,
                        position = position,
                    )
                }
                if (trackSnapshots.isNotEmpty()) {
                    remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube Music fetch failed, continuing with other services", e)
        }
    }
}
