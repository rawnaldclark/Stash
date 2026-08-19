package com.stash.core.data.social

import android.util.Log
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.social.spotify.SpotifyLibraryApiClient
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.social.ytmusic.YtMusicLibraryApiClient
import com.stash.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

class NoSpotifyUriException : Exception("Track has no spotifyUri")
class NoYouTubeIdException : Exception("Track has no youtubeId")
class NoLastFmSessionException : Exception("Last.fm is not connected")

/**
 * v0.9.13: Stateless fan-out for Like operations. Both auto-save
 * (single-destination [Destination.SPOTIFY]) and manual heart
 * (caller-configured set) funnel through this single entry point.
 *
 * Per-destination dedup: skips destinations where the corresponding
 * `*_saved_at` timestamp is already set on the track. Spotify's
 * `PUT /v1/me/tracks` is idempotent at the API level too — even a
 * spurious second call would be safe — but the dedup avoids
 * unnecessary network round-trips.
 *
 * Parallelism: three destinations fire concurrently. Total time =
 * slowest single-destination time, not sum.
 */
@Singleton
class LikeDestinationDispatcher @Inject constructor(
    private val spotifyLibraryClient: SpotifyLibraryApiClient,
    private val ytMusicLibraryClient: YtMusicLibraryApiClient,
    private val stashLikedRepository: StashLikedPlaylistRepository,
    private val trackDao: TrackDao,
    private val lastFmApiClient: com.stash.core.data.lastfm.LastFmApiClient,
    private val lastFmSessionPreference: com.stash.core.data.lastfm.LastFmSessionPreference,
) {
    suspend fun like(
        track: Track,
        destinations: Set<Destination>,
    ): Map<Destination, Result<Unit>> = coroutineScope {
        if (destinations.isEmpty()) return@coroutineScope emptyMap()
        destinations.associateWith { dest ->
            async { fireDestination(track, dest) }
        }.mapValues { it.value.await() }
    }

    private suspend fun fireDestination(track: Track, dest: Destination): Result<Unit> {
        if (alreadySaved(track, dest)) {
            Log.d(TAG, "skip $dest for track ${track.id} — already saved")
            return Result.success(Unit)
        }

        return try {
            when (dest) {
                Destination.STASH -> {
                    stashLikedRepository.add(track.id)
                }
                Destination.SPOTIFY -> {
                    val uri = track.spotifyUri ?: throw NoSpotifyUriException()
                    spotifyLibraryClient.saveTracks(listOf(uri))
                    runCatching { trackDao.markSpotifySaved(track.id, System.currentTimeMillis()) }
                        .onFailure { Log.w(TAG, "markSpotifySaved failed for ${track.id}", it) }
                }
                Destination.YT_MUSIC -> {
                    val videoId = track.youtubeId ?: throw NoYouTubeIdException()
                    ytMusicLibraryClient.likeVideo(videoId)
                    runCatching { trackDao.markYtMusicSaved(track.id, System.currentTimeMillis()) }
                        .onFailure { Log.w(TAG, "markYtMusicSaved failed for ${track.id}", it) }
                }
                Destination.LAST_FM -> {
                    // No platform id needed: Last.fm matches on artist + title, so
                    // there is nothing to resolve first.
                    val session = lastFmSessionPreference.session.first()
                        ?: throw NoLastFmSessionException()
                    lastFmApiClient.setLoved(
                        sessionKey = session.sessionKey,
                        artist = scrobbleArtist(track.artist),
                        track = track.title,
                        loved = true,
                    ).getOrThrow()
                    runCatching { trackDao.markLastFmLoved(track.id, System.currentTimeMillis()) }
                        .onFailure { Log.w(TAG, "markLastFmLoved failed for ${track.id}", it) }
                }
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * v0.9.52 symmetric un-like. Mirrors [like], with the inverted
     * guard: a destination only fires when its `*_saved_at` timestamp
     * is non-null (never un-Like what Stash never Liked). A successful
     * remote remove clears the timestamp so a future re-heart re-fires
     * the save. Failures leave the column untouched → organic retry.
     */
    suspend fun unlike(
        track: Track,
        destinations: Set<Destination>,
    ): Map<Destination, Result<Unit>> = coroutineScope {
        if (destinations.isEmpty()) return@coroutineScope emptyMap()
        destinations.associateWith { dest ->
            async { fireUnlikeDestination(track, dest) }
        }.mapValues { it.value.await() }
    }

    private suspend fun fireUnlikeDestination(track: Track, dest: Destination): Result<Unit> {
        if (!alreadySaved(track, dest)) {
            Log.d(TAG, "skip unlike $dest for track ${track.id} — never saved by Stash")
            return Result.success(Unit)
        }

        return try {
            when (dest) {
                Destination.STASH -> {
                    stashLikedRepository.remove(track.id)
                }
                Destination.SPOTIFY -> {
                    val uri = track.spotifyUri ?: throw NoSpotifyUriException()
                    spotifyLibraryClient.removeTracks(listOf(uri))
                    runCatching { trackDao.clearSpotifySaved(track.id) }
                        .onFailure { Log.w(TAG, "clearSpotifySaved failed for ${track.id}", it) }
                }
                Destination.YT_MUSIC -> {
                    val videoId = track.youtubeId ?: throw NoYouTubeIdException()
                    ytMusicLibraryClient.removeLike(videoId)
                    runCatching { trackDao.clearYtMusicSaved(track.id) }
                        .onFailure { Log.w(TAG, "clearYtMusicSaved failed for ${track.id}", it) }
                }
                Destination.LAST_FM -> {
                    // Only reached when lastFmLovedAt is set, i.e. only for a love
                    // Stash itself created — a love the user made years ago is not
                    // ours to delete.
                    val session = lastFmSessionPreference.session.first()
                        ?: throw NoLastFmSessionException()
                    lastFmApiClient.setLoved(
                        sessionKey = session.sessionKey,
                        artist = scrobbleArtist(track.artist),
                        track = track.title,
                        loved = false,
                    ).getOrThrow()
                    runCatching { trackDao.clearLastFmLoved(track.id) }
                        .onFailure { Log.w(TAG, "clearLastFmLoved failed for ${track.id}", it) }
                }
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Applies the same "only first artist" preference the scrobbler uses.
     *
     * Loves and scrobbles must agree. Last.fm matches a love against its own catalog
     * entry, so submitting "Calvin Harris" for the scrobble and "Calvin Harris, Dua
     * Lipa" for the love lands them on different entries — or the love on nothing at
     * all. That split is precisely what the toggle exists to prevent, so it has to
     * govern both writes, not just one.
     *
     * Reuses [com.stash.core.common.primaryArtist] rather than reimplementing
     * the split: one definition of "primary artist", one place to fix it when the
     * "Tyler, The Creator" limitation is eventually addressed.
     */
    private suspend fun scrobbleArtist(artist: String): String =
        if (lastFmSessionPreference.firstArtistOnly.first() == true) {
            artist.primaryArtist()
        } else {
            artist
        }

    private fun alreadySaved(track: Track, dest: Destination): Boolean = when (dest) {
        Destination.STASH -> track.stashLikedAt != null
        Destination.SPOTIFY -> track.spotifySavedAt != null
        Destination.YT_MUSIC -> track.ytMusicSavedAt != null
        Destination.LAST_FM -> track.lastFmLovedAt != null
    }

    companion object {
        private const val TAG = "LikeDestinationDispatcher"
    }
}
