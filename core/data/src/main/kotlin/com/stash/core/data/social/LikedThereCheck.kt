package com.stash.core.data.social

import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.SyncMode
import com.stash.core.model.Track
import com.stash.data.ytmusic.InnerTubeClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Whether a song is already liked on a service, asked just before Stash likes it there. */
enum class LikedThere {
    /** Already liked there: the like is the listener's own, so Stash neither sends it again nor ever removes it. */
    YES,

    /** Not liked there: the like Stash sends is Stash's, and an un-heart in Stash may take it back. */
    NO,

    /** Couldn't tell. Stash still sends the like (the heart asked for it), but treats it as not its own: never removed. */
    UNKNOWN,
}

/** The "liked there already?" question, one way per service. A seam for [LikeDestinationDispatcher]'s tests. */
interface LikedThereCheck {
    suspend fun onSpotify(track: Track): LikedThere
    suspend fun onYouTubeMusic(videoId: String): LikedThere
    suspend fun onLastFm(artist: String, title: String): LikedThere
}

/**
 * Each service asked the way it can be asked, without a new private endpoint:
 *  - **YouTube Music**: live, from the `next` answer the web client gets when a song opens. Signed in, it carries the profile's
 *    own like button state ([InnerTubeClient.likeStatusOf]).
 *  - **Last.fm**: live, official: `track.getInfo` with the username answers `userloved`.
 *  - **Spotify**: there is no "is it saved?" call Stash can make without guessing a new private one, so it reads the synced
 *    Liked Songs in this phone's library. In it: liked there, when the copy is under [SPOTIFY_FRESH_MS] old and mirrors
 *    removals (the Refresh sync mode). Not in it: not liked there only when the last fetch was complete
 *    ([SpotifyLikedSongsFetch]) and under [SPOTIFY_COMPLETE_MS] old. Anything else is [LikedThere.UNKNOWN], which never
 *    deletes anything.
 *
 * Every failure is [LikedThere.UNKNOWN]: never delete on uncertainty.
 */
@Singleton
class ServiceLikedThereCheck @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val innerTubeClient: InnerTubeClient,
    private val lastFmApiClient: LastFmApiClient,
    private val lastFmSessionPreference: LastFmSessionPreference,
    private val likedSongsFetch: SpotifyLikedSongsFetch,
) : LikedThereCheck {

    override suspend fun onSpotify(track: Track): LikedThere = guarded("Spotify", track.id) {
        spotifyAnswer(
            lastSyncedAt = playlistDao.spotifyLikedSongsLastSynced(),
            completeAt = likedSongsFetch.completeAt(),
            inLikedSongs = playlistDao.isInSpotifyLikedSongs(track.id, track.spotifyUri),
            mirrorsRemovals = syncPreferencesManager.spotifySyncMode.first() == SyncMode.REFRESH,
            now = System.currentTimeMillis(),
        )
    }

    override suspend fun onYouTubeMusic(videoId: String): LikedThere = guarded("YouTube Music", videoId) {
        when (innerTubeClient.likeStatusOf(videoId)) {
            "LIKE" -> LikedThere.YES
            "INDIFFERENT", "DISLIKE" -> LikedThere.NO
            else -> LikedThere.UNKNOWN
        }
    }

    override suspend fun onLastFm(artist: String, title: String): LikedThere = guarded("Last.fm", title) {
        val username = lastFmSessionPreference.session.first()?.username ?: return@guarded LikedThere.UNKNOWN
        when (lastFmApiClient.getTrackInfo(artist = artist, title = title, username = username).getOrNull()?.userLoved) {
            true -> LikedThere.YES
            false -> LikedThere.NO
            null -> LikedThere.UNKNOWN
        }
    }

    private suspend fun guarded(service: String, what: Any, ask: suspend () -> LikedThere): LikedThere = try {
        ask()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't tell whether $what is liked on $service: ${e.javaClass.simpleName}")
        LikedThere.UNKNOWN
    }

    companion object {
        private const val TAG = "LikedThereCheck"

        /**
         * How old the synced Spotify Liked Songs may be and still answer. A sync that stopped (Liked Songs switched off, an
         * expired sign-in) leaves a copy that can't know about likes made on Spotify since, and "not in it" would then make Stash
         * claim, and later remove, a like that was the listener's own.
         */
        const val SPOTIFY_FRESH_MS = 7L * 24 * 60 * 60 * 1_000

        /**
         * How old the last complete fetch may be for "not in it" to mean "not liked there". "No" is the answer that lets Stash
         * take a like back later, so it needs a copy that holds every song and is recent: a song liked on Spotify since is
         * missing from any copy, and only the last hour's are risked.
         */
        const val SPOTIFY_COMPLETE_MS = 60L * 60 * 1_000

        /**
         * The Spotify answer from the synced copy. In the song: liked there (YES) when the copy is fresh and the sync mirrors
         * removals, else it may be a song since unliked on Spotify that Accumulate kept (UNKNOWN: sent, never removed). Not in
         * it: NO only from a complete fetch under [SPOTIFY_COMPLETE_MS] old ([completeAt]), else UNKNOWN.
         */
        internal fun spotifyAnswer(
            lastSyncedAt: Long?,
            completeAt: Long?,
            inLikedSongs: Boolean,
            mirrorsRemovals: Boolean,
            now: Long,
        ): LikedThere = when {
            inLikedSongs ->
                if (mirrorsRemovals && lastSyncedAt != null && now - lastSyncedAt in 0..SPOTIFY_FRESH_MS) LikedThere.YES else LikedThere.UNKNOWN
            completeAt != null && now - completeAt in 0..SPOTIFY_COMPLETE_MS -> LikedThere.NO
            else -> LikedThere.UNKNOWN
        }
    }
}
