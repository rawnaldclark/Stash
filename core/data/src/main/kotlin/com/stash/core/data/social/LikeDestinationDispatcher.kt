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
 * **Never remove a like Stash didn't make.** Before a like goes out, the
 * service is asked whether the song is liked there already ([LikedThereCheck]):
 *  - yes: nothing is sent, and the song is recorded as the service's own
 *    ([LikeOwnership]);
 *  - no: the like is sent, and it is Stash's, so an un-heart may take it back;
 *  - can't tell: the like is sent (the heart asked for it) and recorded as the
 *    service's own, so an un-heart never removes it.
 * Every case stamps the row's `*_saved_at` (a second heart neither asks nor
 * sends again), the ownership record always written first. An unlike fires only
 * on a row stamped at or after [LikeOwnership.stampEpoch] that isn't recorded as
 * the service's own; anything else is left on the service, its stamp cleared so
 * a later heart asks again. Builds before the epoch stamped every like they sent,
 * also songs already in the listener's Spotify Liked Songs, and un-hearting one
 * deleted their own years-old like.
 *
 * Parallelism: three destinations fire concurrently. Total time =
 * slowest single-destination time, not sum.
 */
@Singleton
class LikeDestinationDispatcher internal constructor(
    private val spotifyLibraryClient: SpotifyLibraryApiClient,
    private val ytMusicLibraryClient: YtMusicLibraryApiClient,
    private val stashLikedRepository: StashLikedPlaylistRepository,
    private val trackDao: TrackDao,
    private val lastFmApiClient: com.stash.core.data.lastfm.LastFmApiClient,
    private val lastFmSessionPreference: com.stash.core.data.lastfm.LastFmSessionPreference,
    private val likedThere: LikedThereCheck,
    private val ownership: LikeOwnership,
) {
    @Inject constructor(
        spotifyLibraryClient: SpotifyLibraryApiClient,
        ytMusicLibraryClient: YtMusicLibraryApiClient,
        stashLikedRepository: StashLikedPlaylistRepository,
        trackDao: TrackDao,
        lastFmApiClient: com.stash.core.data.lastfm.LastFmApiClient,
        lastFmSessionPreference: com.stash.core.data.lastfm.LastFmSessionPreference,
        likedThere: ServiceLikedThereCheck,
        ownership: LikeOwnership,
    ) : this(
        spotifyLibraryClient,
        ytMusicLibraryClient,
        stashLikedRepository,
        trackDao,
        lastFmApiClient,
        lastFmSessionPreference,
        likedThere as LikedThereCheck,
        ownership,
    )

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
                    likeUnlessThere(
                        track, dest, likedThere.onSpotify(track),
                        send = { spotifyLibraryClient.saveTracks(listOf(uri)) },
                        stamp = { trackDao.markSpotifySaved(track.id, it) },
                    )
                }
                Destination.YT_MUSIC -> {
                    val videoId = track.youtubeId ?: throw NoYouTubeIdException()
                    likeUnlessThere(
                        track, dest, likedThere.onYouTubeMusic(videoId),
                        send = { ytMusicLibraryClient.likeVideo(videoId) },
                        stamp = { trackDao.markYtMusicSaved(track.id, it) },
                    )
                }
                Destination.LAST_FM -> {
                    // No platform id needed: Last.fm matches on artist + title, so
                    // there is nothing to resolve first.
                    val session = lastFmSessionPreference.session.first()
                        ?: throw NoLastFmSessionException()
                    val artist = scrobbleArtist(track.artist)
                    likeUnlessThere(
                        track, dest, likedThere.onLastFm(artist, track.title),
                        send = {
                            lastFmApiClient.setLoved(
                                sessionKey = session.sessionKey,
                                artist = artist,
                                track = track.title,
                                loved = true,
                            ).getOrThrow()
                        },
                        stamp = { trackDao.markLastFmLoved(track.id, it) },
                    )
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
        if (dest != Destination.STASH) {
            keptReason(track, dest)?.let { why ->
                Log.d(TAG, "skip unlike $dest for track ${track.id} — $why")
                // Stash no longer holds it as liked there: a later heart asks the service again instead of skipping as saved
                bookkeep("clear $dest stamp") { clearStamp(track.id, dest) }
                return Result.success(Unit)
            }
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
                    // Only reached when lastFmLovedAt is set and the love isn't
                    // recorded as the listener's own: a love Stash itself created. A
                    // love the listener made years ago is never stamped
                    // (likeUnlessThere), so it is not ours to delete.
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

    /**
     * Sends a like unless [there] says the service has it already, then stamps the row ([stamp] takes the time). See the class
     * KDoc for the three cases. Bookkeeping failures are logged, never the like's failure, and always fall on the safe side: the
     * stamp is written only after the epoch and, for the service's own like, after its ownership record, so an unwritten record
     * means no stamp (a later heart asks again), never a stamp an un-heart could take away.
     */
    private suspend fun likeUnlessThere(
        track: Track,
        dest: Destination,
        there: LikedThere,
        send: suspend () -> Unit,
        stamp: suspend (Long) -> Unit,
    ) {
        if (there == LikedThere.YES) {
            Log.d(TAG, "skip $dest for track ${track.id} — already liked there; it stays the listener's own")
        } else {
            send()
        }
        val now = System.currentTimeMillis()
        if (there == LikedThere.NO) {
            bookkeep("stamp $dest like") {
                ownership.stampEpoch(now)
                stamp(now)
            }
            bookkeep("clear $dest ownership") { ownership.clear(dest, track.id) }
        } else {
            bookkeep("record $dest like as the listener's own") {
                ownership.markLikedByService(dest, track.id)
                ownership.stampEpoch(now)
                stamp(now)
            }
        }
    }

    private suspend fun bookkeep(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "$what failed", t)
        }
    }

    /**
     * Why the like on [dest] stays on the service, or null when it is Stash's to take back: stamped at or after the epoch and
     * not recorded as the listener's own. Anything that can't be read keeps it (never delete on doubt).
     */
    private suspend fun keptReason(track: Track, dest: Destination): String? = try {
        val stamp = stampOf(track, dest)
        val epoch = ownership.epoch()
        when {
            stamp == null -> "never saved by Stash"
            epoch == null || stamp < epoch -> "stamped by a build that never asked the service first"
            ownership.isLikedByService(dest, track.id) -> "the like there is the listener's own"
            else -> null
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Log.w(TAG, "couldn't read whose $dest like track ${track.id} is; leaving it alone", t)
        "whose it is couldn't be read"
    }

    private fun stampOf(track: Track, dest: Destination): Long? = when (dest) {
        Destination.STASH -> track.stashLikedAt
        Destination.SPOTIFY -> track.spotifySavedAt
        Destination.YT_MUSIC -> track.ytMusicSavedAt
        Destination.LAST_FM -> track.lastFmLovedAt
    }

    private suspend fun clearStamp(trackId: Long, dest: Destination) {
        when (dest) {
            Destination.SPOTIFY -> trackDao.clearSpotifySaved(trackId)
            Destination.YT_MUSIC -> trackDao.clearYtMusicSaved(trackId)
            Destination.LAST_FM -> trackDao.clearLastFmLoved(trackId)
            Destination.STASH -> Unit
        }
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
